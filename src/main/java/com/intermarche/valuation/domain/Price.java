package com.intermarche.valuation.domain;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing a Price for a specific Product in a specific Store.
 * <p>
 * Prices are time-sensitive. The validity is defined by an interval [start, end).
 * Both start and end dates can be null, representing the beginning and end of time respectively.
 * A priority indicator is used to resolve overlaps: a higher priority overrides a lower one.
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "prices",
        indexes = {
                @Index(name = "idx_price_product_store", columnList = "product_id, store_id"),
                @Index(name = "idx_price_validity", columnList = "product_id, store_id, start_date_time, end_date_time")
        }
)
@Cacheable
public class Price extends BaseEntity {

    // --------------------------------------------------
    // Relations
    // --------------------------------------------------

    /**
     * The product this price applies to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @NotNull(message = "Product is mandatory")
    public Product product;

    /**
     * The store where this price applies.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    @NotNull(message = "Store is mandatory")
    public Store store;

    /**
     * Defines how the price is used (e.g., Standard, Promotional, Base for Discount).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Price usage is mandatory")
    public PriceUsage priceUsage;

    // --------------------------------------------------
    // Pricing Details
    // --------------------------------------------------

    /**
     * Price Excluding Tax (Prix HT).
     * High precision (scale=4) to avoid rounding errors during tax calculations.
     */
    @Column(name = "price_excluding_tax", nullable = false, precision = 19, scale = 4)
    @NotNull(message = "Price Excluding Tax is mandatory")
    @PositiveOrZero(message = "Price must be positive")
    public BigDecimal priceExcludingTax;

    /**
     * Price Including Tax (Prix TTC).
     */
    @Column(name = "price_including_tax", nullable = false, precision = 19, scale = 4)
    @NotNull(message = "Price Including Tax is mandatory")
    @PositiveOrZero(message = "Price must be positive")
    public BigDecimal priceIncludingTax;

    /**
     * VAT Rate (Taux de TVA) applied to this price (e.g., 0.2000 for 20%).
     */
    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    @NotNull(message = "VAT Rate is mandatory")
    @PositiveOrZero(message = "VAT Rate must be positive")
    public BigDecimal vatRate;

    // --------------------------------------------------
    // Priority & Validity
    // --------------------------------------------------

    /**
     * Priority of this price.
     * Used when multiple prices are valid for the same product at the same time.
     * The price with the higher priority value should be selected.
     * Default is 0 (Standard price).
     */
    @Column(name = "priority", nullable = false)
    public Integer priority = 0;

    /**
     * Start date and time of the price validity.
     * If null, the price is valid from the beginning of time.
     */
    @Column(name = "start_date_time")
    public LocalDateTime startDateTime;

    /**
     * End date and time of the price validity.
     * If null, the price is valid indefinitely (until end of time).
     */
    @Column(name = "end_date_time")
    public LocalDateTime endDateTime;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds the valid price for a specific product, store, date, and usage.
     * <p>
     * Validity logic:
     * <ul>
     *   <li>Start: Must be null OR <= targetDate.</li>
     *   <li>End: Must be null OR > targetDate.</li>
     * </ul>
     * If multiple prices match the criteria, the one with the HIGHEST priority is returned.
     *
     * @param productId The ID of the product
     * @param storeId   The ID of the store
     * @param date      The date to check validity against
     * @param priceUsage The usage type to filter by (e.g., DEFAULT, BASE_FOR_DISCOUNT)
     * @return The active Price with the highest priority, or null
     */
    public static Price findActivePriceAtDate(Long productId, Long storeId, LocalDateTime date, PriceUsage priceUsage) {
        // We use a query that handles null dates by treating them as +/- infinity
        return find(
                "product.id = ?1 and store.id = ?2 and priceUsage = ?4 " +
                        "and (startDateTime is null or startDateTime <= ?3) " +
                        "and (endDateTime is null or endDateTime > ?3) " +
                        "order by priority DESC",
                productId, storeId, date, priceUsage
        ).firstResult();
    }

    /**
     * Finds the current active price for a specific product and store.
     * <p>
     * Defaults to {@link PriceUsage#DEFAULT} and {@link DateTimeProvider#now()}.
     *
     * @param productId The ID of the product
     * @param storeId   The ID of the store
     * @return The active Price or null
     */
    public static Price findCurrentPrice(Long productId, Long storeId) {
        return findCurrentPrice(productId, storeId, PriceUsage.DEFAULT);
    }

    /**
     * Finds the current active price for a specific product, store, and usage.
     * <p>
     * Uses {@link DateTimeProvider} to determine "now".
     *
     * @param productId The ID of the product
     * @param storeId   The ID of the store
     * @param priceUsage The usage type to filter by.
     * @return The active Price or null
     */
    public static Price findCurrentPrice(Long productId, Long storeId, PriceUsage priceUsage) {
        return findActivePriceAtDate(productId, storeId, DateTimeProvider.now(), priceUsage);
    }

    /**
     * Checks if this price is currently active based on the DateTimeProvider.
     *
     * @return true if now is within the validity interval, false otherwise
     */
    public boolean isActive() {
        LocalDateTime now = DateTimeProvider.now();
        boolean startValid = (startDateTime == null) || (startDateTime.isBefore(now) || startDateTime.isEqual(now));
        boolean endValid = (endDateTime == null) || endDateTime.isAfter(now);
        return startValid && endValid;
    }

    // --------------------------------------------------
    // Equals and HashCode
    // --------------------------------------------------
    // Relying on BaseEntity implementation using ID.

    /**
     * Computes a checksum for the Price entity based on its significant fields.
     * <p>
     * This method combines the product EAN, store code, price usage, pricing details,
     * priority, and validity dates to generate a hash code.
     *
     * @return The computed checksum as an integer.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(product.ean, store.code, priceUsage, priceExcludingTax, priceIncludingTax, vatRate, priority, startDateTime, endDateTime);
    }
}