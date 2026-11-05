package com.intermarche.valuation.engine;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link TargetWhere}, the closed grammar of the free-form product targeting
 * (spec-ciblage-where §2, §6).
 * <p>
 * Every parse test pins the refusal message — the grammar's security contract is that an
 * unexpressible construct is refused with a precise position, never interpreted loosely.
 * Every compile test pins the exact HQL fragment and parameter list, proving the twin
 * invariants: the query text carries nothing but whitelisted identifiers and markers, and
 * every user value ends up bound as a parameter.
 */
class TargetWhereTest {

    /**
     * Asserts that a fragment parses and compiles to the exact fragment and parameters.
     *
     * @param fragment   The condition text.
     * @param hql        The expected compiled HQL fragment.
     * @param parameters The expected bound parameters, in marker order.
     */
    private void compilesTo(String fragment, String hql, Object... parameters) {
        List<Object> bound = new ArrayList<>();
        assertEquals(hql, TargetWhere.compile(TargetWhere.parse(fragment), bound));
        assertEquals(List.of(parameters), bound);
    }

    /**
     * Asserts that a fragment is refused with the exact message.
     *
     * @param fragment The condition text.
     * @param message  The expected refusal message.
     */
    private void refuses(String fragment, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TargetWhere.parse(fragment));
        assertEquals(message, error.getMessage());
    }

    // --------------------------------------------------
    // Happy paths
    // --------------------------------------------------

    /**
     * A single textual equality compiles to one bound marker.
     */
    @Test
    void parsesASingleTextualEquality() {
        compilesTo("brand = 'DANONE'", "p.brand = ?1", "DANONE");
    }

    /**
     * The two inequality spellings are equivalent.
     */
    @Test
    void parsesBothInequalitySpellings() {
        compilesTo("brand != 'X'", "p.brand <> ?1", "X");
        compilesTo("brand <> 'X'", "p.brand <> ?1", "X");
    }

    /**
     * A like condition carries the pattern verbatim, wildcards included.
     */
    @Test
    void parsesALikeWithWildcards() {
        compilesTo("name like 'bio%'", "p.name like ?1", "bio%");
    }

    /**
     * The quoted-string escape yields a literal quote in the value.
     */
    @Test
    void unescapesDoubledQuotes() {
        compilesTo("brand = 'L''AQUA'", "p.brand = ?1", "L'AQUA");
    }

    /**
     * An in-list binds one collection parameter, several values or one.
     */
    @Test
    void parsesAnInList() {
        compilesTo("ean in ('1', '2', '3')", "p.ean in ?1", List.of("1", "2", "3"));
        compilesTo("ean in ('1')", "p.ean in ?1", List.of("1"));
    }

    /**
     * Numeric fields take the ordered operators, decimals and negatives included.
     */
    @Test
    void parsesNumericComparisons() {
        compilesTo("referenceWeight >= 1.5", "p.referenceWeight >= ?1", new java.math.BigDecimal("1.5"));
        compilesTo("referenceWeight < 0", "p.referenceWeight < ?1", new java.math.BigDecimal("0"));
        compilesTo("referenceVolume <= 2", "p.referenceVolume <= ?1", new java.math.BigDecimal("2"));
        compilesTo("referenceVolume > 0.5", "p.referenceVolume > ?1", new java.math.BigDecimal("0.5"));
    }

    /**
     * Enum fields resolve their constants, case-insensitively, and refuse unknown ones by
     * listing what is allowed.
     */
    @Test
    void parsesEnumConditions() {
        List<Object> bound = new ArrayList<>();
        assertEquals("p.productType = ?1",
                TargetWhere.compile(TargetWhere.parse("productType = unit"), bound));
        assertEquals(com.intermarche.valuation.domain.ProductType.UNIT, bound.get(0));
        compilesTo("egalimRegime in (food_34, exempt)", "p.egalimRegime in ?1",
                List.of(com.intermarche.valuation.domain.EgalimRegime.FOOD_34,
                        com.intermarche.valuation.domain.EgalimRegime.EXEMPT));
    }

    /**
     * Conjunctions, disjunctions, negations and parentheses compile to the parenthesised
     * tree, with markers numbered in traversal order.
     */
    @Test
    void parsesBooleanCombinations() {
        compilesTo("brand = 'A' and name like 'b%'",
                "(p.brand = ?1 and p.name like ?2)", "A", "b%");
        compilesTo("brand = 'A' or brand = 'B'",
                "(p.brand = ?1 or p.brand = ?2)", "A", "B");
        compilesTo("not brand = 'A'", "not (p.brand = ?1)", "A");
        compilesTo("not not brand = 'A'", "not (not (p.brand = ?1))", "A");
        compilesTo("(brand = 'A' or brand = 'B') and not name like 'x%'",
                "((p.brand = ?1 or p.brand = ?2) and not (p.name like ?3))", "A", "B", "x%");
        compilesTo("brand = 'A' and brand = 'B' and brand = 'C'",
                "((p.brand = ?1 and p.brand = ?2) and p.brand = ?3)", "A", "B", "C");
        compilesTo("brand = 'A' or brand = 'B' or name like 'c'",
                "((p.brand = ?1 or p.brand = ?2) or p.name like ?3)", "A", "B", "c");
    }

    /**
     * Keywords are case-insensitive and surrounding whitespace is free.
     */
    @Test
    void parsesCaseInsensitiveKeywordsAndLooseWhitespace() {
        compilesTo(" BRAND='A'  AND  Name LIKE 'b%' ", "(p.brand = ?1 and p.name like ?2)",
                "A", "b%");
    }

    // --------------------------------------------------
    // Refusals
    // --------------------------------------------------

    /**
     * An empty or null fragment is refused outright.
     */
    @Test
    void refusesEmptyFragments() {
        refuses(null, "The targeting condition is empty.");
        refuses("   ", "The targeting condition is empty.");
    }

    /**
     * An unknown field names the allowed ones.
     */
    @Test
    void refusesUnknownFields() {
        refuses("colour = 'red'",
                "Unknown field 'colour' at position 0 in the targeting condition. Allowed "
                        + "fields: ean, name, brand, productType, egalimRegime, referenceWeight, "
                        + "referenceVolume.");
    }

    /**
     * A field without an operator, or with a token where an operator belongs, is refused with
     * the position.
     */
    @Test
    void refusesMissingOperators() {
        refuses("brand",
                "Expected a comparison operator after 'brand' but found '' at position 5 in "
                        + "the targeting condition.");
        refuses("brand 'X'",
                "Expected a comparison operator after 'brand' but found ''X'' at position 6 in "
                        + "the targeting condition.");
    }

    /**
     * like is a textual operator: on a numeric field it is refused.
     */
    @Test
    void refusesLikeOnANumericField() {
        refuses("referenceWeight like '1'",
                "The 'like' operator applies to text fields only, not to 'referenceWeight', at "
                        + "position 16 in the targeting condition.");
    }

    /**
     * Value kinds are enforced per field: quoted strings on numerics, numbers on text.
     */
    @Test
    void refusesMismatchedValueKinds() {
        refuses("referenceWeight = '1'",
                "Expected a number for 'referenceWeight' but found ''1'' at position 18 in the "
                        + "targeting condition.");
        refuses("brand = DANONE",
                "Expected a quoted string for 'brand' but found 'DANONE' at position 8 in the "
                        + "targeting condition.");
    }

    /**
     * An unknown enum value lists the accepted constants.
     */
    @Test
    void refusesUnknownEnumValues() {
        refuses("productType = BOX",
                "Unknown value 'BOX' for 'productType' at position 14 in the targeting "
                        + "condition. Allowed values: [UNIT, WEIGHT, VOLUME].");
    }

    /**
     * The in-list needs its parentheses, cannot be empty, and cannot dangle.
     */
    @Test
    void refusesMalformedInLists() {
        refuses("ean in '1'",
                "Expected '(' after 'in' at position 7 in the targeting condition.");
        refuses("ean in ()",
                "An 'in' condition cannot be empty, at position 8 in the targeting condition.");
        refuses("ean in ('1'",
                "Missing ')' at position 11 in the targeting condition.");
    }

    /**
     * Unbalanced parentheses and trailing tokens are refused with their position.
     */
    @Test
    void refusesUnbalancedStructure() {
        refuses("(brand = 'A'",
                "Missing ')' at position 12 in the targeting condition.");
        refuses("brand = 'A' 'B'",
                "Unexpected ''B'' at position 12 in the targeting condition.");
    }

    /**
     * A condition ending where a value belongs names the field it starves.
     */
    @Test
    void refusesATruncatedCondition() {
        refuses("brand =",
                "Expected a value after 'brand' but the condition ends, at position 7 in the "
                        + "targeting condition.");
    }

    /**
     * The coverage preview runs through the two parameterized queries the administration
     * form reads: an exact count projection and a capped, EAN-ordered sample.
     */
    @Test
    void countsAndSamplesTheCoverage() {
        com.intermarche.valuation.domain.Product product = new com.intermarche.valuation.domain.Product();
        product.ean = "123";
        try (MockedStatic<PanacheEntityBase> base = org.mockito.Mockito.mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.count(
                    "select count(p) from Product p where p.brand = ?1", "X"))
                    .thenReturn(7L);
            // The sample query is bound with the same parameterized text and capped by range.
            base.when(() -> PanacheEntityBase.find(
                    "select p from Product p where p.brand = ?1 order by p.ean", "X"))
                    .thenAnswer(invocation -> {
                        PanacheQuery<com.intermarche.valuation.domain.Product> panache =
                                org.mockito.Mockito.mock(PanacheQuery.class);
                        org.mockito.Mockito.when(panache.range(0, 19)).thenReturn(panache);
                        org.mockito.Mockito.when(panache.list()).thenReturn(List.of(product));
                        return panache;
                    });
            org.junit.jupiter.api.Assertions.assertEquals(7L, TargetWhere.countOf("brand = 'X'"));
            org.junit.jupiter.api.Assertions.assertEquals(List.of(product),
                    TargetWhere.sampleOf("brand = 'X'", 20));
        }
    }

    /**
     * An unterminated string and an unknown character carry their position.
     */
    @Test
    void refusesLexicalGarbage() {
        refuses("brand = 'DANONE",
                "Unterminated string at position 8 in the targeting condition.");
        refuses("brand @ 'X'",
                "Unexpected character '@' at position 6 in the targeting condition.");
    }
}
