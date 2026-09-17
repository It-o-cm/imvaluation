package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.PriceUsage;
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
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import com.intermarche.valuation.engine.TierTable;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Factory for the "ANTI_WASTE_DISCOUNT" advantage type: a per-line discount that grows as the
 * best-before date nears (spec §7).
 * <p>
 * Each line carrying a {@code bestBeforeDate} (optionally restricted to {@code targetEans})
 * receives the discount of its strictest reached tier. {@code remainingDays = bestBeforeDate −
 * today}, {@code today} being the server clock (it switches to the evaluation date in C5). A
 * line without a best-before date is silently skipped; an expired line ({@code remainingDays <
 * 0}) takes the strictest tier — the engine does not judge sellability, that is the till's job.
 * <p>
 * Tier resolution reuses {@link TierTable} on an inverted axis rather than a third threshold
 * system: the strictest reached tier is the smallest {@code maxRemainingDays} such that
 * {@code remainingDays ≤ maxRemainingDays}. Thresholds are stored as {@code offset −
 * maxRemainingDays} (with {@code offset} the largest {@code maxRemainingDays}), and
 * {@link TierTable#resolveHighest(BigDecimal)} is queried at {@code offset − remainingDays}, so
 * the highest reached threshold is exactly the smallest {@code maxRemainingDays}. Duplicate
 * thresholds are rejected at creation.
 * <p>
 * Pre-wiring for C5: this type is the natural candidate for a "not subject to Egalim" flag
 * (GB-01-06-08); no such control is implemented here.
 */
@ApplicationScoped
public class AntiWasteDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "ANTI_WASTE_DISCOUNT";

    /**
     * JSON Schema definition for validating anti-waste discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Anti-Waste Discount Offer Specification",
      "description": "A per-line discount growing as the best-before date nears.",
      "type": "object",
      "required": ["tiers"],
      "properties": {
        "targetEans": {
          "type": "array",
          "minItems": 1,
          "items": { "type": "string", "minLength": 1 },
          "description": "Products the discount is restricted to. Absent: any line carrying a best-before date.",
          "x-widget": "ean-list",
          "x-label": "Eligible products"
        },
        "tiers": {
          "type": "array",
          "minItems": 1,
          "description": "The remaining-days thresholds and their percentages.",
          "x-widget": "object-list",
          "x-label": "Tiers",
          "x-item-label": "tier",
          "items": {
            "type": "object",
            "required": ["maxRemainingDays", "percent"],
            "additionalProperties": false,
            "properties": {
              "maxRemainingDays": {
                "type": "integer",
                "minimum": 0,
                "description": "Applies when the remaining days are at most this value; the smallest reached wins.",
                "x-label": "Max remaining days"
              },
              "percent": {
                "type": "number",
                "exclusiveMinimum": 0,
                "maximum": 100,
                "description": "The percentage off the line's current base.",
                "x-widget": "percent",
                "x-label": "Percentage"
              }
            }
          }
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "ANTI_WASTE_DISCOUNT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the anti-waste discount specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per "ANTI_WASTE_DISCOUNT" offer of the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link AntiWasteDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or a cross rule.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create Anti-Waste Discount appliers without a valid basket.");
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
     * @throws IllegalArgumentException if two tiers share a {@code maxRemainingDays}.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Basket basket, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            Set<String> targetEans = new LinkedHashSet<>();
            if (spec.has("targetEans")) {
                for (JsonNode ean : spec.get("targetEans")) {
                    targetEans.add(ean.asText());
                }
            }
            Set<Integer> seenDays = new HashSet<>();
            long offset = 0;
            for (JsonNode tierNode : spec.get("tiers")) {
                int maxRemainingDays = tierNode.get("maxRemainingDays").asInt();
                if (!seenDays.add(maxRemainingDays)) {
                    throw new IllegalArgumentException(String.format(
                            "ANTI_WASTE_DISCOUNT offer '%s': duplicate maxRemainingDays %d.",
                            offer.code, maxRemainingDays));
                }
                offset = Math.max(offset, maxRemainingDays);
            }
            List<TierTable.Tier<BigDecimal>> tiers = new ArrayList<>();
            for (JsonNode tierNode : spec.get("tiers")) {
                long maxRemainingDays = tierNode.get("maxRemainingDays").asLong();
                BigDecimal percent = tierNode.get("percent").decimalValue();
                tiers.add(new TierTable.Tier<>(BigDecimal.valueOf(offset - maxRemainingDays), percent));
            }
            TierTable<BigDecimal> table = TierTable.of(tiers);
            List<Product> targetProducts = targetEans.isEmpty()
                    ? List.of()
                    : Product.findByEans(targetEans);
            AntiWasteDiscountApplier applier = new AntiWasteDiscountApplier(
                    offer.code, targetEans, targetProducts, table, offset, basket, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * Applier of one anti-waste discount offer.
     */
    public static class AntiWasteDiscountApplier implements AdvantageApplier {

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
         * The EANs the discount is restricted to; empty means any line carrying a best-before
         * date.
         */
        private final Set<String> targetEans;

        /**
         * The targeted products (used to switch the covered lines to the reference price);
         * empty when the discount is not restricted.
         */
        private final List<Product> targetProducts;

        /**
         * The tier table on the inverted axis (threshold {@code offset − maxRemainingDays},
         * award the percentage).
         */
        private final TierTable<BigDecimal> table;

        /**
         * The axis offset: the largest {@code maxRemainingDays}, keeping the inverted thresholds
         * non-negative.
         */
        private final long offset;

        /**
         * The sandbox efficiency score: the discount the offer would produce on the current
         * basket, at the reference price. Higher is arbitrated first.
         */
        private final double efficiencyScore;

        /**
         * Creates the applier and computes its sandbox efficiency score.
         *
         * @param code           the offer code.
         * @param targetEans     the EANs the discount is restricted to; empty for any line.
         * @param targetProducts the targeted products; empty when not restricted.
         * @param table          the tier table on the inverted axis.
         * @param offset         the axis offset (largest {@code maxRemainingDays}).
         * @param basket         the basket, scanned for the sandbox score; may be null.
         * @param store          the store, for reference price lookups; may be null.
         */
        public AntiWasteDiscountApplier(String code, Set<String> targetEans, List<Product> targetProducts,
                                        TierTable<BigDecimal> table, long offset, Basket basket, Store store) {
            this.code = code;
            this.targetEans = new LinkedHashSet<>(targetEans);
            this.targetProducts = targetProducts;
            this.table = table;
            this.offset = offset;
            this.efficiencyScore = computeSandboxScore(basket, store);
        }

        /**
         * Resolves the percentage of the strictest reached tier for a given remaining-days value.
         *
         * @param remainingDays the days remaining before the best-before date (negative when
         *                      expired).
         * @return the percentage of the strictest reached tier, or empty when none is reached.
         */
        private Optional<BigDecimal> resolvePercent(long remainingDays) {
            return table.resolveHighest(BigDecimal.valueOf(offset - remainingDays))
                    .map(TierTable.Tier::award);
        }

        /**
         * Computes the number of days remaining before a best-before date, from today (server
         * clock).
         *
         * @param bestBeforeDate the ISO best-before date; may be null or blank.
         * @return the remaining days, or empty when the date is absent or unparsable.
         */
        private Optional<Long> remainingDays(String bestBeforeDate) {
            if (bestBeforeDate == null || bestBeforeDate.isBlank()) {
                return Optional.empty();
            }
            try {
                LocalDate today = DateTimeProvider.now().toLocalDate();
                LocalDate dlc = LocalDate.parse(bestBeforeDate.trim());
                return Optional.of(ChronoUnit.DAYS.between(today, dlc));
            } catch (java.time.format.DateTimeParseException e) {
                return Optional.empty();
            }
        }

        /**
         * Computes the sandbox efficiency score: the summed discount on the basket lines that
         * carry an eligible best-before date, valued at the reference price.
         *
         * @param basket the basket to scan; may be null.
         * @param store  the store for the reference price lookups; may be null.
         * @return the summed sandbox discount, never negative; zero when nothing is valued.
         */
        private double computeSandboxScore(Basket basket, Store store) {
            if (basket == null || basket.items == null || store == null) {
                return 0.0;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                if (!targetEans.isEmpty() && !targetEans.contains(item.produceEan)) {
                    continue;
                }
                Optional<Long> days = remainingDays(item.bestBeforeDate);
                if (days.isEmpty()) {
                    continue;
                }
                Optional<BigDecimal> percent = resolvePercent(days.get());
                if (percent.isEmpty()) {
                    continue;
                }
                try {
                    AmountEvaluation amount = AmountEvaluation.getAmount(item, store, PriceUsage.BASE_FOR_DISCOUNT);
                    if (amount.amountIncludingTax != null) {
                        total = total.add(amount.amountIncludingTax
                                .multiply(percent.get().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)));
                    }
                } catch (RuntimeException e) {
                    // Unpriced line: excluded from the sandbox estimate only.
                }
            }
            return total.doubleValue();
        }

        /**
         * Tells whether this discount can relate to the given offer applier.
         * <p>
         * It relates to every product-aware applier when unrestricted, or to those covering a
         * targeted product when restricted; registration switches the standard lines to the
         * reference price, like every other discount. The best-before eligibility itself is
         * decided per line at application time.
         *
         * @param offerApplier the offer applier to check.
         * @return true when this discount can relate to the applier.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (!(offerApplier instanceof ProductAwareOfferApplier productApplier)) {
                return false;
            }
            if (targetEans.isEmpty()) {
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
         * @return the summed discount on the current basket at the reference price.
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
         * Applies the anti-waste discount to the evaluation.
         * <p>
         * For every available offer application, each valued line carrying an eligible
         * best-before date receives the percentage of its strictest reached tier on its current
         * base, at the line's real VAT rate. Lines without a best-before date, or outside the
         * targeted EANs, are skipped. Each eligible line yields one application targeting its
         * offer application.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return the discount applications, empty when no line is eligible.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            if (evaluation.getOffers() == null || evaluation.getBasket() == null) {
                return applications;
            }
            Map<String, String> dlcByLine = new HashMap<>();
            if (evaluation.getBasket().items != null) {
                for (Basket.Item item : evaluation.getBasket().items) {
                    if (item.lineId != null && item.bestBeforeDate != null) {
                        dlcByLine.put(item.lineId, item.bestBeforeDate);
                    }
                }
            }
            for (OfferApplication app : evaluation.getAvailableOffers()) {
                if (!(app instanceof ProductAwareOfferApplication productAwareApp)) {
                    continue;
                }
                for (BasketEvaluation.Item item : productAwareApp.getValuedItems()) {
                    if (item.amount == null || item.amount.amountIncludingTax == null
                            || item.amount.amountIncludingTax.signum() <= 0) {
                        continue;
                    }
                    if (!targetEans.isEmpty() && !targetEans.contains(item.produceEan)) {
                        continue;
                    }
                    Optional<Long> days = remainingDays(dlcByLine.get(item.lineId));
                    if (days.isEmpty()) {
                        continue;
                    }
                    Optional<BigDecimal> percent = resolvePercent(days.get());
                    if (percent.isEmpty()) {
                        continue;
                    }
                    BigDecimal fraction = percent.get().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                    AmountEvaluation discountAmount = item.amount.multiply(fraction);
                    if (discountAmount.amountIncludingTax.signum() <= 0) {
                        continue;
                    }
                    applications.add(new AntiWasteDiscountApplication(code, item.produceEan, app, discountAmount));
                }
            }
            return applications;
        }
    }

    /**
     * The application of an anti-waste discount on one targeted offer application.
     */
    public static class AntiWasteDiscountApplication implements DiscountApplication {

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
         * The EAN of the discounted line.
         */
        private final String ean;

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
         * @param ean            the EAN of the discounted line.
         * @param target         the targeted offer application.
         * @param discountAmount the discount amount, stored positive.
         */
        public AntiWasteDiscountApplication(String code, String ean, OfferApplication target,
                                            AmountEvaluation discountAmount) {
            this.code = code;
            this.ean = ean;
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
         * @return a string of the form {@code Anti-Waste: <code> (<ean>)}.
         */
        public String getType() {
            return "Anti-Waste: " + code + " (" + ean + ")";
        }

        /**
         * Returns the EAN of the discounted line.
         *
         * @return the discounted EAN.
         */
        public String getEan() {
            return ean;
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
