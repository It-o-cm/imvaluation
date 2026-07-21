package com.intermarche.valuation.domain;

import com.intermarche.valuation.engine.AmountEvaluation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Entity representing a Product in a supermarket POS system.
 * <p>
 * This class focuses strictly on the product's identity and intrinsic attributes.
 * It does NOT contain references to categories, as categorization depends on the Store.
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "products",
        indexes = @Index(name = "idx_product_name", columnList = "name")
)
@Cacheable
public class Product extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The EAN (European Article Number) code, commonly known as barcode.
     * This is the primary identifier used for lookups at the register.
     * Unique constraint implies a database index.
     */
    @Column(name = "ean", unique = true, nullable = false, length = 13)
    @NotBlank(message = "EAN is mandatory")
    public String ean;

    // --------------------------------------------------
    // Product Details
    // --------------------------------------------------

    @Column(nullable = false)
    @NotBlank(message = "Product name is mandatory")
    public String name;

    @Column(length = 255)
    public String description;

    /**
     * The brand of the product.
     * Optional field to specify the manufacturer or brand name.
     */
    @Column(name = "brand", length = 100)
    public String brand;

    // --------------------------------------------------
    // Dimensions & Weight
    // --------------------------------------------------

    /**
     * Reference weight for the product.
     * <p>
     * This optional field can be used for inventory (pallet weight),
     * or as a default weight for products sold by unit (e.g., average weight of a melon).
     * Stored in Kilograms (kg) with 3 decimal places (gram precision).
     * Null if not applicable.
     */
    @Column(name = "reference_weight", precision = 10, scale = 3)
    public BigDecimal referenceWeight;

    /**
     * Reference volume for the product.
     * <p>
     * This optional field can be used for inventory (bottle size, tank capacity),
     * or as a default volume for products sold by unit (e.g., standard drink size).
     * Stored in Liters (L) with 3 decimal places (milliliter precision).
     * Null if not applicable.
     */
    @Column(name = "reference_volume", precision = 10, scale = 3)
    public BigDecimal referenceVolume;

    /**
     * Defines how the product is sold (Unit vs Weight).
     * Intrinsic to the product definition.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Product type is mandatory")
    public ProductType productType;

    /**
     * Display unit for the product (e.g., "kg", "pcs", "L").
     */
    @Column(name = "unit_name", length = 20)
    public String unitName;

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    /**
     * Flag to indicate if the product definition is globally active.
     * Note: A product can be active globally but not present in a specific store's categories.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Converts a given quantity into standard units based on the product type.
     * <p>
     * For UNIT products, the quantity is returned as-is.
     * For WEIGHT or VOLUME products, the quantity (in kg or L) is divided by the reference weight or volume.
     *
     * @param quantity The quantity to convert (can be integer or decimal).
     * @return The quantity expressed in standard units.
     */
    public BigDecimal standardQuantity(double quantity) {
        if (this.productType == ProductType.UNIT) {
            return BigDecimal.valueOf(quantity);
        }
        else {
            if (this.referenceWeight == null || this.referenceWeight.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal quantityKg = BigDecimal.valueOf(quantity);
            return quantityKg.divide(this.referenceWeight, 6, RoundingMode.HALF_UP);
        }
    }

    /**
     * Finds a product by its unique EAN code.
     * This is the most frequently used method at checkout.
     *
     * @param ean The scanned EAN code
     * @return The Product or null
     */
    public static Product findByEan(String ean) {
        return find("ean", ean).firstResult();
    }

    /**
     * Finds a product by EAN code, ensuring it is globally active.
     *
     * @param ean The scanned EAN code
     * @return The active Product or null
     */
    public static Product findActiveByEan(String ean) {
        return find("ean = ?1 and active = true", ean).firstResult();
    }

    /**
     * Calculates a checksum based on the product's key attributes.
     * @return Checksum integer value
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ean, name, description, brand, referenceWeight, referenceVolume, productType, unitName, active);
    }
}