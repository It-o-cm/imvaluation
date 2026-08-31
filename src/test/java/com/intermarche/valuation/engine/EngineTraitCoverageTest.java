package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code @QuarkusTest} coverage of {@link EngineTrait} default methods.
 * <p>
 * The trait is exercised through an anonymous implementation so its default methods run
 * directly. A {@code @QuarkusTest} is used only so quarkus-jacoco attributes the executed
 * lines; no database is involved (the collaborator is a Mockito mock).
 */
@QuarkusTest
public class EngineTraitCoverageTest {

    /**
     * A bare trait implementation carrying no behaviour of its own.
     */
    private final EngineTrait trait = new EngineTrait() {
    };

    /**
     * Covers the null-basket guard of {@code getBasket}: a null basket raises an
     * {@link IllegalStateException} carrying the supplied message.
     */
    @Test
    void getBasketThrowsWhenBasketIsNull() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getBasket()).thenReturn(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> trait.getBasket(evaluation, "no basket here"));
        assertEquals("no basket here", ex.getMessage());
    }

    /**
     * Covers the non-null arm of {@code getBasket}: the basket is returned unchanged.
     */
    @Test
    void getBasketReturnsBasketWhenPresent() {
        Basket basket = new Basket();
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getBasket()).thenReturn(basket);
        assertSame(basket, trait.getBasket(evaluation, "unused"));
    }

    /**
     * Covers the success arm of {@code processSpecification}: valid JSON matching the schema is
     * handed to the consumer.
     */
    @Test
    void processSpecificationInvokesConsumerOnValidJson() {
        String schema = "{\"type\":\"object\",\"required\":[\"x\"],"
                + "\"properties\":{\"x\":{\"type\":\"string\"}}}";
        AtomicBoolean invoked = new AtomicBoolean(false);
        trait.processSpecification(schema, "{\"x\":\"y\"}", (JsonNode node) -> invoked.set(true));
        assertTrue(invoked.get());
    }

    /**
     * Covers the validation-failure arm of {@code processSpecification}: JSON that violates the
     * schema raises an {@link IllegalArgumentException} prefixed with "Error validating offer".
     */
    @Test
    void processSpecificationThrowsOnSchemaViolation() {
        String schema = "{\"type\":\"object\",\"required\":[\"x\"],"
                + "\"properties\":{\"x\":{\"type\":\"string\"}}}";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> trait.processSpecification(schema, "{}", node -> {
                }));
        assertTrue(ex.getMessage().contains("Error validating offer"));
    }

    /**
     * Covers the parse-failure arm of {@code processSpecification}: a malformed JSON payload
     * raises an {@link IllegalArgumentException} reading "Error parsing offer.".
     */
    @Test
    void processSpecificationThrowsOnMalformedJson() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> trait.processSpecification("{}", "{ this is not json", node -> {
                }));
        assertTrue(ex.getMessage().contains("Error parsing offer"));
    }
}
