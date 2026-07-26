package com.intermarche.valuation.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Engine responsible for evaluating a {@link Basket} and applying offers.
 * <p>
 * This engine orchestrates the valuation process by delegating
 * applier creation and application generation to specific isolated methods.
 */
@ApplicationScoped
public class ValuationEngine {

    /**
     * List of all factories registered in the application.
     * <p>
     * CDI injects all beans implementing {@link AdvantageApplierFactory}.
     */
    @Inject
    Instance<AdvantageApplierFactory> discountFactories;

    /**
     * List of all factories registered in the application.
     * <p>
     * CDI injects all beans implementing {@link OfferApplierFactory}.
     */
    @Inject
    Instance<OfferApplierFactory> offerFactories;

    /**
     * Evaluates the provided basket.
     * <p>
     * This method instantiates a new {@link BasketEvaluation}, populates it
     * with items from the basket, and orchestrates the creation of appliers
     * and applications. Finally, it calculates the total price and updates the evaluation.
     *
     * @param basket The basket to evaluate.
     * @return A {@link BasketEvaluation} containing applied offers, discounts, and total price.
     */
    public BasketEvaluation evaluate(Basket basket) {
        // 1. Initialize evaluation context
        BasketEvaluation evaluation = getBasketEvaluation(basket);
        // 2. Create Discount Appliers
        List<AdvantageApplier> discountAppliers = createDiscountAppliers(evaluation);
        // 3. Create Offer Appliers
        List<OfferApplier> offerAppliers = createOfferAppliers(evaluation, discountAppliers);
        // 4. Create Offer Applications
        createOfferApplications(offerAppliers, evaluation);
        // 5. Create Discount Applications
        createDiscountApplications(discountAppliers, evaluation);
        // 6. Calculate and Set Total Price
        AmountEvaluation finalPrice = calculateAmountEvaluation(evaluation);
        evaluation.setTotalPrice(finalPrice);
        return evaluation;
    }

    /**
     * Initializes a new {@link BasketEvaluation} for the provided basket.
     *
     * @param basket The basket to evaluate.
     * @return A new {@link BasketEvaluation} instance.
     */
    private BasketEvaluation getBasketEvaluation(Basket basket) {
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        return evaluation;
    }

    /**
     * Calculates the final total price of the basket after applying offers and discounts.
     *
     * @param evaluation The evaluation context containing applied offers and discounts.
     * @return The final total price as an {@link AmountEvaluation}.
     */
    private static AmountEvaluation calculateAmountEvaluation(BasketEvaluation evaluation) {
        BigDecimal totalHT = BigDecimal.ZERO;
        BigDecimal totalTTC = BigDecimal.ZERO;
        // Sum up all Offer Prices (Products, Delivery, Bundles, etc.)
        if (evaluation.getOffers() != null) {
            for (OfferApplication app : evaluation.getOffers()) {
                AmountEvaluation price = app.getAmount();
                if (price != null) {
                    totalHT = totalHT.add(price.amountExcludingTax);
                    totalTTC = totalTTC.add(price.amountIncludingTax);
                }
            }
        }
        // Subtract all Discount Prices
        if (evaluation.getAdvantages() != null) {
            for (AdvantageApplication app : evaluation.getAdvantages()) {
                if (app instanceof DiscountApplication) {
                    DiscountApplication discount = (DiscountApplication) app;
                    AmountEvaluation price = discount.getDiscountAmount();
                    if (price != null) {
                        // getDiscountAmount() returns a positive amount to deduct; every
                        // DiscountApplication follows that contract, so the sign lives here
                        // in the subtraction, not in the stored value.
                        totalHT = totalHT.subtract(price.amountExcludingTax);
                        totalTTC = totalTTC.subtract(price.amountIncludingTax);
                    }
                }
            }
        }
        // Create the final PriceEvaluation and set it in the context
        // VAT Rate is set to 0 as it's a mix of different rates
        AmountEvaluation finalPrice = new AmountEvaluation(
                totalHT.setScale(2, RoundingMode.HALF_UP),
                totalTTC.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO
        );
        return finalPrice;
    }

    // --------------------------------------------------
    // Factory & Applier Creation Methods
    // --------------------------------------------------

    /**
     * Creates a list of {@link OfferApplier} instances relevant to the provided basket.
     * <p>
     * This method iterates over all available {@link OfferApplierFactory} beans
     * and delegates the creation of appliers to them.
     * <p>
     * Errors during factory processing are caught and logged to ensure that
     * one faulty factory does not stop the entire valuation process.
     *
     * @param basketEvaluation The basket evaluation used to build appliers.
     * @param discountAppliers The list of discount appliers to consider.
     * @return A list of available offer appliers.
     */
    private List<OfferApplier> createOfferAppliers(
            BasketEvaluation basketEvaluation, List<AdvantageApplier> discountAppliers
    ) {
        List<OfferApplier> appliers = new ArrayList<>();
        if (offerFactories != null) {
            for (OfferApplierFactory factory : offerFactories) {
                try {
                    Collection<OfferApplier> builtAppliers = factory.buildAppliers(basketEvaluation);
                    if (builtAppliers != null) {
                        for (OfferApplier applier : builtAppliers) {
                            for (AdvantageApplier discountApplier : discountAppliers) {
                                if (discountApplier.isApplicable(applier)) {
                                    applier.registerDiscountApplier(discountApplier);
                                }
                            }
                            double efficiencyScore = applier.computeEfficiencyScore(basketEvaluation.getBasket());
                            applier.setEfficiencyScore(efficiencyScore);
                        }
                        appliers.addAll(builtAppliers);
                    }
                } catch (Exception e) {
                    // Log error but continue with other factories
                    throw new RuntimeException("Error building appliers from factory: " + e.getMessage(), e);
                }
            }
        }
        return appliers;
    }

    /**
     * Sorts the provided appliers and creates {@link OfferApplication} instances.
     * <p>
     * This method uses an isolated {@link OfferApplierEvaluator} to order
     * appliers by efficiency score, then iterates through them to apply their
     * logic to the provided {@link BasketEvaluation} context.
     * <p>
     * Results (applications) are added directly to the evaluation object.
     *
     * @param appliers   The list of appliers to process.
     * @param evaluation The evaluation context to modify (consume items, add offers).
     */
     void createOfferApplications(List<OfferApplier> appliers, BasketEvaluation evaluation) {
        // 1. Sort Appliers using the dedicated evaluator object
        OfferApplierEvaluator evaluator = new OfferApplierEvaluator();
        evaluator.sort(appliers, evaluation);
        // 2. Apply Appliers in sorted order
        for (OfferApplier applier : appliers) {
            try {
                // Apply offer logic to the evaluation context
                Collection<OfferApplication> applications = applier.apply(evaluation);
                if (applications != null) {
                    evaluation.getOffers().addAll(applications);
                }
            } catch (Exception e) {
                // Log error but continue with other appliers
                throw new RuntimeException("Error applying offer logic: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Sorts the provided appliers and creates {@link AdvantageApplication} instances.
     * <p>
     * This method uses an isolated {@link DiscountApplierEvaluator} to order
     * appliers by efficiency score, then iterates through them to apply their
     * logic to the provided {@link BasketEvaluation} context.
     * <p>
     * Results (applications) are added directly to the evaluation object.
     *
     * @param appliers   The list of appliers to process.
     * @param evaluation The evaluation context to modify (consume items, add offers).
     */
     void createDiscountApplications(List<AdvantageApplier> appliers, BasketEvaluation evaluation) {
        // 1. Sort Appliers using the dedicated evaluator object
        DiscountApplierEvaluator evaluator = new DiscountApplierEvaluator();
        evaluator.sort(appliers);
        // 2. Apply Appliers in sorted order
        for (AdvantageApplier applier : appliers) {
            try {
                // Apply offer logic to the evaluation context
                Collection<AdvantageApplication> applications = applier.apply(evaluation);
                if (applications != null) {
                    evaluation.getAdvantages().addAll(applications);
                }
            } catch (Exception e) {
                // Log error but continue with other appliers
                throw new RuntimeException("Error applying discount logic: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Creates a list of {@link AdvantageApplier} instances relevant to the provided basket evaluation.
     * <p>
     * This method iterates over all available {@link AdvantageApplierFactory} beans
     * and delegates the creation of appliers to them.
     * <p>
     * Errors during factory processing are caught and logged to ensure that
     * one faulty factory does not stop the entire valuation process.
     *
     * @param basketEvaluation The basket evaluation used to build appliers.
     * @return A list of available offer appliers.
     */
    List<AdvantageApplier> createDiscountAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        if (discountFactories != null) {
            for (AdvantageApplierFactory factory : discountFactories) {
                try {
                    Collection<AdvantageApplier> builtAppliers = factory.buildAppliers(basketEvaluation);
                    if (builtAppliers != null) {
                        appliers.addAll(builtAppliers);
                    }
                } catch (Exception e) {
                    // Log error but continue with other factories
                    throw new RuntimeException("Error building appliers from factory: " + e.getMessage(), e);
                }
            }
        }
        return appliers;
    }

    // --------------------------------------------------
    // Helper Methods for external/advanced access
    // --------------------------------------------------

    /**
     * Calculates the total price of the basket before discounts are applied.
     *
     * @param evaluation The evaluation context containing the applied offers.
     * @return The total TTC price before discounts.
     */
    public BigDecimal calculateTotalHorsDiscount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (OfferApplication app : evaluation.getOffers()) {
            if (app.getAmount() != null) {
                total = total.add(app.getAmount().amountIncludingTax);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the total amount of discounts to be applied.
     *
     * @param evaluation The evaluation context containing the applied discounts.
     * @return The total amount of money saved (positive value).
     */
    public BigDecimal calculateTotalDiscount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication app : evaluation.getAdvantages()) {
            if (app instanceof DiscountApplication) {
                DiscountApplication discount = (DiscountApplication) app;
                if (discount.getDiscountAmount() != null) {
                    total = total.add(discount.getDiscountAmount().amountIncludingTax.abs());
                }
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the final total price to be paid.
     * Calculation: Total Hors Discount - Total Discount.
     *
     * @param evaluation The evaluation context.
     * @return The final net price to pay.
     */
    public BigDecimal calculateRealTotal(BasketEvaluation evaluation) {
        return calculateTotalHorsDiscount(evaluation).subtract(calculateTotalDiscount(evaluation));
    }

    // --------------------------------------------------
    // Inner Classes
    // --------------------------------------------------

    /**
     * Inner class responsible for sorting {@link OfferApplier} instances.
     * <p>
     * This class encapsulates the sorting logic, allowing it to be modified
     * without affecting the main engine logic.
     * <p>
     * Sorting is based on efficiency score in descending order
     * (Higher score = processed first).
     */
    public static class OfferApplierEvaluator {

        /**
         * Sorts the provided list of appliers in place.
         *
         * @param appliers The list of appliers to sort.
         */
        public void sort(List<OfferApplier> appliers, BasketEvaluation evaluation) {
            // Sort by efficiency score descending
            // High score = Most Efficient / Highest Priority
            appliers.sort(Comparator.comparingDouble(OfferApplier::getEfficiencyScore).reversed());
        }
    }

    /**
     * Inner class responsible for sorting {@link AdvantageApplier} instances.
     * <p>
     * This class encapsulates the sorting logic, allowing it to be modified
     * without affecting the main engine logic.
     * <p>
     * Sorting is based on efficiency score in descending order
     * (Higher score = processed first).
     */
    public static class DiscountApplierEvaluator {

        /**
         * Sorts the provided list of appliers in place.
         *
         * @param appliers The list of appliers to sort.
         */
        public void sort(List<AdvantageApplier> appliers) {
            // Sort by efficiency score descending
            // High score = Most Efficient / Highest Priority
            appliers.sort(Comparator.comparingDouble(AdvantageApplier::getEfficiencyScore).reversed());
        }
    }

}