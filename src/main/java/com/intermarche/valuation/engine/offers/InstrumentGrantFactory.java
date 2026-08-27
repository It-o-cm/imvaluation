package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.TierTable;
import com.intermarche.valuation.domain.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Common base of the instrument-granting offer types: "VOUCHER_GRANT" (bons d'achat) and
 * "COUPON_GRANT" (coupons).
 * <p>
 * A grant computes, at valuation time, the instrument a basket earns: "this ticket grants
 * a 5€ voucher". Like the meal voucher, the resulting {@link AdvantageApplication} is
 * purely informational — it never touches the basket total: issuing the physical
 * instrument (barcode, print) and honouring it later (burn, registry) belong to the
 * register and the instrument registry, not to the valuation engine. The application
 * echoes the usage constraints declared on the offer (validity, minimum purchase,
 * eligible articles, channels) so the downstream systems need no second lookup.
 * <p>
 * The threshold mechanics are shared with the tiered discounts through {@link TierTable}:
 * highest-reached tiers, progressive brackets or a repeating step, on an assiette drawn
 * from the product-aware offer applications (a target EAN list or the whole merchandise
 * total). Amounts are granted in euros or in points ({@code unit}).
 * <p>
 * Concrete subclasses only fix the discriminator and the display label; they share this
 * schema, parsing and computation.
 */
public abstract class InstrumentGrantFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * Ordering constant: after the discounts and the meal voucher, before the upsells.
     */
    private static final double EFFICIENCY_SCORE = -3.0;

    /**
     * JSON Schema definition for validating instrument grant specifications.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Instrument Grant Offer Specification",
      "description": "Defines a voucher or coupon granted by the basket: tier mechanics on an assiette, an amount in euros or points, and echoed usage constraints.",
      "type": "object",
      "required": ["scope", "trigger", "mode"],
      "oneOf": [
        { "required": ["tiers"] },
        { "required": ["every"] }
      ],
      "properties": {
        "scope": {
          "type": "string",
          "enum": ["ITEMS", "TICKET"],
          "description": "ITEMS draws the assiette from the listed EANs; TICKET from the whole merchandise total.",
          "x-label": "Scope"
        },
        "targetEans": {
          "type": "array",
          "minItems": 1,
          "items": { "type": "string" },
          "description": "Products the assiette is drawn from. Required when the scope is ITEMS.",
          "x-widget": "ean-list",
          "x-label": "Eligible products"
        },
        "trigger": {
          "type": "string",
          "enum": ["AMOUNT", "QUANTITY"],
          "description": "Dimension of the thresholds: a monetary amount or a number of units.",
          "x-label": "Trigger"
        },
        "mode": {
          "type": "string",
          "enum": ["HIGHEST_REACHED", "PROGRESSIVE", "PER_MULTIPLE"],
          "description": "How the tiers apply: highest reached once, progressive brackets, or repeating step.",
          "x-label": "Tier mode"
        },
        "unit": {
          "type": "string",
          "enum": ["EUR", "POINTS"],
          "description": "Unit of the granted amount. Defaults to EUR; POINTS amounts are rounded to whole points.",
          "x-label": "Grant unit"
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
              "description": "Size of one step, in the trigger's dimension.",
              "x-label": "Step"
            },
            "award": { "$ref": "#/definitions/award" }
          }
        },
        "usage": {
          "type": "object",
          "additionalProperties": false,
          "description": "Usage constraints echoed to the register and the instrument registry; the engine does not enforce them.",
          "x-widget": "object",
          "x-label": "Usage constraints",
          "properties": {
            "validityDays": {
              "type": "integer",
              "minimum": 1,
              "description": "Validity of the instrument, in days from issuance.",
              "x-label": "Validity (days)"
            },
            "minimumPurchase": {
              "type": "number",
              "minimum": 0,
              "description": "Minimum purchase required to spend the instrument.",
              "x-widget": "money",
              "x-label": "Minimum purchase"
            },
            "usableEans": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Articles the instrument can be spent on.",
              "x-widget": "ean-list",
              "x-label": "Usable products"
            },
            "channels": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Cash-in channels accepting the instrument (e.g. CAISSE, DRIVE).",
              "x-widget": "string-list",
              "x-label": "Channels"
            }
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
              "enum": ["PERCENTAGE", "AMOUNT", "AMOUNT_PER_ITEM", "VAT_AMOUNT"],
              "description": "PERCENTAGE of the assiette; AMOUNT flat (once, or per step in PER_MULTIPLE); AMOUNT_PER_ITEM per unit; VAT_AMOUNT grants the VAT of the assiette.",
              "x-label": "Award type"
            },
            "value": {
              "type": "number",
              "minimum": 0,
              "description": "Percentage or amount, depending on the award type; unused for VAT_AMOUNT.",
              "x-widget": "discount-value",
              "x-label": "Value",
              "x-unit-from": "type"
            }
          }
        }
      }
    }
    """;

    /**
     * Scope of the assiette: a list of products or the whole merchandise total.
     */
    public enum Scope {
        /** The assiette is drawn from the products listed in {@code targetEans}. */
        ITEMS,
        /** The assiette is the merchandise total (product-aware offers only). */
        TICKET
    }

    /**
     * Dimension of the thresholds.
     */
    public enum Trigger {
        /** Thresholds compare against a monetary amount (tax included). */
        AMOUNT,
        /** Thresholds compare against a number of standard units. */
        QUANTITY
    }

    /**
     * How the tiers apply to the assiette.
     */
    public enum Mode {
        /** The highest reached tier applies once. */
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
        /** A percentage of the assiette (or of the slice in PROGRESSIVE mode). */
        PERCENTAGE,
        /** A flat amount: once in HIGHEST_REACHED, once per step in PER_MULTIPLE. */
        AMOUNT,
        /** An amount per targeted unit (per unit of the slice in PROGRESSIVE mode). */
        AMOUNT_PER_ITEM,
        /** The VAT amount of the assiette (HIGHEST_REACHED mode only). */
        VAT_AMOUNT
    }

    /**
     * Unit of the granted amount.
     */
    public enum Unit {
        /** The grant is a monetary amount, rounded to the cent. */
        EUR,
        /** The grant is a number of points, rounded to the whole point. */
        POINTS
    }

    /**
     * The award granted by a tier or a step.
     *
     * @param type  the nature of the award.
     * @param value the percentage or amount; null for VAT_AMOUNT.
     */
    public record Award(AwardType type, BigDecimal value) {
    }

    /**
     * Returns the name of the granted instrument, echoed in the output.
     *
     * @return "VOUCHER" or "COUPON".
     */
    protected abstract String instrument();

    /**
     * Returns the human display label used in the application type string.
     *
     * @return "Voucher Grant" or "Coupon Grant".
     */
    protected abstract String displayLabel();

    /**
     * Returns the JSON Schema describing the instrument grant specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds one applier per offer of this factory's type, for the store and its groups.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return a collection of {@link InstrumentGrantApplier}.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema or the
     *                                  cross-field rules.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        getBasket(basketEvaluation, "Cannot create " + displayLabel() + " appliers without a valid basket.");
        for (Offer offer : getOffers(basketEvaluation, getOfferType())) {
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
        this.processSpecification(OFFER_SCHEMA, offer.specification, (spec) -> {
            Scope scope = Scope.valueOf(spec.get("scope").asText());
            Trigger trigger = Trigger.valueOf(spec.get("trigger").asText());
            Mode mode = Mode.valueOf(spec.get("mode").asText());
            Unit unit = spec.has("unit") ? Unit.valueOf(spec.get("unit").asText()) : Unit.EUR;
            Set<String> targetEans = new LinkedHashSet<>();
            if (spec.has("targetEans")) {
                for (JsonNode ean : spec.get("targetEans")) {
                    targetEans.add(ean.asText());
                }
            }
            validateCrossRules(offer.code, scope, trigger, mode, targetEans, spec);
            TierTable<Award> table = null;
            BigDecimal step = null;
            Award stepAward = null;
            if (mode == Mode.PER_MULTIPLE) {
                JsonNode every = spec.get("every");
                step = every.get("step").decimalValue();
                stepAward = parseAward(offer.code, every.get("award"), mode, trigger);
            } else {
                List<TierTable.Tier<Award>> tiers = new ArrayList<>();
                for (JsonNode tierNode : spec.get("tiers")) {
                    tiers.add(new TierTable.Tier<>(
                            tierNode.get("threshold").decimalValue(),
                            parseAward(offer.code, tierNode.get("award"), mode, trigger)));
                }
                table = TierTable.of(tiers);
            }
            List<Product> targetProducts = scope == Scope.ITEMS
                    ? Product.findByEans(targetEans)
                    : List.of();
            JsonNode usage = spec.has("usage") ? spec.get("usage") : null;
            appliers.add(new InstrumentGrantApplier(offer.code, scope, trigger, mode, unit,
                    table, step, stepAward, targetProducts, usage));
        });
    }

    /**
     * Parses one award node and validates its combination rules.
     *
     * @param offerCode the offer code, for error messages.
     * @param node      the award node.
     * @param mode      the tier mode, for validation.
     * @param trigger   the trigger dimension, for validation.
     * @return the parsed award.
     * @throws IllegalArgumentException if the award violates a cross-field rule.
     */
    private Award parseAward(String offerCode, JsonNode node, Mode mode, Trigger trigger) {
        AwardType type = AwardType.valueOf(node.get("type").asText());
        BigDecimal value = node.has("value") ? node.get("value").decimalValue() : null;
        if (type != AwardType.VAT_AMOUNT && value == null) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': award type %s requires a value.", getOfferType(), offerCode, type));
        }
        if (type == AwardType.VAT_AMOUNT && mode != Mode.HIGHEST_REACHED) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': the VAT_AMOUNT award requires the HIGHEST_REACHED mode.",
                    getOfferType(), offerCode));
        }
        if (mode == Mode.PROGRESSIVE && type != AwardType.PERCENTAGE && type != AwardType.AMOUNT_PER_ITEM) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': mode PROGRESSIVE only allows PERCENTAGE and AMOUNT_PER_ITEM awards.",
                    getOfferType(), offerCode));
        }
        if (mode == Mode.PROGRESSIVE && type == AwardType.AMOUNT_PER_ITEM && trigger != Trigger.QUANTITY) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': AMOUNT_PER_ITEM in PROGRESSIVE mode requires the QUANTITY trigger.",
                    getOfferType(), offerCode));
        }
        if (mode == Mode.PER_MULTIPLE && type == AwardType.AMOUNT_PER_ITEM) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': mode PER_MULTIPLE does not allow the AMOUNT_PER_ITEM award.",
                    getOfferType(), offerCode));
        }
        return new Award(type, value);
    }

    /**
     * Validates the specification rules spanning several fields.
     *
     * @param offerCode  the offer code, for error messages.
     * @param scope      the assiette scope.
     * @param trigger    the trigger dimension.
     * @param mode       the tier mode.
     * @param targetEans the parsed target EANs.
     * @param spec       the parsed specification.
     * @throws IllegalArgumentException if a rule is violated.
     */
    private void validateCrossRules(String offerCode, Scope scope, Trigger trigger, Mode mode,
                                    Set<String> targetEans, JsonNode spec) {
        if (scope == Scope.ITEMS && targetEans.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': scope ITEMS requires targetEans.", getOfferType(), offerCode));
        }
        if (scope == Scope.TICKET && trigger == Trigger.QUANTITY) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': scope TICKET requires the AMOUNT trigger.", getOfferType(), offerCode));
        }
        if (scope == Scope.TICKET && spec.has("tiers")) {
            for (JsonNode tierNode : spec.get("tiers")) {
                String type = tierNode.get("award").get("type").asText();
                if (AwardType.valueOf(type) == AwardType.AMOUNT_PER_ITEM) {
                    throw new IllegalArgumentException(String.format(
                            "%s offer '%s': scope TICKET does not allow the AMOUNT_PER_ITEM award.",
                            getOfferType(), offerCode));
                }
            }
        }
        if (mode == Mode.PER_MULTIPLE && !spec.has("every")) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': mode PER_MULTIPLE requires 'every'.", getOfferType(), offerCode));
        }
        if (mode != Mode.PER_MULTIPLE && !spec.has("tiers")) {
            throw new IllegalArgumentException(String.format(
                    "%s offer '%s': modes HIGHEST_REACHED and PROGRESSIVE require 'tiers'.",
                    getOfferType(), offerCode));
        }
    }

    /**
     * One product's contribution to the assiette through one offer application.
     *
     * @param quantity the covered quantity in standard units; zero in TICKET scope.
     * @param amount   the amount the application attributes to the product (or its whole
     *                 amount in TICKET scope).
     */
    private record Contribution(double quantity, AmountEvaluation amount) {
    }

    /**
     * Applier of one instrument grant offer.
     */
    public class InstrumentGrantApplier implements AdvantageApplier {

        /**
         * The offer code, used in labels and error messages.
         */
        private final String code;

        /**
         * The assiette scope.
         */
        private final Scope scope;

        /**
         * The trigger dimension of the thresholds.
         */
        private final Trigger trigger;

        /**
         * The tier mode.
         */
        private final Mode mode;

        /**
         * The unit of the granted amount.
         */
        private final Unit unit;

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
         * The usage constraints echoed to the output; may be null.
         */
        private final JsonNode usage;

        /**
         * Creates the applier for one offer.
         *
         * @param code           the offer code.
         * @param scope          the assiette scope.
         * @param trigger        the trigger dimension.
         * @param mode           the tier mode.
         * @param unit           the unit of the granted amount.
         * @param table          the tier table; null in PER_MULTIPLE mode.
         * @param step           the repeating step; null unless in PER_MULTIPLE mode.
         * @param stepAward      the award of the repeating step; null unless PER_MULTIPLE.
         * @param targetProducts the targeted products; empty in TICKET scope.
         * @param usage          the echoed usage constraints; may be null.
         */
        public InstrumentGrantApplier(String code, Scope scope, Trigger trigger, Mode mode, Unit unit,
                                      TierTable<Award> table, BigDecimal step, Award stepAward,
                                      List<Product> targetProducts, JsonNode usage) {
            this.code = code;
            this.scope = scope;
            this.trigger = trigger;
            this.mode = mode;
            this.unit = unit;
            this.table = table;
            this.step = step;
            this.stepAward = stepAward;
            this.targetProducts = targetProducts;
            this.usage = usage;
        }

        /**
         * A grant is informational: it never attaches to a specific offer applier and
         * must not switch the standard lines to the reference price.
         *
         * @param offerApplier the offer applier to check.
         * @return always {@code false}.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return false;
        }

        /**
         * Returns the ordering score of this applier.
         *
         * @return the constant {@code -3.0}: after the discounts and the meal voucher,
         *         before the upsell suggestions.
         */
        @Override
        public double getEfficiencyScore() {
            return EFFICIENCY_SCORE;
        }

        /**
         * Computes the granted instrument for the current evaluation.
         * <p>
         * The assiette is gathered from the product-aware offer applications, the tier
         * mode computes the granted amount, and a single informational application is
         * emitted when the amount is strictly positive. Nothing is capped: the RFP
         * explicitly allows instruments larger than the assiette.
         *
         * @param evaluation the evaluation context containing the applied offers.
         * @return zero or one {@link InstrumentGrantApplication}.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<AdvantageApplication> applications = new ArrayList<>();
            List<Contribution> contributions = collectContributions(evaluation);
            if (contributions.isEmpty()) {
                return applications;
            }
            BigDecimal baseAmount = BigDecimal.ZERO;
            BigDecimal baseVat = BigDecimal.ZERO;
            BigDecimal baseQuantity = BigDecimal.ZERO;
            for (Contribution contribution : contributions) {
                baseAmount = baseAmount.add(contribution.amount().amountIncludingTax);
                baseVat = baseVat.add(contribution.amount().amountIncludingTax
                        .subtract(contribution.amount().amountExcludingTax));
                baseQuantity = baseQuantity.add(BigDecimal.valueOf(contribution.quantity()));
            }
            if (baseAmount.signum() <= 0) {
                return applications;
            }
            BigDecimal base = (trigger == Trigger.AMOUNT) ? baseAmount : baseQuantity;
            BigDecimal amount;
            String detail;
            switch (mode) {
                case HIGHEST_REACHED -> {
                    Optional<TierTable.Tier<Award>> tier = table.resolveHighest(base);
                    if (tier.isEmpty()) {
                        return applications;
                    }
                    amount = awardValue(tier.get().award(), baseAmount, baseVat, baseQuantity, 1);
                    detail = "tier " + tier.get().threshold();
                }
                case PROGRESSIVE -> {
                    List<TierTable.Slice<Award>> slices = table.slices(base);
                    if (slices.isEmpty()) {
                        return applications;
                    }
                    BigDecimal avgUnit = averageUnit(baseAmount, baseQuantity);
                    BigDecimal total = BigDecimal.ZERO;
                    for (TierTable.Slice<Award> slice : slices) {
                        if (slice.award().type() == AwardType.PERCENTAGE) {
                            BigDecimal portionAmount = (trigger == Trigger.AMOUNT)
                                    ? slice.portion()
                                    : slice.portion().multiply(avgUnit);
                            total = total.add(portionAmount.multiply(percent(slice.award().value())));
                        } else { // AMOUNT_PER_ITEM, guaranteed QUANTITY trigger by validation
                            total = total.add(slice.portion().multiply(slice.award().value()));
                        }
                    }
                    amount = total;
                    detail = "progressive";
                }
                case PER_MULTIPLE -> {
                    int multiples = TierTable.multiples(base, step);
                    if (multiples <= 0) {
                        return applications;
                    }
                    BigDecimal covered = step.multiply(BigDecimal.valueOf(multiples));
                    amount = switch (stepAward.type()) {
                        case PERCENTAGE -> {
                            BigDecimal coveredAmount = (trigger == Trigger.AMOUNT)
                                    ? covered
                                    : covered.multiply(averageUnit(baseAmount, baseQuantity));
                            yield coveredAmount.multiply(percent(stepAward.value()));
                        }
                        case AMOUNT -> stepAward.value().multiply(BigDecimal.valueOf(multiples));
                        default -> null; // unreachable: rejected at parse time
                    };
                    detail = "x" + multiples;
                }
                default -> {
                    return applications;
                }
            }
            if (amount == null) {
                return applications;
            }
            amount = (unit == Unit.POINTS)
                    ? amount.setScale(0, RoundingMode.HALF_UP)
                    : amount.setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) {
                return applications;
            }
            applications.add(new InstrumentGrantApplication(displayLabel(), code, detail,
                    new Grant(instrument(), unit.name(), amount, usage)));
            return applications;
        }

        /**
         * Gathers the contributions of the assiette from the product-aware applications.
         *
         * @param evaluation the evaluation context.
         * @return the contributions, empty when nothing is covered.
         */
        private List<Contribution> collectContributions(BasketEvaluation evaluation) {
            List<Contribution> contributions = new ArrayList<>();
            if (evaluation.getOffers() == null) {
                return contributions;
            }
            for (OfferApplication app : evaluation.getOffers()) {
                if (!(app instanceof ProductAwareOfferApplication productAwareApp)) {
                    continue;
                }
                if (scope == Scope.TICKET) {
                    AmountEvaluation amount = productAwareApp.getAmount();
                    if (amount != null && amount.amountIncludingTax.signum() > 0) {
                        contributions.add(new Contribution(0.0, amount));
                    }
                    continue;
                }
                for (Product product : targetProducts) {
                    double quantity = productAwareApp.getProductQuantity(product);
                    if (quantity <= 0) {
                        continue;
                    }
                    AmountEvaluation amount = productAwareApp.getProductAmount(product);
                    if (amount == null || amount.amountIncludingTax.signum() <= 0) {
                        continue;
                    }
                    contributions.add(new Contribution(quantity, amount));
                }
            }
            return contributions;
        }

        /**
         * Computes the value of one award applied once in HIGHEST_REACHED mode.
         *
         * @param award        the award to value.
         * @param baseAmount   the monetary assiette, tax included.
         * @param baseVat      the VAT amount of the assiette.
         * @param baseQuantity the assiette in standard units.
         * @param times        how many times the award applies.
         * @return the raw granted value.
         */
        private BigDecimal awardValue(Award award, BigDecimal baseAmount, BigDecimal baseVat,
                                      BigDecimal baseQuantity, int times) {
            return switch (award.type()) {
                case PERCENTAGE -> baseAmount.multiply(percent(award.value()));
                case AMOUNT -> award.value().multiply(BigDecimal.valueOf(times));
                case AMOUNT_PER_ITEM -> award.value().multiply(baseQuantity);
                case VAT_AMOUNT -> baseVat;
            };
        }

        /**
         * Computes the average unit price of the assiette, used to value quantity slices.
         *
         * @param baseAmount   the monetary assiette, tax included.
         * @param baseQuantity the assiette in standard units.
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
     * The granted instrument, as echoed in the valuation output.
     *
     * @param instrument the instrument name ("VOUCHER" or "COUPON").
     * @param unit       the unit of the amount ("EUR" or "POINTS").
     * @param amount     the granted amount, rounded per its unit.
     * @param usage      the usage constraints declared on the offer; may be null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Grant(String instrument, String unit, BigDecimal amount, JsonNode usage) {
    }

    /**
     * The informational application describing the granted instrument.
     * <p>
     * Deliberately not a {@link com.intermarche.valuation.engine.DiscountApplication}:
     * a grant never changes the basket total.
     */
    public static class InstrumentGrantApplication implements AdvantageApplication {

        /**
         * The display label of the granting type ("Voucher Grant" or "Coupon Grant").
         */
        private final String label;

        /**
         * The offer code.
         */
        private final String offerCode;

        /**
         * The display detail of the applied resolution (reached tier, progressive, xN).
         */
        private final String detail;

        /**
         * The granted instrument.
         */
        private final Grant grant;

        /**
         * Creates the application.
         *
         * @param label     the display label of the granting type.
         * @param offerCode the offer code.
         * @param detail    the display detail of the applied resolution.
         * @param grant     the granted instrument.
         */
        public InstrumentGrantApplication(String label, String offerCode, String detail, Grant grant) {
            this.label = label;
            this.offerCode = offerCode;
            this.detail = detail;
            this.grant = grant;
        }

        /**
         * Returns the display type of this application.
         *
         * @return a string of the form {@code Voucher Grant: <code> (<detail>)}.
         */
        public String getType() {
            return label + ": " + offerCode + " (" + detail + ")";
        }

        /**
         * Returns the code of the offer that granted the instrument.
         *
         * @return the offer code.
         */
        public String getOfferCode() {
            return offerCode;
        }

        /**
         * Returns the granted instrument.
         *
         * @return the grant description.
         */
        public Grant getGrant() {
            return grant;
        }

        /**
         * A grant relates to the basket globally, not to one offer application.
         *
         * @return {@code null}, always.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return null;
        }

        /**
         * Returns the type string, shielding the default resolution from the null
         * target application.
         *
         * @return the same string as {@link #getType()}.
         */
        @Override
        @JsonIgnore
        public String getOffer() {
            return getType();
        }
    }
}
