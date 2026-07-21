package com.intermarche.valuation.domain;

/**
 * Enumeration representing how a product is quantified and sold.
 * Distinguishing between weighted items and unit items is critical
 * for supermarket Point of Sale (POS) systems.
 */
public enum ProductType {

    /**
     * Sold by a single unit (e.g., a cereal box, a toothbrush).
     * The barcode scan directly adds one item to the cart.
     */
    UNIT,

    /**
     * Sold by weight (e.g., fruits, vegetables, deli meat).
     * Typically requires a scale-generated barcode or manual weight entry.
     */
    WEIGHT,

    /**
     * Sold by volume (e.g., bulk liquids, fuel).
     */
    VOLUME
}