package com.intermarche.valuation.engine;

/**
 * An {@link OfferApplication} whose line can be re-priced to its reference price after the
 * arbitration has retained a discount on it (A1, report C1).
 * <p>
 * A standard line is valued at the DEFAULT price during the valuation; only once a discount is
 * actually retained on it is it switched to the reference price ({@code BASE_FOR_DISCOUNT}), so
 * that a discarded advantage never leaves the customer paying the higher reference price. The
 * engine finds the lines to switch through this interface, without depending on any concrete
 * offer type.
 */
public interface RepriceableApplication {

    /**
     * Switches the line from the default price to the reference price.
     *
     * @return {@code true} when the price was switched, {@code false} when there was nothing to
     *         switch (no reference price, or already on it).
     */
    boolean repriceToReference();
}
