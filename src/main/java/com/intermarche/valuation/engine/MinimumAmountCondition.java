package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Leaf condition of kind {@code MINIMUM_AMOUNT}: satisfied when a current tax-included
 * amount reaches a threshold (spec §3.2).
 * <p>
 * Two scopes, one class:
 * <ul>
 *   <li>{@link Scope#TICKET} — the current total of every valued line (all offers, net of
 *       the advantages already retained). {@code eans} is forbidden. No contributors: the
 *       trigger is not attributable to lines (spec §4.4), so a consuming advantage consumes
 *       nothing through it.</li>
 *   <li>{@link Scope#ITEMS} — the current total of the valued lines whose EAN belongs to
 *       {@code eans}. {@code eans} is required. Contributors are the offer applications that
 *       carry at least one matching valued line.</li>
 * </ul>
 * A threshold is reached inclusively: the condition holds as soon as the amount is
 * <em>greater than or equal to</em> the threshold ("to reach" includes reaching it).
 */
public final class MinimumAmountCondition implements TriggerCondition {

    /**
     * The measured perimeter of a {@code MINIMUM_AMOUNT} condition.
     */
    public enum Scope {
        /** The whole ticket: every valued line, net of the advantages already retained. */
        TICKET,
        /** The listed EANs only: the valued lines whose product is in {@code eans}. */
        ITEMS
    }

    /**
     * The measured perimeter.
     */
    private final Scope scope;

    /**
     * The targeted EANs (scope {@code ITEMS}); empty in scope {@code TICKET}.
     */
    private final Set<String> eans;

    /**
     * The threshold to reach, tax included.
     */
    private final BigDecimal threshold;

    /**
     * Builds a minimum-amount condition.
     *
     * @param scope     the measured perimeter.
     * @param eans      the targeted EANs; ignored in scope {@code TICKET}, deduplicated and
     *                  copied defensively.
     * @param threshold the threshold to reach, tax included.
     */
    public MinimumAmountCondition(Scope scope, Set<String> eans, BigDecimal threshold) {
        this.scope = scope;
        this.eans = eans == null ? Set.of() : new LinkedHashSet<>(eans);
        this.threshold = threshold;
    }

    /**
     * Returns the measured perimeter.
     *
     * @return the scope.
     */
    public Scope getScope() {
        return scope;
    }

    /**
     * Returns the targeted EANs.
     *
     * @return an unmodifiable view of the deduplicated EANs; empty in scope {@code TICKET}.
     */
    public Set<String> getEans() {
        return Set.copyOf(eans);
    }

    /**
     * Returns the threshold to reach.
     *
     * @return the threshold, tax included.
     */
    public BigDecimal getThreshold() {
        return threshold;
    }

    /**
     * Evaluates the condition against the current valued lines.
     *
     * @param evaluation the evaluation context.
     * @return satisfied when the measured amount reaches the threshold; contributors are the
     *         matching offer applications in scope {@code ITEMS}, none in scope {@code TICKET}.
     */
    @Override
    public TriggerResult evaluate(BasketEvaluation evaluation) {
        if (scope == Scope.TICKET) {
            BigDecimal amount = ticketAmount(evaluation);
            return new TriggerResult(amount.compareTo(threshold) >= 0, List.of());
        }
        List<OfferApplication> contributors = new ArrayList<>();
        BigDecimal amount = itemsAmount(evaluation, contributors);
        return new TriggerResult(amount.compareTo(threshold) >= 0, contributors);
    }

    /**
     * Computes the current ticket amount: every valued offer, net of the retained advantages.
     *
     * @param evaluation the evaluation context.
     * @return the ticket amount, tax included, never negative.
     */
    private BigDecimal ticketAmount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        if (evaluation.getOffers() != null) {
            for (OfferApplication app : evaluation.getOffers()) {
                if (evaluation.isConsumed(app)) {
                    continue;
                }
                AmountEvaluation amount = app.getAmount();
                if (amount != null && amount.amountIncludingTax != null) {
                    total = total.add(amount.amountIncludingTax);
                }
            }
        }
        total = total.subtract(retainedDiscounts(evaluation));
        return total.signum() < 0 ? BigDecimal.ZERO : total;
    }

    /**
     * Sums the advantages already retained, tax included.
     *
     * @param evaluation the evaluation context.
     * @return the total discount retained, tax included, never negative.
     */
    private static BigDecimal retainedDiscounts(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        if (evaluation.getAdvantages() != null) {
            for (AdvantageApplication advantage : evaluation.getAdvantages()) {
                if (advantage instanceof DiscountApplication discount
                        && discount.getDiscountAmount() != null
                        && discount.getDiscountAmount().amountIncludingTax != null) {
                    total = total.add(discount.getDiscountAmount().amountIncludingTax);
                }
            }
        }
        return total;
    }

    /**
     * Computes the current amount of the valued lines whose EAN is targeted, netting each
     * offer application by the advantages already retained against it, and collecting the
     * contributing applications.
     *
     * @param evaluation   the evaluation context.
     * @param contributors the list receiving the matching offer applications (mutated).
     * @return the matched amount, tax included, never negative.
     */
    private BigDecimal itemsAmount(BasketEvaluation evaluation, List<OfferApplication> contributors) {
        BigDecimal total = BigDecimal.ZERO;
        if (evaluation.getOffers() == null) {
            return total;
        }
        for (OfferApplication app : evaluation.getOffers()) {
            if (evaluation.isConsumed(app)) {
                continue;
            }
            BigDecimal matched = BigDecimal.ZERO;
            boolean contributes = false;
            for (BasketEvaluation.Item item : app.getValuedItems()) {
                if (item.produceEan == null || !eans.contains(item.produceEan)) {
                    continue;
                }
                contributes = true;
                if (item.amount != null && item.amount.amountIncludingTax != null) {
                    matched = matched.add(item.amount.amountIncludingTax);
                }
            }
            if (!contributes) {
                continue;
            }
            contributors.add(app);
            // A3 (report H2): net each contributing application by the advantages already retained
            // against it, through the shared helper (this factor formerly lived here, duplicated).
            total = total.add(matched.multiply(NetAmounts.netFactor(evaluation, app)));
        }
        return total.signum() < 0 ? BigDecimal.ZERO : total;
    }
}
