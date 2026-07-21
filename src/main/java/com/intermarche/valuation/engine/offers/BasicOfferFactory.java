package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
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
            // Check if the specific product for this applier still exists in the working map
            Basket.Item availableItem = evaluation.getToEvaluate().get(product.ean);
            if (availableItem != null) {
                // Pick the item (consume it from the evaluation context)
                Basket.Item pickedItem = evaluation.pick(availableItem.quantity, availableItem.produceEan);
                evaluation.addAvailableToUpcell(pickedItem);
                if (pickedItem != null) {
                    Price price = this.getDiscountAppliers().isEmpty() ? this.defaultPrice : this.refPrice;
                    return List.of(new BasicApplication(pickedItem, store, product, price));
                }
            }
            // If the item is gone (picked by another offer) return empty list
            return List.of();
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
    public static class BasicApplication implements ProductAwareOfferApplication {

        private final Basket.Item item;
        private final Store store;
        private final Product product;
        private final Price price;

        /**
         * Constructs a basic application holding an item and store context.
         *
         * @param item    The basket item to price.
         * @param store   The store context to find applicable prices.
         * @param product The product being priced.
         * @param price   The price entity to use.
         */
        public BasicApplication(Basket.Item item, Store store, Product product, Price price) {
            this.item = item;
            this.store = store;
            this.product = product;
            this.price = price;
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
        public Collection<Basket.Item> getItems() {
            if (item == null) {
                return List.of();
            }
            return List.of(item);
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
        public double getProductQuantity(Product product) {
            if (product != null && product.ean.equals(item.produceEan)) {
                return item.quantity;
            }
            return 0.0;
        }
    }
}