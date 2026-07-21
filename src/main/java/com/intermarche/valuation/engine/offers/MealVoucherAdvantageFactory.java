package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Factory responsible for creating {@link MealVoucherAdvantageApplier} instances.
 * <p>
 * This CDI bean implements {@link AdvantageApplierFactory} to integrate with the valuation engine.
 * It scans the database for {@link Offer} entities of type {@code "MEAL_VOUCHER"} configured
 * for the current store (or its groups) and instantiates the corresponding appliers.
 * </p>
 * <p>
 * <b>Configuration:</b><br>
 * The Offer specification must be a JSON object containing:
 * <ul>
 *   <li>{@code flag}: The product family flag required for eligibility (e.g., "FOOD").</li>
 *   <li>{@code threshold}: The maximum amount payable by meal vouchers (BigDecimal).</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
public class MealVoucherAdvantageFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * ObjectMapper instance used to parse the JSON specification from the Offer entity.
     */
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Meal Voucher Offer Specification",
      "description": "Defines the eligibility rules for the Meal Voucher advantage.",
      "type": "object",
      "required": [
        "flag",
        "threshold"
      ],
      "properties": {
        "flag": {
          "type": "string",
          "description": "The product family flag required for a product to be eligible (e.g., 'FOOD').",
          "minLength": 1
        },
        "threshold": {
          "type": "number",
          "description": "The maximum amount (cap) that can be paid using meal vouchers.",
          "exclusiveMinimum": 0
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Builds a collection of {@link AdvantageApplier} instances based on the provided basket evaluation context.
     * <p>
     * This method retrieves active "MEAL_VOUCHER" offers for the store and its hierarchy,
     * parses their specifications, and creates an applier for each valid configuration.
     * </p>
     *
     * @param basketEvaluation the evaluation context containing store and group information.
     * @return a collection of {@link MealVoucherAdvantageApplier} instances.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Store store = basketEvaluation.getStore();
        Collection<Offer> offers = getOffers(basketEvaluation, "MEAL_VOUCHER");
        for (Offer offer : offers) {
            processOffer(offer, appliers);
        }
        return appliers;
    }

    /**
     * Processes a single Offer entity to extract configuration and create an applier.
     * <p>
     * Parses the JSON specification to find the eligibility flag and the payment threshold.
     * If both are present and valid, a new {@link MealVoucherAdvantageApplier} is added to the list.
     * </p>
     *
     * @param offer    the Offer entity containing the configuration.
     * @param appliers the list to which the created applier will be added.
     * @throws JsonProcessingException if the offer specification is not valid JSON.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec)->{
            // Read the eligibility flag (e.g., "FOOD", "GROCERY")
            String flag = flag = spec.get("flag").asText();
            // Read the threshold (cap)
            BigDecimal threshold = threshold = spec.get("threshold").decimalValue();
            appliers.add(new MealVoucherAdvantageApplier(offer.code, flag, threshold));
        });
    }

    /**
     * Concrete applier for calculating the Meal Voucher payable amount.
     * <p>
     * This applier evaluates the entire basket (post-offer application) to identify
     * products eligible for meal vouchers based on a specific {@link ProductFamily} flag.
     * It sums the prices of these products and applies a configured threshold cap.
     * </p>
     */
    public static class MealVoucherAdvantageApplier implements AdvantageApplier {

        /**
         * The unique code identifying the Offer configuration.
         */
        private final String offerCode;

        /**
         * The flag (e.g., "FOOD") that a product must possess in its family hierarchy to be eligible.
         */
        private final String eligibilityFlag;

        /**
         * The maximum amount that can be paid using meal vouchers for this offer.
         */
        private final BigDecimal threshold;

        /**
         * Constructs a new Meal Voucher Applier.
         *
         * @param offerCode        the code of the offer defining this advantage.
         * @param eligibilityFlag  the product family flag required for eligibility.
         * @param threshold        the maximum payable amount (cap).
         */
        public MealVoucherAdvantageApplier(String offerCode, String eligibilityFlag, BigDecimal threshold) {
            this.offerCode = offerCode;
            this.eligibilityFlag = eligibilityFlag;
            this.threshold = threshold;
        }

        /**
         * Determines if this applier is applicable to a specific offer applier.
         * <p>
         * Meal Voucher calculation is a global operation that happens after all product offers
         * have been applied. It does not modify individual offer prices directly.
         * Therefore, this method always returns {@code false}.
         * </p>
         *
         * @param offerApplier the offer applier to check.
         * @return {@code false}, as this advantage is not a line-item discount.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return false;
        }

        /**
         * Applies the Meal Voucher logic to the basket evaluation.
         * <p>
         * This method iterates through all applied {@link OfferApplication}s in the evaluation.
         * For products within those applications that match the {@code eligibilityFlag},
         * it accumulates their TTC (Tax Inclusive) prices.
         * The final payable amount is the lesser of the accumulated total and the {@code threshold}.
         * </p>
         *
         * @param evaluation the basket evaluation context containing applied offers and prices.
         * @return a collection containing a single {@link MealVoucherAdvantageApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            BigDecimal totalEligibleAmount = BigDecimal.ZERO;

            // Iterate over all offers that have been applied to the basket
            for (OfferApplication offerApp : evaluation.getOffers()) {

                // We can only verify product eligibility if the offer implements ProductAwareOfferApplication
                if (offerApp instanceof ProductAwareOfferApplication) {
                    ProductAwareOfferApplication productApp = (ProductAwareOfferApplication) offerApp;

                    // Iterate through items associated with this application to identify products
                    for (Basket.Item item : offerApp.getItems()) {
                        Product product = Product.findByEan(item.produceEan);

                        // Check if the product is eligible by verifying the flag in its family hierarchy
                        if (product != null && ProductFamily.productHasFlag(product, this.eligibilityFlag)) {

                            // Retrieve the TTC amount specific to this product within the context of this offer
                            AmountEvaluation productAmount = productApp.getProductAmount(product);

                            if (productAmount != null) {
                                totalEligibleAmount = totalEligibleAmount.add(productAmount.amountIncludingTax);
                            }
                        }
                    }
                }
                // If the offer is not "ProductAware", we cannot safely determine eligibility for specific items.
            }

            // Apply the cap (Threshold)
            BigDecimal payableAmount = totalEligibleAmount.min(this.threshold);

            // Standard monetary rounding (2 decimal places, half-up)
            payableAmount = payableAmount.setScale(2, RoundingMode.HALF_UP);
            totalEligibleAmount = totalEligibleAmount.setScale(2, RoundingMode.HALF_UP);

            return List.of(new MealVoucherAdvantageApplication(
                    this.offerCode,
                    totalEligibleAmount,
                    payableAmount,
                    this.threshold
            ));
        }

        /**
         * Returns the efficiency score of this applier.
         * <p>
         * As this advantage does not consume basket items (it is purely informational/calculative
         * applied globally), the efficiency score is not relevant for sorting priority against
         * standard discounts. Returns a constant {@code 0.0}.
         * </p>
         *
         * @return {@code 0.0}.
         */
        @Override
        public double getEfficiencyScore() {
            return -2.0;
        }
    }

    /**
     * Represents the result of applying a Meal Voucher advantage.
     * <p>
     * This class implements {@link AdvantageApplication} but deliberately does <b>not</b>
     * implement {@link DiscountApplication}, as a Meal Voucher is a payment method restriction
     * or validation, not a direct price reduction from the store.
     * </p>
     */
    public static class MealVoucherAdvantageApplication implements AdvantageApplication {

        /**
         * The unique code of the offer that generated this application.
         */
        private final String offerCode;

        /**
         * The total calculated amount of products in the basket that are eligible for meal vouchers
         * (before applying the threshold cap).
         */
        private final BigDecimal totalEligibleAmount;

        /**
         * The constant type identifier for this advantage.
         */
        private final String type = "MEAL_VOUCHER";

        /**
         * The final amount that can actually be paid using meal vouchers.
         * This is equal to {@code totalEligibleAmount} capped at the {@code threshold}.
         */
        private final BigDecimal payableAmount;

        /**
         * The maximum amount configured as the limit for this offer.
         */
        private final BigDecimal threshold;

        /**
         * Constructs a new Meal Voucher Advantage Application.
         *
         * @param offerCode             the code of the originating offer.
         * @param totalEligibleAmount   the total sum of eligible products.
         * @param payableAmount         the capped amount payable by vouchers.
         * @param threshold             the configured limit.
         */
        public MealVoucherAdvantageApplication(String offerCode, BigDecimal totalEligibleAmount, BigDecimal payableAmount, BigDecimal threshold) {
            this.offerCode = offerCode;
            this.totalEligibleAmount = totalEligibleAmount;
            this.payableAmount = payableAmount;
            this.threshold = threshold;
        }

        /**
         * Retrieves the associated offer application.
         * <p>
         * Since this advantage applies to the basket globally rather than modifying a specific
         * line item, this method returns {@code null}.
         * </p>
         *
         * @return {@code null}.
         */
        @Override
        public OfferApplication getOfferApplication() {
            return null;
        }

        /**
         * Returns the type of this advantage application.
         *
         * @return the string "MEAL_VOUCHER".
         */
        @Override
        @JsonIgnore
        public String getOffer() {
            return this.type;
        }

        /**
         * Gets the offer code associated with this application.
         *
         * @return the offer code.
         */
        public String getOfferCode() {
            return offerCode;
        }

        /**
         * Gets the total amount of eligible products found in the basket.
         *
         * @return the total eligible amount.
         */
        public BigDecimal getTotalEligibleAmount() {
            return totalEligibleAmount;
        }

        /**
         * Gets the type of the advantage.
         *
         * @return the type string.
         */
        public String getType() {
            return type;
        }

        /**
         * Gets the configured threshold (maximum payable amount).
         *
         * @return the threshold.
         */
        public BigDecimal getThreshold() {
            return threshold;
        }
    }
}