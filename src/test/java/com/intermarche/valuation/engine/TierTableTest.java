package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TierTable}: the three resolution semantics, the ordering
 * guarantees and the defensive validations of the shared tier building block.
 */
public class TierTableTest {

    /**
     * Builds a three-tier table (thresholds 50, 100, 200) with string awards, passed in
     * a deliberately shuffled order to prove that the table sorts by itself.
     *
     * @return the table under test.
     */
    private TierTable<String> table() {
        return TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("100"), "B"),
                new TierTable.Tier<>(new BigDecimal("50"), "A"),
                new TierTable.Tier<>(new BigDecimal("200"), "C")));
    }

    /**
     * Tests that the tiers are exposed sorted by ascending threshold whatever the input
     * order.
     */
    @Test
    void testTiersAreSortedAscending() {
        List<TierTable.Tier<String>> tiers = table().tiers();
        assertEquals("A", tiers.get(0).award());
        assertEquals("B", tiers.get(1).award());
        assertEquals("C", tiers.get(2).award());
    }

    /**
     * Tests that a null tier collection is refused while an empty one yields a table
     * that never resolves anything.
     */
    @Test
    void testOfNullRejectedEmptyTolerated() {
        assertThrows(IllegalArgumentException.class, () -> TierTable.of(null));
        TierTable<String> empty = TierTable.of(List.of());
        assertTrue(empty.resolveHighest(new BigDecimal("100")).isEmpty());
        assertTrue(empty.slices(new BigDecimal("100")).isEmpty());
    }

    /**
     * Tests that a negative threshold is refused.
     */
    @Test
    void testOfRejectsNegativeThreshold() {
        assertThrows(IllegalArgumentException.class, () -> TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("-1"), "X"))));
    }

    /**
     * Tests the highest-reached resolution below, on and above the thresholds.
     */
    @Test
    void testResolveHighest() {
        TierTable<String> table = table();
        assertTrue(table.resolveHighest(new BigDecimal("49.99")).isEmpty());
        assertEquals("A", table.resolveHighest(new BigDecimal("50")).orElseThrow().award());
        assertEquals("A", table.resolveHighest(new BigDecimal("99.99")).orElseThrow().award());
        assertEquals("B", table.resolveHighest(new BigDecimal("100")).orElseThrow().award());
        assertEquals("C", table.resolveHighest(new BigDecimal("1000")).orElseThrow().award());
        assertTrue(table.resolveHighest(null).isEmpty());
    }

    /**
     * Tests that on duplicate thresholds the last tier in ascending order wins,
     * deterministically.
     */
    @Test
    void testResolveHighestOnDuplicateThresholds() {
        TierTable<String> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("50"), "FIRST"),
                new TierTable.Tier<>(new BigDecimal("50"), "SECOND")));
        Optional<TierTable.Tier<String>> resolved = table.resolveHighest(new BigDecimal("60"));
        assertEquals("SECOND", resolved.orElseThrow().award());
    }

    /**
     * Tests the progressive slicing: each bracket gets its own portion and the last
     * bracket is open-ended.
     */
    @Test
    void testSlicesProgressive() {
        TierTable<String> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("0"), "A"),
                new TierTable.Tier<>(new BigDecimal("50"), "B"),
                new TierTable.Tier<>(new BigDecimal("100"), "C")));
        List<TierTable.Slice<String>> slices = table.slices(new BigDecimal("120"));
        assertEquals(3, slices.size());
        assertEquals(0, new BigDecimal("50").compareTo(slices.get(0).portion()));
        assertEquals("A", slices.get(0).award());
        assertEquals(0, new BigDecimal("50").compareTo(slices.get(1).portion()));
        assertEquals("B", slices.get(1).award());
        assertEquals(0, new BigDecimal("20").compareTo(slices.get(2).portion()));
        assertEquals("C", slices.get(2).award());
    }

    /**
     * Tests that the base below the first floor earns no slice, and that a base inside
     * the first bracket produces a single partial slice.
     */
    @Test
    void testSlicesEdges() {
        TierTable<String> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("50"), "A"),
                new TierTable.Tier<>(new BigDecimal("100"), "B")));
        assertTrue(table.slices(new BigDecimal("50")).isEmpty());
        assertTrue(table.slices(new BigDecimal("30")).isEmpty());
        assertTrue(table.slices(BigDecimal.ZERO).isEmpty());
        assertTrue(table.slices(null).isEmpty());
        List<TierTable.Slice<String>> slices = table.slices(new BigDecimal("80"));
        assertEquals(1, slices.size());
        assertEquals(0, new BigDecimal("30").compareTo(slices.get(0).portion()));
    }

    /**
     * Tests the per-multiple step counting, including the zero and null bases and the
     * refusal of a non-positive step.
     */
    @Test
    void testMultiples() {
        assertEquals(2, TierTable.multiples(new BigDecimal("120"), new BigDecimal("50")));
        assertEquals(0, TierTable.multiples(new BigDecimal("49.99"), new BigDecimal("50")));
        assertEquals(1, TierTable.multiples(new BigDecimal("50"), new BigDecimal("50")));
        assertEquals(0, TierTable.multiples(BigDecimal.ZERO, new BigDecimal("50")));
        assertEquals(0, TierTable.multiples(null, new BigDecimal("50")));
        assertThrows(IllegalArgumentException.class,
                () -> TierTable.multiples(new BigDecimal("10"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> TierTable.multiples(new BigDecimal("10"), null));
    }
}
