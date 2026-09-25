package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for creating {@link BasicOfferApplier} instances.
 * <p>
 * This bean is registered as a CDI component and implements
 * {@link OfferApplierFactory}.
 * It creates a specific applier instance for each unique product found in the basket.
 */
@ApplicationScoped
public class BasicOfferFactory implements OfferApplierFactory, EngineTrait {

    /**
     * Builds a collection of {@link BasicOfferApplier} instances for each unique product in the basket.
     *
     * @param basketEvaluation The basket evaluation context.
     * @return A collection of offer appliers.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create appliers without a valid basket context.");
        Store store = basketEvaluation.getStore();
        // Use a Set to ensure we create only one applier per unique EAN
        Set<String> processedEans = new HashSet<>();
        for (Basket.Item item : basket.items) {
            if (item.produceEan != null && !processedEans.contains(item.produceEan)) {
                Product product = item.getProduct();
                Price defaultPrice = item.getPrice(store, PriceUsage.DEFAULT);
                Price refPrice = item.getPrice(store, PriceUsage.BASE_FOR_DISCOUNT);
                // Create a dedicated applier for this specific product
                appliers.add(new BasicOfferApplier(store, product, defaultPrice, refPrice));
                processedEans.add(item.produceEan);
            }
        }
        return appliers;
    }

    /**
     * Basic implementation of {@link OfferApplier} specific to a single product EAN.
     * <p>
     * This applier attempts to pick the quantity of its specific target EAN from the
     * {@link BasketEvaluation} and creates a {@link BasicApplication} with the standard price.
     * <p>
     * It implements {@link ProductAwareOfferApplier} but returns an empty list of discounts
     * as the base offer does not generate its own internal discounts.
     */
    public static class BasicOfferApplier extends OfferApplier implements ProductAwareOfferApplier {

        private final Store store;
        private final Product product;
        private final Price defaultPrice;
        private final Price refPrice;

        /**
         * Constructs a BasicOfferApplier for a specific product and store.
         *
         * @param store        The store context for pricing.
         * @param product      The product this applier targets.
         * @param defaultPrice The default price to use for the product.
         * @param refPrice     The reference price for discount calculations.
         */
        public BasicOfferApplier(Store store, Product product, Price defaultPrice, Price refPrice) {
            this.store = store;
            this.product = product;
            this.defaultPrice = defaultPrice;
            this.refPrice = refPrice;
        }

        /**
         * Returns no configuration: the Basic valuation is not born from a configuration
         * row, so it carries no trigger and the arbitration treats it as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS}.
         *
         * @return always null.
         */
        @Override
        public Offer getConfiguration() {
            return null;
        }

        /**
         * Returns this applier's product EAN as its stable tie-break key (report C2).
         * <p>
         * The Basic valuation builds one applier per product with no configuration code, so the
         * default empty key would leave two products' appliers tied; the EAN gives the sort a
         * deterministic order among them.
         *
         * @return the product EAN.
         */
        @Override
        public String getTieBreakKey() {
            return product.ean;
        }

        /**
         * Applies the applier to the given basket evaluation.
         * <p>
         * Attempts to pick the quantity of its specific product EAN from the evaluation context.
         * If successful, creates a {@link BasicApplication} for the picked item.
         *
         * @param evaluation The basket evaluation context.
         * @return A collection containing the offer application if the item was picked; otherwise, an empty list.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) {
            // How much of this product remains, across all its price entries.
            BigDecimal remaining = evaluation.remainingQuantity(product.ean);
            if (remaining.signum() <= 0) {
                // If the item is gone (picked by another offer) return empty list
                return List.of();
            }
            // Consume it. pick may return several slices when the product carries more than
            // one price; each slice is mono-price and becomes its own standard line, so a
            // product priced two ways yields two applications rather than a blended one.
            List<Basket.Item> slices = evaluation.pick(remaining, product.ean);
            // A1 (report C1): value the line at the DEFAULT price during the valuation, whatever
            // discount appliers are registered. The switch to the reference price
            // (BASE_FOR_DISCOUNT) is deferred to a post-arbitration re-pricing step in the engine,
            // which touches only the lines that end up carrying an actually-retained discount — so
            // a discarded advantage never leaves the customer paying the (higher) reference price.
            List<OfferApplication> applications = new ArrayList<>();
            for (Basket.Item slice : slices) {
                evaluation.addAvailableToUpcell(slice);
                applications.add(new BasicApplication(slice, store, product, this.defaultPrice, this.refPrice));
            }
            return applications;
        }

        /**
         * Determines if this applier is applicable to the given product.
         *
         * @param product The product to check applicability against.
         * @return True if this applier can be applied to the provided product; false otherwise.
         */
        @Override
        public boolean isApplicable(Product product) {
            return this.product.ean.equals(product.ean);
        }
    }

    /**
     * Default implementation of {@link OfferApplication} representing standard item pricing.
     * <p>
     * This class stores a reference to a {@link Basket.Item} and {@link Store}.
     * The price calculation is performed on-demand via {@link #getAmount()} and returns
     * a {@link AmountEvaluation}.
     */
    public static class BasicApplication implements ProductAwareOfferApplication,
            com.intermarche.valuation.engine.RepriceableApplication {

        private final Basket.Item item;
        private final Store store;
        private final Product product;
        /**
         * The price currently used to value this line. Starts at the default price and is
         * switched to the reference price by {@link #repriceToReference()} once the arbitration
         * has retained a discount on this line (A1, report C1). Not final for that reason.
         */
        private Price price;
        /**
         * The reference price ({@code BASE_FOR_DISCOUNT}) this line switches to when it carries
         * a retained discount, or {@code null} when the product has no reference price row.
         */
        private final Price referencePrice;

        /**
         * Constructs a basic application holding an item and store context.
         *
         * @param item           The basket item to price.
         * @param store          The store context to find applicable prices.
         * @param product        The product being priced.
         * @param price          The default price entity to value the line with.
         * @param referencePrice The reference price to switch to when a discount is retained,
         *                       or {@code null} when none exists.
         */
        public BasicApplication(Basket.Item item, Store store, Product product, Price price, Price referencePrice) {
            this.item = item;
            this.store = store;
            this.product = product;
            this.price = price;
            this.referencePrice = referencePrice;
        }

        /**
         * Constructs a basic application with no reference price: the line is valued at the given
         * price and {@link #repriceToReference()} is a no-op.
         *
         * @param item    The basket item to price.
         * @param store   The store context to find applicable prices.
         * @param product The product being priced.
         * @param price   The price entity to value the line with.
         */
        public BasicApplication(Basket.Item item, Store store, Product product, Price price) {
            this(item, store, product, price, null);
        }

        /**
         * Switches this line from the default price to the reference price (A1, report C1).
         * <p>
         * Called by the engine's post-arbitration re-pricing step for the lines that end up
         * carrying at least one actually-retained discount, so the discount is measured against
         * the reference price rather than the default one. A no-op when no reference price exists
         * or the line already sits on it; {@link #getAmount()} recomputes lazily from the price
         * field, so the offer amount and its valued items follow automatically.
         *
         * @return {@code true} when the price was switched, {@code false} otherwise.
         */
        @Override
        public boolean repriceToReference() {
            if (referencePrice != null && referencePrice != price) {
                this.price = referencePrice;
                return true;
            }
            return false;
        }

        /**
         * Calculates the total price for the covered item quantity.
         *
         * @return The {@link AmountEvaluation} for the item quantity.
         */
        @Override
        public AmountEvaluation getAmount() {
            // Calculate total price for the item quantity
            return AmountEvaluation.getAmount(this.product, this.price, this.item.quantity);
        }

        /**
         * Returns the single item covered by this application.
         *
         * @return A collection containing the single item.
         */
        @Override
        @com.fasterxml.jackson.annotation.JsonIgnore
        public Collection<Basket.Item> getItems() {
            if (item == null) {
                return List.of();
            }
            return List.of(item);
        }

        /**
         * Values this standard line, split back across its source lines.
         * <p>
         * A standard application covers a single mono-price item, so its whole amount is
         * distributed over that item's source lines by the generic rule. When the item came
         * from one request line, this yields exactly one valued item.
         *
         * @return The valued items, one per source line, summing to {@link #getAmount()}.
         */
        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("items")
        public java.util.List<BasketEvaluation.Item> getValuedItems() {
            if (item == null) {
                return java.util.List.of();
            }
            return ItemValuation.distribute(getAmount(), java.util.List.of(item), store);
        }

        /**
         * Returns a string representation of the offer application type.
         *
         * @return A descriptive string of the application.
         */
        @Override
        public String getType() {
            return "Standard: EAN=" + item.produceEan + ", Qty=" + item.quantity;
        }

        /**
         * Retrieves the price evaluation if the product of the covered item matches.
         * <p>
         * Used by {@link ImmediateVoucherDiscountFactory} to calculate discounts on specific products.
         *
         * @param product The product for which to retrieve the amount evaluation.
         * @return The {@link AmountEvaluation} for the product, or null if not applicable.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            if (product != null && product.ean.equals(item.produceEan)) {
                return getAmount();
            }
            return null;
        }

        /**
         * Retrieves the quantity of the product covered by this application.
         * <p>
         * Used by {@link ImmediateVoucherDiscountFactory}.
         *
         * @param product The product for which to retrieve the quantity.
         * @return The quantity of the specified product, or 0 if not applicable.
         */
        @Override
        public BigDecimal getProductQuantity(Product product) {
            if (product != null && product.ean.equals(item.produceEan)) {
                return item.quantity;
            }
            return BigDecimal.ZERO;
        }
    }
}