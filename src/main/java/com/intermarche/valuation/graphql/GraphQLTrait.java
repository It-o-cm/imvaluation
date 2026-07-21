package com.intermarche.valuation.graphql;

import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.jboss.logging.Logger;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Trait interface providing centralized error handling logic.
 * <p>
 * Any class implementing this interface automatically inherits the {@code execute} method.
 */
public interface GraphQLTrait {

    /**
     * Default method to execute an action with standard error handling.
     * <p>
     * It handles:
     * <ul>
     *   <li>{@link AlreadyExistsException}: Logged as a WARN and wrapped in GraphQLException.</li>
     *   <li>{@link PersistenceException}: Logged as an ERROR and wrapped in GraphQLException.</li>
     *   <li>{@link NoSuchElementException}: Re-thrown without wrapping (handled directly by framework).</li>
     *   <li>{@link Exception}: Logged as ERROR and wrapped in GraphQLException (catch-all).</li>
     * </ul>
     *
     * @param supplier     The business logic to execute (usually a lambda).
     * @param contextClass The class calling the trait (used for creating a logger).
     * @param operationName The name of the operation (used for logging and error messages).
     * @return The result of the action.
     * @throws GraphQLException     if a database or unexpected error occurs.
     * @throws NoSuchElementException if business logic explicitly throws this (e.g., not found).
     */
    default <T> T execute(Supplier<T> supplier, Class<?> contextClass, String operationName) throws GraphQLException {
        // Create a logger specific to the class calling this utility
        Logger logger = Logger.getLogger(contextClass);
        try {
            // Execute the provided business logic (Lambda)
            return supplier.get();
        } catch (AlreadyExistsException aee) {
            // Handle specific "Already Exists" business errors (e.g., duplicate code or name)
            // We log as WARN because it's a data consistency issue, not a system failure
            logger.warn("Conflict in " + operationName + ": " + aee.getMessage());
            // Convert to GraphQLException so that API returns a clean JSON error message
            throw new GraphQLException(aee.getMessage(), aee);
        } catch (PersistenceException pe) {
            // Handle generic JPA/Hibernate/Database errors (Constraint violations, connection issues, etc.)
            logger.error("Persistence error in " + operationName, pe);
            throw new GraphQLException("Database error while performing " + operationName + ". Please check your data.", pe);
        } catch (NoSuchElementException nsee) {
            // Re-throw "Not Found" exceptions without wrapping
            // so that GraphQL API returns a clean specific error (404-like behavior)
            throw nsee;
        } catch (Exception e) {
            // Handle any other unexpected errors (including H2 raw exceptions or runtime issues)
            logger.error("Unexpected error in " + operationName, e);
            throw new GraphQLException("An error occurred during " + operationName + ".", e);
        }
    }
}