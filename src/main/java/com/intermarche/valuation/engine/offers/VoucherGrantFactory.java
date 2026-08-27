package com.intermarche.valuation.engine.offers;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Factory for the "VOUCHER_GRANT" offer type: bons d'achat granted by the basket.
 * <p>
 * The whole behaviour — schema, tier mechanics, assiette, output shape — lives in
 * {@link InstrumentGrantFactory}; this bean only fixes the discriminator and the labels.
 */
@ApplicationScoped
public class VoucherGrantFactory extends InstrumentGrantFactory {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "VOUCHER_GRANT";

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "VOUCHER_GRANT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the name of the granted instrument.
     *
     * @return "VOUCHER".
     */
    @Override
    protected String instrument() {
        return "VOUCHER";
    }

    /**
     * Returns the human display label used in the application type string.
     *
     * @return "Voucher Grant".
     */
    @Override
    protected String displayLabel() {
        return "Voucher Grant";
    }
}
