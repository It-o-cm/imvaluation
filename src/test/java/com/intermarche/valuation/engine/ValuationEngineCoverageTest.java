package com.intermarche.valuation.engine;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code @QuarkusTest} coverage of {@link ValuationEngine}.
 * <p>
 * The existing {@code ValuationEngineTest} is a plain Mockito unit test, and the
 * quarkus-jacoco report in this project only attributes lines executed inside a
 * {@code @QuarkusTest}. This class re-drives the error-handling {@code catch} blocks,
 * the applier ordering, and the public total helpers from within a {@code @QuarkusTest}
 * so those lines are attributed. Collaborators are plain Mockito mocks created inline;
 * no database is touched.
 */
@QuarkusTest
public class ValuationEngineCoverageTest {

    /**
     * Covers the {@code catch} block of {@code createOfferApplications}: a generic (non
     * {@code ConfigurationException}) offer applier error propagates unwrapped (A2, report H1a).
     */
    @Test
    void createOfferApplicationsWrapsApplierException() {
        OfferApplier throwing = new OfferApplier() {
            /**
             * Always throws to drive the engine's applier error path.
             *
             * @param basketEvaluation The evaluation context (unused).
             * @return Never returns; always throws.
             */
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                throw new IllegalStateException("boom-offer");
            }

            /**
             * Returns no configuration: this test double is not born from a configuration row.
             *
             * @return always null.
             */
            @Override
            public com.intermarche.valuation.domain.Offer getConfiguration() {
                return null;
            }
        };
        List<OfferApplier> appliers = new ArrayList<>();
        appliers.add(throwing);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        ValuationEngine engine = new ValuationEngine();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> engine.createOfferApplications(appliers, evaluation));
        assertTrue(ex.getMessage().contains("boom-offer"));
    }

    /**
     * Covers the {@code catch} block of {@code createDiscountApplications}: a generic (non
     * {@code ConfigurationException}) discount applier error propagates unwrapped (A2, report H1a).
     */
    @Test
    void createDiscountApplicationsWrapsApplierException() {
        AdvantageApplier throwing = mock(AdvantageApplier.class);
        when(throwing.apply(any())).thenThrow(new IllegalStateException("boom-discount"));
        List<AdvantageApplier> appliers = new ArrayList<>();
        appliers.add(throwing);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        // The two-wave arbitration (spec §4.2) consults the memoized trigger before applying;
        // a mocked evaluation must answer it so the apply() path (and its catch) is reached.
        when(evaluation.triggerResult(any(), any())).thenReturn(new TriggerResult(true, List.of()));
        ValuationEngine engine = new ValuationEngine();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> engine.createDiscountApplications(appliers, evaluation));
        assertTrue(ex.getMessage().contains("boom-discount"));
    }

    /**
     * Covers the {@code catch} block of {@code createDiscountAppliers}: a generic (non
     * {@code ConfigurationException}) factory error propagates unwrapped (A2, report H1a).
     */
    @Test
    @SuppressWarnings("unchecked")
    void createDiscountAppliersWrapsFactoryException() {
        Instance<AdvantageApplierFactory> factories = mock(Instance.class);
        AdvantageApplierFactory factory = mock(AdvantageApplierFactory.class);
        when(factories.iterator()).thenReturn(List.of(factory).iterator());
        when(factory.buildAppliers(any())).thenThrow(new RuntimeException("build-boom"));
        ValuationEngine engine = new ValuationEngine();
        engine.discountFactories = factories;
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> engine.createDiscountAppliers(evaluation));
        assertTrue(ex.getMessage().contains("build-boom"));
    }

    /**
     * Covers the fail-closed branch (A2, report H1a): a factory whose {@code buildAppliers}
     * throws a {@link ConfigurationException} is skipped rather than propagated, the skip is
     * recorded on the evaluation, and the evaluation continues (here, with no appliers).
     */
    @Test
    @SuppressWarnings("unchecked")
    void createDiscountAppliersSkipsConfigurationException() {
        Instance<AdvantageApplierFactory> factories = mock(Instance.class);
        AdvantageApplierFactory factory = mock(AdvantageApplierFactory.class);
        when(factories.iterator()).thenReturn(List.of(factory).iterator());
        when(factory.buildAppliers(any())).thenThrow(new ConfigurationException("bad-spec"));
        ValuationEngine engine = new ValuationEngine();
        engine.discountFactories = factories;
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        List<AdvantageApplier> appliers = engine.createDiscountAppliers(evaluation);
        assertTrue(appliers.isEmpty(), "a corrupted configuration is skipped, not propagated");
        org.mockito.Mockito.verify(evaluation).recordSkippedConfiguration(any());
    }

    /**
     * Covers {@code calculateTotalHorsDiscount}: an offer with an amount is summed, and one
     * whose amount is {@code null} is skipped without failing.
     */
    @Test
    void calculateTotalHorsDiscountSkipsNullAmount() {
        OfferApplication withAmount = mock(OfferApplication.class);
        when(withAmount.getAmount()).thenReturn(
                new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), BigDecimal.ZERO));
        OfferApplication nullAmount = mock(OfferApplication.class);
        when(nullAmount.getAmount()).thenReturn(null);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getOffers()).thenReturn(List.of(withAmount, nullAmount));
        ValuationEngine engine = new ValuationEngine();
        assertEquals(new BigDecimal("12.00"), engine.calculateTotalHorsDiscount(evaluation));
    }

    /**
     * Covers {@code calculateTotalDiscount}: a discount with an amount is summed, a discount
     * whose amount is {@code null} is skipped, and a plain advantage (not a
     * {@link DiscountApplication}) is ignored by the {@code instanceof} guard.
     */
    @Test
    void calculateTotalDiscountHandlesNullAndNonDiscount() {
        DiscountApplication withAmount = mock(DiscountApplication.class);
        when(withAmount.getDiscountAmount()).thenReturn(
                new AmountEvaluation(new BigDecimal("5.00"), new BigDecimal("6.00"), BigDecimal.ZERO));
        DiscountApplication nullAmount = mock(DiscountApplication.class);
        when(nullAmount.getDiscountAmount()).thenReturn(null);
        AdvantageApplication nonDiscount = mock(AdvantageApplication.class);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(withAmount, nullAmount, nonDiscount));
        ValuationEngine engine = new ValuationEngine();
        assertEquals(new BigDecimal("6.00"), engine.calculateTotalDiscount(evaluation));
    }

    /**
     * Covers {@code calculateRealTotal}: total hors discount minus total discount.
     */
    @Test
    void calculateRealTotalSubtractsDiscounts() {
        OfferApplication offer = mock(OfferApplication.class);
        when(offer.getAmount()).thenReturn(
                new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), BigDecimal.ZERO));
        DiscountApplication discount = mock(DiscountApplication.class);
        when(discount.getDiscountAmount()).thenReturn(
                new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), BigDecimal.ZERO));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getOffers()).thenReturn(List.of(offer));
        when(evaluation.getAdvantages()).thenReturn(List.of(discount));
        ValuationEngine engine = new ValuationEngine();
        assertEquals(new BigDecimal("108.00"), engine.calculateRealTotal(evaluation));
    }
}
