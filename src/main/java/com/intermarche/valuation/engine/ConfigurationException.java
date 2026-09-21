package com.intermarche.valuation.engine;

/**
 * Signals that a stored configuration (an offer/advantage specification, trigger or arbitration
 * block) could not be honoured — as opposed to a problem with the request itself.
 * <p>
 * This is the boundary of the fail-closed doctrine (A2, report H1): the engine skips the faulty
 * <em>configuration</em> and continues the evaluation, but a request-level error (a malformed
 * basket line, a product the basket references that the catalog does not know) is a different
 * matter and is left to propagate so the caller is told its request was rejected. Extending
 * {@link IllegalArgumentException} keeps the existing basket-schema validation path — which also
 * flows through the specification validator — answering the caller with a 400 unchanged.
 */
public class ConfigurationException extends IllegalArgumentException {

    /**
     * Builds a configuration exception with a message.
     *
     * @param message the human-readable reason the configuration is invalid.
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Builds a configuration exception with a message and a cause.
     *
     * @param message the human-readable reason the configuration is invalid.
     * @param cause   the underlying error.
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
