package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.NetAmounts;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import com.intermarche.valuation.engine.ProductScopedDiscount;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Factory for the {@code CARD_PROMOTION_DISCOUNT} advantage: card-borne promotions served by
 * imfid and transmitted in the basket (spec §3).
 * <p>
 * This is a canonical {@link AdvantageApplierFactory}: it owns a discriminator, a JSON schema,
 * and appliers whose {@link AdvantageApplier#getConfiguration()} is non-null. Its single
 * difference with the fourteen other factories is the <em>source</em> of the configuration: it
 * does not load the {@link Offer} table through {@code getOffers(...)}; it synthesises a
 * transient {@link Offer} — never persisted — from each {@code IMMEDIATE_DISCOUNT} entry of
 * {@code basket.cardPromotions}, and that synthetic configuration then travels exactly the same
 * path as every other one: schema validation through {@link #processSpecification}, and the C2
 * arbitration read through {@link AdvantageApplier#getConfiguration()}.
 * <p>
 * The specification of a synthetic configuration carries neither a {@code trigger} nor an
 * {@code arbitration} block, so the arbitration defaults apply: {@code AT_TOTAL} (a net-price
 * reduction is measured after the store advantages), priority 500, cumulable, no trigger. The
 * day the retailer wants to prioritise or de-cumulate card promotions, the synthesis adds the
 * block — the engine does not change.
 * <p>
 * Compute semantics mirror §3.1 of the addendum: the base is the available valued lines
 * carrying exactly the target EAN, net of the discounts already retained ({@link NetAmounts});
 * a {@code PERCENT} reduction is {@code value × net base}; an {@code AMOUNT} reduction is
 * {@code value × unit count} (a unit line counts its quantity, a weighed line counts one),
 * capped at the net base. The reduction is rounded to the cent once per promotion and then
 * distributed pro-rata (tax included) across the contributing lines, the residue landing on the
 * last one.
 */
@ApplicationScoped
public class CardPromotionDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * Logger for the silent {@code CAGNOTTE} skips (spec §2.1): traced in debug, never applied.
     */
    private static final Logger LOGGER = Logger.getLogger(CardPromotionDiscountFactory.class);

    /**
     * ObjectMapper used to build the synthetic specification JSON.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "CARD_PROMOTION_DISCOUNT";

    /**
     * The {@code ruleCode} prefix aligned on the imfid convention {@code CARD_PROMO:<ean>}, so
     * till, engine and loyalty all speak the same code.
     */
    public static final String CODE_PREFIX = "CARD_PROMO:";

    /**
     * JSON Schema for one synthetic card-promotion specification.
     * <p>
     * It is the schema of the transient configuration, distinct from the basket's
     * {@code cardPromotions} block: the funnel injects the shared {@code trigger},
     * {@code applicationMoment} and {@code arbitration} fragments into it, so a future
     * synthesis carrying an arbitration block already validates.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Card Promotion Discount Offer Specification",
      "description": "A card-borne promotion synthesised from the basket, never persisted.",
      "type": "object",
      "required": ["targetEan", "promotionType", "value"],
      "properties": {
        "targetEan": {
          "type": "string",
          "minLength": 1,
          "description": "The EAN the promotion is attached to.",
          "x-widget": "ean",
          "x-label": "Product"
        },
        "promotionType": {
          "type": "string",
          "enum": ["PERCENT", "AMOUNT"],
          "description": "PERCENT = fraction of the net base; AMOUNT = euros per unit.",
          "x-label": "Promotion type"
        },
        "value": {
          "type": "number",
          "exclusiveMinimum": 0,
          "description": "PERCENT: a fraction in (0, 1]. AMOUNT: euros per unit.",
          "x-label": "Value"
        },
        "label": {
          "type": "string",
          "description": "Optional decorative product name from imfid.",
          "x-label": "Label"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the {@code "CARD_PROMOTION_DISCOUNT"} discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing one synthetic card-promotion specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per {@code IMMEDIATE_DISCOUNT} card promotion of the basket (spec §3).
     * <p>
     * The uniqueness of the promotion EANs is validated first (spec §2.3): a duplicate is a
     * caller error and is never silently arbitrated. Each eligible entry is turned into a
     * transient {@link Offer} — never persisted, never loaded through the in-force finders —
     * and validated through the canonical funnel; a {@code CAGNOTTE} entry is traced in debug
     * and skipped (spec §2.1).
     *
     * @param basketEvaluation the basket evaluation (store and basket context).
     * @return a collection of {@link CardPromotionDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a promotion EAN is duplicated or a synthetic
     *                                  specification violates the schema.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create Card Promotion Discount appliers without a valid basket.");
        // Spec §2.3: reject a duplicate (card, EAN) couple as a caller error rather than
        // silently double-applying it. Propagated as a request-level error, not a
        // ConfigurationException, so it is never swallowed fail-closed.
        basket.validateCardPromotions();
        if (basket.cardPromotions == null) {
            return appliers;
        }
        Store store = basketEvaluation.getStore();
        for (Basket.CardPromotion promotion : basket.cardPromotions) {
            processPromotion(promotion, appliers, basket, store);
        }
        return appliers;
    }

    /**
     * Synthesises and validates one card promotion, adding its applier when eligible.
     *
     * @param promotion the card promotion entry.
     * @param appliers  the list receiving the created applier.
     * @param basket    the basket, for the card number and the sandbox score.
     * @param store     the store, for the reference price lookups of the sandbox score.
     */
    private void processPromotion(Basket.CardPromotion promotion, List<AdvantageApplier> appliers,
                                  Basket basket, Store store) {
        if (promotion == null || promotion.ean == null) {
            return;
        }
        if (!promotion.isImmediateDiscount()) {
            // Spec §2.1: the engine is the second line of defence — a CAGNOTTE entry belongs to
            // imfid and is silently ignored, traced in debug, never applied.
            LOGGER.debugf("Ignoring CAGNOTTE card promotion for EAN '%s' (spec §2.1)", promotion.ean);
            return;
        }
        Offer offer = synthesiseOffer(promotion);
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            String ean = spec.get("targetEan").asText();
            String promotionType = spec.get("promotionType").asText();
            BigDecimal value = spec.get("value").decimalValue();
            String label = spec.hasNonNull("label") ? spec.get("label").asText() : null;
            Product product = Product.findByEan(ean);
            CardPromotionDiscountApplier applier = new CardPromotionDiscountApplier(
                    offer.code, basket.cardNumber, ean, product, promotionType, value, label, basket, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * Builds the transient, never-persisted {@link Offer} for one card promotion (spec §3).
     * <p>
     * The code is aligned on the imfid {@code ruleCode} convention {@code CARD_PROMO:<ean>}; the
     * specification carries the entry as {@code targetEan}/{@code promotionType}/{@code value}
     * (and {@code label} when present). The object is never persisted, so no JPA callback
     * fires; it lives for the duration of one evaluation.
     *
     * @param promotion the card promotion entry.
     * @return the transient offer.
     */
    private Offer synthesiseOffer(Basket.CardPromotion promotion) {
        Offer offer = new Offer();
        offer.code = CODE_PREFIX + promotion.ean;
        offer.type = OFFER_TYPE;
        ObjectNode spec = MAPPER.createObjectNode();
        spec.put("targetEan", promotion.ean);
        spec.put("promotionType", promotion.promotionType);
        spec.put("value", promotion.value);
        if (promotion.label != null) {
            spec.put("label", promotion.label);
        }
        offer.specification = spec.toString();
        return offer;
    }

    /**
     * Applier of one synthetic card promotion.
     */
    public static class CardPromotionDiscountApplier implements AdvantageApplier {

        /**
         * Reduction kind: a fraction of the net base.
         */
        private static final String PERCENT = "PERCENT";

        /**
         * The offer code ({@code CARD_PROMO:<ean>}), used in labels.
         */
        private final String code;

        /**
         * The loyalty card number, echoed for ticket traceability (spec §3); may be null.
         */
        private final String cardNumber;

        /**
         * The targeted EAN.
         */
        private final String targetEan;

        /**
         * The targeted product resolved from the EAN; null when the EAN is unknown to the
         * catalog, in which case the promotion produces nothing (spec §5.6).
         */
        private final Product product;

        /**
         * The promotion kind ({@code PERCENT} or {@code AMOUNT}).
         */
        private final String promotionType;

        /**
         * The promotion value: a fraction for {@code PERCENT}, euros per unit for {@code AMOUNT}.
         */
        private final BigDecimal value;

        /**
         * The optional decorative label, echoed in the output type when present.
         */
        private final String label;

        /**
         * The configuration (the transient {@link Offer}) this applier was built from, set by
         * the factory right after construction. Never null in production; left null when an
         * applier is built directly (as in unit tests), which the arbitration reads as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS} with default parameters.
         */
        private Offer configuration;

        /**
         * The sandbox efficiency score: the reduction the promotion would produce on the current
         * basket at the reference price. Higher is arbitrated first.
         */
        private final double efficiencyScore;

        /**
         * Creates the applier and computes its sandbox efficiency score.
         *
         * @param code          the offer code ({@code CARD_PROMO:<ean>}).
         * @param cardNumber    the loyalty card number; may be null.
         * @param targetEan     the targeted EAN.
         * @param product       the targeted product; may be null when the EAN is unknown.
         * @param promotionType the promotion kind ({@code PERCENT} or {@code AMOUNT}).
         * @param value         the promotion value.
         * @param label         the optional decorative label.
         * @param basket        the basket, scanned for the sandbox score; may be null.
         * @param store         the store, for reference price lookups; may be null.
         */
        public CardPromotionDiscountApplier(String code, String cardNumber, String targetEan,
                                            Product product, String promotionType, BigDecimal value,
                                            String label, Basket basket, Store store) {
            this.code = code;
            this.cardNumber = cardNumber;
            this.targetEan = targetEan;
            this.product = product;
            this.promotionType = promotionType;
            this.value = value;
            this.label = label;
            this.efficiencyScore = computeSandboxScore(basket, store);
        }

        /**
         * Computes the sandbox efficiency score: the reduction the promotion would produce on the
         * basket lines carrying the target EAN, valued at the reference price.
         *
         * @param basket the basket to scan; may be null.
         * @param store  the store for the reference price lookups; may be null.
         * @return the sandbox reduction, never negative; zero when nothing can be valued.
         */
        private double computeSandboxScore(Basket basket, Store store) {
            if (basket == null || basket.items == null || store == null || product == null
                    || product.id == null) {
                return 0.0;
            }
            Price price = Price.findActivePriceAtDate(
                    product.id, store.id, DateTimeProvider.now(), PriceUsage.BASE_FOR_DISCOUNT);
            if (price == null) {
                return 0.0;
            }
            BigDecimal baseTtc = BigDecimal.ZERO;
            BigDecimal units = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                if (!targetEan.equals(item.produceEan) || item.quantity == null) {
                    continue;
                }
                baseTtc = baseTtc.add(AmountEvaluation.getAmount(product, price, item.quantity).amountIncludingTax);
                units = units.add(unitCount(item.quantity));
            }
            if (baseTtc.signum() <= 0) {
                return 0.0;
            }
            BigDecimal reduction = reductionFor(baseTtc, units);
            return reduction.doubleValue();
        }

        /**
         * Returns the unit count a quantity contributes to an {@code AMOUNT} promotion (spec
         * §3, imfid §22.2): a unit product counts its quantity, a weighed or volume line counts
         * one — the same integer logic as the gift-item selection.
         *
         * @param quantity the line quantity.
         * @return the quantity for a unit product, one otherwise.
         */
        private BigDecimal unitCount(BigDecimal quantity) {
            return product != null && product.productType == ProductType.UNIT
                    ? quantity : BigDecimal.ONE;
        }

        /**
         * Computes the total reduction (tax included) for a base and unit count, rounded to the
         * cent once and capped at the base so a line never goes negative (spec §3).
         *
         * @param baseTtc the net base, tax included.
         * @param units   the summed unit count (used by {@code AMOUNT}).
         * @return the reduction, tax included, in {@code [0, baseTtc]}.
         */
        private BigDecimal reductionFor(BigDecimal baseTtc, BigDecimal units) {
            BigDecimal reduction;
            if (PERCENT.equals(promotionType)) {
                reduction = baseTtc.multiply(value).setScale(2, RoundingMode.HALF_UP);
            } else {
                reduction = value.multiply(units).setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal cappedBase = baseTtc.setScale(2, RoundingMode.HALF_UP);
            return reduction.min(cappedBase);
        }

        /**
         * Tells whether this promotion can relate to the given offer applier.
         * <p>
         * It relates to every product-aware applier covering the targeted product; registration
         * switches those standard lines to the reference price, like every other discount, so
         * the net-price reduction is measured on the reference base.
         *
         * @param offerApplier the offer applier to check.
         * @return true when the applier covers the targeted product.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (product == null || !(offerApplier instanceof ProductAwareOfferApplier productApplier)) {
                return false;
            }
            return productApplier.isApplicable(product);
        }

        /**
         * Returns the sandbox efficiency score of this applier.
         *
         * @return the reduction the promotion would produce on the current basket at the
         *         reference price.
         */
        @Override
        public double getEfficiencyScore() {
            return efficiencyScore;
        }

        /**
         * Returns the configuration this applier was built from.
         *
         * @return the transient offer, or null when the applier was built without one.
         */
        @Override
        public Offer getConfiguration() {
            return configuration;
        }

        /**
         * Applies the card promotion to the evaluation (spec §3).
         * <p>
         * The base is every available product-aware offer application carrying the target EAN,
         * net of the discounts already retained ({@link NetAmounts#netProductTtc}). The total
         * reduction is computed off the summed base and rounded to the cent once, then
         * distributed pro-rata (tax included) across the contributing lines, the residue landing
         * on the last one. An empty or fully-discounted base yields nothing, silently.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return the discount applications, empty when no line is eligible.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            if (product == null || evaluation.getOffers() == null) {
                return applications;
            }
            List<OfferApplication> targets = new ArrayList<>();
            List<BigDecimal> netTtcs = new ArrayList<>();
            BigDecimal baseTtc = BigDecimal.ZERO;
            BigDecimal units = BigDecimal.ZERO;
            BigDecimal rate = BigDecimal.ZERO;
            for (OfferApplication app : evaluation.getAvailableOffers()) {
                if (!(app instanceof ProductAwareOfferApplication productAwareApp)) {
                    continue;
                }
                BigDecimal quantity = productAwareApp.getProductQuantity(product);
                if (quantity.signum() <= 0) {
                    continue;
                }
                AmountEvaluation amount = productAwareApp.getProductAmount(product);
                if (amount == null || amount.amountIncludingTax == null) {
                    continue;
                }
                BigDecimal netTtc = NetAmounts.netProductTtc(evaluation, app, targetEan,
                        amount.amountIncludingTax).setScale(2, RoundingMode.HALF_UP);
                if (netTtc.signum() <= 0) {
                    continue;
                }
                targets.add(app);
                netTtcs.add(netTtc);
                baseTtc = baseTtc.add(netTtc);
                units = units.add(unitCount(quantity));
                rate = amount.vatRate == null ? BigDecimal.ZERO : amount.vatRate;
            }
            if (targets.isEmpty() || baseTtc.signum() <= 0) {
                return applications;
            }
            BigDecimal discountTtc = reductionFor(baseTtc, units);
            if (discountTtc.signum() <= 0) {
                return applications;
            }
            BigDecimal discountHt = discountTtc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            List<BigDecimal> sharesTtc = distribute(discountTtc, netTtcs, baseTtc);
            List<BigDecimal> sharesHt = distribute(discountHt, netTtcs, baseTtc);
            for (int i = 0; i < targets.size(); i++) {
                AmountEvaluation amount = new AmountEvaluation(sharesHt.get(i), sharesTtc.get(i), rate);
                applications.add(new CardPromotionDiscountApplication(
                        code, targetEan, label, cardNumber, targets.get(i), amount));
            }
            return applications;
        }

        /**
         * Distributes a total across weighted lines, rounding each to the cent and putting the
         * residue on the last line so the shares sum exactly to the total (the house pattern).
         *
         * @param total     the total to distribute.
         * @param weights   the per-line weights (the net TTC bases).
         * @param weightSum the sum of the weights, never zero here.
         * @return the per-line shares, summing exactly to {@code total}.
         */
        private List<BigDecimal> distribute(BigDecimal total, List<BigDecimal> weights, BigDecimal weightSum) {
            List<BigDecimal> shares = new ArrayList<>();
            BigDecimal accumulated = BigDecimal.ZERO;
            for (int i = 0; i < weights.size(); i++) {
                BigDecimal share;
                if (i < weights.size() - 1) {
                    share = total.multiply(weights.get(i)).divide(weightSum, 2, RoundingMode.HALF_UP);
                    accumulated = accumulated.add(share);
                } else {
                    share = total.subtract(accumulated);
                }
                shares.add(share);
            }
            return shares;
        }
    }

    /**
     * The application of a card promotion on one targeted offer application.
     * <p>
     * Product-scoped (spec A3, report H2c): it reduces one identified EAN, so a later discount
     * netting the same product subtracts exactly this reduction rather than a blind prorata.
     */
    public static class CardPromotionDiscountApplication implements DiscountApplication, ProductScopedDiscount {

        /**
         * The application moment restituted in the response (spec §3.6), set by the arbitration.
         * Defaults to AT_TOTAL, the current behaviour.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * The offer code ({@code CARD_PROMO:<ean>}).
         */
        private final String code;

        /**
         * The EAN of the line this promotion reduced.
         */
        private final String ean;

        /**
         * The optional decorative label, appended to the output type when present.
         */
        private final String label;

        /**
         * The loyalty card number, kept for ticket traceability (spec §3). Not serialised — no
         * new output field is introduced.
         */
        private final String cardNumber;

        /**
         * The targeted offer application.
         */
        private final OfferApplication target;

        /**
         * The reduction amount (stored positive; the engine subtracts it).
         */
        private final AmountEvaluation discountAmount;

        /**
         * Creates the application.
         *
         * @param code           the offer code ({@code CARD_PROMO:<ean>}).
         * @param ean            the EAN of the reduced line.
         * @param label          the optional decorative label.
         * @param cardNumber     the loyalty card number; may be null.
         * @param target         the targeted offer application.
         * @param discountAmount the reduction amount, stored positive.
         */
        public CardPromotionDiscountApplication(String code, String ean, String label, String cardNumber,
                                                OfferApplication target, AmountEvaluation discountAmount) {
            this.code = code;
            this.ean = ean;
            this.label = label;
            this.cardNumber = cardNumber;
            this.target = target;
            this.discountAmount = discountAmount;
        }

        /**
         * Returns the application moment of the configuration that produced this advantage.
         *
         * @return the application moment, AT_TOTAL until the arbitration sets it.
         */
        @Override
        public String getApplicationMoment() {
            return applicationMoment;
        }

        /**
         * Records the application moment set by the arbitration.
         *
         * @param applicationMoment the moment (AT_TRIGGER or AT_TOTAL).
         */
        @Override
        public void setApplicationMoment(String applicationMoment) {
            this.applicationMoment = applicationMoment;
        }

        /**
         * Returns the display type of this application (spec §3): {@code Card Promotion:
         * CARD_PROMO:<ean>}, followed by {@code  — <label>} when a label is present.
         *
         * @return the display type.
         */
        public String getType() {
            return label == null ? "Card Promotion: " + code : "Card Promotion: " + code + " — " + label;
        }

        /**
         * Returns the loyalty card number carried for ticket traceability (spec §3).
         * <p>
         * Ignored in JSON: no new output field is introduced, this is audit context reachable
         * programmatically.
         *
         * @return the card number, or null when the basket carried none.
         */
        @JsonIgnore
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Returns the offer application this discount targets.
         *
         * @return the targeted application.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return target;
        }

        /**
         * Returns the reduction amount.
         *
         * @return the amount, stored positive; the engine subtracts it from the total.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return discountAmount;
        }

        /**
         * Returns the EAN of the product line this promotion reduced (A3, report H2c).
         *
         * @return the discounted product EAN.
         */
        @Override
        public String discountedEan() {
            return ean;
        }
    }
}
