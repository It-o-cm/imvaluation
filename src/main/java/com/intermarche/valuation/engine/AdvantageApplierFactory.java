package com.intermarche.valuation.engine;

import java.util.Collection;

/**
 * Service Provider Interface (SPI) Factory for creating {@link OfferApplier} instances.
 * <p>
 * This interface allows for dynamic or conditional creation of offer appliers based on
 * the specific context of a {@link Basket}.
 * <p>
 * For example, a factory might decide to return a specific applier based on
 * the store code, the customer code, or the content of the basket.
 */
public interface AdvantageApplierFactory {

    /**
     * Builds a collection of {@link AdvantageApplier} instances for the given basket evaluation.
     *
     */
    Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation);
}