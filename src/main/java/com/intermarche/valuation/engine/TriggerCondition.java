package com.intermarche.valuation.engine;

/**
 * A trigger node: it evaluates itself against the current state of a basket evaluation and
 * reports whether it is satisfied together with the valued lines it selected.
 * <p>
 * This is the internal composite contract behind the flat JSON contract (spec §3.9). Today
 * the tree has a single composite — {@link Trigger}, the implicit AND node the engine sees —
 * and three leaves, one per kind ({@link MinimumAmountCondition},
 * {@link MinimumQuantityCondition}, {@link CouponCodeCondition}). Children are typed on this
 * interface so a future OR node enters as one more class and one schema entry, with no
 * rework. The arbitration only ever calls {@link Trigger#evaluate(BasketEvaluation)}; no one
 * can interrogate a leaf in isolation.
 * <p>
 * The outcome type is {@link TriggerResult} (the {@code ConditionOutcome} of §3.9): a leaf
 * and the root share the very same shape, so a single record serves both.
 */
public interface TriggerCondition {

    /**
     * Evaluates this node against the current basket evaluation.
     * <p>
     * A node always tells the truth: the surrounding context (open basket, application
     * moment) never forces it to {@code false}. Amounts and quantities are read as the
     * arbitration currently sees them — the "current amounts" of spec §3.2.
     *
     * @param evaluation the evaluation context, carrying the basket and the offers valued so
     *                   far.
     * @return the outcome: whether the node is satisfied and the valued lines it selected.
     */
    TriggerResult evaluate(BasketEvaluation evaluation);
}
