package com.intermarche.valuation.engine;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-oriented @QuarkusTest for {@link TierTable}.
 * <p>
 * A plain unit TierTableTest already exists but is not attributed by quarkus-jacoco in this
 * project; this class re-exercises the same lines under a QuarkusTest boot so the coverage
 * report sees them. The class is a pure value object, so no database is involved.
 */
@QuarkusTest
class TierTableCoverageTest {

    /**
     * Builds a tier from a threshold string and a label award.
     *
     * @param threshold The threshold value.
     * @param award     The award label.
     * @return The tier.
     */
    private TierTable.Tier<String> tier(String threshold, String award) {
        return new TierTable.Tier<>(new BigDecimal(threshold), award);
    }

    /**
     * A null tier collection is rejected.
     */
    @Test
    void ofNullCollectionThrows() {
        assertThrows(IllegalArgumentException.class, () -> TierTable.of(null));
    }

    /**
     * A negative threshold is rejected.
     */
    @Test
    void ofNegativeThresholdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TierTable.of(List.of(tier("-1", "A"))));
    }

    /**
     * A null tier in the collection is rejected.
     */
    @Test
    void ofNullTierThrows() {
        List<TierTable.Tier<String>> tiers = new ArrayList<>();
        tiers.add(null);
        assertThrows(IllegalArgumentException.class, () -> TierTable.of(tiers));
    }

    /**
     * A tier with a null threshold is rejected.
     */
    @Test
    void ofNullThresholdThrows() {
        List<TierTable.Tier<String>> tiers = new ArrayList<>();
        tiers.add(new TierTable.Tier<>(null, "A"));
        assertThrows(IllegalArgumentException.class, () -> TierTable.of(tiers));
    }

    /**
     * An empty table resolves nothing and exposes no tiers.
     */
    @Test
    void emptyTableResolvesNothing() {
        TierTable<String> table = TierTable.of(new ArrayList<>());
        assertTrue(table.tiers().isEmpty());
        assertTrue(table.resolveHighest(new BigDecimal("100")).isEmpty());
        assertTrue(table.slices(new BigDecimal("100")).isEmpty());
    }

    /**
     * The tiers are exposed sorted by ascending threshold regardless of input order.
     */
    @Test
    void tiersAreSortedAscending() {
        TierTable<String> table = TierTable.of(Arrays.asList(tier("100", "B"), tier("50", "A")));
        assertEquals(2, table.tiers().size());
        assertEquals(new BigDecimal("50"), table.tiers().get(0).threshold());
        assertEquals("A", table.tiers().get(0).award());
        assertEquals(new BigDecimal("100"), table.tiers().get(1).threshold());
        assertEquals("B", table.tiers().get(1).award());
    }

    /**
     * A null base reaches no tier.
     */
    @Test
    void resolveHighestNullBaseIsEmpty() {
        TierTable<String> table = TierTable.of(List.of(tier("50", "A")));
        assertTrue(table.resolveHighest(null).isEmpty());
    }

    /**
     * The highest tier at or below the base is resolved; a base below every threshold
     * reaches nothing.
     */
    @Test
    void resolveHighestPicksHighestReached() {
        TierTable<String> table = TierTable.of(Arrays.asList(tier("50", "A"), tier("100", "B")));
        Optional<TierTable.Tier<String>> below = table.resolveHighest(new BigDecimal("25"));
        assertTrue(below.isEmpty());
        assertEquals("A", table.resolveHighest(new BigDecimal("75")).orElseThrow().award());
        assertEquals("B", table.resolveHighest(new BigDecimal("100")).orElseThrow().award());
        assertEquals("B", table.resolveHighest(new BigDecimal("150")).orElseThrow().award());
    }

    /**
     * A null, zero or negative base yields no slices.
     */
    @Test
    void slicesNonPositiveBaseIsEmpty() {
        TierTable<String> table = TierTable.of(List.of(tier("50", "A")));
        assertTrue(table.slices(null).isEmpty());
        assertTrue(table.slices(BigDecimal.ZERO).isEmpty());
        assertTrue(table.slices(new BigDecimal("-5")).isEmpty());
    }

    /**
     * Progressive slicing spreads the base over the reached brackets, the last being
     * open-ended.
     */
    @Test
    void slicesSpreadOverBrackets() {
        TierTable<String> table = TierTable.of(Arrays.asList(tier("50", "A"), tier("100", "B")));
        List<TierTable.Slice<String>> slices = table.slices(new BigDecimal("120"));
        assertEquals(2, slices.size());
        assertEquals(new BigDecimal("50"), slices.get(0).portion());
        assertEquals("A", slices.get(0).award());
        assertEquals(new BigDecimal("20"), slices.get(1).portion());
        assertEquals("B", slices.get(1).award());
    }

    /**
     * A base landing inside the first bracket yields a single capped slice.
     */
    @Test
    void slicesStopAtBaseInsideFirstBracket() {
        TierTable<String> table = TierTable.of(Arrays.asList(tier("50", "A"), tier("100", "B")));
        List<TierTable.Slice<String>> slices = table.slices(new BigDecimal("75"));
        assertEquals(1, slices.size());
        assertEquals(new BigDecimal("25"), slices.get(0).portion());
        assertEquals("A", slices.get(0).award());
    }

    /**
     * A zero-width bracket from duplicate thresholds contributes no slice.
     */
    @Test
    void slicesSkipZeroWidthBracket() {
        TierTable<String> table = TierTable.of(Arrays.asList(tier("50", "A"), tier("50", "B")));
        List<TierTable.Slice<String>> slices = table.slices(new BigDecimal("100"));
        assertEquals(1, slices.size());
        assertEquals(new BigDecimal("50"), slices.get(0).portion());
    }

    /**
     * A null, zero or negative step is rejected.
     */
    @Test
    void multiplesNonPositiveStepThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TierTable.multiples(new BigDecimal("100"), null));
        assertThrows(IllegalArgumentException.class,
                () -> TierTable.multiples(new BigDecimal("100"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> TierTable.multiples(new BigDecimal("100"), new BigDecimal("-1")));
    }

    /**
     * A null or non-positive base yields zero complete steps.
     */
    @Test
    void multiplesNonPositiveBaseIsZero() {
        assertEquals(0, TierTable.multiples(null, new BigDecimal("50")));
        assertEquals(0, TierTable.multiples(BigDecimal.ZERO, new BigDecimal("50")));
        assertEquals(0, TierTable.multiples(new BigDecimal("-10"), new BigDecimal("50")));
    }

    /**
     * The number of complete steps in the base is counted.
     */
    @Test
    void multiplesCountsCompleteSteps() {
        assertEquals(2, TierTable.multiples(new BigDecimal("120"), new BigDecimal("50")));
    }
}
