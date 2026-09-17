package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MinimumQuantityCondition}: the summed matched quantity, decimal
 * quantities, the inclusive threshold and the collection of contributors.
 */
public class MinimumQuantityConditionTest {

    /**
     * Builds a valued result item carrying an EAN and a quantity.
     *
     * @param ean      the product EAN.
     * @param quantity the quantity.
     * @return the valued item.
     */
    private BasketEvaluation.Item valued(String ean, double quantity) {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.produceEan = ean;
        item.quantity = quantity;
        item.amount = new AmountEvaluation(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
        return item;
    }

    /**
     * Builds an offer stub exposing the given valued items.
     *
     * @param valued the valued items exposed by the offer.
     * @return the offer application stub.
     */
    private OfferApplication offer(List<BasketEvaluation.Item> valued) {
        return new OfferApplication() {
            /**
             * Returns a fixed own amount.
             *
             * @return a one-euro amount.
             */
            @Override
            public AmountEvaluation getAmount() {
                return new AmountEvaluation(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
            }

            /**
             * Returns the covered basket items.
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
                return "TEST";
            }

            /**
             * Returns the valued items.
             *
             * @return the configured valued items.
             */
            @Override
            public List<BasketEvaluation.Item> getValuedItems() {
                return valued;
            }
        };
    }

    /**
     * Builds a bare evaluation over an empty, store-less basket.
     *
     * @return the evaluation.
     */
    private BasketEvaluation evaluation() {
        return new BasketEvaluation(new Basket());
    }

    /**
     * Tests that decimal quantities are summed as they are (0.5 + 0.5 = 1) and that the
     * threshold is reached inclusively.
     */
    @Test
    void testDecimalQuantitiesInclusiveThreshold() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(List.of(valued("EAN_A", 0.5), valued("EAN_A", 0.5))));
        MinimumQuantityCondition at1 =
                new MinimumQuantityCondition(Set.of("EAN_A"), new BigDecimal("1.0"));
        MinimumQuantityCondition atMore =
                new MinimumQuantityCondition(Set.of("EAN_A"), new BigDecimal("1.01"));
        assertTrue(at1.evaluate(evaluation).satisfied());
        assertFalse(atMore.evaluate(evaluation).satisfied());
    }

    /**
     * Tests that only the targeted EANs are counted, and that the contributing offers are
     * collected while a non-matching offer is not.
     */
    @Test
    void testMatchingOnlyAndContributors() {
        BasketEvaluation evaluation = evaluation();
        OfferApplication matching = offer(List.of(valued("EAN_A", 2.0), valued("EAN_OTHER", 5.0)));
        OfferApplication nonMatching = offer(List.of(valued("EAN_OTHER", 9.0)));
        evaluation.getOffers().add(matching);
        evaluation.getOffers().add(nonMatching);
        MinimumQuantityCondition condition =
                new MinimumQuantityCondition(Set.of("EAN_A"), new BigDecimal("2"));
        TriggerResult result = condition.evaluate(evaluation);
        assertTrue(result.satisfied());
        assertEquals(1, result.contributors().size());
        assertSame(matching, result.contributors().get(0));
    }

    /**
     * Tests that a null EAN item is ignored and that no match yields an unsatisfied result
     * with no contributor.
     */
    @Test
    void testNoMatchAndNullEan() {
        BasketEvaluation evaluation = evaluation();
        evaluation.getOffers().add(offer(List.of(valued(null, 3.0))));
        MinimumQuantityCondition condition =
                new MinimumQuantityCondition(Set.of("EAN_A"), new BigDecimal("1"));
        TriggerResult result = condition.evaluate(evaluation);
        assertFalse(result.satisfied());
        assertTrue(result.contributors().isEmpty());
    }

    /**
     * Tests the accessors and the defensive copy of the EAN set.
     */
    @Test
    void testAccessors() {
        MinimumQuantityCondition condition =
                new MinimumQuantityCondition(Set.of("EAN_A", "EAN_B"), new BigDecimal("3"));
        assertEquals(Set.of("EAN_A", "EAN_B"), condition.getEans());
        assertEquals(new BigDecimal("3"), condition.getThreshold());
        assertTrue(new MinimumQuantityCondition(null, BigDecimal.ONE).getEans().isEmpty());
    }
}
