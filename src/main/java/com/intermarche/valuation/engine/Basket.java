package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object representing a Shopping Basket.
 * <p>
 * This object maps directly to the JSON structure accepted by the valuation endpoint.
 * It contains inner classes for nested objects like {@link Item} and {@link Address}.
 */
public class Basket {

    public Basket() {}

    /**
     * The unique code of the customer.
     */
    public String customerCode;

    /**
     * The code of the store where the purchase is made.
     */
    public String storeCode;

    /**
     * The timestamp when the basket was created (ISO format).
     */
    public String createdAt;

    /**
     * The mode of delivery (e.g., "HOME_DELIVERY", "PICKUP").
     */
    public String deliveryMode;

    /**
     * The delivery address details (required if mode is HOME_DELIVERY).
     */
    public Address deliveryAddress;

    /**
     * List of special instructions or consignments (e.g., "Deposit basket").
     */
    public List<String> instructions;

    /**
     * The list of items in the basket.
     */
    public List<Item> items;

    /**
     * Map of available vignettes (stickers/tokens) per product EAN.
     * <p>
     * Key: Product EAN.
     * Value: Number of vignettes available/spent for this product.
     */
    public Map<String, Integer> vignettes;

    // --------------------------------------------------
    // Inner Classes (Nested DTOs)
    // --------------------------------------------------

    /**
     * Inner class representing an item in the basket.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item implements EngineTrait {

        /**
         * The unique line identifier.
         */
        public Integer lineId;

        /**
         * The EAN code of the product (produce).
         */
        public String produceEan;

        /**
         * The price per unit including tax.
         */
        public BigDecimal pricePerUnitInclTax;

        /**
         * The price per unit excluding tax.
         */
        public BigDecimal pricePerUnitExclTax;

        /**
         * The tax rate applicable to the product.
         */
        public BigDecimal vatRate;

        /**
         * The date and time when the price was recorded.
         */
        public String priceDate;

        /**
         * The quantity (can be an integer or a decimal for weighed items).
         */
        public Double quantity;

        /**
         * Cached Product entity for this item.
         */
        transient Product product;

        /**
         * Get Product by EAN with validation.
         *
         * @return The validated Product.
         */
        @JsonIgnore
        public Product getProduct() {
            if (this.product == null) {
                this.product = getProduct(this.produceEan, "Configuration Error: Product not found for EAN '%s'");
            }
            return this.product;
        }

        /**
         * Retrieves or constructs the price for a basket item based on the provided context.
         * <p>
         * Priority logic:
         * <ol>
         *   <li>If manual pricing information is defined on the item (priceExcludingTax, etc.),
         *       a transient {@link Price} object is constructed and returned immediately.</li>
         *   <li>Otherwise, the method attempts to find the price in the database.
         *       <ul>
         *         <li>If {@code priceDate} (String) is filled in the item, it is parsed using ISO-8601 format and used for the lookup.</li>
         *         <li>Otherwise, the current date (from {@link DateTimeProvider}) is used.</li>
         *       </ul>
         *   </li>
         * </ol>
         *
         * @param store      The store context.
         * @param priceUsage The price usage type (e.g., DEFAULT).
         * @return The {@link Price} entity (either constructed from item data or retrieved from the database).
         * @throws IllegalStateException if the product, store, or price cannot be resolved, or if the date string is invalid.
         */
        public Price getPrice(Store store, PriceUsage priceUsage) throws IllegalStateException {
            // 1. Check if pricing info is defined directly on the item (Manual Pricing Override)
            if (this.pricePerUnitExclTax != null && this.pricePerUnitInclTax != null && this.vatRate != null) {
                Price manualPrice = new Price();
                manualPrice.priceExcludingTax = this.pricePerUnitExclTax;
                manualPrice.priceIncludingTax = this.pricePerUnitInclTax;
                manualPrice.vatRate = this.vatRate;
                return manualPrice;
            }
            // 2. Determine the date to use for the lookup
            LocalDateTime dateToUse;
            if (this.priceDate != null && !this.priceDate.isBlank()) {
                try {
                    dateToUse = LocalDateTime.parse(this.priceDate);
                } catch (java.time.format.DateTimeParseException e) {
                    throw new IllegalStateException(
                            String.format("Invalid date format '%s' for item EAN '%s'. Expected ISO-8601 format.",
                                    this.priceDate, this.produceEan), e);
                }
            } else {
                dateToUse = DateTimeProvider.now();
            }
            Product product = this.getProduct();
            // 3. Find the active price at the determined date
            Price price = Price.findActivePriceAtDate(product.id, store.id, dateToUse, priceUsage);
            if (price == null) {
                throw new IllegalStateException(String.format(
                        "Configuration Error: No active price found for Product '%s' (ID: %d) in Store '%s' (Checked at date: %s)",
                        product.name, product.id, store.code, dateToUse
                ));
            }
            return price;
        }

        /**
         * Calculates the total amount evaluation for this item in the given store context.
         *
         * @param store      The store context for the Amount calculation.
         * @param priceUsage The price usage type.
         * @return The {@link AmountEvaluation} for this item.
         */
        public AmountEvaluation getAmount(Store store, PriceUsage priceUsage) throws IllegalStateException {
            Price price = this.getPrice(store, priceUsage);
            return AmountEvaluation.getAmount(this.getProduct(), price, this.quantity);
        }
    }

    /**
     * Inner class representing a delivery address.
     */
    public static class Address {

        public Address() {}

        /**
         * The first line of the street address.
         */
        public String streetLine1;

        /**
         * The second line of the street address (apartment, building, etc.).
         */
        public String streetLine2;

        /**
         * The postal code.
         */
        public String postalCode;

        /**
         * The city name.
         */
        public String city;

        /**
         * The country name.
         */
        public String country;

        /**
         * The latitude of the delivery location.
         */
        public Double latitude;

        /**
         * The longitude of the delivery location.
         */
        public Double longitude;
    }
}