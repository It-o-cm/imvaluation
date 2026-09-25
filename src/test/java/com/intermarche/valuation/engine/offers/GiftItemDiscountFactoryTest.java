package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AmountEvaluation;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.DiscountApplication;
import com.intermarche.valuation.engine.MinimumAmountCondition;
import com.intermarche.valuation.engine.MinimumQuantityCondition;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.Trigger;
import com.intermarche.valuation.engine.TriggerCondition;
import com.intermarche.valuation.engine.ValuationEngine;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GiftItemDiscountFactory} using the real database.
 * <p>
 * Factory-level tests exercise the required trigger and the contributor-condition cross rule;
 * applier-level tests drive the selection among the trigger's contributors — cheapest / most
 * expensive, the EAN tie-break, and the non-integer exclusion — feeding valued contributor
 * applications directly; end-to-end tests cover the trigger interaction and the inherited
 * arbitration.
 */
@QuarkusTest
@TestTransaction
public class GiftItemDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    GiftItemDiscountFactory factory;

    /**
     * The engine, injected for the end-to-end scenarios.
     */
    @Inject
    ValuationEngine engine;

    /**
     * The store the offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by every test.
     */
    private void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_GI", 48.8566, 2.352214);
    }

    /**
     * Seeds a UNIT product at a zero VAT rate with a DEFAULT and a BASE_FOR_DISCOUNT price.
     *
     * @param ean   the product EAN.
     * @param price the price, tax excluded and included alike.
     */
    private void seedProduct(String ean, String price) {
        Product product = DomainUtils.createAndPersistProduct(ean, ean, ProductType.UNIT);
        BigDecimal amount = new BigDecimal(price);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT, amount, amount, BigDecimal.ZERO);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT, amount, amount, BigDecimal.ZERO);
    }

    /**
     * Builds an evaluation on an empty basket attached to the seeded store.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_GI";
        return new BasketEvaluation(basket);
    }

    /**
     * Builds a basket on the seeded store from the given lines.
     *
     * @param items the basket lines.
     * @return the basket.
     */
    private Basket basket(Basket.Item... items) {
        Basket basket = new Basket();
        basket.storeCode = "STORE_GI";
        basket.items = new ArrayList<>(List.of(items));
        return basket;
    }

    /**
     * Builds a trigger requiring at least one unit across the given EANs.
     *
     * @param eans the triggering EANs.
     * @return the trigger.
     */
    private Trigger minQuantityTrigger(String... eans) {
        List<TriggerCondition> conditions = new ArrayList<>();
        conditions.add(new MinimumQuantityCondition(Set.of(eans), BigDecimal.ONE));
        return new Trigger(conditions);
    }

    /**
     * Builds a gift applier over the given selection and triggering EANs, with no basket.
     *
     * @param selection the selection mode.
     * @param eans      the triggering EANs.
     * @return the applier under test.
     */
    private GiftItemDiscountFactory.GiftItemDiscountApplier applier(
            GiftItemDiscountFactory.Selection selection, String... eans) {
        return new GiftItemDiscountFactory.GiftItemDiscountApplier(
                "GI1", selection, minQuantityTrigger(eans), Set.of(eans), null, null);
    }

    /**
     * Builds a valued result item.
     *
     * @param lineId the source line id.
     * @param ean    the product EAN.
     * @param qty    the quantity.
     * @param ttc    the tax-included amount for the whole quantity.
     * @return the valued item.
     */
    private static BasketEvaluation.Item item(String lineId, String ean, double qty, String ttc) {
        BasketEvaluation.Item vi = new BasketEvaluation.Item();
        vi.lineId = lineId;
        vi.produceEan = ean;
        vi.quantity = BigDecimal.valueOf(qty);
        BigDecimal amount = new BigDecimal(ttc);
        vi.amount = new AmountEvaluation(amount, amount, BigDecimal.ZERO);
        return vi;
    }

    /**
     * Sums the tax-included amount of every discount advantage.
     *
     * @param evaluation the evaluation.
     * @return the total discount, tax included.
     */
    private BigDecimal totalDiscount(BasketEvaluation evaluation) {
        BigDecimal total = BigDecimal.ZERO;
        for (AdvantageApplication advantage : evaluation.getAdvantages()) {
            if (advantage instanceof DiscountApplication discount && discount.getDiscountAmount() != null) {
                total = total.add(discount.getDiscountAmount().amountIncludingTax);
            }
        }
        return total;
    }

    // --------------------------------------------------
    // Factory logic
    // --------------------------------------------------

    /**
     * Tests the successful creation with a selection and a contributor-bearing trigger.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("GI_OK", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"FROM\"], \"threshold\": 1 } ] } }");
        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());
        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof GiftItemDiscountFactory.GiftItemDiscountApplier);
    }

    /**
     * Tests that a specification without a trigger is rejected at creation.
     */
    @Test
    void testBuildAppliers_NoTrigger_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("GI_NT", store, "GIFT_ITEM_DISCOUNT", "{ \"selection\": \"CHEAPEST\" }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a trigger with only a COUPON_CODE condition (no contributor) is rejected.
     */
    @Test
    void testBuildAppliers_CouponOnly_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("GI_CP", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"COUPON_CODE\", \"code\": \"WELCOME\" } ] } }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a trigger with only a MINIMUM_AMOUNT scope TICKET condition is rejected.
     */
    @Test
    void testBuildAppliers_TicketAmountOnly_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("GI_TA", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"TICKET\", \"threshold\": 10 } ] } }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a MINIMUM_AMOUNT scope ITEMS condition counts as a contributor condition.
     */
    @Test
    void testBuildAppliers_ItemsAmount_Accepted() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("GI_IA", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"MOST_EXPENSIVE\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_AMOUNT\", \"scope\": \"ITEMS\", \"eans\": [\"FROM\"], \"threshold\": 5 } ] } }");
        assertEquals(1, factory.buildAppliers(newEvaluation()).size());
    }

    /**
     * Tests that a missing selection is rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingSelection_Rejected() {
        setUpDatabase();
        DomainUtils.createAndPersistOffer("GI_NS", store, "GIFT_ITEM_DISCOUNT",
                "{ \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"FROM\"], \"threshold\": 1 } ] } }");
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a null basket is rejected.
     */
    @Test
    void testBuildAppliers_NoBasket_Throws() {
        setUpDatabase();
        BasketEvaluation eval = new BasketEvaluation(null) {
        };
        assertThrows(IllegalStateException.class, () -> factory.buildAppliers(eval));
    }

    // --------------------------------------------------
    // Applier logic
    // --------------------------------------------------

    /**
     * Tests the cheapest selection: between a 5.00 cheese and an 8.00 wine, the 5.00 cheese is
     * offered.
     */
    @Test
    void testApply_Cheapest() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 1.0, "5.00")));
        evaluation.getOffers().add(new ValuedStub(item("L2", "VIN", 1.0, "8.00")));
        Collection<AdvantageApplication> discounts = applier(
                GiftItemDiscountFactory.Selection.CHEAPEST, "FROM", "VIN").apply(evaluation);
        assertEquals(1, discounts.size());
        GiftItemDiscountFactory.GiftItemDiscountApplication app =
                (GiftItemDiscountFactory.GiftItemDiscountApplication) discounts.iterator().next();
        assertEquals("FROM", app.getEan());
        assertEquals(new BigDecimal("5.00"), app.getDiscountAmount().amountIncludingTax);
        assertEquals("Gift Item: GI1 (FROM)", app.getType());
    }

    /**
     * Tests the most-expensive selection: the 8.00 wine is offered.
     */
    @Test
    void testApply_MostExpensive() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 1.0, "5.00")));
        evaluation.getOffers().add(new ValuedStub(item("L2", "VIN", 1.0, "8.00")));
        GiftItemDiscountFactory.GiftItemDiscountApplication app =
                (GiftItemDiscountFactory.GiftItemDiscountApplication) applier(
                        GiftItemDiscountFactory.Selection.MOST_EXPENSIVE, "FROM", "VIN").apply(evaluation).iterator().next();
        assertEquals("VIN", app.getEan());
        assertEquals(new BigDecimal("8.00"), app.getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests the EAN tie-break: two equally priced contributors are separated by ascending EAN.
     */
    @Test
    void testApply_PriceTie_EanAscending() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "BBB", 1.0, "5.00")));
        evaluation.getOffers().add(new ValuedStub(item("L2", "AAA", 1.0, "5.00")));
        GiftItemDiscountFactory.GiftItemDiscountApplication app =
                (GiftItemDiscountFactory.GiftItemDiscountApplication) applier(
                        GiftItemDiscountFactory.Selection.CHEAPEST, "AAA", "BBB").apply(evaluation).iterator().next();
        assertEquals("AAA", app.getEan());
    }

    /**
     * Tests that a non-integer-quantity line counts toward the trigger but is excluded from the
     * selection, so the integer wine is offered even under the cheapest selection.
     */
    @Test
    void testApply_NonIntegerExcluded() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        // The 0.5 kg cheese is cheaper per unit but not offerable.
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 0.5, "2.00")));
        evaluation.getOffers().add(new ValuedStub(item("L2", "VIN", 1.0, "8.00")));
        Collection<AdvantageApplication> discounts = applier(
                GiftItemDiscountFactory.Selection.CHEAPEST, "FROM", "VIN").apply(evaluation);
        assertEquals(1, discounts.size());
        assertEquals("VIN", ((GiftItemDiscountFactory.GiftItemDiscountApplication)
                discounts.iterator().next()).getEan());
    }

    /**
     * Tests that when all contributors are weighed (non-integer), nothing is offered.
     */
    @Test
    void testApply_AllNonInteger_NoApplication() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 0.5, "2.00")));
        assertTrue(applier(GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").apply(evaluation).isEmpty());
    }

    /**
     * Tests the per-unit price of a multi-unit line: a 2-unit line at 10.00 offers one unit at
     * 5.00.
     */
    @Test
    void testApply_MultiUnitLine_OffersOneUnit() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 2.0, "10.00")));
        GiftItemDiscountFactory.GiftItemDiscountApplication app =
                (GiftItemDiscountFactory.GiftItemDiscountApplication) applier(
                        GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").apply(evaluation).iterator().next();
        assertEquals(new BigDecimal("5.00"), app.getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests that only one application is produced per ticket even with several candidates.
     */
    @Test
    void testApply_OneApplicationPerTicket() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 1.0, "5.00")));
        evaluation.getOffers().add(new ValuedStub(item("L2", "VIN", 1.0, "8.00")));
        assertEquals(1, applier(GiftItemDiscountFactory.Selection.CHEAPEST, "FROM", "VIN").apply(evaluation).size());
    }

    /**
     * Tests that an unsatisfied trigger produces nothing.
     */
    @Test
    void testApply_TriggerNotSatisfied_NoApplication() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        // Trigger requires FROM; the basket has none valued.
        assertTrue(applier(GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").apply(evaluation).isEmpty());
    }

    /**
     * Tests that a satisfied trigger but no offerable EAN (all valued items outside the
     * triggering list) yields nothing.
     */
    @Test
    void testApply_NoContributorEan_NoApplication() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        // The valued item is OTHER, which satisfies nothing; trigger on FROM stays unsatisfied.
        evaluation.getOffers().add(new ValuedStub(item("L1", "OTHER", 1.0, "5.00")));
        assertTrue(applier(GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applicability, score, getters
    // --------------------------------------------------

    /**
     * Tests that a gift is never applicable to an offer applier (it does not switch prices).
     */
    @Test
    void testIsApplicable_AlwaysFalse() {
        setUpDatabase();
        assertFalse(applier(GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").isApplicable(new NonProductApplier()));
    }

    /**
     * Tests the sandbox score: the cheapest offerable unit at the reference price.
     */
    @Test
    void testEfficiencyScore_Cheapest() {
        setUpDatabase();
        seedProduct("FROM", "5.00");
        seedProduct("VIN", "8.00");
        Basket b = basket(DomainUtils.createItem("FROM", 1.0), DomainUtils.createItem("VIN", 1.0));
        GiftItemDiscountFactory.GiftItemDiscountApplier applier =
                new GiftItemDiscountFactory.GiftItemDiscountApplier(
                        "GIS", GiftItemDiscountFactory.Selection.CHEAPEST, minQuantityTrigger("FROM", "VIN"),
                        Set.of("FROM", "VIN"), b, store);
        assertEquals(0, new BigDecimal("5.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests the sandbox score for the most expensive selection.
     */
    @Test
    void testEfficiencyScore_MostExpensive() {
        setUpDatabase();
        seedProduct("FROM", "5.00");
        seedProduct("VIN", "8.00");
        Basket b = basket(DomainUtils.createItem("FROM", 1.0), DomainUtils.createItem("VIN", 1.0));
        GiftItemDiscountFactory.GiftItemDiscountApplier applier =
                new GiftItemDiscountFactory.GiftItemDiscountApplier(
                        "GIM", GiftItemDiscountFactory.Selection.MOST_EXPENSIVE, minQuantityTrigger("FROM", "VIN"),
                        Set.of("FROM", "VIN"), b, store);
        assertEquals(0, new BigDecimal("8.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    /**
     * Tests that the sandbox score is zero without a basket or store.
     */
    @Test
    void testEfficiencyScore_NoBasketOrStore_Zero() {
        setUpDatabase();
        assertEquals(0.0, applier(GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").getEfficiencyScore());
    }

    /**
     * Tests the application-moment round-trip on the produced application.
     */
    @Test
    void testApplication_MomentRoundTrip() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ValuedStub(item("L1", "FROM", 1.0, "5.00")));
        GiftItemDiscountFactory.GiftItemDiscountApplication app =
                (GiftItemDiscountFactory.GiftItemDiscountApplication) applier(
                        GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").apply(evaluation).iterator().next();
        assertEquals("AT_TOTAL", app.getApplicationMoment());
        app.setApplicationMoment("AT_TRIGGER");
        assertEquals("AT_TRIGGER", app.getApplicationMoment());
    }

    /**
     * Tests that the produced application exposes the elected contributor application.
     */
    @Test
    void testApplication_OfferApplicationTarget() {
        setUpDatabase();
        BasketEvaluation evaluation = newEvaluation();
        ValuedStub stub = new ValuedStub(item("L1", "FROM", 1.0, "5.00"));
        evaluation.getOffers().add(stub);
        GiftItemDiscountFactory.GiftItemDiscountApplication app =
                (GiftItemDiscountFactory.GiftItemDiscountApplication) applier(
                        GiftItemDiscountFactory.Selection.CHEAPEST, "FROM").apply(evaluation).iterator().next();
        org.junit.jupiter.api.Assertions.assertSame(stub, app.getOfferApplication());
    }

    /**
     * Tests the sandbox skip branches: a non-contributor line, a weighed line, a line with no
     * product, and a line with no price are all skipped; only the priced integer contributor
     * sets the score.
     */
    @Test
    void testEfficiencyScore_SandboxSkipBranches() {
        setUpDatabase();
        seedProduct("GI_PRICED", "5.00");
        DomainUtils.createAndPersistProduct("GI_NOPRICE", "GI_NOPRICE", ProductType.UNIT);
        Basket b = basket(
                DomainUtils.createItem("GI_OTHER", 1.0),
                DomainUtils.createItem("GI_NONINT", 0.5),
                DomainUtils.createItem("GI_NOPROD", 1.0),
                DomainUtils.createItem("GI_NOPRICE", 1.0),
                DomainUtils.createItem("GI_PRICED", 1.0));
        GiftItemDiscountFactory.GiftItemDiscountApplier applier =
                new GiftItemDiscountFactory.GiftItemDiscountApplier(
                        "GISX", GiftItemDiscountFactory.Selection.CHEAPEST,
                        minQuantityTrigger("GI_NONINT", "GI_NOPROD", "GI_NOPRICE", "GI_PRICED"),
                        Set.of("GI_NONINT", "GI_NOPROD", "GI_NOPRICE", "GI_PRICED"), b, store);
        assertEquals(0, new BigDecimal("5.00").compareTo(BigDecimal.valueOf(applier.getEfficiencyScore())));
    }

    // --------------------------------------------------
    // End-to-end (trigger and arbitration)
    // --------------------------------------------------

    /**
     * Tests the end-to-end nominal case: a cheese-and-wine basket offers the cheaper cheese.
     */
    @Test
    void testEndToEnd_Nominal() {
        setUpDatabase();
        seedProduct("GI_FROM", "5.00");
        seedProduct("GI_VIN", "8.00");
        DomainUtils.createAndPersistOffer("GI_E2E", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"GI_FROM\"], \"threshold\": 1 }, "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"GI_VIN\"], \"threshold\": 1 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(
                DomainUtils.createItem("GI_FROM", 1.0), DomainUtils.createItem("GI_VIN", 1.0)));
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the end-to-end case with an unsatisfied trigger: nothing is offered.
     */
    @Test
    void testEndToEnd_TriggerNotSatisfied() {
        setUpDatabase();
        seedProduct("GI_FROM", "5.00");
        DomainUtils.createAndPersistOffer("GI_E2E", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"GI_FROM\"], \"threshold\": 3 } ] } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("GI_FROM", 1.0)));
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited open-basket dimension: an AT_TOTAL gift does not fall on an open
     * basket.
     */
    @Test
    void testArbitration_OpenBasket_NotApplied() {
        setUpDatabase();
        seedProduct("GI_OB", "5.00");
        DomainUtils.createAndPersistOffer("GI_OBX", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"GI_OB\"], \"threshold\": 1 } ] } }");
        Basket b = basket(DomainUtils.createItem("GI_OB", 1.0));
        b.closed = false;
        BasketEvaluation evaluation = engine.evaluate(b);
        assertTrue(evaluation.getAdvantages().isEmpty());
    }

    /**
     * Tests the inherited cumul dimension: a non-cumulable gift bars a second non-cumulable
     * advantage.
     */
    @Test
    void testArbitration_NonCumulable() {
        setUpDatabase();
        seedProduct("GI_NC", "5.00");
        DomainUtils.createAndPersistOffer("GI_NC1", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", \"arbitration\": { \"priority\": 10, \"cumulable\": false }, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"GI_NC\"], \"threshold\": 1 } ] } }");
        DomainUtils.createAndPersistOffer("GI_NC2", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 50, "
                        + "\"arbitration\": { \"priority\": 20, \"cumulable\": false } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("GI_NC", 1.0)));
        // The gift (higher priority) applies (5.00) and bars the second non-cumulable advantage.
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    /**
     * Tests the inherited consumption dimension: a gift that consumes its contributor withdraws
     * it from a following advantage measuring the same line.
     */
    @Test
    void testArbitration_ConsumesContributors() {
        setUpDatabase();
        seedProduct("GI_CC", "5.00");
        DomainUtils.createAndPersistOffer("GI_CC1", store, "GIFT_ITEM_DISCOUNT",
                "{ \"selection\": \"CHEAPEST\", "
                        + "\"arbitration\": { \"priority\": 10, \"consumesContributors\": true }, "
                        + "\"trigger\": { \"conditions\": [ "
                        + "{ \"kind\": \"MINIMUM_QUANTITY\", \"eans\": [\"GI_CC\"], \"threshold\": 1 } ] } }");
        DomainUtils.createAndPersistOffer("GI_CC2", store, "TICKET_DISCOUNT",
                "{ \"discountType\": \"PERCENTAGE\", \"value\": 10, "
                        + "\"arbitration\": { \"priority\": 20 } }");
        BasketEvaluation evaluation = engine.evaluate(basket(DomainUtils.createItem("GI_CC", 1.0)));
        // The gift consumes the only line, so the ticket discount sees an empty assiette: only
        // the gift's 5.00 remains.
        assertEquals(0, new BigDecimal("5.00").compareTo(totalDiscount(evaluation)));
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * A product-aware application exposing a fixed list of valued items.
     */
    public static class ValuedStub implements ProductAwareOfferApplication {

        /**
         * The valued items this application carries.
         */
        private final List<BasketEvaluation.Item> items;

        /**
         * Builds the stub over the given valued items.
         *
         * @param items the valued items.
         */
        public ValuedStub(BasketEvaluation.Item... items) {
            this.items = List.of(items);
        }

        /**
         * Returns the aggregate application amount.
         *
         * @return the summed amount of the valued items.
         */
        @Override
        public AmountEvaluation getAmount() {
            AmountEvaluation total = new AmountEvaluation();
            for (BasketEvaluation.Item item : items) {
                total = total.add(item.amount);
            }
            return total;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the valued result items.
         *
         * @return the valued items.
         */
        @Override
        public List<BasketEvaluation.Item> getValuedItems() {
            return items;
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "ValuedStub";
        }

        /**
         * Returns the summed amount attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the summed amount when an item matches, null otherwise.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            AmountEvaluation total = null;
            for (BasketEvaluation.Item item : items) {
                if (product != null && product.ean != null && product.ean.equals(item.produceEan)) {
                    total = (total == null) ? item.amount : total.add(item.amount);
                }
            }
            return total;
        }

        /**
         * Returns the summed quantity attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the summed quantity when an item matches, zero otherwise.
         */
        @Override
        public BigDecimal getProductQuantity(Product product) {
            BigDecimal total = BigDecimal.ZERO;
            for (BasketEvaluation.Item item : items) {
                if (product != null && product.ean != null && product.ean.equals(item.produceEan)) {
                    total = total.add(item.quantity);
                }
            }
            return total;
        }
    }

    /**
     * A plain offer applier stub used to drive the always-false applicability.
     */
    public static class NonProductApplier extends OfferApplier {

        /**
         * Produces no application.
         *
         * @param basketEvaluation the evaluation context.
         * @return an empty collection.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            return List.of();
        }

        /**
         * Returns no configuration.
         *
         * @return always null.
         */
        @Override
        public com.intermarche.valuation.domain.Offer getConfiguration() {
            return null;
        }
    }
}
