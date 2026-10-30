package com.intermarche.valuation.domain;

/**
 * Enumeration defining how a price is utilized in calculations.
 * <p>
 * This enum helps distinguish between standard pricing and
 * pricing used as a base for discount calculations.
 */
public enum PriceUsage {

    /** Standard price usage without special considerations. */
    DEFAULT,

    /** Price used as a base for calculating discounts. */
    BASE_FOR_DISCOUNT,

    /**
     * Resale-below-cost floor (SRP + 10%): the administered minimum tax-included unit price a line
     * may be sold at (spec-srp-guard §1). Pushed by the retailer's commercial management as a
     * ready-computed price row (effective purchase price × 1.10). A product with no active
     * {@code SRP_FLOOR} row is simply not floor-controlled — the guard fails open on data.
     */
    SRP_FLOOR
}