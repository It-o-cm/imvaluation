package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.engine.NetAmounts;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for the "NEW_PRICE_DISCOUNT" advantage type: a target-price discount (spec §3).
 * <p>
 * A new price is an <em>outcome</em>, never a valuation: the customer pays the declared new
 * unit price and the {@link DiscountApplication} absorbs the difference with the current base,
 * whatever that base is (the {@code BASE_FOR_DISCOUNT} switch included). The invariant is
 * "the new price fixes the outcome, the discount absorbs the base": on a targeted line the
 * discount is {@code max(0, currentBase − newPrice × quantity)}, so a new price greater than
 * or equal to the current unit price produces nothing — a negative discount does not exist.
 * <p>
 * {@code targets[]} carries one entry per price list: a single entry is the simple new price
 * (GB-01-05-29 / GM-06-04-15), several entries are the multi-list case (GB-01-05-39 /
 * GM-06-04-25). An EAN present in two targets is rejected at creation.
 * <p>
 * Like every discount, declaring itself applicable to an offer applier switches that offer's
 * standard lines to the reference price ({@code BASE_FOR_DISCOUNT}); the discount is then
 * {@code reference − newPrice × quantity} and the paid amount stays {@code newPrice × quantity}.
 * The efficiency score is the sandbox discount magnitude computed from the basket, like the
 * immediate voucher.
 */
@ApplicationScoped
public class NewPriceDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "NEW_PRICE_DISCOUNT";

    /**
     * JSON Schema definition for validating new-price discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "New Price Discount Offer Specification",
      "description": "Replaces the unit price of the targeted lines by a new price; the discount absorbs the difference with the current base.",
      "type": "object",
      "required": ["targets"],
      "properties": {
        "targets": {
          "type": "array",
          "minItems": 1,
          "description": "One entry per price list: the targeted EANs and their new unit price.",
          "x-widget": "object-list",
          "x-label": "Targets",
          "x-item-label": "target",
          "items": {
            "type": "object",
            "required": ["eans", "newPrice"],
            "additionalProperties": false,
            "properties": {
              "eans": {
                "type": "array",
                "minItems": 1,
                "items": { "type": "string", "minLength": 1 },
                "description": "The products this new price applies to.",
                "x-widget": "ean-list",
                "x-label": "Eligible products"
              },
              "newPrice": {
                "type": "number",
                "exclusiveMinimum": 0,
                "description": "The new unit price, tax included.",
                "x-widget": "money",
                "x-label": "New price (incl. tax)"
              }
            }
          }
        }
      },
      "additionalProperties": false
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "NEW_PRICE_DISCOUNT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the new-price discount specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per "NEW_PRICE_DISCOUNT" offer of the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link NewPriceDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or a cross rule.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        Basket basket = getBasket(basketEvaluation, "Cannot create New Price Discount appliers without a valid basket.");
        Store store = basketEvaluation.getStore();
        for (Offer offer : getOffers(basketEvaluation, OFFER_TYPE)) {
            processOffer(offer, appliers, basket, store);
        }
        return appliers;
    }

    /**
     * Parses and validates one offer specification and adds the corresponding applier.
     *
     * @param offer    the offer to process.
     * @param appliers the list receiving the created applier.
     * @param basket   the basket, for the sandbox efficiency score.
     * @param store    the store, for the reference price lookups of the score.
     * @throws IllegalArgumentException if an EAN appears in two targets.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Basket basket, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            Map<String, BigDecimal> newPriceByEan = new LinkedHashMap<>();
            for (JsonNode target : spec.get("targets")) {
                BigDecimal newPrice = target.get("newPrice").decimalValue();
                for (JsonNode ean : target.get("eans")) {
                    String code = ean.asText();
                    if (newPriceByEan.containsKey(code)) {
                        throw new IllegalArgumentException(String.format(
                                "NEW_PRICE_DISCOUNT offer '%s': EAN '%s' appears in more than one target.",
                                offer.code, code));
                    }
                    newPriceByEan.put(code, newPrice);
                }
            }
            List<Product> targetProducts = Product.findByEans(newPriceByEan.keySet());
            NewPriceDiscountApplier applier =
                    new NewPriceDiscountApplier(offer.code, newPriceByEan, targetProducts, basket, store);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * Applier of one new-price discount offer.
     */
    public static class NewPriceDiscountApplier implements AdvantageApplier {

        /**
         * The offer code, used in labels and error messages.
         */
        private final String code;

        /**
         * The configuration (the {@link Offer} row) this applier was built from, set by the
         * factory right after construction. Never null in production; left null when an applier
         * is built directly (as in unit tests), which the arbitration reads as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS} with default parameters.
         */
        private Offer configuration;

        /**
         * The new unit price (tax included) per targeted EAN.
         */
        private final Map<String, BigDecimal> newPriceByEan;

        /**
         * The targeted products, resolved from the target EANs.
         */
        private final List<Product> targetProducts;

        /**
         * The sandbox efficiency score: the discount magnitude the offer would produce on the
         * current basket at the reference price. Higher is arbitrated first.
         */
        private final double efficiencyScore;

        /**
         * Creates the applier and computes its sandbox efficiency score.
         *
         * @param code           the offer code.
         * @param newPriceByEan  the new unit price per targeted EAN.
         * @param targetProducts the targeted products.
         * @param basket         the basket, scanned for the sandbox score; may be null.
         * @param store          the store, for reference price lookups; may be null.
         */
        public NewPriceDiscountApplier(String code, Map<String, BigDecimal> newPriceByEan,
                                       List<Product> targetProducts, Basket basket, Store store) {
            this.code = code;
            this.newPriceByEan = new LinkedHashMap<>(newPriceByEan);
            this.targetProducts = targetProducts;
            this.efficiencyScore = computeSandboxScore(basket, store);
        }

        /**
         * Computes the sandbox efficiency score: the summed potential discount on the basket
         * lines whose EAN is targeted, valued at the reference price.
         *
         * @param basket the basket to scan; may be null.
         * @param store  the store for the reference price lookups; may be null.
         * @return the summed sandbox discount, never negative; zero when nothing can be valued.
         */
        private double computeSandboxScore(Basket basket, Store store) {
            if (basket == null || basket.items == null || store == null) {
                return 0.0;
            }
            Map<String, Product> byEan = new LinkedHashMap<>();
            for (Product product : targetProducts) {
                byEan.put(product.ean, product);
            }
            BigDecimal total = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                Product product = byEan.get(item.produceEan);
                BigDecimal newPrice = newPriceByEan.get(item.produceEan);
                if (product == null || newPrice == null || item.quantity == null || product.id == null) {
                    continue;
                }
                Price price = Price.findActivePriceAtDate(
                        product.id, store.id, DateTimeProvider.now(), PriceUsage.BASE_FOR_DISCOUNT);
                if (price == null) {
                    continue;
                }
                BigDecimal perUnit = price.priceIncludingTax.subtract(newPrice);
                if (perUnit.signum() <= 0) {
                    continue;
                }
                total = total.add(perUnit.multiply(product.standardQuantity(item.quantity)));
            }
            return total.doubleValue();
        }

        /**
         * Tells whether this discount can relate to the given offer applier.
         * <p>
         * It relates to every product-aware applier covering at least one targeted product;
         * registration switches those standard lines to the reference price, like every other
         * discount, so the new price then fixes the paid amount against the reference.
         *
         * @param offerApplier the offer applier to check.
         * @return true when the applier covers at least one targeted product.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (!(offerApplier instanceof ProductAwareOfferApplier productApplier)) {
                return false;
            }
            for (Product product : targetProducts) {
                if (productApplier.isApplicable(product)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the sandbox efficiency score of this applier.
         *
         * @return the summed potential discount on the current basket at the reference price.
         */
        @Override
        public double getEfficiencyScore() {
            return efficiencyScore;
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

        /**
         * Applies the new-price discount to the evaluation.
         * <p>
         * For every targeted product covered by an available offer application, the discount is
         * {@code max(0, currentBase − newPrice × quantity)} at the line's real VAT rate; a line
         * whose current unit price is already at or below the new price produces nothing. Each
         * eligible line yields one application targeting its offer application.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return the discount applications, empty when no line is eligible.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            if (evaluation.getOffers() == null) {
                return applications;
            }
            for (OfferApplication app : evaluation.getAvailableOffers()) {
                if (!(app instanceof ProductAwareOfferApplication productAwareApp)) {
                    continue;
                }
                for (Product product : targetProducts) {
                    BigDecimal newPrice = newPriceByEan.get(product.ean);
                    if (newPrice == null) {
                        continue;
                    }
                    BigDecimal quantity = productAwareApp.getProductQuantity(product);
                    if (quantity.signum() <= 0) {
                        continue;
                    }
                    AmountEvaluation amount = productAwareApp.getProductAmount(product);
                    if (amount == null || amount.amountIncludingTax == null) {
                        continue;
                    }
                    // The base is the line's CURRENT amount: the new price fixes the outcome, so
                    // a discount already retained against this line reduces the base a competing
                    // new price sees (spec §3: the second new price sees the base already at the
                    // first new price).
                    // A3 (report H2c): net per-product — a discount retained against another product
                    // of a multi-product application must not reduce this product's base.
                    BigDecimal currentTtc = NetAmounts.netProductTtc(evaluation, app, product.ean,
                            amount.amountIncludingTax).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal outcomeTtc = newPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal discountTtc = currentTtc.subtract(outcomeTtc);
                    if (discountTtc.signum() <= 0) {
                        continue;
                    }
                    BigDecimal rate = amount.vatRate == null ? BigDecimal.ZERO : amount.vatRate;
                    BigDecimal discountHt = discountTtc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                    AmountEvaluation discountAmount = new AmountEvaluation(discountHt, discountTtc, rate);
                    applications.add(new NewPriceDiscountApplication(code, product.ean, app, discountAmount));
                }
            }
            return applications;
        }
    }

    /**
     * The application of a new-price discount on one targeted offer application.
     */
    public static class NewPriceDiscountApplication implements DiscountApplication,
            com.intermarche.valuation.engine.ProductScopedDiscount {

        /**
         * The application moment restituted in the response (spec §3.6), set by the arbitration.
         * Defaults to AT_TOTAL, the current behaviour.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * The offer code.
         */
        private final String code;

        /**
         * The EAN of the line the new price was applied to.
         */
        private final String ean;

        /**
         * The targeted offer application.
         */
        private final OfferApplication target;

        /**
         * The discount amount (stored positive; the engine subtracts it).
         */
        private final AmountEvaluation discountAmount;

        /**
         * Creates the application.
         *
         * @param code           the offer code.
         * @param ean            the EAN of the line the new price applied to.
         * @param target         the targeted offer application.
         * @param discountAmount the discount amount, stored positive.
         */
        public NewPriceDiscountApplication(String code, String ean, OfferApplication target,
                                           AmountEvaluation discountAmount) {
            this.code = code;
            this.ean = ean;
            this.target = target;
            this.discountAmount = discountAmount;
        }

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

        /**
         * Returns the display type of this application.
         *
         * @return a string of the form {@code New Price: <code>}.
         */
        public String getType() {
            return "New Price: " + code;
        }

        /**
         * Returns the EAN of the line the new price was applied to.
         *
         * @return the targeted EAN.
         */
        public String getEan() {
            return ean;
        }

        /**
         * Returns the offer application this discount targets.
         *
         * @return the targeted application.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return target;
        }

        /**
         * Returns the discount amount.
         *
         * @return the amount, stored positive; the engine subtracts it from the total.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return discountAmount;
        }

        /**
         * Returns the EAN of the product line this new price reduced (A3, report H2c).
         *
         * @return the discounted product EAN.
         */
        @Override
        public String discountedEan() {
            return ean;
        }
    }
}
