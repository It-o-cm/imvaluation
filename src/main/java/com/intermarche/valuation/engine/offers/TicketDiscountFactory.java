package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Store;
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
import java.util.List;
import java.util.Map;

/**
 * Factory for the "TICKET_DISCOUNT" advantage type: a percentage or a flat amount on the whole
 * ticket (spec §5).
 * <p>
 * The assiette is every available valued line. A {@code PERCENTAGE} award (0 &lt; v ≤ 100)
 * takes that share of the assiette; an {@code AMOUNT} award takes a flat amount, capped at the
 * assiette so the ticket never goes negative. Combined with a {@code MINIMUM_AMOUNT} scope
 * {@code TICKET} trigger this is the canonical "5€ off from 50€" of the C1+C2 arbitration
 * (GM-06-04-16 / GM-06-04-17).
 * <p>
 * The total is distributed as one {@link DiscountApplication} per targeted offer application,
 * pro-rata of the tax-included assiette, the rounding residue landing on the last one — the
 * same rule as {@code TIERED_DISCOUNT}. Like every discount, declaring itself applicable
 * switches the standard lines to the reference price.
 */
@ApplicationScoped
public class TicketDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "TICKET_DISCOUNT";

    /**
     * JSON Schema definition for validating ticket discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Ticket Discount Offer Specification",
      "description": "A percentage or a flat amount on the whole ticket.",
      "type": "object",
      "required": ["discountType", "value"],
      "properties": {
        "discountType": {
          "type": "string",
          "enum": ["PERCENTAGE", "AMOUNT"],
          "description": "PERCENTAGE of the assiette, or a flat AMOUNT capped at the assiette.",
          "x-label": "Discount type"
        },
        "value": {
          "type": "number",
          "exclusiveMinimum": 0,
          "description": "The percentage (0 < v <= 100) or the flat amount (tax included).",
          "x-widget": "discount-value",
          "x-label": "Value",
          "x-unit-from": "discountType"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Nature of the ticket discount.
     */
    public enum DiscountType {
        /** A percentage of the assiette (0 &lt; v ≤ 100). */
        PERCENTAGE,
        /** A flat amount, capped at the assiette. */
        AMOUNT
    }

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "TICKET_DISCOUNT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the ticket discount specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per "TICKET_DISCOUNT" offer of the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link TicketDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or a cross rule.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create Ticket Discount appliers without a valid basket.");
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
     * @throws IllegalArgumentException if the percentage is out of the {@code (0, 100]} range.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Basket basket, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            DiscountType discountType = DiscountType.valueOf(spec.get("discountType").asText());
            BigDecimal value = spec.get("value").decimalValue();
            if (discountType == DiscountType.PERCENTAGE && value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException(String.format(
                        "TICKET_DISCOUNT offer '%s': a PERCENTAGE value must be in (0, 100].", offer.code));
            }
            TicketDiscountApplier applier = new TicketDiscountApplier(offer.code, discountType, value, basket, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * One contribution to the assiette: an offer application and its amount.
     *
     * @param application the offer application carrying the contribution.
     * @param amount      the amount it brings to the assiette.
     */
    private record Contribution(ProductAwareOfferApplication application, AmountEvaluation amount) {
    }

    /**
     * Applier of one ticket discount offer.
     */
    public static class TicketDiscountApplier implements AdvantageApplier {

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
         * The nature of the discount.
         */
        private final DiscountType discountType;

        /**
         * The percentage or the flat amount.
         */
        private final BigDecimal value;

        /**
         * The sandbox efficiency score: the discount the offer would produce on the current
         * basket, at the reference price. Higher is arbitrated first.
         */
        private final double efficiencyScore;

        /**
         * Creates the applier and computes its sandbox efficiency score.
         *
         * @param code         the offer code.
         * @param discountType the nature of the discount.
         * @param value        the percentage or the flat amount.
         * @param basket       the basket, scanned for the sandbox score; may be null.
         * @param store        the store, for reference price lookups; may be null.
         */
        public TicketDiscountApplier(String code, DiscountType discountType, BigDecimal value,
                                     Basket basket, Store store) {
            this.code = code;
            this.discountType = discountType;
            this.value = value;
            this.efficiencyScore = computeSandboxScore(basket, store);
        }

        /**
         * Computes the sandbox efficiency score: the discount on the basket total valued at the
         * reference price. A line that cannot be valued is skipped rather than failing the score.
         *
         * @param basket the basket to scan; may be null.
         * @param store  the store for the reference price lookups; may be null.
         * @return the sandbox discount, never negative; zero when nothing is valued.
         */
        private double computeSandboxScore(Basket basket, Store store) {
            if (basket == null || basket.items == null || store == null) {
                return 0.0;
            }
            BigDecimal assiette = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                try {
                    AmountEvaluation amount = AmountEvaluation.getAmount(item, store, PriceUsage.BASE_FOR_DISCOUNT);
                    if (amount.amountIncludingTax != null) {
                        assiette = assiette.add(amount.amountIncludingTax);
                    }
                } catch (RuntimeException e) {
                    // Unpriced line: excluded from the sandbox estimate only.
                }
            }
            return rawDiscount(assiette).doubleValue();
        }

        /**
         * Computes the raw discount for a given assiette, before rounding and capping.
         *
         * @param assiette the tax-included assiette.
         * @return the discount: the percentage of the assiette, or the flat amount capped at it.
         */
        private BigDecimal rawDiscount(BigDecimal assiette) {
            if (assiette.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            if (discountType == DiscountType.PERCENTAGE) {
                return assiette.multiply(value.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            }
            return value.min(assiette);
        }

        /**
         * Tells whether this discount can relate to the given offer applier.
         * <p>
         * It relates to every product-aware applier; registration switches the standard lines to
         * the reference price, like every other discount.
         *
         * @param offerApplier the offer applier to check.
         * @return true when the applier is product-aware.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return offerApplier instanceof ProductAwareOfferApplier;
        }

        /**
         * Returns the sandbox efficiency score of this applier.
         *
         * @return the discount on the basket total at the reference price.
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
         * Applies the ticket discount to the evaluation.
         * <p>
         * The assiette is every available offer application; the total is the percentage of that
         * assiette or the flat amount capped at it, then split into one application per targeted
         * offer application, pro-rata of the tax-included assiette, the rounding residue going to
         * the last one.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return the discount applications, empty when the assiette is empty.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            List<Contribution> contributions = collectContributions(evaluation);
            if (contributions.isEmpty()) {
                return applications;
            }
            BigDecimal baseTtc = BigDecimal.ZERO;
            for (Contribution contribution : contributions) {
                baseTtc = baseTtc.add(contribution.amount().amountIncludingTax);
            }
            if (baseTtc.signum() <= 0) {
                return applications;
            }
            BigDecimal total = rawDiscount(baseTtc).setScale(2, RoundingMode.HALF_UP);
            if (total.compareTo(baseTtc) > 0) {
                total = baseTtc;
            }
            if (total.signum() <= 0) {
                return applications;
            }
            distribute(applications, contributions, total, baseTtc);
            return applications;
        }

        /**
         * Gathers the contributions of the assiette from the available offer applications.
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
                AmountEvaluation amount = productAwareApp.getAmount();
                if (amount != null && amount.amountIncludingTax != null
                        && amount.amountIncludingTax.signum() > 0) {
                    contributions.add(new Contribution(productAwareApp, amount));
                }
            }
            return contributions;
        }

        /**
         * Splits the total discount into one application per targeted offer application.
         *
         * @param applications  the list receiving the applications.
         * @param contributions the assiette contributions.
         * @param total         the total discount, tax included, capped at the assiette.
         * @param baseTtc       the tax-included assiette.
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
                applications.add(new TicketDiscountApplication(code, entries.get(i).getKey(), amount));
            }
        }
    }

    /**
     * The application of a ticket discount on one targeted offer application.
     */
    public static class TicketDiscountApplication implements DiscountApplication {

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
        public TicketDiscountApplication(String code, OfferApplication target, AmountEvaluation discountAmount) {
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
         * @return a string of the form {@code Ticket Discount: <code>}.
         */
        public String getType() {
            return "Ticket Discount: " + code;
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
