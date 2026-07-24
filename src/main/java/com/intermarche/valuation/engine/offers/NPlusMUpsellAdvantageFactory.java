package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.*;

/**
 * Factory for creating {@link NPlusMUpsellAdvantageApplier} instances.
 * <p>
 * This advantage analyzes items remaining in basket (unpicked items) to identify
 * opportunities to complete N+M offers. It does not apply a direct discount but
 * suggests which products should be added to trigger an offer.
 * <p>
 * It relies on "N+M" type offers defined in database.
 */
@ApplicationScoped
public class NPlusMUpsellAdvantageFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "N+M";

    /**
     * JSON Schema definition for validating N+M specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "N Plus M Offer Specification",
      "description": "Defines the rules for N+M type offers.",
      "type": "object",
      "required": [
        "targetEans",
        "quantityToPay",
        "discountedQuantity",
        "selectionStrategy",
        "discountType",
        "discountValue"
      ],
      "properties": {
        "targetEans": {
          "oneOf": [
            { "type": "string" },
            { "type": "array", "items": { "type": "string" } }
          ],
          "description": "List of EANs included in the offer.",
          "x-widget": "ean-list",
          "x-label": "Eligible products"
        },
        "quantityToPay": {
          "type": "integer",
          "description": "The quantity N to pay for.",
          "minimum": 1,
          "x-widget": "quantity",
          "x-label": "Quantity to pay (N)"
        },
        "discountedQuantity": {
          "type": "integer",
          "description": "The quantity M to discount.",
          "minimum": 0,
          "x-widget": "quantity",
          "x-label": "Discounted quantity (M)"
        },
        "selectionStrategy": {
          "type": "string",
          "enum": ["CHEAPEST", "MOST_EXPENSIVE"],
          "description": "Strategy to select items for discount.",
          "x-label": "Selection strategy"
        },
        "discountType": {
          "type": "string",
          "enum": ["PERCENTAGE", "FIXED_AMOUNT"],
          "description": "Type of discount calculation.",
          "x-label": "Discount type"
        },
        "discountValue": {
          "type": "number",
          "description": "The discount value.",
          "x-widget": "discount-value",
          "x-label": "Discount value",
          "x-unit-from": "discountType"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return The "N+M" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Builds a collection of {@link AdvantageApplier} instances based on the basket evaluation.
     *
     * @param basketEvaluation The basket evaluation context.
     * @return A collection of advantage appliers.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Store store = basketEvaluation.getStore();

        // Retrieve all N+M offers for this store (or its groups)
        Collection<Offer> offers = getOffers(basketEvaluation, "N+M");

        // Process offers and create appliers
        for (Offer offer : offers) {
            processOffer(offer, appliers, store);
        }

        return appliers;
    }

    /**
     * Processes a single offer and adds a corresponding applier if valid.
     *
     * @param offer    The offer to process.
     * @param appliers The list to which the created applier will be added.
     * @param store    The store context.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            int quantityToPay = spec.get("quantityToPay").asInt();
            int discountedQuantity = spec.get("discountedQuantity").asInt();

            Set<String> targetEans = new HashSet<>();
            JsonNode eansNode = spec.get("targetEans");

            if (eansNode.isTextual()) {
                targetEans.add(eansNode.asText());
            } else { // Array
                for (JsonNode node : eansNode) {
                    targetEans.add(node.asText());
                }
            }
            NPlusMOfferConfig config = new NPlusMOfferConfig(offer.code, targetEans, quantityToPay, discountedQuantity);
            appliers.add(new NPlusMUpsellAdvantageApplier(config, store));
        });
    }

    /**
     * Internal class holding the parsed configuration of an N+M offer.
     */
    static class NPlusMOfferConfig {
        final String offerCode;
        final Set<String> targetEans;
        final int quantityToPay;
        final int discountedQuantity;

        /**
         * Constructs a new NPlusMOfferConfig.
         *
         * @param offerCode          The offer code.
         * @param targetEans         The set of target EANs.
         * @param quantityToPay      The quantity N to pay.
         * @param discountedQuantity The quantity M to discount.
         */
        NPlusMOfferConfig(String offerCode, Set<String> targetEans, int quantityToPay, int discountedQuantity) {
            this.offerCode = offerCode;
            this.targetEans = targetEans;
            this.quantityToPay = quantityToPay;
            this.discountedQuantity = discountedQuantity;
        }

        /**
         * Returns the total size of the bundle (N + M).
         *
         * @return The bundle size.
         */
        int getBundleSize() {
            return quantityToPay + discountedQuantity;
        }
    }

    /**
     * Applier that checks remaining items in basket for N+M completion opportunities
     * for a specific configuration.
     */
    public static class NPlusMUpsellAdvantageApplier implements AdvantageApplier, EngineTrait {

        private final NPlusMOfferConfig config;
        private final Store store;

        /**
         * Constructs a new N+M Upsell Applier for a specific configuration.
         *
         * @param config The offer configuration.
         * @param store  The store context.
         */
        public NPlusMUpsellAdvantageApplier(NPlusMOfferConfig config, Store store) {
            this.config = config;
            this.store = store;
        }

        /**
         * Determines if this applier is applicable to the given offer applier.
         *
         * @param offerApplier The offer applier context.
         * @return false always, as this is an independent suggestion mechanism.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            // This is an independent suggestion mechanism, not tied to a specific existing applier.
            return false;
        }

        /**
         * Analyzes basket evaluation to find missing items for N+M offers.
         * <p>
         * It iterates through configured offers, checks quantity of target products
         * remaining in evaluation map, and calculates deficit to reach next bundle.
         *
         * @param evaluation The basket evaluation context.
         * @return A collection of {@link NPlusMUpsellAdvantageApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            Map<String, Basket.Item> remainingItems = evaluation.getAvailableToUpcell();
            UpsellSuggestion suggestion = calculateUpsell(remainingItems);
            if (suggestion != null) {
                applications.add(new NPlusMUpsellAdvantageApplication(config.offerCode, suggestion));
            }
            return applications;
        }

        /**
         * Calculates suggestion for the specific offer configuration.
         *
         * @param remainingItems The map of items still in basket.
         * @return An UpsellSuggestion object, or null if no items are needed.
         */
        private UpsellSuggestion calculateUpsell(Map<String, Basket.Item> remainingItems) {
            int bundleSize = config.getBundleSize();
            // 1. Sum up current quantities for all target EANs in remaining pool
            double totalQty = 0.0;
            for (String ean : config.targetEans) {
                Basket.Item item = remainingItems.get(ean);
                if (item != null) {
                    totalQty += item.quantity;
                }
            }
            // 2. Calculate how many full bundles we already have in remaining pool
            int targetBundles = (int) Math.ceil(totalQty / bundleSize);
            double neededQty = (targetBundles * bundleSize) - totalQty;
            // If we are extremely close (due to rounding), or exact, skip
            if (neededQty < 0.001) {
                return null;
            }
            // 4. Determine which product to suggest.
            // Strategy: Suggest buying the cheapest available product to complete the deal.
            String suggestedEan = findCheapestTargetEan();
            return new UpsellSuggestion(suggestedEan, neededQty, config.offerCode);
        }

        /**
         * Finds the cheapest product among the target EANs to suggest as best buy.
         *
         * @return The EAN code of the cheapest product.
         */
        String findCheapestTargetEan() {
            String cheapestEan = null;
            BigDecimal minPrice = BigDecimal.valueOf(Double.MAX_VALUE);

            for (String ean : config.targetEans) {
                Product product = Product.findByEan(ean);
                if (product != null) {
                    Price price = Price.findActivePriceAtDate(product.id, store.id, DateTimeProvider.now(), PriceUsage.DEFAULT);
                    if (price != null) {
                        if (price.priceExcludingTax.compareTo(minPrice) < 0) {
                            minPrice = price.priceExcludingTax;
                            cheapestEan = ean;
                        }
                    }
                }
            }
            // Fallback if no prices found: return the first EAN
            if (cheapestEan == null) {
                return config.targetEans.iterator().next();
            }
            return cheapestEan;
        }

        /**
         * Returns the efficiency score.
         *
         * @return A negative efficiency score.
         */
        @Override
        public double getEfficiencyScore() {
            // Suggestion advantages usually run last
            return -100.0;
        }
    }

    /**
     * Internal data structure representing suggestion details.
     * <p>
     * Fields are PUBLIC to allow Jackson serialization.
     */
    static class UpsellSuggestion {
        /**
         * The suggested product EAN.
         */
        public final String ean;

        /**
         * The quantity needed.
         */
        public final double quantity;

        /**
         * The offer code associated with the suggestion.
         */
        public final String offerCode;

        /**
         * Constructs the suggestion.
         *
         * @param ean       The product EAN.
         * @param quantity  The quantity needed.
         * @param offerCode The offer code.
         */
        UpsellSuggestion(String ean, double quantity, String offerCode) {
            this.ean = ean;
            this.quantity = quantity;
            this.offerCode = offerCode;
        }
    }

    /**
     * Result object representing an N+M Upsell opportunity.
     * <p>
     * Implements {@link AdvantageApplication} but not {@link DiscountApplication} as it does
     * not apply a financial reduction directly, but suggests a purchase path.
     */
    public static class NPlusMUpsellAdvantageApplication implements AdvantageApplication {

        private final String offerCode;
        private final UpsellSuggestion suggestion;

        /**
         * Constructs the application.
         *
         * @param offerCode  The offer code.
         * @param suggestion The suggestion details.
         */
        public NPlusMUpsellAdvantageApplication(String offerCode, UpsellSuggestion suggestion) {
            this.offerCode = offerCode;
            this.suggestion = suggestion;
        }

        /**
         * Returns the offer representation.
         * <p>
         * Overrides default to return type directly since there is no parent application.
         *
         * @return The type description.
         */
        @Override
        @JsonIgnore
        public String getOffer() {
            return getType();
        }

        /**
         * Returns the type of advantage.
         *
         * @return A descriptive string.
         */
        public String getType() {
            return String.format("Upsell N+M: %s (Need %.2f of %s)", offerCode, suggestion.quantity, suggestion.ean);
        }

        /**
         * Returns the offer application targeted by this advantage.
         * <p>
         * Since this is a suggestion for a *potential* application, this returns null.
         *
         * @return null.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return null;
        }

        /**
         * Returns the details of the suggestion.
         *
         * @return The UpsellSuggestion containing EAN and Quantity.
         */
        public UpsellSuggestion getSuggestion() {
            return suggestion;
        }
    }
}
