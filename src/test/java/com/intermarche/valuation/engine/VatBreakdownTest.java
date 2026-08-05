package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link VatBreakdown}.
 * <p>
 * {@code VatBreakdown} is pure computation: it groups an evaluation's offers and discounts
 * into one line per real VAT rate, spreads blended discounts by tax-included weight, and
 * reconciles the lines with the basket total. The collaborators ({@link OfferApplication},
 * {@link DiscountApplication}, {@link BasketEvaluation}) are SPIs or holders, so they are
 * mocked; the amounts are built in memory and the arithmetic is asserted to the cent.
 */
class VatBreakdownTest {

    /**
     * Builds a fully-populated {@link AmountEvaluation} through its scaling constructor.
     *
     * @param ht   The pre-tax amount.
     * @param ttc  The tax-included amount.
     * @param rate The VAT rate.
     * @return The amount with the canonical scales applied.
     */
    private static AmountEvaluation amt(String ht, String ttc, String rate) {
        return new AmountEvaluation(new BigDecimal(ht), new BigDecimal(ttc), new BigDecimal(rate));
    }

    /**
     * Builds an {@link AmountEvaluation} with raw fields, bypassing the scaling constructor so
     * individual fields may be left {@code null}.
     *
     * @param ht   The pre-tax amount, possibly {@code null}.
     * @param ttc  The tax-included amount, possibly {@code null}.
     * @param rate The VAT rate, possibly {@code null}.
     * @return The amount carrying exactly the given fields.
     */
    private static AmountEvaluation raw(BigDecimal ht, BigDecimal ttc, BigDecimal rate) {
        AmountEvaluation amount = new AmountEvaluation();
        amount.amountExcludingTax = ht;
        amount.amountIncludingTax = ttc;
        amount.vatRate = rate;
        return amount;
    }

    /**
     * Builds a result item carrying a given attributed amount.
     *
     * @param amount The attributed amount, possibly {@code null}.
     * @return The result item.
     */
    private static BasketEvaluation.Item item(AmountEvaluation amount) {
        BasketEvaluation.Item item = new BasketEvaluation.Item();
        item.amount = amount;
        return item;
    }

    /**
     * Builds a mock offer that exposes valued result items and never an own amount.
     *
     * @param items The valued items, possibly {@code null} to simulate an offer with no line.
     * @return The configured mock.
     */
    private static OfferApplication offerWithItems(List<BasketEvaluation.Item> items) {
        OfferApplication offer = Mockito.mock(OfferApplication.class);
        Mockito.when(offer.getValuedItems()).thenReturn(items);
        return offer;
    }

    /**
     * Builds a mock offer that carries only its own amount, with no valued line.
     *
     * @param amount The offer's own amount.
     * @return The configured mock.
     */
    private static OfferApplication offerWithAmount(AmountEvaluation amount) {
        OfferApplication offer = Mockito.mock(OfferApplication.class);
        Mockito.when(offer.getValuedItems()).thenReturn(null);
        Mockito.when(offer.getAmount()).thenReturn(amount);
        return offer;
    }

    /**
     * Builds a mock offer with an empty valued list and no own amount: it prices nothing.
     *
     * @return The configured mock.
     */
    private static OfferApplication offerEmptyAndUnpriced() {
        OfferApplication offer = Mockito.mock(OfferApplication.class);
        Mockito.when(offer.getValuedItems()).thenReturn(List.of());
        Mockito.when(offer.getAmount()).thenReturn(null);
        return offer;
    }

    /**
     * Builds a mock discount with a given amount and target offer.
     *
     * @param amount The discount amount, possibly {@code null}.
     * @param target The targeted offer, possibly {@code null}.
     * @return The configured mock.
     */
    private static DiscountApplication discount(AmountEvaluation amount, OfferApplication target) {
        DiscountApplication discount = Mockito.mock(DiscountApplication.class);
        Mockito.when(discount.getDiscountAmount()).thenReturn(amount);
        Mockito.when(discount.getOfferApplication()).thenReturn(target);
        return discount;
    }

    /**
     * Builds a mock evaluation exposing the given offers, advantages and total.
     *
     * @param offers     The applied offers.
     * @param advantages The applied advantages.
     * @param total      The basket total, possibly {@code null}.
     * @return The configured mock.
     */
    private static BasketEvaluation eval(List<OfferApplication> offers,
                                         List<AdvantageApplication> advantages,
                                         AmountEvaluation total) {
        BasketEvaluation evaluation = Mockito.mock(BasketEvaluation.class);
        Mockito.when(evaluation.getOffers()).thenReturn(offers);
        Mockito.when(evaluation.getAdvantages()).thenReturn(advantages);
        Mockito.when(evaluation.getTotalPrice()).thenReturn(total);
        return evaluation;
    }

    /**
     * Asserts a monetary field equals an expected value regardless of scale.
     *
     * @param expected The expected value as a string.
     * @param actual   The actual value.
     */
    private static void assertMoney(String expected, BigDecimal actual) {
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    /**
     * Asserts a whole breakdown line: rate, pre-tax, tax and tax-included amounts.
     *
     * @param line The line under test.
     * @param rate The expected rate.
     * @param ht   The expected pre-tax amount.
     * @param vat  The expected tax amount.
     * @param ttc  The expected tax-included amount.
     */
    private static void assertLine(BasketEvaluation.VatLine line, String rate,
                                   String ht, String vat, String ttc) {
        assertMoney(rate, line.vatRate);
        assertMoney(ht, line.amountExcludingTax);
        assertMoney(vat, line.vatAmount);
        assertMoney(ttc, line.amountIncludingTax);
    }

    /**
     * A {@code null} evaluation yields an empty, immutable breakdown.
     */
    @Test
    void computeNullEvaluationReturnsEmptyList() {
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(null);
        Assertions.assertTrue(lines.isEmpty());
    }

    /**
     * An evaluation with no offers and no advantages produces no lines: the buckets stay
     * empty and reconciliation short-circuits.
     */
    @Test
    void computeWithNothingPricedReturnsEmpty() {
        BasketEvaluation evaluation = eval(List.of(), List.of(), amt("0", "0", "0"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertTrue(lines.isEmpty());
    }

    /**
     * A single offer with valued items at one rate is grouped and reconciled to a matching
     * total, leaving no residue.
     */
    @Test
    void offerWithValuedItemsSingleRateReconciledToTotal() {
        OfferApplication offer = offerWithItems(List.of(item(amt("20", "24", "0.20"))));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), amt("20", "24", "0.20"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "20.00", "4.00", "24.00");
    }

    /**
     * Within an offer, an item without an amount is skipped and two items sharing a rate
     * accumulate into the same bucket; a {@code null} total leaves the sums untouched.
     */
    @Test
    void offerValuedItemsSkipNullAmountAndAccumulateSameRate() {
        OfferApplication offer = offerWithItems(List.of(
                item(amt("10", "12", "0.20")), item(null), item(amt("5", "6", "0.20"))));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "15.00", "3.00", "18.00");
    }

    /**
     * An offer that prices no line falls back to its own amount, which carries a single real
     * rate.
     */
    @Test
    void offerWithoutValuedItemsUsesOwnAmount() {
        OfferApplication offer = offerWithAmount(amt("8", "8.44", "0.055"));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), amt("8", "8.44", "0.055"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.0550", "8.00", "0.44", "8.44");
    }

    /**
     * An offer with an empty valued list and no own amount contributes nothing, so the
     * breakdown is empty.
     */
    @Test
    void offerEmptyAndUnpricedAddsNothing() {
        OfferApplication offer = offerEmptyAndUnpriced();
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertTrue(lines.isEmpty());
    }

    /**
     * A discount targeting an offer of a single rate is deducted from that rate; a
     * null-amount item on the target is ignored when reading its rate mix.
     */
    @Test
    void discountSingleRateFromTargetMix() {
        OfferApplication offer = offerWithItems(List.of(
                item(amt("100", "120", "0.20")), item(null)));
        DiscountApplication disc = discount(amt("10", "12", "0.20"), offer);
        BasketEvaluation evaluation =
                eval(List.of(offer), List.of(disc), amt("90", "108", "0.20"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "90.00", "18.00", "108.00");
    }

    /**
     * A discount with no target offer is charged to the rate it declares on its own amount.
     */
    @Test
    void discountWithoutTargetUsesDiscountRate() {
        OfferApplication offer = offerWithItems(List.of(item(amt("50", "60", "0.20"))));
        DiscountApplication disc = discount(amt("5", "6", "0.20"), null);
        BasketEvaluation evaluation =
                eval(List.of(offer), List.of(disc), amt("45", "54", "0.20"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "45.00", "9.00", "54.00");
    }

    /**
     * A discount targeting an unpriced offer (empty items, no own amount) falls back to the
     * rate it declares, exercising the target-present but empty-mix path.
     */
    @Test
    void discountTargetEmptyAndUnpricedFallsBackToDiscountRate() {
        OfferApplication offer = offerWithItems(List.of(item(amt("50", "60", "0.20"))));
        DiscountApplication disc = discount(amt("5", "6", "0.20"), offerEmptyAndUnpriced());
        BasketEvaluation evaluation =
                eval(List.of(offer), List.of(disc), amt("45", "54", "0.20"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "45.00", "9.00", "54.00");
    }

    /**
     * A discount whose target prices only through its own amount reads a single rate from
     * that amount and is deducted at it, with the discount's own figures.
     */
    @Test
    void discountTargetWithOnlyAmountSingleRate() {
        OfferApplication offer = offerWithItems(List.of(item(amt("50", "60", "0.20"))));
        OfferApplication target = offerWithAmount(amt("10", "10.60", "0.06"));
        DiscountApplication disc = discount(amt("10", "10.60", "0.06"), target);
        BasketEvaluation evaluation = eval(List.of(offer), List.of(disc), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(2, lines.size());
        assertLine(lines.get(0), "0.0600", "-10.00", "-0.60", "-10.60");
        assertLine(lines.get(1), "0.2000", "50.00", "10.00", "60.00");
    }

    /**
     * A discount whose amount is absent leaves every bucket untouched.
     */
    @Test
    void discountWithNullAmountIsIgnored() {
        OfferApplication offer = offerWithItems(List.of(item(amt("50", "60", "0.20"))));
        DiscountApplication disc = discount(null, offer);
        BasketEvaluation evaluation = eval(List.of(offer), List.of(disc), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "50.00", "10.00", "60.00");
    }

    /**
     * An advantage that is not a discount is ignored entirely.
     */
    @Test
    void nonDiscountAdvantageIsIgnored() {
        OfferApplication offer = offerWithItems(List.of(item(amt("50", "60", "0.20"))));
        AdvantageApplication advantage = Mockito.mock(AdvantageApplication.class);
        BasketEvaluation evaluation =
                eval(List.of(offer), List.of(advantage), amt("50", "60", "0.20"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "50.00", "10.00", "60.00");
    }

    /**
     * A blended discount, targeting an offer holding three rates including zero, is spread by
     * tax-included weight; the rounding residue lands on the highest rate. The zero-rate
     * entry, coming first, never exceeds the running highest, exercising both arms of the
     * highest-rate comparison.
     */
    @Test
    void blendedDiscountSpreadWithResidueOverThreeRates() {
        OfferApplication offer = offerWithItems(List.of(
                item(amt("100", "100", "0.00")),
                item(amt("90", "100", "0.055")),
                item(amt("80", "100", "0.20"))));
        DiscountApplication disc = discount(amt("8", "10", "0.20"), offer);
        BasketEvaluation evaluation = eval(List.of(offer), List.of(disc), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(3, lines.size());
        assertLine(lines.get(0), "0.0000", "96.67", "0.00", "96.67");
        assertLine(lines.get(1), "0.0550", "86.84", "9.83", "96.67");
        assertLine(lines.get(2), "0.2000", "77.22", "19.44", "96.66");
    }

    /**
     * A blended discount whose target rates carry offsetting tax-included weights summing to
     * zero cannot be spread and is dropped, leaving the offer untouched.
     */
    @Test
    void blendedDiscountWithZeroTotalWeightIsIgnored() {
        OfferApplication contributor = offerWithItems(List.of(item(amt("100", "120", "0.20"))));
        OfferApplication target = offerWithItems(List.of(
                item(amt("40", "50", "0.055")), item(amt("-40", "-50", "0.20"))));
        DiscountApplication disc = discount(amt("5", "6", "0.20"), target);
        BasketEvaluation evaluation = eval(List.of(contributor), List.of(disc), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "100.00", "20.00", "120.00");
    }

    /**
     * When the lines fall a cent short of a matching total, the reconciliation carries the
     * difference to the highest rate.
     */
    @Test
    void reconcileResidueLandsOnHighestRate() {
        OfferApplication offer = offerWithItems(List.of(
                item(amt("10", "10.55", "0.055")), item(amt("10", "12", "0.20"))));
        BasketEvaluation evaluation =
                eval(List.of(offer), List.of(), amt("20.50", "23.00", "0.12"));
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(2, lines.size());
        assertLine(lines.get(0), "0.0550", "10.00", "0.55", "10.55");
        assertLine(lines.get(1), "0.2000", "10.50", "1.95", "12.45");
    }

    /**
     * A total whose tax-included amount is absent disables reconciliation, so the lines carry
     * the raw bucket sums.
     */
    @Test
    void reconcileWithNullIncludingTaxTotalSkipsAdjustment() {
        OfferApplication offer = offerWithItems(List.of(item(amt("10", "12", "0.20"))));
        AmountEvaluation total = raw(new BigDecimal("99.00"), null, new BigDecimal("0.20"));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), total);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "10.00", "2.00", "12.00");
    }

    /**
     * A total whose pre-tax amount is absent likewise disables reconciliation.
     */
    @Test
    void reconcileWithNullExcludingTaxTotalSkipsAdjustment() {
        OfferApplication offer = offerWithItems(List.of(item(amt("10", "12", "0.20"))));
        AmountEvaluation total = raw(null, new BigDecimal("99.00"), new BigDecimal("0.20"));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), total);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "10.00", "2.00", "12.00");
    }

    /**
     * Items whose amount is missing either the pre-tax or the tax-included figure are skipped
     * by the accumulator, while a complete item is retained.
     */
    @Test
    void addSkipsNullExcludingAndNullIncludingAmounts() {
        OfferApplication offer = offerWithItems(List.of(
                item(raw(null, new BigDecimal("12"), new BigDecimal("0.20"))),
                item(raw(new BigDecimal("10"), null, new BigDecimal("0.20"))),
                item(amt("5", "6", "0.20"))));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.2000", "5.00", "1.00", "6.00");
    }

    /**
     * An item whose amount carries no rate is bucketed at a rate of zero.
     */
    @Test
    void keyTreatsNullRateAsZero() {
        OfferApplication offer = offerWithItems(List.of(
                item(raw(new BigDecimal("7"), new BigDecimal("8"), null))));
        BasketEvaluation evaluation = eval(List.of(offer), List.of(), null);
        List<BasketEvaluation.VatLine> lines = VatBreakdown.compute(evaluation);
        Assertions.assertEquals(1, lines.size());
        assertLine(lines.get(0), "0.0000", "7.00", "1.00", "8.00");
    }
}
