package com.intermarche.valuation.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link AlreadyExistsException} class.
 * Covers both constructors and the inherited RuntimeException behaviour.
 */
public class AlreadyExistsExceptionTest {

    /**
     * Verifies the message-only constructor stores the message and leaves the cause null.
     */
    @Test
    void testMessageOnlyConstructor() {
        AlreadyExistsException exception = new AlreadyExistsException("already there");
        assertEquals("already there", exception.getMessage());
        assertNull(exception.getCause());
    }

    /**
     * Verifies the message-only constructor accepts a null message.
     */
    @Test
    void testMessageOnlyConstructorWithNullMessage() {
        AlreadyExistsException exception = new AlreadyExistsException(null);
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    /**
     * Verifies the message-and-cause constructor stores both the message and the cause.
     */
    @Test
    void testMessageAndCauseConstructor() {
        Throwable cause = new IllegalStateException("root");
        AlreadyExistsException exception = new AlreadyExistsException("already there", cause);
        assertEquals("already there", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    /**
     * Verifies the message-and-cause constructor accepts null message and null cause.
     */
    @Test
    void testMessageAndCauseConstructorWithNulls() {
        AlreadyExistsException exception = new AlreadyExistsException(null, null);
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    /**
     * Verifies the exception is a RuntimeException so it can be thrown unchecked.
     */
    @Test
    void testIsRuntimeException() {
        AlreadyExistsException exception = new AlreadyExistsException("boom");
        assertTrue(exception instanceof RuntimeException);
    }
}
