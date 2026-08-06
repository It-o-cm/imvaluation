package com.intermarche.valuation.ui;

import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.OfferApplierFactory;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry aggregating the JSON Schemas exposed by every offer and advantage factory.
 * <p>
 * The registry is populated once at startup by scanning all CDI beans implementing
 * {@link OfferApplierFactory} or {@link AdvantageApplierFactory} and collecting the pairs
 * returned by their {@code getOfferType()} / {@code getSchema()} methods.
 * <p>
 * Because the very same schema instances are used by the engine to validate specifications
 * at runtime, the administration UI and the valuation engine can never drift apart.
 */
@ApplicationScoped
public class OfferSchemaRegistry {

    private static final Logger LOGGER = Logger.getLogger(OfferSchemaRegistry.class);

    /**
     * All CDI beans producing offer appliers.
     */
    @Inject
    Instance<OfferApplierFactory> offerFactories;

    /**
     * All CDI beans producing advantage appliers.
     */
    @Inject
    Instance<AdvantageApplierFactory> advantageFactories;

    /**
     * Offer type to JSON Schema, sorted alphabetically for a stable UI ordering.
     */
    private final Map<String, String> schemasByType = new TreeMap<>();

    /**
     * Scans every factory bean and indexes the schemas by offer type.
     * <p>
     * Factories returning a null type or a null schema are skipped, which covers appliers
     * such as the basic per-product offer that have no configurable specification.
     */
    @PostConstruct
    void collectSchemas() {
        for (OfferApplierFactory factory : offerFactories) {
            register(factory.getOfferType(), factory.getSchema());
        }
        for (AdvantageApplierFactory factory : advantageFactories) {
            register(factory.getOfferType(), factory.getSchema());
        }
    }

    /**
     * Registers a single type/schema pair, ignoring incomplete declarations.
     * <p>
     * A type may be claimed by both an offer factory and an advantage factory (N+M,
     * MIXED_BUNDLE). Offer factories are scanned first, so the schema that produces a valid
     * engine offer is registered first; a later duplicate is rejected and logged rather than
     * silently overwriting it, keeping exactly one registration — the offer-valid one — per
     * type.
     *
     * @param type   The offer type discriminator, may be null.
     * @param schema The JSON Schema, may be null.
     */
    private void register(String type, String schema) {
        if (type == null || type.isBlank() || schema == null || schema.isBlank()) {
            return;
        }
        String existing = schemasByType.putIfAbsent(type, schema);
        if (existing != null) {
            LOGGER.warnf("Duplicate schema registration for offer type '%s' ignored; "
                    + "keeping the first (offer-valid) registration.", type);
        }
    }

    /**
     * Returns the JSON Schema declared for the given offer type.
     *
     * @param type The offer type discriminator.
     * @return The JSON Schema as a string, or {@code null} if the type has no schema.
     */
    public String getSchema(String type) {
        if (type == null) {
            return null;
        }
        return schemasByType.get(type);
    }

    /**
     * Indicates whether a schema is available for the given offer type.
     *
     * @param type The offer type discriminator.
     * @return {@code true} if a schema is registered, {@code false} otherwise.
     */
    public boolean hasSchema(String type) {
        return getSchema(type) != null;
    }

    /**
     * Returns every offer type having a registered schema.
     *
     * @return An unmodifiable, alphabetically sorted set of offer types.
     */
    public Set<String> getKnownTypes() {
        return Collections.unmodifiableSet(schemasByType.keySet());
    }

    /**
     * Returns the whole type-to-schema mapping.
     * <p>
     * Used by the UI to embed every schema in a single page, avoiding a round trip
     * when the user switches the offer type in the editor.
     *
     * @return An unmodifiable map of offer type to JSON Schema.
     */
    public Map<String, String> getAllSchemas() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(schemasByType));
    }
}
