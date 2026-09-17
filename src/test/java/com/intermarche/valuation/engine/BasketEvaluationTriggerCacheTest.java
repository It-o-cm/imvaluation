package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the trigger memoization and carrier consumption added to
 * {@link BasketEvaluation} (spec §3.3, §4.3, §4.4).
 */
public class BasketEvaluationTriggerCacheTest {

    /**
     * Builds a bare evaluation over a store-less basket, so no database is touched.
     *
     * @return the evaluation.
     */
    private BasketEvaluation evaluation() {
        return new BasketEvaluation(new Basket());
    }

    /**
     * A distinct offer application used only as a consumption identity.
     *
     * @return the offer application stub.
     */
    private OfferApplication application() {
        return new OfferApplication() {
            /**
             * Returns no amount.
             *
             * @return null.
             */
            @Override
            public AmountEvaluation getAmount() {
                return null;
            }

            /**
             * Returns no covered items.
             *
             * @return an empty collection.
             */
            @Override
            public Collection<Basket.Item> getItems() {
                return List.of();
            }

            /**
             * Returns the offer type label.
             *
             * @return a constant label.
             */
            @Override
            public String getType() {
                return "STUB";
            }
        };
    }

    /**
     * Tests that a configuration's trigger is evaluated once and memoized until the cache is
     * invalidated, after which it is re-evaluated against the current state.
     */
    @Test
    void testTriggerMemoizationAndInvalidation() {
        int[] calls = {0};
        TriggerCondition counting = e -> {
            calls[0]++;
            return new TriggerResult(calls[0] == 1, List.of());
        };
        Trigger trigger = new Trigger(List.of(counting));
        Offer configuration = new Offer();
        BasketEvaluation evaluation = evaluation();
        assertTrue(evaluation.triggerResult(configuration, trigger).satisfied());
        assertTrue(evaluation.triggerResult(configuration, trigger).satisfied());
        assertEquals(1, calls[0]);
        evaluation.invalidateTriggerCache();
        assertFalse(evaluation.triggerResult(configuration, trigger).satisfied());
        assertEquals(2, calls[0]);
    }

    /**
     * Tests that a null configuration is never cached: its trigger is evaluated every time
     * (an applier not born from a configuration row is always evaluated fresh).
     */
    @Test
    void testNullConfigurationIsNeverCached() {
        int[] calls = {0};
        TriggerCondition counting = e -> {
            calls[0]++;
            return new TriggerResult(true, List.of());
        };
        Trigger trigger = new Trigger(List.of(counting));
        BasketEvaluation evaluation = evaluation();
        assertTrue(evaluation.triggerResult(null, trigger).satisfied());
        assertTrue(evaluation.triggerResult(null, trigger).satisfied());
        assertEquals(2, calls[0]);
    }

    /**
     * Tests carrier consumption: a marked application becomes consumed, a null collection is a
     * no-op, and an unmarked application stays free.
     */
    @Test
    void testCarrierConsumption() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication consumed = application();
        OfferApplication free = application();
        assertFalse(evaluation.isConsumed(consumed));
        evaluation.markConsumed(null);
        evaluation.markConsumed(List.of(consumed));
        assertTrue(evaluation.isConsumed(consumed));
        assertFalse(evaluation.isConsumed(free));
    }
}
