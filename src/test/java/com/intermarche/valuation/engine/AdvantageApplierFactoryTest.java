package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link AdvantageApplierFactory}.
 * <p>
 * This SPI interface exposes one abstract method ({@code buildAppliers}) and two
 * default methods ({@code getOfferType} and {@code getSchema}) that both return
 * {@code null} unconditionally. The interface carries no conditional logic, so
 * there are no branches to exercise; the tests pin the default return values and
 * verify a minimal implementation can honour the abstract contract.
 */
public class AdvantageApplierFactoryTest {

    /**
     * Builds a minimal concrete factory that overrides only the abstract
     * {@code buildAppliers} method, leaving the two default methods untouched.
     *
     * @param appliersToReturn The collection the stubbed {@code buildAppliers} should return.
     * @return A concrete {@link AdvantageApplierFactory} instance.
     */
    private AdvantageApplierFactory createMinimalFactory(Collection<AdvantageApplier> appliersToReturn) {
        return new AdvantageApplierFactory() {
            /**
             * Returns the pre-configured collection of appliers.
             *
             * @param basketEvaluation The evaluation context (ignored by this stub).
             * @return The collection supplied at construction time.
             */
            @Override
            public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
                return appliersToReturn;
            }
        };
    }

    /**
     * Verifies that the default {@link AdvantageApplierFactory#getOfferType()} implementation
     * returns {@code null} when not overridden.
     */
    @Test
    void testGetOfferTypeDefaultsToNull() {
        AdvantageApplierFactory factory = createMinimalFactory(List.of());
        assertNull(factory.getOfferType());
    }

    /**
     * Verifies that the default {@link AdvantageApplierFactory#getSchema()} implementation
     * returns {@code null} when not overridden.
     */
    @Test
    void testGetSchemaDefaultsToNull() {
        AdvantageApplierFactory factory = createMinimalFactory(List.of());
        assertNull(factory.getSchema());
    }

    /**
     * Verifies that the abstract {@link AdvantageApplierFactory#buildAppliers(BasketEvaluation)}
     * contract returns exactly the collection produced by the implementation.
     */
    @Test
    void testBuildAppliersReturnsImplementationResult() {
        AdvantageApplier applier = new AdvantageApplier() {
            /**
             * Stub that is never applicable.
             *
             * @param offerApplier The offer applier to check (ignored).
             * @return Always {@code false}.
             */
            @Override
            public boolean isApplicable(OfferApplier offerApplier) {
                return false;
            }

            /**
             * Stub returning no advantage applications.
             *
             * @param basketEvaluation The evaluation context (ignored).
             * @return An empty collection.
             */
            @Override
            public Collection<AdvantageApplication> apply(BasketEvaluation basketEvaluation) {
                return List.of();
            }

            /**
             * Stub efficiency score.
             *
             * @return Always {@code 0.0}.
             */
            @Override
            public double getEfficiencyScore() {
                return 0.0;
            }
        };
        Collection<AdvantageApplier> expected = List.of(applier);
        AdvantageApplierFactory factory = createMinimalFactory(expected);
        Collection<AdvantageApplier> actual = factory.buildAppliers(null);
        assertSame(expected, actual);
        assertEquals(1, actual.size());
        assertTrue(actual.contains(applier));
    }
}
