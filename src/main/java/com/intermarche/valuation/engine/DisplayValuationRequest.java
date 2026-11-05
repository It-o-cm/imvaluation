package com.intermarche.valuation.engine;

import java.util.List;

/**
 * The payload of a {@code POST /display-valuation} call
 * (spec-valorisation-affichage-ecommerce §2).
 * <p>
 * It carries the basket conventions of {@code /valuation} restricted to what a display valuation
 * needs: the store and effective date giving the price/offer/guard context, the EANs to value, and
 * the OPTIONAL loyalty card number plus the card promotions the orchestrator (immcomm) has already
 * obtained from imfid and <em>pushes</em> here — the engine never resolves a card. Fields are
 * public for JSON deserialization.
 */
public class DisplayValuationRequest {

    /**
     * Default constructor used by the JSON deserializer.
     */
    public DisplayValuationRequest() {
    }

    /**
     * Code of the store giving the price, offer and EGAlim/SRP context.
     */
    public String storeCode;

    /**
     * Effective date (ISO {@code yyyy-MM-dd}) at which prices and offers are read; its campaign
     * bounds decide which offers are in force.
     */
    public String valuationDate;

    /**
     * The EANs to value, each as an isolated one-unit mini-basket. Capped per call by
     * {@code valuation.display.max-eans} (default 200): beyond the cap the call is refused 400.
     */
    public List<String> eans;

    /**
     * Optional loyalty card number, echoed for traceability; the engine never resolves it (same
     * convention as {@code Basket.cardNumber}).
     */
    public String cardNumber;

    /**
     * Optional card promotions pushed by the caller, same shape and guarantees as at
     * {@code /valuation} ({@code Basket.CardPromotion}); without this block only the public price
     * is returned. A {@code CAGNOTTE} entry is silently ignored, exactly as at the basket.
     */
    public List<Basket.CardPromotion> cardPromotions;
}
