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
 * Integration tests for {@link MixedBundleOfferFactory}.
 * <p>
 * Covers offer creation, bundle logic, substitutions, and application calculations.
 */
@QuarkusTest
@TestTransaction
public class MixedBundleOfferFactoryTest {

    @Inject
    MixedBundleOfferFactory factory;

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
        DomainUtils.createAndPersistPrice(mainProduct, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));

        // Substitute Product (Price 20.00 HT)
        subProduct = DomainUtils.createAndPersistProduct("2000000000002", "Sub Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(subProduct, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("20.00"), new BigDecimal("24.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(subProduct, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("20.00"), new BigDecimal("24.00"), new BigDecimal("0.20"));

        // Other Product (Price 5.00 HT)
        otherProduct = DomainUtils.createAndPersistProduct("3000000000003", "Other Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(otherProduct, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(otherProduct, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("5.00"), new BigDecimal("6.00"), new BigDecimal("0.20"));
    }

    // --------------------------------------------------
    // Tests for buildAppliers
    // --------------------------------------------------

    /**
     * Tests successful creation of an applier when all components are present.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        // Offer: Main (1) + Other (1) for 10€
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 }, " +
                "{ \"ean\": \"3000000000003\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_01", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0),
                createItem("3000000000003", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        assertEquals(1, appliers.size(), "Should create one applier");
        assertTrue(appliers.iterator().next() instanceof MixedBundleOfferFactory.MixedBundleOfferApplier);
    }

    /**
     * Tests that no applier is created if a component is missing from the basket.
     */
    @Test
    void testBuildAppliers_MissingComponent() {
        setUpDatabase();
        // Offer requires Main + Other
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 }, " +
                "{ \"ean\": \"3000000000003\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_MISSING", store, "MIXED_BUNDLE", jsonSpec);

        // Basket only has Main
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        assertTrue(appliers.isEmpty(), "Should not create applier if a component is missing");
    }

    /**
     * Tests that an applier is created when the Main EAN is missing but a Substitute is present.
     */
    @Test
    void testBuildAppliers_WithSubstitutes() {
        setUpDatabase();
        // Offer: Requires Main (qty 1), but accepts Sub as substitute
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0, \"substituteEans\": [\"2000000000002\"] } " +
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_SUB", store, "MIXED_BUNDLE", jsonSpec);

        // Basket has only the Substitute
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("2000000000002", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        assertEquals(1, appliers.size(), "Should create applier via substitute");
    }

    // --------------------------------------------------
    // Tests for Schema Validation
    // --------------------------------------------------

    /**
     * Tests that a specification declaring no pricing mode at all is rejected.
     * <p>
     * The schema requires exactly one of "bundlePrice" or "discount".
     */
    @Test
    void testBuildAppliers_MissingBundlePrice() {
        setUpDatabase();
        String jsonSpec = "{ \"vatRate\": 0.20, \"contents\": [ { \"ean\": \"1000000000001\", \"quantity\": 1 } ] }";
        DomainUtils.createAndPersistOffer("NO_PRICE", store, "MIXED_BUNDLE", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that a specification declaring both pricing modes at once is rejected.
     * <p>
     * A fixed bundle price and a discount are mutually exclusive.
     */
    @Test
    void testBuildAppliers_BothPricingModes() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"discount\": { \"type\": \"PERCENTAGE\", \"value\": 20.0 }, " +
                "\"vatRate\": 0.20, \"contents\": [ { \"ean\": \"1000000000001\", \"quantity\": 1 } ] }";
        DomainUtils.createAndPersistOffer("BOTH_MODES", store, "MIXED_BUNDLE", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that a specification priced by a discount instead of a fixed amount is accepted.
     */
    @Test
    void testBuildAppliers_DiscountMode() {
        setUpDatabase();
        String jsonSpec = "{ \"discount\": { \"type\": \"PERCENTAGE\", \"value\": 20.0 }, " +
                "\"vatRate\": 0.20, \"contents\": [ { \"ean\": \"1000000000001\", \"quantity\": 1 } ] }";
        DomainUtils.createAndPersistOffer("DISCOUNT_MODE", store, "MIXED_BUNDLE", jsonSpec);
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        assertEquals(1, appliers.size(), "Should create an applier when priced by discount");
    }

    // --------------------------------------------------
    // Tests for Apply Logic
    // --------------------------------------------------

    /**
     * Tests successful application of one bundle.
     */
    @Test
    void testApply_OneBundle() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 12.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("APPLY_01", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        Collection<OfferApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        MixedBundleOfferFactory.MixedBundleApplication app =
                (MixedBundleOfferFactory.MixedBundleApplication) apps.iterator().next();

        // Check consumption
        assertTrue(evaluation.getToEvaluate().isEmpty(), "Item should be consumed");

        // Check Price: 12.00 TTC -> 10.00 HT
        assertEquals(new BigDecimal("10.00"), app.getAmount().amountExcludingTax);
        assertEquals(new BigDecimal("12.00"), app.getAmount().amountIncludingTax);
    }

    /**
     * Tests application of multiple bundles (2x).
     */
    @Test
    void testApply_MultipleBundles() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("APPLY_MULTI", store, "MIXED_BUNDLE", jsonSpec);

        // Basket has qty 2.5, should form 2 bundles
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 2.5));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        Collection<OfferApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        MixedBundleOfferFactory.MixedBundleApplication app =
                (MixedBundleOfferFactory.MixedBundleApplication) apps.iterator().next();

        // 2 bundles * 10.00 = 20.00 TTC -> 16.67 HT
        assertEquals(2, app.bundleCount);
        assertEquals(new BigDecimal("16.67"), app.getAmount().amountExcludingTax);
        assertEquals(new BigDecimal("20.00"), app.getAmount().amountIncludingTax);

        // 2.5 - 2.0 = 0.5 remaining
        assertEquals(0.5, evaluation.getToEvaluate().get("1000000000001").quantity, 0.001);
    }

    /**
     * Tests consumption logic prioritizing Main EAN, then Substitutes.
     */
    @Test
    void testApply_SubstitutionPriority() {
        setUpDatabase();
        // Offer requires 2 items. Main EAN is 100... Sub is 200...
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0, \"substituteEans\": [\"2000000000002\"] } " +
                "] }";
        DomainUtils.createAndPersistOffer("APPLY_PRIO", store, "MIXED_BUNDLE", jsonSpec);

        // Basket has 1 Main (100...) and 1 Sub (200...)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0), // Main
                createItem("2000000000002", 2.0)  // Sub
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        Collection<OfferApplication> apps = applier.apply(evaluation);
        MixedBundleOfferFactory.MixedBundleApplication app =
                (MixedBundleOfferFactory.MixedBundleApplication) apps.iterator().next();

        // Verify consumption
        assertEquals(2, app.getItems().size());

        // Check pro-rata amount calculation
        // Total Bundle: 15.00 TTC -> 12.50 HT
        // Ref Prices: Main(10.00 HT) + Sub(20.00 HT) = 30.00 HT Total Ref
        // Ratio Main: 10/30 = 0.3333 -> 12.50 * 0.3333 = 4.17 HT
        // Ratio Sub:  20/30 = 0.6666 -> 12.50 * 0.6666 = 8.33 HT

        AmountEvaluation mainAmount = app.getProductAmount(mainProduct);
        AmountEvaluation subAmount = app.getProductAmount(subProduct);

        assertEquals(new BigDecimal("4.17"), mainAmount.amountExcludingTax);
        assertEquals(new BigDecimal("8.33"), subAmount.amountExcludingTax);
    }

    // --------------------------------------------------
    // Tests for Application Logic
    // --------------------------------------------------

    /**
     * Tests the pro-rata calculation in getProductAmount.
     */
    @Test
    void testApplication_GetProductAmount_ProRata() {
        setUpDatabase();
        // Bundle: Main (10€ HT) + Other (5€ HT) = 15€ HT Ref Price.
        // Bundle Fixed Price: 12.00 TTC (10.00 HT).
        String jsonSpec = "{ \"bundlePrice\": 12.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 }, " +
                "{ \"ean\": \"3000000000003\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("PRO_RATA", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0),
                createItem("3000000000003", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        MixedBundleOfferFactory.MixedBundleApplication app =
                (MixedBundleOfferFactory.MixedBundleApplication) applier.apply(evaluation).iterator().next();

        // Calculation:
        // Total Ref HT = 10.00 + 5.00 = 15.00
        // Bundle HT = 10.00
        // Main Product Ratio = 10.00 / 15.00 = 0.666666...
        // Main Product Price HT = 10.00 * 0.666666 = 6.67

        AmountEvaluation mainAmount = app.getProductAmount(mainProduct);
        assertEquals(new BigDecimal("6.67"), mainAmount.amountExcludingTax);

        // Other Product Ratio = 5.00 / 15.00 = 0.333333...
        // Other Product Price HT = 10.00 * 0.333333 = 3.33
        AmountEvaluation otherAmount = app.getProductAmount(otherProduct);
        assertEquals(new BigDecimal("3.33"), otherAmount.amountExcludingTax);
    }

    /**
     * Tests getProductQuantity for a specific product.
     */
    @Test
    void testApplication_GetProductQuantity() {
        setUpDatabase();
        String jsonSpec = "{ \"bundlePrice\": 12.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0 } " + // Needs 2
                "] }";
        DomainUtils.createAndPersistOffer("QTY_TEST", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 2.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleApplication app =
                (MixedBundleOfferFactory.MixedBundleApplication) appliers.iterator().next().apply(evaluation).iterator().next();

        assertEquals(2.0, app.getProductQuantity(mainProduct));
        assertEquals(0.0, app.getProductQuantity(subProduct));
    }

    /**
     * Tests isApplicable for the Applier.
     */
    @Test
    void testApplier_IsApplicable() {
        setUpDatabase();
        // Bundle targets Main (with Sub as substitute)
        String jsonSpec = "{ \"bundlePrice\": 12.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0, \"substituteEans\": [\"2000000000002\"] } " +
                "] }";
        DomainUtils.createAndPersistOffer("APPLICABLE", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) factory.buildAppliers(evaluation).iterator().next();

        assertTrue(applier.isApplicable(mainProduct), "Should be applicable to Main product");
        assertTrue(applier.isApplicable(subProduct), "Should be applicable to Substitute product");
        assertFalse(applier.isApplicable(otherProduct), "Should not be applicable to Other product");
    }

    /**
     * Tests calculation when reference price is zero (division by zero guard).
     */
    @Test
    void testApplication_GetProductAmount_ZeroRefPrice() {
        setUpDatabase();
        // Create a product with 0 price
        Product zeroProduct = DomainUtils.createAndPersistProduct("4000000000004", "Zero Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(zeroProduct, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.20"));

        String jsonSpec = "{ \"bundlePrice\": 12.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"4000000000004\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("ZERO_REF", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("4000000000004", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleApplication app =
                (MixedBundleOfferFactory.MixedBundleApplication) appliers.iterator().next().apply(evaluation).iterator().next();

        // Should not crash, return zero amount
        AmountEvaluation amount = app.getProductAmount(zeroProduct);
        assertNotNull(amount);
        assertEquals(BigDecimal.ZERO.setScale(2), amount.amountExcludingTax);
    }

    /**
     * Tests {@link MixedBundleOfferApplier#apply} when stock is insufficient to form a bundle.
     * <p>
     * Scenario: Bundle requires 2 items, but basket only has 1.
     * Condition tested: {@code if (nbPossibleBundles > 0)} is false.
     */
    @Test
    void testApply_InsufficientStock() {
        setUpDatabase();
        // Offer: Requires 2 items of Main Product
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_STOCK_KO", store, "MIXED_BUNDLE", jsonSpec);

        // Basket: Only 1 item available (Cannot form a bundle of 2)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should return empty list if quantity is insufficient");
        // Verify item was NOT consumed (still in evaluation map)
        assertEquals(1, evaluation.getToEvaluate().size());
    }

    /**
     * Tests {@link MixedBundleOfferApplier#apply} when consumption fails after calculation.
     * <p>
     * Scenario: A bundle requires 2 components using the SAME item (EAN).
     * Calculation counts availability twice (once per component) and thinks 1 bundle is possible.
     * Consumption consumes the item for the 1st component, leaving nothing for the 2nd.
     * <p>
     * Condition tested: {@code if (allConsumedItems != null)} is false.
     */
    @Test
    void testApply_ConsumptionFailure_OverlappingItems() {
        setUpDatabase();
        // Offer: Requires 2 components of the SAME item (Main Product)
        // This simulates a logic conflict where components are not distinct.
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 }, " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " + // Same EAN again
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_CONFLICT", store, "MIXED_BUNDLE", jsonSpec);

        // Basket: Exactly 1 item available.
        // Logic:
        // 1. calculateMaxPossibleBundles:
        //    - Comp 1 sees qty 1. Capacity = 1.
        //    - Comp 2 sees qty 1. Capacity = 1.
        //    - Min capacity = 1. Returns 1 bundle possible.
        // 2. consumeComponentsForBundles(nb=1):
        //    - Comp 1 consumes the item. Remaining in map = 0.
        //    - Comp 2 tries to consume. Item is gone. Returns NULL.
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should return empty list because consumption failed");
    }

    /**
     * Tests that no applier is created if no components match the basket items.
     * <p>
     * Scenario: Offer requires product A, but basket only contains product B (unrelated).
     * Result: The loop does not add any components to the list.
     * Condition tested: {@code !components.isEmpty()} is false.
     */
    @Test
    void testBuildAppliers_NoMatchingItems() {
        setUpDatabase();
        // Offer requires Main Product (100...)
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_NO_MATCH", store, "MIXED_BUNDLE", jsonSpec);

        // Basket contains only the "Sub Product" (200...), which is not in the offer
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("2000000000002", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        // The loop finishes with 'components' being empty.
        // The condition 'if (allComponentsAvailable && !components.isEmpty())' fails.
        assertTrue(appliers.isEmpty(), "Should not create applier if no components match basket items");
    }

    /**
     * Tests {@link MixedBundleOfferApplier#calculateMaxPossibleBundles} when the item is missing from the evaluation map.
     * <p>
     * Scenario: The Applier is created (because item exists in basket initially),
     * but the item is manually removed (consumed) before applying.
     * <p>
     * Condition tested: {@code if (available != null)} is false for all valid EANs.
     */
    @Test
    void testCalculateMaxPossibleBundles_ItemNotFound() {
        setUpDatabase();
        // Offer requires Main Product
        String jsonSpec = "{ \"bundlePrice\": 15.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("BUNDLE_NULL", store, "MIXED_BUNDLE", jsonSpec);

        // Basket contains the item
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 2.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act 1: Build Appliers (The factory sees the item and creates the applier)
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        // Act 2: Simulate that the item was consumed by another offer before this one runs
        // This forces 'available' to be null inside calculateMaxPossibleBundles
        evaluation.getToEvaluate().clear();

        // Act 3: Apply
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        // Since available is null, totalAvailableQty remains 0.0.
        // possibleForComp becomes 0.
        // nbPossibleBundles becomes 0.
        // Application list should be empty.
        assertTrue(apps.isEmpty(), "Should return empty list when items are missing (available is null)");
    }

    /**
     * Tests consumption where partial quantity is taken from Main, forcing check on Substitute.
     * <p>
     * Scenario: Bundle needs 2 items. Main has 1. Substitute has 1.
     * 1. Consumes Main (1). remainingToConsume = 1.
     * 2. Checks condition: (remainingToConsume <= 0.0) is FALSE. Continues loop.
     * 3. Consumes Substitute (1).
     */
    @Test
    void testConsume_PartialConsumptionNeedsSubstitute() {
        setUpDatabase();
        // Offer: Needs 2 items. Valid: Main OR Sub.
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 2.0, \"substituteEans\": [\"2000000000002\"] } " +
                "] }";
        DomainUtils.createAndPersistOffer("PARTIAL_CONS", store, "MIXED_BUNDLE", jsonSpec);

        // Basket: 1 Main + 1 Sub = 2 items total (Enough for 1 bundle)
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 2.0),
                createItem("2000000000002", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size(), "Should create 1 bundle combining Main and Sub");
    }

    /**
     * Tests that items with quantity 0 are ignored during consumption.
     * <p>
     * Condition tested: {@code available.quantity > 0.0} is false.
     */
    @Test
    void testConsume_ItemQuantityZero() {
        setUpDatabase();
        // Offer needs 1 item
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("QTY_ZERO", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        // Basket has 1 valid item
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // HACK: Manually insert an item with quantity 0 to test the guard
        Basket.Item zeroItem = new Basket.Item();
        zeroItem.produceEan = "1000000000001";
        zeroItem.quantity = 0.0; // Quantity is zero
        evaluation.getToEvaluate().put("1000000000001", zeroItem);

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert: Should return empty because the only available item has qty 0
        assertTrue(apps.isEmpty(), "Should not consume items with quantity 0");
    }

    /**
     * Tests the failure branch when evaluation.pick() returns null unexpectedly.
     * <p>
     * Condition tested: {@code if (picked != null)} is false -> enters {@code return null}.
     */
    @Test
    void testConsume_PickReturnsNull() {
        setUpDatabase();
        // Offer needs 1 item
        String jsonSpec = "{ \"bundlePrice\": 10.00, \"vatRate\": 0.20, \"contents\": [ " +
                "{ \"ean\": \"1000000000001\", \"quantity\": 1.0 } " +
                "] }";
        DomainUtils.createAndPersistOffer("PICK_FAIL", store, "MIXED_BUNDLE", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));

        // Use a custom BasketEvaluation that simulates a pick failure
        class BrokenPickEvaluation extends BasketEvaluation {
            public BrokenPickEvaluation(Basket basket) { super(basket); }

            @Override
            public Basket.Item pick(Double quantityToPick, String ean) {
                // Simulate a failure (e.g. item removed by another thread or logic error)
                return null;
            }
        }

        BasketEvaluation evaluation = new BrokenPickEvaluation(basket);
        evaluation.feedFrom(basket); // Items are present in the map

        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        MixedBundleOfferFactory.MixedBundleOfferApplier applier =
                (MixedBundleOfferFactory.MixedBundleOfferApplier) appliers.iterator().next();

        // The apply method should catch the failure and return empty list
        Collection<OfferApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should return empty list if pick fails (returns null)");
    }

    /**
     * Tests {@link MixedBundleApplication#getType()} output formatting.
     */
    @Test
    void testApplication_GetType() {
        // Arrange
        String code = "MENU_01";
        int count = 3;
        BigDecimal unitPrice = new BigDecimal("12.50"); // 12.50 TTC
        // Calcul attendu : 12.50 * 3 = 37.50
        String expectedType = "MixedBundle: MENU_01 x3 for 37.50€";
        // Constructeur: (Store, offerCode, bundlePriceUnit, discountType, discountValue, vatRate, coveredItems, bundleCount)
        MixedBundleOfferFactory.MixedBundleApplication app =
                new MixedBundleOfferFactory.MixedBundleApplication(
                        null,               // Store (non utilisé par getType)
                        code,
                        unitPrice,
                        null,               // discountType (mode prix fixe)
                        null,               // discountValue (mode prix fixe)
                        BigDecimal.ZERO,    // vatRate (non utilisé)
                        Collections.emptyList(), // coveredItems (non utilisé)
                        count
                );
        // Act
        String result = app.getType();
        // Assert
        assertEquals(expectedType, result);
    }

    /**
     * Tests {@link MixedBundleApplication#getProductAmount(Product)} when the requested product
     * is not part of the consumed items.
     * <p>
     * Condition tested: {@code if (items.length != 0)} is false.
     * Expectation: Returns null.
     */
    @Test
    void testGetProductAmount_ProductNotInBundle() {
        setUpDatabase();

        // Arrange: Create an application covering only 'mainProduct'
        // We simulate a simple bundle consumption
        Basket.Item consumedItem = new Basket.Item();
        consumedItem.produceEan = mainProduct.ean; // EAN: 100...
        consumedItem.quantity = 1.0;

        MixedBundleOfferFactory.MixedBundleApplication app =
                new MixedBundleOfferFactory.MixedBundleApplication(
                        store,
                        "BUNDLE_TEST",
                        new BigDecimal("10.00"),
                        null,               // discountType (mode prix fixe)
                        null,               // discountValue (mode prix fixe)
                        new BigDecimal("0.20"),
                        List.of(consumedItem),
                        1
                );

        // Act: Request amount for 'otherProduct' (which is NOT in the consumed items)
        // The stream filter will return an empty array -> items.length == 0
        AmountEvaluation result = app.getProductAmount(otherProduct);

        // Assert
        assertNull(result, "Should return null if the product is not part of the bundle items");
    }
}