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
    BASE_FOR_DISCOUNT
}