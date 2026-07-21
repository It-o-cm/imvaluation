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
}