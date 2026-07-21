package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating Offer Appliers for "Mixed Bundle" type offers (e.g., "Menu", "Pack").
 * <p>
 * Offers are retrieved from the database where the type is "MIXED_BUNDLE".
 * The JSON specification must contain:
 * <ul>
 *   <li>"bundlePrice": The fixed price for the bundle (TTC).</li>
 *   <li>"vatRate": The VAT rate to apply to the bundle.</li>
 *   <li>"contents": A list of objects defining the components.
 *       Each component must have: "ean" (reference), "quantity", and optionally "substituteEans" (array of EANs).
 *       Example: {"ean": "123", "quantity": 1.0, "substituteEans": ["456", "789"]}</li>
 * </ul>
 */
@ApplicationScoped
public class MixedBundleOfferFactory implements OfferApplierFactory, EngineTrait {

    /**
     * JSON Schema definition for validating Mixed Bundle specifications.
     */
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
     * Builds a collection of offer appliers based on the provided basket.
     *
     * @param basketEvaluation The basketEvaluation referencing items to be evaluated.
     * @return A collection of {@link MixedBundleOfferApplier} instances relevant to the basket.
     */
    @Override
    public Collection<OfferApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<OfferApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create appliers without a valid basket context.");
        // Optimization: Pre-calculate EANs present in the basket.
        Map<String, Basket.Item> basketItems = basketEvaluation.getBasket().items.stream()
                .collect(Collectors.toMap(item -> item.produceEan, item -> item));
        Store store = basketEvaluation.getStore();
        // Retrieve all "MIXED_BUNDLE" type offers for this store
        Collection<Offer> offers = getOffers(basketEvaluation, basketItems.keySet(), "MIXED_BUNDLE");
        for (Offer offer : offers) {
            processOffer(offer, appliers, basketItems, store);
        }
        return appliers;
    }

    /**
     * Processes a single offer and creates the corresponding applier if valid.
     *
     * @param offer      The offer to process.
     * @param appliers   The list to which the created applier will be added.
     * @param basketItems The map of EANs to Basket Items present in the basket.
     * @param store      The store concerned.
     */
    private void processOffer(Offer offer, List<OfferApplier> appliers, Map<String, Basket.Item> basketItems, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            BigDecimal bundlePrice = spec.get("bundlePrice").decimalValue();
            BigDecimal vatRate = spec.get("vatRate").decimalValue();
            JsonNode contentsNode = spec.get("contents");

            List<BundleComponent> components = new ArrayList<>();
            boolean allComponentsAvailable = true;

            for (JsonNode itemNode : contentsNode) {
                String mainEan = itemNode.get("ean").asText();
                double requiredQty = itemNode.get("quantity").asDouble();

                Set<String> validEans = new LinkedHashSet<>();
                validEans.add(mainEan);

                if (itemNode.has("substituteEans")) {
                    for (JsonNode subNode : itemNode.get("substituteEans")) {
                        validEans.add(subNode.asText());
                    }
                }
                // Check if ANY of the valid EANs is present in the basket
                boolean existsInBasket = validEans.stream().anyMatch(basketItems.keySet()::contains);
                if (existsInBasket) {
                    components.add(new BundleComponent(mainEan, validEans, requiredQty));
                } else {
                    allComponentsAvailable = false;
                }
            }
            if (allComponentsAvailable) {
                appliers.add(new MixedBundleOfferApplier(offer.code, bundlePrice, vatRate, components, basketItems, store));
            }
        });
    }

    /**
     * Helper class to store bundle component requirements including substitutions.
     */
    private static class BundleComponent {
        String mainEan; // Reference EAN used for pricing calculation
        // LinkedHashSet to ensure uniqueness AND priority order (Main first, then substituteEans)
        Set<String> validEans;
        double quantity;

        /**
         * Constructs a new BundleComponent.
         *
         * @param mainEan    The main EAN.
         * @param validEans  The set of valid EANs (main + substitutes).
         * @param quantity   The required quantity.
         */
        BundleComponent(String mainEan, Set<String> validEans, double quantity) {
            this.mainEan = mainEan;
            this.validEans = validEans;
            this.quantity = quantity;
        }
    }

    /**
     * Specific applier for a single Mixed Bundle offer configuration.
     */
    public static class MixedBundleOfferApplier extends OfferApplier implements ProductAwareOfferApplier, EngineTrait {

        private final String offerCode;
        private final BigDecimal bundlePrice;
        private final BigDecimal vatRate;
        private final Map<String, Basket.Item> basketItems;
        private final List<BundleComponent> components;
        private final Store store;

        /**
         * Constructs a new Mixed Bundle Offer Applier.
         *
         * @param offerCode   The code identifying the offer.
         * @param bundlePrice The fixed price (TTC) of the bundle.
         * @param vatRate     The VAT rate for the bundle.
         * @param components  The list of components required for the bundle.
         * @param basketItems The map of items in the basket.
         * @param store       The store context.
         */
        public MixedBundleOfferApplier(String offerCode, BigDecimal bundlePrice, BigDecimal vatRate, List<BundleComponent> components, Map<String, Basket.Item> basketItems, Store store) {
            this.offerCode = offerCode;
            this.bundlePrice = bundlePrice;
            this.vatRate = vatRate;
            this.components = components;
            this.store = store;
            this.basketItems = basketItems;
        }

        /**
         * Applies the offer to the evaluation context.
         *
         * @param evaluation The evaluation context.
         * @return A collection of offer applications.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation evaluation) {
            List<OfferApplication> applications = new ArrayList<>();
            // 1. Calculate the maximum number of bundles possible based on stock
            int nbPossibleBundles = calculateMaxPossibleBundles(evaluation);
            // 2. If bundles are possible, consume the items
            if (nbPossibleBundles > 0) {
                List<Basket.Item> allConsumedItems = consumeComponentsForBundles(nbPossibleBundles, evaluation);
                // 3. If consumption was successful, create the application
                if (allConsumedItems != null) {
                    applications.add(new MixedBundleApplication(
                            store,
                            offerCode,
                            bundlePrice,
                            vatRate,
                            allConsumedItems,
                            nbPossibleBundles
                    ));
                }
            }
            return applications;
        }

        /**
         * Calculates the maximum number of complete bundles that can be formed.
         * <p>
         * For each component, it sums the available quantity across all valid EANs (Main + Substitutes).
         * The global bundle count is the minimum of these ratios across all components.
         *
         * @param evaluation The basket evaluation context.
         * @return The maximum number of bundles.
         */
         int calculateMaxPossibleBundles(BasketEvaluation evaluation) {
            int maxBundles = Integer.MAX_VALUE;
            for (BundleComponent comp : components) {
                double totalAvailableQty = 0.0;
                // Sum available quantities for all valid EANs (Main + Substitutes)
                for (String ean : comp.validEans) {
                    Basket.Item available = evaluation.getToEvaluate().get(ean);
                    if (available != null) {
                        totalAvailableQty += available.quantity;
                    }
                }
                int possibleForComp = (int) (totalAvailableQty / comp.quantity);
                maxBundles = Math.min(maxBundles, possibleForComp);
            }
            return maxBundles;
        }

        /**
         * Consumes the required items from the evaluation to form the specified number of bundles.
         * <p>
         * Consumption strategy:
         * 1. Prioritizes the Main EAN.
         * 2. Falls back to Substitutes in the order defined in the specification.
         *
         * @param nbBundles   The number of bundles to consume items for.
         * @param evaluation  The basket evaluation context.
         * @return A list of consumed items, or null if consumption fails (insufficient stock/pick error).
         */
         List<Basket.Item> consumeComponentsForBundles(int nbBundles, BasketEvaluation evaluation) {
            List<Basket.Item> consumedItems = new ArrayList<>();
            for (BundleComponent comp : components) {
                double totalToConsume = comp.quantity * nbBundles;
                double remainingToConsume = totalToConsume;
                // Try to consume prioritizing Main EAN, then Substitutes in order
                for (String ean : comp.validEans) {
                    if (remainingToConsume <= 0.0) break;
                    Basket.Item available = evaluation.getToEvaluate().get(ean);
                    // If this specific EAN exists in the basket and has quantity
                    if (available != null) {
                        // Take what we can (min of available or remaining needed)
                        double takeQty = Math.min(available.quantity, remainingToConsume);
                        Basket.Item picked = evaluation.pick(takeQty, ean);
                        if (picked != null) {
                            consumedItems.add(picked);
                            remainingToConsume -= takeQty;
                        } else {
                            return null;
                        }
                    }
                }
                if (remainingToConsume > 0.0001) {
                    // If we couldn't consume enough despite the initial check
                    return null;
                }
            }
            return consumedItems;
        }

        /**
         * Determines if this applier is applicable to the given product.
         *
         * @param product The product to check applicability against.
         * @return True if this applier can be applied to the provided product; false otherwise.
         */
        @Override
        public boolean isApplicable(Product product) {
            for (BundleComponent comp : components) {
                if (comp.validEans.contains(product.ean)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Represents the result of applying a Mixed Bundle offer.
     */
    public static class MixedBundleApplication implements ProductAwareOfferApplication {

        private final Store store;
        private final String offerCode;
        private final BigDecimal bundlePriceUnit; // Fixed price for ONE bundle
        private final BigDecimal vatRate;
        private final List<Basket.Item> coveredItems; // All items consumed
        final int bundleCount; // How many times the bundle was applied

        /**
         * Constructs an application.
         *
         * @param store          The store context.
         * @param offerCode      The ID of the offer.
         * @param bundlePriceUnit The fixed price (TTC) of the bundle.
         * @param vatRate        The VAT rate of the bundle.
         * @param coveredItems   List of items consumed (summed across all bundles).
         * @param bundleCount    The number of bundles formed.
         */
        public MixedBundleApplication(Store store, String offerCode, BigDecimal bundlePriceUnit, BigDecimal vatRate, List<Basket.Item> coveredItems, int bundleCount) {
            this.store = store;
            this.offerCode = offerCode;
            this.bundlePriceUnit = bundlePriceUnit;
            this.vatRate = vatRate;
            this.coveredItems = coveredItems;
            this.bundleCount = bundleCount;
        }

        /**
         * Calculates the price for the applied bundle(s).
         * <p>
         * Price TTC = Fixed Price * Number of bundles.
         * Price HT = Price TTC / (1 + VAT Rate).
         *
         * @return A {@link AmountEvaluation}.
         */
        @Override
        public AmountEvaluation getAmount() {
            BigDecimal totalTTC = bundlePriceUnit.multiply(BigDecimal.valueOf(bundleCount)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal divisor = BigDecimal.ONE.add(vatRate);
            BigDecimal totalHT = totalTTC.divide(divisor, 2, RoundingMode.HALF_UP);
            return new AmountEvaluation(totalHT, totalTTC, vatRate);
        }

        /**
         * Returns all items covered by this application (every product from every bundle instance).
         *
         * @return The collection of consumed items.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return coveredItems;
        }

        /**
         * Returns a descriptive type of the offer application.
         *
         * @return A string describing the offer type.
         */
        @Override
        public String getType() {
            return "MixedBundle: " + offerCode + " x" + bundleCount + " for " + bundlePriceUnit.multiply(BigDecimal.valueOf(bundleCount)) + "€";
        }

        /**
         * Retrieves the price evaluation for a specific product within this offer application.
         * <p>
         * If the product matches the EAN of the bundle item, it returns the calculated price.
         * Otherwise, it returns null.
         *
         * @param product The product for which to retrieve the amount evaluation.
         * @return The {@link AmountEvaluation} for the product, or null if not applicable.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            Basket.Item[] allItems = coveredItems.toArray(new Basket.Item[0]);
            // Check if the product is part of the covered items
            Basket.Item[] items = coveredItems.stream()
                    .filter(item -> item.produceEan.equals(product.ean))
                    .toArray(Basket.Item[]::new);
            if (items.length != 0) {
                AmountEvaluation totalEvaluation = AmountEvaluation.getAmount(allItems, store, PriceUsage.BASE_FOR_DISCOUNT);
                AmountEvaluation productEvaluation = AmountEvaluation.getAmount(items, store, PriceUsage.BASE_FOR_DISCOUNT);
                // Avoid division by zero
                if (totalEvaluation.amountExcludingTax.compareTo(BigDecimal.ZERO) == 0) {
                    return new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                }
                BigDecimal ratio = productEvaluation.amountExcludingTax.divide(
                        totalEvaluation.amountExcludingTax, 4, RoundingMode.HALF_UP);
                BigDecimal priceExclTax = (getAmount().amountExcludingTax.multiply(ratio));
                BigDecimal productVat = productEvaluation.vatRate;
                return new AmountEvaluation(
                        priceExclTax,
                        (priceExclTax.multiply(BigDecimal.ONE.add(productVat))),
                        productVat
                );
            }
            return null;
        }

        /**
         * Retrieves the quantity of a specific product covered by this offer application.
         *
         * @param product The product for which to retrieve the quantity.
         * @return The quantity of the specified product.
         */
        @Override
        public double getProductQuantity(Product product) {
            return coveredItems.stream()
                    .filter(item -> item.produceEan.equals(product.ean))
                    .mapToDouble(item -> item.quantity)
                    .sum();
        }

    }
}