package com.intermarche.valuation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit test for {@link PriceUsage}.
 * <p>
 * {@code PriceUsage} is a branchless two-constant enumeration, so this class
 * exercises the synthetic {@code values()} and {@code valueOf(String)} members
 * together with the identity and ordering of every declared constant.
 */
class PriceUsageTest {

    /**
     * Verifies that {@code values()} returns exactly the two declared constants
     * in declaration order.
     */
    @Test
    void valuesReturnsAllConstantsInDeclarationOrder() {
        PriceUsage[] expected = {PriceUsage.DEFAULT, PriceUsage.BASE_FOR_DISCOUNT};
        assertArrayEquals(expected, PriceUsage.values());
    }

    /**
     * Verifies that {@code values()} yields a fresh defensive copy on each call.
     */
    @Test
    void valuesReturnsFreshArrayEachCall() {
        assertNotSame(PriceUsage.values(), PriceUsage.values());
    }

    /**
     * Verifies that {@code valueOf} resolves the {@code DEFAULT} constant name.
     */
    @Test
    void valueOfResolvesDefault() {
        assertSame(PriceUsage.DEFAULT, PriceUsage.valueOf("DEFAULT"));
    }

    /**
     * Verifies that {@code valueOf} resolves the {@code BASE_FOR_DISCOUNT} constant name.
     */
    @Test
    void valueOfResolvesBaseForDiscount() {
        assertSame(PriceUsage.BASE_FOR_DISCOUNT, PriceUsage.valueOf("BASE_FOR_DISCOUNT"));
    }

    /**
     * Verifies that {@code valueOf} rejects an unknown constant name.
     */
    @Test
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> PriceUsage.valueOf("UNKNOWN"));
    }

    /**
     * Verifies that {@code valueOf} rejects a null constant name.
     */
    @Test
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> PriceUsage.valueOf(null));
    }

    /**
     * Verifies the stable ordinal assignment of every constant.
     */
    @Test
    void ordinalsAreStable() {
        assertEquals(0, PriceUsage.DEFAULT.ordinal());
        assertEquals(1, PriceUsage.BASE_FOR_DISCOUNT.ordinal());
    }

    /**
     * Verifies the {@code name()} of every constant matches its identifier.
     */
    @Test
    void namesMatchIdentifiers() {
        assertEquals("DEFAULT", PriceUsage.DEFAULT.name());
        assertEquals("BASE_FOR_DISCOUNT", PriceUsage.BASE_FOR_DISCOUNT.name());
    }

    /**
     * Verifies that the two constants are distinct, non-null singletons.
     */
    @Test
    void constantsAreDistinctSingletons() {
        assertNotNull(PriceUsage.DEFAULT);
        assertNotNull(PriceUsage.BASE_FOR_DISCOUNT);
        assertNotSame(PriceUsage.DEFAULT, PriceUsage.BASE_FOR_DISCOUNT);
    }
}
