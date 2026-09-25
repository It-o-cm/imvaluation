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
import java.math.RoundingMode;
import java.util.*;

/**
 * Factory for creating {@link MixedBundleUpsellAdvantageApplier} instances.
 * <p>
 * This advantage analyzes items remaining in basket (unpicked items) to identify
 * opportunities to complete Mixed Bundle offers (e.g., "Menu", "Pack").
 * It suggests which specific product (EAN) and quantity is needed to maximize the offer
 * based on the basket's current composition.
 * <p>
 * Logic: It calculates the maximum potential bundles for each component.
 * The "Global Max" is the maximum of these potentials (e.g., if you have 4 Coffees and 1 Biscuit,
 * the potential is 4 bundles limited by Coffee, but 1 bundle limited by Biscuit).
 * However, for Upsell, we target the *abundance* (4 bundles) to suggest buying the missing Biscuits.
 * <p>
 * It relies on "MIXED_BUNDLE" type offers defined in database.
 */
@ApplicationScoped
public class MixedBundleUpsellAdvantageFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * JSON Schema definition for validating Mixed Bundle specifications.
     */
    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "MIXED_BUNDLE";

    /**
     * Returns the offer type handled by this factory.
     *
     * @return The "MIXED_BUNDLE" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Mixed Bundle Offer Specification",
      "description": "Defines the composition and pricing of a mixed bundle offer.",
      "type": "object",
      "required": [
        "bundlePrice",
        "vatRate",
        "contents"
      ],
      "properties": {
        "bundlePrice": {
          "type": "number",
          "description": "The fixed price (TTC) for the bundle.",
          "exclusiveMinimum": 0
        },
        "vatRate": {
          "type": "number",
          "description": "The VAT rate for the bundle.",
          "minimum": 0
        },
        "contents": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "required": [
              "ean",
              "quantity"
            ],
            "properties": {
              "ean": {
                "type": "string",
                "description": "The reference EAN for this component."
              },
              "quantity": {
                "type": "number",
                "description": "The required quantity for this component.",
                "exclusiveMinimum": 0
              },
              "substituteEans": {
                "type": "array",
                "items": { "type": "string" },
                "description": "List of substitute EANs."
              }
            }
          }
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Builds a collection of {@link MixedBundleUpsellAdvantageApplier} instances
     * for each configured Mixed Bundle offer in the store.
     *
     * @param basketEvaluation The basket evaluation context.
     * @return A collection of advantage appliers.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Store store = basketEvaluation.getStore();
        // Retrieve all MIXED_BUNDLE offers for this store (or its groups)
        Collection<Offer> offers = getOffers(basketEvaluation, "MIXED_BUNDLE");

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
    void processOffer(Offer offer, List<AdvantageApplier> appliers, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            JsonNode contentsNode = spec.get("contents");
            List<UpsellBundleComponent> components = new ArrayList<>();

            for (JsonNode itemNode : contentsNode) {
                String mainEan = itemNode.get("ean").asText();
                BigDecimal requiredQty = new BigDecimal(itemNode.get("quantity").asText());

                Set<String> validEans = new LinkedHashSet<>();
                validEans.add(mainEan);

                if (itemNode.has("substituteEans")) {
                    for (JsonNode subNode : itemNode.get("substituteEans")) {
                        validEans.add(subNode.asText());
                    }
                }
                components.add(new UpsellBundleComponent(mainEan, validEans, requiredQty));
            }
            MixedBundleConfig config = new MixedBundleConfig(offer.code, components);
            MixedBundleUpsellAdvantageApplier applier = new MixedBundleUpsellAdvantageApplier(config, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * Internal class holding parsed configuration of a Mixed Bundle Offer.
     */
    private static class MixedBundleConfig {
        final String offerCode;
        final List<UpsellBundleComponent> components;

        /**
         * Constructs the Mixed Bundle configuration.
         *
         * @param offerCode  The offer code.
         * @param components The list of bundle components.
         */
        MixedBundleConfig(String offerCode, List<UpsellBundleComponent> components) {
            this.offerCode = offerCode;
            this.components = components;
        }
    }

    /**
     * Internal class representing a component of a bundle (Requirement).
     */
     static class UpsellBundleComponent {
        final String mainEan; // Reference EAN
        final Set<String> validEans; // Main + Substitutes
        final BigDecimal requiredQuantity;

        /**
         * Constructs a bundle component with main EAN, valid EANs, and required quantity.
         *
         * @param mainEan          The main EAN code for the component.
         * @param validEans       Set of valid EANs (including substitutes).
         * @param requiredQuantity The quantity required for the bundle.
         */
        UpsellBundleComponent(String mainEan, Set<String> validEans, BigDecimal requiredQuantity) {
            this.mainEan = mainEan;
            this.validEans = validEans;
            this.requiredQuantity = requiredQuantity;
        }
    }

    /**
     * Applier that checks remaining items in basket for Mixed Bundle completion opportunities.
     */
    public static class MixedBundleUpsellAdvantageApplier implements AdvantageApplier, EngineTrait {

        private final MixedBundleConfig config;
        private final Store store;

        /**
         * The configuration (the {@link Offer} row) this applier was built from, set by the
         * factory right after construction. Never null in production; left null when an
         * applier is built directly (as in unit tests), which the arbitration reads as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS} with default parameters.
         */
        private Offer configuration;

        /**
         * Constructs the applier with configuration and store context.
         *
         * @param config The Mixed Bundle configuration.
         * @param store  The store context.
         */
        public MixedBundleUpsellAdvantageApplier(MixedBundleConfig config, Store store) {
            this.config = config;
            this.store = store;
        }

        /**
         * This applier is always applicable since it analyzes remaining items.
         *
         * @param offerApplier The offer applier context.
         * @return true always.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return false;
        }

        /**
         * Analyzes basket evaluation to find missing items for configured Mixed Bundle.
         *
         * @param evaluation The basket evaluation context.
         * @return A collection of {@link MixedBundleUpsellAdvantageApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            Map<String, Basket.Item> remainingItems = evaluation.getAvailableToUpcell();

            UpsellSuggestion suggestion = calculateUpsell(remainingItems);
            if (suggestion != null) {
                applications.add(new MixedBundleUpsellAdvantageApplication(config.offerCode, suggestion));
            }
            return applications;
        }

        /**
         * Core logic to calculate upsell suggestion based on remaining items.
         *
         * @param remainingItems The map of remaining items in the basket.
         * @return An {@link UpsellSuggestion} if applicable, otherwise null.
         */
        private UpsellSuggestion calculateUpsell(Map<String, Basket.Item> remainingItems) {
            // 1. Calculate max bundles possible for each component based on current stock (Using CEIL for capacity)
            Map<UpsellBundleComponent, Integer> maxBundlesPerComponent = new HashMap<>();
            int globalMaxBundles = getGlobalMaxBundles(remainingItems, maxBundlesPerComponent);
            // If globalMaxBundles is 0, basket is empty for this offer.
            if (globalMaxBundles == 0) {
                 return null;
            }
            // 2. Calculate total deficit across ALL components to reach this target
            Set<String> validEansForSuggestion = new HashSet<>();
            BigDecimal totalNeededQty = getTotalNeededQty(remainingItems, globalMaxBundles, validEansForSuggestion);
            if (totalNeededQty.signum() <= 0) {
                return null;
            }
            // 4. Determine which product to suggest (Cheapest valid EAN)
            String suggestedEan = findCheapestEan(validEansForSuggestion);
            return new UpsellSuggestion(suggestedEan, totalNeededQty, config.offerCode);
        }

        /**
         * Calculates the maximum number of bundles that can be formed based on current quantities.
         *
         * @param remainingItems          The map of remaining items in the basket.
         * @param maxBundlesPerComponent  Output map to hold max bundles per component.
         * @return The global maximum number of bundles possible.
         */
        private int getGlobalMaxBundles(Map<String, Basket.Item> remainingItems, Map<UpsellBundleComponent, Integer> maxBundlesPerComponent) {
            int globalMaxBundles = 0; // Start at 0, look for MAX
            for (UpsellBundleComponent comp : config.components) {
                BigDecimal compQty = BigDecimal.ZERO;
                for (String ean : comp.validEans) {
                    Basket.Item item = remainingItems.get(ean);
                    if (item != null) {
                        compQty = compQty.add(item.quantity);
                    }
                }
                // Use CEIL to determine "abundance" (capacity if we bought more)
                // e.g., if I have 4.1 coffees, ceil(4.1) = 5. I can aim for 5.
                int possible = compQty.divide(comp.requiredQuantity, 0, RoundingMode.CEILING).intValue();
                maxBundlesPerComponent.put(comp, possible);
                if (possible > globalMaxBundles) {
                    globalMaxBundles = possible;
                }
            }
            return globalMaxBundles;
        }

        /**
         * Calculates the total quantity needed across all components to reach target bundles.
         *
         * @param remainingItems          The map of remaining items in the basket.
         * @param targetBundles           The target number of bundles to achieve.
         * @param validEansForSuggestion  Output set to hold valid EANs for suggestion.
         * @return The total quantity needed.
         */
        private BigDecimal getTotalNeededQty(Map<String, Basket.Item> remainingItems, int targetBundles, Set<String> validEansForSuggestion) {
            // We iterate through ALL components to find what is missing
            BigDecimal totalNeededQty = BigDecimal.ZERO;
            for (UpsellBundleComponent comp : config.components) {
                BigDecimal neededForComp = comp.requiredQuantity.multiply(BigDecimal.valueOf(targetBundles));
                BigDecimal currentAvailableForComp = BigDecimal.ZERO;
                for (String ean : comp.validEans) {
                    Basket.Item item = remainingItems.get(ean);
                    if (item != null) {
                        currentAvailableForComp = currentAvailableForComp.add(item.quantity);
                    }
                }
                BigDecimal deficit = neededForComp.subtract(currentAvailableForComp);
                if (deficit.signum() > 0) {
                    totalNeededQty = totalNeededQty.add(deficit);
                    validEansForSuggestion.addAll(comp.validEans);
                }
            }
            return totalNeededQty;
        }

        /**
         * Finds cheapest product among the set of valid EANs.
         *
         * @param validEans The set of eligible EANs.
         * @return The EAN code of cheapest product.
         */
         String findCheapestEan(Set<String> validEans) {
            String cheapestEan = null;
            BigDecimal minPrice = BigDecimal.valueOf(Double.MAX_VALUE);
            for (String ean : validEans) {
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
            // Fallback
            if (cheapestEan == null) {
                return validEans.iterator().next();
            }
            return cheapestEan;
        }

        /**
         * Returns a negative efficiency score to indicate this is an Upsell opportunity.
         *
         * @return A negative efficiency score.
         */
        @Override
        public double getEfficiencyScore() {
            return -100.0;
        }

        /**
         * Returns the configuration this applier was built from.
         *
         * @return the source offer, or null when the applier was built without one.
         */
        @Override
        public Offer getConfiguration() {
            return configuration;
        }
    }

    /**
     * Internal data structure representing suggestion details.
     * <p>
     * Fields are PUBLIC to allow Jackson serialization.
     */
     static class UpsellSuggestion {
        public final String ean;
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.intermarche.valuation.engine.QuantitySerializer.class)
        public final BigDecimal quantity;
        public final String offerCode;

        /**
         * Constructs the suggestion with EAN, quantity, and offer code.
         *
         * @param ean       The product EAN to suggest.
         * @param quantity  The quantity needed.
         * @param offerCode The associated offer code.
         */
        UpsellSuggestion(String ean, BigDecimal quantity, String offerCode) {
            this.ean = ean;
            this.quantity = quantity;
            this.offerCode = offerCode;
        }
    }

    /**
     * Result object representing a Mixed Bundle Upsell opportunity.
     * <p>
     * Implements {@link AdvantageApplication} but not {@link DiscountApplication}.
     */
    public static class MixedBundleUpsellAdvantageApplication implements AdvantageApplication {

        /**
         * The application moment restituted in the response (spec §3.6), set by the arbitration.
         * Defaults to AT_TOTAL, the current behaviour.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * Returns the application moment of the configuration that produced this advantage.
         *
         * @return the application moment, AT_TOTAL until the arbitration sets it.
         */
        @Override
        public String getApplicationMoment() {
            return applicationMoment;
        }

        /**
         * Records the application moment set by the arbitration.
         *
         * @param applicationMoment the moment (AT_TRIGGER or AT_TOTAL).
         */
        @Override
        public void setApplicationMoment(String applicationMoment) {
            this.applicationMoment = applicationMoment;
        }

        private final String offerCode;
        private final UpsellSuggestion suggestion;

        /**
         * Constructs the application with offer code and suggestion details.
         *
         * @param offerCode  The offer code.
         * @param suggestion The upsell suggestion details.
         */
        public MixedBundleUpsellAdvantageApplication(String offerCode, UpsellSuggestion suggestion) {
            this.offerCode = offerCode;
            this.suggestion = suggestion;
        }

        /**
         * Returns a string representation of the offer application type.
         *
         * @return A descriptive string of the application.
         */
        public String getType() {
            return String.format("Upsell Mixed Bundle: %s (Need %.2f of %s)", offerCode, suggestion.quantity, suggestion.ean);
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
         * Retrieves the associated offer application.
         * <p>
         * For Upsell suggestions, there is no direct offer application, so returns null.
         *
         * @return null.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return null;
        }

        /**
         * Retrieves the upsell suggestion details.
         *
         * @return The {@link UpsellSuggestion} object.
         */
        public UpsellSuggestion getSuggestion() {
            return suggestion;
        }
    }
}