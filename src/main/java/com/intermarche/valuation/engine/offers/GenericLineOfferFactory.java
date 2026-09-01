package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.OfferApplierFactory;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Values the basket lines that carry no EAN: unknown articles sold at a price typed at the
 * register.
 * <p>
 * Such a line is legal when it carries the complete price triplet
 * ({@code pricePerUnitExclTax}, {@code pricePerUnitInclTax}, {@code vatRate} — enforced by
 * the basket schema). The amount is simply the given unit price multiplied by the quantity:
 * the engine's product types (UNIT/WEIGHT/VOLUME) only exist to convert catalog reference
 * prices, so no type is needed here — the price is understood as "per unit of whatever the
 * quantity is expressed in" (per piece, per kilogram, per litre).
 * <p>
 * Having no EAN, a generic line is out of reach of every EAN-targeted mechanism (N+M,
 * bundles, vouchers, vignettes, family flags, upsell) by construction, but it does count in
 * the basket total, in the VAT breakdown (at the provided rate) and — being product-aware —
 * in the merchandise total used by the free-delivery threshold.
 * <p>
 * Like {@link BasicOfferFactory}, this factory is not driven by any database offer: it has
 * no type and no schema, and is always active.
 */
@ApplicationScoped
public class GenericLineOfferFactory implements OfferApplierFactory {

    /**
     * Builds one applier per basket line without an EAN.
     * <p>
     * The schema already guarantees the price triplet on such lines for requests entering
     * through the REST resource; the defensive validation below keeps the invariant for
     * direct engine calls too.
     *
     * @param basketEvaluation The evaluation context containing the basket.
     * @return One applier per generic line, possibly empty.
     * @throws IllegalStateException if the context has no basket, or if a line without an
     *                               EAN lacks part of the price triplet.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        if (basketEvaluation == null || basketEvaluation.getBasket() == null) {
            throw new IllegalStateException("Cannot create generic line appliers without a valid basket context.");
        }
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = basketEvaluation.getBasket();
        if (basket.items == null) {
            return appliers;
        }
        for (Basket.Item item : basket.items) {
            if (item.produceEan != null) {
                continue;
            }
            if (item.pricePerUnitExclTax == null || item.pricePerUnitInclTax == null || item.vatRate == null) {
                throw new IllegalStateException(String.format(
                        "Configuration Error: Item without EAN on line '%s' requires "
                                + "pricePerUnitExclTax, pricePerUnitInclTax and vatRate.",
                        item.lineId));
            }
            appliers.add(new GenericLineOfferApplier(item));
        }
        return appliers;
    }

    /**
     * Applier dedicated to one generic basket line.
     * <p>
     * It consumes its own line from the evaluation pool by price profile (the {@code null}
     * EAN bucket), so a manual gesture carried by the same line — served first by its
     * ultra-priority — naturally leaves nothing for this applier to value.
     */
    public static class GenericLineOfferApplier extends OfferApplier {

        /**
         * The basket line this applier values.
         */
        private final Basket.Item item;

        /**
         * Creates an applier for one generic line.
         *
         * @param item The basket line without an EAN, carrying the complete price triplet.
         */
        public GenericLineOfferApplier(Basket.Item item) {
            this.item = item;
        }

        /**
         * Returns no configuration: a generic line valuation is not born from a
         * configuration row, so it carries no trigger and the arbitration treats it as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS}.
         *
         * @return always null.
         */
        @Override
        public Offer getConfiguration() {
            return null;
        }

        /**
         * Consumes the line from the pool and values it at the price it carries.
         *
         * @param basketEvaluation The evaluation context.
         * @return A single application for the consumed slice, or none when the line was
         *         already consumed (for example by a manual gesture).
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            List<OfferApplication> applications = new ArrayList<>();
            List<Basket.Item> slices = basketEvaluation.pickMatching(item.quantity, item);
            for (Basket.Item slice : slices) {
                applications.add(new GenericLineApplication(slice));
            }
            return applications;
        }

        /**
         * Returns a neutral efficiency score.
         * <p>
         * A typed-price line is neither a promotion nor a cost: it ranks with the standard
         * lines, after every discount-bearing offer.
         *
         * @param basket The basket context.
         * @return {@code 0.0}.
         */
        @Override
        public double computeEfficiencyScore(Basket basket) {
            return 0.0;
        }
    }

    /**
     * The valuation of one generic line: given unit price multiplied by the quantity.
     * <p>
     * Product-aware so the line counts as merchandise (free-delivery threshold), while
     * answering {@code null}/zero for any actual product: no EAN-targeted discount can ever
     * reach it.
     */
    public static class GenericLineApplication implements ProductAwareOfferApplication {

        /**
         * The consumed slice (line id, quantity, price triplet, source lines).
         */
        private final Basket.Item slice;

        /**
         * Creates the application for a consumed slice.
         *
         * @param slice The slice consumed from the evaluation pool.
         */
        public GenericLineApplication(Basket.Item slice) {
            this.slice = slice;
        }

        /**
         * Computes the total amount: unit price times quantity, at the provided VAT rate.
         *
         * @return The amount of this line.
         */
        @Override
        public AmountEvaluation getAmount() {
            double quantity = slice.quantity == null ? 0.0 : slice.quantity;
            AmountEvaluation unit = new AmountEvaluation(
                    slice.pricePerUnitExclTax, slice.pricePerUnitInclTax, slice.vatRate);
            return unit.multiply(BigDecimal.valueOf(quantity));
        }

        /**
         * Returns the consumed slice backing this application.
         *
         * @return A single-element collection holding the slice.
         */
        @Override
        @JsonIgnore
        public Collection<Basket.Item> getItems() {
            return List.of(slice);
        }

        /**
         * Splits the line amount back over its source lines, pro-rata of their quantities.
         * <p>
         * The residual cent goes to the last source line so the items always sum to
         * {@link #getAmount()} exactly. Without source lines, the slice is reported as its
         * own single line.
         *
         * @return The per-line valuation of this application.
         */
        @Override
        @JsonProperty("items")
        public List<BasketEvaluation.Item> getValuedItems() {
            AmountEvaluation total = getAmount();
            List<BasketEvaluation.Item> valued = new ArrayList<>();
            List<Basket.Item.SourceLine> sources = slice.sourceLines;
            if (sources == null || sources.isEmpty()) {
                valued.add(new BasketEvaluation.Item(slice, total));
                return valued;
            }
            BigDecimal remainingTtc = total.amountIncludingTax;
            BigDecimal remainingHt = total.amountExcludingTax;
            for (int i = 0; i < sources.size(); i++) {
                Basket.Item.SourceLine source = sources.get(i);
                BigDecimal ttc;
                BigDecimal ht;
                if (i == sources.size() - 1) {
                    // Last line absorbs the rounding residual so the sum is exact.
                    ttc = remainingTtc;
                    ht = remainingHt;
                } else {
                    BigDecimal qty = BigDecimal.valueOf(source.quantity);
                    ttc = slice.pricePerUnitInclTax.multiply(qty).setScale(2, RoundingMode.HALF_UP);
                    ht = slice.pricePerUnitExclTax.multiply(qty).setScale(2, RoundingMode.HALF_UP);
                    remainingTtc = remainingTtc.subtract(ttc);
                    remainingHt = remainingHt.subtract(ht);
                }
                BasketEvaluation.Item valuedItem = new BasketEvaluation.Item();
                valuedItem.lineId = source.lineId;
                valuedItem.produceEan = null;
                valuedItem.quantity = source.quantity;
                valuedItem.amount = new AmountEvaluation(ht, ttc, total.vatRate);
                valued.add(valuedItem);
            }
            return valued;
        }

        /**
         * Returns the display type of this application.
         *
         * @return A string of the form {@code Generic: Line=<lineId>, Qty=<quantity>}.
         */
        @Override
        public String getType() {
            return "Generic: Line=" + slice.lineId + ", Qty=" + slice.quantity;
        }

        /**
         * A generic line covers no cataloged product.
         *
         * @param product The product being asked about.
         * @return {@code null}, always.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return null;
        }

        /**
         * A generic line covers no cataloged product.
         *
         * @param product The product being asked about.
         * @return {@code 0.0}, always.
         */
        @Override
        public double getProductQuantity(Product product) {
            return 0.0;
        }
    }
}
