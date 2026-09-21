package com.intermarche.valuation.graphql;

/**
 * Exception thrown when an attempt is made to create or update an entity
 * that already exists (e.g., a unique constraint violation in business logic).
 * <p>
 * This is the logical counterpart of {@link java.util.NoSuchElementException}.
 */
public class AlreadyExistsException extends RuntimeException {

    /**
     * Constructs the exception with a detail message.
     *
     * @param message The detail message describing the conflict.
     */
    public AlreadyExistsException(String message) {
        super(message);
    }

    /**
     * Constructs the exception with a detail message and an underlying cause.
     *
     * @param message The detail message describing the conflict.
     * @param cause   The underlying cause of this exception.
     */
    public AlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}