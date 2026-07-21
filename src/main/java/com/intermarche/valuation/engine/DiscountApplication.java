package com.intermarche.valuation.engine;

public interface DiscountApplication extends AdvantageApplication {

    /**
     * Retrieves the discount evaluation calculated for this specific application.
     *
     * @return The {@link AmountEvaluation} object containing discount details.
     */
    AmountEvaluation getDiscountAmount();
}
