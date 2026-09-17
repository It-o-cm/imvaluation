package com.intermarche.valuation.engine;

import java.util.List;

/**
 * Leaf condition of kind {@code COUPON_CODE}: satisfied when the configured code is present
 * among the basket's {@code couponCodes} (spec §3.2).
 * <p>
 * The comparison is exact and case-sensitive, after trimming both sides. The basket carries
 * a set of codes: a code presented twice satisfies the condition once, and the trigger never
 * counts presentations. A basket without {@code couponCodes} satisfies no coupon condition.
 * A coupon has no line-level attribution, so this condition never carries contributors: a
 * consuming advantage consumes nothing through it (spec §4.4).
 */
public final class CouponCodeCondition implements TriggerCondition {

    /**
     * The code that must be present, already trimmed.
     */
    private final String code;

    /**
     * Builds a coupon-code condition.
     *
     * @param code the required code; trimmed defensively, never {@code null}.
     */
    public CouponCodeCondition(String code) {
        this.code = code == null ? "" : code.trim();
    }

    /**
     * Returns the required code.
     *
     * @return the trimmed code.
     */
    public String getCode() {
        return code;
    }

    /**
     * Evaluates the condition against the basket's presented coupon codes.
     *
     * @param evaluation the evaluation context.
     * @return satisfied when a presented code equals the configured code after trimming;
     *         contributors are always empty.
     */
    @Override
    public TriggerResult evaluate(BasketEvaluation evaluation) {
        Basket basket = evaluation.getBasket();
        if (basket == null || basket.couponCodes == null) {
            return new TriggerResult(false, List.of());
        }
        for (String presented : basket.couponCodes) {
            if (presented != null && presented.trim().equals(code)) {
                return new TriggerResult(true, List.of());
            }
        }
        return new TriggerResult(false, List.of());
    }
}
