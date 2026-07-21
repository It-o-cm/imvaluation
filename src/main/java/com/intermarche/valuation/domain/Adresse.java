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
public class Adresse {

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
    public Adresse() {
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
    public Adresse(String streetLine1, String streetLine2, String postalCode, String city, String country, Double latitude, Double longitude) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Adresse adresse = (Adresse) o;
        return Objects.equals(streetLine1, adresse.streetLine1) &&
                Objects.equals(streetLine2, adresse.streetLine2) &&
                Objects.equals(postalCode, adresse.postalCode) &&
                Objects.equals(city, adresse.city) &&
                Objects.equals(country, adresse.country) &&
                Objects.equals(latitude, adresse.latitude) &&
                Objects.equals(longitude, adresse.longitude);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streetLine1, streetLine2, postalCode, city, country, latitude, longitude);
    }

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