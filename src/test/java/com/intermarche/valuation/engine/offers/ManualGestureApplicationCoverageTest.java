package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @QuarkusTest} coverage tests for {@link ManualGestureOfferFactory} and its inner
 * {@link ManualGestureOfferFactory.ManualGestureApplication}.
 * <p>
 * quarkus-jacoco only attributes lines executed inside a {@code @QuarkusTest}; the sibling
 * plain unit test {@code ManualGestureOfferFactoryTest} exercises the same logic but is not
 * instrumented. This class re-runs the still-red lines under {@code @QuarkusTest} so the
 * coverage is attributed: the {@code null} item list guard of {@code buildAppliers}, the
 * empty-remaining guard of {@code apply}, the gesture resolution arms of {@code getAmount}
 * (forced price, fixed amount, percentage, catalog fallback, zero floor, null quantity),
 * {@code getItems}, and both source-line arms of {@code getValuedItems}.
 * <p>
 * Every basket line carries an inline price override, so {@link Basket.Item#getPrice} returns
 * a transient price without touching Panache; the {@link org.mockito.Mockito}-mocked
 * {@link BasketEvaluation} controls {@code remainingQuantity}. The {@code store} is therefore
 * never dereferenced and is passed as {@code null}.
 */
@QuarkusTest
@TestTransaction
public class ManualGestureApplicationCoverageTest {

    /**
     * Builds a basket line carrying an inline price override so its price resolves in memory.
     *
     * @param ean      The product EAN.
     * @param quantity The line quantity, nullable.
     * @param inclTax  The unit price including tax.
     * @return A fully priced basket line.
     */
    private Basket.Item pricedItem(String ean, Double quantity, BigDecimal inclTax) {
        Basket.Item item = new Basket.Item();
        item.produceEan = ean;
        item.quantity = quantity;
        item.pricePerUnitExclTax = new BigDecimal("10.00");
        item.pricePerUnitInclTax = inclTax;
        item.vatRate = new BigDecimal("0.2");
        return item;
    }

    /**
     * Verifies that a basket with a {@code null} item list yields no appliers.
     * <p>
     * Covers the {@code basket.items == null} return arm of {@code buildAppliers}.
     */
    @Test
    void testBuildAppliersReturnsEmptyWhenItemsNull() {
        Basket basket = new Basket();
        basket.items = null;
        BasketEvaluation evaluation = Mockito.mock(BasketEvaluation.class);
        Mockito.when(evaluation.getBasket()).thenReturn(basket);
        Mockito.when(evaluation.getStore()).thenReturn(null);
        ManualGestureOfferFactory factory = new ManualGestureOfferFactory();
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.isEmpty());
    }

    /**
     * Verifies that {@code apply} returns nothing when the line is already fully consumed.
     * <p>
     * Covers the {@code remaining <= 0.0} return arm.
     */
    @Test
    void testApplyReturnsEmptyWhenNothingRemains() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        BasketEvaluation evaluation = Mockito.mock(BasketEvaluation.class);
        Mockito.when(evaluation.remainingQuantity("1111111111111")).thenReturn(0.0);
        ManualGestureOfferFactory.ManualGestureOfferApplier applier =
                new ManualGestureOfferFactory.ManualGestureOfferApplier(null, item);
        Collection<OfferApplication> applications = applier.apply(evaluation);
        assertTrue(applications.isEmpty());
    }

    /**
     * Verifies the amount when a forced price replaces the catalog price.
     * <p>
     * Covers the {@code manualForcedPrice != null} branch of {@code getAmount}.
     */
    @Test
    void testGetAmountForcedPrice() {
        Basket.Item item = pricedItem("1111111111111", 2.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualForcedPrice = new BigDecimal("5.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("10.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("8.33"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Verifies the amount when a fixed amount is deducted from the tax-included price.
     * <p>
     * Covers the {@code manualDiscountAmount != null} branch of {@code getAmount}.
     */
    @Test
    void testGetAmountDiscountAmount() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualDiscountAmount = new BigDecimal("2.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("10.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("8.33"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Verifies the amount when a percentage reduction is applied to the tax-included price.
     * <p>
     * Covers the {@code manualDiscountPercent != null} branch of {@code getAmount}.
     */
    @Test
    void testGetAmountDiscountPercent() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualDiscountPercent = new BigDecimal("25");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("9.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("7.50"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Verifies the amount falls back to the catalog price when no gesture value is set.
     * <p>
     * Covers the final {@code else} branch (line 160) of the gesture resolution.
     */
    @Test
    void testGetAmountNoGestureValueUsesCatalogPrice() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("12.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("10.00"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("0.2000"), amount.vatRate);
    }

    /**
     * Verifies that an over-large deduction floors the unit price at zero.
     * <p>
     * Covers the {@code unitTtc.compareTo(ZERO) < 0} arm (line 163).
     */
    @Test
    void testGetAmountFloorsNegativeUnitPriceAtZero() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualDiscountAmount = new BigDecimal("20.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("0.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.00"), amount.amountExcludingTax);
    }

    /**
     * Verifies that a line with no quantity prices as zero.
     * <p>
     * Covers the {@code item.quantity == null} arm (line 168) of the quantity ternary.
     */
    @Test
    void testGetAmountNullQuantityPricesAsZero() {
        Basket.Item item = pricedItem("1111111111111", null, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualForcedPrice = new BigDecimal("5.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("0.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.00"), amount.amountExcludingTax);
    }

    /**
     * Verifies that {@code getItems} returns the single covered slice.
     * <p>
     * Covers line 182.
     */
    @Test
    void testGetItemsReturnsSingleSlice() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualForcedPrice = new BigDecimal("5.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        Collection<Basket.Item> items = app.getItems();
        assertEquals(1, items.size());
        assertSame(item, items.iterator().next());
    }

    /**
     * Verifies that a slice with {@code null} source lines yields a single valued item.
     * <p>
     * Covers the {@code sources == null} guard arm and lines 200-202 of {@code getValuedItems}.
     */
    @Test
    void testGetValuedItemsNullSources() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        item.lineId = "L1";
        item.sourceLines = null;
        Basket.Item gesture = new Basket.Item();
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        List<BasketEvaluation.Item> valued = app.getValuedItems();
        assertEquals(1, valued.size());
        assertEquals("L1", valued.get(0).lineId);
        assertEquals(new BigDecimal("12.00"), valued.get(0).amount.amountIncludingTax);
        assertEquals(new BigDecimal("10.00"), valued.get(0).amount.amountExcludingTax);
    }

    /**
     * Verifies that the gesture amount is split across source lines, the residue landing on the
     * last line.
     * <p>
     * Covers the non-empty guard arm and both arms of the {@code i == last} test (lines 204-231).
     */
    @Test
    void testGetValuedItemsSplitsAcrossSourceLines() {
        Basket.Item item = pricedItem("1111111111111", 3.0, new BigDecimal("12.00"));
        item.sourceLines = new ArrayList<>();
        item.sourceLines.add(new Basket.Item.SourceLine("L1", 1.0));
        item.sourceLines.add(new Basket.Item.SourceLine("L2", 2.0));
        Basket.Item gesture = new Basket.Item();
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        List<BasketEvaluation.Item> valued = app.getValuedItems();
        assertEquals(2, valued.size());
        assertEquals("L1", valued.get(0).lineId);
        assertEquals(1.0, valued.get(0).quantity);
        assertEquals(new BigDecimal("12.00"), valued.get(0).amount.amountIncludingTax);
        assertEquals(new BigDecimal("10.00"), valued.get(0).amount.amountExcludingTax);
        assertEquals("L2", valued.get(1).lineId);
        assertEquals(2.0, valued.get(1).quantity);
        assertEquals(new BigDecimal("24.00"), valued.get(1).amount.amountIncludingTax);
        assertEquals(new BigDecimal("20.00"), valued.get(1).amount.amountExcludingTax);
    }
}
