package com.intermarche.valuation.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;
import java.util.Set;

/**
 * Entity representing a physical Store (Supermarket/Hypermarket).
 * <p>
 * A Store owns its Categories.
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "stores",
        indexes = {
                @Index(name = "idx_store_code", columnList = "code"),
                @Index(name = "idx_store_name", columnList = "name")
        }
)
@Cacheable
public class Store extends BaseEntity {

    // --------------------------------------------------
    // Store Details
    // --------------------------------------------------

    /**
     * The unique code of the store (e.g., "0034").
     * The unique constraint implicitly creates a unique index.
     */
    @Column(unique = true, nullable = false, length = 20)
    @NotBlank(message = "Store code is mandatory")
    public String code;

    /**
     * The name of the store (e.g., "Intermarché Lyon Centre").
     * Indexed to speed up search queries.
     */
    @Column(nullable = false)
    @NotBlank(message = "Store name is mandatory")
    public String name;

    /**
     * The full address of the store including GPS coordinates.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "streetLine1", column = @Column(name = "address_street_line1")),
            @AttributeOverride(name = "streetLine2", column = @Column(name = "address_street_line2")),
            @AttributeOverride(name = "postalCode", column = @Column(name = "address_postal_code")),
            @AttributeOverride(name = "city", column = @Column(name = "address_city")),
            @AttributeOverride(name = "country", column = @Column(name = "address_country")),
            @AttributeOverride(name = "latitude", column = @Column(name = "address_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "address_longitude"))
    })
    public Adresse address;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a store by its unique code.
     *
     * @param code The store code
     * @return The Store or null
     */
    public static Store findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Calculates a checksum based on the product's key attributes.
     * @return Checksum integer value
     */
    @Override
    public int getChecksum() {
        int addressChecksum = address == null ? 0 : address.getChecksum();
        int checksum = Objects.hash(code, name, addressChecksum);
        return checksum;
    }
}