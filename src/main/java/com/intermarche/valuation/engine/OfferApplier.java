package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.PriceUsage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Service Provider Interface (SPI) for applying offers to a shopping basket.
 * <p>
 * Implementations of this interface are responsible for analyzing the provided basket,
 * finding applicable offers, and returning the results of those calculations.
 */
public abstract class OfferApplier {

    final Collection<AdvantageApplier> discountAppliers = new ArrayList<>();

    /**
     * The efficiency score of this offer applier.
     */
    private double efficiencyScore = -1.0;

    /**
     * Applies offer logic to the provided basket.
     * <p>
     * This method analyzes the basket contents, store, and customer details
     * to determine which offers apply and their respective impacts.
     *
     * @param basketEvaluation The evaluation context containing the basket and its items.
     * @return A collection of offer applications representing calculated offers.
     */
    public abstract Collection<OfferApplication> apply(BasketEvaluation basketEvaluation);

    /**
     * Returns the configuration (the {@link Offer} table row) this offer applier was built
     * from.
     * <p>
     * Only the optional offers ({@code N+M}, {@code MIXED_BUNDLE}) need this link, so their
     * trigger can be evaluated by the arbitration (spec §4.2.A): an optional offer whose
     * trigger is not satisfied is dropped and its lines fall back on the Basic valuation.
     * Abstract on purpose so every offer applier answers honestly: an applier not born from
     * a configuration row (Basic valuation, manual gesture, generic line) returns
     * {@code null}, which the arbitration reads as {@link Trigger#ALWAYS}.
     *
     * @return the source configuration, or {@code null} when the applier is not born from a
     *         configuration row.
     */
    public abstract Offer getConfiguration();

    /**
     * Sets the efficiency score of this offer applier.
     *
     * @param efficiencyScore The efficiency score as a double.
     */
    public void setEfficiencyScore(double efficiencyScore) {
        this.efficiencyScore = efficiencyScore;
    }

    /**
     * Retrieves the efficiency score of this offer applier.
     *
     * @return The efficiency score as a double.
     */
    public final double getEfficiencyScore() {
        return efficiencyScore;
    }

    /**
     * Compute the efficiency score of this offer applier.
     * <p>
     * The efficiency score is a metric used to prioritize offer appliers.
     * Higher scores indicate more efficient or relevant appliers.
     *
     * @return The efficiency score as a double.
     */
    public double computeEfficiencyScore(Basket basket) {
        BasketEvaluation basketEvaluation = new BasketEvaluation(basket);
        basketEvaluation.feedFrom(basket);
        Collection<OfferApplication> offerApplications = this.apply(basketEvaluation);
        basketEvaluation.getOffers().addAll(offerApplications);
        AmountEvaluation totalAmountEvaluation = new AmountEvaluation();
        for (OfferApplication app : offerApplications) {
            totalAmountEvaluation = totalAmountEvaluation.add(app.getAmount());
        }
        Collection<Basket.Item> items = basketEvaluation.getOffers()
            .stream()
            .flatMap(offerApp -> offerApp.getItems().stream())
                .toList();
        AmountEvaluation referenceAmountEvaluation = AmountEvaluation.getAmount(items, basketEvaluation.getStore(), PriceUsage.DEFAULT);
        for (AdvantageApplier applier : this.getDiscountAppliers()) {
            Collection<AdvantageApplication> dApps = applier.apply(basketEvaluation);
            basketEvaluation.getAdvantages().addAll(dApps);
        }
        AmountEvaluation totalDiscountEvaluation = new AmountEvaluation();
        for (AdvantageApplication dApp : basketEvaluation.getAdvantages()) {
            if (dApp instanceof DiscountApplication) {
                DiscountApplication discountApp = (DiscountApplication) dApp;
                totalDiscountEvaluation = totalDiscountEvaluation.add(discountApp.getDiscountAmount());
            }
        }
        return (referenceAmountEvaluation.amountIncludingTax.doubleValue() - totalAmountEvaluation.amountIncludingTax.doubleValue()
                + totalDiscountEvaluation.amountIncludingTax.doubleValue()) /
                totalAmountEvaluation.amountIncludingTax.doubleValue();
    }

    /**
     * Returns a stable key breaking ties between appliers of equal efficiency score (report C2).
     * <p>
     * The offer-applier sort orders by efficiency score descending; two appliers of equal score
     * would otherwise keep whatever order their non-deterministic build produced, so the offer
     * that consumes a shared line could differ between runs. The default key is the configuration
     * code when the applier is born from a configuration row, otherwise the empty string; appliers
     * built one-per-product (Basic valuation) override this to return their EAN. The key is never
     * {@code null}, so it is a total order in the comparator.
     *
     * @return the stable tie-break key; never {@code null}.
     */
    public String getTieBreakKey() {
        Offer configuration = getConfiguration();
        return configuration != null && configuration.code != null ? configuration.code : "";
    }

    /**
     * Registers a discount applier to be used by the offer appliers created by this factory.
     * <p>
     * This method allows the factory to associate discount appliers with the offer appliers
     * it creates, enabling complex pricing strategies that combine offers and discounts.
     *
     * @param discountApplier The discount applier to register.
     */
    public void registerDiscountApplier(AdvantageApplier discountApplier) {
        this.discountAppliers.add(discountApplier);
    }

    /**
     * Retrieves the collection of registered discount appliers.
     *
     * @return A collection of discount appliers associated with this offer applier.
     */
    public Collection<AdvantageApplier> getDiscountAppliers() {
        return discountAppliers;
    }
}