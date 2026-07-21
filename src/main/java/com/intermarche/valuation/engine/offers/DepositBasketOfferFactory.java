package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Factory for creating Appliers for "DEPOSIT_BASKET" type offers.
 * <p>
 * Offers are retrieved from the database where the type is "DEPOSIT_BASKET".
 * <b>Constraint:</b> Only one DEPOSIT_BASKET offer is allowed per store.
 * <p>
 * The offer is conditional: it is only applied if the basket instructions contain
 * the keyword "Deposit basket".
 * <p>
 * The JSON specification must contain:
 * <ul>
 *   <li>"basketVolume": The maximum volume capacity of one deposit basket (in Liters).</li>
 *   <li>"basketPrice": The fixed price (TTC) for renting one basket.</li>
 *   <li>"vatRate": The VAT rate for the basket rental.</li>
 * </ul>
 * <p>
 * Logic:
 * 1. Calculates the total volume of items in the basket using {@link Product#referenceVolume}.
 * 2. Determines the number of baskets needed: Ceil(TotalVolume / BasketVolume).
 * 3. Applies price: BasketCount * BasketPrice.
 */
@ApplicationScoped
public class DepositBasketOfferFactory implements OfferApplierFactory, EngineTrait {

    /**
     * JSON Schema definition for validating Deposit Basket specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Deposit Basket Offer Specification",
      "description": "Defines the configuration for a deposit basket offer.",
      "type": "object",
      "required": [
        "basketVolume",
        "basketPrice",
        "vatRate"
      ],
      "properties": {
        "basketVolume": {
          "type": "number",
          "description": "The maximum volume capacity of one deposit basket (in Liters).",
          "exclusiveMinimum": 0
        },
        "basketPrice": {
          "type": "number",
          "description": "The fixed price (TTC) for renting one basket.",
          "exclusiveMinimum": 0
        },
        "vatRate": {
          "type": "number",
          "description": "The VAT rate for the basket rental.",
          "minimum": 0
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Builds a collection of offer appliers based on the provided evaluation context.
     * <p>
     * Only creates an applier if:
     * <ul>
     *   <li>The basket instructions contain "Deposit basket".</li>
     *   <li><b>Exactly one</b> DEPOSIT_BASKET offer is defined for the store.</li>
     * </ul>
     *
     * @param evaluation The evaluation context containing the basket and items.
     * @return A collection containing at most one {@link DepositBasketOfferApplier}.
     * @throws IllegalStateException if more than one DEPOSIT_BASKET offer is found for the store.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation evaluation) {
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(evaluation, "Cannot create deposit basket appliers without a valid basket context.");
        // Check for "Deposit basket" instruction (case-insensitive)
        boolean hasDepositInstruction = basket.instructions != null && basket.instructions.stream()
                .anyMatch(instr -> "Deposit basket".equalsIgnoreCase(instr.trim()));
        if (!hasDepositInstruction) {
            return appliers;
        }
        Store store = evaluation.getStore();
        // Retrieve all "DEPOSIT_BASKET" type offers for this store
        List<Offer> offers = Offer.findByStoreAndType(store, "DEPOSIT_BASKET");
        // Constraint: Only one deposit basket offer allowed per store
        if (offers.size() > 1) {
            throw new IllegalStateException(String.format(
                    "Configuration Error: Multiple DEPOSIT_BASKET offers found for store '%s'. Expected 1, found %d.",
                    store.code, offers.size()
            ));
        }
        // Since we checked size > 1, we check for existence (size == 1)
        if (!offers.isEmpty()) {
            Offer offer = offers.get(0); // Directly access the unique offer
            processOffer(offer, appliers);
        }
        return appliers;
    }

    /**
     * Processes a single DEPOSIT_BASKET offer and creates the corresponding applier.
     *
     * @param offer    The offer to process.
     * @param appliers The list to which the created applier will be added.
     */
    private void processOffer(Offer offer, List<OfferApplier> appliers) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            double basketVolume = spec.get("basketVolume").asDouble(); // in Liters
            BigDecimal basketPrice = spec.get("basketPrice").decimalValue(); // TTC
            BigDecimal vatRate = spec.get("vatRate").decimalValue();

            appliers.add(new DepositBasketOfferApplier(offer.code, basketVolume, basketPrice, vatRate));
        });
    }

    /**
     * Specific applier for a single Deposit Basket Offer.
     */
    public static class DepositBasketOfferApplier extends OfferApplier implements EngineTrait {

        private final String offerCode;
        private final double basketVolumeCapacity; // Capacity in Liters
        private final BigDecimal basketPriceTTC;   // Price per basket
        private final BigDecimal vatRate;

        /**
         * Constructs a new DepositBasketOfferApplier.
         *
         * @param offerCode          The offer code.
         * @param basketVolumeCapacity The capacity of one basket in Liters.
         * @param basketPriceTTC      The price per basket.
         * @param vatRate             The VAT rate.
         */
        public DepositBasketOfferApplier(String offerCode, double basketVolumeCapacity, BigDecimal basketPriceTTC, BigDecimal vatRate) {
            this.offerCode = offerCode;
            this.basketVolumeCapacity = basketVolumeCapacity;
            this.basketPriceTTC = basketPriceTTC;
            this.vatRate = vatRate;
        }

        /**
         * Calculates the total volume of items and applies the basket price.
         *
         * @param evaluation The evaluation context.
         * @return A collection of offer applications.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) {
            List<OfferApplication> applications = new ArrayList<>();
            // 1. Calculate Total Volume required for basket items
            // We use evaluation.getBasket() to get the items
            double totalVolumeLiters = 0.0;
            Basket basket = evaluation.getBasket();
            if (basket.items != null) {
                for (Basket.Item item : basket.items) {
                    Product product = item.getProduct();
                    totalVolumeLiters += calculateItemVolume(item, product);
                }
            }
            // 2. Determine number of baskets needed
            if (totalVolumeLiters > 0) {
                int nbBaskets = (int) Math.ceil(totalVolumeLiters / basketVolumeCapacity);
                applications.add(new DepositBasketApplication(offerCode, nbBaskets, basketPriceTTC, vatRate));
            }
            return applications;
        }

        /**
         * Calculates the volume of a specific item in Liters.
         * <p>
         * Logic:
         * <ul>
         *   <li>UNIT: Volume = Quantity * ReferenceVolume.</li>
         *   <li>WEIGHT: Volume = (Quantity / ReferenceWeight) * ReferenceVolume. (Rule of Three)</li>
         * </ul>
         *
         * @param item    The basket item.
         * @param product The product details.
         * @return Volume in Liters.
         */
        private double calculateItemVolume(Basket.Item item, Product product) {
            if (product.referenceVolume == null) {
                // Cannot calculate volume if referenceVolume is missing. Assume 0.
                return 0.0;
            }
            return product.standardQuantity(item.quantity).multiply(product.referenceVolume).doubleValue();
        }

        /**
         * Service offers usually have 0 efficiency as they are costs/add-ons, not discounts.
         *
         * @param basket The basket context.
         * @return 0.0.
         */
        @Override
        public double computeEfficiencyScore(Basket basket){
            return 0.0;
        }
    }

    /**
     * Result of applying a Deposit Basket Offer.
     */
    public static class DepositBasketApplication implements OfferApplication {

        private final String offerCode;
        private final int basketCount;
        private final BigDecimal basketPriceUnit;
        private final BigDecimal vatRate;

        /**
         * Constructs a new DepositBasketApplication.
         *
         * @param offerCode      The offer code.
         * @param basketCount    The number of baskets.
         * @param basketPriceUnit The price per unit.
         * @param vatRate        The VAT rate.
         */
        public DepositBasketApplication(String offerCode, int basketCount, BigDecimal basketPriceUnit, BigDecimal vatRate) {
            this.offerCode = offerCode;
            this.basketCount = basketCount;
            this.basketPriceUnit = basketPriceUnit;
            this.vatRate = vatRate;
        }

        /**
         * Calculates the total price for the baskets.
         *
         * @return The amount evaluation.
         */
        @Override
        public AmountEvaluation getAmount() {
            // Total TTC = Count * PricePerBasket
            BigDecimal totalTTC = basketPriceUnit.multiply(BigDecimal.valueOf(basketCount)).setScale(2, RoundingMode.HALF_UP);
            // HT = TTC / (1 + TVA)
            BigDecimal divisor = BigDecimal.ONE.add(vatRate);
            BigDecimal totalHT = totalTTC.divide(divisor, 2, RoundingMode.HALF_UP);
            return new AmountEvaluation(totalHT, totalTTC, vatRate);
        }

        /**
         * Deposit baskets are services, not physical items.
         * Returns an empty list as no items are consumed from the inventory.
         *
         * @return An empty list.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the offer code associated with this application.
         *
         * @return The offer presentation.
         */
        @Override
        public String getType() {
            return "Deposit Basket: " + basketCount + " x " + basketPriceUnit + "€";
        }
    }

}