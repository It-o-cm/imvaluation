package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Factory for creating {@link FreeDeliveryThresholdApplier} instances for Free Delivery offers.
 * <p>
 * Offers are retrieved from the database where the type is "FREE_DELIVERY_THRESHOLD".
 * The JSON specification must contain a list of "tiers":
 * <ul>
 *   <li>"tiers": List of objects defining discount levels.
 *     <ul>
 *       <li>"threshold": Minimum merchandise amount required to reach this tier.</li>
 *       <li>"value": The discount value (percentage or fixed amount).</li>
 *       <li>"type": "PERCENTAGE" or "FIXED_AMOUNT".</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * This discount checks the total price of all {@link ProductAwareOfferApplication} in the basket.
 * It finds the highest matching tier to calculate a discount, which is then capped at the
 * actual price of the delivery offer.
 */
@ApplicationScoped
public class FreeDeliveryThresholdDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * ObjectMapper instance used for JSON processing.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * JSON Schema definition for validating Free Delivery Threshold specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Free Delivery Threshold Offer Specification",
      "description": "Defines the rules for free delivery threshold discounts.",
      "type": "object",
      "required": [
        "tiers"
      ],
      "properties": {
        "tiers": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "required": [
              "threshold",
              "value",
              "type"
            ],
            "properties": {
              "threshold": {
                "type": "number",
                "description": "Minimum basket amount required."
              },
              "value": {
                "type": "number",
                "description": "The discount value."
              },
              "type": {
                "type": "string",
                "enum": ["PERCENTAGE", "FIXED_AMOUNT"],
                "description": "The type of discount."
              }
            }
          }
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Enumeration to type the discount calculation mode.
     */
    public enum DiscountType {
        /** Discount calculated as a percentage of the delivery cost. */
        PERCENTAGE,
        /** Discount calculated as a fixed monetary amount. */
        FIXED_AMOUNT
    }

    /**
     * Internal class representing a discount configuration tier.
     */
    public static class DiscountTier {
        private final BigDecimal threshold;
        private final double value;
        private final DiscountType type;

        /**
         * Constructs a new DiscountTier.
         *
         * @param threshold The minimum basket amount required.
         * @param value     The value of the discount (percentage or amount).
         * @param type      The type of discount.
         */
        public DiscountTier(BigDecimal threshold, double value, DiscountType type) {
            this.threshold = threshold;
            this.value = value;
            this.type = type;
        }

        /**
         * Gets the minimum basket amount required to qualify for this tier.
         *
         * @return The threshold amount.
         */
        public BigDecimal getThreshold() {
            return threshold;
        }

        /**
         * Gets the value of the discount.
         *
         * @return The discount value.
         */
        public double getValue() {
            return value;
        }

        /**
         * Gets the calculation type for this discount.
         *
         * @return The discount type.
         */
        public DiscountType getType() {
            return type;
        }
    }

    /**
     * Builds a collection of {@link FreeDeliveryThresholdApplier} instances based on the basket.
     *
     * @param basketEvaluation The basket evaluation (contains the store code).
     * @return A collection of {@link FreeDeliveryThresholdApplier}.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create Free Delivery Threshold appliers without a valid basket.");
        Store store = basketEvaluation.getStore();
        List<Offer> offers = Offer.findByStoreAndType(store, "FREE_DELIVERY_THRESHOLD");
        for (Offer offer : offers) {
            processOffer(offer, appliers);
        }
        return appliers;
    }

    /**
     * Processes a single offer and adds a corresponding applier if valid.
     *
     * @param offer    The offer to process.
     * @param appliers The list to which the applier will be added.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            JsonNode tiersNode = spec.get("tiers");
            List<DiscountTier> tiers = new ArrayList<>();
            // Parse the list of tiers from the specification
            for (JsonNode tierNode : tiersNode) {
                BigDecimal threshold = tierNode.get("threshold").decimalValue();
                double value = tierNode.get("value").asDouble();
                String typeStr = tierNode.get("type").asText();
                DiscountType type = DiscountType.valueOf(typeStr);
                tiers.add(new DiscountTier(threshold, value, type));
            }
            // Sort tiers in descending order to easily find the best applicable offer
            tiers.sort(Comparator.comparing(DiscountTier::getThreshold).reversed());
            // Pass the code and the list of tiers to the Applier
            appliers.add(new FreeDeliveryThresholdApplier(offer.code, tiers));
        });
    }

    /**
     * Specific applier for the Free Delivery Threshold discount.
     */
    public static class FreeDeliveryThresholdApplier implements AdvantageApplier {

        private static final double AFTER_ALL_STANDARD_DISCOUNTS = -1.0;
        private final String code;
        private final List<DiscountTier> tiers;

        /**
         * Constructs a new FreeDeliveryThresholdApplier.
         *
         * @param code  The offer code.
         * @param tiers The list of configured discount tiers, sorted descending.
         */
        public FreeDeliveryThresholdApplier(String code, List<DiscountTier> tiers) {
            this.code = code;
            this.tiers = tiers;
        }

        /**
         * Determines if this applier is applicable to the given offer applier.
         * This applier is only applicable to Delivery offers.
         *
         * @param offerApplier The offer applier to check.
         * @return {@code true} if the offer applier is a Delivery Offer, {@code false} otherwise.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return offerApplier instanceof DeliveryOfferFactory.DeliveryOfferApplier;
        }

        /**
         * Applies the Free Delivery Threshold logic to the basket evaluation.
         * <p>
         * This method performs the following steps:
         * <ol>
         *   <li>Calculates the total merchandise price (excluding delivery).</li>
         *   <li>Identifies the highest applicable discount tier based on the total.</li>
         *   <li>Calculates the discount amount based on the tier type (Percentage or Fixed).</li>
         *   <li>Caps the discount amount so it does not exceed the actual delivery cost.</li>
         *   <li>Creates a {@link FreeDeliveryThresholdApplication} if applicable.</li>
         * </ol>
         *
         * @param evaluation The evaluation context containing the already calculated offers.
         * @return A collection of {@link FreeDeliveryThresholdApplication} representing the applied discounts.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            // 1. Calculate total merchandise price
            // We sum ONLY ProductAwareOfferApplication (Products, Bundles, etc.)
            // We exclude DeliveryApplication (Service) and DepositBasketApplication (Service)
            BigDecimal merchandiseTotal = getMerchandiseTotal(evaluation);
            OfferApplication deliveryOffer = getDeliveryOfferApplication(evaluation);
            DiscountTier bestTier = getBestDiscountTier(merchandiseTotal);
            // 3. If a tier is found and a delivery offer exists, calculate the discount
            if (bestTier != null && deliveryOffer != null) {
                AdvantageApplication discountApplication = getDeliveryDiscountApplication(deliveryOffer, bestTier);
                if (discountApplication != null) applications.add(discountApplication);
            }
            return applications;
        }

        /**
         * Calculates the delivery discount application based on the best tier.
         * @param deliveryOffer delivery offer application to be discounted.
         * @param bestTier The best matching discount tier.
         * @return A {@link FreeDeliveryThresholdApplication} representing the discount, or null if no discount applies.
         */
        AdvantageApplication getDeliveryDiscountApplication(OfferApplication deliveryOffer, DiscountTier bestTier) {
            AmountEvaluation deliveryPrice = deliveryOffer.getAmount();
            if (deliveryPrice.amountIncludingTax.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deliveryCost = deliveryPrice.amountIncludingTax;
                BigDecimal calculatedDiscount = computeDiscount(bestTier, deliveryCost);
                // 4. Cap the discount: The discount cannot exceed the delivery cost
                if (calculatedDiscount.compareTo(deliveryCost) > 0) {
                    calculatedDiscount = deliveryCost;
                }
                // Round to 2 decimal places (currency)
                calculatedDiscount = calculatedDiscount.setScale(2, RoundingMode.HALF_UP);
                // Calculate the corresponding Excluding Tax price
                // Price Excluding Tax = Price Including Tax / (1 + VAT)
                BigDecimal multiplier = BigDecimal.ONE.add(deliveryPrice.vatRate);
                BigDecimal refundHt = calculatedDiscount.divide(multiplier, 2, RoundingMode.HALF_UP);
                AmountEvaluation refundAmount = new AmountEvaluation(refundHt, calculatedDiscount, deliveryPrice.vatRate);
                return new FreeDeliveryThresholdApplication(this.code, deliveryOffer, refundAmount);
            }
            return null;
        }

        /**
         * Computes the discount amount based on the best tier and delivery cost.
         *
         * @param bestTier     The best matching discount tier.
         * @param deliveryCost The total delivery cost.
         * @return The calculated discount amount.
         */
        BigDecimal computeDiscount(DiscountTier bestTier, BigDecimal deliveryCost) {
            // Example: value = 50 for 50%
            // Example: value = 5.00 for 5€ discount
            // Calculate discount based on the type defined in the tier
            return switch (bestTier.getType()) {
                case PERCENTAGE -> {
                    // Example: value = 50 for 50%
                    BigDecimal percent = BigDecimal.valueOf(bestTier.getValue())
                            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                    yield deliveryCost.multiply(percent);
                }
                case FIXED_AMOUNT ->
                    // Example: value = 5.00 for 5€ discount
                        BigDecimal.valueOf(bestTier.getValue());
            };
        }

        /**
         * Finds the best matching discount tier for the given merchandise total.
         *
         * @param merchandiseTotal The total merchandise amount.
         * @return The best matching {@link DiscountTier}, or null if none match.
         */
        private DiscountTier getBestDiscountTier(BigDecimal merchandiseTotal) {
            DiscountTier bestTier = null;
            for (DiscountTier tier : tiers) {
                if (merchandiseTotal.compareTo(tier.getThreshold()) >= 0) {
                    bestTier = tier;
                    break; // Since sorted descending, the first match is the best one
                }
            }
            return bestTier;
        }

        /**
         * Retrieves the Delivery OfferApplication from the basket evaluation.
         *
         * @param evaluation The basket evaluation containing applied offers.
         * @return The Delivery {@link OfferApplication}, or null if none found.
         */
        OfferApplication getDeliveryOfferApplication(BasketEvaluation evaluation) {
            OfferApplication deliveryOffer = null;
            if (evaluation.getOffers() != null) {
                for (OfferApplication app : evaluation.getOffers()) {
                    // Check for Delivery Offer to handle potential refund separately
                    if (app.getClass().getSimpleName().equals("DeliveryApplication")) {
                        deliveryOffer = app;
                    }
                }
            }
            return deliveryOffer;
        }

        /**
         * Calculates the total merchandise price from the basket evaluation.
         * Only includes prices from ProductAwareOfferApplication instances.
         *
         * @param evaluation The basket evaluation containing applied offers.
         * @return The total merchandise price as BigDecimal.
         */
        BigDecimal getMerchandiseTotal(BasketEvaluation evaluation) {
            BigDecimal merchandiseTotal = BigDecimal.ZERO;
            if (evaluation.getOffers() != null) {
                for (OfferApplication app : evaluation.getOffers()) {
                    // Check for ProductAware Offer to sum into the merchandise total
                    if (app instanceof ProductAwareOfferApplication) {
                        AmountEvaluation price = app.getAmount();
                        if (price != null) {
                            merchandiseTotal = merchandiseTotal.add(price.amountIncludingTax);
                        }
                    }
                }
            }
            return merchandiseTotal;
        }

        /**
         * Returns the efficiency score for this applier.
         * Returns a constant negative value to ensure it runs after all standard product discounts.
         *
         * @return The efficiency score (-1.0).
         */
        @Override
        public double getEfficiencyScore() {
            return AFTER_ALL_STANDARD_DISCOUNTS;
        }
    }

    /**
     * Represents the discount application that refunds the delivery cost.
     */
    public static class FreeDeliveryThresholdApplication implements DiscountApplication {

        private final String code;
        private final OfferApplication deliveryOffer;
        private final AmountEvaluation refundAmount;

        /**
         * Constructs a new FreeDeliveryThresholdApplication.
         *
         * @param code          The offer code.
         * @param deliveryOffer The delivery offer application this discount targets.
         * @param refundAmount  The calculated refund amount (positive value).
         */
        public FreeDeliveryThresholdApplication(String code, OfferApplication deliveryOffer, AmountEvaluation refundAmount) {
            this.code = code;
            this.deliveryOffer = deliveryOffer;
            this.refundAmount = refundAmount;
        }

        /**
         * Returns a string representation of the offer type associated with this discount application.
         *
         * @return A descriptive string of the offer type.
         */
        public String getType() {
            return "Free Delivery Threshold Discount: " + this.code;
        }

        /**
         * Returns the Delivery Offer that this discount is targeting.
         *
         * @return The {@link OfferApplication} instance.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return this.deliveryOffer;
        }

        /**
         * Returns the refund amount as a positive PriceEvaluation.
         *
         * @return The refund amount.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return this.refundAmount;
        }

    }
}