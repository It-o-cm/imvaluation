package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link OfferApplierFactory}.
 * <p>
 * This SPI interface exposes one abstract method ({@code buildAppliers}) and two
 * default methods ({@code getOfferType} and {@code getSchema}) that both return
 * {@code null} unconditionally. The interface carries no conditional logic, so
 * there are no branches to exercise; the tests pin the default return values and
 * verify a minimal implementation can honour the abstract contract.
 */
public class OfferApplierFactoryTest {

    /**
     * Builds a minimal concrete factory that overrides only the abstract
     * {@code buildAppliers} method, leaving the two default methods untouched.
     *
     * @param appliersToReturn The collection the stubbed {@code buildAppliers} should return.
     * @return A concrete {@link OfferApplierFactory} instance.
     */
    private OfferApplierFactory createMinimalFactory(Collection<OfferApplier> appliersToReturn) {
        return new OfferApplierFactory() {
            /**
             * Returns the pre-configured collection of appliers.
             *
             * @param basketEvaluation The evaluation context (ignored by this stub).
             * @return The collection supplied at construction time.
             */
            @Override
            public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
                return appliersToReturn;
            }
        };
    }

    /**
     * Verifies that the default {@link OfferApplierFactory#getOfferType()} implementation
     * returns {@code null} when not overridden.
     */
    @Test
    void testGetOfferTypeDefaultsToNull() {
        OfferApplierFactory factory = createMinimalFactory(List.of());
        assertNull(factory.getOfferType());
    }

    /**
     * Verifies that the default {@link OfferApplierFactory#getSchema()} implementation
     * returns {@code null} when not overridden.
     */
    @Test
    void testGetSchemaDefaultsToNull() {
        OfferApplierFactory factory = createMinimalFactory(List.of());
        assertNull(factory.getSchema());
    }

    /**
     * Verifies that the abstract {@link OfferApplierFactory#buildAppliers(BasketEvaluation)}
     * contract returns exactly the collection produced by the implementation.
     */
    @Test
    void testBuildAppliersReturnsImplementationResult() {
        OfferApplier applier = new OfferApplier() {
            /**
             * Stub returning no offer applications.
             *
             * @param basketEvaluation The evaluation context (ignored).
             * @return An empty collection.
             */
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return List.of();
            }
        };
        Collection<OfferApplier> expected = List.of(applier);
        OfferApplierFactory factory = createMinimalFactory(expected);
        Collection<OfferApplier> actual = factory.buildAppliers(null);
        assertSame(expected, actual);
        assertEquals(1, actual.size());
        assertTrue(actual.contains(applier));
    }
}
