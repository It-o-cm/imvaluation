package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Product;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ProductAwareOfferApplication}.
 * <p>
 * {@code ProductAwareOfferApplication} is an SPI that extends {@link OfferApplication} with
 * two additional abstract accessors, {@link ProductAwareOfferApplication#getProductAmount(Product)}
 * and {@link ProductAwareOfferApplication#getProductQuantity(Product)}. It declares no default
 * methods and holds no branchable code, so there is no conditional logic to cover. These tests
 * exercise a minimal concrete implementation to pin the pass-through behaviour of the new
 * accessors, confirm the inherited {@link OfferApplication#getValuedItems()} default still
 * yields an empty list, and verify that both product-aware accessors are keyed by the product
 * argument.
 */
class ProductAwareOfferApplicationTest {

    /**
     * Minimal concrete {@link ProductAwareOfferApplication} that implements the inherited
     * abstract accessors and the two product-aware accessors, inheriting the
     * {@link OfferApplication#getValuedItems()} default so it can be observed in isolation.
     */
    private static final class TestProductAwareOfferApplication implements ProductAwareOfferApplication {

        /**
         * The amount returned by {@link #getAmount()}.
         */
        private final AmountEvaluation amount;

        /**
         * The items returned by {@link #getItems()}.
         */
        private final Collection<Basket.Item> items;

        /**
         * The type label returned by {@link #getType()}.
         */
        private final String type;

        /**
         * The product keying the per-product accessors; a call with any other product returns
         * the neutral values.
         */
        private final Product keyProduct;

        /**
         * The amount returned by {@link #getProductAmount(Product)} for {@link #keyProduct}.
         */
        private final AmountEvaluation productAmount;

        /**
         * The quantity returned by {@link #getProductQuantity(Product)} for {@link #keyProduct}.
         */
        private final double productQuantity;

        /**
         * Creates a test application wrapping the supplied accessor values.
         *
         * @param amount          The amount to expose through {@link #getAmount()}.
         * @param items           The covered items to expose through {@link #getItems()}.
         * @param type            The type label to expose through {@link #getType()}.
         * @param keyProduct      The product the per-product accessors are keyed on.
         * @param productAmount   The amount to expose for {@code keyProduct}.
         * @param productQuantity The quantity to expose for {@code keyProduct}.
         */
        private TestProductAwareOfferApplication(AmountEvaluation amount, Collection<Basket.Item> items, String type,
                Product keyProduct, AmountEvaluation productAmount, double productQuantity) {
            this.amount = amount;
            this.items = items;
            this.type = type;
            this.keyProduct = keyProduct;
            this.productAmount = productAmount;
            this.productQuantity = productQuantity;
        }

        /**
         * Returns the wrapped amount.
         *
         * @return The amount supplied at construction.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns the wrapped covered items.
         *
         * @return The items supplied at construction.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return items;
        }

        /**
         * Returns the wrapped type label.
         *
         * @return The type supplied at construction.
         */
        @Override
        public String getType() {
            return type;
        }

        /**
         * Returns the per-product amount for the keyed product, or a neutral empty evaluation
         * for any other product.
         *
         * @param product The product for which to retrieve the amount evaluation.
         * @return The wrapped product amount when {@code product} is the keyed product.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return product == keyProduct ? productAmount : new AmountEvaluation();
        }

        /**
         * Returns the per-product quantity for the keyed product, or zero for any other product.
         *
         * @param product The product for which to retrieve the quantity.
         * @return The wrapped product quantity when {@code product} is the keyed product.
         */
        @Override
        public double getProductQuantity(Product product) {
            return product == keyProduct ? productQuantity : 0.0d;
        }
    }

    /**
     * The two product-aware accessors return the values supplied for the keyed product.
     */
    @Test
    void productAccessorsExposeSuppliedValuesForKeyedProduct() {
        Product product = Mockito.mock(Product.class);
        AmountEvaluation productAmount = new AmountEvaluation();
        ProductAwareOfferApplication application = new TestProductAwareOfferApplication(new AmountEvaluation(),
                List.of(), "PRODUCT_AWARE", product, productAmount, 2.5d);
        Assertions.assertSame(productAmount, application.getProductAmount(product));
        Assertions.assertEquals(2.5d, application.getProductQuantity(product));
    }

    /**
     * The product-aware accessors are keyed by their argument: a different product yields the
     * neutral empty amount and a zero quantity rather than the keyed product's values.
     */
    @Test
    void productAccessorsAreKeyedByProduct() {
        Product keyProduct = Mockito.mock(Product.class);
        Product otherProduct = Mockito.mock(Product.class);
        AmountEvaluation productAmount = new AmountEvaluation();
        ProductAwareOfferApplication application = new TestProductAwareOfferApplication(new AmountEvaluation(),
                List.of(), "PRODUCT_AWARE", keyProduct, productAmount, 2.5d);
        Assertions.assertNotSame(productAmount, application.getProductAmount(otherProduct));
        Assertions.assertEquals(0.0d, application.getProductQuantity(otherProduct));
    }

    /**
     * The abstract accessors inherited from {@link OfferApplication} pass their supplied values
     * straight through unchanged.
     */
    @Test
    void inheritedAccessorsExposeSuppliedValues() {
        AmountEvaluation amount = new AmountEvaluation();
        Basket.Item covered = Mockito.mock(Basket.Item.class);
        Collection<Basket.Item> items = List.of(covered);
        Product product = Mockito.mock(Product.class);
        ProductAwareOfferApplication application = new TestProductAwareOfferApplication(amount, items, "MANUAL",
                product, new AmountEvaluation(), 1.0d);
        Assertions.assertSame(amount, application.getAmount());
        Assertions.assertSame(items, application.getItems());
        Assertions.assertEquals("MANUAL", application.getType());
    }

    /**
     * The inherited default {@link OfferApplication#getValuedItems()} still returns an empty
     * list when a product-aware implementation does not override it.
     */
    @Test
    void getValuedItemsDefaultsToEmptyList() {
        Product product = Mockito.mock(Product.class);
        ProductAwareOfferApplication application = new TestProductAwareOfferApplication(new AmountEvaluation(),
                List.of(), "PRODUCT_AWARE", product, new AmountEvaluation(), 1.0d);
        List<BasketEvaluation.Item> valued = application.getValuedItems();
        Assertions.assertNotNull(valued);
        Assertions.assertTrue(valued.isEmpty());
    }
}
