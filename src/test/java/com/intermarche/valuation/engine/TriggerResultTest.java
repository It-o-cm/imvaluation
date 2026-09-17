package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link TriggerResult} record: its accessors and value semantics.
 */
public class TriggerResultTest {

    /**
     * Tests that the record exposes the satisfied flag and the contributors it was built
     * with, and that two equal results are equal.
     */
    @Test
    void testAccessorsAndValueSemantics() {
        TriggerResult empty = new TriggerResult(true, List.of());
        assertTrue(empty.satisfied());
        assertTrue(empty.contributors().isEmpty());
        TriggerResult unsatisfied = new TriggerResult(false, List.of());
        assertFalse(unsatisfied.satisfied());
        assertEquals(new TriggerResult(true, List.of()), empty);
    }
}
