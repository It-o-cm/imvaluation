package com.intermarche.valuation.graphql;

import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link GraphQLTrait} interface.
 * Tests the default execute method behavior for success and various exception scenarios.
 */
public class GraphQLTraitTest {

    // Create an anonymous implementation to test the default method
    private final GraphQLTrait trait = new GraphQLTrait() {};

    // --------------------------------------------------
    // Success Scenario
    // --------------------------------------------------

    /**
     * Tests successful execution of the supplier.
     */
    @Test
    void testExecute_Success() throws GraphQLException {
        String result = trait.execute(() -> "Success", GraphQLTraitTest.class, "testOperation");
        assertEquals("Success", result);
    }

    // --------------------------------------------------
    // Exception Scenarios
    // --------------------------------------------------

    /**
     * Tests that {@link AlreadyExistsException} is caught and wrapped in {@link GraphQLException}.
     * <p>
     * Validates:
     * - Exception message is preserved in the GraphQLException message.
     * - The original exception is set as the cause.
     */
    @Test
    void testExecute_AlreadyExistsException() {
        String errorMessage = "Resource already exists";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            trait.execute(() -> {
                throw new AlreadyExistsException(errorMessage);
            }, GraphQLTraitTest.class, "createResource");
        });

        assertTrue(ex.getMessage().contains(errorMessage));
        assertTrue(ex.getCause() instanceof AlreadyExistsException);
    }

    /**
     * Tests that {@link PersistenceException} is caught and wrapped in {@link GraphQLException}.
     * <p>
     * Validates:
     * - A generic database error message is returned (not exposing raw DB errors).
     * - The original exception is set as the cause.
     */
    @Test
    void testExecute_PersistenceException() {
        PersistenceException cause = new PersistenceException("Constraint violation");

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            trait.execute(() -> {
                throw cause;
            }, GraphQLTraitTest.class, "saveResource");
        });

        assertTrue(ex.getMessage().contains("Database error while performing saveResource"));
        assertEquals(cause, ex.getCause());
    }

    /**
     * Tests that {@link NoSuchElementException} is re-thrown directly without wrapping.
     * <p>
     * Validates:
     * - The exception is NOT wrapped in a GraphQLException.
     */
    @Test
    void testExecute_NoSuchElementException() {
        String errorMessage = "Item not found";
        NoSuchElementException cause = new NoSuchElementException(errorMessage);

        // Expect NoSuchElementException directly, not GraphQLException
        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> {
            trait.execute(() -> {
                throw cause;
            }, GraphQLTraitTest.class, "getResource");
        });

        assertEquals(errorMessage, ex.getMessage());
    }

    /**
     * Tests that generic {@link Exception} is caught and wrapped in {@link GraphQLException}.
     * <p>
     * Validates:
     * - A generic error message is returned.
     * - The original exception is set as the cause.
     */
    @Test
    void testExecute_GenericException() {
        RuntimeException cause = new RuntimeException("Unexpected failure");

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            trait.execute(() -> {
                throw cause;
            }, GraphQLTraitTest.class, "processResource");
        });

        assertTrue(ex.getMessage().contains("An error occurred during processResource"));
        assertEquals(cause, ex.getCause());
    }

    /**
     * Tests the constructor with a message only.
     */
    @Test
    void testConstructor_Message() {
        String message = "Entity already exists";
        AlreadyExistsException ex = new AlreadyExistsException(message);

        assertEquals(message, ex.getMessage());
        assertNull(ex.getCause());
    }

    /**
     * Tests the constructor with a message and a cause.
     */
    @Test
    void testConstructor_MessageAndCause() {
        String message = "Entity already exists";
        Throwable cause = new RuntimeException("Underlying DB error");

        AlreadyExistsException ex = new AlreadyExistsException(message, cause);

        assertEquals(message, ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}