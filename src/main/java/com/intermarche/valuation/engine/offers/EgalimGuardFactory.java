package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.valuation.domain.EgalimCeiling;
import com.intermarche.valuation.domain.EgalimRegime;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
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
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Factory for the {@code EGALIM_GUARD} advantage: an end-of-valuation conformity check that
 * caps the cumulated promotional generosity of a line to its legal EGAlim ceiling and records,
 * line by line, the nominal price, the final price, the generosity rate and the margin still
 * available before the ceiling (spec §1).
 * <p>
 * This is a canonical {@link AdvantageApplierFactory} — a real configuration persisted in the
 * {@code Offer} table, subject to the in-force finders and the {@code active}/{@code validFrom}/
 * {@code validTo} window like any other. Its specificity is that it is a <em>counter</em>-advantage:
 * when a line's counted generosity exceeds its ceiling, it emits a <em>negative</em> discount
 * that lifts the line back exactly to the ceiling. In every case it also produces the per-line
 * EGAlim record ({@link BasketEvaluation#getEgalim()}), so a removed illegal offer is a
 * back-office act informed by the record, never a silent censorship by the engine (spec §1, §7).
 * <p>
 * Its arbitration position is structural, not configurable (spec §3): whatever the persisted
 * specification carries, {@link EgalimGuardApplier#getConfiguration()} returns a forced synthetic
 * configuration — {@code applicationMoment = AT_TOTAL}, {@code priority = Integer.MAX_VALUE}
 * (last in the ascending order, guaranteed after every other advantage, card promotions at 500
 * included), cumulable, consuming no carrier, with no application limit. On an open basket the
 * {@code AT_TOTAL} wave does not run, so the guard neither corrects nor records (spec §3, §8.9).
 * <p>
 * Compute semantics (spec §4), per initial basket line: the nominal is the reference price times
 * the quantity (the base the engine posed, discarded lot included for a repricing offer); the
 * final is the net retained after every advantage ({@link NetAmounts}); the counted generosity is
 * {@code nominal − final} minus the excluded part — the anti-waste share (perishable exemption)
 * and the manual-gesture repricing (a commercial gesture, not a promotional advantage). The
 * ceiling is {@code cap × nominal} rounded DOWN to the cent, so the residual generosity after a
 * correction is always at or below the ceiling.
 */
@ApplicationScoped
public class EgalimGuardFactory implements AdvantageApplierFactory, EngineTrait {

    /**
     * Logger for the "several in-force guards" trace (spec §3).
     */
    private static final Logger LOGGER = Logger.getLogger(EgalimGuardFactory.class);

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "EGALIM_GUARD";

    /**
     * The forced synthetic specification of the guard's configuration (spec §3): AT_TOTAL, last
     * in the arbitration order, cumulable, consuming nothing. Read by {@code parseArbitrationConfig}
     * without schema re-validation; {@code 2147483647} is {@link Integer#MAX_VALUE}.
     */
    private static final String FORCED_SPECIFICATION =
            "{\"applicationMoment\":\"AT_TOTAL\","
                    + "\"arbitration\":{\"priority\":2147483647,\"cumulable\":true,\"consumesContributors\":false}}";

    /**
     * JSON Schema for the EGAlim guard specification (spec §3): optional per-regime cap overrides,
     * defaulting to the legal ceilings. No {@code trigger} nor {@code arbitration} block is
     * declared — the guard's position is structural, not negotiable.
     */
    /*
     * The ceiling of a regime has THREE possible sources and exactly one wins, in this order:
     * the administered referential ({@link EgalimCeiling}, fed by the EGALIM_REGIMES feed), the
     * caps override below, then the legal value compiled into the enum. See resolveCaps.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "EGAlim Guard Offer Specification",
      "description": "Caps the cumulated promotional generosity of a line to its legal EGAlim ceiling.",
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "caps": {
          "type": "object",
          "additionalProperties": false,
          "description": "Optional per-regime ceiling overrides, used only for a regime the administered referential does not state; otherwise the legal values (0.34, 0.40).",
          "x-label": "Ceiling overrides",
          "properties": {
            "FOOD_34": {
              "type": "number",
              "exclusiveMinimum": 0,
              "maximum": 1,
              "description": "Override of the food ceiling (default 0.34).",
              "x-label": "Food ceiling"
            },
            "DPH_40": {
              "type": "number",
              "exclusiveMinimum": 0,
              "maximum": 1,
              "description": "Override of the DPH ceiling (default 0.40).",
              "x-label": "DPH ceiling"
            }
          }
        }
      }
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the {@code "EGALIM_GUARD"} discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the EGAlim guard specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds the guard applier from the single in-force {@code EGALIM_GUARD} configuration of the
     * store and its groups (spec §3).
     * <p>
     * At most one configuration is expected; when several are in force, the first in the
     * arbitration order (by code) applies and the others are ignored with a trace. A basket
     * without a configuration builds no applier, so the valuation is unchanged (regression-free).
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return the guard applier, or an empty collection when no configuration is in force.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if the configuration's specification violates the schema.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        getBasket(basketEvaluation, "Cannot create EGAlim Guard appliers without a valid basket.");
        Store store = basketEvaluation.getStore();
        List<Offer> offers = new ArrayList<>(getOffers(basketEvaluation, OFFER_TYPE));
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            if (i == 0) {
                processOffer(offer, appliers, store);
            } else {
                String message = "EGALIM_GUARD configuration '" + offer.code
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
     * @param store    the store, for the nominal reference-price lookups.
     * @throws IllegalArgumentException if the specification violates the schema.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) ->
                appliers.add(new EgalimGuardApplier(offer.code, resolveCaps(spec.get("caps")), store)));
    }

    /**
     * Resolves the ceiling of every regime, ONE source winning per regime, in this order:
     * the administered referential, then the guard's own {@code caps} override, then the
     * legal value compiled into the enum (spec §3).
     * <p>
     * The referential comes FIRST because it is the only one of the three a shop can correct
     * the morning the law changes: {@link EgalimCeiling} is fed by the {@code EGALIM_REGIMES}
     * feed the store node delivers, and administered on that node's EGAlim screen. As long as
     * the table is empty — an engine fed by an older node, or a fresh database — nothing moves
     * and the previous two-source behaviour stands, which is what makes this change
     * regression-free.
     * <p>
     * A regime the referential states WITHOUT a ceiling is uncapped, and that is not the same
     * thing as a regime the referential ignores: the first is an answer, the second is a
     * silence, and only the silence falls through to the override and the enum. That is how
     * the exempt category is stated, and how a shop may legitimately un-cap a regime the law
     * has released. A regime carrying no ceiling from any of the three sources is simply
     * absent from the returned map, which is how the applier reads "never correct this line".
     *
     * @param caps the {@code caps} node of the specification, or null when the guard declares none.
     * @return the ceiling fraction of every capped regime; a regime absent from the map is uncapped.
     */
    static Map<EgalimRegime, BigDecimal> resolveCaps(JsonNode caps) {
        Map<EgalimRegime, BigDecimal> resolved = new EnumMap<>(EgalimRegime.class);
        for (EgalimRegime regime : EgalimRegime.values()) {
            EgalimCeiling administered = EgalimCeiling.findByCode(regime.name());
            BigDecimal cap;
            if (administered != null) {
                cap = administered.capRate;
            } else if (caps != null && caps.hasNonNull(regime.name())) {
                cap = caps.get(regime.name()).decimalValue();
            } else {
                cap = regime.defaultCap();
            }
            if (cap != null) {
                resolved.put(regime, cap);
            }
        }
        return resolved;
    }

    /**
     * The EGAlim guard applier: computes the per-line record and the negative correction.
     */
    public static class EgalimGuardApplier implements AdvantageApplier {

        /**
         * The configuration code, used in the correction label.
         */
        private final String code;

        /**
         * The resolved ceiling of every CAPPED regime; a regime absent from this map carries no
         * ceiling and its lines are never corrected.
         */
        private final Map<EgalimRegime, BigDecimal> caps;

        /**
         * The store, for the nominal reference-price lookups.
         */
        private final Store store;

        /**
         * The forced synthetic configuration returned by {@link #getConfiguration()}, built once.
         */
        private final Offer forcedConfiguration;

        /**
         * Creates the applier and its forced synthetic configuration.
         *
         * @param code  the configuration code.
         * @param caps  the ceiling fraction of every capped regime, as resolved by
         *              {@link EgalimGuardFactory#resolveCaps(JsonNode)}; a regime absent from the
         *              map is uncapped.
         * @param store the store, for reference-price lookups; may be null.
         */
        public EgalimGuardApplier(String code, Map<EgalimRegime, BigDecimal> caps, Store store) {
            this.code = code;
            this.caps = caps;
            this.store = store;
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
         * Returns the sandbox efficiency score, zero by convention: the guard competes with no
         * one, being alone in its priority wave (spec §5).
         *
         * @return {@code 0.0}.
         */
        @Override
        public double getEfficiencyScore() {
            return 0.0;
        }

        /**
         * Returns the forced synthetic configuration (spec §3): AT_TOTAL, priority
         * {@link Integer#MAX_VALUE}, cumulable, consuming nothing.
         *
         * @return the forced configuration.
         */
        @Override
        public Offer getConfiguration() {
            return forcedConfiguration;
        }

        /**
         * Runs the guard: builds the per-line EGAlim record on the evaluation and returns the
         * single negative correction advantage when a ceiling was exceeded (spec §4, §5, §6).
         * <p>
         * Called once, last in the {@code AT_TOTAL} wave, so it measures every other advantage
         * already applied and its own amount never enters the generosity it measures. It always
         * sets the record (even with no correction); it returns an advantage only when the summed
         * corrections are positive.
         *
         * @param evaluation the evaluation context.
         * @return the single negative correction advantage, or an empty list when nothing exceeds
         *         its ceiling.
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            List<BasketEvaluation.EgalimLine> record = new ArrayList<>();
            Basket basket = evaluation.getBasket();
            if (basket == null || basket.items == null) {
                evaluation.setEgalimRecord(record);
                return List.of();
            }
            Map<String, BigDecimal> netByLine = netTtcByLine(evaluation);
            Map<String, BigDecimal> antiWasteByLine = antiWasteTtcByLine(evaluation, basket);
            List<CorrectionDetail> corrections = new ArrayList<>();
            BigDecimal totalCorrHt = BigDecimal.ZERO;
            BigDecimal totalCorrTtc = BigDecimal.ZERO;
            for (Basket.Item item : basket.items) {
                AmountEvaluation nominal = nominalOf(item);
                if (nominal == null || nominal.amountIncludingTax == null) {
                    continue;
                }
                BigDecimal nominalTtc = nominal.amountIncludingTax;
                BigDecimal rate = nominal.vatRate == null ? BigDecimal.ZERO : nominal.vatRate;
                Product product = item.produceEan == null ? null : Product.findByEan(item.produceEan);
                EgalimRegime regime = product == null || product.egalimRegime == null
                        ? EgalimRegime.EXEMPT : product.egalimRegime;
                BigDecimal netTtc = item.lineId != null && netByLine.containsKey(item.lineId)
                        ? netByLine.get(item.lineId) : nominalTtc;
                if (netTtc.signum() < 0) {
                    netTtc = BigDecimal.ZERO;
                }
                BigDecimal totalGenerosity = nominalTtc.subtract(netTtc);
                if (totalGenerosity.signum() < 0) {
                    totalGenerosity = BigDecimal.ZERO;
                }
                BigDecimal excluded;
                BigDecimal counted;
                if (item.hasManualGesture()) {
                    // A manual gesture is a commercial gesture, not a promotional advantage: its
                    // whole repricing delta is excluded and the line is never corrected (spec §4.3).
                    excluded = totalGenerosity;
                    counted = BigDecimal.ZERO;
                } else {
                    BigDecimal antiWaste = item.lineId != null
                            ? antiWasteByLine.getOrDefault(item.lineId, BigDecimal.ZERO) : BigDecimal.ZERO;
                    if (antiWaste.compareTo(totalGenerosity) > 0) {
                        antiWaste = totalGenerosity;
                    }
                    excluded = antiWaste;
                    counted = totalGenerosity.subtract(antiWaste);
                }
                if (counted.signum() < 0) {
                    counted = BigDecimal.ZERO;
                }
                BasketEvaluation.EgalimLine line = new BasketEvaluation.EgalimLine();
                line.ean = item.produceEan;
                line.label = product == null ? null : product.name;
                line.regime = regime.name();
                line.nominalPrice = scale2(nominalTtc);
                line.finalPrice = scale2(netTtc);
                line.generosityRate = nominalTtc.signum() > 0
                        ? counted.divide(nominalTtc, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                line.countedGenerosity = scale2(counted);
                line.excludedGenerosity = scale2(excluded);
                // The ceiling comes from the resolved map and not from the enum's own default: an
                // administered referential may cap a regime the enum releases, and release one the
                // enum caps. An absent entry is an uncapped regime, hence no correction and no
                // remaining-before-cap figure — the line is recorded and left alone.
                BigDecimal cap = caps.get(regime);
                if (cap != null) {
                    // The maximum authorised generosity is rounded DOWN to the cent, so the residual
                    // generosity after a correction is always at or below the ceiling (spec §4.4).
                    BigDecimal maxAllowed = cap.multiply(nominalTtc).setScale(2, RoundingMode.FLOOR);
                    if (counted.compareTo(maxAllowed) > 0) {
                        BigDecimal correctionTtc = counted.subtract(maxAllowed).setScale(2, RoundingMode.HALF_UP);
                        line.correction = correctionTtc;
                        line.remainingBeforeCap = scale2(BigDecimal.ZERO);
                        BigDecimal correctionHt = correctionTtc.divide(
                                BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                        totalCorrTtc = totalCorrTtc.add(correctionTtc);
                        totalCorrHt = totalCorrHt.add(correctionHt);
                        corrections.add(new CorrectionDetail(item.produceEan, correctionTtc));
                    } else {
                        line.remainingBeforeCap = maxAllowed.subtract(counted).setScale(2, RoundingMode.HALF_UP);
                    }
                }
                record.add(line);
            }
            evaluation.setEgalimRecord(record);
            if (totalCorrTtc.signum() > 0) {
                BigDecimal blendedRate = totalCorrHt.signum() > 0
                        ? totalCorrTtc.divide(totalCorrHt, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                        : BigDecimal.ZERO;
                // Stored negative: the engine subtracts a discount amount, so a negative value lifts
                // the line back up — the EGAlim reprise (spec §5).
                AmountEvaluation amount = new AmountEvaluation(
                        totalCorrHt.negate(), totalCorrTtc.negate(), blendedRate);
                return List.of(new EgalimAdjustmentApplication(code, amount, corrections));
            }
            return List.of();
        }

        /**
         * Computes the nominal amount of a line: the reference price times the quantity (spec §4.1).
         * <p>
         * A line carrying an EAN is priced at {@link PriceUsage#BASE_FOR_DISCOUNT} (falling back to
         * the default price when no reference row exists); a line without an EAN uses its entered
         * price. An unpriceable line yields {@code null} and is left out of the record.
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
         * Builds the net tax-included amount paid for each initial line, keyed by line id.
         * <p>
         * Each available offer application is grouped by EAN; the net of every group is computed
         * once with {@link NetAmounts#netProductTtc} (which nets each product tranche of the
         * discounts already retained against it) and distributed to the lines by their gross
         * weight, so a product-scoped discount is never subtracted twice when two lines share an
         * EAN in one application.
         *
         * @param evaluation the evaluation context.
         * @return the net tax-included amount per line id.
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
         * Builds the anti-waste discount attributed to each initial line, keyed by line id (spec
         * §4.3).
         * <p>
         * Each anti-waste discount is spread over the valued items of its targeted application
         * that carry its EAN and a best-before date — the very lines it was computed on — by their
         * gross weight, reproducing the per-line anti-waste amount.
         *
         * @param evaluation the evaluation context.
         * @param basket     the basket, to find the lines carrying a best-before date.
         * @return the anti-waste tax-included amount per line id.
         */
        private Map<String, BigDecimal> antiWasteTtcByLine(BasketEvaluation evaluation, Basket basket) {
            Map<String, BigDecimal> antiWasteByLine = new HashMap<>();
            Set<String> perishableLines = new HashSet<>();
            for (Basket.Item item : basket.items) {
                if (item.lineId != null && item.bestBeforeDate != null) {
                    perishableLines.add(item.lineId);
                }
            }
            for (AdvantageApplication advantage : evaluation.getAdvantages()) {
                if (!(advantage instanceof AntiWasteDiscountFactory.AntiWasteDiscountApplication antiWaste)) {
                    continue;
                }
                if (antiWaste.getDiscountAmount() == null
                        || antiWaste.getDiscountAmount().amountIncludingTax == null) {
                    continue;
                }
                OfferApplication target = antiWaste.getOfferApplication();
                if (target == null) {
                    continue;
                }
                String ean = antiWaste.getEan();
                List<BasketEvaluation.Item> eligible = new ArrayList<>();
                BigDecimal sumGross = BigDecimal.ZERO;
                for (BasketEvaluation.Item item : target.getValuedItems()) {
                    if (item.amount == null || item.amount.amountIncludingTax == null
                            || !Objects.equals(ean, item.produceEan)) {
                        continue;
                    }
                    if (item.lineId != null && !perishableLines.contains(item.lineId)) {
                        continue;
                    }
                    eligible.add(item);
                    sumGross = sumGross.add(item.amount.amountIncludingTax);
                }
                if (sumGross.signum() <= 0) {
                    continue;
                }
                BigDecimal amount = antiWaste.getDiscountAmount().amountIncludingTax;
                for (BasketEvaluation.Item item : eligible) {
                    if (item.lineId == null) {
                        continue;
                    }
                    BigDecimal share = amount.multiply(item.amount.amountIncludingTax)
                            .divide(sumGross, 2, RoundingMode.HALF_UP);
                    antiWasteByLine.merge(item.lineId, share, BigDecimal::add);
                }
            }
            return antiWasteByLine;
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
    }

    /**
     * One corrected line's contribution to the EGAlim adjustment (spec §5).
     * <p>
     * Fields are public for JSON serialization.
     */
    public static class CorrectionDetail {

        /**
         * The EAN of the corrected line.
         */
        public String ean;

        /**
         * The correction applied to the line, in euros, tax included (a positive magnitude).
         */
        public BigDecimal correction;

        /**
         * Builds a correction detail.
         *
         * @param ean        the corrected EAN.
         * @param correction the correction magnitude, tax included.
         */
        public CorrectionDetail(String ean, BigDecimal correction) {
            this.ean = ean;
            this.correction = correction;
        }
    }

    /**
     * The single EGAlim adjustment advantage: a negative discount lifting the over-generous lines
     * back to their ceiling (spec §5).
     * <p>
     * It targets no offer application (its amount aggregates several lines), so it is deducted by
     * the VAT breakdown at its own blended rate and reconciled into the total like any discount,
     * with no special case.
     */
    public static class EgalimAdjustmentApplication implements DiscountApplication {

        /**
         * The application moment restituted in the response, set by the arbitration.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * The configuration code.
         */
        private final String code;

        /**
         * The correction amount, tax excluded and included, stored negative (the reprise).
         */
        private final AmountEvaluation adjustmentAmount;

        /**
         * The per-line detail of the corrections that make up this adjustment.
         */
        private final List<CorrectionDetail> corrections;

        /**
         * Builds the adjustment.
         *
         * @param code             the configuration code.
         * @param adjustmentAmount the correction amount, stored negative.
         * @param corrections      the per-line correction detail.
         */
        public EgalimAdjustmentApplication(String code, AmountEvaluation adjustmentAmount,
                                           List<CorrectionDetail> corrections) {
            this.code = code;
            this.adjustmentAmount = adjustmentAmount;
            this.corrections = corrections;
        }

        /**
         * Returns the display type of this advantage.
         *
         * @return {@code "EGAlim Adjustment: <code>"}.
         */
        public String getType() {
            return "EGAlim Adjustment: " + code;
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
         * Returns the per-line correction detail (spec §5).
         *
         * @return the corrections, each carrying an EAN and a positive correction magnitude.
         */
        public List<CorrectionDetail> getCorrections() {
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
         * Returns the adjustment amount, stored negative so the engine's subtraction lifts the
         * total back up.
         *
         * @return the negative correction amount.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return adjustmentAmount;
        }
    }
}
