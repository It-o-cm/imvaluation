package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.*;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MixedBundleUpsellAdvantageFactory}.
 * <p>
 * Verifies the logic for generating upsell suggestions based on incomplete bundle components.
 * Note: This factory relies on items present in 'availableToUpcell', which implies
 * items must have been picked (consumed) beforehand.
 */
@QuarkusTest
@TestTransaction
public class MixedBundleUpsellAdvantageFactoryTest {

    @Inject
    MixedBundleUpsellAdvantageFactory factory;

    private Store store;
    private Product mainProduct;
    private Product subProduct;
    private Product otherProduct;

    /**
     * Sets up the database with a Store and Products.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.0, 2.0);

        // Main Product (Price 10.00 HT)
        mainProduct = DomainUtils.createAndPersistProduct("1000000000001", "Main Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(mainProduct, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));

        // Substitute Product (Price 5.00 HT - Cheaper)
        subProduct = DomainUtils.createAndPersistProduct("2000000000002", "Sub Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(subProduct, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));

        // Other Product (Price 2.00 HT)
        otherProduct = DomainUtils.createAndPersistProduct("3000000000003", "Other Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(otherProduct, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("2.00"), new BigDecimal("2.40"), new BigDecimal("0.20"));
    }

    /**
     * Helper method to simulate that items have been processed by a standard offer.
     * Moves items from 'toEvaluate' to 'availableToUpcell'.
     */
    private void moveToUpcell(BasketEvaluation evaluation) {
        // We iterate over a copy to avoid ConcurrentModificationException. getToEvaluate()
        // now holds one list of price entries per EAN, so we flatten to individual items.
        List<Basket.Item> itemsToMove = new ArrayList<>();
        for (List<Basket.Item> bucket : evaluation.getToEvaluate().values()) {
            itemsToMove.addAll(bucket);
        }
        for (Basket.Item item : itemsToMove) {
            // Pick the item (removes from toEvaluate)
            Basket.Item picked = evaluation.pickMerged(item.quantity, item.produceEan);
            if (picked != null) {
                // Add to upcell map
                evaluation.addAvailableToUpcell(picked);
            }
        }
    }

    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_01", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
    }

    /**
     * Tests the suggestion logic when one component is missing.
     * <p>
     * Scenario: Bundle needs Main (Qty 1) + Other (Qty 1).
     * Basket (in Upcell): 4 Mains, 0 Others.
     * Expected: Global Max = 4. Needs 4 Others.
     */
    @Test
    void testApply_SuggestMissingQuantity() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 }, " + // Main
                "{ \"ean\": \"3000000000003\", \"quantity\": 1.0 } " +  // Other
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_MISSING", store, "MIXED_BUNDLE", jsonSpec);

        // Basket has 4 Mains, 0 Others
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 4.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // CRITICAL: Move items to availableToUpcell
        moveToUpcell(evaluation);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) appliers.iterator().next();

        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        // Verify Suggestion
        MixedBundleUpsellAdvantageFactory.UpsellSuggestion suggestion = app.getSuggestion();
        assertEquals("3000000000003", suggestion.ean);
        assertEquals(4.0, suggestion.quantity, 0.001);
    }

    /**
     * Tests that no suggestion is made if the basket components are complete.
     * <p>
     * Scenario: Bundle needs Main + Other.
     * Basket (in Upcell): 1 Main + 1 Other.
     * Expected: No deficit.
     */
    @Test
    void testApply_NoSuggestionIfComplete() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 }, " +
                "{ \"ean\": \"3000000000003\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_COMPLETE", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0),
                createItem("3000000000003", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        moveToUpcell(evaluation);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) appliers.iterator().next();

        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        assertTrue(apps.isEmpty(), "Should not suggest anything if basket is complete for current max potential");
    }

    /**
     * Tests that the applier suggests the cheapest valid product when there is a deficit.
     * <p>
     * Scenario: Bundle requires Qty 2. Basket has Qty 1 of Main.
     * Deficit = 1. Valid EANs = Main (10€) + Sub (5€).
     * Expectation: Suggests Sub because it is cheaper.
     */
    @Test
    void testApply_SuggestCheapestProduct() {
        setUpDatabase();

        // 1. Define Offer: Needs 2 items. Accepts Main OR Sub.
        // We create ONLY this offer.
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0, \"substituteEans\": [\"2000000000002\"] } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_CHEAP", store, "MIXED_BUNDLE", jsonSpec);

        // 2. Prepare Basket: Contains 1 Main item.
        // Target bundles = ceil(1 / 2) = 1 bundle possible.
        // Needed = 2. Available = 1. Deficit = 1.
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // 3. Move to Upcell context
        moveToUpcell(evaluation);

        // 4. Execute
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        // There should be exactly one applier for our specific offer
        assertEquals(1, appliers.size(), "Should have exactly one applier for the test offer");

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) appliers.iterator().next();

        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // 5. Assert
        assertEquals(1, apps.size());
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        // Logic:
        // Deficit exists.
        // Valid EANs for component: 100... (Main, 10€) and 200... (Sub, 5€).
        // findCheapestEan should return 200... (Sub).
        assertEquals("2000000000002", app.getSuggestion().ean, "Should suggest the cheapest substitute (Sub Product)");
    }

    /**
     * Tests output format of getType.
     */
    @Test
    void testApplication_GetType() {
        MixedBundleUpsellAdvantageFactory.UpsellSuggestion suggestion =
                new MixedBundleUpsellAdvantageFactory.UpsellSuggestion("1000000000001", 2.5, "OFFER_01");

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                new MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication("OFFER_01", suggestion);

        String type = app.getType();
        assertTrue(type.contains("Upsell Mixed Bundle"));
        assertTrue(type.contains("2,50"));
    }

    /**
     * Tests {@link MixedBundleUpsellAdvantageApplier#isApplicable(OfferApplier)}.
     * <p>
     * Expectation: Always returns false as this is an independent suggestion mechanism.
     */
    @Test
    void testApplier_IsApplicable() {
        setUpDatabase();
        // Setup minimal valid offer
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_APPLICABLE", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        // Get Applier
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) appliers.iterator().next();

        // Act & Assert
        // Pass any offerApplier (can be null or a mock, implementation ignores it)
        assertFalse(applier.isApplicable(null), "isApplicable should always return false");
    }

    /**
     * Tests {@link MixedBundleUpsellAdvantageApplier#getEfficiencyScore()}.
     * <p>
     * Expectation: Returns -100.0 to indicate low priority/up-sell nature.
     */
    @Test
    void testApplier_GetEfficiencyScore() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_SCORE", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) factory.buildAppliers(evaluation).iterator().next();

        // Act & Assert
        assertEquals(-100.0, applier.getEfficiencyScore(), "Efficiency score should be -100.0");
    }

    /**
     * Tests {@link MixedBundleUpsellAdvantageApplication#getOffer()}.
     * <p>
     * Expectation: Returns the same string as getType().
     */
    @Test
    void testApplication_GetOffer() {
        setUpDatabase();
        // Setup scenario that generates an application (deficit)
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_GET_OFFER", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0)); // Deficit of 1
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) factory.buildAppliers(evaluation).iterator().next();

        Collection<AdvantageApplication> apps = applier.apply(evaluation);
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        // Act & Assert
        assertEquals(app.getType(), app.getOffer(), "getOffer should return the result of getType");
    }

    /**
     * Tests {@link MixedBundleUpsellAdvantageApplication#getOfferApplication()}.
     * <p>
     * Expectation: Returns null as this is a suggestion, not a discount on an existing offer.
     */
    @Test
    void testApplication_GetOfferApplication() {
        setUpDatabase();
        // Setup scenario that generates an application
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_GET_APP", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) factory.buildAppliers(evaluation).iterator().next();

        Collection<AdvantageApplication> apps = applier.apply(evaluation);
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        // Act & Assert
        assertNull(app.getOfferApplication(), "getOfferApplication should return null for upsell suggestions");
    }

    /**
     * Tests {@link MixedBundleUpsellAdvantageApplier#calculateUpsell} when no items match the offer.
     * <p>
     * Scenario: The offer requires Main Product, but the upcell map is empty.
     * Condition tested: {@code if (globalMaxBundles == 0)} is true.
     * Expectation: Returns null (no suggestion generated).
     */
    @Test
    void testCalculateUpsell_GlobalMaxBundlesZero() {
        setUpDatabase();
        // Offer requires Main Product
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("UPSELL_ZERO", store, "MIXED_BUNDLE", jsonSpec);

        // Basket contains a different product (Other Product)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("3000000000003", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Move items to upcell map.
        // Since "Other Product" is not in the offer, getGlobalMaxBundles will find 0 quantity for the offer's components.
        moveToUpcell(evaluation);

        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) appliers.iterator().next();

        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        // calculateUpsell returns null, so apply returns an empty list
        assertTrue(apps.isEmpty(), "Should return empty list when globalMaxBundles is 0");
    }

    /**
     * Tests the fallback logic in findCheapestEan when the product is not found in DB.
     * <p>
     * Scenario: Offer requires Qty 2 of Unknown EAN. Basket has Qty 1.
     * Deficit = 1. findCheapestEan is called with set ["999..."].
     * Condition tested: {@code if (product != null)} is false.
     */
    @Test
    void testFindCheapestEan_ProductNotFound() {
        setUpDatabase();
        // Offer requires 2 items of Unknown Product
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"9999999999999\", \"quantity\": 2.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("FIND_NO_PROD", store, "MIXED_BUNDLE", jsonSpec);

        // Basket has only 1 item (Deficit = 1)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("9999999999999", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        // Act
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) factory.buildAppliers(evaluation).iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        // Logic: Product not found -> cheapestEan remains null -> Fallback returns iterator.next()
        assertEquals(1, apps.size());

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        assertEquals("9999999999999", app.getSuggestion().ean);
    }

    /**
     * Tests the fallback logic when product exists but price is missing.
     * <p>
     * Scenario: Offer requires Qty 2. Basket has Qty 1.
     * Deficit = 1. findCheapestEan called.
     * Condition tested: {@code if (price != null ...)} is false.
     */
    @Test
    void testFindCheapestEan_PriceNotFound() {
        setUpDatabase();
        // Create Product but NO Price
        Product noPriceProduct = DomainUtils.createAndPersistProduct("8000000000008", "No Price Product", ProductType.UNIT);

        // Offer requires 2 items
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"8000000000008\", \"quantity\": 2.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("FIND_NO_PRICE", store, "MIXED_BUNDLE", jsonSpec);

        // Basket has 1 item (Deficit = 1)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("8000000000008", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        // Act
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) factory.buildAppliers(evaluation).iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size());

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        // Price not found -> Fallback returns EAN
        assertEquals("8000000000008", app.getSuggestion().ean);
    }

    /**
     * Tests that the comparison logic ignores products more expensive than the current minimum.
     * <p>
     * Scenario:
     * - Main EAN is "1000000000001".
     * - Substitute EAN is "2000000000002".
     * <p>
     * Setup Modification:
     * - We set Main Product (100...) price to 5.00€ (Cheap).
     * - We set Substitute Product (200...) price to 10.00€ (Expensive).
     * <p>
     * Execution Flow:
     * 1. Loop finds Main "100..." (Price 5.00€).
     *    -> Condition (5.00 < MAX_VALUE) is TRUE.
     *    -> minPrice becomes 5.00. cheapestEan becomes "100...".
     * 2. Loop finds Substitute "200..." (Price 10.00€).
     *    -> Condition (10.00 < 5.00) is **FALSE**.
     *    -> Update is skipped.
     * <p>
     * Expectation: The suggestion returns the Main product ("100..."), ignoring the expensive substitute.
     */
    @Test
    void testFindCheapestEan_ComparisonFalse() {
        setUpDatabase();

        // 1. Override Prices to ensure Main is Cheap and Sub is Expensive
        // Clear default prices (optional, but good practice if changing logic) or just create new ones.
        // Here we just create new prices that will be picked up as active.
        // Main Product (100...) -> Price 5.00 HT
        DomainUtils.createAndPersistPrice(mainProduct, store, 1, PriceUsage.DEFAULT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));

        // Substitute Product (200...) -> Price 10.00 HT
        DomainUtils.createAndPersistPrice(subProduct, store, 1, PriceUsage.DEFAULT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));

        // 2. Define Offer: Main is 100..., Substitute is 200...
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0, \"substituteEans\": [\"2000000000002\"] } " +
                "] }";
        DomainUtils.createAndPersistOffer("FIND_COMPARE_FALSE", store, "MIXED_BUNDLE", jsonSpec);

        // 3. Prepare Basket: Contains 1 Main item (Deficit = 1)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        moveToUpcell(evaluation);

        // Act
        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier applier =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplier) factory.buildAppliers(evaluation).iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size());

        MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication app =
                (MixedBundleUpsellAdvantageFactory.MixedBundleUpsellAdvantageApplication) apps.iterator().next();

        // Verify that the expensive substitute was ignored (condition was false)
        // and the cheap Main product was kept.
        assertEquals("1000000000001", app.getSuggestion().ean, "Should return Main EAN as it is cheaper");
    }

}