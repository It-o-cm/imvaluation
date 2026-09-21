package com.intermarche.valuation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Address}.
 * <p>
 * Verifies the behavior of the Value Object including field initialization via constructors,
 * equality based on content (Value Object semantics), hash code consistency,
 * and the checksum calculation logic.
 */
class AddressTest {

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Creates a standard instance of {@link Address} with predefined values.
     *
     * @return A fully populated {@link Address} object.
     */
    private Address createStandardAddress() {
        return new Address(
                "10 Rue de la Paix",
                "Batiment B",
                "75002",
                "Paris",
                "France",
                48.866667,
                2.333333
        );
    }

    // --------------------------------------------------
    // Constructor Tests
    // --------------------------------------------------

    /**
     * Tests the full constructor to ensure all fields are correctly initialized.
     */
    @Test
    void fullConstructor_shouldInitializeAllFields() {
        Address adresse = createStandardAddress();
        assertEquals("10 Rue de la Paix", adresse.streetLine1);
        assertEquals("Batiment B", adresse.streetLine2);
        assertEquals("75002", adresse.postalCode);
        assertEquals("Paris", adresse.city);
        assertEquals("France", adresse.country);
        assertEquals(48.866667, adresse.latitude);
        assertEquals(2.333333, adresse.longitude);
    }

    /**
     * Tests the default constructor (required by JPA).
     */
    @Test
    void defaultConstructor_shouldCreateInstanceWithNullFields() {
        Address adresse = new Address();
        assertNull(adresse.streetLine1);
        assertNull(adresse.streetLine2);
        assertNull(adresse.postalCode);
        assertNull(adresse.city);
        assertNull(adresse.country);
        assertNull(adresse.latitude);
        assertNull(adresse.longitude);
    }

    // --------------------------------------------------
    // Equals and HashCode Tests
    // --------------------------------------------------

    /**
     * Tests the reflexive property of equals: x.equals(x) is true.
     */
    @Test
    void equals_shouldReturnTrue_forSameInstance() {
        Address adresse = createStandardAddress();
        assertEquals(adresse, adresse);
    }

    /**
     * Tests the symmetric property: x.equals(y) implies y.equals(x).
     */
    @Test
    void equals_shouldReturnTrue_forEqualContent() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        assertEquals(adresse1, adresse2);
        assertEquals(adresse2, adresse1);
    }

    /**
     * Tests that equals returns false when the compared object is null.
     */
    @Test
    void equals_shouldReturnFalse_whenOtherIsNull() {
        Address adresse = createStandardAddress();
        assertFalse(adresse.equals(null));
    }

    /**
     * Tests that equals returns false when comparing with an incompatible type.
     */
    @Test
    void equals_shouldReturnFalse_forDifferentObject() {
        Address adresse = createStandardAddress();
        Object otherObject = "Not an Address";
        assertNotEquals(adresse, otherObject);
    }

    // --------------------------------------------------
    // Specific Field Inequality Tests
    // --------------------------------------------------

    /**
     * Tests that equals returns false when {@link Address#streetLine1} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenStreetLine1Differs() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        adresse2.streetLine1 = "20 Rue de la Liberté";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Address#streetLine2} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenStreetLine2Differs() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        adresse2.streetLine2 = "Batiment C";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Address#postalCode} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenPostalCodeDiffers() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        adresse2.postalCode = "69001"; // Lyon code
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Address#country} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenCountryDiffers() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        adresse2.country = "Italy";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Address#city} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenCityDiffers() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        adresse2.city = "Marseille";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when GPS coordinates ({@link Address#latitude} or {@link Address#longitude}) differ.
     */
    @Test
    void equals_shouldReturnFalse_whenCoordinatesDiffer() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = new Address(
                "10 Rue de la Paix",
                "Batiment B",
                "75002",
                "Paris",
                "France",
                40.7128, // Different Lat/Long
                -74.0060
        );
        assertNotEquals(adresse1, adresse2);
    }

    // --------------------------------------------------
    // HashCode Tests
    // --------------------------------------------------

    /**
     * Tests the consistency between equals and hashCode.
     * <p>
     * If two objects are equal according to {@code equals(Object)}, they must return
     * the same integer from {@code hashCode()}.
     */
    @Test
    void hashCode_shouldBeEqual_forEqualObjects() {
        Address adresse1 = createStandardAddress();
        Address adresse2 = createStandardAddress();
        assertEquals(adresse1.hashCode(), adresse2.hashCode());
    }

    // --------------------------------------------------
    // GetChecksum Tests
    // --------------------------------------------------

    /**
     * Tests that {@link Address#getChecksum()} returns a value consistent with
     * {@link Address#hashCode()} as both rely on the same fields.
     */
    @Test
    void getChecksum_shouldMatchHashCode() {
        Address adresse = createStandardAddress();
        // Based on the implementation, getChecksum uses Objects.hash like hashCode
        assertEquals(adresse.hashCode(), adresse.getChecksum());
    }

    /**
     * Tests that {@link Address#getChecksum()} returns different values for different content.
     */
    @Test
    void getChecksum_shouldChange_whenContentChanges() {
        Address adresse = createStandardAddress();
        int originalChecksum = adresse.getChecksum();
        // Modify a field
        adresse.city = "Marseille";
        int newChecksum = adresse.getChecksum();
        assertNotEquals(originalChecksum, newChecksum);
    }
}