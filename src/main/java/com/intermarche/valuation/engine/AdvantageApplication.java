package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;

/**
 * Service Provider Interface (SPI) representing a result of a discount application.
 * <p>
 * This is an interface defining the result of a discount calculation.
 * It uses {@link BigDecimal} for monetary values to ensure precision.
 */
public interface AdvantageApplication {

    /**
     * Retrieves, if exists, the offer application associated with this discount application.
     * If no offer application is associated, returns null. It means that the discount is applied to
     * the basket without any specific offer.
     *
     * @return The {@link OfferApplication} object.
     */
    @JsonIgnore
    OfferApplication getOfferApplication();

    /**
     * Returns a string representation of the offer type associated with this discount application.
     * If no offer application is associated, returns null.
     * @return A descriptive string of the offer type.
     */
    default String getOffer() {
        return this.getOfferApplication().getType();
    }

}