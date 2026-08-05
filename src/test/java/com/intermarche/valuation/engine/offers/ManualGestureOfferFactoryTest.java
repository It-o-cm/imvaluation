package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Plain unit tests for {@link ManualGestureOfferFactory}.
 * <p>
 * The factory, its applier and its application are pure computation once the base price is
 * resolved. Every basket line here carries an inline price override
 * ({@code pricePerUnitExclTax}, {@code pricePerUnitInclTax}, {@code vatRate}), so
 * {@link Basket.Item#getPrice} returns a transient {@link com.intermarche.valuation.domain.Price}
 * without touching Panache; the {@link Store} is therefore never dereferenced and is passed as
 * {@code null}. The {@link BasketEvaluation} collaborator is mocked with Mockito so that
 * {@code remainingQuantity} and {@code pickMatching} are controlled directly.
 */
public class ManualGestureOfferFactoryTest {

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

    // --------------------------------------------------
    // buildAppliers
    // --------------------------------------------------

    /**
     * Verifies that a basket with a {@code null} item list yields no appliers.
     * <p>
     * Covers the {@code basket.items == null} arm of {@code buildAppliers}.
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
     * Verifies that exactly one applier is built for the gesture-bearing line and none for the
     * plain line.
     * <p>
     * Covers both arms of {@code item.hasManualGesture()} and the non-null {@code items} path.
     */
    @Test
    void testBuildAppliersOneApplierPerGestureLine() {
        Basket.Item gestureLine = new Basket.Item();
        gestureLine.produceEan = "1111111111111";
        gestureLine.quantity = 1.0;
        gestureLine.manualDiscountAmount = new BigDecimal("2.00");
        Basket.Item plainLine = new Basket.Item();
        plainLine.produceEan = "2222222222222";
        plainLine.quantity = 1.0;
        Basket basket = new Basket();
        basket.items = List.of(gestureLine, plainLine);
        BasketEvaluation evaluation = Mockito.mock(BasketEvaluation.class);
        Mockito.when(evaluation.getBasket()).thenReturn(basket);
        Mockito.when(evaluation.getStore()).thenReturn(null);
        ManualGestureOfferFactory factory = new ManualGestureOfferFactory();
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof ManualGestureOfferFactory.ManualGestureOfferApplier);
    }

    // --------------------------------------------------
    // ManualGestureOfferApplier
    // --------------------------------------------------

    /**
     * Verifies that the efficiency score is the fixed ultra-priority value.
     */
    @Test
    void testComputeEfficiencyScoreIsMaxValue() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        ManualGestureOfferFactory.ManualGestureOfferApplier applier =
                new ManualGestureOfferFactory.ManualGestureOfferApplier(null, item);
        assertEquals(Double.MAX_VALUE, applier.computeEfficiencyScore(new Basket()));
    }

    /**
     * Verifies that {@code apply} returns nothing when the line is already fully consumed.
     * <p>
     * Covers the {@code remaining <= 0.0} arm.
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
     * Verifies that {@code apply} produces one application per consumed slice.
     * <p>
     * Covers the {@code remaining > 0.0} arm and the body of the slice loop.
     */
    @Test
    void testApplyProducesOneApplicationPerSlice() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        item.manualDiscountAmount = new BigDecimal("2.00");
        Basket.Item slice = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        slice.manualDiscountAmount = new BigDecimal("2.00");
        BasketEvaluation evaluation = Mockito.mock(BasketEvaluation.class);
        Mockito.when(evaluation.remainingQuantity("1111111111111")).thenReturn(1.0);
        Mockito.when(evaluation.pickMatching(eq(1.0), any(Basket.Item.class))).thenReturn(List.of(slice));
        ManualGestureOfferFactory.ManualGestureOfferApplier applier =
                new ManualGestureOfferFactory.ManualGestureOfferApplier(null, item);
        Collection<OfferApplication> applications = applier.apply(evaluation);
        assertEquals(1, applications.size());
        assertTrue(applications.iterator().next() instanceof ManualGestureOfferFactory.ManualGestureApplication);
    }

    /**
     * Verifies that {@code apply} yields no application when the matching entry is already gone.
     * <p>
     * Covers the zero-iteration path of the slice loop while {@code remaining > 0.0}.
     */
    @Test
    void testApplyProducesNoApplicationWhenNoMatchingSlice() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        BasketEvaluation evaluation = Mockito.mock(BasketEvaluation.class);
        Mockito.when(evaluation.remainingQuantity("1111111111111")).thenReturn(1.0);
        Mockito.when(evaluation.pickMatching(any(), any(Basket.Item.class))).thenReturn(new ArrayList<>());
        ManualGestureOfferFactory.ManualGestureOfferApplier applier =
                new ManualGestureOfferFactory.ManualGestureOfferApplier(null, item);
        Collection<OfferApplication> applications = applier.apply(evaluation);
        assertTrue(applications.isEmpty());
    }

    // --------------------------------------------------
    // ManualGestureApplication.getAmount
    // --------------------------------------------------

    /**
     * Verifies the amount when a forced price replaces the catalog price.
     * <p>
     * Covers the {@code manualForcedPrice != null} branch and the non-negative {@code unitTtc}.
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
     * Covers the {@code manualDiscountAmount != null} branch.
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
     * Covers the {@code manualDiscountPercent != null} branch.
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
     * Covers the final {@code else} branch of the gesture resolution.
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
     * Covers the {@code unitTtc.compareTo(ZERO) < 0} arm.
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
     * Covers the {@code item.quantity == null} arm of the quantity ternary.
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

    // --------------------------------------------------
    // ManualGestureApplication.getItems
    // --------------------------------------------------

    /**
     * Verifies that {@code getItems} returns the single covered slice.
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

    // --------------------------------------------------
    // ManualGestureApplication.getValuedItems
    // --------------------------------------------------

    /**
     * Verifies that a slice with {@code null} source lines yields a single valued item.
     * <p>
     * Covers the {@code sources == null} operand of the guard.
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
     * Verifies that a slice with an empty source list yields a single valued item.
     * <p>
     * Covers the {@code sources.isEmpty()} operand of the guard.
     */
    @Test
    void testGetValuedItemsEmptySources() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        item.lineId = "L1";
        item.sourceLines = new ArrayList<>();
        Basket.Item gesture = new Basket.Item();
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        List<BasketEvaluation.Item> valued = app.getValuedItems();
        assertEquals(1, valued.size());
        assertEquals("L1", valued.get(0).lineId);
        assertEquals(new BigDecimal("12.00"), valued.get(0).amount.amountIncludingTax);
    }

    /**
     * Verifies that the gesture amount is split across source lines, the residue landing on the
     * last line.
     * <p>
     * Covers the non-empty guard arm and both arms of the {@code i == last} test.
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

    // --------------------------------------------------
    // ManualGestureApplication.getType
    // --------------------------------------------------

    /**
     * Verifies the type string for a forced-price gesture.
     * <p>
     * Covers the {@code manualForcedPrice != null} branch of {@code getType}.
     */
    @Test
    void testGetTypeForcedPrice() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualForcedPrice = new BigDecimal("5.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        String type = app.getType();
        assertTrue(type.contains("forced price 5.00"));
        assertTrue(type.contains("1111111111111"));
    }

    /**
     * Verifies the type string for a fixed-amount gesture.
     * <p>
     * Covers the {@code manualDiscountAmount != null} branch of {@code getType}.
     */
    @Test
    void testGetTypeDiscountAmount() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualDiscountAmount = new BigDecimal("2.00");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        String type = app.getType();
        assertTrue(type.contains("amount -2.00"));
    }

    /**
     * Verifies the type string for a percentage gesture.
     * <p>
     * Covers the final {@code else} branch of {@code getType}.
     */
    @Test
    void testGetTypeDiscountPercent() {
        Basket.Item item = pricedItem("1111111111111", 1.0, new BigDecimal("12.00"));
        Basket.Item gesture = new Basket.Item();
        gesture.manualDiscountPercent = new BigDecimal("25");
        ManualGestureOfferFactory.ManualGestureApplication app =
                new ManualGestureOfferFactory.ManualGestureApplication(null, item, gesture);
        String type = app.getType();
        assertTrue(type.contains("percent -25%"));
    }
}
