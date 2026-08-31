package com.intermarche.valuation.graphql;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @QuarkusTest} coverage of {@link GraphQLTrait#execute}.
 * <p>
 * Every exception arm of the centralized error handler is driven through an anonymous trait
 * implementation. The class is a {@code @QuarkusTest} solely so quarkus-jacoco attributes the
 * executed lines; nothing here touches the database.
 */
@QuarkusTest
public class GraphQLTraitCoverageTest {

    /**
     * A bare trait implementation used to reach the default {@code execute} method.
     */
    private final GraphQLTrait trait = new GraphQLTrait() {
    };

    /**
     * Covers the happy path: the supplier's result is returned untouched.
     */
    @Test
    void executeReturnsSupplierResult() throws org.eclipse.microprofile.graphql.GraphQLException {
        String result = trait.execute(() -> "ok", GraphQLTraitCoverageTest.class, "read");
        assertEquals("ok", result);
    }

    /**
     * Covers the {@link AlreadyExistsException} arm: it is logged and wrapped in a
     * {@link GraphQLException} preserving its message.
     */
    @Test
    void executeWrapsAlreadyExists() {
        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> trait.execute(() -> {
                    throw new AlreadyExistsException("duplicate code");
                }, GraphQLTraitCoverageTest.class, "create"));
        assertEquals("duplicate code", ex.getMessage());
    }

    /**
     * Covers the {@link PersistenceException} arm: it is logged and wrapped in a
     * {@link GraphQLException} describing a database error.
     */
    @Test
    void executeWrapsPersistenceException() {
        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> trait.execute(() -> {
                    throw new PersistenceException("constraint violation");
                }, GraphQLTraitCoverageTest.class, "update"));
        assertTrue(ex.getMessage().contains("Database error while performing update"));
    }

    /**
     * Covers the {@link NoSuchElementException} arm: it is re-thrown unwrapped.
     */
    @Test
    void executeRethrowsNoSuchElement() {
        NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                () -> trait.execute(() -> {
                    throw new NoSuchElementException("missing");
                }, GraphQLTraitCoverageTest.class, "find"));
        assertEquals("missing", ex.getMessage());
    }

    /**
     * Covers the catch-all arm: any other exception is logged and wrapped in a generic
     * {@link GraphQLException}.
     */
    @Test
    void executeWrapsUnexpectedException() {
        GraphQLException ex = assertThrows(GraphQLException.class,
                () -> trait.execute(() -> {
                    throw new IllegalStateException("boom");
                }, GraphQLTraitCoverageTest.class, "compute"));
        assertTrue(ex.getMessage().contains("An error occurred during compute"));
    }
}
