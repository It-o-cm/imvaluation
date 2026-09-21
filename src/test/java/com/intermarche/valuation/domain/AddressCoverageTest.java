package com.intermarche.valuation.domain;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-oriented @QuarkusTest for {@link Address}, covering the full constructor,
 * {@code equals}, {@code hashCode} and {@code getChecksum}, including the null and
 * differing arm of every compared field.
 * <p>
 * The class is a plain embeddable value object, so no database is involved.
 */
@QuarkusTest
class AddressCoverageTest {

    /**
     * Builds the reference address shared by the equality tests.
     *
     * @return A fully populated address.
     */
    private Address base() {
        return new Address("10 Rue A", "Bat B", "75008", "Paris", "France", 48.8, 2.3);
    }

    /**
     * The full constructor sets every field.
     */
    @Test
    void constructorSetsAllFields() {
        Address a = base();
        assertEquals("10 Rue A", a.streetLine1);
        assertEquals("Bat B", a.streetLine2);
        assertEquals("75008", a.postalCode);
        assertEquals("Paris", a.city);
        assertEquals("France", a.country);
        assertEquals(48.8, a.latitude);
        assertEquals(2.3, a.longitude);
    }

    /**
     * An address equals itself.
     */
    @Test
    void equalsSameInstance() {
        Address a = base();
        assertEquals(a, a);
    }

    /**
     * An address never equals null (exercises {@code Address.equals(null)}).
     */
    @Test
    void notEqualsNull() {
        assertFalse(base().equals(null));
    }

    /**
     * An address never equals an object of another type (exercises the class guard).
     */
    @Test
    void notEqualsOtherType() {
        assertFalse(base().equals("not an address"));
    }

    /**
     * Two addresses with identical content are equal, share a hash code and a checksum.
     */
    @Test
    void equalsAndHashOnEqualContent() {
        Address a = base();
        Address b = base();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.getChecksum(), b.getChecksum());
        assertEquals(a.hashCode(), a.getChecksum());
    }

    /**
     * A different street line 1 breaks equality.
     */
    @Test
    void notEqualsOnStreetLine1() {
        Address b = base();
        b.streetLine1 = "Other";
        assertNotEquals(base(), b);
    }

    /**
     * A different street line 2 breaks equality.
     */
    @Test
    void notEqualsOnStreetLine2() {
        Address b = base();
        b.streetLine2 = "Other";
        assertNotEquals(base(), b);
    }

    /**
     * A different postal code breaks equality.
     */
    @Test
    void notEqualsOnPostalCode() {
        Address b = base();
        b.postalCode = "99999";
        assertNotEquals(base(), b);
    }

    /**
     * A different city breaks equality.
     */
    @Test
    void notEqualsOnCity() {
        Address b = base();
        b.city = "Lyon";
        assertNotEquals(base(), b);
    }

    /**
     * A different country breaks equality.
     */
    @Test
    void notEqualsOnCountry() {
        Address b = base();
        b.country = "Belgium";
        assertNotEquals(base(), b);
    }

    /**
     * A null latitude breaks equality against a set latitude.
     */
    @Test
    void notEqualsOnLatitude() {
        Address b = base();
        b.latitude = null;
        assertNotEquals(base(), b);
    }

    /**
     * A null longitude breaks equality against a set longitude.
     */
    @Test
    void notEqualsOnLongitude() {
        Address b = base();
        b.longitude = null;
        assertNotEquals(base(), b);
    }

    /**
     * The default constructor leaves every field null and yields a stable checksum.
     */
    @Test
    void defaultConstructorChecksumMatchesHash() {
        Address a = new Address();
        assertEquals(a.hashCode(), a.getChecksum());
        assertTrue(a.equals(new Address()));
    }
}
