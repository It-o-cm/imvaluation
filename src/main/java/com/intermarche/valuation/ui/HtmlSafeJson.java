package com.intermarche.valuation.ui;

/**
 * Makes a JSON string safe to embed verbatim inside a {@code <script>} element (report C3).
 * <p>
 * A JSON document emitted straight into {@code <script type="application/json">...</script>} can
 * break out of the element if any string value contains {@code </script>}: Jackson escapes quotes
 * and backslashes but leaves {@code <} and {@code /} untouched, and a raw database string is not
 * escaped at all. Replacing {@code <}, {@code >} and {@code /} with their {@code \\uXXXX} JSON
 * escapes keeps the document valid JSON while making the {@code </script>} sequence -- and any
 * HTML tag -- impossible to form. The Unicode line separators U+2028/U+2029, which are valid in
 * JSON but break a script, are escaped too.
 */
public final class HtmlSafeJson {

    /**
     * Not instantiable.
     */
    private HtmlSafeJson() {
    }

    /**
     * Escapes a JSON string for safe inclusion inside a {@code <script>} block.
     *
     * @param json the JSON string, may be null.
     * @return the escaped JSON, or {@code "{}"} when the input is null so the script block always
     *         holds a valid JSON value.
     */
    public static String forScript(String json) {
        if (json == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '<') {
                sb.append("\\u003c");
            } else if (c == '>') {
                sb.append("\\u003e");
            } else if (c == '/') {
                sb.append("\\u002f");
            } else if (c == 0x2028) {
                sb.append("\\u2028");
            } else if (c == 0x2029) {
                sb.append("\\u2029");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
