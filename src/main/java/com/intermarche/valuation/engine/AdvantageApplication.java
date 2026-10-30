package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

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

    /**
     * Returns the accounting ventilation key copied verbatim from the configuration
     * (spec-divers-offre §3): the engine does not interpret it, the till and accounting do.
     * <p>
     * Restituted in the response only when set; the default is {@code null} so an advantage
     * whose configuration carries no {@code accountingCode} leaves the output unchanged
     * bit-for-bit. Concrete applications override it to carry the value stamped by the engine.
     *
     * @return the accounting code, or {@code null} when the configuration set none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    default String getAccountingCode() {
        return null;
    }

    /**
     * Records the accounting ventilation key copied from the configuration (spec-divers-offre §3).
     * <p>
     * The default is a no-op; concrete applications override it to store the value.
     *
     * @param accountingCode the accounting code, or {@code null}.
     */
    default void setAccountingCode(String accountingCode) {
    }

    /**
     * Returns the ticket print mode copied verbatim from the configuration (spec-divers-offre §3):
     * {@code LINE} (printed on its own line) or {@code FOOTER} (printed at the ticket foot).
     * <p>
     * Restituted in the response only when set; the default is {@code null} so an advantage whose
     * configuration carries no {@code printMode} leaves the output unchanged bit-for-bit. Concrete
     * applications override it to carry the value stamped by the engine.
     *
     * @return the print mode, or {@code null} when the configuration set none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    default String getPrintMode() {
        return null;
    }

    /**
     * Records the ticket print mode copied from the configuration (spec-divers-offre §3).
     * <p>
     * The default is a no-op; concrete applications override it to store the value.
     *
     * @param printMode the print mode ({@code LINE} or {@code FOOTER}), or {@code null}.
     */
    default void setPrintMode(String printMode) {
    }

    /**
     * Returns the promotion funding copied verbatim from the configuration (spec-exploitation §3):
     * {@code STORE}, {@code BANNER} or {@code SPLIT_50_50}.
     * <p>
     * Restituted in the response only when set; the default is {@code null} so an advantage whose
     * configuration carries no {@code funding} leaves the output unchanged bit-for-bit. Concrete
     * applications override it to carry the value stamped by the engine.
     *
     * @return the funding, or {@code null} when the configuration set none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    default String getFunding() {
        return null;
    }

    /**
     * Records the promotion funding copied from the configuration (spec-exploitation §3).
     * <p>
     * The default is a no-op; concrete applications override it to store the value.
     *
     * @param funding the funding ({@code STORE}, {@code BANNER} or {@code SPLIT_50_50}), or
     *                {@code null}.
     */
    default void setFunding(String funding) {
    }

    /**
     * Tells whether the source configuration flags its discounts as EGAlim-exempt
     * (spec-divers-offre §2): when {@code true}, this advantage's generosity is counted as
     * {@code excludedGenerosity} by the EGAlim guard, like the anti-gaspi exemption.
     * <p>
     * Internal marker read by the guard, never serialized. The default is {@code false} so an
     * advantage with no exemption behaves exactly as today. Concrete applications override it.
     *
     * @return {@code true} when the configuration set {@code compliance.egalimExempt}.
     */
    @JsonIgnore
    default boolean isEgalimExempt() {
        return false;
    }

    /**
     * Records the EGAlim-exempt flag copied from the configuration (spec-divers-offre §2).
     * <p>
     * The default is a no-op; concrete applications override it to store the value.
     *
     * @param egalimExempt whether the configuration is EGAlim-exempt.
     */
    default void setEgalimExempt(boolean egalimExempt) {
    }

    /**
     * Tells whether the source configuration flags its discounts as SRP-exempt
     * (spec-divers-offre §2): a marker reserved for the future SRP guard (spec-srp-guard.md),
     * carried and traced now, with no consumer in this delivery.
     * <p>
     * Internal marker, never serialized. The default is {@code false}. Concrete applications
     * override it to carry the value stamped by the engine.
     *
     * @return {@code true} when the configuration set {@code compliance.srpExempt}.
     */
    @JsonIgnore
    default boolean isSrpExempt() {
        return false;
    }

    /**
     * Records the SRP-exempt flag copied from the configuration (spec-divers-offre §2).
     * <p>
     * The default is a no-op; concrete applications override it to store the value.
     *
     * @param srpExempt whether the configuration is SRP-exempt.
     */
    default void setSrpExempt(boolean srpExempt) {
    }

}