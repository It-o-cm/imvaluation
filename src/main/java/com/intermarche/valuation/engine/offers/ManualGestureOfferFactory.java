package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Factory for the ultra-priority manual cash-desk gesture offer.
 * <p>
 * A basket line may carry a manual gesture: a fixed amount deducted, a percentage
 * reduction, or a forced unit price (the "label price"). When a line carries a discount
 * gesture it is handled here, ahead of every other offer, and is removed from the pool so
 * no other offer or discount can touch it. The gesture is computed on the resolved unit
 * price, so it applies on top of a forced price when both are present.
 */
@ApplicationScoped
public class ManualGestureOfferFactory implements OfferApplierFactory, EngineTrait {

    /**
     * Builds one applier per basket line that carries a manual discount gesture.
     *
     * @param basketEvaluation The basket evaluation context.
     * @return One applier per gesture line; empty when no line carries a gesture.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create appliers without a valid basket context.");
        Store store = basketEvaluation.getStore();
        if (basket.items == null) {
            return appliers;
        }
        for (Basket.Item item : basket.items) {
            item.validateManualGesture();
            if (item.hasManualGesture()) {
                appliers.add(new ManualGestureOfferApplier(store, item));
            }
        }
        return appliers;
    }

    /**
     * Applier for a single line bearing a manual gesture.
     * <p>
     * It reports an efficiency score above any computed one, so the engine sorts it first
     * and it consumes its line before any other offer sees it.
     */
    public static class ManualGestureOfferApplier extends OfferApplier {

        private final Store store;
        private final Basket.Item item;

        /**
         * Constructs the applier for a gesture-bearing line.
         *
         * @param store The store context, used to resolve the base price.
         * @param item  The basket line carrying the gesture.
         */
        public ManualGestureOfferApplier(Store store, Basket.Item item) {
            this.store = store;
            this.item = item;
        }

        /**
         * Reports the ultra-priority score.
         * <p>
         * The manual gesture must run before every other offer, so the score is fixed at
         * {@link Double#MAX_VALUE} rather than derived from the basket.
         *
         * @param basket The basket (unused; the score is constant).
         * @return {@link Double#MAX_VALUE}.
         */
        @Override
        public double computeEfficiencyScore(Basket basket) {
            return Double.MAX_VALUE;
        }

        /**
         * Consumes the gesture line and produces its application.
         *
         * @param basketEvaluation The evaluation context.
         * @return A single application, or empty when the line is already gone.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            double remaining = basketEvaluation.remainingQuantity(item.produceEan);
            if (remaining <= 0.0) {
                return List.of();
            }
            // Consume this line's quantity. Because a gesture line has its own price profile,
            // it is picked as its own slice(s), never merged with catalog-priced lines.
            List<Basket.Item> slices = basketEvaluation.pick(item.quantity, item.produceEan);
            List<OfferApplication> applications = new ArrayList<>();
            for (Basket.Item slice : slices) {
                // Deliberately NOT added to the upcell pool: a gesture line is excluded from
                // every other offer, so it must not feed upsell suggestions either.
                applications.add(new ManualGestureApplication(store, slice, item));
            }
            return applications;
        }
    }

    /**
     * The result of a manual gesture applied to one consumed slice.
     */
    public static class ManualGestureApplication implements OfferApplication {

        private final Store store;
        private final Basket.Item item;
        private final Basket.Item gestureSource;

        /**
         * Constructs the application.
         *
         * @param store         The store context, used to resolve the base price.
         * @param item          The consumed slice being priced.
         * @param gestureSource The original line carrying the gesture values.
         */
        public ManualGestureApplication(Store store, Basket.Item item, Basket.Item gestureSource) {
            this.store = store;
            this.item = item;
            this.gestureSource = gestureSource;
        }

        /**
         * Computes the amount for the covered quantity after the manual gesture.
         * <p>
         * The base price is resolved as usual (a forced price when present, otherwise the
         * catalog price). A fixed amount is deducted per unit, or a percentage is applied,
         * on the tax-included price; the tax-excluded figure is rebuilt at the base VAT
         * rate, which the gesture does not change. The unit result is floored at zero.
         *
         * @return The {@link AmountEvaluation} after the gesture.
         */
        @Override
        public AmountEvaluation getAmount() {
            Price base = item.getPrice(store, PriceUsage.DEFAULT);
            BigDecimal rate = base.vatRate;
            BigDecimal unitTtc;

            if (gestureSource.manualForcedPrice != null) {
                // Forced price replaces the catalog price outright; the catalog VAT rate is kept.
                unitTtc = gestureSource.manualForcedPrice;
            } else if (gestureSource.manualDiscountAmount != null) {
                unitTtc = base.priceIncludingTax.subtract(gestureSource.manualDiscountAmount);
            } else if (gestureSource.manualDiscountPercent != null) {
                BigDecimal keep = BigDecimal.ONE.subtract(
                        gestureSource.manualDiscountPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                unitTtc = base.priceIncludingTax.multiply(keep);
            } else {
                unitTtc = base.priceIncludingTax;
            }
            if (unitTtc.compareTo(BigDecimal.ZERO) < 0) {
                unitTtc = BigDecimal.ZERO;
            }

            BigDecimal qty = BigDecimal.valueOf(item.quantity);
            BigDecimal totalTtc = unitTtc.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalHt = totalTtc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            return new AmountEvaluation(totalHt, totalTtc, rate);
        }

        /**
         * Returns the covered slice.
         *
         * @return A single-item collection.
         */
        @Override
        @com.fasterxml.jackson.annotation.JsonIgnore
        public Collection<Basket.Item> getItems() {
            return List.of(item);
        }

        /**
         * Values this line, split back across its source lines.
         *
         * @return The valued items, summing to {@link #getAmount()}.
         */
        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("items")
        public java.util.List<BasketEvaluation.Item> getValuedItems() {
            // The gesture amount, not the catalog price, is what must be split — so this does
            // not go through the catalog-weighted distributor. A single covered item is split
            // across its source lines by quantity, the residue landing on the last line.
            AmountEvaluation total = getAmount();
            List<BasketEvaluation.Item> result = new ArrayList<>();
            List<Basket.Item.SourceLine> sources = item.sourceLines;
            if (sources == null || sources.isEmpty()) {
                result.add(new BasketEvaluation.Item(item, total));
                return result;
            }
            double totalQty = 0.0;
            for (Basket.Item.SourceLine s : sources) {
                totalQty += s.quantity;
            }
            BigDecimal assignedHt = BigDecimal.ZERO;
            BigDecimal assignedTtc = BigDecimal.ZERO;
            for (int i = 0; i < sources.size(); i++) {
                Basket.Item.SourceLine s = sources.get(i);
                Basket.Item lineItem = new Basket.Item();
                lineItem.lineId = s.lineId;
                lineItem.produceEan = item.produceEan;
                lineItem.quantity = s.quantity;
                AmountEvaluation lineAmount;
                if (i == sources.size() - 1) {
                    lineAmount = new AmountEvaluation(
                            total.amountExcludingTax.subtract(assignedHt),
                            total.amountIncludingTax.subtract(assignedTtc),
                            total.vatRate);
                } else {
                    BigDecimal ratio = BigDecimal.valueOf(s.quantity / totalQty);
                    BigDecimal ht = total.amountExcludingTax.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal ttc = total.amountIncludingTax.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    lineAmount = new AmountEvaluation(ht, ttc, total.vatRate);
                    assignedHt = assignedHt.add(ht);
                    assignedTtc = assignedTtc.add(ttc);
                }
                result.add(new BasketEvaluation.Item(lineItem, lineAmount));
            }
            return result;
        }

        /**
         * Returns a descriptive type string naming the gesture.
         *
         * @return The application type.
         */
        @Override
        public String getType() {
            String gesture;
            if (gestureSource.manualForcedPrice != null) {
                gesture = "forced price " + gestureSource.manualForcedPrice;
            } else if (gestureSource.manualDiscountAmount != null) {
                gesture = "amount -" + gestureSource.manualDiscountAmount;
            } else {
                gesture = "percent -" + gestureSource.manualDiscountPercent + "%";
            }
            return "Manual Gesture: EAN=" + item.produceEan + " (" + gesture + ")";
        }
    }
}
