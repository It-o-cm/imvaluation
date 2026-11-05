package com.intermarche.valuation.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Plain unit test guaranteeing the interface message bundles stay exhaustive and aligned
 * (spec-exploitation §1, §5.1).
 * <p>
 * The French bundle is the reference; the English bundle must be complete. The test loads both
 * from the classpath and asserts their key sets are identical, so a label added to one screen in
 * one language and forgotten in the other fails the build rather than surfacing the raw key at
 * runtime. No value may be blank.
 */
public class MessageBundleExhaustivenessTest {

    /**
     * Loads a bundle's key/value pairs from the classpath.
     *
     * @param language the bundle language.
     * @return the loaded properties.
     * @throws IOException if the bundle cannot be read.
     */
    private Properties load(String language) throws IOException {
        String path = "messages/msg_" + language + ".properties";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "bundle must exist on the classpath: " + path);
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }

    /**
     * Tests that the French and English bundles declare exactly the same keys.
     *
     * @throws IOException if a bundle cannot be read.
     */
    @Test
    void testBundlesShareTheSameKeys() throws IOException {
        Set<String> fr = new TreeSet<>(load("fr").stringPropertyNames());
        Set<String> en = new TreeSet<>(load("en").stringPropertyNames());
        Set<String> missingInEnglish = new TreeSet<>(fr);
        missingInEnglish.removeAll(en);
        Set<String> missingInFrench = new TreeSet<>(en);
        missingInFrench.removeAll(fr);
        assertEquals(Set.of(), missingInEnglish, "keys present in fr but missing in en");
        assertEquals(Set.of(), missingInFrench, "keys present in en but missing in fr");
    }

    /**
     * Tests that no bundle carries a blank value, in either language.
     *
     * @throws IOException if a bundle cannot be read.
     */
    @Test
    void testNoBlankValues() throws IOException {
        for (String language : new String[]{"fr", "en"}) {
            Properties properties = load(language);
            for (String key : properties.stringPropertyNames()) {
                assertFalse(properties.getProperty(key).isBlank(),
                        "blank value for key '" + key + "' in bundle " + language);
            }
        }
    }
}
