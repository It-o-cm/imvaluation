package com.intermarche.valuation.ui;

import com.intermarche.valuation.engine.offers.FreeDeliveryThresholdDiscountFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link OfferSchemaRegistry}, exercised through the real CDI bean so the
 * container runs its {@code @PostConstruct} scan against the actual factory beans and
 * quarkus-jacoco attributes the coverage.
 * <p>
 * The registry keys are a {@link Set}/{@link Map} sorted by a {@code TreeMap}, so every
 * assertion goes through {@code contains}/{@code get} rather than any positional access; the
 * {@code FREE_DELIVERY_THRESHOLD} factory is used as the known type because it declares a
 * non-blank schema.
 */
@QuarkusTest
public class OfferSchemaRegistryCoverageTest {

    /**
     * A known offer type guaranteed to carry a registered schema.
     */
    private static final String KNOWN_TYPE = FreeDeliveryThresholdDiscountFactory.OFFER_TYPE;

    /**
     * The registry under test, injected from the container.
     */
    @Inject
    OfferSchemaRegistry registry;

    /**
     * Tests that a known type resolves to its factory's non-blank schema, covering the
     * non-null arm of getSchema and the true arm of hasSchema.
     */
    @Test
    void testGetSchema_knownTypeReturnsSchema() {
        String schema = registry.getSchema(KNOWN_TYPE);
        assertNotNull(schema);
        assertFalse(schema.isBlank());
        assertTrue(registry.hasSchema(KNOWN_TYPE));
    }

    /**
     * Tests that an unregistered type resolves to null, covering the map-miss arm of getSchema
     * and the false arm of hasSchema.
     */
    @Test
    void testGetSchema_unknownTypeReturnsNull() {
        assertNull(registry.getSchema("NO_SUCH_TYPE"));
        assertFalse(registry.hasSchema("NO_SUCH_TYPE"));
    }

    /**
     * Tests that a null type resolves to null without touching the map, covering the null arm
     * of the getSchema guard and the false arm of hasSchema.
     */
    @Test
    void testGetSchema_nullTypeReturnsNull() {
        assertNull(registry.getSchema(null));
        assertFalse(registry.hasSchema(null));
    }

    /**
     * Tests that the known-types set contains the known type, is non-empty and rejects
     * mutation, asserting membership rather than any iteration order.
     */
    @Test
    void testGetKnownTypes_containsKnownTypeAndIsUnmodifiable() {
        Set<String> types = registry.getKnownTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.contains(KNOWN_TYPE));
        assertThrows(UnsupportedOperationException.class, () -> types.add("X"));
    }

    /**
     * Tests that the full schema map maps the known type to the same schema getSchema returns,
     * carries the same key set as getKnownTypes and rejects mutation, asserting by key lookup
     * rather than by order.
     */
    @Test
    void testGetAllSchemas_mapsKnownTypeAndIsUnmodifiable() {
        Map<String, String> all = registry.getAllSchemas();
        assertTrue(all.containsKey(KNOWN_TYPE));
        assertEquals(registry.getSchema(KNOWN_TYPE), all.get(KNOWN_TYPE));
        assertEquals(registry.getKnownTypes().size(), all.size());
        assertThrows(UnsupportedOperationException.class, () -> all.put("X", "Y"));
    }
}
