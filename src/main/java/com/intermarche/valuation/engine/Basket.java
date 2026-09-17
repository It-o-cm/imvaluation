package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.VatRate;
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
          "description": "Special instructions, for example a drop-off note.",
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
        "couponCodes": {
          "type": "array",
          "items": { "type": "string", "minLength": 1 },
          "description": "Coupon codes presented at the till or online. Duplicates are ignored (a set).",
          "x-widget": "string-list",
          "x-label": "Coupon codes"
        },
        "closed": {
          "type": "boolean",
          "default": true,
          "description": "A fact declared by the caller: true = final basket, everything applies; false = basket in progress (till scan), AT_TOTAL advantages never fall. Never guessed by the engine.",
          "x-label": "Closed"
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
            "required": ["quantity"],
            "anyOf": [
              { "required": ["produceEan"] },
              { "required": ["pricePerUnitExclTax", "pricePerUnitInclTax", "vatRate"] }
            ],
            "properties": {
              "lineId": {
                "type": "string",
                "description": "Line identifier, unique within the basket.",
                "x-label": "Line"
              },
              "produceEan": {
                "type": "string",
                "description": "EAN of the scanned product. May be omitted for an unknown article, in which case the three price fields are required and the amount is the given price times the quantity.",
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
              "manualDiscountAmount": {
                "type": "number",
                "minimum": 0,
                "description": "Manual gesture: a fixed amount in euros deducted from the item price. Exclusive with the percentage.",
                "x-widget": "money",
                "x-label": "Manual discount (amount)"
              },
              "manualDiscountPercent": {
                "type": "number",
                "minimum": 0,
                "maximum": 100,
                "description": "Manual gesture: a percentage reduction on the item price. Exclusive with the amount.",
                "x-widget": "percent",
                "x-label": "Manual discount (%)"
              },
              "manualForcedPrice": {
                "type": "number",
                "minimum": 0,
                "description": "Manual gesture: a forced unit price (tax included), the label price. Exclusive with the other gestures.",
                "x-widget": "money",
                "x-label": "Forced price (incl. tax)"
              },
              "priceDate": {
                "type": "string",
                "description": "Date used to look the price up, ISO-8601. Defaults to now.",
                "x-label": "Price date"
              },
              "bestBeforeDate": {
                "type": "string",
                "format": "date",
                "description": "Best-before date (DLC) of this line, ISO-8601. Optional; drives the anti-waste discount. Per line: two lots of the same EAN are two lines.",
                "x-label": "Best-before date"
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

    /**
     * Coupon codes presented at the till or online (spec §3.7).
     * <p>
     * A set in spirit: duplicates are ignored, and the trigger counts a code once however
     * many times it is presented. A basket without coupon codes satisfies no
     * {@code COUPON_CODE} condition.
     */
    public List<String> couponCodes;

    /**
     * Whether the basket is final (spec §3.7).
     * <p>
     * A fact declared by the caller, never guessed by the engine, so two identical baskets
     * yield two identical responses (the stateless invariant). {@code true} means the basket
     * is final and everything applies (the default and the current behaviour); {@code false}
     * means the basket is still in progress (a till scan), where {@code AT_TOTAL} advantages
     * never fall. A {@code null} value is read as closed: a basket without {@code closed} is
     * closed.
     */
    public Boolean closed;

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
         * <p>
         * A String rather than a number: it labels a basket line and is never used in any
         * arithmetic, so an upstream system is free to send "A001", a UUID, or a plain "1".
         */
        public String lineId;

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
         * Manual cash-desk gesture: a fixed amount in euros deducted from the item price.
         * <p>
         * One of three mutually exclusive gestures ({@link #manualDiscountAmount},
         * {@link #manualDiscountPercent}, {@link #manualForcedPrice}). When any is set the
         * item is handled by the ultra-priority manual-gesture offer and is excluded from
         * every other offer and discount. Distinct from {@link #pricePerUnitInclTax}, which
         * is a normal contractual price that leaves the item eligible for every offer.
         */
        public BigDecimal manualDiscountAmount;

        /**
         * Manual cash-desk gesture: a percentage reduction applied to the item price.
         * <p>
         * Expressed as a percentage (e.g. {@code 10} for 10%). One of three mutually
         * exclusive gestures; see {@link #manualDiscountAmount}.
         */
        public BigDecimal manualDiscountPercent;

        /**
         * Manual cash-desk gesture: a forced unit price, tax included — the "label price".
         * <p>
         * Replaces the catalog price for this line; the product's catalog VAT rate is kept.
         * One of three mutually exclusive gestures; see {@link #manualDiscountAmount}. This
         * is not the same as {@link #pricePerUnitInclTax}: a forced price is a cash-desk
         * gesture that makes the line ultra-priority and bars every discount, whereas
         * {@link #pricePerUnitInclTax} is a normal price that keeps the line fully eligible.
         */
        public BigDecimal manualForcedPrice;

        /**
         * The date and time when the price was recorded.
         */
        public String priceDate;

        /**
         * Best-before date (DLC) of this line, ISO-8601 ({@code yyyy-MM-dd}); optional.
         * <p>
         * Additive, backward-compatible field (spec §7): a line without it behaves exactly as
         * before. It drives the {@code ANTI_WASTE_DISCOUNT} advantage, which reads the remaining
         * days to this date. It is per line — two lots of the same EAN with distinct expiry are
         * two lines, which is the caller's responsibility. The engine never derives or guesses
         * it.
         */
        public String bestBeforeDate;

        /**
         * The quantity (can be an integer or a decimal for weighed items).
         */
        public Double quantity;

        /**
         * Cached Product entity for this item.
         */
        transient Product product;

        /**
         * Contributions of the original basket lines to this item.
         * <p>
         * When several basket lines carry the same EAN, the engine aggregates them into a
         * single working item for pricing. This list preserves what each line contributed,
         * so a consumed quantity can later be split back across the exact lines it came
         * from, in order — the basis for a line-by-line valuation on the ticket.
         * <p>
         * It carries only the identifier and the quantity of each source line, never a
         * price, and is ignored in JSON: it is internal bookkeeping, not part of the
         * exchanged contract.
         */
        @JsonIgnore
        public transient java.util.List<SourceLine> sourceLines = new java.util.ArrayList<>();

        /**
         * One original basket line's contribution to an aggregated item.
         * <p>
         * Holds the line identifier and the quantity that line brought, so a later split
         * can rebuild per-line amounts exactly rather than by proportional guesswork.
         */
        public static class SourceLine {

            /**
             * Identifier of the original basket line.
             */
            public String lineId;

            /**
             * Quantity this line contributed to the aggregated item.
             */
            public double quantity;

            /**
             * Constructs a source-line contribution.
             *
             * @param lineId   Identifier of the original line.
             * @param quantity Quantity contributed by that line.
             */
            public SourceLine(String lineId, double quantity) {
                this.lineId = lineId;
                this.quantity = quantity;
            }
        }

        /**
         * Indicates whether this item carries a manual cash-desk gesture.
         * <p>
         * A gesture is a price forcing, a fixed discount, or a percentage discount. Price
         * forcing alone is not a gesture on its own here — it only sets the base price; a
         * gesture is a forced discount or percentage, which routes the item to the
         * ultra-priority manual-gesture offer. Price forcing combined with one of those is
         * still one gesture.
         *
         * @return {@code true} when a manual discount amount or percentage is present.
         */
        @JsonIgnore
        public boolean hasManualGesture() {
            return this.manualDiscountAmount != null
                    || this.manualDiscountPercent != null
                    || this.manualForcedPrice != null;
        }

        /**
         * Validates that at most one kind of manual discount is set on this item.
         *
         * @throws IllegalStateException when both a fixed amount and a percentage are given.
         */
        public void validateManualGesture() {
            int count = 0;
            if (this.manualDiscountAmount != null) count++;
            if (this.manualDiscountPercent != null) count++;
            if (this.manualForcedPrice != null) count++;
            if (count > 1) {
                throw new IllegalStateException(String.format(
                        "Item EAN '%s' carries more than one manual gesture (amount, percentage, "
                                + "forced price); only one is allowed.", this.produceEan));
            }
        }

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
         * @implNote When no price row exists for a non-DEFAULT usage (e.g. BASE_FOR_DISCOUNT), the
         *           lookup falls back to the DEFAULT usage: a product without a dedicated reference
         *           price uses its current price as the reference. Only a missing DEFAULT price is
         *           a configuration error.
         */
        public Price getPrice(Store store, PriceUsage priceUsage) throws IllegalStateException {
            // 1. Check if pricing info is defined directly on the item (Manual Pricing Override)
            if (this.pricePerUnitExclTax != null && this.pricePerUnitInclTax != null && this.vatRate != null) {
                Price manualPrice = new Price();
                manualPrice.priceExcludingTax = this.pricePerUnitExclTax;
                manualPrice.priceIncludingTax = this.pricePerUnitInclTax;
                // The request contract is unchanged: a manual line still carries a bare rate. The
                // transient carrier wraps it in an UNPERSISTED regime (number null) so that
                // Price#vatRate() reads the rate through exactly as it does for a catalog price.
                // This VatRate is never persisted and never joins the referential.
                manualPrice.vat = new VatRate(null, this.vatRate, null);
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
            if (price == null && priceUsage != PriceUsage.DEFAULT) {
                // No dedicated row for this usage: the reference price falls back to the
                // current price, so referentials only maintain BASE_FOR_DISCOUNT rows where
                // a distinct reference actually exists.
                price = Price.findActivePriceAtDate(product.id, store.id, dateToUse, PriceUsage.DEFAULT);
            }
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