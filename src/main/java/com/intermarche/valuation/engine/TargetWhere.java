package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.EgalimRegime;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed grammar of the free-form product targeting (spec-ciblage-where §2).
 * <p>
 * An offer may replace its enumerated targeting ({@code targetEans}/{@code targetFamilyCodes})
 * with a single condition text — {@code brand = 'DANONE' and not name like 'bio%'} — written by
 * the administrator and stored verbatim in the specification. Nothing of it is ever executed as
 * text: the fragment is parsed here into an abstract tree whose only reachable fields and
 * operators are the whitelisted ones, and only that tree is compiled, into a HQL fragment
 * carrying whitelisted identifiers and bind markers. A fragment that parses is by construction
 * unable to name another entity, to sub-query, to join, or to smuggle a value into the query
 * text — the interdicts are unexpressible, not detected.
 * <p>
 * The validation funnel is the usual one: {@code processSpecification} parses the fragment at
 * offer creation, so an invalid condition is refused at the door; at valuation time,
 * {@link EngineTrait#resolveTargetEans} resolves a valid fragment into the concrete EAN plate
 * through one parameterized query, exactly as it resolves family codes.
 */
public final class TargetWhere {

    /**
     * The abstract tree of a parsed condition.
     */
    private sealed interface Node permits Comparison, Conjunction, Disjunction, Negation {
    }

    /**
     * A single {@code field operator value(s)} comparison.
     */
    private record Comparison(Field field, Operator operator, List<Object> values) implements Node {
    }

    /**
     * The conjunction of two sub-conditions.
     */
    private record Conjunction(Node left, Node right) implements Node {
    }

    /**
     * The disjunction of two sub-conditions.
     */
    private record Disjunction(Node left, Node right) implements Node {
    }

    /**
     * The negation of a sub-condition.
     */
    private record Negation(Node inner) implements Node {
    }

    /**
     * The kind of value a field accepts.
     */
    private enum Kind {
        TEXT, NUMBER, PRODUCT_TYPE, EGALIM_REGIME
    }

    /**
     * The whitelisted product attributes a condition may name. The HQL path is fixed per field:
     * it is the only path the compiled query can ever carry.
     */
    private enum Field {
        EAN("ean", "p.ean", Kind.TEXT),
        NAME("name", "p.name", Kind.TEXT),
        BRAND("brand", "p.brand", Kind.TEXT),
        PRODUCT_TYPE("productType", "p.productType", Kind.PRODUCT_TYPE),
        EGALIM_REGIME("egalimRegime", "p.egalimRegime", Kind.EGALIM_REGIME),
        REFERENCE_WEIGHT("referenceWeight", "p.referenceWeight", Kind.NUMBER),
        REFERENCE_VOLUME("referenceVolume", "p.referenceVolume", Kind.NUMBER);

        /**
         * The name the administrator writes.
         */
        final String label;

        /**
         * The HQL path the compiled fragment carries, fixed by this whitelist.
         */
        final String path;

        /**
         * The kind of value this field accepts.
         */
        final Kind kind;

        Field(String label, String path, Kind kind) {
            this.label = label;
            this.path = path;
            this.kind = kind;
        }
    }

    /**
     * The whitelisted comparison operators.
     */
    private enum Operator {
        EQUALS, NOT_EQUALS, LESS, LESS_OR_EQUALS, GREATER, GREATER_OR_EQUALS, LIKE, IN
    }

    /**
     * The field whitelist keyed by the written name lower-cased — field names are
     * case-insensitive — in a stable order for error messages.
     */
    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    static {
        for (Field field : Field.values()) {
            FIELDS.put(field.label.toLowerCase(Locale.ROOT), field);
        }
    }

    /**
     * The token pattern of the grammar: operators, quoted strings with '' escapes, numbers,
     * identifiers, and punctuation. Whitespace separates tokens; anything unmatched is a
     * syntax error carrying its position.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(<>|!=|<=|>=|=|<|>|\\(|\\)|,|'(?:[^']|'')*'|-?\\d+(?:\\.\\d+)?|[A-Za-z_][A-Za-z_0-9]*)");

    /**
     * The cursor over the fragment: the tokens, their positions, and the read index. A mutable
     * class, because the parser consumes tokens as it descends.
     */
    private static final class Cursor {
        /**
         * The original text, for error positions.
         */
        private final String fragment;

        /**
         * The lexed tokens, in order.
         */
        private final List<String> tokens;

        /**
         * The character position of each token, in the same order.
         */
        private final List<Integer> positions;

        /**
         * The index of the current token.
         */
        private int index;

        /**
         * Builds a cursor positioned on the first token.
         *
         * @param fragment  The original text.
         * @param tokens    The lexed tokens.
         * @param positions The character position of each token.
         */
        Cursor(String fragment, List<String> tokens, List<Integer> positions) {
            this.fragment = fragment;
            this.tokens = tokens;
            this.positions = positions;
        }

        /**
         * Indicates whether the whole fragment has been consumed.
         *
         * @return {@code true} when no token remains.
         */
        boolean done() {
            return index >= tokens.size();
        }

        /**
         * Returns the current token without consuming it.
         *
         * @return The current token, or the empty string at the end.
         */
        String peek() {
            return done() ? "" : tokens.get(index);
        }

        /**
         * Returns the position of the current token.
         *
         * @return The character offset of the current token.
         */
        int position() {
            return done() ? fragment.length() : positions.get(index);
        }

        /**
         * Consumes and returns the current token.
         *
         * @return The consumed token.
         */
        String next() {
            String token = peek();
            index += 1;
            return token;
        }

        /**
         * Consumes the current token if it equals the expected one, case-insensitively.
         *
         * @param expected The expected token.
         * @return {@code true} when the token matched and was consumed.
         */
        boolean accept(String expected) {
            if (peek().equalsIgnoreCase(expected)) {
                index += 1;
                return true;
            }
            return false;
        }
    }

    private TargetWhere() {
    }

    /**
     * Parses a fragment into its validated abstract tree.
     *
     * @param fragment The condition text, may be null.
     * @return The parsed tree, never null.
     * @throws IllegalArgumentException when the fragment is malformed, names an unknown field,
     *                                  uses an operator foreign to the field's kind, or carries a
     *                                  value of the wrong type — always with the offending
     *                                  position.
     */
    public static Node parse(String fragment) {
        Cursor cursor = lex(fragment);
        if (cursor.done()) {
            throw new IllegalArgumentException("The targeting condition is empty.");
        }
        Node node = parseDisjunction(cursor);
        if (!cursor.done()) {
            throw new IllegalArgumentException("Unexpected '" + cursor.peek() + "' at position "
                    + cursor.position() + " in the targeting condition.");
        }
        return node;
    }

    /**
     * Resolves a fragment into the concrete EAN plate, through one parameterized query whose text
     * carries only whitelisted identifiers and bind markers.
     *
     * @param fragment The condition text, already validated at offer creation.
     * @return The EANs of the matching products, in a stable order.
     * @throws IllegalArgumentException when the fragment is malformed — the configuration is then
     *                                  skipped and traced through the engine's usual channel.
     */
    public static Set<String> eansOf(String fragment) {
        List<Object> parameters = new ArrayList<>();
        String hql = "select p from Product p where " + compile(parse(fragment), parameters);
        List<Product> products = Product.list(hql, parameters.toArray());
        Set<String> eans = new LinkedHashSet<>();
        for (Product product : products) {
            if (product.ean != null) {
                eans.add(product.ean);
            }
        }
        return eans;
    }

    /**
     * Counts the products a fragment covers, through one parameterized projection query —
     * the coverage preview of the administration form (spec-ciblage-where §5): the count is
     * exact however wide the condition, the matching products themselves are not loaded.
     *
     * @param fragment The condition text, may be malformed.
     * @return The number of matching products.
     * @throws IllegalArgumentException when the fragment is malformed.
     */
    public static long countOf(String fragment) {
        List<Object> parameters = new ArrayList<>();
        String hql = "select count(p) from Product p where " + compile(parse(fragment), parameters);
        return Product.count(hql, parameters.toArray());
    }

    /**
     * Samples the products a fragment covers, capped, ordered by EAN for a stable preview —
     * the products the administration form shows as chips next to the coverage count.
     *
     * @param fragment  The condition text, may be malformed.
     * @param limit     The maximum number of products returned, at least 1.
     * @return The first matching products, at most {@code limit}.
     * @throws IllegalArgumentException when the fragment is malformed.
     */
    public static List<Product> sampleOf(String fragment, int limit) {
        List<Object> parameters = new ArrayList<>();
        String hql = "select p from Product p where " + compile(parse(fragment), parameters)
                + " order by p.ean";
        return Product.find(hql, parameters.toArray()).range(0, Math.max(0, limit - 1)).list();
    }

    // --------------------------------------------------
    // Lexing
    // --------------------------------------------------

    /**
     * Splits the fragment into tokens.
     *
     * @param fragment The condition text, may be null.
     * @return The cursor over the tokens.
     * @throws IllegalArgumentException on an unterminated string or an unlexable character.
     */
    private static Cursor lex(String fragment) {
        String text = fragment == null ? "" : fragment;
        List<String> tokens = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        int index = 0;
        while (index < text.length()) {
            if (Character.isWhitespace(text.charAt(index))) {
                index += 1;
                continue;
            }
            matcher.region(index, text.length());
            if (!matcher.find() || matcher.start() != index) {
                // A quote that fails to open a string token is an unterminated string;
                // anything else is simply a character the grammar does not know.
                if (text.charAt(index) == '\'') {
                    throw new IllegalArgumentException("Unterminated string at position " + index
                            + " in the targeting condition.");
                }
                throw new IllegalArgumentException("Unexpected character '" + text.charAt(index)
                        + "' at position " + index + " in the targeting condition.");
            }
            String token = matcher.group(1);
            tokens.add(token);
            positions.add(index);
            index = matcher.end(1);
        }
        return new Cursor(text, tokens, positions);
    }

    // --------------------------------------------------
    // Parsing
    // --------------------------------------------------

    /**
     * Parses the disjunction level: {@code a or b or c}.
     */
    private static Node parseDisjunction(Cursor cursor) {
        Node left = parseConjunction(cursor);
        while (cursor.accept("or")) {
            Node right = parseConjunction(cursor);
            left = new Disjunction(left, right);
        }
        return left;
    }

    /**
     * Parses the conjunction level: {@code a and b}.
     */
    private static Node parseConjunction(Cursor cursor) {
        Node left = parseNegation(cursor);
        while (cursor.accept("and")) {
            Node right = parseNegation(cursor);
            left = new Conjunction(left, right);
        }
        return left;
    }

    /**
     * Parses the negation level: {@code not a}, a parenthesised sub-expression, or a comparison.
     */
    private static Node parseNegation(Cursor cursor) {
        if (cursor.accept("not")) {
            return new Negation(parseNegation(cursor));
        }
        if (cursor.accept("(")) {
            Node inner = parseDisjunction(cursor);
            if (!cursor.accept(")")) {
                throw new IllegalArgumentException("Missing ')' at position " + cursor.position()
                        + " in the targeting condition.");
            }
            return inner;
        }
        return parseComparison(cursor);
    }

    /**
     * Parses a single comparison: {@code field operator value(s)}, enforcing the operator and the
     * value kinds the field's whitelist entry declares.
     */
    private static Node parseComparison(Cursor cursor) {
        String written = cursor.peek();
        Field field = FIELDS.get(written.toLowerCase(Locale.ROOT));
        if (field == null) {
            // The refusal lists the whitelisted names as the administrator writes them.
            StringBuilder allowed = new StringBuilder();
            for (Field candidate : Field.values()) {
                if (allowed.length() > 0) {
                    allowed.append(", ");
                }
                allowed.append(candidate.label);
            }
            throw new IllegalArgumentException("Unknown field '" + written + "' at position "
                    + cursor.position() + " in the targeting condition. Allowed fields: "
                    + allowed + ".");
        }
        cursor.next();
        Operator operator = parseOperator(cursor, field);
        List<Object> values = parseValues(cursor, field, operator);
        return new Comparison(field, operator, values);
    }

    /**
     * Parses the comparison operator, refusing the ones the field's kind cannot carry. The
     * reported position is always the one of the offending token, before it is consumed.
     */
    private static Operator parseOperator(Cursor cursor, Field field) {
        int position = cursor.position();
        if (cursor.accept("like")) {
            requireText(field, position);
            return Operator.LIKE;
        }
        if (cursor.accept("in")) {
            return Operator.IN;
        }
        String token = cursor.peek();
        switch (token) {
            case "=" -> {
                cursor.next();
                return Operator.EQUALS;
            }
            case "!=", "<>" -> {
                cursor.next();
                return Operator.NOT_EQUALS;
            }
            case "<" -> {
                cursor.next();
                return Operator.LESS;
            }
            case "<=" -> {
                cursor.next();
                return Operator.LESS_OR_EQUALS;
            }
            case ">" -> {
                cursor.next();
                return Operator.GREATER;
            }
            case ">=" -> {
                cursor.next();
                return Operator.GREATER_OR_EQUALS;
            }
            default -> throw new IllegalArgumentException("Expected a comparison operator after '"
                    + field.label + "' but found '" + token + "' at position " + cursor.position()
                    + " in the targeting condition.");
        }
    }

    /**
     * Parses the value(s) of a comparison: one value, or the parenthesised list of an {@code in}.
     */
    private static List<Object> parseValues(Cursor cursor, Field field, Operator operator) {
        List<Object> values = new ArrayList<>();
        if (operator == Operator.IN) {
            if (!cursor.accept("(")) {
                throw new IllegalArgumentException("Expected '(' after 'in' at position "
                        + cursor.position() + " in the targeting condition.");
            }
            int position = cursor.position();
            if (cursor.accept(")")) {
                throw new IllegalArgumentException("An 'in' condition cannot be empty, at position "
                        + position + " in the targeting condition.");
            }
            values.add(parseValue(cursor, field));
            while (cursor.accept(",")) {
                values.add(parseValue(cursor, field));
            }
            if (!cursor.accept(")")) {
                throw new IllegalArgumentException("Missing ')' at position " + cursor.position()
                        + " in the targeting condition.");
            }
            return values;
        }
        values.add(parseValue(cursor, field));
        return values;
    }

    /**
     * Parses one value, typed by the field's kind.
     */
    private static Object parseValue(Cursor cursor, Field field) {
        if (cursor.peek().isEmpty()) {
            throw new IllegalArgumentException("Expected a value after '" + field.label
                    + "' but the condition ends, at position " + cursor.position()
                    + " in the targeting condition.");
        }
        return switch (field.kind) {
            case TEXT -> textValue(cursor, field);
            case NUMBER -> numberValue(cursor, field);
            case PRODUCT_TYPE -> enumValue(cursor, field, ProductType.class);
            case EGALIM_REGIME -> enumValue(cursor, field, EgalimRegime.class);
        };
    }

    /**
     * Parses a quoted string value, resolving the {@code ''} escapes.
     */
    private static String textValue(Cursor cursor, Field field) {
        String token = cursor.peek();
        if (!token.startsWith("'")) {
            throw new IllegalArgumentException("Expected a quoted string for '" + field.label
                    + "' but found '" + token + "' at position " + cursor.position()
                    + " in the targeting condition.");
        }
        cursor.next();
        return token.substring(1, token.length() - 1).replace("''", "'");
    }

    /**
     * Parses a numeric value.
     */
    private static BigDecimal numberValue(Cursor cursor, Field field) {
        String token = cursor.peek();
        if (!isNumber(token)) {
            throw new IllegalArgumentException("Expected a number for '" + field.label
                    + "' but found '" + token + "' at position " + cursor.position()
                    + " in the targeting condition.");
        }
        cursor.next();
        return new BigDecimal(token);
    }

    /**
     * Resolves an enum-typed value, listing the accepted constants on failure. The reported
     * position is the one of the offending value, before it is consumed.
     */
    private static <E extends Enum<E>> E enumValue(Cursor cursor, Field field, Class<E> type) {
        int position = cursor.position();
        String token = cursor.next();
        try {
            return Enum.valueOf(type, token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown value '" + token + "' for '" + field.label
                    + "' at position " + position + " in the targeting condition. "
                    + "Allowed values: " + Arrays.toString(type.getEnumConstants()) + ".");
        }
    }

    /**
     * Refuses the {@code like} operator on a non-textual field.
     */
    private static void requireText(Field field, int position) {
        if (field.kind != Kind.TEXT) {
            throw new IllegalArgumentException("The 'like' operator applies to text "
                    + "fields only, not to '" + field.label + "', at position " + position
                    + " in the targeting condition.");
        }
    }

    /**
     * Indicates whether a token is a number of the grammar.
     */
    private static boolean isNumber(String token) {
        return token.matches("-?\\d+(?:\\.\\d+)?");
    }

    // --------------------------------------------------
    // Compilation
    // --------------------------------------------------

    /**
     * Compiles a parsed tree into a HQL fragment, binding every value as a numbered parameter.
     * The text carries nothing but whitelisted identifiers, operators, and {@code ?n} markers.
     *
     * @param node       The parsed tree.
     * @param parameters The parameter accumulator, filled in marker order.
     * @return The HQL fragment for a {@code select p from Product p where …} query.
     */
    static String compile(Node node, List<Object> parameters) {
        if (node instanceof Conjunction conjunction) {
            return "(" + compile(conjunction.left(), parameters) + " and "
                    + compile(conjunction.right(), parameters) + ")";
        }
        if (node instanceof Disjunction disjunction) {
            return "(" + compile(disjunction.left(), parameters) + " or "
                    + compile(disjunction.right(), parameters) + ")";
        }
        if (node instanceof Negation negation) {
            return "not (" + compile(negation.inner(), parameters) + ")";
        }
        Comparison comparison = (Comparison) node;
        int marker = parameters.size() + 1;
        String hql = switch (comparison.operator()) {
            case EQUALS -> comparison.field().path + " = ?" + marker;
            case NOT_EQUALS -> comparison.field().path + " <> ?" + marker;
            case LESS -> comparison.field().path + " < ?" + marker;
            case LESS_OR_EQUALS -> comparison.field().path + " <= ?" + marker;
            case GREATER -> comparison.field().path + " > ?" + marker;
            case GREATER_OR_EQUALS -> comparison.field().path + " >= ?" + marker;
            case LIKE -> comparison.field().path + " like ?" + marker;
            case IN -> comparison.field().path + " in ?" + marker;
        };
        if (comparison.operator() == Operator.IN) {
            // One collection parameter: the values travel as a single bound list.
            parameters.add(comparison.values());
        } else {
            parameters.addAll(comparison.values());
        }
        return hql;
    }
}
