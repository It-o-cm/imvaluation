package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Trigger}: the implicit AND semantics, the contributors union,
 * {@link Trigger#ALWAYS}, JSON parsing and the cross-field rejections of spec §3.8.
 */
public class TriggerTest {

    /**
     * The JSON mapper used to parse trigger fragments.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses a JSON string into a node.
     *
     * @param json the JSON text.
     * @return the parsed node.
     */
    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds a bare offer application, used only as a distinct contributor identity.
     *
     * @return the offer application stub.
     */
    private OfferApplication contributor() {
        return new OfferApplication() {
            /**
             * Returns no amount.
             *
             * @return null.
             */
            @Override
            public AmountEvaluation getAmount() {
                return null;
            }

            /**
             * Returns no covered items.
             *
             * @return an empty collection.
             */
            @Override
            public Collection<Basket.Item> getItems() {
                return List.of();
            }

            /**
             * Returns the offer type label.
             *
             * @return a constant label.
             */
            @Override
            public String getType() {
                return "C";
            }
        };
    }

    /**
     * Builds a constant condition returning a fixed outcome, to drive the AND node.
     *
     * @param satisfied    whether the condition is satisfied.
     * @param contributors the contributors it reports.
     * @return the condition stub.
     */
    private TriggerCondition constant(boolean satisfied, List<OfferApplication> contributors) {
        return evaluation -> new TriggerResult(satisfied, contributors);
    }

    /**
     * Tests that {@link Trigger#ALWAYS} is satisfied with no contributors, and that parsing
     * a null node returns it.
     */
    @Test
    void testAlways() {
        TriggerResult result = Trigger.ALWAYS.evaluate(new BasketEvaluation(new Basket()));
        assertTrue(result.satisfied());
        assertTrue(result.contributors().isEmpty());
        assertSame(Trigger.ALWAYS, Trigger.of(null));
        assertSame(Trigger.ALWAYS, Trigger.of(node("null")));
    }

    /**
     * Tests the AND: satisfied only when every child is, otherwise not.
     */
    @Test
    void testAndSemantics() {
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());
        Trigger allTrue = new Trigger(List.of(constant(true, List.of()), constant(true, List.of())));
        Trigger oneFalse = new Trigger(List.of(constant(true, List.of()), constant(false, List.of())));
        assertTrue(allTrue.evaluate(evaluation).satisfied());
        assertFalse(oneFalse.evaluate(evaluation).satisfied());
    }

    /**
     * Tests that the contributors are the deduplicated union of the children's contributors,
     * in first-seen order (identity semantics).
     */
    @Test
    void testContributorsUnionDeduplicated() {
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());
        OfferApplication a = contributor();
        OfferApplication b = contributor();
        Trigger trigger = new Trigger(List.of(
                constant(true, List.of(a, b)),
                constant(true, List.of(a))));
        List<OfferApplication> contributors = trigger.evaluate(evaluation).contributors();
        assertEquals(2, contributors.size());
        assertSame(a, contributors.get(0));
        assertSame(b, contributors.get(1));
    }

    /**
     * Tests parsing then evaluating a full MINIMUM_AMOUNT + COUPON_CODE conjunction.
     */
    @Test
    void testParseAndEvaluate() {
        Trigger trigger = Trigger.of(node("""
                { "conditions": [
                    { "kind": "MINIMUM_AMOUNT", "scope": "TICKET", "threshold": 40.0 },
                    { "kind": "COUPON_CODE", "code": "HIVER24" } ] }"""));
        assertEquals(2, trigger.getChildren().size());
        assertInstanceOf(MinimumAmountCondition.class, trigger.getChildren().get(0));
        assertInstanceOf(CouponCodeCondition.class, trigger.getChildren().get(1));
    }

    /**
     * Tests parsing every leaf kind, including a MINIMUM_QUANTITY and a MINIMUM_AMOUNT ITEMS.
     */
    @Test
    void testParseEveryKind() {
        Trigger trigger = Trigger.of(node("""
                { "conditions": [
                    { "kind": "MINIMUM_AMOUNT", "scope": "ITEMS", "eans": ["1"], "threshold": 10 },
                    { "kind": "MINIMUM_QUANTITY", "eans": ["2"], "threshold": 3 } ] }"""));
        assertInstanceOf(MinimumAmountCondition.class, trigger.getChildren().get(0));
        assertEquals(MinimumAmountCondition.Scope.ITEMS,
                ((MinimumAmountCondition) trigger.getChildren().get(0)).getScope());
        assertInstanceOf(MinimumQuantityCondition.class, trigger.getChildren().get(1));
    }

    /**
     * Tests that duplicate EANs are tolerated and deduplicated (spec §3.8.6).
     */
    @Test
    void testDuplicateEansDeduplicated() {
        Trigger trigger = Trigger.of(node("""
                { "conditions": [
                    { "kind": "MINIMUM_QUANTITY", "eans": ["1", "1", "2"], "threshold": 3 } ] }"""));
        MinimumQuantityCondition condition = (MinimumQuantityCondition) trigger.getChildren().get(0);
        assertEquals(Set.of("1", "2"), condition.getEans());
    }

    /**
     * Tests that an empty or missing conditions array is rejected (spec §3.8.3).
     */
    @Test
    void testEmptyConditionsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("{ \"conditions\": [] }")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("{}")));
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.of(node("{ \"conditions\": \"x\" }")));
    }

    /**
     * Tests that scope ITEMS without eans and scope TICKET with eans are both rejected
     * (spec §3.8.1 and §3.8.2).
     */
    @Test
    void testScopeEansCrossRules() {
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_AMOUNT", "scope": "ITEMS", "threshold": 10 } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_AMOUNT", "scope": "TICKET",
                  "eans": ["1"], "threshold": 10 } ] }""")));
    }

    /**
     * Tests that an unknown kind, a missing kind and an unknown scope are rejected
     * (spec §3.8.4).
     */
    @Test
    void testUnknownKindAndScope() {
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MYSTERY" } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "scope": "TICKET", "threshold": 10 } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_AMOUNT", "scope": "GALAXY", "threshold": 10 } ] }""")));
    }

    /**
     * Tests that a missing scope, a missing threshold, an empty quantity EAN list and a
     * blank coupon code are all rejected.
     */
    @Test
    void testMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_AMOUNT", "threshold": 10 } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_AMOUNT", "scope": "TICKET" } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_QUANTITY", "eans": [], "threshold": 3 } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "COUPON_CODE", "code": "  " } ] }""")));
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "COUPON_CODE" } ] }""")));
    }

    /**
     * Tests that a non-numeric threshold is rejected.
     */
    @Test
    void testNonNumericThresholdRejected() {
        assertThrows(IllegalArgumentException.class, () -> Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_QUANTITY", "eans": ["1"], "threshold": "x" } ] }""")));
    }

    /**
     * Tests the defensive copy of the children list on construction.
     */
    @Test
    void testChildrenDefensiveCopy() {
        Trigger trigger = new Trigger(List.of(constant(true, List.of())));
        assertThrows(UnsupportedOperationException.class, () -> trigger.getChildren().clear());
        assertEquals(1, trigger.getChildren().size());
    }

    /**
     * Tests that a parsed MINIMUM_AMOUNT TICKET condition evaluates against a real basket
     * evaluation, tying parsing to evaluation end to end.
     */
    @Test
    void testParsedTicketConditionEvaluates() {
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());
        evaluation.getOffers().add(new OfferApplication() {
            /**
             * Returns a forty-euro amount.
             *
             * @return the amount.
             */
            @Override
            public AmountEvaluation getAmount() {
                return new AmountEvaluation(new BigDecimal("40"), new BigDecimal("40"), BigDecimal.ZERO);
            }

            /**
             * Returns no covered items.
             *
             * @return an empty collection.
             */
            @Override
            public Collection<Basket.Item> getItems() {
                return List.of();
            }

            /**
             * Returns the offer type label.
             *
             * @return a constant label.
             */
            @Override
            public String getType() {
                return "T";
            }
        });
        Trigger trigger = Trigger.of(node("""
                { "conditions": [ { "kind": "MINIMUM_AMOUNT", "scope": "TICKET", "threshold": 40 } ] }"""));
        assertTrue(trigger.evaluate(evaluation).satisfied());
    }
}
