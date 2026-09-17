package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Leaf condition of kind {@code MINIMUM_QUANTITY}: satisfied when the summed quantity of the
 * valued lines whose EAN is targeted reaches a threshold (spec §3.2).
 * <p>
 * Quantities are summed as they are, decimals included: a weighed line of 0.5 counts as 0.5,
 * consistent with the engine's quantity semantics. The threshold is reached inclusively
 * (<em>greater than or equal to</em>). Contributors are the offer applications carrying at
 * least one counted line; a discount never changes a counted quantity, so no netting applies
 * here.
 */
public final class MinimumQuantityCondition implements TriggerCondition {

    /**
     * The targeted EANs; required and non-empty for this kind.
     */
    private final Set<String> eans;

    /**
     * The quantity threshold to reach.
     */
    private final BigDecimal threshold;

    /**
     * Builds a minimum-quantity condition.
     *
     * @param eans      the targeted EANs, deduplicated and copied defensively.
     * @param threshold the quantity threshold to reach.
     */
    public MinimumQuantityCondition(Set<String> eans, BigDecimal threshold) {
        this.eans = eans == null ? Set.of() : new LinkedHashSet<>(eans);
        this.threshold = threshold;
    }

    /**
     * Returns the targeted EANs.
     *
     * @return an unmodifiable view of the deduplicated EANs.
     */
    public Set<String> getEans() {
        return Set.copyOf(eans);
    }

    /**
     * Returns the quantity threshold to reach.
     *
     * @return the threshold.
     */
    public BigDecimal getThreshold() {
        return threshold;
    }

    /**
     * Evaluates the condition against the current valued lines.
     *
     * @param evaluation the evaluation context.
     * @return satisfied when the summed matched quantity reaches the threshold; contributors
     *         are the offer applications carrying at least one matching valued line.
     */
    @Override
    public TriggerResult evaluate(BasketEvaluation evaluation) {
        List<OfferApplication> contributors = new ArrayList<>();
        BigDecimal quantity = BigDecimal.ZERO;
        if (evaluation.getOffers() != null) {
            for (OfferApplication app : evaluation.getOffers()) {
                if (evaluation.isConsumed(app)) {
                    continue;
                }
                boolean contributes = false;
                for (BasketEvaluation.Item item : app.getValuedItems()) {
                    if (item.produceEan == null || !eans.contains(item.produceEan)) {
                        continue;
                    }
                    contributes = true;
                    quantity = quantity.add(BigDecimal.valueOf(item.quantity));
                }
                if (contributes) {
                    contributors.add(app);
                }
            }
        }
        return new TriggerResult(quantity.compareTo(threshold) >= 0, contributors);
    }
}
