package com.intermarche.valuation.engine;

/**
 * Signals that the referential cannot resolve something the request names, so the basket
 * is impossible to value through no fault of the engine: an article the catalog does not
 * know and whose price is not pushed, a store code that resolves to nothing, or a known
 * article without any active price row.
 * <p>
 * This is distinct from {@link ConfigurationException}, which marks a faulty <em>stored</em>
 * offer/advantage specification the engine skips to stay fail-closed. A referential gap is a
 * dead end for the whole evaluation: it is left to propagate. It extends
 * {@link IllegalStateException} so the engine call sites and their tests, which already expect
 * an {@code IllegalStateException}, keep working, while {@link ValuationResource} singles out
 * this subtype to answer HTTP 422 (Unprocessable Entity) with a message naming the offending
 * EAN or store, instead of a bare HTTP 500.
 */
public class ReferentialException extends IllegalStateException {

    /**
     * Builds the exception with a message that names the offending EAN or store.
     *
     * @param message the human-readable referential error, naming the EAN or the store.
     */
    public ReferentialException(String message) {
        super(message);
    }
}
