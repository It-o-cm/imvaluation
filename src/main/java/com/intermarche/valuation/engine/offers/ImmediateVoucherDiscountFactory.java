package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating {@link ImmediateVoucherApplier} instances for Immediate Vouchers.
 * <p>
 * Offers are retrieved from the database where the type is "IMMEDIATE_VOUCHER".
 * The JSON specification must contain:
 * <ul>
 *   <li>"targetOfferClass" : The name(s) of the target offer class.
 *       Can be a canonical name (e.g. "com.foo.Bar"), a simple name (e.g. "Bar"),
 *       or a partial substring (e.g. "NPlusMOffer" matches "...$NPlusMApplication").
 *       Can be a single String or a List of Strings.</li>
 *   <li>"targetEans" : A list of EANs of the products on which the discount applies.</li>
 *   <li>"discountType" : The type of discount ("FIXED_AMOUNT", "PERCENTAGE").</li>
 *   <li>"value" : The discount value (amount or percentage).</li>
 * </ul>
 */
@ApplicationScoped
public class ImmediateVoucherDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * ObjectMapper instance used for JSON processing.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * JSON Schema definition for validating Immediate Voucher specifications.
     */
    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "IMMEDIATE_VOUCHER";

    /**
     * Returns the offer type handled by this factory.
     *
     * @return The "IMMEDIATE_VOUCHER" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the immediate voucher specification.
     *
     * @return The JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Immediate Voucher Offer Specification",
      "description": "Defines the rules for an immediate voucher discount.",
      "type": "object",
      "required": [
        "targetOfferClass",
        "targetEans",
        "discountType",
        "value"
      ],
      "properties": {
        "targetOfferClass": {
          "oneOf": [
            { "type": "string" },
            { "type": "array", "items": { "type": "string" } }
          ],
          "description": "The name(s) of the target offer class.",
          "x-widget": "string-list",
          "x-label": "Target offer classes"
        },
        "targetEans": {
          "oneOf": [
            { "type": "string" },
            { "type": "array", "items": { "type": "string" } }
          ],
          "description": "A list of EANs of the products on which the discount applies.",
          "x-widget": "ean-list",
          "x-label": "Discounted products"
        },
        "discountType": {
          "type": "string",
          "enum": ["FIXED_AMOUNT", "PERCENTAGE"],
          "description": "The type of discount.",
          "x-label": "Discount type"
        },
        "value": {
          "type": "number",
          "description": "The discount value (amount or percentage).",
          "x-widget": "discount-value",
          "x-label": "Discount value",
          "x-unit-from": "discountType"
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Builds a collection of {@link ImmediateVoucherApplier} instances based on the basket.
     *
     * @param basketEvaluation The basket evaluation (contains the store code).
     * @return A collection of {@link ImmediateVoucherApplier}.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Store store = basketEvaluation.getStore();
        // Retrieve all "IMMEDIATE_VOUCHER" type offers for this store
        List<Offer> offers = Offer.findByStoreAndType(store, "IMMEDIATE_VOUCHER");
        Map<String, Basket.Item> basketItems = basketEvaluation.getBasket().items.stream()
                .collect(Collectors.toMap(item -> item.produceEan, item -> item));
        for (Offer offer : offers) {
            processOffer(offer, appliers, basketItems, store);
        }
        return appliers;
    }

    /**
     * Processes a single Offer and creates an ImmediateVoucherApplier if valid.
     *
     * @param offer    The offer to process.
     * @param appliers The list to which the created applier will be added.
     * @param basketItems The map of items in the basket.
     * @param store    The store context for the applier.
     */
    void processOffer(Offer offer, List<AdvantageApplier> appliers, Map<String, Basket.Item> basketItems, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            JsonNode classTarget = spec.get("targetOfferClass");
            Set<String> targetOfferClasses = getTargetOfferClassNames(classTarget);
            // Parse targetEans (List)
            Map<String, Basket.Item> targetItems = new HashMap<>();
            JsonNode eansNode = spec.get("targetEans");
            getTargetEans(basketItems, eansNode, targetItems);
            String discountTypeStr = spec.get("discountType").asText();
            double value = spec.get("value").asDouble();
            if (!targetOfferClasses.isEmpty() && !targetItems.isEmpty()) {
                DiscountType discountType = DiscountType.valueOf(discountTypeStr);
                // Pass the 'store' to the Applier to allow dynamic efficiency score calculation
                appliers.add(new ImmediateVoucherApplier(offer.code, targetOfferClasses, targetItems, discountType, value, store));
            }
        });
    }

    /**
     * Extracts target offer class names from a JSON node.
     * <p>
     * Handles both single string and array of strings.
     *
     * @param node The JSON node containing the class name(s).
     * @return A set of class names.
     */
    Set<String> getTargetOfferClassNames(JsonNode node) {
        Set<String> targetOfferClasses = new HashSet<>();
        if (node.isTextual()) {
            targetOfferClasses.add(node.asText());
        } else {  // Array
            for (JsonNode item : node) {
                targetOfferClasses.add(item.asText());
            }
        }
        return targetOfferClasses;
    }

    /**
     * Extracts target EANs from a JSON node and maps them to basket items.
     * <p>
     * Handles both single string and array of strings.
     *
     * @param basketItems The map of all basket items.
     * @param node        The JSON node containing the EAN(s).
     * @param targetItems The map to populate with found items.
     */
    void getTargetEans(Map<String, Basket.Item> basketItems, JsonNode node, Map<String, Basket.Item> targetItems) {
        if (node.isTextual()) {
            String ean = node.asText();
            Basket.Item item = basketItems.get(ean);
            if (item != null) {
                targetItems.put(ean, item);
            }
        } else { // Array
            for (JsonNode item : node) {
                String ean = item.asText();
                Basket.Item basketItem = basketItems.get(ean);
                if (basketItem != null) {
                    targetItems.put(ean, basketItem);
                }
            }
        }
    }

    /**
     * Enumeration to type the discount calculation mode.
     */
    public enum DiscountType {
        /** Fixed amount per product unit. */
        FIXED_AMOUNT,
        /** Percentage reduction on the price. */
        PERCENTAGE
    }

    /**
     * Specific applier for a single Immediate Voucher configuration.
     */
    public static class ImmediateVoucherApplier implements AdvantageApplier, EngineTrait {

        private final String code;
        private final Set<String> targetOfferClassNames;
        private final Map<String, Product> productMap;
        private final Map<String, Price> priceMap;
        private final DiscountType discountType;
        private final double value;

        private final double efficiencyScore;

        /**
         * Constructs a new ImmediateVoucherApplier.
         * <p>
         * Loads all products and prices corresponding to the target EANs.
         * Calculates the efficiency score based on the average discount ratio across these products.
         *
         * @param code                  The code of the offer.
         * @param targetOfferClassNames A list of class name identifiers.
         * @param targetItems           A map of target basket items by EAN.
         * @param discountType          The type of discount calculation.
         * @param value                 The discount value.
         * @param store                 The store context to retrieve product pricing.
         */
        public ImmediateVoucherApplier(
                String code,
                Collection<String> targetOfferClassNames,
                Map<String, Basket.Item> targetItems,
                DiscountType discountType,
                double value,
                Store store)
        {
            this.code = code;
            this.targetOfferClassNames = new HashSet<>(targetOfferClassNames);
            this.productMap = new HashMap<>();
            this.priceMap = new HashMap<>();
            this.discountType = discountType;
            this.value = value;
            // Load all products and their prices to calculate efficiency
            for (Basket.Item item : targetItems.values()) {
                this.productMap.put(item.produceEan, item.getProduct());
                this.priceMap.put(item.produceEan, item.getPrice(store, PriceUsage.BASE_FOR_DISCOUNT));
            }
            // Calculate the average efficiency score across all valid products
            this.efficiencyScore = calculateAverageEfficiencyScore();
        }

        /**
         * Calculates the average discount ratio (efficiency score) across all target products.
         *
         * @return The calculated efficiency score.
         */
        private double calculateAverageEfficiencyScore() {
            double totalScore = 0.0;
            int count = 0;

            for (Price price : this.priceMap.values()) {
                double unitPrice = price.priceExcludingTax.doubleValue();
                switch (discountType) {
                    case PERCENTAGE:
                        totalScore += value / 100.0;
                        break;
                    default: // FIXED_AMOUNT
                        totalScore += value / unitPrice;
                        break;
                }
                count++;
            }
            return count > 0 ? totalScore / count : 0.0;
        }

        /**
         * Checks if the current Offer Application class matches one of the configured target names.
         * <p>
         * Supports flexible matching (Exact or Partial Contains).
         *
         * @param appClass The class of the offer application to check.
         * @return {@code true} if the class matches any configured target, {@code false} otherwise.
         */
        private boolean matchesTarget(Class<?> appClass) {
            String fullClassName = appClass.getName();
            String simpleClassName = appClass.getSimpleName();
            for (String configuredName : targetOfferClassNames) {
                String targetLower = configuredName.toLowerCase();
                if (fullClassName.equalsIgnoreCase(targetLower) || simpleClassName.equalsIgnoreCase(targetLower)) {
                    return true;
                }
                if (fullClassName.toLowerCase().contains(targetLower)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Determines if this applier is applicable to the given offer applier.
         * <p>
         * The applier is applicable if the offer application class matches AND the offer applier
         * is applicable to at least one of the target products.
         *
         * @param offerApplier The offer applier to check.
         * @return {@code true} if applicable, {@code false} otherwise.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (!matchesTarget(offerApplier.getClass())) return false;
            if (!(offerApplier instanceof ProductAwareOfferApplier)) return false;
            ProductAwareOfferApplier productApplier = (ProductAwareOfferApplier) offerApplier;
            // Check if the applier is applicable to ANY of our target products
            for (Product product : productMap.values()) {
                if (productApplier.isApplicable(product)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Applies the Immediate Voucher logic to the offers already applied.
         * <p>
         * Iterates through applied offers. If an offer covers any of the target EANs,
         * calculates the discount for that specific product part.
         *
         * @param evaluation The evaluation context.
         * @return A collection of {@link ImmediateVoucherApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            for (OfferApplication offerApp : evaluation.getOffers()) {
                if (offerApp instanceof ProductAwareOfferApplication) {
                    ProductAwareOfferApplication productAwareApp = (ProductAwareOfferApplication) offerApp;
                    // Check class match first (optimization)
                    if (!matchesTarget(productAwareApp.getClass())) {
                        continue;
                    }
                    // Check each target EAN against this offer
                    for (Map.Entry<String, Product> entry : productMap.entrySet()) {
                        Product product = entry.getValue();
                        // Check if this offer covers this product
                        double productQuantityInOffer = productAwareApp.getProductQuantity(product);
                        if (productQuantityInOffer <= 0) {
                            continue;
                        }
                        // Retrieve the price for this specific product within the offer
                        AmountEvaluation basePriceForProduct = productAwareApp.getProductAmount(product);
                        if (basePriceForProduct == null) {
                            continue;
                        }
                        // Calculate and create discount
                        AmountEvaluation discountAmount = calculateDiscountAmount(basePriceForProduct, productQuantityInOffer, product);
                        applications.add(new ImmediateVoucherApplication(code, productAwareApp, discountAmount));
                    }
                }
            }
            return applications;
        }

        /**
         * Calculates the discount amount as a positive value to be deducted from the total.
         *
         * @param basePrice The base price.
         * @param quantity  The quantity.
         * @param product   The product (used for reference weight).
         * @return The discount amount.
         */
        private AmountEvaluation calculateDiscountAmount(AmountEvaluation basePrice, double quantity, Product product) {
            BigDecimal vatRate = basePrice.vatRate;
            BigDecimal discountHt;
            switch (discountType) {
                case PERCENTAGE:
                    BigDecimal percent = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                    discountHt = basePrice.amountExcludingTax.multiply(percent);
                    break;
                default : // FIXED_AMOUNT
                    discountHt = BigDecimal.valueOf(value).multiply(product.standardQuantity(quantity));
                    break;
            }
            // Calculate TTC
            BigDecimal multiplier = BigDecimal.ONE.add(vatRate);
            BigDecimal discountTtc = discountHt.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            discountHt = discountHt.setScale(2, RoundingMode.HALF_UP);
            // Discount amounts are stored positive; the engine subtracts them from the total
            // and the UI renders the minus sign. Negating here would double the sign and
            // turn a reduction into a surcharge.
            return new AmountEvaluation(discountHt, discountTtc, vatRate);
        }

        /**
         * Returns the efficiency score of this applier.
         *
         * @return The efficiency score.
         */
        @Override
        public double getEfficiencyScore() {
            return this.efficiencyScore;
        }

    }

    /**
     * Represents the application of an Immediate Voucher.
     */
    public static class ImmediateVoucherApplication implements DiscountApplication {

        private final String code;
        private final OfferApplication offerApplication;
        private final AmountEvaluation discountAmount;

        /**
         * Constructs a new Immediate Voucher Application.
         *
         * @param code              The offer code.
         * @param offerApplication  The target offer application.
         * @param discountAmount    The calculated discount amount.
         */
        public ImmediateVoucherApplication(String code, OfferApplication offerApplication, AmountEvaluation discountAmount) {
            this.code = code;
            this.offerApplication = offerApplication;
            this.discountAmount = discountAmount;
        }

        /**
         * Returns the type of this application.
         *
         * @return A descriptive string.
         */
        public String getType() {
            return "Immediate Voucher Discount : " + this.code;
        }

        /**
         * Returns the discount amount.
         *
         * @return The discount amount.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return this.discountAmount;
        }

        /**
         * Returns the target offer application.
         *
         * @return The target offer application.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return this.offerApplication;
        }
    }
}