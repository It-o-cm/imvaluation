package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GenericLineOfferFactory}: the valuation of basket lines that
 * carry no EAN but a full price triplet.
 * <p>
 * Generic lines never touch the database — they carry their own price — so the baskets are
 * built in memory with a null store code, which the {@link BasketEvaluation} constructor
 * accepts without resolving a store.
 */
@QuarkusTest
@TestTransaction
public class GenericLineOfferFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    GenericLineOfferFactory factory;

    /**
     * Builds a generic (no-EAN) basket line with a complete price triplet.
     *
     * @param lineId   the line identifier.
     * @param quantity the quantity, possibly null.
     * @param ht       the unit price excluding tax.
     * @param ttc      the unit price including tax.
     * @param rate     the VAT rate.
     * @return the configured generic line.
     */
    private Basket.Item genericItem(String lineId, Double quantity, String ht, String ttc, String rate) {
        Basket.Item item = new Basket.Item();
        item.lineId = lineId;
        item.produceEan = null;
        item.quantity = quantity == null ? null : BigDecimal.valueOf(quantity);
        item.pricePerUnitExclTax = new BigDecimal(ht);
        item.pricePerUnitInclTax = new BigDecimal(ttc);
        item.vatRate = new BigDecimal(rate);
        return item;
    }

    /**
     * Builds an evaluation over the given items, fed from the basket, with no store.
     *
     * @param items the basket lines.
     * @return the evaluation, already fed from the basket.
     */
    private BasketEvaluation evaluationOf(List<Basket.Item> items) {
        Basket basket = new Basket();
        basket.storeCode = null;
        basket.items = items;
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        return evaluation;
    }

    // --------------------------------------------------
    // Factory logic
    // --------------------------------------------------

    /**
     * Tests that a null evaluation is rejected (first arm of the guard).
     */
    @Test
    void testBuildAppliers_NullEvaluationRejected() {
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(null));
    }

    /**
     * Tests that an evaluation with no basket is rejected (second arm of the guard).
     */
    @Test
    void testBuildAppliers_NullBasketRejected() {
        BasketEvaluation evaluation = new BasketEvaluation(null);
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that a basket with no items yields no applier.
     */
    @Test
    void testBuildAppliers_NullItemsYieldsNoApplier() {
        BasketEvaluation evaluation = evaluationOf(null);
        assertTrue(factory.buildAppliers(evaluation).isEmpty());
    }

    /**
     * Tests that lines carrying an EAN are skipped and only the generic lines produce an
     * applier.
     */
    @Test
    void testBuildAppliers_SkipsEanLines() {
        Basket.Item eanLine = new Basket.Item();
        eanLine.lineId = "L1";
        eanLine.produceEan = "1000000000001";
        eanLine.quantity = BigDecimal.valueOf(1.0);
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(
                eanLine, genericItem("L2", 1.0, "5.00", "6.00", "0.20"))));
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
    }

    /**
     * Tests that a generic line missing its tax-included unit price is rejected.
     */
    @Test
    void testBuildAppliers_MissingInclTaxRejected() {
        Basket.Item item = genericItem("L1", 1.0, "5.00", "6.00", "0.20");
        item.pricePerUnitInclTax = null;
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(item)));
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that a generic line missing its tax-excluded unit price is rejected.
     */
    @Test
    void testBuildAppliers_MissingExclTaxRejected() {
        Basket.Item item = genericItem("L1", 1.0, "5.00", "6.00", "0.20");
        item.pricePerUnitExclTax = null;
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(item)));
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that a generic line missing its VAT rate is rejected.
     */
    @Test
    void testBuildAppliers_MissingVatRateRejected() {
        Basket.Item item = genericItem("L1", 1.0, "5.00", "6.00", "0.20");
        item.vatRate = null;
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(item)));
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that a valid generic line yields exactly one applier.
     */
    @Test
    void testBuildAppliers_ValidGenericLine() {
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(
                genericItem("L1", 2.0, "5.00", "6.00", "0.20"))));
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof GenericLineOfferFactory.GenericLineOfferApplier);
    }

    // --------------------------------------------------
    // Applier logic
    // --------------------------------------------------

    /**
     * Tests that the applier consumes its line and values it at unit price times quantity.
     */
    @Test
    void testApply_ValuesLineAtPriceTimesQuantity() {
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(
                genericItem("L1", 2.0, "5.00", "6.00", "0.20"))));
        OfferApplier applier = factory.buildAppliers(evaluation).iterator().next();
        Collection<OfferApplication> applications = applier.apply(evaluation);
        assertEquals(1, applications.size());
        AmountEvaluation amount = applications.iterator().next().getAmount();
        assertEquals(new BigDecimal("10.00"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("12.00"), amount.amountIncludingTax);
    }

    /**
     * Tests that a line already consumed yields no application on a second pass.
     */
    @Test
    void testApply_AlreadyConsumedYieldsNothing() {
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(
                genericItem("L1", 2.0, "5.00", "6.00", "0.20"))));
        OfferApplier applier = factory.buildAppliers(evaluation).iterator().next();
        applier.apply(evaluation);
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests the neutral efficiency score of a generic line applier.
     */
    @Test
    void testComputeEfficiencyScore_IsZero() {
        BasketEvaluation evaluation = evaluationOf(new ArrayList<>(List.of(
                genericItem("L1", 1.0, "5.00", "6.00", "0.20"))));
        GenericLineOfferFactory.GenericLineOfferApplier applier =
                (GenericLineOfferFactory.GenericLineOfferApplier) factory.buildAppliers(evaluation).iterator().next();
        assertEquals(0.0, applier.computeEfficiencyScore(evaluation.getBasket()));
    }

    // --------------------------------------------------
    // Application getters
    // --------------------------------------------------

    /**
     * Tests that a null quantity values the line at zero.
     */
    @Test
    void testApplication_GetAmount_NullQuantity() {
        Basket.Item slice = genericItem("L1", null, "5.00", "6.00", "0.20");
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("0.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.00"), amount.amountExcludingTax);
    }

    /**
     * Tests that the application exposes its single backing slice.
     */
    @Test
    void testApplication_GetItems_ReturnsSlice() {
        Basket.Item slice = genericItem("L1", 1.0, "5.00", "6.00", "0.20");
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        Collection<Basket.Item> items = app.getItems();
        assertEquals(1, items.size());
        assertEquals(slice, items.iterator().next());
    }

    /**
     * Tests that, with an empty source-line list, the valued items report the slice as a
     * single line.
     */
    @Test
    void testApplication_GetValuedItems_EmptySources() {
        Basket.Item slice = genericItem("L1", 2.0, "5.00", "6.00", "0.20");
        slice.sourceLines = new ArrayList<>();
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        List<BasketEvaluation.Item> valued = app.getValuedItems();
        assertEquals(1, valued.size());
        assertEquals("L1", valued.get(0).lineId);
        assertEquals(new BigDecimal("12.00"), valued.get(0).amount.amountIncludingTax);
    }

    /**
     * Tests that a null source-line list is treated like an empty one: the slice is reported
     * as a single line.
     */
    @Test
    void testApplication_GetValuedItems_NullSources() {
        Basket.Item slice = genericItem("L1", 2.0, "5.00", "6.00", "0.20");
        slice.sourceLines = null;
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        List<BasketEvaluation.Item> valued = app.getValuedItems();
        assertEquals(1, valued.size());
        assertEquals("L1", valued.get(0).lineId);
        assertEquals(new BigDecimal("12.00"), valued.get(0).amount.amountIncludingTax);
    }

    /**
     * Tests that valued items are split over the source lines pro-rata, the residual landing
     * on the last one so their sum matches the application amount to the cent.
     */
    @Test
    void testApplication_GetValuedItems_SplitsOverSources() {
        Basket.Item slice = genericItem("SLICE", 3.0, "0.90", "1.00", "0.1111");
        slice.produceEan = null;
        slice.sourceLines = new ArrayList<>(List.of(
                new Basket.Item.SourceLine("L1", BigDecimal.valueOf(1.0)),
                new Basket.Item.SourceLine("L2", BigDecimal.valueOf(2.0))));
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        List<BasketEvaluation.Item> valued = app.getValuedItems();
        assertEquals(2, valued.size());
        assertEquals("L1", valued.get(0).lineId);
        assertEquals("L2", valued.get(1).lineId);
        BigDecimal sumTtc = valued.stream()
                .map(v -> v.amount.amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(app.getAmount().amountIncludingTax, sumTtc);
        assertEquals(new BigDecimal("1.00"), valued.get(0).amount.amountIncludingTax);
        assertEquals(new BigDecimal("2.00"), valued.get(1).amount.amountIncludingTax);
    }

    /**
     * Tests the display type of the application.
     */
    @Test
    void testApplication_GetType() {
        Basket.Item slice = genericItem("L7", 3.5, "5.00", "6.00", "0.20");
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        String type = app.getType();
        assertTrue(type.contains("Generic"));
        assertTrue(type.contains("L7"));
        assertTrue(type.contains("3.5"));
    }

    /**
     * Tests that a generic line covers no cataloged product: amount null, quantity zero.
     */
    @Test
    void testApplication_ProductAwareIsNeutral() {
        Basket.Item slice = genericItem("L1", 1.0, "5.00", "6.00", "0.20");
        GenericLineOfferFactory.GenericLineApplication app =
                new GenericLineOfferFactory.GenericLineApplication(slice);
        Product product = new Product();
        product.ean = "1000000000001";
        assertNull(app.getProductAmount(product));
        assertEquals(0, app.getProductQuantity(product).compareTo(BigDecimal.valueOf(0.0)));
    }
}
