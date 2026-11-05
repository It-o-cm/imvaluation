package com.intermarche.valuation.ai;

import com.intermarche.valuation.ui.OfferSchemaRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the exhaustiveness of {@code offer-type-descriptions.properties} against the real
 * {@link OfferSchemaRegistry}, under the full container so the factory scan is the live one.
 * <p>
 * The behavioural descriptions are the half of the drafting prompt the JSON Schemas cannot
 * carry; a type registered without a description would silently degrade the assistant's
 * type choice, and a description naming a type that no factory registers would be dead
 * text. Both drifts fail here, in both directions:
 * <ul>
 *   <li>every registered type carries a non-blank description;</li>
 *   <li>every described type is registered.</li>
 * </ul>
 */
@QuarkusTest
class OfferTypeDescriptionsTest {

    /**
     * The live registry, populated by the factory scan.
     */
    @Inject
    OfferSchemaRegistry registry;

    /**
     * Every registered type has a description, and none of them is blank.
     */
    @Test
    void everyRegisteredTypeIsDescribed() {
        Set<String> missing = new TreeSet<>();
        for (String type : registry.getKnownTypes()) {
            if (OfferTypeDescriptions.description(type).isBlank()) {
                missing.add(type);
            }
        }
        assertTrue(missing.isEmpty(),
                "Types without a behavioural description in " + OfferTypeDescriptions.RESOURCE
                        + ": " + missing);
    }

    /**
     * No description names a type the engine does not register: dead text fails too.
     */
    @Test
    void everyDescribedTypeIsRegistered() {
        Set<String> orphans = new TreeSet<>(OfferTypeDescriptions.describedTypes());
        orphans.removeAll(registry.getKnownTypes());
        assertTrue(orphans.isEmpty(),
                "Descriptions for unknown offer types in " + OfferTypeDescriptions.RESOURCE
                        + ": " + orphans);
    }

    /**
     * The catalogue is not empty: the file really loaded and the registry really scanned.
     */
    @Test
    void theCatalogueCarriesTypes() {
        assertFalse(registry.getKnownTypes().isEmpty());
        assertFalse(OfferTypeDescriptions.describedTypes().isEmpty());
        assertFalse(OfferTypeDescriptions.description("TIERED_DISCOUNT").isBlank());
    }

    /**
     * The registry stores the enriched schemas — the very ones served to the administration
     * form and to the AI drafting prompt — so the prompt the model reads carries the
     * free-form targeting field (spec-ciblage-where §3): a dictated condition can be drafted.
     */
    @Test
    void theRegistryServesEnrichedSchemasCarryingTargetWhere() {
        for (String type : registry.getKnownTypes()) {
            String schema = registry.getSchema(type);
            if (schema.contains("targetEans")) {
                assertTrue(schema.contains("targetWhere"),
                        "The schema of " + type + " must carry the injected targetWhere.");
                assertTrue(schema.contains("targetFamilyCodes"),
                        "The schema of " + type + " must carry the injected targetFamilyCodes.");
                return;
            }
        }
        throw new AssertionError("No EAN-targeting type found in the registry.");
    }
}
