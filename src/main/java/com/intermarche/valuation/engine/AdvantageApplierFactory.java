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

    /**
     * Returns the offer type handled by this factory (e.g., "MEAL_VOUCHER", "N+M").
     * <p>
     * The value must match the {@code type} column of the offers targeted by this factory.
     * Factories that are not driven by a database offer type return {@code null}.
     *
     * @return The offer type discriminator, or {@code null} if this factory has no type.
     */
    default String getOfferType() {
        return null;
    }

    /**
     * Returns the JSON Schema describing the specification accepted by this factory.
     * <p>
     * The schema is the single source of truth: it is used at runtime to validate
     * offer specifications, and by the administration UI to render an edition form.
     * Factories without a configurable specification return {@code null}.
     *
     * @return The JSON Schema as a string, or {@code null} if this factory has no schema.
     */
    default String getSchema() {
        return null;
    }
}
