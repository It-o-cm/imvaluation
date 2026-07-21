package com.intermarche.valuation.graphql;

/**
 * Exception thrown when an attempt is made to create or update an entity
 * that already exists (e.g., a unique constraint violation in business logic).
 * <p>
 * This is the logical counterpart of {@link java.util.NoSuchElementException}.
 */
public class AlreadyExistsException extends RuntimeException {

    public AlreadyExistsException(String message) {
        super(message);
    }

    public AlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}