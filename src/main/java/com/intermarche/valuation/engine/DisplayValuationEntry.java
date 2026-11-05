package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * One output entry of a {@code POST /display-valuation} call — the display valuation of a single
 * EAN (spec-valorisation-affichage-ecommerce §2).
 * <p>
 * Either a valuation (every monetary field set) or an error (the {@code error} field names a
 * per-EAN referential gap while the rest of the batch is valued normally, §7 criterion 5). Null
 * fields are omitted: {@code immediateDiscount} is absent when nothing is marked down,
 * {@code unitPriceMeasure} when the product carries no measure reference, {@code floorGuard} when
 * no legal floor constrains the price. Monetary values are {@link AmountEvaluation}s (BigDecimal,
 * scale 2), the VAT rate is scale 4.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DisplayValuationEntry {

    /**
     * The EAN this entry values.
     */
    public String ean;

    /**
     * A per-EAN referential error (unknown article, no active price), or {@code null} on success.
     * When set, every valuation field is {@code null}.
     */
    public String error;

    /**
     * The crossed-out reference price ("au lieu de"): the base the engine posed for the line — the
     * reference price when a markdown applies, the current price otherwise.
     */
    public AmountEvaluation basePrice;

    /**
     * The displayed price after the product markdowns applicable to a single unit, EGAlim/SRP
     * floors respected; equal to {@link #basePrice} when nothing is marked down (rules 2, 4, 6).
     */
    public AmountEvaluation displayedPrice;

    /**
     * The total product markdown ({@code basePrice − displayedPrice}); {@code null} when nil.
     */
    public AmountEvaluation immediateDiscount;

    /**
     * The price per unit of measure (€/kg, €/L, …) computed from {@link #displayedPrice} and the
     * product's measure reference; {@code null} when the product carries none.
     */
    public UnitPrice unitPriceMeasure;

    /**
     * The basket-dependent offers targeting the product, as labels only — never converted to a
     * unit price (rule 1). Always present, possibly empty.
     */
    public List<DisplayContext.OfferBadge> offerBadges;

    /**
     * The VAT rate of the resolved price line (scale 4).
     */
    public BigDecimal vatRate;

    /**
     * The legal floor that constrained the displayed price, or {@code null} when none did.
     */
    public FloorGuard floorGuard;

    /**
     * The opaque price+offer fingerprint of {@code (store, valuationDate)}, for cache and
     * reconciliation (rule 5).
     */
    public String priceVersion;

    /**
     * Default constructor for JSON serialization.
     */
    public DisplayValuationEntry() {
    }

    /**
     * Builds an error entry for one EAN, leaving every valuation field null.
     *
     * @param ean          the offending EAN.
     * @param error        the message naming the gap.
     * @param priceVersion the call's fingerprint, still carried so the entry is reconcilable.
     * @return the error entry.
     */
    public static DisplayValuationEntry error(String ean, String error, String priceVersion) {
        DisplayValuationEntry entry = new DisplayValuationEntry();
        entry.ean = ean;
        entry.error = error;
        entry.priceVersion = priceVersion;
        return entry;
    }

    /**
     * The price per unit of measure of a product (spec §4): a value and the unit it is expressed in
     * ({@code kg}, {@code L}, {@code pcs}). Fields are public for JSON serialization.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UnitPrice {

        /**
         * The price for one reference unit of measure, tax included, scale 2 HALF_UP.
         */
        public BigDecimal value;

        /**
         * The unit the value is expressed per, from {@code Product.unitName}.
         */
        public String unit;

        /**
         * Default constructor for JSON serialization.
         */
        public UnitPrice() {
        }

        /**
         * Builds a unit price.
         *
         * @param value the price per reference unit.
         * @param unit  the unit name.
         */
        public UnitPrice(BigDecimal value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    /**
     * The legal floor that constrained a displayed price (spec §2, rule 4): which guard bound the
     * price and the floor it was held at. Fields are public for JSON serialization.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FloorGuard {

        /**
         * The guard that bound the price ({@code EGALIM} or {@code SRP}).
         */
        public String type;

        /**
         * The floor the displayed price was held at, tax included, scale 2.
         */
        public BigDecimal floorPrice;

        /**
         * Default constructor for JSON serialization.
         */
        public FloorGuard() {
        }

        /**
         * Builds a floor guard.
         *
         * @param type       the guard type.
         * @param floorPrice the floor price.
         */
        public FloorGuard(String type, BigDecimal floorPrice) {
            this.type = type;
            this.floorPrice = floorPrice;
        }
    }
}
