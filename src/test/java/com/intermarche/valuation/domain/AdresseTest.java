package com.intermarche.valuation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Adresse}.
 * <p>
 * Verifies the behavior of the Value Object including field initialization via constructors,
 * equality based on content (Value Object semantics), hash code consistency,
 * and the checksum calculation logic.
 */
class AdresseTest {

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Creates a standard instance of {@link Adresse} with predefined values.
     *
     * @return A fully populated {@link Adresse} object.
     */
    private Adresse createStandardAddress() {
        return new Adresse(
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
        Adresse adresse = createStandardAddress();
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
        Adresse adresse = new Adresse();
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
        Adresse adresse = createStandardAddress();
        assertEquals(adresse, adresse);
    }

    /**
     * Tests the symmetric property: x.equals(y) implies y.equals(x).
     */
    @Test
    void equals_shouldReturnTrue_forEqualContent() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        assertEquals(adresse1, adresse2);
        assertEquals(adresse2, adresse1);
    }

    /**
     * Tests that equals returns false when the compared object is null.
     */
    @Test
    void equals_shouldReturnFalse_whenOtherIsNull() {
        Adresse adresse = createStandardAddress();
        assertFalse(adresse.equals(null));
    }

    /**
     * Tests that equals returns false when comparing with an incompatible type.
     */
    @Test
    void equals_shouldReturnFalse_forDifferentObject() {
        Adresse adresse = createStandardAddress();
        Object otherObject = "Not an Address";
        assertNotEquals(adresse, otherObject);
    }

    // --------------------------------------------------
    // Specific Field Inequality Tests
    // --------------------------------------------------

    /**
     * Tests that equals returns false when {@link Adresse#streetLine1} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenStreetLine1Differs() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        adresse2.streetLine1 = "20 Rue de la Liberté";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Adresse#streetLine2} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenStreetLine2Differs() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        adresse2.streetLine2 = "Batiment C";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Adresse#postalCode} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenPostalCodeDiffers() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        adresse2.postalCode = "69001"; // Lyon code
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Adresse#country} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenCountryDiffers() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        adresse2.country = "Italy";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when {@link Adresse#city} differs.
     */
    @Test
    void equals_shouldReturnFalse_whenCityDiffers() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        adresse2.city = "Marseille";
        assertNotEquals(adresse1, adresse2);
    }

    /**
     * Tests that equals returns false when GPS coordinates ({@link Adresse#latitude} or {@link Adresse#longitude}) differ.
     */
    @Test
    void equals_shouldReturnFalse_whenCoordinatesDiffer() {
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = new Adresse(
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
        Adresse adresse1 = createStandardAddress();
        Adresse adresse2 = createStandardAddress();
        assertEquals(adresse1.hashCode(), adresse2.hashCode());
    }

    // --------------------------------------------------
    // GetChecksum Tests
    // --------------------------------------------------

    /**
     * Tests that {@link Adresse#getChecksum()} returns a value consistent with
     * {@link Adresse#hashCode()} as both rely on the same fields.
     */
    @Test
    void getChecksum_shouldMatchHashCode() {
        Adresse adresse = createStandardAddress();
        // Based on the implementation, getChecksum uses Objects.hash like hashCode
        assertEquals(adresse.hashCode(), adresse.getChecksum());
    }

    /**
     * Tests that {@link Adresse#getChecksum()} returns different values for different content.
     */
    @Test
    void getChecksum_shouldChange_whenContentChanges() {
        Adresse adresse = createStandardAddress();
        int originalChecksum = adresse.getChecksum();
        // Modify a field
        adresse.city = "Marseille";
        int newChecksum = adresse.getChecksum();
        assertNotEquals(originalChecksum, newChecksum);
    }
}