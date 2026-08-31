package com.intermarche.valuation.engine;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @QuarkusTest} coverage of {@link BasketEvaluation}'s in-memory picking logic.
 * <p>
 * Every scenario builds a {@link Basket} with a {@code null} store code, so the constructor
 * resolves no store and touches no database; the working map is populated with
 * {@link BasketEvaluation#feedFrom(Basket)} and drawn down with the pick methods. The class is
 * a {@code @QuarkusTest} purely so quarkus-jacoco attributes the executed lines, which a plain
 * unit test would not receive in this project.
 */
@QuarkusTest
public class BasketEvaluationCoverageTest {

    /**
     * Builds a plain basket line with an EAN and a quantity.
     *
     * @param lineId The line identifier.
     * @param ean    The product EAN, may be {@code null} for a generic line.
     * @param qty    The quantity, may be {@code null}.
     * @return A new item.
     */
    private Basket.Item item(String lineId, String ean, Double qty) {
        Basket.Item i = new Basket.Item();
        i.lineId = lineId;
        i.produceEan = ean;
        i.quantity = qty;
        return i;
    }

    /**
     * Builds a basket carrying the provided items and a null store code.
     *
     * @param items The lines to place in the basket.
     * @return A fed {@link BasketEvaluation} ready for picking.
     */
    private BasketEvaluation feed(Basket.Item... items) {
        Basket basket = new Basket();
        basket.items = new ArrayList<>(List.of(items));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        return evaluation;
    }

    /**
     * Covers the aggregation branch of {@code feedFrom} where the existing entry already
     * carries a non-null quantity: two lines of the same EAN and price profile are summed.
     */
    @Test
    void feedFromAggregatesNonNullQuantities() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 2.0), item("L2", "E1", 3.0));
        assertEquals(5.0, evaluation.remainingQuantity("E1"), 1e-9);
        assertEquals(1, evaluation.getToEvaluate().get("E1").size());
    }

    /**
     * Covers the aggregation branch of {@code feedFrom} where the existing entry carries a
     * null quantity: the null is treated as zero before adding the second line's quantity.
     */
    @Test
    void feedFromAggregatesOntoNullQuantity() {
        BasketEvaluation evaluation = feed(item("L1", "E1", null), item("L2", "E1", 3.0));
        assertEquals(3.0, evaluation.remainingQuantity("E1"), 1e-9);
    }

    /**
     * Covers the early return of {@code pick} when the requested EAN has no bucket at all.
     */
    @Test
    void pickReturnsEmptyForAbsentEan() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 1.0));
        assertTrue(evaluation.pick(1.0, "ABSENT").isEmpty());
    }

    /**
     * Covers the null-guard early return of {@code pick} for both a null quantity and a null
     * EAN.
     */
    @Test
    void pickReturnsEmptyForNullArguments() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 1.0));
        assertTrue(evaluation.pick(null, "E1").isEmpty());
        assertTrue(evaluation.pick(1.0, null).isEmpty());
    }

    /**
     * Covers the partial-consumption arm of {@code pick} (taken quantity below available) then
     * the full-consumption arm that removes the entry and the EAN key.
     */
    @Test
    void pickConsumesPartiallyThenFully() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 5.0));
        List<Basket.Item> first = evaluation.pick(2.0, "E1");
        assertEquals(1, first.size());
        assertEquals(2.0, first.get(0).quantity, 1e-9);
        assertEquals(3.0, evaluation.remainingQuantity("E1"), 1e-9);
        List<Basket.Item> second = evaluation.pick(3.0, "E1");
        assertEquals(3.0, second.get(0).quantity, 1e-9);
        assertTrue(evaluation.getToEvaluate().isEmpty());
    }

    /**
     * Covers the null-guard early return of {@code pickMatching} for a null quantity and a
     * null source.
     */
    @Test
    void pickMatchingReturnsEmptyForNullArguments() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 1.0));
        assertTrue(evaluation.pickMatching(null, item("L1", "E1", 1.0)).isEmpty());
        assertTrue(evaluation.pickMatching(1.0, null).isEmpty());
    }

    /**
     * Covers the branch of {@code pickMatching} where the EAN has no bucket.
     */
    @Test
    void pickMatchingReturnsEmptyForAbsentBucket() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 1.0));
        assertTrue(evaluation.pickMatching(1.0, item("L2", "OTHER", 1.0)).isEmpty());
    }

    /**
     * Covers the branch of {@code pickMatching} where a bucket exists but no entry shares the
     * source's price profile (index below zero).
     */
    @Test
    void pickMatchingReturnsEmptyWhenNoProfileMatches() {
        Basket.Item stored = item("L1", "E1", 5.0);
        stored.pricePerUnitExclTax = new BigDecimal("1.00");
        BasketEvaluation evaluation = feed(stored);
        Basket.Item source = item("L2", "E1", 2.0);
        source.pricePerUnitExclTax = new BigDecimal("9.99");
        assertTrue(evaluation.pickMatching(2.0, source).isEmpty());
    }

    /**
     * Covers the partial-consumption arm of {@code pickMatching}.
     */
    @Test
    void pickMatchingConsumesPartially() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 5.0));
        List<Basket.Item> picked = evaluation.pickMatching(2.0, item("L1", "E1", 2.0));
        assertEquals(1, picked.size());
        assertEquals(2.0, picked.get(0).quantity, 1e-9);
        assertEquals(3.0, evaluation.remainingQuantity("E1"), 1e-9);
    }

    /**
     * Covers the full-consumption arm of {@code pickMatching}, which removes the entry and the
     * now-empty EAN key.
     */
    @Test
    void pickMatchingConsumesFully() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 4.0));
        List<Basket.Item> picked = evaluation.pickMatching(4.0, item("L1", "E1", 4.0));
        assertEquals(4.0, picked.get(0).quantity, 1e-9);
        assertTrue(evaluation.getToEvaluate().isEmpty());
    }

    /**
     * Covers {@code pickMerged} returning {@code null} when nothing can be taken.
     */
    @Test
    void pickMergedReturnsNullWhenEmpty() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 1.0));
        assertNull(evaluation.pickMerged(1.0, "ABSENT"));
    }

    /**
     * Covers {@code pickMerged} returning the single slice unchanged when the EAN carries only
     * one price entry.
     */
    @Test
    void pickMergedReturnsSingleSlice() {
        BasketEvaluation evaluation = feed(item("L1", "E1", 5.0));
        Basket.Item merged = evaluation.pickMerged(3.0, "E1");
        assertEquals(3.0, merged.quantity, 1e-9);
    }

    /**
     * Covers the merge branch of {@code pickMerged}: two price entries of the same EAN are
     * consumed and merged into a single item summing both slices.
     */
    @Test
    void pickMergedMergesMultipleSlices() {
        Basket.Item cheap = item("L1", "E1", 2.0);
        cheap.pricePerUnitExclTax = new BigDecimal("1.00");
        Basket.Item pricey = item("L2", "E1", 2.0);
        pricey.pricePerUnitExclTax = new BigDecimal("2.00");
        BasketEvaluation evaluation = feed(cheap, pricey);
        assertEquals(2, evaluation.getToEvaluate().get("E1").size());
        Basket.Item merged = evaluation.pickMerged(4.0, "E1");
        assertEquals(4.0, merged.quantity, 1e-9);
        assertEquals(2, merged.sourceLines.size());
    }

    /**
     * Covers the default constructor of the {@link BasketEvaluation.VatLine} JSON holder.
     */
    @Test
    void vatLineDefaultConstructor() {
        BasketEvaluation.VatLine line = new BasketEvaluation.VatLine();
        assertNull(line.vatRate);
        assertNull(line.amountExcludingTax);
    }

    /**
     * Covers the result {@link BasketEvaluation.Item} constructor for both arms of its
     * quantity guard: a source with a null quantity yields zero, a source with a quantity is
     * copied through.
     */
    @Test
    void resultItemConstructorHandlesNullQuantity() {
        Basket.Item nullQty = item("L1", "E1", null);
        AmountEvaluation amount = new AmountEvaluation(
                new BigDecimal("1.00"), new BigDecimal("1.20"), new BigDecimal("0.20"));
        BasketEvaluation.Item fromNull = new BasketEvaluation.Item(nullQty, amount);
        assertEquals(0.0, fromNull.quantity, 1e-9);
        assertEquals("L1", fromNull.lineId);
        Basket.Item withQty = item("L2", "E1", 2.5);
        BasketEvaluation.Item fromQty = new BasketEvaluation.Item(withQty, amount);
        assertEquals(2.5, fromQty.quantity, 1e-9);
        assertSame(amount, fromQty.amount);
    }
}
