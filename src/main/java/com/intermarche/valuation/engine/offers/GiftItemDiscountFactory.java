package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
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
import com.intermarche.valuation.engine.MinimumAmountCondition;
import com.intermarche.valuation.engine.MinimumQuantityCondition;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.Trigger;
import com.intermarche.valuation.engine.TriggerCondition;
import com.intermarche.valuation.engine.TriggerResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for the "GIFT_ITEM_DISCOUNT" advantage type: the cheapest or most expensive of the
 * trigger's contributors offered (spec §6).
 * <p>
 * The gift is chosen among the <em>contributors of the trigger</em> — the union of the
 * triggering lines — never the whole basket (manipulable) nor a separate endowment (another
 * mechanic). A {@code trigger} is therefore required, with at least one contributor-bearing
 * condition ({@code MINIMUM_QUANTITY} or {@code MINIMUM_AMOUNT} scope {@code ITEMS}); a trigger
 * that attributes no line ({@code COUPON_CODE} alone, {@code MINIMUM_AMOUNT} scope
 * {@code TICKET} alone) is rejected at creation.
 * <p>
 * Among the contributor lines whose EAN belongs to a triggering list, the current unit price
 * elects the gift — the lowest ({@code CHEAPEST}) or the highest ({@code MOST_EXPENSIVE}), ties
 * broken by ascending EAN for determinism. Lines with a non-integer quantity count toward the
 * thresholds but are not offerable, so they are excluded from the selection. The gift is one
 * unit: {@code discountAmount = current unit price} of the elected line, and there is one
 * application per ticket. An empty selection produces nothing, silently.
 * <p>
 * Specified but not built (spec §6): a separate {@code giftEans} endowment and a repetition
 * field — future optional fields with defaults, per the JSON evolution clause.
 */
@ApplicationScoped
public class GiftItemDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "GIFT_ITEM_DISCOUNT";

    /**
     * JSON Schema definition for validating gift-item discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Gift Item Discount Offer Specification",
      "description": "Offers one unit chosen among the trigger's contributors, by current unit price.",
      "type": "object",
      "required": ["selection"],
      "properties": {
        "selection": {
          "type": "string",
          "enum": ["CHEAPEST", "MOST_EXPENSIVE"],
          "description": "Which contributor is offered: the cheapest or the most expensive unit.",
          "x-label": "Selection"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Which contributor an offer elects.
     */
    public enum Selection {
        /** The contributor with the lowest current unit price is offered. */
        CHEAPEST,
        /** The contributor with the highest current unit price is offered. */
        MOST_EXPENSIVE
    }

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "GIFT_ITEM_DISCOUNT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the gift-item discount specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per "GIFT_ITEM_DISCOUNT" offer of the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link GiftItemDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or a cross rule.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create Gift Item Discount appliers without a valid basket.");
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
     * @throws IllegalArgumentException if the trigger is absent or bears no contributor
     *                                  condition.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Basket basket, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            Selection selection = Selection.valueOf(spec.get("selection").asText());
            if (!spec.has("trigger")) {
                throw new IllegalArgumentException(String.format(
                        "GIFT_ITEM_DISCOUNT offer '%s': a trigger is required (the gift is chosen among its contributors).",
                        offer.code));
            }
            Trigger trigger = Trigger.of(spec.get("trigger"));
            Set<String> contributorEans = new LinkedHashSet<>();
            boolean hasContributorCondition = false;
            for (TriggerCondition child : trigger.getChildren()) {
                if (child instanceof MinimumQuantityCondition mq) {
                    contributorEans.addAll(mq.getEans());
                    hasContributorCondition = true;
                } else if (child instanceof MinimumAmountCondition ma
                        && ma.getScope() == MinimumAmountCondition.Scope.ITEMS) {
                    contributorEans.addAll(ma.getEans());
                    hasContributorCondition = true;
                }
            }
            if (!hasContributorCondition) {
                throw new IllegalArgumentException(String.format(
                        "GIFT_ITEM_DISCOUNT offer '%s': the trigger must carry at least one contributor-bearing "
                                + "condition (MINIMUM_QUANTITY or MINIMUM_AMOUNT scope ITEMS).", offer.code));
            }
            GiftItemDiscountApplier applier =
                    new GiftItemDiscountApplier(offer.code, selection, trigger, contributorEans, basket, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * Tells whether a quantity is a whole number of units, the only kind that can be offered.
     *
     * @param quantity the quantity to test.
     * @return true when the quantity is a positive integer within tolerance.
     */
    private static boolean isInteger(double quantity) {
        return quantity > 0 && Math.abs(quantity - Math.rint(quantity)) < 1e-9;
    }

    /**
     * One offerable unit: a contributor line, its current unit price and the application it
     * belongs to.
     *
     * @param application the contributor application carrying the line.
     * @param ean         the line's EAN.
     * @param unit        the current unit price (tax included, tax excluded, real rate).
     */
    private record Candidate(OfferApplication application, String ean, AmountEvaluation unit) {
    }

    /**
     * Applier of one gift-item discount offer.
     */
    public static class GiftItemDiscountApplier implements AdvantageApplier {

        /**
         * The offer code, used in labels and error messages.
         */
        private final String code;

        /**
         * The configuration (the {@link Offer} row) this applier was built from, set by the
         * factory right after construction. Never null in production; left null when an applier
         * is built directly (as in unit tests), which the arbitration reads as
         * {@link Trigger#ALWAYS} with default parameters.
         */
        private Offer configuration;

        /**
         * Which contributor to offer.
         */
        private final Selection selection;

        /**
         * The trigger whose contributors the gift is chosen among.
         */
        private final Trigger trigger;

        /**
         * The union of the triggering lists' EANs: the offerable perimeter.
         */
        private final Set<String> contributorEans;

        /**
         * The sandbox efficiency score: the current unit price the gift would offer on the
         * basket, at the reference price. Higher is arbitrated first.
         */
        private final double efficiencyScore;

        /**
         * Creates the applier and computes its sandbox efficiency score.
         *
         * @param code            the offer code.
         * @param selection       which contributor to offer.
         * @param trigger         the trigger whose contributors the gift is chosen among.
         * @param contributorEans the union of the triggering lists' EANs.
         * @param basket          the basket, scanned for the sandbox score; may be null.
         * @param store           the store, for reference price lookups; may be null.
         */
        public GiftItemDiscountApplier(String code, Selection selection, Trigger trigger,
                                       Set<String> contributorEans, Basket basket, Store store) {
            this.code = code;
            this.selection = selection;
            this.trigger = trigger;
            this.contributorEans = new LinkedHashSet<>(contributorEans);
            this.efficiencyScore = computeSandboxScore(basket, store);
        }

        /**
         * Computes the sandbox efficiency score: the unit price the gift would elect among the
         * basket lines whose EAN is a triggering one, valued at the reference price.
         *
         * @param basket the basket to scan; may be null.
         * @param store  the store for the reference price lookups; may be null.
         * @return the elected unit price, or zero when nothing is offerable.
         */
        private double computeSandboxScore(Basket basket, Store store) {
            if (basket == null || basket.items == null || store == null) {
                return 0.0;
            }
            BigDecimal best = null;
            for (Basket.Item item : basket.items) {
                if (!contributorEans.contains(item.produceEan) || item.quantity == null
                        || !isInteger(item.quantity)) {
                    continue;
                }
                Product product = Product.findByEan(item.produceEan);
                if (product == null || product.id == null) {
                    continue;
                }
                Price price = Price.findActivePriceAtDate(
                        product.id, store.id, DateTimeProvider.now(), PriceUsage.BASE_FOR_DISCOUNT);
                if (price == null) {
                    continue;
                }
                BigDecimal unit = price.priceIncludingTax;
                if (best == null
                        || (selection == Selection.CHEAPEST && unit.compareTo(best) < 0)
                        || (selection == Selection.MOST_EXPENSIVE && unit.compareTo(best) > 0)) {
                    best = unit;
                }
            }
            return best == null ? 0.0 : best.doubleValue();
        }

        /**
         * A gift never attaches to a specific offer applier and must not switch the standard
         * lines to the reference price: it reads the current valued amounts of the contributors.
         *
         * @param offerApplier the offer applier to check.
         * @return always {@code false}.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return false;
        }

        /**
         * Returns the sandbox efficiency score of this applier.
         *
         * @return the elected unit price on the current basket at the reference price.
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
         * Applies the gift-item discount to the evaluation.
         * <p>
         * The candidates are the contributor lines whose EAN is a triggering one and whose
         * quantity is a whole number of units; the elected one is the cheapest or most expensive
         * current unit price, ties broken by ascending EAN. The gift is one unit at that price,
         * one application per ticket. An empty selection produces nothing.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return zero or one {@link GiftItemDiscountApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            TriggerResult result = evaluation.triggerResult(configuration, trigger);
            if (!result.satisfied()) {
                return applications;
            }
            List<Candidate> candidates = new ArrayList<>();
            for (OfferApplication contributor : result.contributors()) {
                for (BasketEvaluation.Item item : contributor.getValuedItems()) {
                    if (item.produceEan == null || !contributorEans.contains(item.produceEan)
                            || !isInteger(item.quantity) || item.amount == null
                            || item.amount.amountIncludingTax == null) {
                        continue;
                    }
                    BigDecimal quantity = BigDecimal.valueOf(item.quantity);
                    BigDecimal unitTtc = item.amount.amountIncludingTax.divide(quantity, 2, RoundingMode.HALF_UP);
                    BigDecimal unitHt = item.amount.amountExcludingTax.divide(quantity, 2, RoundingMode.HALF_UP);
                    if (unitTtc.signum() <= 0) {
                        continue;
                    }
                    BigDecimal rate = item.amount.vatRate == null ? BigDecimal.ZERO : item.amount.vatRate;
                    candidates.add(new Candidate(contributor, item.produceEan,
                            new AmountEvaluation(unitHt, unitTtc, rate)));
                }
            }
            if (candidates.isEmpty()) {
                return applications;
            }
            Comparator<Candidate> byPrice = Comparator.comparing(c -> c.unit().amountIncludingTax);
            Comparator<Candidate> order = (selection == Selection.MOST_EXPENSIVE ? byPrice.reversed() : byPrice)
                    .thenComparing(Candidate::ean);
            Candidate elected = candidates.stream().min(order).orElseThrow();
            applications.add(new GiftItemDiscountApplication(code, elected.ean(),
                    elected.application(), elected.unit()));
            return applications;
        }
    }

    /**
     * The application of a gift-item discount on the elected contributor application.
     */
    public static class GiftItemDiscountApplication implements DiscountApplication {

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
         * The EAN of the offered line.
         */
        private final String ean;

        /**
         * The elected contributor application.
         */
        private final OfferApplication target;

        /**
         * The discount amount (stored positive; the engine subtracts it): one unit's price.
         */
        private final AmountEvaluation discountAmount;

        /**
         * Creates the application.
         *
         * @param code           the offer code.
         * @param ean            the EAN of the offered line.
         * @param target         the elected contributor application.
         * @param discountAmount the discount amount (one unit's current price), stored positive.
         */
        public GiftItemDiscountApplication(String code, String ean, OfferApplication target,
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
         * @return a string of the form {@code Gift Item: <code> (<ean>)}.
         */
        public String getType() {
            return "Gift Item: " + code + " (" + ean + ")";
        }

        /**
         * Returns the EAN of the offered line.
         *
         * @return the offered EAN.
         */
        public String getEan() {
            return ean;
        }

        /**
         * Returns the offer application the gift targets.
         *
         * @return the elected contributor application.
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
