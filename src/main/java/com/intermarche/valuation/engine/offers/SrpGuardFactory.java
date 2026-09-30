package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.NetAmounts;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductScopedDiscount;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Factory for the {@code SRP_GUARD} advantage: an end-of-valuation floor check that lifts a line
 * back to its resale-below-cost floor when the cumulated advantages drove its net below it
 * (spec-srp-guard §2, §3). It is the "EGAlim bis" — the same canonical pattern as
 * {@link EgalimGuardFactory}, one step further in the arbitration order.
 * <p>
 * The floor is data: the retailer's commercial management pushes a {@link PriceUsage#SRP_FLOOR}
 * price row (purchase price × 1.10) through the ordinary price feed, with its temporal validity,
 * priority and VAT coherence. The floor of an article in a store is its most-prioritary active
 * {@code SRP_FLOOR} row; a line whose article has no such row is simply not controlled — the guard
 * fails open on data (the law only targets food, so floors are pushed only where they apply).
 * <p>
 * Like the EGAlim guard, this is a real configuration persisted in the {@code Offer} table, subject
 * to the in-force finders and targeted to a store or store groups, never global; its specification
 * is empty ({@code {}}). Its arbitration position is structural: {@link SrpGuardApplier#getConfiguration()}
 * returns a forced configuration with {@code applicationMoment = AT_TOTAL} and
 * {@code priority = Integer.MAX_VALUE} — the very last of the ascending order, after the EGAlim
 * guard (which sits at {@code Integer.MAX_VALUE - 1}). EGAlim corrects the generosity first, SRP
 * locks the resulting price last. On an open basket the {@code AT_TOTAL} wave does not run, so the
 * guard neither corrects nor records.
 * <p>
 * Compute semantics (spec-srp-guard §3), per initial basket line carrying a floor: {@code floor} is
 * the floor unit price (tax included) times the quantity; {@code final} is the net retained after
 * every advantage, the EGAlim reprise included (it has just applied); when {@code final < floor} a
 * negative correction lifts the line exactly to the floor. Lines discounted by a
 * {@code compliance.srpExempt} configuration are traced but never corrected (the sales case).
 */
@ApplicationScoped
public class SrpGuardFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * Logger for the "several in-force guards" trace.
     */
    private static final Logger LOGGER = Logger.getLogger(SrpGuardFactory.class);

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "SRP_GUARD";

    /**
     * Stable reason code (spec-srp-guard §3.A): the line was clamped up to its floor — the requested
     * discount was not applied in full. Distinct from the EGAlim record, which carries a regime.
     */
    public static final String REASON_FLOOR_CLAMPED = "SRP_FLOOR_CLAMPED";

    /**
     * Stable reason code (spec-srp-guard §3.B): the discount reached a configured warning palier of the
     * descent toward the floor, without being clamped.
     */
    public static final String REASON_MARGIN_WARNING = "SRP_MARGIN_WARNING";

    /**
     * Stable reason code (spec-srp-guard §3.C): the floor was bypassed for this line by a per-line
     * degraded-product derogation declared at the till.
     */
    public static final String REASON_OVERRIDE = "SRP_OVERRIDE";

    /**
     * The forced synthetic specification of the guard's configuration: AT_TOTAL, dead last in the
     * arbitration order ({@code 2147483647} is {@link Integer#MAX_VALUE}), cumulable, consuming
     * nothing. Read by {@code parseArbitrationConfig} without schema re-validation.
     */
    private static final String FORCED_SPECIFICATION =
            "{\"applicationMoment\":\"AT_TOTAL\","
                    + "\"arbitration\":{\"priority\":2147483647,\"cumulable\":true,\"consumesContributors\":false}}";

    /**
     * JSON Schema for the SRP guard specification: an empty object. The floor is carried entirely by
     * the price feed, so the configuration has no field of its own; the guard's arbitration position
     * is structural, not negotiable, hence no {@code trigger} nor {@code arbitration} to declare.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "SRP Guard Offer Specification",
      "description": "Locks a line to its resale-below-cost floor (SRP + 10%). The floor is a price row; the only field is the optional warning paliers.",
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "warningThresholds": {
          "type": "array",
          "minItems": 1,
          "description": "Optional warning paliers (spec-srp-guard §3.B): fractions of the descent toward the floor, strictly between 0 and 1 (e.g. 0.5 and 0.8). A line whose discount reaches a palier without breaching the floor is flagged SRP_MARGIN_WARNING, uncorrected. Administered per point of sale like the guard itself; absent means no warning, only the binary floor.",
          "items": { "type": "number", "exclusiveMinimum": 0, "exclusiveMaximum": 1 },
          "x-label": "Warning paliers"
        }
      }
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the {@code "SRP_GUARD"} discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the SRP guard specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds the guard applier from the single in-force {@code SRP_GUARD} configuration of the store
     * and its groups.
     * <p>
     * At most one configuration is expected; when several are in force, the first in the arbitration
     * order (by code) applies and the others are ignored with a trace. A basket without a
     * configuration builds no applier, so the valuation is unchanged (regression-free).
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return the guard applier, or an empty collection when no configuration is in force.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if the configuration's specification violates the schema.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        getBasket(basketEvaluation, "Cannot create SRP Guard appliers without a valid basket.");
        Store store = basketEvaluation.getStore();
        List<Offer> offers = new ArrayList<>(getOffers(basketEvaluation, OFFER_TYPE));
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            if (i == 0) {
                processOffer(offer, appliers, store);
            } else {
                String message = "SRP_GUARD configuration '" + offer.code
                        + "' ignored: another guard is already in force.";
                LOGGER.warn(message);
                basketEvaluation.recordSkippedConfiguration(message);
            }
        }
        return appliers;
    }

    /**
     * Validates one guard specification against the schema and adds the corresponding applier.
     *
     * @param offer    the offer to process.
     * @param appliers the list receiving the created applier.
     * @param store    the store, for the floor-price lookups.
     * @throws IllegalArgumentException if the specification violates the schema.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) ->
                appliers.add(new SrpGuardApplier(offer.code, store, parseWarningThresholds(spec))));
    }

    /**
     * Parses the optional {@code warningThresholds} of a guard specification (spec-srp-guard §3.B)
     * into an ascending list of fractions.
     * <p>
     * The schema already bounds each value to {@code (0, 1)}; this reads them, sorts them ascending so
     * the "highest palier reached" is a simple scan, and returns an empty list when the field is
     * absent — the binary floor, no warning.
     *
     * @param spec the parsed guard specification.
     * @return the warning paliers, ascending; empty when none is configured.
     */
    static List<BigDecimal> parseWarningThresholds(com.fasterxml.jackson.databind.JsonNode spec) {
        List<BigDecimal> thresholds = new ArrayList<>();
        if (spec == null) {
            return thresholds;
        }
        com.fasterxml.jackson.databind.JsonNode node = spec.get("warningThresholds");
        if (node == null || !node.isArray()) {
            return thresholds;
        }
        for (com.fasterxml.jackson.databind.JsonNode value : node) {
            thresholds.add(value.decimalValue());
        }
        thresholds.sort(java.util.Comparator.naturalOrder());
        return thresholds;
    }

    /**
     * The SRP guard applier: computes the per-line floor record and the negative correction.
     */
    public static class SrpGuardApplier implements AdvantageApplier {

        /**
         * The configuration code, used in the correction label.
         */
        private final String code;

        /**
         * The store, for the floor reference-price lookups.
         */
        private final Store store;

        /**
         * The forced synthetic configuration returned by {@link #getConfiguration()}, built once.
         */
        private final Offer forcedConfiguration;

        /**
         * The warning paliers (spec-srp-guard §3.B), ascending; empty when none is configured.
         */
        private final List<BigDecimal> warningThresholds;

        /**
         * Creates the applier and its forced synthetic configuration.
         *
         * @param code              the configuration code.
         * @param store             the store, for floor-price lookups; may be null.
         * @param warningThresholds the warning paliers (ascending), possibly empty.
         */
        public SrpGuardApplier(String code, Store store, List<BigDecimal> warningThresholds) {
            this.code = code;
            this.store = store;
            this.warningThresholds = warningThresholds == null ? List.of() : warningThresholds;
            this.forcedConfiguration = new Offer();
            this.forcedConfiguration.code = code;
            this.forcedConfiguration.type = OFFER_TYPE;
            this.forcedConfiguration.specification = FORCED_SPECIFICATION;
        }

        /**
         * The guard never registers on an offer applier: it must not trigger a reference-price
         * switch and it reads the applied offers directly at application time.
         *
         * @param offerApplier the offer applier to check.
         * @return always {@code false}.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return false;
        }

        /**
         * Returns the sandbox efficiency score, zero by convention: the guard competes with no one,
         * being alone in its priority wave.
         *
         * @return {@code 0.0}.
         */
        @Override
        public double getEfficiencyScore() {
            return 0.0;
        }

        /**
         * Returns the forced synthetic configuration: AT_TOTAL, priority {@link Integer#MAX_VALUE},
         * cumulable, consuming nothing.
         *
         * @return the forced configuration.
         */
        @Override
        public Offer getConfiguration() {
            return forcedConfiguration;
        }

        /**
         * Runs the guard: builds the per-line SRP record on the evaluation and returns the single
         * negative correction advantage when a floor was breached (spec-srp-guard §3).
         * <p>
         * Called once, last in the {@code AT_TOTAL} wave (after the EGAlim reprise), so the net it
         * reads already includes every other advantage. It always sets the record (even empty); it
         * returns an advantage only when the summed corrections are positive.
         *
         * @param evaluation the evaluation context.
         * @return the single negative correction advantage, or an empty list when no floor is breached.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<BasketEvaluation.SrpLine> record = new ArrayList<>();
            Basket basket = evaluation.getBasket();
            if (basket == null || basket.items == null) {
                evaluation.setSrpRecord(record);
                return List.of();
            }
            Map<String, BigDecimal> netByLine = netTtcByLine(evaluation);
            Map<String, BigDecimal> nominalByLine = new HashMap<>();
            Map<String, List<String>> linesByEan = new HashMap<>();
            indexNominals(basket, nominalByLine, linesByEan);
            Map<String, BigDecimal> egalimRaiseByLine = egalimRaiseByLine(evaluation, nominalByLine, linesByEan);
            Set<String> exemptLines = srpExemptLines(evaluation);
            List<SrpCorrectionDetail> corrections = new ArrayList<>();
            BigDecimal totalCorrHt = BigDecimal.ZERO;
            BigDecimal totalCorrTtc = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                if (item.lineId == null) {
                    continue;
                }
                AmountEvaluation floorAmount = floorOf(item);
                if (floorAmount == null || floorAmount.amountIncludingTax == null) {
                    // No SRP_FLOOR row for this article: not controlled, absent from the record.
                    continue;
                }
                String lineId = item.lineId;
                BigDecimal floor = scale2(floorAmount.amountIncludingTax);
                BigDecimal rate = floorAmount.vatRate == null ? BigDecimal.ZERO : floorAmount.vatRate;
                BigDecimal preNet = netByLine.containsKey(lineId)
                        ? netByLine.get(lineId)
                        : nominalByLine.getOrDefault(lineId, floor);
                BigDecimal finalTtc = preNet.add(egalimRaiseByLine.getOrDefault(lineId, BigDecimal.ZERO));
                if (finalTtc.signum() < 0) {
                    finalTtc = BigDecimal.ZERO;
                }
                finalTtc = scale2(finalTtc);
                BigDecimal nominal = nominalByLine.get(lineId);
                LineDecision decision = decideLine(lineId, item.produceEan, floor, nominal, finalTtc,
                        warningThresholds, exemptLines.contains(lineId), item.srpOverride,
                        item.srpOverrideReason, item.hasManualGesture());
                record.add(decision.line);
                if (decision.correctionTtc.signum() > 0) {
                    BigDecimal correctionTtc = decision.correctionTtc;
                    BigDecimal correctionHt = correctionTtc.divide(
                            BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                    totalCorrTtc = totalCorrTtc.add(correctionTtc);
                    totalCorrHt = totalCorrHt.add(correctionHt);
                    corrections.add(new SrpCorrectionDetail(lineId, item.produceEan, correctionTtc));
                }
            }
            evaluation.setSrpRecord(record);
            if (totalCorrTtc.signum() > 0) {
                BigDecimal blendedRate = totalCorrHt.signum() > 0
                        ? totalCorrTtc.divide(totalCorrHt, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                        : BigDecimal.ZERO;
                // Stored negative: the engine subtracts a discount amount, so a negative value lifts
                // the line back up to its floor — the SRP adjustment (spec-srp-guard §3.4).
                AmountEvaluation amount = new AmountEvaluation(
                        totalCorrHt.negate(), totalCorrTtc.negate(), blendedRate);
                return List.of(new SrpAdjustmentApplication(code, amount, corrections));
            }
            return List.of();
        }

        /**
         * Computes the floor amount of a line: the {@link PriceUsage#SRP_FLOOR} unit price times the
         * quantity, tax included (spec-srp-guard §3.1).
         * <p>
         * The lookup is DIRECT and does <em>not</em> fall back to the default price the way
         * {@code Basket.Item#getPrice} does: a line whose article has no active {@code SRP_FLOOR} row
         * is not floor-controlled, so this returns {@code null} rather than borrowing the shelf price
         * as a floor. A line without an EAN or an unpriceable article also yields {@code null}.
         *
         * @param item the initial basket line.
         * @return the floor amount, or {@code null} when the line has no floor.
         */
        private AmountEvaluation floorOf(Basket.Item item) {
            if (item.produceEan == null || store == null) {
                return null;
            }
            Product product = item.getProduct();
            if (product == null) {
                return null;
            }
            Price floor = Price.findActivePriceAtDate(product.id, store.id, resolveDate(item), PriceUsage.SRP_FLOOR);
            if (floor == null) {
                return null;
            }
            try {
                return AmountEvaluation.getAmount(product, floor, item.quantity);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /**
         * Resolves the date at which the floor is looked up: the line's own {@code priceDate} when it
         * carries one, otherwise {@link DateTimeProvider#now()} — the same rule the ordinary price
         * lookup uses.
         *
         * @param item the initial basket line.
         * @return the lookup date.
         */
        private LocalDateTime resolveDate(Basket.Item item) {
            if (item.priceDate != null && !item.priceDate.isBlank()) {
                try {
                    return LocalDateTime.parse(item.priceDate);
                } catch (RuntimeException e) {
                    return DateTimeProvider.now();
                }
            }
            return DateTimeProvider.now();
        }

        /**
         * Builds the net tax-included amount paid for each initial line, keyed by line id.
         * <p>
         * Mirrors the EGAlim guard: each available offer application is grouped by EAN, netted once
         * with {@link NetAmounts#netProductTtc}, and distributed to the lines by gross weight. This
         * nets the offer-attributed advantages; the EGAlim reprise (a null-application discount) is
         * added on top by {@link #egalimRaiseByLine}.
         *
         * @param evaluation the evaluation context.
         * @return the net tax-included amount per line id, before the EGAlim reprise.
         */
        private Map<String, BigDecimal> netTtcByLine(BasketEvaluation evaluation) {
            Map<String, BigDecimal> netByLine = new HashMap<>();
            for (OfferApplication app : evaluation.getAvailableOffers()) {
                List<BasketEvaluation.Item> valued = app.getValuedItems();
                if (valued == null || valued.isEmpty()) {
                    continue;
                }
                Map<String, List<BasketEvaluation.Item>> byEan = new LinkedHashMap<>();
                for (BasketEvaluation.Item item : valued) {
                    if (item.amount == null || item.amount.amountIncludingTax == null) {
                        continue;
                    }
                    byEan.computeIfAbsent(item.produceEan, k -> new ArrayList<>()).add(item);
                }
                for (Map.Entry<String, List<BasketEvaluation.Item>> entry : byEan.entrySet()) {
                    List<BasketEvaluation.Item> items = entry.getValue();
                    BigDecimal groupGross = BigDecimal.ZERO;
                    for (BasketEvaluation.Item item : items) {
                        groupGross = groupGross.add(item.amount.amountIncludingTax);
                    }
                    if (groupGross.signum() <= 0) {
                        continue;
                    }
                    BigDecimal netGroup = NetAmounts.netProductTtc(evaluation, app, entry.getKey(), groupGross);
                    for (BasketEvaluation.Item item : items) {
                        if (item.lineId == null) {
                            continue;
                        }
                        BigDecimal share = netGroup.multiply(item.amount.amountIncludingTax)
                                .divide(groupGross, 2, RoundingMode.HALF_UP);
                        netByLine.merge(item.lineId, share, BigDecimal::add);
                    }
                }
            }
            return netByLine;
        }

        /**
         * Indexes, for every priceable initial line, its nominal tax-included amount and the lines
         * carrying each EAN — used to spread the EGAlim reprise back onto lines.
         *
         * @param basket        the basket.
         * @param nominalByLine output: nominal tax-included amount per line id.
         * @param linesByEan    output: the line ids carrying each EAN.
         */
        private void indexNominals(Basket basket, Map<String, BigDecimal> nominalByLine,
                                   Map<String, List<String>> linesByEan) {
            for (Basket.Item item : basket.items) {
                if (item.lineId == null || item.produceEan == null) {
                    continue;
                }
                AmountEvaluation nominal = nominalOf(item);
                if (nominal == null || nominal.amountIncludingTax == null) {
                    continue;
                }
                nominalByLine.put(item.lineId, nominal.amountIncludingTax);
                linesByEan.computeIfAbsent(item.produceEan, k -> new ArrayList<>()).add(item.lineId);
            }
        }

        /**
         * Computes the nominal amount of a line: the reference price times the quantity.
         * <p>
         * A line with an EAN is priced at {@link PriceUsage#BASE_FOR_DISCOUNT} (falling back to the
         * default price when no reference row exists), like the EGAlim nominal; a line without an EAN
         * uses its entered price.
         *
         * @param item the initial basket line.
         * @return the nominal amount, or {@code null} when the line cannot be priced.
         */
        private AmountEvaluation nominalOf(Basket.Item item) {
            if (item.produceEan != null) {
                try {
                    return AmountEvaluation.getAmount(item, store, PriceUsage.BASE_FOR_DISCOUNT);
                } catch (RuntimeException e) {
                    return null;
                }
            }
            if (item.pricePerUnitInclTax == null || item.pricePerUnitExclTax == null
                    || item.vatRate == null || item.quantity == null) {
                return null;
            }
            return new AmountEvaluation(
                    item.pricePerUnitExclTax.multiply(item.quantity),
                    item.pricePerUnitInclTax.multiply(item.quantity),
                    item.vatRate);
        }

        /**
         * Spreads the EGAlim reprise back onto the initial lines so the SRP net reflects it
         * (spec-srp-guard §3.2, "EGAlim compris").
         * <p>
         * The EGAlim adjustment is a single null-application discount that {@link #netTtcByLine} does
         * not attribute; this reads its per-line correction detail, sums it by EAN and spreads each
         * EAN's total (a positive raise) across the lines carrying that EAN by nominal gross weight.
         * For the ordinary one-line-per-EAN ticket this is exact; when several lines share an EAN the
         * raise is prorated by their nominal weight.
         *
         * @param evaluation    the evaluation context.
         * @param nominalByLine the nominal amount per line id.
         * @param linesByEan    the line ids carrying each EAN.
         * @return the EGAlim raise (positive, tax included) per line id.
         */
        private Map<String, BigDecimal> egalimRaiseByLine(BasketEvaluation evaluation,
                                                          Map<String, BigDecimal> nominalByLine,
                                                          Map<String, List<String>> linesByEan) {
            Map<String, BigDecimal> raiseByLine = new HashMap<>();
            Map<String, BigDecimal> correctionByEan = new HashMap<>();
            for (AdvantageApplication advantage : evaluation.getAdvantages()) {
                if (!(advantage instanceof EgalimGuardFactory.EgalimAdjustmentApplication egalim)) {
                    continue;
                }
                for (EgalimGuardFactory.CorrectionDetail detail : egalim.getCorrections()) {
                    if (detail.ean != null && detail.correction != null) {
                        correctionByEan.merge(detail.ean, detail.correction, BigDecimal::add);
                    }
                }
            }
            for (Map.Entry<String, BigDecimal> entry : correctionByEan.entrySet()) {
                List<String> lines = linesByEan.getOrDefault(entry.getKey(), List.of());
                BigDecimal sumNominal = BigDecimal.ZERO;
                for (String lineId : lines) {
                    sumNominal = sumNominal.add(nominalByLine.getOrDefault(lineId, BigDecimal.ZERO));
                }
                if (sumNominal.signum() <= 0) {
                    continue;
                }
                for (String lineId : lines) {
                    BigDecimal weight = nominalByLine.getOrDefault(lineId, BigDecimal.ZERO);
                    BigDecimal share = entry.getValue().multiply(weight)
                            .divide(sumNominal, 2, RoundingMode.HALF_UP);
                    raiseByLine.merge(lineId, share, BigDecimal::add);
                }
            }
            return raiseByLine;
        }

        /**
         * Builds the set of line ids discounted by a {@code compliance.srpExempt} configuration
         * (spec-srp-guard §3.3): those lines are traced but never lifted to the floor (the sales
         * case).
         * <p>
         * A srp-exempt discount marks the valued lines of its targeted application — restricted to
         * its own EAN when it is product-scoped, the whole application otherwise, mirroring the
         * EGAlim-exempt attribution.
         *
         * @param evaluation the evaluation context.
         * @return the exempt line ids.
         */
        private Set<String> srpExemptLines(BasketEvaluation evaluation) {
            Set<String> lines = new HashSet<>();
            for (AdvantageApplication advantage : evaluation.getAdvantages()) {
                if (!advantage.isSrpExempt() || !(advantage instanceof DiscountApplication discount)) {
                    continue;
                }
                OfferApplication target = discount.getOfferApplication();
                if (target == null) {
                    continue;
                }
                String scopedEan = advantage instanceof ProductScopedDiscount scoped
                        ? scoped.discountedEan() : null;
                for (BasketEvaluation.Item item : target.getValuedItems()) {
                    if (item.lineId == null) {
                        continue;
                    }
                    if (scopedEan != null && !Objects.equals(scopedEan, item.produceEan)) {
                        continue;
                    }
                    lines.add(item.lineId);
                }
            }
            return lines;
        }

        /**
         * Rounds an amount to the cent.
         *
         * @param value the amount.
         * @return the amount at scale 2.
         */
        private static BigDecimal scale2(BigDecimal value) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }

        /**
         * Decides one controlled line's SRP outcome and builds its record entry (spec-srp-guard §3).
         * <p>
         * Pure function of the line's figures, so every branch is unit-testable without the price feed.
         * The order of the checks is the semantics: an override wins over everything (the floor is
         * bypassed, traced, never corrected — §3.C); an {@code srpExempt} line is traced but never
         * corrected (the sales case); otherwise a line below its floor is clamped up to it
         * ({@code SRP_FLOOR_CLAMPED}, with the requested/granted discount when it carries a manual
         * gesture — §3.A); a line above its floor that reaches a configured warning palier is flagged
         * ({@code SRP_MARGIN_WARNING}, uncorrected — §3.B); anything else is granted without reserve.
         *
         * @param lineId            the controlled line id.
         * @param ean               the line's EAN.
         * @param floor             the floor price, tax included, scale 2.
         * @param nominal           the sale (reference) price times quantity, tax included; may be null.
         * @param finalTtc          the net after every advantage (EGAlim included), scale 2.
         * @param warningThresholds the configured warning paliers, ascending; possibly empty.
         * @param exempt            whether an {@code srpExempt} configuration discounts the line.
         * @param overridden        whether the line carries a per-line degraded-product derogation.
         * @param overrideReason    the operator's reason for the override; may be null/blank.
         * @param hasManualGesture  whether the line carries a manual cash-desk gesture.
         * @return the decision: the populated record line and the (positive) correction, zero when none.
         */
        static LineDecision decideLine(String lineId, String ean, BigDecimal floor, BigDecimal nominal,
                                       BigDecimal finalTtc, List<BigDecimal> warningThresholds,
                                       boolean exempt, boolean overridden, String overrideReason,
                                       boolean hasManualGesture) {
            BasketEvaluation.SrpLine line = new BasketEvaluation.SrpLine();
            line.lineId = lineId;
            line.ean = ean;
            line.floorPrice = floor;
            line.finalPrice = finalTtc;
            BigDecimal margin = scale2(finalTtc.subtract(floor));
            if (overridden) {
                // §3.C: the floor is bypassed for this line — traced, never corrected, even below floor.
                line.marginAboveFloor = margin;
                line.reasonCode = REASON_OVERRIDE;
                if (overrideReason != null && !overrideReason.isBlank()) {
                    line.overrideReason = overrideReason;
                }
                return new LineDecision(line, BigDecimal.ZERO);
            }
            if (exempt) {
                // Sales case (compliance.srpExempt): the line is traced but never corrected.
                line.marginAboveFloor = margin;
                return new LineDecision(line, BigDecimal.ZERO);
            }
            BigDecimal fraction = descentFraction(floor, nominal, finalTtc, warningThresholds);
            if (fraction != null) {
                line.descentFraction = fraction;
            }
            if (finalTtc.compareTo(floor) < 0) {
                // §3.A: below the floor — clamp up to it, tracing why the gesture was not applied in full.
                BigDecimal correction = floor.subtract(finalTtc);
                line.correction = correction;
                line.marginAboveFloor = scale2(BigDecimal.ZERO);
                line.reasonCode = REASON_FLOOR_CLAMPED;
                if (hasManualGesture && nominal != null) {
                    line.requestedDiscount = scale2(nominal.subtract(finalTtc));
                    line.grantedDiscount = scale2(nominal.subtract(floor));
                }
                return new LineDecision(line, correction);
            }
            // At or above the floor: a warning palier may still fire (§3.B), without any correction.
            line.marginAboveFloor = margin;
            if (fraction != null) {
                BigDecimal crossed = highestCrossed(warningThresholds, fraction);
                if (crossed != null) {
                    line.reasonCode = REASON_MARGIN_WARNING;
                    line.warningThreshold = crossed;
                }
            }
            return new LineDecision(line, BigDecimal.ZERO);
        }

        /**
         * Computes the descent toward the floor as a fraction of the sale-price-to-floor room
         * (spec-srp-guard §3.B): {@code (nominal − final) / (nominal − floor)}, scale 4.
         * <p>
         * Returns {@code null} when there is nothing to measure: no warning palier configured, no
         * nominal, or a room that is not strictly positive (a sale price already at or below the floor
         * has no scale on which a palier could be placed).
         *
         * @param floor             the floor, tax included.
         * @param nominal           the sale price times quantity, tax included; may be null.
         * @param finalTtc          the net after every advantage.
         * @param warningThresholds the configured paliers.
         * @return the descent fraction at scale 4, or {@code null}.
         */
        static BigDecimal descentFraction(BigDecimal floor, BigDecimal nominal, BigDecimal finalTtc,
                                          List<BigDecimal> warningThresholds) {
            if (warningThresholds == null || warningThresholds.isEmpty() || nominal == null) {
                return null;
            }
            BigDecimal room = nominal.subtract(floor);
            if (room.signum() <= 0) {
                return null;
            }
            return nominal.subtract(finalTtc).divide(room, 4, RoundingMode.HALF_UP);
        }

        /**
         * Returns the highest configured palier the descent fraction reached, or {@code null} when it
         * reached none (spec-srp-guard §3.B).
         *
         * @param warningThresholds the configured paliers, ascending.
         * @param fraction          the descent fraction.
         * @return the highest reached palier, or {@code null}.
         */
        static BigDecimal highestCrossed(List<BigDecimal> warningThresholds, BigDecimal fraction) {
            BigDecimal crossed = null;
            for (BigDecimal threshold : warningThresholds) {
                if (fraction.compareTo(threshold) >= 0) {
                    crossed = threshold;
                }
            }
            return crossed;
        }
    }

    /**
     * The outcome of {@link SrpGuardApplier#decideLine}: the populated record line and the positive
     * correction to aggregate ({@link BigDecimal#ZERO} when the line was not clamped).
     */
    static final class LineDecision {

        /**
         * The populated record line.
         */
        final BasketEvaluation.SrpLine line;

        /**
         * The positive correction, tax included; {@link BigDecimal#ZERO} when the line was not clamped.
         */
        final BigDecimal correctionTtc;

        /**
         * Builds a decision.
         *
         * @param line          the populated record line.
         * @param correctionTtc the positive correction, or zero.
         */
        LineDecision(BasketEvaluation.SrpLine line, BigDecimal correctionTtc) {
            this.line = line;
            this.correctionTtc = correctionTtc;
        }
    }

    /**
     * One corrected line's contribution to the SRP adjustment (spec-srp-guard §3.4).
     * <p>
     * Fields are public for JSON serialization.
     */
    public static class SrpCorrectionDetail {

        /**
         * The corrected line id.
         */
        public String lineId;

        /**
         * The EAN of the corrected line.
         */
        public String ean;

        /**
         * The correction magnitude (positive), tax included.
         */
        public BigDecimal correction;

        /**
         * Builds a correction detail.
         *
         * @param lineId     the corrected line id.
         * @param ean        the EAN of the corrected line.
         * @param correction the correction magnitude, positive, tax included.
         */
        public SrpCorrectionDetail(String lineId, String ean, BigDecimal correction) {
            this.lineId = lineId;
            this.ean = ean;
            this.correction = correction;
        }
    }

    /**
     * The single negative correction produced by the SRP guard when at least one line was below its
     * floor (spec-srp-guard §3.4): a {@link DiscountApplication} of type {@code "SRP Adjustment:
     * <code>"}, stored negative so the engine's subtraction lifts the lines back to their floors.
     */
    public static class SrpAdjustmentApplication implements DiscountApplication {

        /**
         * The application moment restituted in the response, set by the arbitration; AT_TOTAL here.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * The configuration code.
         */
        private final String code;

        /**
         * The correction amount, tax excluded and included, stored negative (the lift).
         */
        private final AmountEvaluation adjustmentAmount;

        /**
         * The per-line detail of the corrections that make up this adjustment.
         */
        private final List<SrpCorrectionDetail> corrections;

        /**
         * Builds the adjustment.
         *
         * @param code             the configuration code.
         * @param adjustmentAmount the correction amount, stored negative.
         * @param corrections      the per-line correction detail.
         */
        public SrpAdjustmentApplication(String code, AmountEvaluation adjustmentAmount,
                                        List<SrpCorrectionDetail> corrections) {
            this.code = code;
            this.adjustmentAmount = adjustmentAmount;
            this.corrections = corrections;
        }

        /**
         * Returns the display type of this advantage.
         *
         * @return {@code "SRP Adjustment: <code>"}.
         */
        public String getType() {
            return "SRP Adjustment: " + code;
        }

        /**
         * Returns the display offer of this advantage; the guard has no targeted offer, so its own
         * type stands in (and no {@code null} dereference occurs).
         *
         * @return the same string as {@link #getType()}.
         */
        @Override
        public String getOffer() {
            return getType();
        }

        /**
         * Returns the per-line correction detail (spec-srp-guard §3.4).
         *
         * @return the corrections, each carrying a line id, an EAN and a positive magnitude.
         */
        public List<SrpCorrectionDetail> getCorrections() {
            return corrections;
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
         * Returns no targeted offer application: the adjustment aggregates several lines.
         *
         * @return always {@code null}.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return null;
        }

        /**
         * Returns the adjustment amount, stored negative so the engine's subtraction lifts the total
         * back up.
         *
         * @return the negative correction amount.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return adjustmentAmount;
        }
    }
}
