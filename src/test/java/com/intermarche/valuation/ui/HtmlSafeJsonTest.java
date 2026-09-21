package com.intermarche.valuation.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link HtmlSafeJson}, the {@code <script>}-safe JSON escaper (report C3).
 */
public class HtmlSafeJsonTest {

    /**
     * A {@code </script>} payload cannot survive the escaping: the slashes and angle brackets are
     * replaced by their JSON unicode escapes, so no HTML tag can be formed.
     */
    @Test
    void escapesScriptBreakout() {
        String malicious = "{\"name\":\"</script><script>alert(1)</script>\"}";
        String escaped = HtmlSafeJson.forScript(malicious);
        assertFalse(escaped.contains("</script>"), "the </script> sequence must not survive");
        assertFalse(escaped.contains("<"), "no raw < must remain");
        assertFalse(escaped.contains(">"), "no raw > must remain");
        assertFalse(escaped.contains("/"), "no raw / must remain");
        assertEquals("{\"name\":\"\\u003c\\u002fscript\\u003e\\u003cscript\\u003ealert(1)"
                + "\\u003c\\u002fscript\\u003e\"}", escaped);
    }

    /**
     * A plain JSON document without any dangerous character is returned unchanged.
     */
    @Test
    void leavesPlainJsonUnchanged() {
        assertEquals("{\"a\":1,\"b\":\"x\"}", HtmlSafeJson.forScript("{\"a\":1,\"b\":\"x\"}"));
    }

    /**
     * A null input yields an empty JSON object, so the script block always holds valid JSON.
     */
    @Test
    void nullBecomesEmptyObject() {
        assertEquals("{}", HtmlSafeJson.forScript(null));
    }
}
