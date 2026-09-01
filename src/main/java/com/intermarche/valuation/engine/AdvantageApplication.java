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

    /**
     * Returns the application moment of the configuration that produced this advantage
     * (spec §3.6, §4.5), restituted in the response.
     * <p>
     * {@code AT_TOTAL} (the default and current behaviour) means the advantage is applied on
     * a closed basket, in the second arbitration wave; {@code AT_TRIGGER} means it applies as
     * soon as it is applicable, including on an open basket, in the first wave. The default
     * is {@code AT_TOTAL} so every existing advantage restitutes the current value unchanged;
     * concrete applications override it to carry the real moment set by the arbitration.
     *
     * @return the application moment, {@code "AT_TOTAL"} by default.
     */
    default String getApplicationMoment() {
        return "AT_TOTAL";
    }

    /**
     * Records the application moment of the configuration that produced this advantage, set
     * by the arbitration once the advantage is retained (spec §4.5).
     * <p>
     * The default is a no-op so advantages that do not carry a moment (none is expected in
     * production, but test doubles may) are unaffected; concrete applications override it to
     * store the value returned by {@link #getApplicationMoment()}.
     *
     * @param applicationMoment the moment to record ({@code AT_TRIGGER} or {@code AT_TOTAL}).
     */
    default void setApplicationMoment(String applicationMoment) {
    }

}