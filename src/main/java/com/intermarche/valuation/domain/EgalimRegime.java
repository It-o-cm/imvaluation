package com.intermarche.valuation.domain;

import java.math.BigDecimal;

/**
 * The EGAlim regime of a product: which legal generosity ceiling applies to it.
 * <p>
 * EGAlim (loi 2018, loi Descrozaille 2023, loi Travert n° 2025-337) caps the cumulated
 * promotional generosity of a product line: 34% for food and petfood ({@link #FOOD_34}), 40%
 * for droguerie-parfumerie-hygiène ({@link #DPH_40}, raised from 34% by the loi Travert), and
 * no ceiling for everything else ({@link #EXEMPT}).
 * <p>
 * {@link #EXEMPT} is the deliberate fail-open default: a product with no declared regime is
 * never corrected. Tightening a product's ceiling is a data act (the {@code EGALIM_REGIME}
 * column of the product feed), never a default of the engine.
 */
public enum EgalimRegime {

    /**
     * Food and petfood: the generosity ceiling is 34%.
     */
    FOOD_34(new BigDecimal("0.34")),

    /**
     * Droguerie-parfumerie-hygiène: the generosity ceiling is 40% (loi Travert).
     */
    DPH_40(new BigDecimal("0.40")),

    /**
     * Everything else: no ceiling, never corrected. The default value.
     */
    EXEMPT(null);

    /**
     * The default legal ceiling as a fraction (0.34 = 34%), or {@code null} when the regime is
     * not capped.
     */
    private final BigDecimal defaultCap;

    /**
     * Builds a regime with its default legal ceiling.
     *
     * @param defaultCap the ceiling fraction, or {@code null} when the regime is exempt.
     */
    EgalimRegime(BigDecimal defaultCap) {
        this.defaultCap = defaultCap;
    }

    /**
     * Returns the default legal ceiling of this regime, as a fraction.
     *
     * @return the ceiling fraction (0.34, 0.40), or {@code null} for {@link #EXEMPT}.
     */
    public BigDecimal defaultCap() {
        return defaultCap;
    }

    /**
     * Tells whether this regime carries a generosity ceiling.
     *
     * @return {@code true} for {@link #FOOD_34} and {@link #DPH_40}, {@code false} for
     *         {@link #EXEMPT}.
     */
    public boolean isCapped() {
        return defaultCap != null;
    }
}
