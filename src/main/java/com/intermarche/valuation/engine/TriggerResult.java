package com.intermarche.valuation.engine;

import java.util.List;

/**
 * Outcome of evaluating a configuration's trigger against the current basket state.
 * <p>
 * Two fields, two consumers (spec §3.3): {@code satisfied} feeds the arbitration (C1), and
 * {@code contributors} feeds the consumption of carriers (C2). A trigger always tells the
 * truth: it is never forced to {@code false} by the surrounding context (open basket,
 * application moment) — those decisions belong to the arbitration, not to the trigger.
 * <p>
 * A contributor is a valued line that satisfied a condition (selected by the <em>condition</em>,
 * not by the advantage's target). Conditions with no line-level attribution — the
 * {@code MINIMUM_AMOUNT} scope {@code TICKET} and {@code COUPON_CODE} kinds — contribute
 * nothing, so those triggers carry an empty contributor list even when satisfied.
 *
 * @param satisfied    whether the trigger (an implicit AND of its conditions) is satisfied.
 * @param contributors the valued lines that satisfied the conditions, deduplicated; never
 *                     {@code null}, possibly empty.
 */
public record TriggerResult(boolean satisfied, List<OfferApplication> contributors) {
}
