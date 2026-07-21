package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory for creating {@link VignetteDiscountApplier} instances for Vignette-based discounts.
 * <p>
 * Offers are retrieved from the database where the type is "VIGNETTE_DISCOUNT".
 * The JSON specification must contain a "catalog" list defining the rules:
 * <pre>
 * {
 *   "catalog": [
 *     {
 *       "ean": "1234567890123",
 *       "vignettesRequired": 5,
 *       "discount": { "type": "PERCENTAGE", "value": 50.0 }
 *     }
 *   ]
 * }
 * </pre>
 */
@ApplicationScoped
public class VignetteDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * JSON Schema definition for validating Vignette Discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Vignette Discount Offer Specification",
      "description": "Defines the catalog for vignette-based discounts.",
      "type": "object",
      "required": [
        "catalog"
      ],
      "properties": {
        "catalog": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "required": [
              "ean",
              "vignettesRequired",
              "discount"
            ],
            "properties": {
              "ean": {
                "type": "string",
                "description": "The product EAN."
              },
              "vignettesRequired": {
                "type": "integer",
                "description": "Number of vignettes required.",
                "minimum": 0
              },
              "discount": {
                "type": "object",
                "required": [
                  "type",
                  "value"
                ],
                "properties": {
                  "type": {
                    "type": "string",
                    "enum": ["PERCENTAGE", "FIXED_AMOUNT"],
                    "description": "Type of discount calculation."
                  },
                  "value": {
                    "type": "number",
                    "description": "The discount value."
                  }
                }
              }
            }
          }
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Builds a collection of discount appliers based on the provided basket evaluation.
     *
     * @param basketEvaluation The evaluation context.
     * @return A collection of {@link VignetteDiscountApplier}.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create Vignette appliers without a valid basket.");
        if (basket.vignettes == null || basket.vignettes.isEmpty()) {
            return appliers;
        }
        Store store = basketEvaluation.getStore();
        Collection<Offer> offers = getOffers(basketEvaluation, "VIGNETTE_DISCOUNT");
        for (Offer offer : offers) {
            processOffer(offer, appliers, basket.vignettes, store);
        }
        return appliers;
    }

    /**
     * Processes a single offer and adds a corresponding applier if valid.
     *
     * @param offer    The offer to process.
     * @param appliers The list to which the created applier will be added.
     * @param vignettes The map of vignettes from the basket.
     * @param store    The store context.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Map<String, Integer> vignettes, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            JsonNode catalogNode = spec.get("catalog");
            List<VignetteRule> catalog = new ArrayList<>();

            for (JsonNode node : catalogNode) {
                String ean = node.get("ean").asText();
                int required = node.get("vignettesRequired").asInt();
                JsonNode discountNode = node.get("discount");
                String typeStr = discountNode.get("type").asText();
                double value = discountNode.get("value").asDouble();
                catalog.add(new VignetteRule(ean, required, DiscountType.valueOf(typeStr), value));
            }
            // Pass a copy of the vignettes map to avoid modifying the original basket object directly
            appliers.add(new VignetteDiscountApplier(offer.code, catalog, new HashMap<>(vignettes), store));
        });
    }

    /**
     * Internal class representing a single discount rule from the catalog.
     */
    private static class VignetteRule {
        /**
         * The EAN code of the product this rule applies to.
         */
        final String ean;

        /**
         * The number of vignettes required to trigger the discount once.
         */
        final int vignettesRequired;

        /**
         * The type of discount calculation.
         */
        final DiscountType discountType;

        /**
         * The value of the discount (percentage or amount).
         */
        final double discountValue;

        /**
         * Constructs a new VignetteRule.
         *
         * @param ean               The product EAN.
         * @param vignettesRequired  The number of required vignettes.
         * @param discountType       The discount type.
         * @param discountValue      The discount value.
         */
        VignetteRule(String ean, int vignettesRequired, DiscountType discountType, double discountValue) {
            this.ean = ean;
            this.vignettesRequired = vignettesRequired;
            this.discountType = discountType;
            this.discountValue = discountValue;
        }
    }

    /**
     * Enumeration defining the calculation type for the discount.
     */
    private enum DiscountType {
        /** Discount calculated as a percentage of the unit price. */
        PERCENTAGE,

        /** Discount calculated as a fixed monetary amount. */
        FIXED_AMOUNT
    }

    /**
     * Specific applier for Vignette Discounts.
     */
    public static class VignetteDiscountApplier implements AdvantageApplier, EngineTrait {

        /**
         * The unique code identifying the offer.
         */
        private final String offerCode;

        /**
         * The list of rules defining eligible products and discounts.
         */
        private final List<VignetteRule> catalog;

        /**
         * A local map tracking the balance of available vignettes during processing.
         */
        private final Map<String, Integer> availableVignettes;

        /**
         * The store context for price resolution.
         */
        private final Store store;

        private Map<String, Product> productInCatalog;

        /**
         * Constructs a new VignetteDiscountApplier.
         *
         * @param offerCode          The offer code.
         * @param catalog            The list of discount rules.
         * @param availableVignettes The initial stock of vignettes.
         * @param store              The store context.
         */
        public VignetteDiscountApplier(String offerCode, List<VignetteRule> catalog, Map<String, Integer> availableVignettes, Store store) {
            this.offerCode = offerCode;
            this.catalog = catalog;
            this.availableVignettes = availableVignettes;
            this.store = store;
            this.productInCatalog = availableVignettes.keySet().stream().collect(Collectors.toMap(k -> k, Product::findByEan));
        }

        /**
         * Determines if this applier is applicable to a given offer applier.
         * <p>
         * This discount is a post-processor on applied offers, not a filter for offer creation.
         *
         * @param offerApplier The offer applier to check.
         * @return Always false.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (!(offerApplier instanceof ProductAwareOfferApplier)) return false;
            ProductAwareOfferApplier paOfferApplier = (ProductAwareOfferApplier) offerApplier;
            for (Product product : productInCatalog.values()) {
                if (paOfferApplier.isApplicable(product)) return true;
            }
            return false;
        }

        /**
         * Applies the discount logic by iterating over existing offers and matching catalog rules.
         *
         * @param evaluation The basket evaluation context.
         * @return A collection of {@link AdvantageApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            processAppliedOffers(evaluation, applications);
            return applications;
        }

        /**
         * Iterates through the offers already applied to the basket to find eligible products for vignette discounts.
         *
         * @param evaluation   The basket evaluation context.
         * @param applications The list to populate with valid discount applications.
         */
        void processAppliedOffers(BasketEvaluation evaluation, List<AdvantageApplication> applications) {
            for (OfferApplication offerApp : evaluation.getOffers()) {
                if (offerApp instanceof ProductAwareOfferApplication) {
                    processProductAwareOffer((ProductAwareOfferApplication) offerApp, applications);
                }
            }
        }

        /**
         * Processes a specific ProductAwareOfferApplication to find eligible items for vignette discounts.
         *
         * @param offerApp     The product offer application to inspect.
         * @param applications The list to populate with valid discount applications.
         */
        private void processProductAwareOffer(ProductAwareOfferApplication offerApp, List<AdvantageApplication> applications) {
            for (Basket.Item item : offerApp.getItems()) {
                Product product = Product.findByEan(item.produceEan);
                if (product == null) continue;
                Optional<VignetteRule> ruleOpt = findRuleForProduct(product.ean);
                if (ruleOpt.isPresent()) {
                    tryApplyVignetteDiscount(product, offerApp, ruleOpt.get(), applications);
                }
            }
        }

        /**
         * Finds a catalog rule for a specific product EAN.
         *
         * @param ean The product EAN.
         * @return An Optional containing the rule if found.
         */
        private Optional<VignetteRule> findRuleForProduct(String ean) {
            return catalog.stream().filter(r -> r.ean.equals(ean)).findFirst();
        }

        /**
         * Attempts to apply the vignette discount if conditions (vignettes, quantity) are met.
         *
         * @param product     The product to discount.
         * @param offerApp    The application providing the price context.
         * @param rule        The discount rule to apply.
         * @param applications The list to populate with valid discount applications.
         */
        private void tryApplyVignetteDiscount(Product product, ProductAwareOfferApplication offerApp, VignetteRule rule, List<AdvantageApplication> applications) {
            Integer userVignetteCount = availableVignettes.getOrDefault(product.ean, 0);
            if (userVignetteCount >= rule.vignettesRequired) {
                double productQuantity = offerApp.getProductQuantity(product);
                if (productQuantity <= 0) return;
                int numberOfApplications = calculateMaxApplications(productQuantity, userVignetteCount, rule.vignettesRequired);
                if (numberOfApplications > 0) {
                    AmountEvaluation discountAmount = calculateTotalDiscount(product, offerApp, rule, numberOfApplications);
                    if (discountAmount != null) {
                        int consumedVignettes = rule.vignettesRequired * numberOfApplications;
                        applications.add(new VignetteDiscountApplication(
                                this.offerCode,
                                offerApp,
                                discountAmount,
                                numberOfApplications,
                                consumedVignettes
                        ));
                        // Update local balance
                        availableVignettes.put(product.ean, userVignetteCount - consumedVignettes);
                    }
                }
            }
        }

        /**
         * Calculates the maximum number of times the discount can be applied.
         * Limited by both the product quantity in the basket and the available vignettes.
         *
         * @param productQuantity       The quantity of product in the offer.
         * @param availableVignettes     The number of vignettes held by the user.
         * @param vignettesPerApplication The cost in vignettes for a single discount.
         * @return The number of applications.
         */
        private int calculateMaxApplications(double productQuantity, int availableVignettes, int vignettesPerApplication) {
            int maxByQuantity = (int) Math.floor(productQuantity);
            int maxByVignettes = availableVignettes / vignettesPerApplication;
            return Math.min(maxByQuantity, maxByVignettes);
        }

        /**
         * Calculates the total discount amount based on unit price and number of applications.
         *
         * @param product             The product.
         * @param productApp          The offer application providing the price context.
         * @param rule                The discount rule.
         * @param numberOfApplications The number of times to apply the unit discount.
         * @return The total discount evaluation (negative values).
         */
        AmountEvaluation calculateTotalDiscount(Product product, ProductAwareOfferApplication productApp, VignetteRule rule, int numberOfApplications) {
            AmountEvaluation totalProductPrice = productApp.getProductAmount(product);
            if (totalProductPrice == null) return null;
            double totalQty = productApp.getProductQuantity(product);
            // Derive unit price
            BigDecimal unitPriceHT = totalProductPrice.amountExcludingTax.divide(
                    BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
            BigDecimal unitPriceTTC = totalProductPrice.amountIncludingTax.divide(
                    BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
            // Calculate unit discount
            AmountEvaluation unitDiscount = computeUnitDiscount(unitPriceHT, unitPriceTTC, totalProductPrice.vatRate, rule);
            // Scale to number of applications
            BigDecimal totalHT = unitDiscount.amountExcludingTax.multiply(BigDecimal.valueOf(numberOfApplications)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalTTC = unitDiscount.amountIncludingTax.multiply(BigDecimal.valueOf(numberOfApplications)).setScale(2, RoundingMode.HALF_UP);
            return new AmountEvaluation(totalHT, totalTTC, totalProductPrice.vatRate);
        }

        /**
         * Computes the unit discount amount based on the rule type (Percentage or Fixed Amount).
         * <p>
         * This method isolates the specific logic for determining the discount value.
         *
         * @param unitPriceHT The unit price excluding tax.
         * @param unitPriceTTC The unit price including tax.
         * @param vatRate     The VAT rate.
         * @param rule         The discount rule containing type and value.
         * @return An AmountEvaluation representing the discount for one unit.
         */
        private AmountEvaluation computeUnitDiscount(BigDecimal unitPriceHT, BigDecimal unitPriceTTC, BigDecimal vatRate, VignetteRule rule) {
            BigDecimal discountHT = BigDecimal.ZERO;
            BigDecimal discountTTC = BigDecimal.ZERO;
            if (rule.discountType == DiscountType.PERCENTAGE) {
                BigDecimal percent = BigDecimal.valueOf(rule.discountValue)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                discountHT = unitPriceHT.multiply(percent);
                discountTTC = unitPriceTTC.multiply(percent);
            } else { // FIXED_AMOUNT
                discountTTC = BigDecimal.valueOf(rule.discountValue);
                // Derive HT from TTC using the product's VAT rate
                BigDecimal divisor = BigDecimal.ONE.add(vatRate);
                discountHT = discountTTC.divide(divisor, 2, RoundingMode.HALF_UP);
            }
            return new AmountEvaluation(discountHT, discountTTC, vatRate);
        }

        /**
         * Returns the efficiency score.
         * <p>
         * This score is used by the engine to sort discount appliers.
         *
         * @return The efficiency score.
         */
        @Override
        public double getEfficiencyScore() {
            return 10.0;
        }
    }

    /**
     * Result object representing a Vignette Discount application.
     */
    public static class VignetteDiscountApplication implements DiscountApplication {

        /**
         * The offer code.
         */
        final String offerCode;

        /**
         * The original offer application this discount targets.
         */
        final OfferApplication targetApplication;

        /**
         * The calculated discount amount (negative value).
         */
        final AmountEvaluation discountAmount;

        /**
         * The number of times the discount was applied.
         */
        final int numberOfApplications;

        /**
         * The total number of vignettes consumed.
         */
        final int vignettesConsumed;

        /**
         * Constructs a Vignette Discount Application.
         *
         * @param offerCode         The offer code.
         * @param targetApplication  The product offer being discounted.
         * @param discountAmount     The calculated discount (negative).
         * @param numberOfApplications How many times the discount was applied.
         * @param vignettesConsumed  How many vignettes were used.
         */
        public VignetteDiscountApplication(String offerCode, OfferApplication targetApplication,
                                           AmountEvaluation discountAmount, int numberOfApplications, int vignettesConsumed) {
            this.offerCode = offerCode;
            this.targetApplication = targetApplication;
            this.discountAmount = discountAmount;
            this.numberOfApplications = numberOfApplications;
            this.vignettesConsumed = vignettesConsumed;
        }

        /**
         * Returns a descriptive string representing the type of this discount application.
         *
         * @return A string describing the vignette usage and application count.
         */
        public String getType() {
            return "Vignette Discount: " + offerCode + " (" + vignettesConsumed + " vignettes used, applied " + numberOfApplications + " times)";
        }

        /**
         * Returns the offer application targeted by this discount.
         *
         * @return The {@link OfferApplication} instance.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return this.targetApplication;
        }

        /**
         * Returns the calculated discount amount.
         *
         * @return The {@link AmountEvaluation} representing the discount value.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return this.discountAmount;
        }
    }


}