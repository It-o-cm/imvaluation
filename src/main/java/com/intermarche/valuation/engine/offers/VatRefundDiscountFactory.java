package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.NetAmounts;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factory for the "VAT_REFUND_DISCOUNT" advantage type: an ordinary discount whose amount is
 * the VAT of an base (spec §4).
 * <p>
 * "Remise = TVA" is a plain discount whose amount is derived from the VAT: on the base,
 * at the current amounts, the discount equals {@code Σ(TTC − HT)}. The VAT value is computed
 * by the single shared helper {@link InstrumentGrantFactory#vatAmount(AmountEvaluation)} — the
 * one place that knows what "value of the VAT" means — so this type and the {@code VAT_AMOUNT}
 * grant agree to the cent. Multiple rates are handled line by line; a rate of zero (gift
 * cards) contributes nothing.
 * <p>
 * Two scopes, the same cross rules as a {@code MINIMUM_AMOUNT} trigger: {@code TICKET}
 * (GB-01-05-32) takes the whole valued ticket and forbids {@code targetEans};
 * {@code ITEMS} (GB-01-05-33) takes the listed lines and requires {@code targetEans}. The
 * total is distributed as one {@link DiscountApplication} per targeted offer application,
 * pro-rata of the tax-included base, the rounding residue landing on the last one — the
 * same rule as {@code TIERED_DISCOUNT}.
 * <p>
 * The fiscal VAT breakdown is untouched: it is recomputed from the final net amounts, so a
 * 120.00 TTC / 20 % base yields a 20.00 discount, 100.00 paid, and a fiscal VAT of 16.67
 * — no special case anywhere in this type.
 */
@ApplicationScoped
public class VatRefundDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "VAT_REFUND_DISCOUNT";

    /**
     * JSON Schema definition for validating VAT-refund discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "VAT Refund Discount Offer Specification",
      "description": "An ordinary discount whose amount equals the VAT of the base at the current amounts.",
      "type": "object",
      "required": ["scope"],
      "properties": {
        "scope": {
          "type": "string",
          "enum": ["TICKET", "ITEMS"],
          "description": "TICKET takes the whole valued ticket; ITEMS takes the listed EANs.",
          "x-label": "Scope"
        },
        "targetEans": {
          "type": "array",
          "minItems": 1,
          "items": { "type": "string", "minLength": 1 },
          "description": "Products the base is drawn from. Required when the scope is ITEMS, forbidden for TICKET.",
          "x-widget": "ean-list",
          "x-label": "Eligible products"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Scope of the base: the whole ticket or a list of products.
     */
    public enum Scope {
        /** The base is every available valued line. */
        TICKET,
        /** The base is the available valued lines whose EAN is targeted. */
        ITEMS
    }

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "VAT_REFUND_DISCOUNT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the VAT-refund discount specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per "VAT_REFUND_DISCOUNT" offer of the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link VatRefundDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or a cross rule.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create VAT Refund Discount appliers without a valid basket.");
        Store store = basketEvaluation.getStore();
        for (Offer offer : getOffers(basketEvaluation, OFFER_TYPE)) {
            processOffer(offer, appliers, basket, store);
        }
        return appliers;
    }

    /**
     * Parses and validates one offer specification and adds the corresponding applier.
     *
     * @param offer    the offer to process.
     * @param appliers the list receiving the created applier.
     * @param basket   the basket, for the sandbox efficiency score.
     * @param store    the store, for the reference price lookups of the score.
     * @throws IllegalArgumentException if a cross-field rule is violated.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Basket basket, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            Scope scope = Scope.valueOf(spec.get("scope").asText());
            Set<String> targetEans = new LinkedHashSet<>();
            if (spec.has("targetEans")) {
                for (JsonNode ean : spec.get("targetEans")) {
                    targetEans.add(ean.asText());
                }
            }
            if (scope == Scope.ITEMS && targetEans.isEmpty()) {
                throw new IllegalArgumentException(String.format(
                        "VAT_REFUND_DISCOUNT offer '%s': scope ITEMS requires targetEans.", offer.code));
            }
            if (scope == Scope.TICKET && !targetEans.isEmpty()) {
                throw new IllegalArgumentException(String.format(
                        "VAT_REFUND_DISCOUNT offer '%s': scope TICKET forbids targetEans.", offer.code));
            }
            List<Product> targetProducts = scope == Scope.ITEMS
                    ? Product.findByEans(targetEans)
                    : List.of();
            VatRefundDiscountApplier applier =
                    new VatRefundDiscountApplier(offer.code, scope, targetProducts, basket, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * One targeted contribution to the base: an offer application and the amount it brings.
     *
     * @param application the offer application carrying the contribution.
     * @param amount      the amount attributed to the base (the whole application in TICKET
     *                    scope, the product's part in ITEMS scope).
     */
    private record Contribution(ProductAwareOfferApplication application, AmountEvaluation amount) {
    }

    /**
     * Applier of one VAT-refund discount offer.
     */
    public static class VatRefundDiscountApplier implements AdvantageApplier {

        /**
         * The offer code, used in labels and error messages.
         */
        private final String code;

        /**
         * The configuration (the {@link Offer} row) this applier was built from, set by the
         * factory right after construction. Never null in production; left null when an applier
         * is built directly (as in unit tests), which the arbitration reads as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS} with default parameters.
         */
        private Offer configuration;

        /**
         * The base scope.
         */
        private final Scope scope;

        /**
         * The targeted products (scope ITEMS); empty in TICKET scope.
         */
        private final List<Product> targetProducts;

        /**
         * The sandbox efficiency score: the VAT of the base on the current basket, at the
         * reference price. Higher is arbitrated first.
         */
        private final double efficiencyScore;

        /**
         * Creates the applier and computes its sandbox efficiency score.
         *
         * @param code           the offer code.
         * @param scope          the base scope.
         * @param targetProducts the targeted products; empty in TICKET scope.
         * @param basket         the basket, scanned for the sandbox score; may be null.
         * @param store          the store, for reference price lookups; may be null.
         */
        public VatRefundDiscountApplier(String code, Scope scope, List<Product> targetProducts,
                                        Basket basket, Store store) {
            this.code = code;
            this.scope = scope;
            this.targetProducts = targetProducts;
            this.efficiencyScore = computeSandboxScore(basket, store);
        }

        /**
         * Computes the sandbox efficiency score: the VAT of the in-scope basket lines valued at
         * the reference price. A line that cannot be valued is skipped rather than failing the
         * score.
         *
         * @param basket the basket to scan; may be null.
         * @param store  the store for the reference price lookups; may be null.
         * @return the summed VAT of the base, never negative; zero when nothing is valued.
         */
        private double computeSandboxScore(Basket basket, Store store) {
            if (basket == null || basket.items == null || store == null) {
                return 0.0;
            }
            Set<String> targetEans = new LinkedHashSet<>();
            for (Product product : targetProducts) {
                targetEans.add(product.ean);
            }
            BigDecimal total = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                if (scope == Scope.ITEMS && !targetEans.contains(item.produceEan)) {
                    continue;
                }
                try {
                    AmountEvaluation amount = AmountEvaluation.getAmount(item, store, PriceUsage.BASE_FOR_DISCOUNT);
                    total = total.add(InstrumentGrantFactory.vatAmount(amount));
                } catch (RuntimeException e) {
                    // A line that cannot be priced at build time does not contribute to the
                    // score; the real base is computed later against the applied offers.
                }
            }
            return total.doubleValue();
        }

        /**
         * Tells whether this discount can relate to the given offer applier.
         * <p>
         * As an ordinary discount it relates to every product-aware applier in TICKET scope, and
         * to those covering a targeted product in ITEMS scope; registration switches the standard
         * lines to the reference price, like every other discount.
         *
         * @param offerApplier the offer applier to check.
         * @return true when this discount can relate to the applier.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (!(offerApplier instanceof ProductAwareOfferApplier productApplier)) {
                return false;
            }
            if (scope == Scope.TICKET) {
                return true;
            }
            for (Product product : targetProducts) {
                if (productApplier.isApplicable(product)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the sandbox efficiency score of this applier.
         *
         * @return the VAT of the base on the current basket at the reference price.
         */
        @Override
        public double getEfficiencyScore() {
            return efficiencyScore;
        }

        /**
         * Returns the configuration this applier was built from.
         *
         * @return the source offer, or null when the applier was built without one.
         */
        @Override
        public Offer getConfiguration() {
            return configuration;
        }

        /**
         * Applies the VAT-refund discount to the evaluation.
         * <p>
         * The base is gathered from the available offer applications, the total discount is
         * the summed VAT of the base, then split into one application per targeted offer
         * application, pro-rata of the tax-included base, the rounding residue going to the
         * last one.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return the discount applications, empty when the base carries no VAT.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            List<Contribution> contributions = collectContributions(evaluation);
            if (contributions.isEmpty()) {
                return applications;
            }
            BigDecimal baseTtc = BigDecimal.ZERO;
            BigDecimal totalVat = BigDecimal.ZERO;
            for (Contribution contribution : contributions) {
                baseTtc = baseTtc.add(contribution.amount().amountIncludingTax);
                totalVat = totalVat.add(InstrumentGrantFactory.vatAmount(contribution.amount()));
            }
            BigDecimal total = totalVat.setScale(2, RoundingMode.HALF_UP);
            if (total.signum() <= 0 || baseTtc.signum() <= 0) {
                return applications;
            }
            if (total.compareTo(baseTtc) > 0) {
                total = baseTtc;
            }
            distribute(applications, contributions, total, baseTtc);
            return applications;
        }

        /**
         * Gathers the contributions of the base from the available offer applications.
         *
         * @param evaluation the evaluation context.
         * @return the contributions, empty when nothing is covered.
         */
        private List<Contribution> collectContributions(BasketEvaluation evaluation) {
            List<Contribution> contributions = new ArrayList<>();
            if (evaluation.getOffers() == null) {
                return contributions;
            }
            for (OfferApplication app : evaluation.getAvailableOffers()) {
                if (!(app instanceof ProductAwareOfferApplication productAwareApp)) {
                    continue;
                }
                if (scope == Scope.TICKET) {
                    AmountEvaluation gross = productAwareApp.getAmount();
                    if (gross == null || gross.amountIncludingTax == null
                            || gross.amountIncludingTax.signum() <= 0) {
                        continue;
                    }
                    // A3 (report H2b): the refunded VAT is measured on the amount net of the
                    // advantages already retained, not on the gross line — no double advantage.
                    AmountEvaluation amount = NetAmounts.net(evaluation, productAwareApp, gross);
                    if (amount != null && amount.amountIncludingTax != null
                            && amount.amountIncludingTax.signum() > 0) {
                        contributions.add(new Contribution(productAwareApp, amount));
                    }
                    continue;
                }
                for (Product product : targetProducts) {
                    BigDecimal quantity = productAwareApp.getProductQuantity(product);
                    if (quantity.signum() <= 0) {
                        continue;
                    }
                    AmountEvaluation gross = productAwareApp.getProductAmount(product);
                    if (gross == null || gross.amountIncludingTax == null
                            || gross.amountIncludingTax.signum() <= 0) {
                        continue;
                    }
                    // A3 (report H2b): net this product tranche per-product before measuring its VAT.
                    BigDecimal rate = gross.vatRate == null ? BigDecimal.ZERO : gross.vatRate;
                    BigDecimal netTtc = NetAmounts.netProductTtc(evaluation, productAwareApp,
                            product.ean, gross.amountIncludingTax);
                    if (netTtc.signum() <= 0) {
                        continue;
                    }
                    BigDecimal netHt = netTtc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                    contributions.add(new Contribution(productAwareApp, new AmountEvaluation(netHt, netTtc, rate)));
                }
            }
            return contributions;
        }

        /**
         * Splits the total discount into one application per targeted offer application.
         * <p>
         * Each application receives a tax-included share pro-rata of its contribution to the
         * base; the last one absorbs the rounding residue. The tax-excluded share is rebuilt
         * at the group's implied rate.
         *
         * @param applications  the list receiving the applications.
         * @param contributions the base contributions.
         * @param total         the total discount, tax included, capped at the base.
         * @param baseTtc       the tax-included base.
         */
        private void distribute(List<AdvantageApplication> applications, List<Contribution> contributions,
                                BigDecimal total, BigDecimal baseTtc) {
            Map<ProductAwareOfferApplication, BigDecimal[]> byApplication = new LinkedHashMap<>();
            for (Contribution contribution : contributions) {
                BigDecimal[] slot = byApplication.computeIfAbsent(contribution.application(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                slot[0] = slot[0].add(contribution.amount().amountIncludingTax);
                slot[1] = slot[1].add(contribution.amount().amountExcludingTax);
            }
            List<Map.Entry<ProductAwareOfferApplication, BigDecimal[]>> entries =
                    new ArrayList<>(byApplication.entrySet());
            BigDecimal remaining = total;
            for (int i = 0; i < entries.size(); i++) {
                BigDecimal groupTtc = entries.get(i).getValue()[0];
                BigDecimal groupHt = entries.get(i).getValue()[1];
                BigDecimal shareTtc;
                if (i == entries.size() - 1) {
                    shareTtc = remaining;
                } else {
                    shareTtc = total.multiply(groupTtc).divide(baseTtc, 2, RoundingMode.HALF_UP);
                    remaining = remaining.subtract(shareTtc);
                }
                if (shareTtc.signum() <= 0) {
                    continue;
                }
                BigDecimal rate = (groupHt.signum() > 0)
                        ? groupTtc.divide(groupHt, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                        : BigDecimal.ZERO;
                BigDecimal shareHt = shareTtc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                AmountEvaluation amount = new AmountEvaluation(shareHt, shareTtc, rate);
                applications.add(new VatRefundDiscountApplication(code, entries.get(i).getKey(), amount));
            }
        }
    }

    /**
     * The application of a VAT-refund discount on one targeted offer application.
     */
    public static class VatRefundDiscountApplication implements DiscountApplication {

        /**
         * The application moment restituted in the response (spec §3.6), set by the arbitration.
         * Defaults to AT_TOTAL, the current behaviour.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * The offer code.
         */
        private final String code;

        /**
         * The targeted offer application.
         */
        private final OfferApplication target;

        /**
         * The discount amount (stored positive; the engine subtracts it).
         */
        private final AmountEvaluation discountAmount;

        /**
         * Creates the application.
         *
         * @param code           the offer code.
         * @param target         the targeted offer application.
         * @param discountAmount the discount amount, stored positive.
         */
        public VatRefundDiscountApplication(String code, OfferApplication target, AmountEvaluation discountAmount) {
            this.code = code;
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
         * Returns the display type of this application.
         *
         * @return a string of the form {@code VAT Refund: <code>}.
         */
        public String getType() {
            return "VAT Refund: " + code;
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
         * Returns the discount amount.
         *
         * @return the amount, stored positive; the engine subtracts it from the total.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return discountAmount;
        }
    }
}
