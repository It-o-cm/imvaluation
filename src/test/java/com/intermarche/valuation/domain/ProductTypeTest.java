package com.intermarche.valuation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit test for {@link ProductType}.
 * <p>
 * {@code ProductType} is a branchless three-constant enumeration, so this class
 * exercises the synthetic {@code values()} and {@code valueOf(String)} members
 * together with the identity and ordering of every declared constant.
 */
class ProductTypeTest {

    /**
     * Verifies that {@code values()} returns exactly the three declared constants
     * in declaration order.
     */
    @Test
    void valuesReturnsAllConstantsInDeclarationOrder() {
        ProductType[] expected = {ProductType.UNIT, ProductType.WEIGHT, ProductType.VOLUME};
        assertArrayEquals(expected, ProductType.values());
    }

    /**
     * Verifies that {@code values()} yields a fresh defensive copy on each call.
     */
    @Test
    void valuesReturnsFreshArrayEachCall() {
        assertNotSame(ProductType.values(), ProductType.values());
    }

    /**
     * Verifies that {@code valueOf} resolves the {@code UNIT} constant name.
     */
    @Test
    void valueOfResolvesUnit() {
        assertSame(ProductType.UNIT, ProductType.valueOf("UNIT"));
    }

    /**
     * Verifies that {@code valueOf} resolves the {@code WEIGHT} constant name.
     */
    @Test
    void valueOfResolvesWeight() {
        assertSame(ProductType.WEIGHT, ProductType.valueOf("WEIGHT"));
    }

    /**
     * Verifies that {@code valueOf} resolves the {@code VOLUME} constant name.
     */
    @Test
    void valueOfResolvesVolume() {
        assertSame(ProductType.VOLUME, ProductType.valueOf("VOLUME"));
    }

    /**
     * Verifies that {@code valueOf} rejects an unknown constant name.
     */
    @Test
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> ProductType.valueOf("UNKNOWN"));
    }

    /**
     * Verifies that {@code valueOf} rejects a null constant name.
     */
    @Test
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> ProductType.valueOf(null));
    }

    /**
     * Verifies the stable ordinal assignment of every constant.
     */
    @Test
    void ordinalsAreStable() {
        assertEquals(0, ProductType.UNIT.ordinal());
        assertEquals(1, ProductType.WEIGHT.ordinal());
        assertEquals(2, ProductType.VOLUME.ordinal());
    }

    /**
     * Verifies the {@code name()} of every constant matches its identifier.
     */
    @Test
    void namesMatchIdentifiers() {
        assertEquals("UNIT", ProductType.UNIT.name());
        assertEquals("WEIGHT", ProductType.WEIGHT.name());
        assertEquals("VOLUME", ProductType.VOLUME.name());
    }

    /**
     * Verifies that the three constants are distinct, non-null singletons.
     */
    @Test
    void constantsAreDistinctSingletons() {
        assertNotNull(ProductType.UNIT);
        assertNotNull(ProductType.WEIGHT);
        assertNotNull(ProductType.VOLUME);
        assertNotSame(ProductType.UNIT, ProductType.WEIGHT);
        assertNotSame(ProductType.WEIGHT, ProductType.VOLUME);
        assertNotSame(ProductType.UNIT, ProductType.VOLUME);
    }
}
