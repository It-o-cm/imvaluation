package com.intermarche.valuation.engine;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link OfferApplication}.
 * <p>
 * {@code OfferApplication} is an SPI with three abstract accessors and a single default
 * method {@link OfferApplication#getValuedItems()}. The default has no branches: it always
 * returns an empty immutable list. These tests exercise a minimal concrete implementation
 * that inherits the default and pins the pass-through behaviour of the abstract accessors.
 */
class OfferApplicationTest {

    /**
     * Minimal concrete {@link OfferApplication} that implements only the abstract accessors
     * and inherits the {@link OfferApplication#getValuedItems()} default, so the default's
     * behaviour can be observed in isolation.
     */
    private static final class TestOfferApplication implements OfferApplication {

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
         * Creates a test application wrapping the supplied accessor values.
         *
         * @param amount The amount to expose.
         * @param items  The covered items to expose.
         * @param type   The type label to expose.
         */
        private TestOfferApplication(AmountEvaluation amount, Collection<Basket.Item> items, String type) {
            this.amount = amount;
            this.items = items;
            this.type = type;
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
    }

    /**
     * The inherited default {@link OfferApplication#getValuedItems()} returns an empty list
     * when the implementation does not override it.
     */
    @Test
    void getValuedItemsDefaultsToEmptyList() {
        OfferApplication application = new TestOfferApplication(new AmountEvaluation(), List.of(), "TEST");
        List<BasketEvaluation.Item> valued = application.getValuedItems();
        Assertions.assertNotNull(valued);
        Assertions.assertTrue(valued.isEmpty());
    }

    /**
     * The inherited default {@link OfferApplication#getValuedItems()} is unaffected by the
     * covered items exposed through {@link OfferApplication#getItems()}: it still returns
     * an empty list even when the application covers priced basket lines.
     */
    @Test
    void getValuedItemsIgnoresCoveredItems() {
        Basket.Item covered = Mockito.mock(Basket.Item.class);
        OfferApplication application = new TestOfferApplication(new AmountEvaluation(), List.of(covered), "TEST");
        List<BasketEvaluation.Item> valued = application.getValuedItems();
        Assertions.assertTrue(valued.isEmpty());
    }

    /**
     * The abstract accessors pass their supplied values straight through unchanged.
     */
    @Test
    void abstractAccessorsExposeSuppliedValues() {
        AmountEvaluation amount = new AmountEvaluation();
        Basket.Item covered = Mockito.mock(Basket.Item.class);
        Collection<Basket.Item> items = List.of(covered);
        OfferApplication application = new TestOfferApplication(amount, items, "MANUAL");
        Assertions.assertSame(amount, application.getAmount());
        Assertions.assertSame(items, application.getItems());
        Assertions.assertEquals("MANUAL", application.getType());
    }
}
