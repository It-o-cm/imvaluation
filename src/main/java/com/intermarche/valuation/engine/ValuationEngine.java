package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.BudgetGroup;
import com.intermarche.valuation.domain.ConsumptionCounter;
import com.intermarche.valuation.domain.Offer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Engine responsible for evaluating a {@link Basket} and applying offers.
 * <p>
 * This engine orchestrates the valuation process by delegating
 * applier creation and application generation to specific isolated methods.
 */
@ApplicationScoped
public class ValuationEngine {

    /**
     * Logger for fail-closed skips (A2, report H1): a configuration that cannot be honoured is
     * logged as an error and the evaluation continues, rather than failing the whole request.
     */
    private static final Logger LOGGER = Logger.getLogger(ValuationEngine.class);

    /**
     * List of all factories registered in the application.
     * <p>
     * CDI injects all beans implementing {@link AdvantageApplierFactory}.
     */
    @Inject
    Instance<AdvantageApplierFactory> discountFactories;

    /**
     * List of all factories registered in the application.
     * <p>
     * CDI injects all beans implementing {@link OfferApplierFactory}.
     */
    @Inject
    Instance<OfferApplierFactory> offerFactories;

    /**
     * Evaluates the provided basket.
     * <p>
     * This method instantiates a new {@link BasketEvaluation}, populates it
     * with items from the basket, and orchestrates the creation of appliers
     * and applications. Finally, it calculates the total price and updates the evaluation.
     *
     * @param basket The basket to evaluate.
     * @return A {@link BasketEvaluation} containing applied offers, discounts, and total price.
     */
    public BasketEvaluation evaluate(Basket basket) {
        return evaluate(basket, null);
    }

    /**
     * Evaluates a basket against an optional shared display context
     * (spec-valorisation-affichage-ecommerce §2bis).
     * <p>
     * When {@code displayContext} is non-null, this is one mini-basket of a
     * {@code POST /display-valuation} call: the offers come from the context resolved once for the
     * whole call (through {@link EngineTrait#getOffers}, which consults it), and only the offers
     * that may shape a one-unit displayed price are applied. When it is {@code null} the evaluation
     * is a normal basket valuation, byte-for-byte unchanged.
     *
     * @param basket         The basket to evaluate.
     * @param displayContext The shared display context, or {@code null} for a normal valuation.
     * @return A {@link BasketEvaluation} containing applied offers, discounts, and total price.
     */
    public BasketEvaluation evaluate(Basket basket, DisplayContext displayContext) {
        // 1. Initialize evaluation context
        BasketEvaluation evaluation = getBasketEvaluation(basket);
        evaluation.setDisplayContext(displayContext);
        // 2. Create Discount Appliers
        List<AdvantageApplier> discountAppliers = createDiscountAppliers(evaluation);
        // 3. Create Offer Appliers
        List<OfferApplier> offerAppliers = createOfferAppliers(evaluation, discountAppliers);
        // 4. Create Offer Applications
        createOfferApplications(offerAppliers, evaluation);
        // 5. Create Discount Applications
        createDiscountApplications(discountAppliers, evaluation);
        // 6. Calculate and Set Total Price
        AmountEvaluation finalPrice = calculateAmountEvaluation(evaluation);
        evaluation.setTotalPrice(finalPrice);
        return evaluation;
    }

    /**
     * Initializes a new {@link BasketEvaluation} for the provided basket.
     *
     * @param basket The basket to evaluate.
     * @return A new {@link BasketEvaluation} instance.
     */
    private BasketEvaluation getBasketEvaluation(Basket basket) {
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        return evaluation;
    }

    /**
     * Calculates the final total price of the basket after applying offers and discounts.
     *
     * @param evaluation The evaluation context containing applied offers and discounts.
     * @return The final total price as an {@link AmountEvaluation}.
     */
    private static AmountEvaluation calculateAmountEvaluation(BasketEvaluation evaluation) {
        BigDecimal totalExclVat = BigDecimal.ZERO;
        BigDecimal totalInclVat = BigDecimal.ZERO;
        // Sum up all Offer Prices (Products, Delivery, Bundles, etc.)
        if (evaluation.getOffers() != null) {
            for (OfferApplication app : evaluation.getOffers()) {
                AmountEvaluation price = app.getAmount();
                if (price != null) {
                    totalExclVat = totalExclVat.add(price.amountExcludingTax);
                    totalInclVat = totalInclVat.add(price.amountIncludingTax);
                }
            }
        }
        // Subtract all Discount Prices
        if (evaluation.getAdvantages() != null) {
            for (AdvantageApplication app : evaluation.getAdvantages()) {
                if (app instanceof DiscountApplication) {
                    DiscountApplication discount = (DiscountApplication) app;
                    AmountEvaluation price = discount.getDiscountAmount();
                    if (price != null) {
                        // getDiscountAmount() returns a positive amount to deduct; every
                        // DiscountApplication follows that contract, so the sign lives here
                        // in the subtraction, not in the stored value.
                        totalExclVat = totalExclVat.subtract(price.amountExcludingTax);
                        totalInclVat = totalInclVat.subtract(price.amountIncludingTax);
                    }
                }
            }
        }
        // Create the final PriceEvaluation and set it in the context
        // VAT Rate is set to 0 as it's a mix of different rates
        AmountEvaluation finalPrice = new AmountEvaluation(
                totalExclVat.setScale(2, RoundingMode.HALF_UP),
                totalInclVat.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO
        );
        return finalPrice;
    }

    // --------------------------------------------------
    // Factory & Applier Creation Methods
    // --------------------------------------------------

    /**
     * Creates a list of {@link OfferApplier} instances relevant to the provided basket.
     * <p>
     * This method iterates over all available {@link OfferApplierFactory} beans
     * and delegates the creation of appliers to them.
     * <p>
     * Errors during factory processing are caught and logged to ensure that
     * one faulty factory does not stop the entire valuation process.
     *
     * @param basketEvaluation The basket evaluation used to build appliers.
     * @param discountAppliers The list of discount appliers to consider.
     * @return A list of available offer appliers.
     */
    private List<OfferApplier> createOfferAppliers(
            BasketEvaluation basketEvaluation, List<AdvantageApplier> discountAppliers
    ) {
        List<OfferApplier> appliers = new ArrayList<>();
        if (offerFactories != null) {
            for (OfferApplierFactory factory : offerFactories) {
                try {
                    Collection<OfferApplier> builtAppliers = factory.buildAppliers(basketEvaluation);
                    if (builtAppliers != null) {
                        for (OfferApplier applier : builtAppliers) {
                            for (AdvantageApplier discountApplier : discountAppliers) {
                                if (discountApplier.isApplicable(applier)) {
                                    applier.registerDiscountApplier(discountApplier);
                                }
                            }
                            double efficiencyScore = applier.computeEfficiencyScore(basketEvaluation.getBasket());
                            applier.setEfficiencyScore(efficiencyScore);
                        }
                        appliers.addAll(builtAppliers);
                    }
                } catch (ConfigurationException e) {
                    // Fail-closed per configuration (A2, report H1a): a stored specification this
                    // factory cannot build is skipped and recorded, the evaluation continues with
                    // the others — never a 500 for every basket of the store. Request-level errors
                    // (a malformed basket line, an unknown product) are not ConfigurationException
                    // and propagate, so the caller is still told its request was rejected.
                    skip(basketEvaluation, "Offer factory " + factory.getClass().getSimpleName(), e);
                }
            }
        }
        return appliers;
    }

    /**
     * Sorts the provided appliers and creates {@link OfferApplication} instances.
     * <p>
     * This method uses an isolated {@link OfferApplierEvaluator} to order
     * appliers by efficiency score, then iterates through them to apply their
     * logic to the provided {@link BasketEvaluation} context.
     * <p>
     * Results (applications) are added directly to the evaluation object.
     *
     * @param appliers   The list of appliers to process.
     * @param evaluation The evaluation context to modify (consume items, add offers).
     */
     void createOfferApplications(List<OfferApplier> appliers, BasketEvaluation evaluation) {
        // 1. Sort Appliers using the dedicated evaluator object
        OfferApplierEvaluator evaluator = new OfferApplierEvaluator();
        evaluator.sort(appliers, evaluation);
        // 2. Apply Appliers in sorted order
        for (OfferApplier applier : appliers) {
            // Optional offer (N+M, MIXED_BUNDLE) carrying an unsatisfied trigger: dropped, so
            // its lines fall back on the Basic valuation (spec §4.2.A). Offers without a
            // configuration (Basic, generic line, manual gesture) or without a trigger
            // (Trigger.ALWAYS) are never dropped — current behaviour is untouched.
            //
            // Documented limitation (spec §3, accepted): an offer-side MINIMUM_AMOUNT /
            // MINIMUM_QUANTITY trigger measures the offers valued so far, which for an offer
            // considered before its own lines are valued may under-measure. COUPON_CODE (and
            // ticket conditions read after other offers are valued) are exact; no P1 line
            // depends on offer-side amount/quantity triggers. Future fix path: measure the
            // offer-side trigger against a provisional Basic valuation of the basket.
            Offer configuration = applier.getConfiguration();
            if (configuration != null) {
                ArbitrationConfig config = parseArbitrationConfig(configuration, evaluation);
                // Fail-closed (A2, report H1b): a configuration whose trigger/arbitration block
                // could not be parsed is skipped, not silently treated as always-on.
                if (!config.valid()) {
                    continue;
                }
                if (!evaluation.triggerResult(configuration, config.trigger()).satisfied()) {
                    continue;
                }
                // Budget control (spec §5): a configuration whose offer budget or budget group is
                // exhausted is not served — same mechanic as a cumul refusal. The current ticket
                // increments nothing; only cashing counts.
                if (budgetExhausted(configuration, evaluation)) {
                    continue;
                }
            }
            try {
                // Apply offer logic to the evaluation context
                Collection<OfferApplication> applications = applier.apply(evaluation);
                if (applications != null) {
                    evaluation.getOffers().addAll(applications);
                }
            } catch (ConfigurationException e) {
                // Fail-closed (A2, report H1a): a stored-spec problem skips this offer and continues;
                // a genuine runtime error is not ConfigurationException and propagates.
                skip(evaluation, "Offer " + (configuration != null ? configuration.code : applier.getClass().getSimpleName()), e);
            }
        }
    }

    /**
     * Sorts the provided appliers and creates {@link AdvantageApplication} instances.
     * <p>
     * This method orders appliers wave by wave (priority, then current sandbox
     * score, then configuration code) and iterates through them to apply their
     * logic to the provided {@link BasketEvaluation} context.
     * <p>
     * Results (applications) are added directly to the evaluation object.
     *
     * @param appliers   The list of appliers to process.
     * @param evaluation The evaluation context to modify (consume items, add offers).
     */
     void createDiscountApplications(List<AdvantageApplier> appliers, BasketEvaluation evaluation) {
        // Parse each configuration's arbitration parameters once (spec §4.1). Appliers not
        // born from a configuration row parse to the defaults, so they behave exactly as
        // today — activation by data, never by code.
        Map<AdvantageApplier, ArbitrationConfig> configs = new IdentityHashMap<>();
        for (AdvantageApplier applier : appliers) {
            configs.put(applier, parseArbitrationConfig(applier.getConfiguration(), evaluation));
        }
        boolean closed = isBasketClosed(evaluation.getBasket());
        ArbitrationState state = new ArbitrationState();
        // Per-applier record of the applications actually retained, in application order, so the
        // A1 re-pricing step can recompute the discounts that land on a re-priced line.
        Map<AdvantageApplier, List<AdvantageApplication>> applied = new LinkedHashMap<>();
        // Two waves (spec §4.2): AT_TRIGGER first, always; AT_TOTAL second, only on a closed
        // basket. Wave membership takes precedence over every other ordering criterion.
        arbitrateWave("AT_TRIGGER", appliers, configs, evaluation, state, applied);
        if (closed) {
            arbitrateWave("AT_TOTAL", appliers, configs, evaluation, state, applied);
        }
        // A1 (report C1): the standard lines were valued at the DEFAULT price during the
        // arbitration; now switch the lines carrying an actually-retained discount to the
        // reference price and recompute those discounts on that base.
        repriceRetainedDiscountLines(evaluation, configs, applied);
    }

    /**
     * Re-prices the standard lines carrying a retained discount and recomputes those discounts on
     * the reference base (A1, report C1).
     * <p>
     * Standard lines are valued at the DEFAULT price throughout the arbitration, so a discarded
     * advantage never costs the customer the higher reference price. Once the arbitration has
     * settled, the lines that ended up carrying at least one retained {@link DiscountApplication}
     * are switched to their reference price; the discounts that target such a line are then
     * recomputed against the new base by re-running their (pure, non-consuming) applier and
     * swapping in the fresh applications. Discounts frozen at apply-time otherwise keep their
     * default-base amount, which would understate the reduction on the reference price.
     *
     * @param evaluation the evaluation whose retained discounts are being finalised.
     * @param configs    the parsed arbitration parameters, per applier.
     * @param applied    the applications retained per applier, in application order.
     */
    private void repriceRetainedDiscountLines(BasketEvaluation evaluation,
                                              Map<AdvantageApplier, ArbitrationConfig> configs,
                                              Map<AdvantageApplier, List<AdvantageApplication>> applied) {
        // 1. Switch to the reference price every standard line that carries a retained discount.
        Set<OfferApplication> repricedTargets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AdvantageApplication advantage : evaluation.getAdvantages()) {
            if (!(advantage instanceof DiscountApplication)) {
                continue;
            }
            OfferApplication target = advantage.getOfferApplication();
            if (target instanceof RepriceableApplication && !repricedTargets.contains(target)
                    && ((RepriceableApplication) target).repriceToReference()) {
                repricedTargets.add(target);
            }
        }
        if (repricedTargets.isEmpty()) {
            return;
        }
        // 2. Select, in application order, the pure discount appliers (every retained application
        // is a DiscountApplication) that actually target a re-priced line. They read the live
        // offer amounts and consume nothing, so re-running them on the re-priced evaluation
        // re-derives their amounts on the reference base.
        List<Map.Entry<AdvantageApplier, List<AdvantageApplication>>> toRecompute = new ArrayList<>();
        for (Map.Entry<AdvantageApplier, List<AdvantageApplication>> entry : applied.entrySet()) {
            if (isPureDiscount(entry.getValue()) && targetsRepricedLine(entry.getValue(), repricedTargets)) {
                toRecompute.add(entry);
            }
        }
        // 3. Withdraw their pass-1 (default-base) applications up front, so an applier whose base
        // nets out prior discounts does not see the stale amount it is about to replace.
        for (Map.Entry<AdvantageApplier, List<AdvantageApplication>> entry : toRecompute) {
            evaluation.getAdvantages().removeAll(entry.getValue());
        }
        // 4. Re-run them in application order, so an applier that nets out earlier discounts sees
        // their recomputed (reference-base) amounts, exactly as in the first pass.
        for (Map.Entry<AdvantageApplier, List<AdvantageApplication>> entry : toRecompute) {
            AdvantageApplier applier = entry.getKey();
            List<AdvantageApplication> previous = entry.getValue();
            Collection<AdvantageApplication> recomputed;
            try {
                recomputed = applier.apply(evaluation);
            } catch (Exception e) {
                // A failure here restores the pass-1 applications rather than dropping a retained
                // advantage.
                evaluation.getAdvantages().addAll(previous);
                continue;
            }
            List<AdvantageApplication> kept = (recomputed == null || recomputed.isEmpty())
                    ? List.of() : capApplications(recomputed, configs.get(applier));
            if (kept.isEmpty()) {
                evaluation.getAdvantages().addAll(previous);
                continue;
            }
            for (AdvantageApplication application : kept) {
                stampConfiguration(application, configs.get(applier));
            }
            evaluation.getAdvantages().addAll(kept);
        }
    }

    /**
     * Stamps onto a retained advantage the passthrough and compliance values of its
     * configuration (spec-divers-offre §2, §3), alongside the application moment: the accounting
     * code and print mode restituted verbatim in the output, and the EGAlim/SRP exemption markers
     * read later by the guards. Centralised here so no factory copies them one by one.
     *
     * @param application the retained advantage application.
     * @param config      the parsed configuration of the applier that produced it.
     */
    private static void stampConfiguration(AdvantageApplication application, ArbitrationConfig config) {
        application.setApplicationMoment(config.applicationMoment());
        application.setAccountingCode(config.accountingCode());
        application.setPrintMode(config.printMode());
        application.setEgalimExempt(config.egalimExempt());
        application.setSrpExempt(config.srpExempt());
        application.setFunding(config.funding());
    }

    /**
     * Tells whether every retained application of an applier is a {@link DiscountApplication} —
     * a pure reduction that reads the offers and consumes nothing, hence safe to re-run.
     *
     * @param applications the applier's retained applications.
     * @return {@code true} when they are all discount applications.
     */
    private static boolean isPureDiscount(List<AdvantageApplication> applications) {
        for (AdvantageApplication application : applications) {
            if (!(application instanceof DiscountApplication)) {
                return false;
            }
        }
        return !applications.isEmpty();
    }

    /**
     * Tells whether any of an applier's retained applications targets a re-priced line.
     *
     * @param applications    the applier's retained applications.
     * @param repricedTargets the offer applications that were switched to their reference price.
     * @return {@code true} when at least one application lands on a re-priced line.
     */
    private static boolean targetsRepricedLine(List<AdvantageApplication> applications,
                                               Set<OfferApplication> repricedTargets) {
        for (AdvantageApplication application : applications) {
            if (repricedTargets.contains(application.getOfferApplication())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Arbitrates one wave: repeatedly applies the best remaining eligible advantage until the
     * wave is exhausted (spec §4.2).
     * <p>
     * Each pass re-sorts the not-yet-settled candidates of the wave by priority ascending,
     * then current sandbox score descending, then configuration code — the code being the
     * stable final tiebreaker. The top candidate is settled (applied or dropped) and the loop
     * repeats on the updated state, so a discount that changes the current amounts is seen by
     * the configurations arbitrated afterwards.
     *
     * @param wave       the application moment of this wave ({@code AT_TRIGGER}/{@code AT_TOTAL}).
     * @param appliers   all advantage appliers.
     * @param configs    the parsed arbitration parameters, per applier.
     * @param evaluation the evaluation context.
     * @param state      the running arbitration state (cumul, exclusion groups, settled set).
     * @param applied    collects, per applier, the applications actually retained (A1 re-pricing).
     */
    private void arbitrateWave(String wave, List<AdvantageApplier> appliers,
                               Map<AdvantageApplier, ArbitrationConfig> configs,
                               BasketEvaluation evaluation, ArbitrationState state,
                               Map<AdvantageApplier, List<AdvantageApplication>> applied) {
        while (true) {
            List<AdvantageApplier> candidates = new ArrayList<>();
            for (AdvantageApplier applier : appliers) {
                if (!state.settled.contains(applier)
                        && configs.get(applier).applicationMoment().equals(wave)) {
                    candidates.add(applier);
                }
            }
            if (candidates.isEmpty()) {
                return;
            }
            candidates.sort(Comparator
                    .comparingInt((AdvantageApplier a) -> configs.get(a).priority())
                    .thenComparing(Comparator.comparingDouble(
                            (AdvantageApplier a) -> a.getEfficiencyScore()).reversed())
                    .thenComparing(a -> configs.get(a).code()));
            AdvantageApplier applier = candidates.get(0);
            state.settled.add(applier);
            applyIfEligible(applier, configs.get(applier), evaluation, state, applied);
        }
    }

    /**
     * Applies one advantage if it passes the full application conjunction of spec §4.2, and
     * records its effects.
     * <p>
     * The conjunction: trigger satisfied (memoized), base non-empty (the applier produces
     * at least one application), cumulable compatible with the advantages already applied, no
     * exclusion group already consumed, and the per-ticket / per-line limits not reached. On
     * success the applications are recorded with their application moment, the exclusion
     * bookkeeping is updated, the carriers are consumed when asked (spec §4.4), and the
     * trigger cache is invalidated (spec §4.3). A trigger satisfied without an application is
     * a non-event: nothing is produced and nothing is consumed.
     *
     * @param applier    the advantage applier.
     * @param config     its arbitration parameters.
     * @param evaluation the evaluation context.
     * @param state      the running arbitration state.
     * @param applied    collects, per applier, the applications actually retained (A1 re-pricing).
     * @return {@code true} when the advantage was applied.
     */
    private boolean applyIfEligible(AdvantageApplier applier, ArbitrationConfig config,
                                    BasketEvaluation evaluation, ArbitrationState state,
                                    Map<AdvantageApplier, List<AdvantageApplication>> applied) {
        // Fail-closed (A2, report H1b): an advantage whose trigger/arbitration block could not be
        // parsed is skipped, never granted as if it were unconditional.
        if (!config.valid()) {
            return false;
        }
        TriggerResult trigger = evaluation.triggerResult(applier.getConfiguration(), config.trigger());
        if (!trigger.satisfied()) {
            return false;
        }
        if (!config.cumulable() && state.nonCumulableApplied) {
            return false;
        }
        for (String group : config.exclusionGroups()) {
            if (state.consumedExclusionGroups.contains(group)) {
                return false;
            }
        }
        // Budget control (spec §5): an advantage whose offer budget or budget group is exhausted is
        // not applied — same mechanic as a cumul refusal, reason traced. Fail-open borné: a counter
        // read error applies the advantage. The current ticket increments nothing.
        if (budgetExhausted(applier.getConfiguration(), evaluation)) {
            return false;
        }
        Collection<AdvantageApplication> produced;
        try {
            produced = applier.apply(evaluation);
        } catch (ConfigurationException e) {
            // Fail-closed (A2, report H1a): a stored-spec problem skips this advantage and continues
            // the arbitration; a genuine runtime error is not ConfigurationException and propagates.
            Offer configuration = applier.getConfiguration();
            skip(evaluation, "Advantage " + (configuration != null ? configuration.code : applier.getClass().getSimpleName()), e);
            return false;
        }
        if (produced == null || produced.isEmpty()) {
            return false;
        }
        List<AdvantageApplication> kept = capApplications(produced, config);
        if (kept.isEmpty()) {
            return false;
        }
        for (AdvantageApplication application : kept) {
            stampConfiguration(application, config);
        }
        evaluation.getAdvantages().addAll(kept);
        applied.put(applier, kept);
        if (!config.cumulable()) {
            state.nonCumulableApplied = true;
        }
        state.consumedExclusionGroups.addAll(config.exclusionGroups());
        if (config.consumesContributors() && !trigger.contributors().isEmpty()) {
            evaluation.markConsumed(trigger.contributors());
        }
        // A discount really applied (the current amounts changed) and possibly carriers were
        // consumed: invalidate the memoized triggers so the next configuration measures the
        // current state (spec §4.3).
        evaluation.invalidateTriggerCache();
        return true;
    }

    /**
     * Caps the produced applications by the per-line then per-ticket limits (spec §4.1).
     * <p>
     * Absent limits leave the applications untouched. The per-line cap keeps at most the
     * allowed number of applications per targeted offer application; the per-ticket cap keeps
     * at most the allowed number overall, in the applier's own order.
     *
     * @param produced the applications the applier produced.
     * @param config   its arbitration parameters.
     * @return the kept applications.
     */
    private List<AdvantageApplication> capApplications(Collection<AdvantageApplication> produced,
                                                       ArbitrationConfig config) {
        List<AdvantageApplication> kept = new ArrayList<>(produced);
        if (config.maxApplicationsPerLine() != null) {
            int max = config.maxApplicationsPerLine();
            Map<OfferApplication, Integer> perTarget = new IdentityHashMap<>();
            List<AdvantageApplication> filtered = new ArrayList<>();
            for (AdvantageApplication application : kept) {
                OfferApplication target = application.getOfferApplication();
                int count = perTarget.getOrDefault(target, 0);
                if (count < max) {
                    filtered.add(application);
                    perTarget.put(target, count + 1);
                }
            }
            kept = filtered;
        }
        if (config.maxApplicationsPerTicket() != null && kept.size() > config.maxApplicationsPerTicket()) {
            kept = new ArrayList<>(kept.subList(0, config.maxApplicationsPerTicket()));
        }
        return kept;
    }

    /**
     * Parses the arbitration parameters of one configuration (spec §3, §4.1).
     * <p>
     * A {@code null} configuration (an applier not born from a configuration row) yields the
     * defaults: {@link Trigger#ALWAYS}, moment {@code AT_TOTAL}, priority 500, cumulable, no
     * exclusion groups, no limits, no consumption. The specification was validated at
     * creation, so a malformed one here falls back on the same defaults rather than failing
     * the whole evaluation.
     * <p>
     * Documented interpretation (spec §5.9, accepted): {@code maxApplicationsPerTicket} and
     * {@code maxApplicationsPerLine} are applied by {@link #capApplications} as a literal cap
     * on the <em>count</em> of applications a configuration produces — overall for the ticket,
     * and per targeted offer application for the line. There is no repeatable-mechanic
     * re-invocation model to bound instead, so the count is what is capped.
     *
     * @param offer      the configuration row, or {@code null}.
     * @param evaluation the evaluation, used to record a fail-closed skip on parse failure.
     * @return the parsed arbitration parameters; {@link ArbitrationConfig#valid()} is
     *         {@code false} when the block could not be parsed and the configuration must be
     *         skipped (A2, report H1b).
     */
    /**
     * Tells whether an offer's generosity budget is exhausted at the arbitration (spec §5).
     * <p>
     * The engine consults ITS counters, at home, with no network call. Two independent porters are
     * checked and the most constrained wins (spec §5.6): the offer's own {@code budget} block (an
     * amount and/or an application ceiling, scoped per point of sale when {@code perStore}) and the
     * offer's budget group (the same two ceilings, applied to the summed consumption of every offer
     * of the group). A reached ceiling refuses the advantage and records the reason
     * ({@code BUDGET_EXHAUSTED} / {@code GROUP_BUDGET_EXHAUSTED}).
     * <p>
     * Fail-open borné (spec §5.5): any error reading the counters applies the advantage and traces
     * the incident — the budget is a piloting instrument, not a comptable guarantee. The current
     * ticket increments nothing; only the cashing counts. An offer with no budget and no group is a
     * strict no-op (bit-for-bit regression, spec §5.6).
     *
     * @param offer      the configuration whose budget is checked, or null.
     * @param evaluation the evaluation, used for the store scope and to record the refusal reason.
     * @return true when the advantage must be refused for an exhausted budget.
     */
    private boolean budgetExhausted(Offer offer, BasketEvaluation evaluation) {
        if (offer == null) {
            return false;
        }
        String storeCode = evaluation != null && evaluation.getStore() != null
                ? evaluation.getStore().code : null;
        String offerCode = offer.code == null ? "" : offer.code;
        try {
            JsonNode budget = readBudget(offer);
            if (budget != null && offerBudgetExhausted(budget, offerCode, storeCode)) {
                recordBudgetRefusal(evaluation, "BUDGET_EXHAUSTED", "offer " + offerCode);
                return true;
            }
            BudgetGroup group = offer.budgetGroup;
            if (group != null && groupBudgetExhausted(group, storeCode)) {
                recordBudgetRefusal(evaluation, "GROUP_BUDGET_EXHAUSTED",
                        "offer " + offerCode + " group " + group.code);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            // Fail-open borné (spec §5.5): apply and trace, never fail the evaluation on a counter read.
            LOGGER.warn("Budget counter read failed for offer " + offerCode + "; applying fail-open.", e);
            recordBudgetRefusal(evaluation, "BUDGET_COUNTER_ERROR", "offer " + offerCode + " (fail-open)");
            return false;
        }
    }

    /**
     * Reads the optional {@code budget} block off an offer's specification.
     *
     * @param offer the offer whose specification is read.
     * @return the {@code budget} node, or null when the offer carries no budget.
     */
    private static JsonNode readBudget(Offer offer) {
        if (offer.specification == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(offer.specification).get("budget");
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            // A malformed specification was rejected at creation; here it simply carries no budget.
            return null;
        }
    }

    /**
     * Tells whether the offer's own budget block is exhausted for the relevant scope.
     *
     * @param budget    the parsed {@code budget} node.
     * @param offerCode the offer code.
     * @param storeCode the basket's point-of-sale code, used when the budget is per-store.
     * @return true when the amount or the application ceiling is reached.
     */
    private static boolean offerBudgetExhausted(JsonNode budget, String offerCode, String storeCode) {
        boolean perStore = budget.hasNonNull("perStore") && budget.get("perStore").asBoolean();
        String scope = perStore ? storeCode : null;
        List<String> single = List.of(offerCode);
        if (budget.hasNonNull("maxAmount")) {
            BigDecimal max = budget.get("maxAmount").decimalValue();
            if (ConsumptionCounter.sumAmount(single, scope).compareTo(max) >= 0) {
                return true;
            }
        }
        if (budget.hasNonNull("maxApplications")) {
            long max = budget.get("maxApplications").asLong();
            return ConsumptionCounter.sumApplications(single, scope) >= max;
        }
        return false;
    }

    /**
     * Tells whether a budget group's envelope is exhausted for the relevant scope, summing the
     * consumption of every offer of the group (spec §5.3).
     *
     * @param group     the budget group.
     * @param storeCode the basket's point-of-sale code, used when the group is per-store.
     * @return true when the group's amount or application ceiling is reached.
     */
    private static boolean groupBudgetExhausted(BudgetGroup group, String storeCode) {
        if (group.maxAmount == null && group.maxApplications == null) {
            return false;
        }
        List<Offer> members = Offer.list("budgetGroup.id = ?1", group.id);
        List<String> codes = new ArrayList<>();
        for (Offer member : members) {
            codes.add(member.code == null ? "" : member.code);
        }
        String scope = group.perStore ? storeCode : null;
        if (group.maxAmount != null
                && ConsumptionCounter.sumAmount(codes, scope).compareTo(group.maxAmount) >= 0) {
            return true;
        }
        return group.maxApplications != null
                && ConsumptionCounter.sumApplications(codes, scope) >= group.maxApplications;
    }

    /**
     * Records a budget refusal reason on the evaluation so the resource can trace it (spec §5.2,
     * §5.3), reusing the same audit sink as the fail-closed configuration skips.
     *
     * @param evaluation the evaluation to record on, possibly null.
     * @param reason     the reason code ({@code BUDGET_EXHAUSTED}, {@code GROUP_BUDGET_EXHAUSTED}
     *                   or {@code BUDGET_COUNTER_ERROR}).
     * @param detail     the offer (and group) the reason applies to.
     */
    private static void recordBudgetRefusal(BasketEvaluation evaluation, String reason, String detail) {
        String message = reason + ": " + detail;
        LOGGER.info(message);
        if (evaluation != null) {
            evaluation.recordSkippedConfiguration(message);
        }
    }

    ArbitrationConfig parseArbitrationConfig(Offer offer, BasketEvaluation evaluation) {
        if (offer == null) {
            return new ArbitrationConfig(Trigger.ALWAYS, "AT_TOTAL", 500, true,
                    List.of(), null, null, false, "", true, null, null, false, false, null);
        }
        String code = offer.code == null ? "" : offer.code;
        try {
            JsonNode spec = MAPPER.readTree(offer.specification);
            Trigger trigger = Trigger.of(spec.get("trigger"));
            String moment = spec.hasNonNull("applicationMoment")
                    ? spec.get("applicationMoment").asText() : "AT_TOTAL";
            // Shared passthrough / compliance fragment (spec-divers-offre §2, §3): copied
            // verbatim (accountingCode, printMode) or read as behaviour flags (compliance).
            String accountingCode = spec.hasNonNull("accountingCode")
                    ? spec.get("accountingCode").asText() : null;
            String printMode = spec.hasNonNull("printMode")
                    ? spec.get("printMode").asText() : null;
            // Funding passthrough (spec-exploitation §3): copied verbatim, never interpreted.
            String funding = spec.hasNonNull("funding")
                    ? spec.get("funding").asText() : null;
            boolean egalimExempt = false;
            boolean srpExempt = false;
            JsonNode compliance = spec.get("compliance");
            if (compliance != null) {
                egalimExempt = compliance.hasNonNull("egalimExempt")
                        && compliance.get("egalimExempt").asBoolean();
                srpExempt = compliance.hasNonNull("srpExempt")
                        && compliance.get("srpExempt").asBoolean();
            }
            int priority = 500;
            boolean cumulable = true;
            List<String> groups = new ArrayList<>();
            Integer maxTicket = null;
            Integer maxLine = null;
            boolean consumes = false;
            JsonNode arbitration = spec.get("arbitration");
            if (arbitration != null) {
                if (arbitration.hasNonNull("priority")) {
                    priority = arbitration.get("priority").asInt();
                }
                if (arbitration.hasNonNull("cumulable")) {
                    cumulable = arbitration.get("cumulable").asBoolean();
                }
                if (arbitration.has("exclusionGroups")) {
                    for (JsonNode group : arbitration.get("exclusionGroups")) {
                        groups.add(group.asText());
                    }
                }
                if (arbitration.hasNonNull("maxApplicationsPerTicket")) {
                    maxTicket = arbitration.get("maxApplicationsPerTicket").asInt();
                }
                if (arbitration.hasNonNull("maxApplicationsPerLine")) {
                    maxLine = arbitration.get("maxApplicationsPerLine").asInt();
                }
                if (arbitration.hasNonNull("consumesContributors")) {
                    consumes = arbitration.get("consumesContributors").asBoolean();
                }
            }
            return new ArbitrationConfig(trigger, moment, priority, cumulable,
                    List.copyOf(groups), maxTicket, maxLine, consumes, code, true,
                    accountingCode, printMode, egalimExempt, srpExempt, funding);
        } catch (Exception e) {
            // Fail-closed (A2, report H1b): an unparseable trigger/arbitration block no longer
            // falls back on Trigger.ALWAYS (fail-open on money); the configuration is marked
            // invalid so its offer/advantage is skipped, and the skip is logged and recorded.
            skip(evaluation, "Configuration " + code, e);
            return new ArbitrationConfig(Trigger.ALWAYS, "AT_TOTAL", 500, true,
                    List.of(), null, null, false, code, false, null, null, false, false, null);
        }
    }

    /**
     * Tells whether a basket is closed (spec §3.7): a fact declared by the caller, never
     * guessed. A {@code null} basket or a basket without {@code closed} is closed — the
     * default and the current behaviour.
     *
     * @param basket the basket.
     * @return {@code true} when the basket is closed.
     */
    private static boolean isBasketClosed(Basket basket) {
        return basket == null || basket.closed == null || basket.closed;
    }

    /**
     * The arbitration parameters of one configuration (spec §3, §4.1), parsed once.
     *
     * @param trigger                  the configuration's trigger ({@link Trigger#ALWAYS} when
     *                                 none).
     * @param applicationMoment        {@code AT_TRIGGER} or {@code AT_TOTAL}.
     * @param priority                 the arbitration priority (smaller = arbitrated earlier).
     * @param cumulable                whether the advantage cumulates with other non-cumulable
     *                                 advantages.
     * @param exclusionGroups          the named exclusion groups, at most one applied per group.
     * @param maxApplicationsPerTicket the per-ticket application cap, or {@code null} for none.
     * @param maxApplicationsPerLine   the per-line application cap, or {@code null} for none.
     * @param consumesContributors     whether the trigger contributors become consumed carriers.
     * @param code                     the configuration code, the stable ordering tiebreaker.
     * @param valid                    whether the block parsed; a {@code false} configuration is
     *                                 skipped fail-closed (A2, report H1b).
     * @param accountingCode           the accounting ventilation key copied verbatim to the
     *                                 output, or {@code null} (spec-divers-offre §3).
     * @param printMode                the ticket print mode ({@code LINE}/{@code FOOTER}) copied
     *                                 verbatim to the output, or {@code null} (spec-divers-offre §3).
     * @param egalimExempt             whether the configuration's discounts are EGAlim-exempt,
     *                                 counted as excluded generosity (spec-divers-offre §2).
     * @param srpExempt                whether the configuration's discounts are SRP-exempt,
     *                                 carried for the future SRP guard (spec-divers-offre §2).
     * @param funding                  who funds the promotion ({@code STORE}/{@code BANNER}/
     *                                 {@code SPLIT_50_50}) copied verbatim to the output, or
     *                                 {@code null} (spec-exploitation §3).
     */
    record ArbitrationConfig(Trigger trigger, String applicationMoment, int priority,
                             boolean cumulable, List<String> exclusionGroups,
                             Integer maxApplicationsPerTicket, Integer maxApplicationsPerLine,
                             boolean consumesContributors, String code, boolean valid,
                             String accountingCode, String printMode,
                             boolean egalimExempt, boolean srpExempt, String funding) {
    }

    /**
     * The running state of the two-wave arbitration.
     */
    private static final class ArbitrationState {

        /**
         * The appliers settled (applied or definitively dropped) in this evaluation.
         */
        private final Set<AdvantageApplier> settled = Collections.newSetFromMap(new IdentityHashMap<>());

        /**
         * Whether a non-cumulable advantage has already been applied on this ticket.
         */
        private boolean nonCumulableApplied = false;

        /**
         * The exclusion groups already consumed by an applied advantage.
         */
        private final Set<String> consumedExclusionGroups = new HashSet<>();
    }

    /**
     * Shared JSON mapper for parsing arbitration parameters from configurations.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Creates a list of {@link AdvantageApplier} instances relevant to the provided basket evaluation.
     * <p>
     * This method iterates over all available {@link AdvantageApplierFactory} beans
     * and delegates the creation of appliers to them.
     * <p>
     * Errors during factory processing are caught and logged to ensure that
     * one faulty factory does not stop the entire valuation process.
     *
     * @param basketEvaluation The basket evaluation used to build appliers.
     * @return A list of available offer appliers.
     */
    List<AdvantageApplier> createDiscountAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        if (discountFactories != null) {
            for (AdvantageApplierFactory factory : discountFactories) {
                try {
                    Collection<AdvantageApplier> builtAppliers = factory.buildAppliers(basketEvaluation);
                    if (builtAppliers != null) {
                        appliers.addAll(builtAppliers);
                    }
                } catch (ConfigurationException e) {
                    // Fail-closed per configuration (A2, report H1a): skip the faulty specification
                    // and continue; request-level errors are not ConfigurationException and propagate.
                    skip(basketEvaluation, "Advantage factory " + factory.getClass().getSimpleName(), e);
                }
            }
        }
        return appliers;
    }

    /**
     * Skips a configuration fail-closed (A2, report H1): logs the reason as an error and records it
     * on the evaluation so the resource can trace it, without interrupting the valuation.
     *
     * @param evaluation the evaluation to record the skip on.
     * @param what       a short description of what was skipped.
     * @param cause      the error that caused the skip.
     */
    private static void skip(BasketEvaluation evaluation, String what, Throwable cause) {
        String message = what + " skipped: " + cause.getMessage();
        LOGGER.error(message, cause);
        if (evaluation != null) {
            evaluation.recordSkippedConfiguration(message);
        }
    }

    // --------------------------------------------------
    // Helper Methods for external/advanced access
    // --------------------------------------------------

    /**
     * Calculates the total price of the basket before discounts are applied.
     *
     * @param evaluation The evaluation context containing the applied offers.
     * @return The total TTC price before discounts.
     */
    public BigDecimal calculateTotalHorsDiscount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (OfferApplication app : evaluation.getOffers()) {
            if (app.getAmount() != null) {
                total = total.add(app.getAmount().amountIncludingTax);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the total amount of discounts to be applied.
     *
     * @param evaluation The evaluation context containing the applied discounts.
     * @return The total amount of money saved (positive value).
     */
    public BigDecimal calculateTotalDiscount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication app : evaluation.getAdvantages()) {
            if (app instanceof DiscountApplication) {
                DiscountApplication discount = (DiscountApplication) app;
                if (discount.getDiscountAmount() != null) {
                    total = total.add(discount.getDiscountAmount().amountIncludingTax.abs());
                }
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates the final total price to be paid.
     * Calculation: Total Hors Discount - Total Discount.
     *
     * @param evaluation The evaluation context.
     * @return The final net price to pay.
     */
    public BigDecimal calculateRealTotal(BasketEvaluation evaluation) {
        return calculateTotalHorsDiscount(evaluation).subtract(calculateTotalDiscount(evaluation));
    }

    // --------------------------------------------------
    // Inner Classes
    // --------------------------------------------------

    /**
     * Inner class responsible for sorting {@link OfferApplier} instances.
     * <p>
     * This class encapsulates the sorting logic, allowing it to be modified
     * without affecting the main engine logic.
     * <p>
     * Sorting is based on efficiency score in descending order
     * (Higher score = processed first).
     */
    public static class OfferApplierEvaluator {

        /**
         * Sorts the provided list of appliers in place.
         *
         * @param appliers The list of appliers to sort.
         */
        public void sort(List<OfferApplier> appliers, BasketEvaluation evaluation) {
            // Sort by efficiency score descending (high score = most efficient / highest
            // priority), then by a stable tie-break key so appliers of equal score keep a
            // deterministic order between runs and nodes (report C2), mirroring the advantage
            // wave's code tie-break.
            appliers.sort(Comparator.comparingDouble(OfferApplier::getEfficiencyScore).reversed()
                    .thenComparing(OfferApplier::getTieBreakKey));
        }
    }

}