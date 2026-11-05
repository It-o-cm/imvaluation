package com.intermarche.valuation.ai;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The behavioural summary of every offer and advantage type, distilled from the factories'
 * documentation into {@code offer-type-descriptions.properties}.
 * <p>
 * The JSON Schemas sent to the drafting model say <em>how to fill</em> a specification; they
 * do not say <em>what the type does</em> — that a {@code TIERED_DISCOUNT} applies the highest
 * reached tier once, that an instrument grant never touches the ticket total, that the
 * Egalim guard is a counter-advantage. Those sentences live here, one entry per registered
 * type, and the drafting prompt carries each description next to its schema so the model
 * chooses a type on behaviour, not on field names.
 * <p>
 * The file is loaded once from the classpath, UTF-8, exactly like the message bundles.
 * {@link OfferTypeDescriptionsTest} pins the exhaustiveness contract: every type the
 * {@code OfferSchemaRegistry} exposes has an entry here, and no entry names an unknown
 * type — a new factory without a description fails the CI instead of silently degrading
 * the prompt. A missing description at runtime degrades to the bare schema, logged.
 */
public final class OfferTypeDescriptions {

    /**
     * Classpath location of the descriptions file.
     */
    static final String RESOURCE = "/offer-type-descriptions.properties";

    private static final Logger LOGGER = Logger.getLogger(OfferTypeDescriptions.class);

    /**
     * The descriptions keyed by offer type, loaded once.
     */
    private static final Map<String, String> DESCRIPTIONS = load();

    /**
     * This class is a namespace; no instances.
     */
    private OfferTypeDescriptions() {
    }

    /**
     * Returns the behavioural description of an offer type.
     *
     * @param type The offer type discriminator, may be null.
     * @return The description, or the empty string when the type has none — never null.
     */
    public static String description(String type) {
        if (type == null) {
            return "";
        }
        String description = DESCRIPTIONS.get(type);
        if (description == null) {
            LOGGER.warnf("No behavioural description for offer type '%s'; the drafting prompt "
                    + "falls back to the bare schema.", type);
            return "";
        }
        return description;
    }

    /**
     * Returns every type carrying a description.
     *
     * @return An unmodifiable set of offer types.
     */
    public static java.util.Set<String> describedTypes() {
        return java.util.Collections.unmodifiableSet(DESCRIPTIONS.keySet());
    }

    /**
     * Loads the descriptions from the classpath.
     *
     * @return The type-to-description map, empty when the resource is missing.
     */
    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = OfferTypeDescriptions.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String name : properties.stringPropertyNames()) {
                    map.put(name, properties.getProperty(name));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + RESOURCE, e);
        }
        return map;
    }
}
