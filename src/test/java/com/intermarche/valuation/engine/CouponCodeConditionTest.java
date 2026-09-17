package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CouponCodeCondition}: exact, case-sensitive, trimmed comparison of a
 * code against the basket's presented coupon codes, and the empty-contributors contract.
 */
public class CouponCodeConditionTest {

    /**
     * Builds an evaluation over a store-less basket carrying the given coupon codes.
     *
     * @param codes the coupon codes, or {@code null} to leave the list absent.
     * @return the evaluation.
     */
    private BasketEvaluation evaluationWith(List<String> codes) {
        Basket basket = new Basket();
        basket.couponCodes = codes;
        return new BasketEvaluation(basket);
    }

    /**
     * Tests that a present code satisfies the condition, with no contributors.
     */
    @Test
    void testPresentSatisfied() {
        BasketEvaluation evaluation = evaluationWith(List.of("HIVER24"));
        TriggerResult result = new CouponCodeCondition("HIVER24").evaluate(evaluation);
        assertTrue(result.satisfied());
        assertTrue(result.contributors().isEmpty());
    }

    /**
     * Tests that an absent code is not satisfied.
     */
    @Test
    void testAbsentNotSatisfied() {
        BasketEvaluation evaluation = evaluationWith(List.of("SUMMER"));
        assertFalse(new CouponCodeCondition("HIVER24").evaluate(evaluation).satisfied());
    }

    /**
     * Tests that a basket with no coupon codes, or a null basket, satisfies nothing.
     */
    @Test
    void testNoCodesAndNullBasket() {
        assertFalse(new CouponCodeCondition("HIVER24").evaluate(evaluationWith(null)).satisfied());
        assertFalse(new CouponCodeCondition("HIVER24")
                .evaluate(new BasketEvaluation(null)).satisfied());
    }

    /**
     * Tests that a code presented twice satisfies the condition once (the trigger does not
     * count presentations).
     */
    @Test
    void testDuplicatePresentationSatisfiesOnce() {
        List<String> codes = new ArrayList<>(List.of("HIVER24", "HIVER24"));
        assertTrue(new CouponCodeCondition("HIVER24").evaluate(evaluationWith(codes)).satisfied());
    }

    /**
     * Tests that the comparison is case-sensitive.
     */
    @Test
    void testCaseSensitive() {
        assertFalse(new CouponCodeCondition("HIVER24")
                .evaluate(evaluationWith(List.of("hiver24"))).satisfied());
    }

    /**
     * Tests that both sides are trimmed before comparison.
     */
    @Test
    void testTrimBothSides() {
        BasketEvaluation evaluation = evaluationWith(List.of("  HIVER24  "));
        assertTrue(new CouponCodeCondition("  HIVER24  ").evaluate(evaluation).satisfied());
    }

    /**
     * Tests that a null presented code in the list is skipped without failing.
     */
    @Test
    void testNullPresentedCodeSkipped() {
        List<String> codes = new ArrayList<>();
        codes.add(null);
        codes.add("HIVER24");
        assertTrue(new CouponCodeCondition("HIVER24").evaluate(evaluationWith(codes)).satisfied());
    }

    /**
     * Tests that the accessor returns the trimmed code, and that a null code becomes empty.
     */
    @Test
    void testAccessor() {
        assertEquals("HIVER24", new CouponCodeCondition("  HIVER24 ").getCode());
        assertEquals("", new CouponCodeCondition(null).getCode());
    }
}
