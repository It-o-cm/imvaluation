package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared "net of already-retained discounts" arithmetic (A3, report H2).
 * <p>
 * Caps and measurements throughout the engine must be taken on amounts net of the advantages
 * already retained, so a second reduction can never take more than what a line is still worth and
 * a ticket total can never go negative. This is the single home of that netting, generalising the
 * factor that previously lived, duplicated, inside {@link MinimumAmountCondition} and
 * {@code NewPriceDiscountFactory}.
 * <p>
 * All amounts are tax included and every result is clamped to be non-negative.
 */
public final class NetAmounts {

    /**
     * Not instantiable.
     */
    private NetAmounts() {
    }

    /**
     * Sums the discounts already retained against one offer application, tax included.
     *
     * @param evaluation the evaluation context.
     * @param app        the offer application.
     * @return the retained discount total against {@code app}, tax included, never negative.
     */
    public static BigDecimal retainedAgainst(BasketEvaluation evaluation, OfferApplication app) {
        BigDecimal total = BigDecimal.ZERO;
        if (evaluation.getAdvantages() != null) {
            for (AdvantageApplication advantage : evaluation.getAdvantages()) {
                if (advantage instanceof DiscountApplication discount
                        && discount.getOfferApplication() == app
                        && discount.getDiscountAmount() != null
                        && discount.getDiscountAmount().amountIncludingTax != null) {
                    total = total.add(discount.getDiscountAmount().amountIncludingTax);
                }
            }
        }
        return total;
    }

    /**
     * Computes the net factor of an offer application: the share of its amount left once the
     * advantages retained against it are removed, clamped to {@code [0, 1]}.
     *
     * @param evaluation the evaluation context.
     * @param app        the offer application to net.
     * @return the net factor in {@code [0, 1]}, or one when the application carries no amount.
     */
    public static BigDecimal netFactor(BasketEvaluation evaluation, OfferApplication app) {
        AmountEvaluation amount = app.getAmount();
        if (amount == null || amount.amountIncludingTax == null || amount.amountIncludingTax.signum() <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal gross = amount.amountIncludingTax;
        BigDecimal factor = gross.subtract(retainedAgainst(evaluation, app))
                .divide(gross, 6, RoundingMode.HALF_UP);
        return factor.signum() < 0 ? BigDecimal.ZERO : factor;
    }

    /**
     * Returns an amount scaled by an application's net factor, tax included and excluded alike.
     *
     * @param evaluation the evaluation context.
     * @param app        the offer application whose net factor is applied.
     * @param amount     the gross amount to net.
     * @return a new amount netted by {@code app}'s factor, or {@code amount} when it is null.
     */
    public static AmountEvaluation net(BasketEvaluation evaluation, OfferApplication app, AmountEvaluation amount) {
        if (amount == null) {
            return null;
        }
        return amount.multiply(netFactor(evaluation, app));
    }

    /**
     * Computes the net tax-included amount of a single product tranche within an offer application
     * (A3, report H2c).
     * <p>
     * The netting is per-product: a discount scoped to this exact product (a
     * {@link ProductScopedDiscount} whose EAN matches) is subtracted in full, a discount scoped to
     * another product of the same application contributes nothing, and a discount that is not
     * product-scoped (a ticket-wide reduction) is prorated by the product's share of the
     * application. This replaces applying the whole application's net factor to one product's
     * tranche, which over-nets a bundle where a prior discount hit a different product.
     *
     * @param evaluation      the evaluation context.
     * @param app             the offer application the product belongs to.
     * @param ean             the product EAN.
     * @param grossProductTtc the product tranche's gross amount, tax included.
     * @return the net product amount, tax included, never negative.
     */
    public static BigDecimal netProductTtc(BasketEvaluation evaluation, OfferApplication app,
                                           String ean, BigDecimal grossProductTtc) {
        if (grossProductTtc == null || grossProductTtc.signum() <= 0) {
            return grossProductTtc == null ? BigDecimal.ZERO : grossProductTtc;
        }
        AmountEvaluation appAmount = app.getAmount();
        BigDecimal appGross = (appAmount != null && appAmount.amountIncludingTax != null
                && appAmount.amountIncludingTax.signum() > 0)
                ? appAmount.amountIncludingTax : grossProductTtc;
        BigDecimal attributed = BigDecimal.ZERO;
        if (evaluation.getAdvantages() != null) {
            for (AdvantageApplication advantage : evaluation.getAdvantages()) {
                if (!(advantage instanceof DiscountApplication discount)
                        || discount.getOfferApplication() != app
                        || discount.getDiscountAmount() == null
                        || discount.getDiscountAmount().amountIncludingTax == null) {
                    continue;
                }
                BigDecimal discountTtc = discount.getDiscountAmount().amountIncludingTax;
                if (discount instanceof ProductScopedDiscount scoped) {
                    // A product-scoped discount reduces only its own product line.
                    if (ean.equals(scoped.discountedEan())) {
                        attributed = attributed.add(discountTtc);
                    }
                } else {
                    // A ticket-wide discount is spread across the application by product share.
                    attributed = attributed.add(discountTtc.multiply(grossProductTtc)
                            .divide(appGross, 2, RoundingMode.HALF_UP));
                }
            }
        }
        BigDecimal net = grossProductTtc.subtract(attributed);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }
}
