package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link VatRate}, the VAT regime referential.
 * <p>
 * The finders run against the real database under {@code @TestTransaction} (rolled back after
 * each test); the formatting, label and checksum accessors are asserted on transient regimes.
 * Isolated high numbers and uncommon rates are used so the tests do not depend on regimes a
 * committing class may have left in the shared database.
 */
@QuarkusTest
@TestTransaction
public class VatRateTest {

    /**
     * Tests that a regime is found by its number.
     */
    @Test
    void testFindByNumber() {
        new VatRate(9001, new BigDecimal("0.3300"), "Isolated A").persist();
        VatRate found = VatRate.findByNumber(9001);
        assertEquals(9001, found.number);
        assertEquals(0, new BigDecimal("0.3300").compareTo(found.rate));
    }

    /**
     * Tests that a null number finds nothing.
     */
    @Test
    void testFindByNumber_Null() {
        assertNull(VatRate.findByNumber(null));
    }

    /**
     * Tests that a regime is found by its rate.
     */
    @Test
    void testFindByRate() {
        VatRate regime = new VatRate(9002, new BigDecimal("0.3400"), "Isolated B");
        regime.persist();
        assertSame(regime.id, VatRate.findByRate(new BigDecimal("0.3400")).id);
    }

    /**
     * Tests that a null rate finds nothing.
     */
    @Test
    void testFindByRate_Null() {
        assertNull(VatRate.findByRate(null));
    }

    /**
     * Tests that two regimes sharing a rate are a referential anomaly resolved by the first
     * row winning.
     */
    @Test
    void testFindByRate_DuplicateFirstWins() {
        VatRate first = new VatRate(9003, new BigDecimal("0.4200"), "First");
        first.persist();
        VatRate second = new VatRate(9004, new BigDecimal("0.4200"), "Second");
        second.persist();
        VatRate found = VatRate.findByRate(new BigDecimal("0.4200"));
        assertEquals(9003, found.number);
    }

    /**
     * Tests that the regimes are listed in number order.
     */
    @Test
    void testListAllOrdered() {
        new VatRate(9006, new BigDecimal("0.6600"), "Sixth").persist();
        new VatRate(9005, new BigDecimal("0.5500"), "Fifth").persist();
        List<VatRate> ordered = VatRate.listAllOrdered().stream()
                .filter(r -> r.number == 9005 || r.number == 9006)
                .toList();
        assertEquals(2, ordered.size());
        assertEquals(9005, ordered.get(0).number);
        assertEquals(9006, ordered.get(1).number);
    }

    /**
     * Tests that the label is returned when present.
     */
    @Test
    void testGetLabel_Present() {
        assertEquals("Taux normal", new VatRate(1, new BigDecimal("0.2000"), "Taux normal").getLabel());
    }

    /**
     * Tests that a null or blank label falls back to the number.
     */
    @Test
    void testGetLabel_Fallback() {
        assertEquals("7", new VatRate(7, new BigDecimal("0.2000"), null).getLabel());
        assertEquals("8", new VatRate(8, new BigDecimal("0.2000"), "   ").getLabel());
    }

    /**
     * Tests the percentage formatting, needless decimals dropped and the decimal comma.
     */
    @Test
    void testGetRateFormatted() {
        assertEquals("20", new VatRate(1, new BigDecimal("0.2000"), null).getRateFormatted());
        assertEquals("5,5", new VatRate(2, new BigDecimal("0.0550"), null).getRateFormatted());
        assertEquals("2,1", new VatRate(4, new BigDecimal("0.0210"), null).getRateFormatted());
        assertEquals("0", new VatRate(5, new BigDecimal("0.0000"), null).getRateFormatted());
    }

    /**
     * Tests that a null rate formats to the empty string.
     */
    @Test
    void testGetRateFormatted_NullRate() {
        assertEquals("", new VatRate(1, null, null).getRateFormatted());
    }

    /**
     * Tests that the checksum reflects the number, the rate and the label.
     */
    @Test
    void testGetChecksum() {
        VatRate base = new VatRate(1, new BigDecimal("0.2000"), "Taux normal");
        int checksum = base.getChecksum();
        assertEquals(checksum, new VatRate(1, new BigDecimal("0.2000"), "Taux normal").getChecksum());
        assertNotEquals(checksum, new VatRate(2, new BigDecimal("0.2000"), "Taux normal").getChecksum());
        assertNotEquals(checksum, new VatRate(1, new BigDecimal("0.1000"), "Taux normal").getChecksum());
        assertNotEquals(checksum, new VatRate(1, new BigDecimal("0.2000"), "Autre").getChecksum());
    }

    /**
     * Tests that the checksum is stable across a persist round-trip: the value stored by the
     * lifecycle callback matches a recomputation.
     */
    @Test
    void testChecksumPersisted() {
        VatRate regime = new VatRate(9007, new BigDecimal("0.7700"), "Persisted");
        regime.persist();
        assertTrue(regime.checksum == regime.getChecksum());
    }
}
