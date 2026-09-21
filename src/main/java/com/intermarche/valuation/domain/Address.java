package com.intermarche.valuation.domain;

import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Value Object representing a standard postal address with GPS coordinates.
 * <p>
 * This is NOT an Entity (@Entity), but an Embeddable object.
 * It allows the address fields to be mapped directly into the parent entity's table.
 * <p>
 * Uses public fields to comply with the project's Panache/Entity conventions.
 */
@Embeddable
public class Address {

    // --------------------------------------------------
    // Address Fields
    // --------------------------------------------------

    /**
     * Street number and name (e.g., "10 Avenue des Champs-Élysées").
     */
    public String streetLine1;

    /**
     * Additional address information (e.g., "Batiment A", "Appartement 4").
     */
    public String streetLine2;

    /**
     * ZIP code or Postal code (e.g., "75008").
     */
    public String postalCode;

    /**
     * City or Locality (e.g., "Paris").
     */
    public String city;

    /**
     * Country code or name (e.g., "France").
     */
    public String country;

    /**
     * GPS Latitude coordinate.
     * Stored as a Double to allow null values if the position is unknown.
     */
    public Double latitude;

    /**
     * GPS Longitude coordinate.
     * Stored as a Double to allow null values if the position is unknown.
     */
    public Double longitude;

    // --------------------------------------------------
    // Constructors
    // --------------------------------------------------

    /**
     * Default constructor required by JPA/Hibernate.
     */
    public Address() {
    }

    /**
     * Full constructor including GPS coordinates.
     *
     * @param streetLine1 Line 1
     * @param streetLine2 Line 2
     * @param postalCode  Zip
     * @param city        City
     * @param country     Country
     * @param latitude    Latitude
     * @param longitude   Longitude
     */
    public Address(String streetLine1, String streetLine2, String postalCode, String city, String country, Double latitude, Double longitude) {
        this.streetLine1 = streetLine1;
        this.streetLine2 = streetLine2;
        this.postalCode = postalCode;
        this.city = city;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    // --------------------------------------------------
    // Equals and HashCode
    // --------------------------------------------------
    // Important for Value Objects: they should be compared by content.

    /**
     * Compares this address with another object for content equality.
     * <p>
     * Two addresses are equal when all their fields are equal, as befits a value object.
     *
     * @param o The object to compare with.
     * @return {@code true} if the other object is an Address with identical field values.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Address other = (Address) o;
        return Objects.equals(streetLine1, other.streetLine1) &&
                Objects.equals(streetLine2, other.streetLine2) &&
                Objects.equals(postalCode, other.postalCode) &&
                Objects.equals(city, other.city) &&
                Objects.equals(country, other.country) &&
                Objects.equals(latitude, other.latitude) &&
                Objects.equals(longitude, other.longitude);
    }

    /**
     * Computes a hash code consistent with {@link #equals(Object)}.
     *
     * @return A hash code derived from all address fields.
     */
    @Override
    public int hashCode() {
        return Objects.hash(streetLine1, streetLine2, postalCode, city, country, latitude, longitude);
    }

    /**
     * Computes a content-based checksum for this address.
     * <p>
     * Derived from all address fields, it is used to detect changes to the address content.
     *
     * @return The checksum computed from all address fields.
     */
    public int getChecksum() {
        int checksum = Objects.hash(
            streetLine1,
            streetLine2,
            postalCode,
            city,
            country,
            latitude,
            longitude);
        return checksum;
    }
}