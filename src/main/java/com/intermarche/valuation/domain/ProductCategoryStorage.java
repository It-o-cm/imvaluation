package com.intermarche.valuation.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.util.Objects;

/**
 * Entity materializing the storage link between a Product and its Category hierarchy.
 * <p>
 * This entity stores the product reference and the **names** (Strings) of up to 5 category levels.
 * <p>
 * By storing the hierarchy path as plain strings (e.g., "Food", "Fresh", "Dairy"),
 * it allows for rapid text-based queries and denormalization without needing JOINs
 * to the category table for every read.
 * <p>
 * It extends {@link BaseEntity} to inherit ID, versioning, and audit fields.
 */
@Entity
@Table(name = "product_category_storages",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "level1", "level5"})
)
@Cacheable
public class ProductCategoryStorage extends BaseEntity {

    // --------------------------------------------------
    // Relations
    // --------------------------------------------------

    /**
     * The product being categorized.
     * <p>
     * Note: This is a unidirectional link. Product does not reference this entity back.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    public Product product;

    // --------------------------------------------------
    // Category Hierarchy (Names as Strings)
    // --------------------------------------------------

    /**
     * Level 1 category name (Root level).
     * Example: "Food"
     */
    @Column(name = "level1")
    public String level1;

    /**
     * Level 2 category name.
     * Example: "Fresh"
     */
    @Column(name = "level2")
    public String level2;

    /**
     * Level 3 category name.
     * Example: "Dairy"
     */
    @Column(name = "level3")
    public String level3;

    /**
     * Level 4 category name.
     * Example: "Yogurts"
     */
    @Column(name = "level4")
    public String level4;

    /**
     * Level 5 category name (Leaf level).
     * Example: "Bio"
     */
    @Column(name = "level5")
    public String level5;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the product and the 5 category level strings.
     * This ensures the integrity of the classification data stored here.
     *
     * @return Checksum integer value
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                this.product.id,
                this.level1,
                this.level2,
                this.level3,
                this.level4,
                this.level5
        );
    }
}