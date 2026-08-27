package com.intermarche.valuation.engine.offers;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Factory for the "COUPON_GRANT" offer type: coupons granted by the basket.
 * <p>
 * The whole behaviour — schema, tier mechanics, assiette, output shape — lives in
 * {@link InstrumentGrantFactory}; this bean only fixes the discriminator and the labels.
 */
@ApplicationScoped
public class CouponGrantFactory extends InstrumentGrantFactory {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "COUPON_GRANT";

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "COUPON_GRANT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the name of the granted instrument.
     *
     * @return "COUPON".
     */
    @Override
    protected String instrument() {
        return "COUPON";
    }

    /**
     * Returns the human display label used in the application type string.
     *
     * @return "Coupon Grant".
     */
    @Override
    protected String displayLabel() {
        return "Coupon Grant";
    }
}
