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

    /**
     * JSON Schema describing an acceptable valuation request.
     * <p>
     * Offer specifications have been validated against a schema from the start; baskets,
     * which come from outside the application, were not. This schema closes that gap: it
     * is enforced by the valuation endpoint before the engine runs, so a malformed
     * request fails immediately with a precise message instead of surfacing later as an
     * {@link IllegalStateException} from deep inside a factory.
     * <p>
     * It is also the source of truth for the administration test form, which renders its
     * fields from these declarations.
     * <p>
     * {@code additionalProperties} is left permissive on purpose: rejecting unknown
     * fields would break existing callers sending attributes this version ignores.
     */
    public static final String BASKET_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Basket Valuation Request",
      "description": "A shopping basket submitted for valuation.",
      "type": "object",
      "required": [
        "storeCode",
        "items"
      ],
      "properties": {
        "customerCode": {
          "type": "string",
          "description": "Identifier of the customer owning the basket.",
          "x-label": "Customer code"
        },
        "storeCode": {
          "type": "string",
          "description": "Code of the store where the purchase takes place.",
          "minLength": 1,
          "x-widget": "store-code",
          "x-label": "Store"
        },
        "createdAt": {
          "type": "string",
          "description": "Creation timestamp of the basket, ISO-8601.",
          "x-label": "Created at"
        },
        "deliveryMode": {
          "type": "string",
          "enum": ["HOME_DELIVERY", "PICKUP", "IN_STORE"],
          "description": "How the basket is handed over to the customer.",
          "x-label": "Delivery mode"
        },
        "deliveryAddress": {
          "type": "object",
          "description": "Destination of the delivery. Required when the mode is HOME_DELIVERY.",
          "x-widget": "object",
          "x-label": "Delivery address",
          "properties": {
            "streetLine1": { "type": "string", "x-label": "Street line 1" },
            "streetLine2": { "type": "string", "x-label": "Street line 2" },
            "postalCode": { "type": "string", "x-label": "Postal code" },
            "city": { "type": "string", "x-label": "City" },
            "country": { "type": "string", "x-label": "Country" },
            "latitude": {
              "type": "number",
              "minimum": -90,
              "maximum": 90,
              "description": "Required to price a home delivery.",
              "x-label": "Latitude"
            },
            "longitude": {
              "type": "number",
              "minimum": -180,
              "maximum": 180,
              "description": "Required to price a home delivery.",
              "x-label": "Longitude"
            }
          }
        },
        "instructions": {
          "type": "array",
          "items": { "type": "string" },
          "description": "Special instructions, e.g. \"Deposit basket\".",
          "x-widget": "string-list",
          "x-label": "Instructions"
        },
        "vignettes": {
          "type": "object",
          "description": "Number of vignettes available per product EAN.",
          "additionalProperties": { "type": "integer", "minimum": 0 },
          "x-widget": "ean-quantity-map",
          "x-label": "Vignettes"
        },
        "items": {
          "type": "array",
          "minItems": 1,
          "description": "The lines of the basket.",
          "x-widget": "object-list",
          "x-label": "Items",
          "x-item-label": "line",
          "items": {
            "type": "object",
            "required": ["produceEan", "quantity"],
            "properties": {
              "lineId": {
                "type": "integer",
                "description": "Line number, unique within the basket.",
                "x-widget": "quantity",
                "x-label": "Line"
              },
              "produceEan": {
                "type": "string",
                "description": "EAN of the scanned product.",
                "minLength": 1,
                "x-widget": "ean",
                "x-label": "Product"
              },
              "quantity": {
                "type": "number",
                "exclusiveMinimum": 0,
                "description": "Units for a UNIT product, kilograms or litres otherwise.",
                "x-label": "Quantity"
              },
              "pricePerUnitExclTax": {
                "type": "number",
                "minimum": 0,
                "description": "Overrides the catalog price. Requires the two other price fields.",
                "x-widget": "money",
                "x-label": "Unit price (excl. tax)"
              },
              "pricePerUnitInclTax": {
                "type": "number",
                "minimum": 0,
                "description": "Overrides the catalog price. Requires the two other price fields.",
                "x-widget": "money",
                "x-label": "Unit price (incl. tax)"
              },
              "vatRate": {
                "type": "number",
                "minimum": 0,
                "description": "Overrides the catalog price. Requires the two other price fields.",
                "x-widget": "rate",
                "x-label": "VAT rate"
              },
              "priceDate": {
                "type": "string",
                "description": "Date used to look the price up, ISO-8601. Defaults to now.",
                "x-label": "Price date"
              }
            }
          }
        }
      }
    }
    """;

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