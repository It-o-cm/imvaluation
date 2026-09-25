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
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import com.intermarche.valuation.engine.TierTable;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Factory for the "TIERED_DISCOUNT" offer type: threshold-based (tiered) discounts.
 * <p>
 * Offers are retrieved from the database (store and store groups) where the type is
 * "TIERED_DISCOUNT". The specification declares a scope (a list of target EANs or the
 * whole ticket), a metric dimension (amount or quantity), one of three tier modes, and
 * an award per tier:
 * <ul>
 *   <li>{@code HIGHEST_REACHED} — the highest reached tier applies once on the whole
 *       base ("5% from 50€, 10% from 100€");</li>
 *   <li>{@code PROGRESSIVE} — marginal brackets, each tier's award applies to its own
 *       slice ("5% on the first 50€, 10% on the next 50€");</li>
 *   <li>{@code PER_MULTIPLE} — a repeating step ("1€ for every 50€ spent").</li>
 * </ul>
 * Like the other discounts, this factory produces {@link DiscountApplication}s applied
 * after the offers, computed from the amounts the product-aware offer applications
 * attribute to the targeted products; the total discount is capped at that base so a
 * tiered discount can never drive a line or the ticket negative.
 */
@ApplicationScoped
public class TieredDiscountFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "TIERED_DISCOUNT";

    /**
     * Ordering constant: after the vignettes (10.0), close to the immediate vouchers,
     * before the free-delivery threshold (-1.0) and the meal voucher (-2.0).
     */
    private static final double EFFICIENCY_SCORE = 5.0;

    /**
     * JSON Schema definition for validating tiered discount specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Tiered Discount Offer Specification",
      "description": "Defines a threshold-based discount: highest tier reached, progressive brackets, or per-multiple step.",
      "type": "object",
      "required": ["scope", "metric", "mode"],
      "oneOf": [
        { "required": ["tiers"] },
        { "required": ["every"] }
      ],
      "properties": {
        "scope": {
          "type": "string",
          "enum": ["ITEMS", "TICKET"],
          "description": "ITEMS targets the listed EANs; TICKET targets the whole merchandise total.",
          "x-label": "Scope"
        },
        "targetEans": {
          "type": "array",
          "minItems": 1,
          "items": { "type": "string" },
          "description": "Products the discount targets. Required when the scope is ITEMS.",
          "x-widget": "ean-list",
          "x-label": "Eligible products"
        },
        "metric": {
          "type": "string",
          "enum": ["AMOUNT", "QUANTITY"],
          "description": "Dimension of the thresholds: a monetary amount or a number of units.",
          "x-label": "Metric"
        },
        "mode": {
          "type": "string",
          "enum": ["HIGHEST_REACHED", "PROGRESSIVE", "PER_MULTIPLE"],
          "description": "How the tiers apply: highest reached once, progressive brackets, or repeating step.",
          "x-label": "Tier mode"
        },
        "priceUsage": {
          "type": "string",
          "enum": ["BASE_FOR_DISCOUNT", "DEFAULT"],
          "description": "Price rows used for unit price lookups (free item, new price). Defaults to BASE_FOR_DISCOUNT.",
          "x-label": "Price basis"
        },
        "perProduct": {
          "type": "boolean",
          "default": false,
          "description": "When true, the metric is measured and the tiers resolved per distinct target EAN, independently. Requires scope ITEMS and metric QUANTITY.",
          "x-label": "Per identical product"
        },
        "tiers": {
          "type": "array",
          "minItems": 1,
          "description": "The thresholds and their awards (modes HIGHEST_REACHED and PROGRESSIVE).",
          "x-widget": "object-list",
          "x-label": "Tiers",
          "x-item-label": "tier",
          "items": {
            "type": "object",
            "required": ["threshold", "award"],
            "additionalProperties": false,
            "properties": {
              "threshold": {
                "type": "number",
                "minimum": 0,
                "description": "Minimum amount or quantity to reach this tier; in PROGRESSIVE mode, the floor of its bracket.",
                "x-label": "Threshold"
              },
              "award": { "$ref": "#/definitions/award" }
            }
          }
        },
        "every": {
          "type": "object",
          "required": ["step", "award"],
          "additionalProperties": false,
          "description": "The repeating step and its award (mode PER_MULTIPLE).",
          "x-widget": "object",
          "x-label": "Repeating step",
          "properties": {
            "step": {
              "type": "number",
              "exclusiveMinimum": 0,
              "description": "Size of one step, in the metric's dimension.",
              "x-label": "Step"
            },
            "award": { "$ref": "#/definitions/award" }
          }
        }
      },
      "additionalProperties": false,
      "definitions": {
        "award": {
          "type": "object",
          "required": ["type"],
          "additionalProperties": false,
          "properties": {
            "type": {
              "type": "string",
              "enum": ["PERCENTAGE", "AMOUNT", "AMOUNT_PER_ITEM", "ITEM_FREE", "NEW_PRICE"],
              "description": "PERCENTAGE of the base; AMOUNT flat (once, or per step in PER_MULTIPLE); AMOUNT_PER_ITEM per unit; ITEM_FREE offers items; NEW_PRICE replaces the unit price.",
              "x-label": "Award type"
            },
            "value": {
              "type": "number",
              "minimum": 0,
              "description": "Percentage, amount or new unit price, depending on the award type.",
              "x-widget": "discount-value",
              "x-label": "Value",
              "x-unit-from": "type"
            },
            "selection": {
              "type": "string",
              "enum": ["CHEAPEST", "MOST_EXPENSIVE"],
              "description": "Which items are offered (ITEM_FREE only).",
              "x-label": "Selection"
            },
            "quantity": {
              "type": "integer",
              "minimum": 1,
              "description": "How many items are offered (ITEM_FREE only, defaults to 1).",
              "x-label": "Offered quantity"
            }
          }
        }
      }
    }
    """;

    /**
     * Scope of the discount: a list of products or the whole merchandise total.
     */
    public enum Scope {
        /** The discount targets the products listed in {@code targetEans}. */
        ITEMS,
        /** The discount targets the merchandise total (product-aware offers only). */
        TICKET
    }

    /**
     * Dimension of the thresholds.
     */
    public enum Metric {
        /** Thresholds compare against a monetary amount (tax included). */
        AMOUNT,
        /** Thresholds compare against a number of standard units. */
        QUANTITY
    }

    /**
     * How the tiers apply to the base.
     */
    public enum Mode {
        /** The highest reached tier applies once on the whole base. */
        HIGHEST_REACHED,
        /** Marginal brackets: each tier's award applies to its own slice. */
        PROGRESSIVE,
        /** A single repeating step whose award applies once per complete step. */
        PER_MULTIPLE
    }

    /**
     * Nature of the award granted by a tier.
     */
    public enum AwardType {
        /** A percentage of the base (or of the slice in PROGRESSIVE mode). */
        PERCENTAGE,
        /** A flat amount: once in HIGHEST_REACHED, once per step in PER_MULTIPLE. */
        AMOUNT,
        /** An amount per targeted unit (per unit of the slice in PROGRESSIVE mode). */
        AMOUNT_PER_ITEM,
        /** One or more targeted items offered, picked by unit price. */
        ITEM_FREE,
        /** The targeted unit price is replaced; the discount is the difference. */
        NEW_PRICE
    }

    /**
     * Which items an {@code ITEM_FREE} award offers.
     */
    public enum Selection {
        /** The cheapest targeted items are offered. */
        CHEAPEST,
        /** The most expensive targeted items are offered. */
        MOST_EXPENSIVE
    }

    /**
     * The award granted by a tier or a step.
     *
     * @param type      the nature of the award.
     * @param value     the percentage, amount or new unit price; null for ITEM_FREE.
     * @param selection which items are offered; null unless the type is ITEM_FREE.
     * @param quantity  how many items are offered; meaningful for ITEM_FREE only.
     */
    public record Award(AwardType type, BigDecimal value, Selection selection, int quantity) {
    }

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the "TIERED_DISCOUNT" discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the tiered discount specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per "TIERED_DISCOUNT" offer of the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link TieredDiscountApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or the
     *                                  cross-field rules below.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        getBasket(basketEvaluation, "Cannot create Tiered Discount appliers without a valid basket.");
        for (Offer offer : getOffers(basketEvaluation, OFFER_TYPE)) {
            processOffer(offer, appliers);
        }
        return appliers;
    }

    /**
     * Parses and validates one offer specification and adds the corresponding applier.
     *
     * @param offer    the offer to process.
     * @param appliers the list receiving the created applier.
     * @throws IllegalArgumentException if the specification violates a cross-field rule.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            Scope scope = Scope.valueOf(spec.get("scope").asText());
            Metric metric = Metric.valueOf(spec.get("metric").asText());
            Mode mode = Mode.valueOf(spec.get("mode").asText());
            PriceUsage priceUsage = spec.has("priceUsage")
                    ? PriceUsage.valueOf(spec.get("priceUsage").asText())
                    : PriceUsage.BASE_FOR_DISCOUNT;
            boolean perProduct = spec.has("perProduct") && spec.get("perProduct").asBoolean();
            Set<String> targetEans = parseTargetEans(spec);
            validateCrossRules(offer.code, scope, metric, mode, targetEans, spec);
            if (perProduct && (scope != Scope.ITEMS || metric != Metric.QUANTITY)) {
                throw new IllegalArgumentException(String.format(
                        "TIERED_DISCOUNT offer '%s': perProduct requires scope ITEMS and metric QUANTITY.",
                        offer.code));
            }
            TierTable<Award> table = null;
            BigDecimal step = null;
            Award stepAward = null;
            if (mode == Mode.PER_MULTIPLE) {
                JsonNode every = spec.get("every");
                step = every.get("step").decimalValue();
                stepAward = parseAward(offer.code, every.get("award"), scope, metric, mode);
            } else {
                List<TierTable.Tier<Award>> tiers = new ArrayList<>();
                for (JsonNode tierNode : spec.get("tiers")) {
                    tiers.add(new TierTable.Tier<>(
                            tierNode.get("threshold").decimalValue(),
                            parseAward(offer.code, tierNode.get("award"), scope, metric, mode)));
                }
                table = TierTable.of(tiers);
            }
            List<Product> targetProducts = scope == Scope.ITEMS
                    ? Product.findByEans(targetEans)
                    : List.of();
            TieredDiscountApplier applier = new TieredDiscountApplier(
                    offer.code, scope, metric, mode, priceUsage, table, step, stepAward, targetProducts, perProduct);
            applier.configuration = offer;
            appliers.add(applier);
        });
    }

    /**
     * Extracts the target EANs of the specification.
     *
     * @param spec the parsed specification.
     * @return the target EANs, empty when absent.
     */
    private Set<String> parseTargetEans(JsonNode spec) {
        Set<String> eans = new LinkedHashSet<>();
        if (spec.has("targetEans")) {
            for (JsonNode ean : spec.get("targetEans")) {
                eans.add(ean.asText());
            }
        }
        return eans;
    }

    /**
     * Parses one award node.
     *
     * @param offerCode the offer code, for error messages.
     * @param node      the award node.
     * @param scope     the offer scope, for validation.
     * @param metric   the metric dimension, for validation.
     * @param mode      the tier mode, for validation.
     * @return the parsed award.
     * @throws IllegalArgumentException if the award violates a cross-field rule.
     */
    private Award parseAward(String offerCode, JsonNode node, Scope scope, Metric metric, Mode mode) {
        AwardType type = AwardType.valueOf(node.get("type").asText());
        BigDecimal value = node.has("value") ? node.get("value").decimalValue() : null;
        Selection selection = node.has("selection") ? Selection.valueOf(node.get("selection").asText()) : null;
        int quantity = node.has("quantity") ? node.get("quantity").asInt() : 1;
        if (type != AwardType.ITEM_FREE && value == null) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': award type %s requires a value.", offerCode, type));
        }
        if (type == AwardType.ITEM_FREE && selection == null) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': award type ITEM_FREE requires a selection.", offerCode));
        }
        if (scope == Scope.TICKET && type != AwardType.PERCENTAGE && type != AwardType.AMOUNT) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': scope TICKET only allows PERCENTAGE and AMOUNT awards.", offerCode));
        }
        if (mode == Mode.PROGRESSIVE && type != AwardType.PERCENTAGE && type != AwardType.AMOUNT_PER_ITEM) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': mode PROGRESSIVE only allows PERCENTAGE and AMOUNT_PER_ITEM awards.",
                    offerCode));
        }
        if (mode == Mode.PROGRESSIVE && type == AwardType.AMOUNT_PER_ITEM && metric != Metric.QUANTITY) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': AMOUNT_PER_ITEM in PROGRESSIVE mode requires the QUANTITY metric.",
                    offerCode));
        }
        if (mode == Mode.PER_MULTIPLE && (type == AwardType.AMOUNT_PER_ITEM || type == AwardType.NEW_PRICE)) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': mode PER_MULTIPLE does not allow the %s award.", offerCode, type));
        }
        if (mode != Mode.HIGHEST_REACHED && type == AwardType.NEW_PRICE) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': the NEW_PRICE award requires the HIGHEST_REACHED mode.", offerCode));
        }
        return new Award(type, value, selection, quantity);
    }

    /**
     * Validates the specification rules spanning several fields.
     *
     * @param offerCode  the offer code, for error messages.
     * @param scope      the offer scope.
     * @param metric    the metric dimension.
     * @param mode       the tier mode.
     * @param targetEans the parsed target EANs.
     * @param spec       the parsed specification.
     * @throws IllegalArgumentException if a rule is violated.
     */
    private void validateCrossRules(String offerCode, Scope scope, Metric metric, Mode mode,
                                    Set<String> targetEans, JsonNode spec) {
        if (scope == Scope.ITEMS && targetEans.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': scope ITEMS requires targetEans.", offerCode));
        }
        if (scope == Scope.TICKET && metric == Metric.QUANTITY) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': scope TICKET requires the AMOUNT metric.", offerCode));
        }
        if (mode == Mode.PER_MULTIPLE && !spec.has("every")) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': mode PER_MULTIPLE requires 'every'.", offerCode));
        }
        if (mode != Mode.PER_MULTIPLE && !spec.has("tiers")) {
            throw new IllegalArgumentException(String.format(
                    "TIERED_DISCOUNT offer '%s': modes HIGHEST_REACHED and PROGRESSIVE require 'tiers'.", offerCode));
        }
    }

    /**
     * One product's contribution to the base through one offer application.
     *
     * @param application the product-aware application covering the product.
     * @param product     the targeted product; null in TICKET scope.
     * @param quantity    the covered quantity in standard units; zero in TICKET scope.
     * @param amount      the amount the application attributes to the product (or its
     *                    whole amount in TICKET scope).
     */
    private record Contribution(ProductAwareOfferApplication application, Product product,
                                BigDecimal quantity, AmountEvaluation amount) {
    }

    /**
     * Applier of one tiered discount offer.
     */
    public static class TieredDiscountApplier implements AdvantageApplier {

        /**
         * The offer code, used in labels and error messages.
         */
        private final String code;

        /**
         * The configuration (the {@link Offer} row) this applier was built from, set by the
         * factory right after construction. Never null in production; left null when an
         * applier is built directly (as in unit tests), which the arbitration reads as
         * {@link com.intermarche.valuation.engine.Trigger#ALWAYS} with default parameters.
         */
        private Offer configuration;

        /**
         * The offer scope.
         */
        private final Scope scope;

        /**
         * The metric dimension of the thresholds.
         */
        private final Metric metric;

        /**
         * The tier mode.
         */
        private final Mode mode;

        /**
         * The price rows used for unit price lookups.
         */
        private final PriceUsage priceUsage;

        /**
         * The tier table (modes HIGHEST_REACHED and PROGRESSIVE); null in PER_MULTIPLE.
         */
        private final TierTable<Award> table;

        /**
         * The repeating step (mode PER_MULTIPLE); null otherwise.
         */
        private final BigDecimal step;

        /**
         * The award of the repeating step (mode PER_MULTIPLE); null otherwise.
         */
        private final Award stepAward;

        /**
         * The targeted products (scope ITEMS); empty in TICKET scope.
         */
        private final List<Product> targetProducts;

        /**
         * Whether the metric and the tiers are resolved per distinct target EAN (spec §8);
         * false reproduces the current behaviour bit for bit. Only true with scope ITEMS and
         * metric QUANTITY, enforced at creation.
         */
        private final boolean perProduct;

        /**
         * Creates the applier for one offer, with per-product resolution disabled.
         * <p>
         * A convenience overload preserving the pre-{@code perProduct} signature (spec §8 is
         * additive): it delegates with {@code perProduct} false, the current behaviour.
         *
         * @param code           the offer code.
         * @param scope          the offer scope.
         * @param metric        the metric dimension.
         * @param mode           the tier mode.
         * @param priceUsage     the price rows used for unit price lookups.
         * @param table          the tier table; null in PER_MULTIPLE mode.
         * @param step           the repeating step; null unless in PER_MULTIPLE mode.
         * @param stepAward      the award of the repeating step; null unless PER_MULTIPLE.
         * @param targetProducts the targeted products; empty in TICKET scope.
         */
        public TieredDiscountApplier(String code, Scope scope, Metric metric, Mode mode,
                                     PriceUsage priceUsage, TierTable<Award> table,
                                     BigDecimal step, Award stepAward, List<Product> targetProducts) {
            this(code, scope, metric, mode, priceUsage, table, step, stepAward, targetProducts, false);
        }

        /**
         * Creates the applier for one offer.
         *
         * @param code           the offer code.
         * @param scope          the offer scope.
         * @param metric        the metric dimension.
         * @param mode           the tier mode.
         * @param priceUsage     the price rows used for unit price lookups.
         * @param table          the tier table; null in PER_MULTIPLE mode.
         * @param step           the repeating step; null unless in PER_MULTIPLE mode.
         * @param stepAward      the award of the repeating step; null unless PER_MULTIPLE.
         * @param targetProducts the targeted products; empty in TICKET scope.
         * @param perProduct     whether the tiers are resolved per distinct target EAN.
         */
        public TieredDiscountApplier(String code, Scope scope, Metric metric, Mode mode,
                                     PriceUsage priceUsage, TierTable<Award> table,
                                     BigDecimal step, Award stepAward, List<Product> targetProducts,
                                     boolean perProduct) {
            this.code = code;
            this.scope = scope;
            this.metric = metric;
            this.mode = mode;
            this.priceUsage = priceUsage;
            this.table = table;
            this.step = step;
            this.stepAward = stepAward;
            this.targetProducts = targetProducts;
            this.perProduct = perProduct;
        }

        /**
         * Tells whether this discount can relate to the given offer applier.
         * <p>
         * A tiered discount concerns every product-aware applier in TICKET scope, and the
         * appliers covering at least one targeted product in ITEMS scope. Registration
         * switches the standard lines to the reference price, like every other discount.
         *
         * @param offerApplier the offer applier to check.
         * @return true when this discount can relate to the applier.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            if (!(offerApplier instanceof ProductAwareOfferApplier productApplier)) {
                return false;
            }
            if (scope == Scope.TICKET) {
                return true;
            }
            for (Product product : targetProducts) {
                if (productApplier.isApplicable(product)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the ordering score of this applier.
         *
         * @return the constant {@code 5.0}: after the vignettes, before the free-delivery
         *         threshold and the meal voucher.
         */
        @Override
        public double getEfficiencyScore() {
            return EFFICIENCY_SCORE;
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
         * Applies the tiered discount to the evaluation.
         * <p>
         * The base is gathered from the product-aware offer applications (the amounts
         * they attribute to the targeted products, or their whole amounts in TICKET
         * scope), the tier mode computes the total discount, the total is capped at the
         * base, then split into one application per targeted offer application,
         * pro-rata of their contributions, the rounding residual going to the last one.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return the discount applications, empty when no tier applies.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            List<Contribution> contributions = collectContributions(evaluation);
            if (contributions.isEmpty()) {
                return applications;
            }
            if (!perProduct) {
                applyGroup(evaluation, applications, contributions);
                return applications;
            }
            // Per identical product (spec §8): the metric is measured and the tiers resolved for
            // each distinct target EAN independently, so 6 of A and 2 of B each reach their own
            // tier. Scope ITEMS and metric QUANTITY are guaranteed by the creation-time check, so
            // every contribution carries a product here.
            Map<String, List<Contribution>> byEan = new LinkedHashMap<>();
            for (Contribution contribution : contributions) {
                byEan.computeIfAbsent(contribution.product().ean, k -> new ArrayList<>()).add(contribution);
            }
            for (List<Contribution> group : byEan.values()) {
                applyGroup(evaluation, applications, group);
            }
            return applications;
        }

        /**
         * Resolves and distributes the discount for one homogeneous set of contributions.
         * <p>
         * This is the whole-base computation of the default behaviour; {@link #apply} calls
         * it once over every contribution when {@code perProduct} is false (bit for bit the
         * current behaviour), or once per distinct target EAN when it is true.
         *
         * @param evaluation    the evaluation context (store, for unit price lookups).
         * @param applications  the list receiving the produced applications.
         * @param contributions the contributions to resolve together.
         */
        private void applyGroup(BasketEvaluation evaluation, List<AdvantageApplication> applications,
                                List<Contribution> contributions) {
            BigDecimal baseAmount = BigDecimal.ZERO;
            BigDecimal baseQuantity = BigDecimal.ZERO;
            // A3 (report H2a): the cap is taken on the base net of the advantages already retained,
            // so the award can never exceed what these lines are still worth. Tier selection stays on
            // the gross base — which tier is reached is not a cap.
            BigDecimal netBaseAmount = BigDecimal.ZERO;
            for (Contribution contribution : contributions) {
                baseAmount = baseAmount.add(contribution.amount().amountIncludingTax);
                baseQuantity = baseQuantity.add(contribution.quantity());
                netBaseAmount = netBaseAmount.add(contribution.amount().amountIncludingTax
                        .multiply(NetAmounts.netFactor(evaluation, contribution.application())));
            }
            if (baseAmount.signum() <= 0) {
                return;
            }
            BigDecimal base = (metric == Metric.AMOUNT) ? baseAmount : baseQuantity;
            Result result = computeTotalDiscount(evaluation, contributions, base, baseAmount, baseQuantity);
            if (result == null) {
                return;
            }
            BigDecimal total = result.totalTtc().setScale(2, RoundingMode.HALF_UP);
            if (total.compareTo(netBaseAmount) > 0) {
                total = netBaseAmount;
            }
            if (total.signum() <= 0) {
                return;
            }
            distribute(applications, contributions, total, baseAmount, result.detail());
        }

        /**
         * The outcome of the tier resolution: a raw total and a display detail.
         *
         * @param totalTtc the total discount, tax included, before capping and rounding.
         * @param detail   the display detail of the applied resolution.
         */
        private record Result(BigDecimal totalTtc, String detail) {
        }

        /**
         * Gathers the contributions of the targeted products to the base.
         *
         * @param evaluation the evaluation context.
         * @return the contributions, empty when nothing is covered.
         */
        private List<Contribution> collectContributions(BasketEvaluation evaluation) {
            List<Contribution> contributions = new ArrayList<>();
            if (evaluation.getOffers() == null) {
                return contributions;
            }
            for (OfferApplication app : evaluation.getAvailableOffers()) {
                if (!(app instanceof ProductAwareOfferApplication productAwareApp)) {
                    continue;
                }
                if (scope == Scope.TICKET) {
                    AmountEvaluation amount = productAwareApp.getAmount();
                    if (amount != null && amount.amountIncludingTax.signum() > 0) {
                        contributions.add(new Contribution(productAwareApp, null, BigDecimal.ZERO, amount));
                    }
                    continue;
                }
                for (Product product : targetProducts) {
                    BigDecimal quantity = productAwareApp.getProductQuantity(product);
                    if (quantity.signum() <= 0) {
                        continue;
                    }
                    AmountEvaluation amount = productAwareApp.getProductAmount(product);
                    if (amount == null || amount.amountIncludingTax.signum() <= 0) {
                        continue;
                    }
                    contributions.add(new Contribution(productAwareApp, product, quantity, amount));
                }
            }
            return contributions;
        }

        /**
         * Computes the raw total discount according to the tier mode.
         *
         * @param evaluation    the evaluation context (store, for unit price lookups).
         * @param contributions the base contributions.
         * @param base          the compared value (amount or quantity per the metric).
         * @param baseAmount    the monetary base, tax included.
         * @param baseQuantity  the base in standard units.
         * @return the raw total and its display detail, or null when no tier applies.
         */
        private Result computeTotalDiscount(BasketEvaluation evaluation, List<Contribution> contributions,
                                            BigDecimal base, BigDecimal baseAmount, BigDecimal baseQuantity) {
            return switch (mode) {
                case HIGHEST_REACHED -> {
                    Optional<TierTable.Tier<Award>> tier = table.resolveHighest(base);
                    if (tier.isEmpty()) {
                        yield null;
                    }
                    BigDecimal total = awardValue(evaluation, tier.get().award(), contributions,
                            baseAmount, baseQuantity, 1);
                    yield total == null ? null : new Result(total, "tier " + tier.get().threshold());
                }
                case PROGRESSIVE -> {
                    List<TierTable.Slice<Award>> slices = table.slices(base);
                    if (slices.isEmpty()) {
                        yield null;
                    }
                    BigDecimal avgUnit = averageUnit(baseAmount, baseQuantity);
                    BigDecimal total = BigDecimal.ZERO;
                    for (TierTable.Slice<Award> slice : slices) {
                        Award award = slice.award();
                        if (award.type() == AwardType.PERCENTAGE) {
                            BigDecimal portionAmount = (metric == Metric.AMOUNT)
                                    ? slice.portion()
                                    : slice.portion().multiply(avgUnit);
                            total = total.add(portionAmount.multiply(percent(award.value())));
                        } else { // AMOUNT_PER_ITEM, guaranteed QUANTITY metric by validation
                            total = total.add(slice.portion().multiply(award.value()));
                        }
                    }
                    yield new Result(total, "progressive");
                }
                case PER_MULTIPLE -> {
                    int multiples = TierTable.multiples(base, step);
                    if (multiples <= 0) {
                        yield null;
                    }
                    BigDecimal covered = step.multiply(BigDecimal.valueOf(multiples));
                    BigDecimal total = switch (stepAward.type()) {
                        case PERCENTAGE -> {
                            BigDecimal coveredAmount = (metric == Metric.AMOUNT)
                                    ? covered
                                    : covered.multiply(averageUnit(baseAmount, baseQuantity));
                            yield coveredAmount.multiply(percent(stepAward.value()));
                        }
                        case AMOUNT -> stepAward.value().multiply(BigDecimal.valueOf(multiples));
                        case ITEM_FREE -> freeItemsValue(evaluation, contributions,
                                stepAward, stepAward.quantity() * multiples);
                        default -> null; // unreachable: rejected at parse time
                    };
                    yield total == null ? null : new Result(total, "x" + multiples);
                }
            };
        }

        /**
         * Computes the value of one award applied once in HIGHEST_REACHED mode.
         *
         * @param evaluation    the evaluation context (store, for unit price lookups).
         * @param award         the award to value.
         * @param contributions the base contributions.
         * @param baseAmount    the monetary base, tax included.
         * @param baseQuantity  the base in standard units.
         * @param times         how many times the award applies.
         * @return the raw discount value, or null when nothing can be valued.
         */
        private BigDecimal awardValue(BasketEvaluation evaluation, Award award, List<Contribution> contributions,
                                      BigDecimal baseAmount, BigDecimal baseQuantity, int times) {
            return switch (award.type()) {
                case PERCENTAGE -> baseAmount.multiply(percent(award.value()));
                case AMOUNT -> award.value().multiply(BigDecimal.valueOf(times));
                case AMOUNT_PER_ITEM -> award.value().multiply(baseQuantity);
                case ITEM_FREE -> freeItemsValue(evaluation, contributions, award, award.quantity() * times);
                case NEW_PRICE -> newPriceValue(evaluation, contributions, award.value());
            };
        }

        /**
         * Values an ITEM_FREE award: the summed unit prices of the offered items.
         * <p>
         * The targeted units are sorted by unit price ({@code priceUsage} rows), cheapest
         * or most expensive first per the selection, and the requested count is taken
         * from what is available; units of a product without a price row are skipped.
         *
         * @param evaluation    the evaluation context (store).
         * @param contributions the base contributions.
         * @param award         the ITEM_FREE award.
         * @param count         how many items to offer.
         * @return the summed unit prices, or null when no unit can be valued.
         */
        private BigDecimal freeItemsValue(BasketEvaluation evaluation, List<Contribution> contributions,
                                          Award award, int count) {
            record Unit(BigDecimal unitTtc, int available) {
            }
            Map<String, Unit> perProduct = new LinkedHashMap<>();
            for (Contribution contribution : contributions) {
                Product product = contribution.product();
                if (product == null) {
                    continue;
                }
                BigDecimal unit = unitPrice(evaluation, product);
                if (unit == null) {
                    continue;
                }
                int available = contribution.quantity().setScale(0, RoundingMode.FLOOR).intValue();
                perProduct.merge(product.ean, new Unit(unit, available),
                        (a, b) -> new Unit(a.unitTtc(), a.available() + b.available()));
            }
            List<Unit> units = new ArrayList<>(perProduct.values());
            Comparator<Unit> byPrice = Comparator.comparing(Unit::unitTtc);
            units.sort(award.selection() == Selection.MOST_EXPENSIVE ? byPrice.reversed() : byPrice);
            BigDecimal total = BigDecimal.ZERO;
            int remaining = count;
            for (Unit unit : units) {
                if (remaining <= 0) {
                    break;
                }
                int taken = Math.min(remaining, unit.available());
                total = total.add(unit.unitTtc().multiply(BigDecimal.valueOf(taken)));
                remaining -= taken;
            }
            return (count - remaining) > 0 ? total : null;
        }

        /**
         * Values a NEW_PRICE award: the summed per-unit differences with the new price.
         *
         * @param evaluation    the evaluation context (store).
         * @param contributions the base contributions.
         * @param newPrice      the new unit price, tax included.
         * @return the summed differences (never negative per unit), or null when no unit
         *         can be valued.
         */
        private BigDecimal newPriceValue(BasketEvaluation evaluation, List<Contribution> contributions,
                                         BigDecimal newPrice) {
            BigDecimal total = BigDecimal.ZERO;
            boolean valued = false;
            for (Contribution contribution : contributions) {
                Product product = contribution.product();
                if (product == null) {
                    continue;
                }
                BigDecimal unit = unitPrice(evaluation, product);
                if (unit == null) {
                    continue;
                }
                BigDecimal perUnit = unit.subtract(newPrice);
                if (perUnit.signum() <= 0) {
                    valued = true;
                    continue;
                }
                total = total.add(perUnit.multiply(contribution.quantity()));
                valued = true;
            }
            return valued ? total : null;
        }

        /**
         * Looks up the current unit price (tax included) of a product for the configured
         * price usage.
         *
         * @param evaluation the evaluation context carrying the store.
         * @param product    the product to price.
         * @return the unit price tax included, or null when no active row exists.
         */
        private BigDecimal unitPrice(BasketEvaluation evaluation, Product product) {
            Store store = evaluation.getStore();
            if (store == null || product == null || product.id == null) {
                return null;
            }
            Price price = Price.findActivePriceAtDate(product.id, store.id, DateTimeProvider.now(), priceUsage);
            return price == null ? null : price.priceIncludingTax;
        }

        /**
         * Splits the capped total into one application per targeted offer application.
         *
         * @param applications  the list receiving the applications.
         * @param contributions the base contributions.
         * @param total         the capped total discount, tax included.
         * @param baseAmount    the monetary base, tax included.
         * @param detail        the display detail of the applied resolution.
         */
        private void distribute(List<AdvantageApplication> applications, List<Contribution> contributions,
                                BigDecimal total, BigDecimal baseAmount, String detail) {
            Map<ProductAwareOfferApplication, List<Contribution>> byApplication = new LinkedHashMap<>();
            for (Contribution contribution : contributions) {
                byApplication.computeIfAbsent(contribution.application(), k -> new ArrayList<>()).add(contribution);
            }
            List<Map.Entry<ProductAwareOfferApplication, List<Contribution>>> entries =
                    new ArrayList<>(byApplication.entrySet());
            BigDecimal remaining = total;
            for (int i = 0; i < entries.size(); i++) {
                List<Contribution> group = entries.get(i).getValue();
                BigDecimal groupTtc = BigDecimal.ZERO;
                BigDecimal groupHt = BigDecimal.ZERO;
                for (Contribution contribution : group) {
                    groupTtc = groupTtc.add(contribution.amount().amountIncludingTax);
                    groupHt = groupHt.add(contribution.amount().amountExcludingTax);
                }
                BigDecimal shareTtc;
                if (i == entries.size() - 1) {
                    shareTtc = remaining;
                } else {
                    shareTtc = total.multiply(groupTtc)
                            .divide(baseAmount, 2, RoundingMode.HALF_UP);
                    remaining = remaining.subtract(shareTtc);
                }
                if (shareTtc.signum() <= 0) {
                    continue;
                }
                BigDecimal rate = (groupHt.signum() > 0)
                        ? groupTtc.divide(groupHt, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                        : BigDecimal.ZERO;
                BigDecimal shareHt = shareTtc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                AmountEvaluation amount = new AmountEvaluation(shareHt, shareTtc, rate);
                applications.add(new TieredDiscountApplication(code, detail, entries.get(i).getKey(), amount));
            }
        }

        /**
         * Computes the average unit price of the base, used to value quantity slices.
         *
         * @param baseAmount   the monetary base, tax included.
         * @param baseQuantity the base in standard units.
         * @return the average unit price at four decimals, or zero when no unit exists.
         */
        private BigDecimal averageUnit(BigDecimal baseAmount, BigDecimal baseQuantity) {
            if (baseQuantity.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return baseAmount.divide(baseQuantity, 4, RoundingMode.HALF_UP);
        }

        /**
         * Converts a percentage value into a multiplier fraction.
         *
         * @param value the percentage value (e.g. 5 for 5%).
         * @return the fraction at four decimals (e.g. 0.05).
         */
        private BigDecimal percent(BigDecimal value) {
            return value.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
    }

    /**
     * The application of a tiered discount on one targeted offer application.
     */
    public static class TieredDiscountApplication implements DiscountApplication {

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

        /**
         * The offer code.
         */
        private final String code;

        /**
         * The display detail of the applied resolution (reached tier, progressive, xN).
         */
        private final String detail;

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
         * @param detail         the display detail of the applied resolution.
         * @param target         the targeted offer application.
         * @param discountAmount the discount amount, stored positive.
         */
        public TieredDiscountApplication(String code, String detail, OfferApplication target,
                                         AmountEvaluation discountAmount) {
            this.code = code;
            this.detail = detail;
            this.target = target;
            this.discountAmount = discountAmount;
        }

        /**
         * Returns the display type of this application.
         *
         * @return a string of the form {@code Tiered Discount: <code> (<detail>)}.
         */
        public String getType() {
            return "Tiered Discount: " + code + " (" + detail + ")";
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
    }
}
