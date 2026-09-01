package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;

import java.util.Collection;

/**
 * Service Provider Interface (SPI) for applying discounts to a shopping basket evaluation.
 * <p>
 * Implementations of this interface are responsible for analyzing the provided basket evaluation,
 * finding applicable discounts, and returning the results of those calculations.
 */
public interface AdvantageApplier {

    /**
     * Returns the configuration (the {@link Offer} table row) this applier was built from.
     * <p>
     * This link is what lets the arbitration read the applier's trigger and arbitration
     * parameters per configuration (spec §4). The contract is honest and abstract on
     * purpose: every applier answers for itself. An applier that is not born from a
     * configuration row returns {@code null}, and the arbitration then treats it as
     * {@link Trigger#ALWAYS} with the default arbitration parameters (priority 500,
     * cumulable, no exclusion groups, no limits, {@code consumesContributors} false).
     *
     * @return the source configuration, or {@code null} when the applier is not born from a
     *         configuration row.
     */
    Offer getConfiguration();

    /**
     * Determines if this discount applier is applicable to the given offer applier.
     * @param offerApplier The offer applier to check applicability against.
     * @return True if this discount applier can be applied in the context of the provided offer applier; false otherwise.
     */
    boolean isApplicable(OfferApplier offerApplier);

    /**
     * Applies discount logic to the provided basket evaluation.
     * <p>
     * This method analyzes the basket contents, store, and customer details
     * to determine which discounts apply and their respective impacts.
     *
     * @param basketEvaluation The evaluation context containing the basket and its items.
     * @return A collection of offer applications representing calculated offers.
     */
    Collection<AdvantageApplication> apply(BasketEvaluation basketEvaluation);

    /**
     * Gets the efficiency score of this discount applier.
     * <p>
     * The efficiency score is a metric used to prioritize discount appliers.
     * Higher scores indicate more efficient or relevant discount.
     *
     * @return The efficiency score as a double.
     */
    double getEfficiencyScore();
}