package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NetAmounts}, the shared "net of already-retained discounts" arithmetic
 * (A3, report H2). Plain Mockito: no application boot, no database.
 */
public class NetAmountsTest {

    /**
     * Builds a tax-included amount with a matching excluding-tax value at the given rate.
     *
     * @param ht   the amount excluding tax.
     * @param ttc  the amount including tax.
     * @param rate the VAT rate.
     * @return the amount.
     */
    private static AmountEvaluation amount(String ht, String ttc, String rate) {
        return new AmountEvaluation(new BigDecimal(ht), new BigDecimal(ttc), new BigDecimal(rate));
    }

    /**
     * A product-scoped discount fake against a given application and EAN.
     */
    private static final class ScopedDiscount implements DiscountApplication, ProductScopedDiscount {

        /** The targeted application. */
        private final OfferApplication target;
        /** The discounted EAN. */
        private final String ean;
        /** The discount amount. */
        private final AmountEvaluation amount;

        /**
         * Builds the scoped discount.
         *
         * @param target the targeted application.
         * @param ean    the discounted EAN.
         * @param amount the discount amount.
         */
        private ScopedDiscount(OfferApplication target, String ean, AmountEvaluation amount) {
            this.target = target;
            this.ean = ean;
            this.amount = amount;
        }

        /**
         * Returns the targeted application.
         *
         * @return the target.
         */
        @Override
        public OfferApplication getOfferApplication() {
            return target;
        }

        /**
         * Returns the discount amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return amount;
        }

        /**
         * Returns the discounted EAN.
         *
         * @return the EAN.
         */
        @Override
        public String discountedEan() {
            return ean;
        }
    }

    /**
     * {@link NetAmounts#retainedAgainst} sums the discounts retained against one application.
     */
    @Test
    void retainedAgainstSumsDiscounts() {
        OfferApplication app = mock(OfferApplication.class);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                new ScopedDiscount(app, "A", amount("25.00", "30.00", "0.20"))));
        assertEquals(0, new BigDecimal("30.00").compareTo(NetAmounts.retainedAgainst(evaluation, app)));
    }

    /**
     * {@link NetAmounts#netFactor} is the application's residual share, clamped to {@code [0,1]}.
     */
    @Test
    void netFactorIsResidualShare() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                new ScopedDiscount(app, "A", amount("25.00", "30.00", "0.20")),
                new ScopedDiscount(app, "B", amount("33.33", "40.00", "0.20"))));
        // (200 - 30 - 40) / 200 = 0.65.
        assertEquals(0, new BigDecimal("0.650000").compareTo(NetAmounts.netFactor(evaluation, app)));
    }

    /**
     * A discount scoped to product B does not net product A of the same application (report H2c):
     * A keeps its full gross, while B is netted by its own discount.
     */
    @Test
    void netProductTtcAttributesScopedDiscountToItsOwnProductOnly() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                new ScopedDiscount(app, "B", amount("25.00", "30.00", "0.20"))));
        // A is untouched by B's discount; B is netted by its own 30.
        assertEquals(0, new BigDecimal("100.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
        assertEquals(0, new BigDecimal("70.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "B", new BigDecimal("100.00"))));
    }

    /**
     * A discount that is not product-scoped (a ticket-wide reduction) is prorated by the product's
     * share of the application, while a scoped discount still lands only on its own product.
     */
    @Test
    void netProductTtcProratesUnscopedDiscountByShare() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        DiscountApplication ticketWide = mock(DiscountApplication.class);
        when(ticketWide.getOfferApplication()).thenReturn(app);
        when(ticketWide.getDiscountAmount()).thenReturn(amount("33.33", "40.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                new ScopedDiscount(app, "B", amount("25.00", "30.00", "0.20")), ticketWide));
        // A: 100 - (40 * 100/200) = 80. B: 100 - 30 (scoped) - (40 * 100/200) = 50.
        assertEquals(0, new BigDecimal("80.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
        assertEquals(0, new BigDecimal("50.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "B", new BigDecimal("100.00"))));
    }

    /**
     * The net product amount is clamped to zero: discounts exceeding the product's gross never
     * make it negative.
     */
    @Test
    void netProductTtcClampsToZero() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("83.33", "100.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                new ScopedDiscount(app, "A", amount("125.00", "150.00", "0.20"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * {@link NetAmounts#netFactor} returns one when the application carries no positive amount, so
     * a valueless application is never netted.
     */
    @Test
    void netFactorIsOneWhenNoAmount() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(null);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        assertEquals(0, BigDecimal.ONE.compareTo(NetAmounts.netFactor(evaluation, app)));
    }
}
