package com.intermarche.valuation.graphql;

/**
 * Thrown when a mutation would write outside the signed-in user's perimeter — an offer type the
 * user may not use, or a store/group targeting beyond the user's store scope (spec §2).
 * <p>
 * It is a {@link RuntimeException} so it can be raised from inside the {@code Supplier} lambda that
 * {@link GraphQLTrait#execute} runs. That method turns it into a clean {@code GraphQLException}
 * carrying this message, rather than the masked generic "System error".
 */
public class ScopeViolationException extends RuntimeException {

    /**
     * Builds the exception with a message shown to the caller.
     *
     * @param message the human-readable reason the write is outside the perimeter.
     */
    public ScopeViolationException(String message) {
        super(message);
    }
}
