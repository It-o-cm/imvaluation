package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Service Provider Interface (SPI) representing a result of an offer application.
 * <p>
 * This is a functional interface defining the result of an offer calculation.
 * It uses {@link BigDecimal} for monetary values to ensure precision.
 */
public interface OfferApplication {

    /**
     * Retrieves the price evaluation calculated for this specific application.
     *
     * @return The {@link AmountEvaluation} object containing tax details.
     */
    AmountEvaluation getAmount();

    /**
     * Retrieves the collection of basket items that are covered by this offer application.
     * @return
     */
    Collection<Basket.Item> getItems();

    /**
     * Returns a string representation of the offer application type.
     * @return A descriptive string of the application.
     */
    String getType();

    /**
     * Returns this offer's items as valued result items, one per source line.
     * <p>
     * Each item carries the offer's attributed amount at the product's real VAT rate and
     * the identifier of the request line it came from, so a caller reconstructs a
     * line-by-line valuation by grouping on {@code lineId}. The amounts of the returned
     * items sum to {@link #getAmount()}.
     * <p>
     * The default splits the offer total across the items by their catalog TTC weight
     * (option b). An offer whose own logic already attributes amounts per item — because it
     * selects or discounts items by price — overrides this to reflect that attribution
     * exactly.
     *
     * @return The valued result items; empty when the offer covers no priced items.
     */
    default java.util.List<BasketEvaluation.Item> getValuedItems() {
        return java.util.List.of();
    }
}