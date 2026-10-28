package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for {@link ReferentialException}.
 */
public class ReferentialExceptionTest {

    /**
     * The exception keeps the supplied message and is an {@link IllegalStateException}, so engine
     * call sites expecting the broader type still catch it while the resource can single it out.
     */
    @Test
    void testKeepsMessageAndIsIllegalState() {
        ReferentialException ex = new ReferentialException("Configuration Error: Store not found for code 'S1'");
        assertEquals("Configuration Error: Store not found for code 'S1'", ex.getMessage());
        assertTrue(ex instanceof IllegalStateException);
    }
}
