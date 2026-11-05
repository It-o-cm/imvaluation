package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage complement for {@link NetAmounts}: pins the defensive and clamp arms that
 * {@link NetAmountsTest} leaves untouched — the null-advantages guards of {@code retainedAgainst}
 * and {@code netProductTtc}, the null and non-positive amount guards of {@code netFactor}, the
 * negative-factor clamp, the null pass-through of {@code net}, the null and non-positive gross
 * guards of {@code netProductTtc}, its application-gross fallback and every {@code continue} arm of
 * its advantage loop. Pure in-memory arithmetic: mocked collaborators, no database.
 */
public class NetAmountsCoverageTest {

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
     * Builds an amount whose tax-included component is explicitly null, to drive the tax-included
     * null guards.
     *
     * @return the amount with a null tax-included component.
     */
    private static AmountEvaluation nullTtc() {
        AmountEvaluation amount = new AmountEvaluation();
        amount.amountIncludingTax = null;
        return amount;
    }

    /**
     * Builds a product-scoped discount against an application, targeting one EAN for a given amount.
     *
     * @param target the targeted application.
     * @param ean    the discounted EAN.
     * @param value  the discount amount.
     * @return the scoped discount.
     */
    private static ScopedDiscount scoped(OfferApplication target, String ean, AmountEvaluation value) {
        return new ScopedDiscount(target, ean, value);
    }

    /**
     * A plain advantage that is not a discount, used to exercise the non-discount branch of the
     * advantage loop. Implemented for real (rather than mocked) so no default-method proxy class is
     * generated.
     */
    private static final class NonDiscountAdvantage implements AdvantageApplication {

        /**
         * Returns no associated offer application.
         *
         * @return null.
         */
        @Override
        public OfferApplication getOfferApplication() {
            return null;
        }
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
        private final AmountEvaluation value;

        /**
         * Builds the scoped discount.
         *
         * @param target the targeted application.
         * @param ean    the discounted EAN.
         * @param value  the discount amount.
         */
        private ScopedDiscount(OfferApplication target, String ean, AmountEvaluation value) {
            this.target = target;
            this.ean = ean;
            this.value = value;
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
            return value;
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
     * A null advantages list makes {@code retainedAgainst} return zero without iterating.
     */
    @Test
    void retainedAgainstNullAdvantagesIsZero() {
        OfferApplication app = mock(OfferApplication.class);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(null);
        assertEquals(0, BigDecimal.ZERO.compareTo(NetAmounts.retainedAgainst(evaluation, app)));
    }

    /**
     * A discount against the application whose amount is null is skipped, so only the valid discount
     * is summed.
     */
    @Test
    void retainedAgainstSkipsNullDiscountAmount() {
        OfferApplication app = mock(OfferApplication.class);
        DiscountApplication nullAmount = mock(DiscountApplication.class);
        when(nullAmount.getOfferApplication()).thenReturn(app);
        when(nullAmount.getDiscountAmount()).thenReturn(null);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                nullAmount, scoped(app, "A", amount("25.00", "30.00", "0.20"))));
        assertEquals(0, new BigDecimal("30.00").compareTo(NetAmounts.retainedAgainst(evaluation, app)));
    }

    /**
     * A discount against the application whose amount carries a null tax-included component is
     * skipped, so only the valid discount is summed.
     */
    @Test
    void retainedAgainstSkipsNullTaxIncludedDiscount() {
        OfferApplication app = mock(OfferApplication.class);
        DiscountApplication nullTtc = mock(DiscountApplication.class);
        when(nullTtc.getOfferApplication()).thenReturn(app);
        when(nullTtc.getDiscountAmount()).thenReturn(nullTtc());
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                nullTtc, scoped(app, "A", amount("25.00", "30.00", "0.20"))));
        assertEquals(0, new BigDecimal("30.00").compareTo(NetAmounts.retainedAgainst(evaluation, app)));
    }

    /**
     * {@code netFactor} returns one when the application's amount carries a null tax-included
     * component.
     */
    @Test
    void netFactorIsOneWhenTaxIncludedNull() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(nullTtc());
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        assertEquals(0, BigDecimal.ONE.compareTo(NetAmounts.netFactor(evaluation, app)));
    }

    /**
     * {@code netFactor} returns one when the application's tax-included amount is not strictly
     * positive.
     */
    @Test
    void netFactorIsOneWhenAmountNotPositive() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("0.00", "0.00", "0.00"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        assertEquals(0, BigDecimal.ONE.compareTo(NetAmounts.netFactor(evaluation, app)));
    }

    /**
     * {@code netFactor} clamps to zero when the retained discounts exceed the application's gross,
     * so the factor never goes negative.
     */
    @Test
    void netFactorClampsToZeroWhenRetainedExceedsGross() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("83.33", "100.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(
                scoped(app, "A", amount("125.00", "150.00", "0.20"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(NetAmounts.netFactor(evaluation, app)));
    }

    /**
     * {@code net} passes a null amount through unchanged (as null).
     */
    @Test
    void netReturnsNullForNullAmount() {
        OfferApplication app = mock(OfferApplication.class);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        assertNull(NetAmounts.net(evaluation, app, null));
    }

    /**
     * {@code net} scales a non-null amount by the application's net factor; with no retained
     * discount the factor is one, so the amount is returned unchanged to the cent.
     */
    @Test
    void netScalesByUnitFactorWhenNothingRetained() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of());
        AmountEvaluation netted = NetAmounts.net(evaluation, app, amount("83.33", "100.00", "0.20"));
        assertEquals(0, new BigDecimal("100.00").compareTo(netted.amountIncludingTax));
        assertEquals(0, new BigDecimal("83.33").compareTo(netted.amountExcludingTax));
    }

    /**
     * {@code netProductTtc} returns zero for a null gross tranche.
     */
    @Test
    void netProductTtcReturnsZeroForNullGross() {
        OfferApplication app = mock(OfferApplication.class);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(NetAmounts.netProductTtc(evaluation, app, "A", null)));
    }

    /**
     * {@code netProductTtc} returns the gross unchanged when it is not strictly positive (here
     * zero), never entering the netting path.
     */
    @Test
    void netProductTtcReturnsGrossWhenNotPositive() {
        OfferApplication app = mock(OfferApplication.class);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        assertEquals(0, new BigDecimal("0.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("0.00"))));
    }

    /**
     * When the application carries no amount, {@code netProductTtc} falls back to the product gross
     * as the proration base, so an unscoped discount is spread against the tranche itself.
     */
    @Test
    void netProductTtcFallsBackToGrossWhenApplicationHasNoAmount() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(null);
        DiscountApplication ticketWide = mock(DiscountApplication.class);
        when(ticketWide.getOfferApplication()).thenReturn(app);
        when(ticketWide.getDiscountAmount()).thenReturn(amount("33.33", "40.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(ticketWide));
        // appGross falls back to the 100 gross, so 40 * 100/100 = 40 is attributed: 100 - 40 = 60.
        assertEquals(0, new BigDecimal("60.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * When the application's amount carries a null tax-included component, {@code netProductTtc}
     * falls back to the product gross as the proration base.
     */
    @Test
    void netProductTtcFallsBackWhenApplicationTaxIncludedNull() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(nullTtc());
        DiscountApplication ticketWide = mock(DiscountApplication.class);
        when(ticketWide.getOfferApplication()).thenReturn(app);
        when(ticketWide.getDiscountAmount()).thenReturn(amount("33.33", "40.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(ticketWide));
        assertEquals(0, new BigDecimal("60.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * When the application's tax-included amount is not strictly positive, {@code netProductTtc}
     * falls back to the product gross as the proration base.
     */
    @Test
    void netProductTtcFallsBackWhenApplicationAmountNotPositive() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("0.00", "0.00", "0.00"));
        DiscountApplication ticketWide = mock(DiscountApplication.class);
        when(ticketWide.getOfferApplication()).thenReturn(app);
        when(ticketWide.getDiscountAmount()).thenReturn(amount("33.33", "40.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(ticketWide));
        assertEquals(0, new BigDecimal("60.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * A null advantages list makes {@code netProductTtc} return the gross unchanged.
     */
    @Test
    void netProductTtcNullAdvantagesReturnsGross() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(null);
        assertEquals(0, new BigDecimal("100.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * An advantage that is not a discount is skipped by {@code netProductTtc}, leaving the gross
     * unchanged.
     */
    @Test
    void netProductTtcSkipsNonDiscountAdvantage() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        AdvantageApplication notADiscount = new NonDiscountAdvantage();
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(notADiscount));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * A discount whose amount is null is skipped by {@code netProductTtc}, leaving the gross
     * unchanged.
     */
    @Test
    void netProductTtcSkipsNullDiscountAmount() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        DiscountApplication nullAmount = mock(DiscountApplication.class);
        when(nullAmount.getOfferApplication()).thenReturn(app);
        when(nullAmount.getDiscountAmount()).thenReturn(null);
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(nullAmount));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }

    /**
     * A discount whose amount carries a null tax-included component is skipped by
     * {@code netProductTtc}, leaving the gross unchanged.
     */
    @Test
    void netProductTtcSkipsNullTaxIncludedDiscount() {
        OfferApplication app = mock(OfferApplication.class);
        when(app.getAmount()).thenReturn(amount("166.67", "200.00", "0.20"));
        DiscountApplication nullTtc = mock(DiscountApplication.class);
        when(nullTtc.getOfferApplication()).thenReturn(app);
        when(nullTtc.getDiscountAmount()).thenReturn(nullTtc());
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getAdvantages()).thenReturn(List.of(nullTtc));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                NetAmounts.netProductTtc(evaluation, app, "A", new BigDecimal("100.00"))));
    }
}
