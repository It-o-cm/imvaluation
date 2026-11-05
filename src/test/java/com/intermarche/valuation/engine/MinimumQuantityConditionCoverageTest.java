package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage complement for {@link MinimumQuantityCondition}: pins the defensive
 * null-offers arm of {@code evaluate} that {@link MinimumQuantityConditionTest} cannot reach,
 * since a real {@link BasketEvaluation} always exposes a non-null offers collection. Driven by a
 * mocked evaluation returning a null collection.
 */
public class MinimumQuantityConditionCoverageTest {

    /**
     * When the evaluation exposes no offers collection at all, {@code evaluate} sums nothing and the
     * condition stays unsatisfied with no contributor.
     */
    @Test
    void testEvaluateWithNullOffersCollection() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getOffers()).thenReturn(null);
        MinimumQuantityCondition condition =
                new MinimumQuantityCondition(Set.of("EAN_A"), new BigDecimal("1"));
        TriggerResult result = condition.evaluate(evaluation);
        assertFalse(result.satisfied());
        assertTrue(result.contributors().isEmpty());
    }
}
