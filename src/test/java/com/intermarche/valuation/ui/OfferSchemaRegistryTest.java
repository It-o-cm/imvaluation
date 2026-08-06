package com.intermarche.valuation.ui;

import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.OfferApplierFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OfferSchemaRegistry}.
 * <p>
 * The registry is a plain CDI bean; it is instantiated directly and its two
 * {@link Instance} collaborators are mocked so that {@code collectSchemas()} iterates over a
 * controlled set of factories. Every branch of {@code register}, {@code getSchema} and
 * {@code hasSchema} is exercised, and the defensive copies returned by {@code getKnownTypes}
 * and {@code getAllSchemas} are asserted to be unmodifiable.
 */
class OfferSchemaRegistryTest {

    /**
     * Builds a registry whose factory instances yield the supplied factories, then runs the
     * {@code @PostConstruct} scan so the internal map reflects them.
     *
     * @param offers     The offer applier factories to expose.
     * @param advantages The advantage applier factories to expose.
     * @return A fully populated registry ready for assertions.
     */
    private OfferSchemaRegistry newRegistry(List<OfferApplierFactory> offers,
                                            List<AdvantageApplierFactory> advantages) {
        OfferSchemaRegistry registry = new OfferSchemaRegistry();
        @SuppressWarnings("unchecked")
        Instance<OfferApplierFactory> offerInstance = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<AdvantageApplierFactory> advantageInstance = mock(Instance.class);
        when(offerInstance.iterator()).thenReturn(offers.iterator());
        when(advantageInstance.iterator()).thenReturn(advantages.iterator());
        registry.offerFactories = offerInstance;
        registry.advantageFactories = advantageInstance;
        registry.collectSchemas();
        return registry;
    }

    /**
     * Creates an offer applier factory mock returning the given type and schema.
     *
     * @param type   The offer type to report.
     * @param schema The JSON schema to report.
     * @return A stubbed offer applier factory.
     */
    private OfferApplierFactory offerFactory(String type, String schema) {
        OfferApplierFactory factory = mock(OfferApplierFactory.class);
        lenient().when(factory.getOfferType()).thenReturn(type);
        lenient().when(factory.getSchema()).thenReturn(schema);
        return factory;
    }

    /**
     * Creates an advantage applier factory mock returning the given type and schema.
     *
     * @param type   The offer type to report.
     * @param schema The JSON schema to report.
     * @return A stubbed advantage applier factory.
     */
    private AdvantageApplierFactory advantageFactory(String type, String schema) {
        AdvantageApplierFactory factory = mock(AdvantageApplierFactory.class);
        lenient().when(factory.getOfferType()).thenReturn(type);
        lenient().when(factory.getSchema()).thenReturn(schema);
        return factory;
    }

    /**
     * A valid offer factory is indexed by its type.
     */
    @Test
    void collectSchemasIndexesValidOfferFactory() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.emptyList());
        assertEquals("{d}", registry.getSchema("DELIVERY"));
        assertEquals(Collections.singleton("DELIVERY"), registry.getKnownTypes());
    }

    /**
     * A valid advantage factory is indexed too, proving the second scan loop runs.
     */
    @Test
    void collectSchemasIndexesValidAdvantageFactory() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.emptyList(),
                Collections.singletonList(advantageFactory("MEAL_VOUCHER", "{m}")));
        assertEquals("{m}", registry.getSchema("MEAL_VOUCHER"));
        assertEquals(Collections.singleton("MEAL_VOUCHER"), registry.getKnownTypes());
    }

    /**
     * A factory declaring a null type is skipped (first arm of the guard).
     */
    @Test
    void collectSchemasSkipsNullType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory(null, "{s}")),
                Collections.emptyList());
        assertTrue(registry.getKnownTypes().isEmpty());
    }

    /**
     * A factory declaring a blank type is skipped (second arm of the guard).
     */
    @Test
    void collectSchemasSkipsBlankType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("   ", "{s}")),
                Collections.emptyList());
        assertTrue(registry.getKnownTypes().isEmpty());
    }

    /**
     * A factory declaring a null schema is skipped (third arm of the guard).
     */
    @Test
    void collectSchemasSkipsNullSchema() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("TYPE", null)),
                Collections.emptyList());
        assertTrue(registry.getKnownTypes().isEmpty());
    }

    /**
     * A factory declaring a blank schema is skipped (fourth arm of the guard).
     */
    @Test
    void collectSchemasSkipsBlankSchema() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("TYPE", "  ")),
                Collections.emptyList());
        assertTrue(registry.getKnownTypes().isEmpty());
    }

    /**
     * Offer and advantage schemas coexist and are sorted alphabetically by type.
     */
    @Test
    void collectSchemasMergesAndSortsBothSources() {
        OfferSchemaRegistry registry = newRegistry(
                Arrays.asList(offerFactory("ZOFFER", "{z}"), offerFactory("MOFFER", "{m}")),
                Collections.singletonList(advantageFactory("AADV", "{a}")));
        assertEquals(Arrays.asList("AADV", "MOFFER", "ZOFFER"),
                registry.getKnownTypes().stream().toList());
    }

    /**
     * A type claimed by both an offer factory and an advantage factory (N+M, MIXED_BUNDLE)
     * keeps exactly one registration: the offer scan runs first, so the offer-valid schema
     * wins and the advantage duplicate is rejected rather than overwriting it.
     */
    @Test
    void collectSchemasKeepsFirstRegistrationOnDuplicateType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("N+M", "{offer}")),
                Collections.singletonList(advantageFactory("N+M", "{advantage}")));
        assertEquals("{offer}", registry.getSchema("N+M"));
        assertEquals(1, registry.getKnownTypes().size());
    }

    /**
     * {@code getSchema} returns null for a null type (true arm of the null guard).
     */
    @Test
    void getSchemaReturnsNullForNullType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.emptyList());
        assertNull(registry.getSchema(null));
    }

    /**
     * {@code getSchema} returns null for an unregistered type (false arm, missing key).
     */
    @Test
    void getSchemaReturnsNullForUnknownType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.emptyList());
        assertNull(registry.getSchema("UNKNOWN"));
    }

    /**
     * {@code hasSchema} is true when the type is registered.
     */
    @Test
    void hasSchemaTrueForKnownType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.emptyList());
        assertTrue(registry.hasSchema("DELIVERY"));
    }

    /**
     * {@code hasSchema} is false when the type is unregistered.
     */
    @Test
    void hasSchemaFalseForUnknownType() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.emptyList());
        assertFalse(registry.hasSchema("UNKNOWN"));
    }

    /**
     * {@code getKnownTypes} exposes the types but forbids mutation.
     */
    @Test
    void getKnownTypesIsUnmodifiable() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.emptyList());
        Set<String> types = registry.getKnownTypes();
        assertEquals(Collections.singleton("DELIVERY"), types);
        assertThrows(UnsupportedOperationException.class, () -> types.add("X"));
    }

    /**
     * {@code getAllSchemas} returns the full mapping but forbids mutation.
     */
    @Test
    void getAllSchemasIsUnmodifiable() {
        OfferSchemaRegistry registry = newRegistry(
                Collections.singletonList(offerFactory("DELIVERY", "{d}")),
                Collections.singletonList(advantageFactory("MEAL_VOUCHER", "{m}")));
        Map<String, String> all = registry.getAllSchemas();
        assertEquals(2, all.size());
        assertEquals("{d}", all.get("DELIVERY"));
        assertEquals("{m}", all.get("MEAL_VOUCHER"));
        assertThrows(UnsupportedOperationException.class, () -> all.put("X", "{x}"));
    }

    /**
     * With no factories at all the registry stays empty, covering the loop-not-entered arm.
     */
    @Test
    void collectSchemasHandlesNoFactories() {
        OfferSchemaRegistry registry = newRegistry(Collections.emptyList(), Collections.emptyList());
        assertTrue(registry.getKnownTypes().isEmpty());
        assertTrue(registry.getAllSchemas().isEmpty());
    }

    /**
     * An empty iterator constant kept referenced to document the loop-not-entered case.
     */
    @Test
    void emptyIteratorHasNoNext() {
        Iterator<OfferApplierFactory> iterator = Collections.<OfferApplierFactory>emptyList().iterator();
        assertFalse(iterator.hasNext());
    }
}
