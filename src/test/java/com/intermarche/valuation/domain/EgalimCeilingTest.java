package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link EgalimCeiling}, the administered EGAlim ceiling referential.
 * <p>
 * The finders run against the real database under {@code @TestTransaction} (rolled back after
 * each test); the formatting, label, capped and checksum accessors are asserted on transient
 * rows. Isolated codes are used so the tests do not depend on rows a committing class may have
 * left in the shared database, and above all not on the three real regime codes the engine's
 * enum carries.
 * <p>
 * Branch enumeration, every leg exercised: {@code findByCode} the null arm and the lookup arm;
 * {@code getLabel} the three legs of its guard — label null, label blank, label set;
 * {@code isCapped} both arms, including the zero ceiling that is NOT the absent one;
 * {@code getCapFormatted} the uncapped arm and the formatting arm.
 */
@QuarkusTest
@TestTransaction
public class EgalimCeilingTest {

    /**
     * Tests that a row is found by its regime code.
     */
    @Test
    void testFindByCode() {
        EgalimCeiling ceiling = new EgalimCeiling("TEST_A", "Isolated A", new BigDecimal("0.3400"));
        ceiling.persist();
        assertSame(ceiling.id, EgalimCeiling.findByCode("TEST_A").id);
        assertEquals(0, new BigDecimal("0.3400").compareTo(EgalimCeiling.findByCode("TEST_A").capRate));
    }

    /**
     * Tests that a null code finds nothing and never reaches the database.
     */
    @Test
    void testFindByCode_Null() {
        assertNull(EgalimCeiling.findByCode(null));
    }

    /**
     * Tests that a code nothing carries finds nothing — the silence the resolution ladder
     * falls through on.
     */
    @Test
    void testFindByCode_Unknown() {
        assertNull(EgalimCeiling.findByCode("TEST_NOBODY"));
    }

    /**
     * Tests that a row may state a regime WITHOUT a ceiling: that is an answer, not a silence,
     * and it is how the exempt category is declared.
     */
    @Test
    void testFindByCode_UncappedRow() {
        new EgalimCeiling("TEST_FREE", "Hors champ", null).persist();
        EgalimCeiling found = EgalimCeiling.findByCode("TEST_FREE");
        assertNull(found.capRate);
        assertFalse(found.isCapped());
    }

    /**
     * Tests that the rows are listed in code order.
     */
    @Test
    void testListAllOrdered() {
        new EgalimCeiling("TEST_Z", "Last", new BigDecimal("0.4000")).persist();
        new EgalimCeiling("TEST_B", "First", new BigDecimal("0.3400")).persist();
        List<EgalimCeiling> ordered = EgalimCeiling.listAllOrdered().stream()
                .filter(c -> c.regimeCode.equals("TEST_B") || c.regimeCode.equals("TEST_Z"))
                .toList();
        assertEquals(2, ordered.size());
        assertEquals("TEST_B", ordered.get(0).regimeCode);
        assertEquals("TEST_Z", ordered.get(1).regimeCode);
    }

    /**
     * Tests that the default constructor leaves every field unset, as JPA expects.
     */
    @Test
    void testDefaultConstructor() {
        EgalimCeiling ceiling = new EgalimCeiling();
        assertNull(ceiling.regimeCode);
        assertNull(ceiling.label);
        assertNull(ceiling.capRate);
    }

    /**
     * Tests that the label is returned when present.
     */
    @Test
    void testGetLabel_Present() {
        assertEquals("Alimentaire",
                new EgalimCeiling("FOOD_34", "Alimentaire", new BigDecimal("0.3400")).getLabel());
    }

    /**
     * Tests that a null or blank label falls back to the regime code — a screen printing an
     * empty cell would name no category at all.
     */
    @Test
    void testGetLabel_Fallback() {
        assertEquals("FOOD_34",
                new EgalimCeiling("FOOD_34", null, new BigDecimal("0.3400")).getLabel());
        assertEquals("FOOD_34",
                new EgalimCeiling("FOOD_34", "   ", new BigDecimal("0.3400")).getLabel());
    }

    /**
     * Tests that a row carrying a ceiling is capped, and that a ceiling of ZERO is capped too:
     * zero is a rule someone wrote ("no generosity at all"), not an absence.
     */
    @Test
    void testIsCapped() {
        assertTrue(new EgalimCeiling("FOOD_34", null, new BigDecimal("0.3400")).isCapped());
        assertTrue(new EgalimCeiling("NONE", null, new BigDecimal("0.0000")).isCapped());
        assertFalse(new EgalimCeiling("EXEMPT", null, null).isCapped());
    }

    /**
     * Tests the percentage formatting, needless decimals dropped and the decimal comma.
     */
    @Test
    void testGetCapFormatted() {
        assertEquals("34", new EgalimCeiling("FOOD_34", null, new BigDecimal("0.3400")).getCapFormatted());
        assertEquals("40", new EgalimCeiling("DPH_40", null, new BigDecimal("0.4000")).getCapFormatted());
        assertEquals("33,5", new EgalimCeiling("ODD", null, new BigDecimal("0.3350")).getCapFormatted());
        assertEquals("0", new EgalimCeiling("NONE", null, new BigDecimal("0.0000")).getCapFormatted());
    }

    /**
     * Tests that an uncapped row formats to an empty string rather than to a zero — a document
     * printing "0 %" would state a rule nobody wrote.
     */
    @Test
    void testGetCapFormatted_Uncapped() {
        assertEquals("", new EgalimCeiling("EXEMPT", null, null).getCapFormatted());
    }

    /**
     * Tests that the checksum reflects the code, the label and the ceiling.
     */
    @Test
    void testGetChecksum() {
        EgalimCeiling base = new EgalimCeiling("FOOD_34", "Alimentaire", new BigDecimal("0.3400"));
        int checksum = base.getChecksum();
        assertEquals(checksum,
                new EgalimCeiling("FOOD_34", "Alimentaire", new BigDecimal("0.3400")).getChecksum());
        assertNotEquals(checksum,
                new EgalimCeiling("DPH_40", "Alimentaire", new BigDecimal("0.3400")).getChecksum());
        assertNotEquals(checksum,
                new EgalimCeiling("FOOD_34", "Alimentaire", new BigDecimal("0.4000")).getChecksum());
        assertNotEquals(checksum,
                new EgalimCeiling("FOOD_34", "Autre", new BigDecimal("0.3400")).getChecksum());
        assertNotEquals(checksum,
                new EgalimCeiling("FOOD_34", "Alimentaire", null).getChecksum());
    }

    /**
     * Tests that the checksum is stable across a persist round-trip: the value stored by the
     * lifecycle callback matches a recomputation.
     */
    @Test
    void testChecksumPersisted() {
        EgalimCeiling ceiling = new EgalimCeiling("TEST_C", "Persisted", new BigDecimal("0.3300"));
        ceiling.persist();
        assertTrue(ceiling.checksum == ceiling.getChecksum());
    }
}
