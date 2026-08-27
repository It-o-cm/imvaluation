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
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.ProductAwareOfferApplication;
import com.intermarche.valuation.engine.ProductAwareOfferApplier;
import com.intermarche.valuation.engine.TierTable;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TieredDiscountFactory} using the real database.
 * <p>
 * The factory-level tests exercise the schema and cross-field validations through real
 * offers; the applier-level tests feed hand-built product-aware applications into the
 * evaluation, mirroring the approach of the free-delivery threshold tests.
 */
@QuarkusTest
@TestTransaction
public class TieredDiscountFactoryTest {

    /**
     * The factory under test, injected as a CDI bean.
     */
    @Inject
    TieredDiscountFactory factory;

    /**
     * The store the offers are attached to.
     */
    private Store store;

    /**
     * Seeds the store required by every test; called manually at the start of each test
     * because {@code @TestTransaction} rolls back between tests.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);
    }

    /**
     * Builds an evaluation on a basket attached to the seeded store.
     *
     * @return the evaluation under test.
     */
    private BasketEvaluation newEvaluation() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        return new BasketEvaluation(basket);
    }

    /**
     * Builds a HIGHEST_REACHED applier on one EAN with two percentage tiers
     * (50 → 5%, 100 → 10%).
     *
     * @param ean the targeted EAN.
     * @return the applier under test.
     */
    private TieredDiscountFactory.TieredDiscountApplier highestPercentApplier(String ean) {
        Product product = new Product();
        product.ean = ean;
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("50"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("5"), null, 1)),
                new TierTable.Tier<>(new BigDecimal("100"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        return new TieredDiscountFactory.TieredDiscountApplier(
                "TIER1", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
    }

    // --------------------------------------------------
    // Factory logic
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier from a valid specification.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_01", store, "TIERED_DISCOUNT", jsonSpec);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(newEvaluation());

        assertEquals(1, appliers.size());
        assertTrue(appliers.iterator().next() instanceof TieredDiscountFactory.TieredDiscountApplier);
    }

    /**
     * Tests that a specification without tiers nor step is rejected by the schema.
     */
    @Test
    void testBuildAppliers_MissingTiersRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\" }";
        DomainUtils.createAndPersistOffer("TIERED_02", store, "TIERED_DISCOUNT", jsonSpec);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that the ITEMS scope without target EANs is rejected by the cross-field
     * validation.
     */
    @Test
    void testBuildAppliers_ItemsScopeWithoutTargetsRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_03", store, "TIERED_DISCOUNT", jsonSpec);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a NEW_PRICE award in PROGRESSIVE mode is rejected.
     */
    @Test
    void testBuildAppliers_NewPriceInProgressiveRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"NEW_PRICE\", \"value\": 4.75 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_04", store, "TIERED_DISCOUNT", jsonSpec);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    // --------------------------------------------------
    // Applier logic — HIGHEST_REACHED
    // --------------------------------------------------

    /**
     * Tests the highest-reached percentage: a 120€ base reaches the 100€ tier and gets
     * 10% on the whole assiette.
     */
    @Test
    void testApply_HighestReached_Percentage() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 4.0, 120.00));

        Collection<AdvantageApplication> discounts = highestPercentApplier("1000000000001").apply(evaluation);

        assertEquals(1, discounts.size());
        TieredDiscountFactory.TieredDiscountApplication discount =
                (TieredDiscountFactory.TieredDiscountApplication) discounts.iterator().next();
        assertEquals(new BigDecimal("12.00"), discount.getDiscountAmount().amountIncludingTax);
        assertEquals(new BigDecimal("10.00"), discount.getDiscountAmount().amountExcludingTax);
        assertTrue(discount.getType().contains("tier 100"));
    }

    /**
     * Tests that a base below the first threshold produces no discount.
     */
    @Test
    void testApply_HighestReached_BelowFirstTier() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 30.00));

        assertTrue(highestPercentApplier("1000000000001").apply(evaluation).isEmpty());
    }

    /**
     * Tests that products absent from the target list contribute nothing.
     */
    @Test
    void testApply_IgnoresUntargetedProducts() {
        setUpDatabase();
        Product other = new Product();
        other.ean = "9999999999999";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(other, 4.0, 120.00));

        assertTrue(highestPercentApplier("1000000000001").apply(evaluation).isEmpty());
    }

    /**
     * Tests the quantity trigger with a per-item amount: 5 units reach the 3-unit tier
     * and each unit earns 0.50€.
     */
    @Test
    void testApply_HighestReached_QuantityAmountPerItem() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("3"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.AMOUNT_PER_ITEM, new BigDecimal("0.50"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER2", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 5.0, 10.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("2.50"), amount.amountIncludingTax);
    }

    /**
     * Tests that the discount is capped at the assiette: a flat award larger than the
     * base cannot drive the total negative.
     */
    @Test
    void testApply_CappedAtBase() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("50"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.AMOUNT, new BigDecimal("500"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER3", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 4.0, 120.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("120.00"), amount.amountIncludingTax);
    }

    // --------------------------------------------------
    // Applier logic — PROGRESSIVE and PER_MULTIPLE
    // --------------------------------------------------

    /**
     * Tests the progressive brackets: 120€ against floors 0 (5%), 50 (10%), 100 (15%)
     * earn 2.50 + 5.00 + 3.00 = 10.50€.
     */
    @Test
    void testApply_Progressive_Percentage() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("0"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("5"), null, 1)),
                new TierTable.Tier<>(new BigDecimal("50"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1)),
                new TierTable.Tier<>(new BigDecimal("100"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("15"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER4", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.PROGRESSIVE, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 4.0, 120.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("10.50"), amount.amountIncludingTax);
    }

    /**
     * Tests the per-multiple step: 120€ with a 50€ step and a 1€ award earn 2€.
     */
    @Test
    void testApply_PerMultiple_Amount() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TieredDiscountFactory.Award award = new TieredDiscountFactory.Award(
                TieredDiscountFactory.AwardType.AMOUNT, new BigDecimal("1.00"), null, 1);
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER5", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.PER_MULTIPLE, PriceUsage.BASE_FOR_DISCOUNT,
                null, new BigDecimal("50"), award, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 4.0, 120.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        assertEquals(1, discounts.size());
        TieredDiscountFactory.TieredDiscountApplication discount =
                (TieredDiscountFactory.TieredDiscountApplication) discounts.iterator().next();
        assertEquals(new BigDecimal("2.00"), discount.getDiscountAmount().amountIncludingTax);
        assertTrue(discount.getType().contains("x2"));
    }

    // --------------------------------------------------
    // Applier logic — TICKET scope and distribution
    // --------------------------------------------------

    /**
     * Tests the TICKET scope with two merchandise applications: the discount is split
     * pro-rata and the shares sum exactly to the total.
     */
    @Test
    void testApply_TicketScope_SplitsProRata() {
        setUpDatabase();
        Product p1 = new Product();
        p1.ean = "1000000000001";
        Product p2 = new Product();
        p2.ean = "1000000000002";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("100"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER6", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(p1, 1.0, 50.00));
        evaluation.getOffers().add(new ProductApplication(p2, 1.0, 70.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        assertEquals(2, discounts.size());
        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("12.00"), sum);
    }

    /**
     * Tests that delivery-like applications (not product-aware) are excluded from the
     * TICKET assiette.
     */
    @Test
    void testApply_TicketScope_ExcludesServices() {
        setUpDatabase();
        Product p1 = new Product();
        p1.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("100"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER7", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(p1, 1.0, 60.00));
        evaluation.getOffers().add(new ServiceApplication(60.00));

        assertTrue(applier.apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applier logic — unit price based awards (database)
    // --------------------------------------------------

    /**
     * Tests the ITEM_FREE award: with two targeted products priced 2€ and 5€, offering
     * one cheapest item discounts 2€.
     */
    @Test
    void testApply_ItemFree_Cheapest() {
        setUpDatabase();
        Product cheap = DomainUtils.createAndPersistProduct("2000000000001", "Cheap", ProductType.UNIT);
        Product expensive = DomainUtils.createAndPersistProduct("2000000000002", "Expensive", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(cheap, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("1.67"), new BigDecimal("2.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(expensive, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("4.17"), new BigDecimal("5.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("5"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.CHEAPEST, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER8", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(cheap, expensive));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(cheap, 1.0, 2.00));
        evaluation.getOffers().add(new ProductApplication(expensive, 1.0, 5.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("2.00"), sum);
    }

    /**
     * Tests the NEW_PRICE award: two units priced 6€ brought to 4.75€ discount 2.50€.
     */
    @Test
    void testApply_NewPrice() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000003", "Bottle", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("2"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.NEW_PRICE, new BigDecimal("4.75"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER9", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 2.0, 12.00));

        Collection<AdvantageApplication> discounts = applier.apply(evaluation);

        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("2.50"), amount.amountIncludingTax);
    }

    // --------------------------------------------------
    // Factory logic — cross-field rules rejected one by one
    // --------------------------------------------------

    /**
     * Tests that the TICKET scope combined with the QUANTITY trigger is rejected.
     */
    @Test
    void testBuildAppliers_TicketQuantityRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"TICKET\", "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 3.0, \"award\": { \"type\": \"PERCENTAGE\", \"value\": 5.0 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_TQ", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an AMOUNT_PER_ITEM award under the TICKET scope is rejected.
     */
    @Test
    void testBuildAppliers_TicketAmountPerItemRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"TICKET\", "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.50 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_TAPI", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a NEW_PRICE award in PER_MULTIPLE mode is rejected.
     */
    @Test
    void testBuildAppliers_PerMultipleNewPriceRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PER_MULTIPLE\", "
                + "\"every\": { \"step\": 2.0, \"award\": { \"type\": \"NEW_PRICE\", \"value\": 4.75 } } }";
        DomainUtils.createAndPersistOffer("TIERED_PMNP", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that an ITEM_FREE award without a selection is rejected.
     */
    @Test
    void testBuildAppliers_ItemFreeWithoutSelectionRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 5.0, \"award\": { \"type\": \"ITEM_FREE\", \"quantity\": 1 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_IFNS", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a PERCENTAGE award without a value is rejected.
     */
    @Test
    void testBuildAppliers_PercentageWithoutValueRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"tiers\": [ { \"threshold\": 50.0, \"award\": { \"type\": \"PERCENTAGE\" } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_PNV", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    // --------------------------------------------------
    // Applier logic — boundaries and per-multiple variants
    // --------------------------------------------------

    /**
     * Tests the exact boundary: a base equal to a threshold reaches that tier. A 50€ base
     * lands exactly on the 50€ tier and earns 5%.
     */
    @Test
    void testApply_HighestReached_ExactThreshold() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 2.0, 50.00));
        Collection<AdvantageApplication> discounts = highestPercentApplier("1000000000001").apply(evaluation);
        assertEquals(1, discounts.size());
        TieredDiscountFactory.TieredDiscountApplication discount =
                (TieredDiscountFactory.TieredDiscountApplication) discounts.iterator().next();
        assertEquals(new BigDecimal("2.50"), discount.getDiscountAmount().amountIncludingTax);
        assertTrue(discount.getType().contains("tier 50"));
    }

    /**
     * Tests the per-multiple percentage on the AMOUNT trigger: 120€ with a 50€ step covers
     * 100€ and a 10% award discounts 10€.
     */
    @Test
    void testApply_PerMultiple_Percentage_AmountTrigger() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TieredDiscountFactory.Award award = new TieredDiscountFactory.Award(
                TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1);
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PMPA", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.PER_MULTIPLE, PriceUsage.BASE_FOR_DISCOUNT,
                null, new BigDecimal("50"), award, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 4.0, 120.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("10.00"), amount.amountIncludingTax);
    }

    /**
     * Tests the per-multiple percentage on the QUANTITY trigger: 5 units at a 24€ average
     * with a 2-unit step cover 4 units (96€) and a 10% award discounts 9.60€.
     */
    @Test
    void testApply_PerMultiple_Percentage_QuantityTrigger() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TieredDiscountFactory.Award award = new TieredDiscountFactory.Award(
                TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1);
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PMPQ", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.PER_MULTIPLE, PriceUsage.BASE_FOR_DISCOUNT,
                null, new BigDecimal("2"), award, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 5.0, 120.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("9.60"), amount.amountIncludingTax);
    }

    /**
     * Tests the per-multiple ITEM_FREE award: 5 units priced 3€ with a 2-unit step offer
     * one item per step, so two cheapest units for 6€.
     */
    @Test
    void testApply_PerMultiple_ItemFree() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000010", "Cans", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("2.50"), new BigDecimal("3.00"), new BigDecimal("0.20"));
        TieredDiscountFactory.Award award = new TieredDiscountFactory.Award(
                TieredDiscountFactory.AwardType.ITEM_FREE, null, TieredDiscountFactory.Selection.CHEAPEST, 1);
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PMIF", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.PER_MULTIPLE, PriceUsage.BASE_FOR_DISCOUNT,
                null, new BigDecimal("2"), award, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 5.0, 15.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("6.00"), sum);
    }

    /**
     * Tests the progressive AMOUNT_PER_ITEM award on the QUANTITY trigger: floors 0 (0.10)
     * and 3 (0.20) over 5 units grant 3×0.10 + 2×0.20 = 0.70€.
     */
    @Test
    void testApply_Progressive_AmountPerItem_Quantity() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("0"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.AMOUNT_PER_ITEM, new BigDecimal("0.10"), null, 1)),
                new TierTable.Tier<>(new BigDecimal("3"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.AMOUNT_PER_ITEM, new BigDecimal("0.20"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PAPI", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.PROGRESSIVE, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 5.0, 20.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("0.70"), amount.amountIncludingTax);
    }

    // --------------------------------------------------
    // Applier logic — unit price selection and price usage
    // --------------------------------------------------

    /**
     * Tests the ITEM_FREE MOST_EXPENSIVE selection: with products priced 2€ and 5€, offering
     * one item discounts the 5€ one.
     */
    @Test
    void testApply_ItemFree_MostExpensive() {
        setUpDatabase();
        Product cheap = DomainUtils.createAndPersistProduct("2000000000011", "Cheap", ProductType.UNIT);
        Product expensive = DomainUtils.createAndPersistProduct("2000000000012", "Expensive", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(cheap, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("1.67"), new BigDecimal("2.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(expensive, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("4.17"), new BigDecimal("5.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("5"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.MOST_EXPENSIVE, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_IFME", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(cheap, expensive));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(cheap, 1.0, 2.00));
        evaluation.getOffers().add(new ProductApplication(expensive, 1.0, 5.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("5.00"), sum);
    }

    /**
     * Tests that an ITEM_FREE award silently ignores a targeted product carrying no price
     * row: only the priced product can be offered.
     */
    @Test
    void testApply_ItemFree_ProductWithoutPriceIgnored() {
        setUpDatabase();
        Product priced = DomainUtils.createAndPersistProduct("2000000000013", "Priced", ProductType.UNIT);
        Product noPrice = DomainUtils.createAndPersistProduct("2000000000014", "NoPrice", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(priced, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("4.17"), new BigDecimal("5.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("5"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.CHEAPEST, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_IFNP", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(priced, noPrice));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(noPrice, 1.0, 2.00));
        evaluation.getOffers().add(new ProductApplication(priced, 1.0, 5.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("5.00"), sum);
    }

    /**
     * Tests that the DEFAULT price usage reads the DEFAULT price row rather than the
     * BASE_FOR_DISCOUNT one: an ITEM_FREE award values the offered unit at its DEFAULT price.
     */
    @Test
    void testApply_PriceUsageDefault() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000015", "Dual", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("3.33"), new BigDecimal("4.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.CHEAPEST, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PUD", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.DEFAULT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 4.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("4.00"), amount.amountIncludingTax);
    }

    /**
     * Tests that a NEW_PRICE award above or equal to the current unit price yields no
     * discount and no application at all.
     */
    @Test
    void testApply_NewPrice_AboveUnitNoApplication() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000016", "Bottle", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("2"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.NEW_PRICE, new BigDecimal("7.00"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_NPAB", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 2.0, 12.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    // --------------------------------------------------
    // Applier logic — distribution and idempotence
    // --------------------------------------------------

    /**
     * Tests the pro-rata split across three targeted applications of unequal amounts: the
     * shares sum exactly to the total and the rounding residual lands on the last one.
     */
    @Test
    void testApply_TicketScope_SplitsThreeUnequal() {
        setUpDatabase();
        Product p1 = new Product();
        p1.ean = "1000000000001";
        Product p2 = new Product();
        p2.ean = "1000000000002";
        Product p3 = new Product();
        p3.ean = "1000000000003";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("100"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_3W", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(p1, 1.0, 33.33));
        evaluation.getOffers().add(new ProductApplication(p2, 1.0, 33.33));
        evaluation.getOffers().add(new ProductApplication(p3, 1.0, 33.34));
        List<AdvantageApplication> discounts = new java.util.ArrayList<>(applier.apply(evaluation));
        assertEquals(3, discounts.size());
        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("10.00"), sum);
        BigDecimal last = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.get(2)).getDiscountAmount().amountIncludingTax;
        assertEquals(new BigDecimal("3.34"), last);
    }

    /**
     * Tests that two successive apply() calls on the same evaluation are idempotent: the
     * engine runs each applier twice (score estimation then real application) and both must
     * yield the same discount.
     */
    @Test
    void testApply_Idempotent_TwoSuccessiveApplies() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 4.0, 120.00));
        TieredDiscountFactory.TieredDiscountApplier applier = highestPercentApplier("1000000000001");
        Collection<AdvantageApplication> first = applier.apply(evaluation);
        Collection<AdvantageApplication> second = applier.apply(evaluation);
        assertEquals(first.size(), second.size());
        BigDecimal firstAmount = ((TieredDiscountFactory.TieredDiscountApplication)
                first.iterator().next()).getDiscountAmount().amountIncludingTax;
        BigDecimal secondAmount = ((TieredDiscountFactory.TieredDiscountApplication)
                second.iterator().next()).getDiscountAmount().amountIncludingTax;
        assertEquals(firstAmount, secondAmount);
        assertEquals(new BigDecimal("12.00"), secondAmount);
    }

    // --------------------------------------------------
    // Factory logic — parsing, price usage and remaining rejections
    // --------------------------------------------------

    /**
     * Tests the offer type discriminator and that a non-blank schema is exposed.
     */
    @Test
    void testGetOfferTypeAndSchema() {
        assertEquals("TIERED_DISCOUNT", factory.getOfferType());
        assertTrue(factory.getSchema().contains("Tiered Discount Offer Specification"));
    }

    /**
     * Tests that an explicit priceUsage field is parsed through the factory.
     */
    @Test
    void testBuildAppliers_PriceUsageDefaultParsed() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"HIGHEST_REACHED\", \"priceUsage\": \"DEFAULT\", "
                + "\"tiers\": [ { \"threshold\": 1.0, \"award\": { \"type\": \"ITEM_FREE\", "
                + "\"selection\": \"CHEAPEST\", \"quantity\": 2 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_PU", store, "TIERED_DISCOUNT", jsonSpec);
        assertEquals(1, factory.buildAppliers(newEvaluation()).size());
    }

    /**
     * Tests that a PER_MULTIPLE specification builds an applier through the factory.
     */
    @Test
    void testBuildAppliers_PerMultipleValid() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PER_MULTIPLE\", "
                + "\"every\": { \"step\": 2.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 1.0 } } }";
        DomainUtils.createAndPersistOffer("TIERED_PM", store, "TIERED_DISCOUNT", jsonSpec);
        assertEquals(1, factory.buildAppliers(newEvaluation()).size());
    }

    /**
     * Tests that an AMOUNT_PER_ITEM award in PROGRESSIVE mode on the AMOUNT trigger is
     * rejected.
     */
    @Test
    void testBuildAppliers_ProgressiveAmountPerItemAmountTriggerRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"PROGRESSIVE\", "
                + "\"tiers\": [ { \"threshold\": 0.0, \"award\": { \"type\": \"AMOUNT_PER_ITEM\", \"value\": 0.5 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_PAPIAT", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a PER_MULTIPLE specification carrying 'tiers' instead of 'every' is rejected.
     */
    @Test
    void testBuildAppliers_PerMultipleWithoutEveryRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"QUANTITY\", \"mode\": \"PER_MULTIPLE\", "
                + "\"tiers\": [ { \"threshold\": 2.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 1.0 } } ] }";
        DomainUtils.createAndPersistOffer("TIERED_PMNE", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    /**
     * Tests that a HIGHEST_REACHED specification carrying 'every' instead of 'tiers' is
     * rejected.
     */
    @Test
    void testBuildAppliers_HighestWithoutTiersRejected() {
        setUpDatabase();
        String jsonSpec = "{ \"scope\": \"ITEMS\", \"targetEans\": [\"1000000000001\"], "
                + "\"trigger\": \"AMOUNT\", \"mode\": \"HIGHEST_REACHED\", "
                + "\"every\": { \"step\": 2.0, \"award\": { \"type\": \"AMOUNT\", \"value\": 1.0 } } }";
        DomainUtils.createAndPersistOffer("TIERED_HNT", store, "TIERED_DISCOUNT", jsonSpec);
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(newEvaluation()));
    }

    // --------------------------------------------------
    // Applier logic — isApplicable
    // --------------------------------------------------

    /**
     * Tests that a non product-aware offer applier is never concerned by a tiered discount.
     */
    @Test
    void testIsApplicable_NonProductAwareIsFalse() {
        setUpDatabase();
        assertFalse(highestPercentApplier("1000000000001").isApplicable(new NonProductApplier()));
    }

    /**
     * Tests that in TICKET scope every product-aware applier is concerned.
     */
    @Test
    void testIsApplicable_TicketScopeIsTrue() {
        setUpDatabase();
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("50"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_TA", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        assertTrue(applier.isApplicable(new FakeProductApplier("1000000000001")));
    }

    /**
     * Tests that in ITEMS scope only an applier covering a targeted product is concerned.
     */
    @Test
    void testIsApplicable_ItemsScopeMatchesTargetOnly() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        assertTrue(highestPercentApplier("1000000000001").isApplicable(
                new FakeProductApplier("1000000000001")));
        assertFalse(highestPercentApplier("1000000000001").isApplicable(
                new FakeProductApplier("9999999999999")));
    }

    // --------------------------------------------------
    // Applier logic — empty resolutions and unit-price edge cases
    // --------------------------------------------------

    /**
     * Tests that a progressive resolution whose base is below the first floor yields no
     * discount.
     */
    @Test
    void testApply_Progressive_BelowFirstFloor_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("50"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PBF", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.PROGRESSIVE, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 30.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests the progressive PERCENTAGE award on the QUANTITY trigger: floors 0 (5%) and 3
     * (10%) over 5 units at a 4€ average grant 3×4×5% + 2×4×10% = 1.40€.
     */
    @Test
    void testApply_Progressive_Percentage_QuantityTrigger() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("0"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("5"), null, 1)),
                new TierTable.Tier<>(new BigDecimal("3"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PPQ", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.PROGRESSIVE, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 5.0, 20.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("1.40"), amount.amountIncludingTax);
    }

    /**
     * Tests that a per-multiple resolution whose base is below the step yields no discount.
     */
    @Test
    void testApply_PerMultiple_BelowStep_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TieredDiscountFactory.Award award = new TieredDiscountFactory.Award(
                TieredDiscountFactory.AwardType.AMOUNT, new BigDecimal("1.00"), null, 1);
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PMB", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.PER_MULTIPLE, PriceUsage.BASE_FOR_DISCOUNT,
                null, new BigDecimal("100"), award, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 30.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests that an ITEM_FREE award whose targeted products carry no price row values
     * nothing and yields no application.
     */
    @Test
    void testApply_ItemFree_NoPricedProduct_NoApplication() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000020", "NoPrice", ProductType.UNIT);
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.CHEAPEST, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_IFNP2", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 2.0, 6.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests that an ITEM_FREE award over an unpersisted product (no id) values nothing:
     * the unit price lookup is skipped.
     */
    @Test
    void testApply_ItemFree_UnpersistedProduct_NoApplication() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.CHEAPEST, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_IFUP", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 2.0, 6.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests that two offer applications covering the same product merge their available
     * units before an ITEM_FREE award is valued.
     */
    @Test
    void testApply_ItemFree_SameProductTwoApplications_Merges() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000021", "Merged", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(product, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("2.50"), new BigDecimal("3.00"), new BigDecimal("0.20"));
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.ITEM_FREE, null,
                        TieredDiscountFactory.Selection.CHEAPEST, 2))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_IFM", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 3.00));
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 3.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        BigDecimal sum = discounts.stream()
                .map(d -> ((TieredDiscountFactory.TieredDiscountApplication) d)
                        .getDiscountAmount().amountIncludingTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Two cheapest units of the same 3€ product, drawn across both applications.
        assertEquals(new BigDecimal("6.00"), sum);
    }

    /**
     * Tests that a NEW_PRICE award over a product without a price row values nothing and
     * yields no application.
     */
    @Test
    void testApply_NewPrice_ProductWithoutPrice_NoApplication() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000022", "NoPriceNP", ProductType.UNIT);
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("2"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.NEW_PRICE, new BigDecimal("4.75"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_NPNP", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 2.0, 12.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests the per-multiple ITEM_FREE award when no targeted product carries a price: the
     * resolution values nothing and yields no application.
     */
    @Test
    void testApply_PerMultiple_ItemFree_NoPricedProduct() {
        setUpDatabase();
        Product product = DomainUtils.createAndPersistProduct("2000000000023", "NoPricePM", ProductType.UNIT);
        TieredDiscountFactory.Award award = new TieredDiscountFactory.Award(
                TieredDiscountFactory.AwardType.ITEM_FREE, null, TieredDiscountFactory.Selection.CHEAPEST, 1);
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_PMIFNP", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.PER_MULTIPLE, PriceUsage.BASE_FOR_DISCOUNT,
                null, new BigDecimal("2"), award, List.of(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 5.0, 15.00));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests that a targeted product covered with a positive quantity but a null amount is
     * skipped when gathering the assiette, leaving no contribution and no discount.
     */
    @Test
    void testApply_ItemsScope_NullProductAmountSkipped() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        StubApplication stub = new StubApplication("1000000000001", 2.0, new AmountEvaluation(
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20")), null);
        assertEquals(2.0, stub.getProductQuantity(product));
        assertNull(stub.getProductAmount(product));
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(stub);
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("5"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_NPA", TieredDiscountFactory.Scope.ITEMS, TieredDiscountFactory.Trigger.QUANTITY,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of(product));
        assertTrue(applier.apply(evaluation).isEmpty());
    }

    /**
     * Tests the TICKET progressive percentage: floors 0 (5%) and 50 (10%) over a 120€ ticket
     * grant 2.50 + 7.00 = 9.50€, exercising the average-unit fallback on a quantity-less
     * assiette.
     */
    @Test
    void testApply_TicketScope_Progressive_Percentage() {
        setUpDatabase();
        Product product = new Product();
        product.ean = "1000000000001";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("0"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("5"), null, 1)),
                new TierTable.Tier<>(new BigDecimal("50"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_TPP", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.PROGRESSIVE, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(product, 1.0, 120.00));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("9.50"), amount.amountIncludingTax);
    }

    /**
     * Tests that a share rounding to zero is skipped in the pro-rata split: a 0.01€ flat
     * discount over three groups lands entirely on the last one.
     */
    @Test
    void testApply_TicketScope_ZeroShareSkipped() {
        setUpDatabase();
        Product p1 = new Product();
        p1.ean = "1000000000001";
        Product p2 = new Product();
        p2.ean = "1000000000002";
        Product p3 = new Product();
        p3.ean = "1000000000003";
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.AMOUNT, new BigDecimal("0.01"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_ZS", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new ProductApplication(p1, 1.0, 40.00));
        evaluation.getOffers().add(new ProductApplication(p2, 1.0, 40.00));
        evaluation.getOffers().add(new ProductApplication(p3, 1.0, 20.00));
        List<AdvantageApplication> discounts = new java.util.ArrayList<>(applier.apply(evaluation));
        assertEquals(1, discounts.size());
        assertEquals(new BigDecimal("0.01"), ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.get(0)).getDiscountAmount().amountIncludingTax);
    }

    /**
     * Tests the tax-rate fallback when a group carries no tax-excluded amount: a zero-HT
     * ticket contribution splits with a zero VAT rate rather than dividing by zero.
     */
    @Test
    void testApply_TicketScope_ZeroExclTaxRateFallback() {
        setUpDatabase();
        TierTable<TieredDiscountFactory.Award> table = TierTable.of(List.of(
                new TierTable.Tier<>(new BigDecimal("1"), new TieredDiscountFactory.Award(
                        TieredDiscountFactory.AwardType.PERCENTAGE, new BigDecimal("10"), null, 1))));
        TieredDiscountFactory.TieredDiscountApplier applier = new TieredDiscountFactory.TieredDiscountApplier(
                "TIER_ZHT", TieredDiscountFactory.Scope.TICKET, TieredDiscountFactory.Trigger.AMOUNT,
                TieredDiscountFactory.Mode.HIGHEST_REACHED, PriceUsage.BASE_FOR_DISCOUNT,
                table, null, null, List.of());
        BasketEvaluation evaluation = newEvaluation();
        evaluation.getOffers().add(new StubApplication("1000000000001", 0.0,
                new AmountEvaluation(BigDecimal.ZERO, new BigDecimal("5.00"), BigDecimal.ZERO),
                new AmountEvaluation(BigDecimal.ZERO, new BigDecimal("5.00"), BigDecimal.ZERO)));
        Collection<AdvantageApplication> discounts = applier.apply(evaluation);
        assertEquals(1, discounts.size());
        AmountEvaluation amount = ((TieredDiscountFactory.TieredDiscountApplication)
                discounts.iterator().next()).getDiscountAmount();
        assertEquals(new BigDecimal("0.50"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("0.50"), amount.amountExcludingTax);
    }

    // --------------------------------------------------
    // Test helpers
    // --------------------------------------------------

    /**
     * A product-aware offer applier stub used to drive {@code isApplicable}: it is applicable
     * to a single EAN.
     */
    public static class FakeProductApplier extends OfferApplier implements ProductAwareOfferApplier {

        /**
         * The single EAN this applier covers.
         */
        private final String ean;

        /**
         * Builds a stub applier for one EAN.
         *
         * @param ean the covered EAN.
         */
        public FakeProductApplier(String ean) {
            this.ean = ean;
        }

        /**
         * Produces no application: the stub only serves the applicability probe.
         *
         * @param basketEvaluation the evaluation context.
         * @return an empty collection.
         */
        @Override
        public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
            return List.of();
        }

        /**
         * Tells whether this stub covers the given product.
         *
         * @param product the product to check.
         * @return true when the product carries the covered EAN.
         */
        @Override
        public boolean isApplicable(Product product) {
            return product != null && ean.equals(product.ean);
        }
    }

    /**
     * A plain offer applier stub that is not product-aware, used to drive the negative arm
     * of {@code isApplicable}.
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
    }

    /**
     * Minimal product-aware application: one product, one quantity, one TTC amount at a
     * 20% VAT rate.
     */
    public static class ProductApplication implements ProductAwareOfferApplication {

        /**
         * The covered product.
         */
        final Product product;

        /**
         * The covered quantity in standard units.
         */
        final double quantity;

        /**
         * The attributed amount.
         */
        final AmountEvaluation amount;

        /**
         * Builds the application.
         *
         * @param product  the covered product.
         * @param quantity the covered quantity.
         * @param ttcPrice the attributed amount, tax included (20% VAT assumed).
         */
        public ProductApplication(Product product, double quantity, double ttcPrice) {
            this.product = product;
            this.quantity = quantity;
            BigDecimal ttc = BigDecimal.valueOf(ttcPrice);
            BigDecimal ht = ttc.divide(BigDecimal.valueOf(1.20), 2, RoundingMode.HALF_UP);
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.20"));
        }

        /**
         * Returns the attributed amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list; unused by the tiered discount.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "Product";
        }

        /**
         * Returns the amount attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the amount when the EAN matches, null otherwise.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return (product != null && this.product.ean.equals(product.ean)) ? amount : null;
        }

        /**
         * Returns the quantity attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the quantity when the EAN matches, zero otherwise.
         */
        @Override
        public double getProductQuantity(Product product) {
            return (product != null && this.product.ean.equals(product.ean)) ? quantity : 0.0;
        }
    }

    /**
     * Minimal non-product-aware application, simulating a service such as a delivery.
     */
    public static class ServiceApplication implements OfferApplication {

        /**
         * The service amount.
         */
        final AmountEvaluation amount;

        /**
         * Builds the application.
         *
         * @param ttcPrice the service amount, tax included (20% VAT assumed).
         */
        public ServiceApplication(double ttcPrice) {
            BigDecimal ttc = BigDecimal.valueOf(ttcPrice);
            BigDecimal ht = ttc.divide(BigDecimal.valueOf(1.20), 2, RoundingMode.HALF_UP);
            this.amount = new AmountEvaluation(ht, ttc, new BigDecimal("0.20"));
        }

        /**
         * Returns the service amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
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
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "Service";
        }
    }

    /**
     * A product-aware application whose ticket amount and per-product amount are supplied
     * independently, so a test can drive the null-amount and zero-HT edge cases.
     */
    public static class StubApplication implements ProductAwareOfferApplication {

        /**
         * The covered EAN.
         */
        final String ean;

        /**
         * The covered quantity in standard units.
         */
        final double quantity;

        /**
         * The whole ticket amount (TICKET scope).
         */
        final AmountEvaluation amount;

        /**
         * The amount attributed to the covered product (ITEMS scope); may be null.
         */
        final AmountEvaluation productAmount;

        /**
         * Builds the stub.
         *
         * @param ean           the covered EAN.
         * @param quantity      the covered quantity.
         * @param amount        the whole ticket amount.
         * @param productAmount the per-product amount, possibly null.
         */
        public StubApplication(String ean, double quantity, AmountEvaluation amount,
                               AmountEvaluation productAmount) {
            this.ean = ean;
            this.quantity = quantity;
            this.amount = amount;
            this.productAmount = productAmount;
        }

        /**
         * Returns the whole ticket amount.
         *
         * @return the amount.
         */
        @Override
        public AmountEvaluation getAmount() {
            return amount;
        }

        /**
         * Returns the covered basket items.
         *
         * @return an empty list; unused by the tiered discount.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return List.of();
        }

        /**
         * Returns the display type.
         *
         * @return a constant test label.
         */
        @Override
        public String getType() {
            return "Stub";
        }

        /**
         * Returns the amount attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the supplied per-product amount when the EAN matches, null otherwise.
         */
        @Override
        public AmountEvaluation getProductAmount(Product product) {
            return (product != null && ean.equals(product.ean)) ? productAmount : null;
        }

        /**
         * Returns the quantity attributed to the given product.
         *
         * @param product the product being asked about.
         * @return the covered quantity when the EAN matches, zero otherwise.
         */
        @Override
        public double getProductQuantity(Product product) {
            return (product != null && ean.equals(product.ean)) ? quantity : 0.0;
        }
    }
}
