package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link NPlusMOfferFactory}.
 * <p>
 * This test class verifies the end-to-end functionality of the N+M offer system, including:
 * <ul>
 *   <li>Factory logic: Creation of appliers based on database offers and basket content.</li>
 *   <li>Applier logic: Selection strategies (Cheapest/Most Expensive), quantity calculations, and item picking.</li>
 *   <li>Application logic: Price calculations for fixed amounts and percentages, including pro-rata distribution.</li>
 * </ul>
 * <p>
 * Tests are transactional ({@link TestTransaction}) to ensure database isolation.
 */
@QuarkusTest
@TestTransaction
public class NPlusMOfferFactoryTest {

    /**
     * The factory instance under test, injected by CDI.
     */
    @Inject
    NPlusMOfferFactory factory;

    /**
     * The store context used for testing.
     */
    private Store store;

    /**
     * A product with a higher price (20.00 HT), used for testing "Most Expensive" strategies.
     */
    private Product productExpensive;

    /**
     * A product with a lower price (10.00 HT), used for testing "Cheapest" strategies.
     */
    private Product productCheap;

    /**
     * Initializes the database with a Store and two Products (Expensive and Cheap).
     * <p>
     * This method sets up the basic prerequisites for most tests in this class.
     * It creates default and base-for-discount prices for both products.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.0, 2.0);

        // Product Expensive (20.00 HT)
        productExpensive = DomainUtils.createAndPersistProduct("1000000000001", "Expensive Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(productExpensive, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("20.00"), new BigDecimal("24.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(productExpensive, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("20.00"), new BigDecimal("24.00"), new BigDecimal("0.20"));

        // Product Cheap (10.00 HT)
        productCheap = DomainUtils.createAndPersistProduct("2000000000002", "Cheap Product", ProductType.UNIT);
        DomainUtils.createAndPersistPrice(productCheap, store, 0, PriceUsage.DEFAULT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));
        DomainUtils.createAndPersistPrice(productCheap, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.20"));
    }

    // --------------------------------------------------
    // Tests for buildAppliers (Factory Level)
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier for a valid basket.
     * <p>
     * Scenario: An offer exists targeting two products, and the basket contains both.
     * Expected: One applier is created.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\", \"2000000000002\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 1, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_01", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0),
                createItem("2000000000002", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size());
    }

    /**
     * Tests that targetEans can be defined as a single string instead of an array.
     * <p>
     * Scenario: The JSON specification contains a single string EAN.
     * Expected: The factory parses the string and creates the applier successfully.
     */
    @Test
    void testBuildAppliers_TargetEanAsString() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": \"1000000000001\", " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 0, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 0 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_STR", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size(), "Should handle targetEans as a string");
    }

    /**
     * Tests that no applier is created when the offer's target EANs do not match the basket.
     * <p>
     * Scenario: The offer targets EAN "999...", but the basket contains EAN "100...".
     * Expected: The resulting appliers list is empty.
     */
    @Test
    void testBuildAppliers_NoMatchingEans() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"9999999999999\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 1, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 50 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_NO_MATCH", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.isEmpty());
    }

    /**
     * Tests schema validation failure when a required field is missing.
     * <p>
     * Scenario: The JSON specification is missing the "quantityToPay" field.
     * Expected: An {@link IllegalArgumentException} is thrown during processing.
     */
    @Test
    void testBuildAppliers_InvalidSpec_MissingField() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\"], " +
                "\"discountedQuantity\": 1 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_MISSING_FIELD", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation));
    }

    /**
     * Tests that no applier is created if the target EAN (as a string) is not in the basket.
     * <p>
     * Scenario: targetEans is a single string "999..." which is not in the basket.
     * Expected: The applier list is empty.
     */
    @Test
    void testBuildAppliers_TargetEanAsString_NotInBasket() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": \"9999999999999\", " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 0, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 0 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_STR_MISS", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.isEmpty(), "Should not create applier if string EAN is not in basket");
    }

    /**
     * Tests handling of an array of EANs where some match and some do not.
     * <p>
     * Scenario: The offer targets ["100...", "999..."]. Only "100..." is in the basket.
     * Expected: An applier is created for the matching EAN.
     */
    @Test
    void testBuildAppliers_ArrayWithMixedEans() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\", \"9999999999999\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 0, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 0 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_ARR_MIX", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size(), "Should create applier for the matching EAN in the array");

        NPlusMOfferFactory.NPlusMOfferApplier applier = (NPlusMOfferFactory.NPlusMOfferApplier) appliers.iterator().next();
        assertTrue(applier.isApplicable(productExpensive), "Applier should apply to the product found in basket");
    }

    // --------------------------------------------------
    // Tests for processOffer (Reflection - Private Method)
    // --------------------------------------------------

    /**
     * Tests the internal logic of processOffer when a textual EAN is not found.
     * <p>
     * Uses reflection to bypass the upstream database filtering.
     * Scenario: The offer specification has a textual EAN not present in the basket items.
     * Expected: No applier is added to the list.
     *
     * @throws Exception if reflection fails.
     */
    @Test
    void testProcessOffer_TextualEanNotFound() throws Exception {
        setUpDatabase();

        Offer offer = new Offer();
        offer.specification = "{ " +
                "\"targetEans\": \"9999999999999\", " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 0, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 0 " +
                "}";

        Map<String, Basket.Item> basketItems = new HashMap<>();
        basketItems.put("1000000000001", createItem("1000000000001", 1.0));

        List<OfferApplier> appliers = new ArrayList<>();

        Method method = NPlusMOfferFactory.class.getDeclaredMethod(
                "processOffer", Offer.class, Map.class, List.class, Store.class);
        method.setAccessible(true);
        method.invoke(factory, offer, basketItems, appliers, store);

        assertTrue(appliers.isEmpty(), "Should not create applier when textual EAN is missing from basketItems");
    }

    /**
     * Tests the internal logic when no target products match the offer.
     * <p>
     * Uses reflection to test the specific condition where hasTargetProduct is false.
     * Scenario: The offer targets an array of EANs, none of which are in the basket.
     * Expected: No applier is created.
     *
     * @throws Exception if reflection fails.
     */
    @Test
    void testProcessOffer_NoMatchingTargetProduct() throws Exception {
        setUpDatabase();

        Offer offer = new Offer();
        offer.code = "NPM_NO_MATCH";
        offer.specification = "{ " +
                "\"targetEans\": [\"9999999999999\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 0, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 0 " +
                "}";

        Map<String, Basket.Item> basketItems = new HashMap<>();
        basketItems.put("1000000000001", createItem("1000000000001", 1.0));

        List<OfferApplier> appliers = new ArrayList<>();

        Method method = NPlusMOfferFactory.class.getDeclaredMethod(
                "processOffer", Offer.class, Map.class, List.class, Store.class);
        method.setAccessible(true);
        method.invoke(factory, offer, basketItems, appliers, store);

        assertTrue(appliers.isEmpty(), "Should not create applier when hasTargetProduct is false");
    }

    // --------------------------------------------------
    // Tests for Apply Logic (Applier Level)
    // --------------------------------------------------

    /**
     * Tests the application logic with the "CHEAPEST" strategy.
     * <p>
     * Scenario: Offer 1+1. Basket has Expensive and Cheap. Strategy is Cheapest.
     * Expected: Expensive is paid, Cheap is discounted (100% off). Total = 20.00.
     */
    @Test
    void testApply_StrategyCheapest() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\", \"2000000000002\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 1, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 100 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_CHEAP", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0),
                createItem("2000000000002", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        NPlusMOfferFactory.NPlusMOfferApplier applier =
                (NPlusMOfferFactory.NPlusMOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        NPlusMOfferFactory.NPlusMApplication app =
                (NPlusMOfferFactory.NPlusMApplication) apps.iterator().next();

        assertEquals(new BigDecimal("20.00"), app.getAmount().amountExcludingTax);
    }

    /**
     * Tests the application logic with the "MOST_EXPENSIVE" strategy.
     * <p>
     * Scenario: Offer 1+1. Basket has Expensive and Cheap. Strategy is Most Expensive.
     * Expected: Cheap is paid, Expensive is discounted (100% off). Total = 10.00.
     */
    @Test
    void testApply_StrategyMostExpensive() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\", \"2000000000002\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 1, " +
                "\"selectionStrategy\": \"MOST_EXPENSIVE\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 100 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_EXP", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1000000000001", 1.0),
                createItem("2000000000002", 1.0)
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        NPlusMOfferFactory.NPlusMOfferApplier applier =
                (NPlusMOfferFactory.NPlusMOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        NPlusMOfferFactory.NPlusMApplication app =
                (NPlusMOfferFactory.NPlusMApplication) apps.iterator().next();

        assertEquals(new BigDecimal("10.00"), app.getAmount().amountExcludingTax);
    }

    /**
     * Tests the scenario where the basket quantity is insufficient to trigger the offer.
     * <p>
     * Scenario: Offer requires 2 items, basket has 1.
     * Expected: No application is created.
     */
    @Test
    void testApply_InsufficientQuantity() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\"], " +
                "\"quantityToPay\": 2, " +
                "\"discountedQuantity\": 0, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 0 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_INSUFF", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        NPlusMOfferFactory.NPlusMOfferApplier applier =
                (NPlusMOfferFactory.NPlusMOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        assertTrue(apps.isEmpty());
    }

    /**
     * Tests splitting a single line item across paid and discounted slots.
     * <p>
     * Scenario: Basket has 1 line item with quantity 2.0. Offer is 1+1.
     * Expected: The item is split into two entries: one paid, one discounted.
     */
    @Test
    void testApply_SplitItemAcrossPaidAndDiscounted() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\"], " +
                "\"quantityToPay\": 1, " +
                "\"discountedQuantity\": 1, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 100 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_SPLIT", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 2.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        NPlusMOfferFactory.NPlusMOfferApplier applier =
                (NPlusMOfferFactory.NPlusMOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        NPlusMOfferFactory.NPlusMApplication app =
                (NPlusMOfferFactory.NPlusMApplication) apps.iterator().next();

        assertEquals(new BigDecimal("20.00"), app.getAmount().amountExcludingTax);
        assertEquals(2, app.getItems().size(), "Item should be split into two entries");
    }

    /**
     * Tests applying an offer multiple times (Multiple Bundles).
     * <p>
     * Scenario: Offer is 2+1. Basket has 6 items.
     * Expected: 2 Applications are created (two bundles of 3 items each).
     */
    @Test
    void testApply_MultipleBundles() {
        setUpDatabase();
        String jsonSpec = "{ " +
                "\"targetEans\": [\"1000000000001\"], " +
                "\"quantityToPay\": 2, " +
                "\"discountedQuantity\": 1, " +
                "\"selectionStrategy\": \"CHEAPEST\", " +
                "\"discountType\": \"PERCENTAGE\", " +
                "\"discountValue\": 100 " +
                "}";
        DomainUtils.createAndPersistOffer("NPM_MULTI", store, "N+M", jsonSpec);

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 6.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        NPlusMOfferFactory.NPlusMOfferApplier applier =
                (NPlusMOfferFactory.NPlusMOfferApplier) appliers.iterator().next();
        Collection<OfferApplication> apps = applier.apply(evaluation);

        assertEquals(2, apps.size(), "Should create 2 applications for 2 bundles of 3");

        int totalItems = apps.stream().mapToInt(
                app -> app.getItems().stream().mapToInt(item -> (int) Math.round(item.quantity)).sum()
        ).sum();
        assertEquals(6, totalItems);
    }

    /**
     * Tests the case where sorted candidates list is empty.
     * <p>
     * Scenario: Applier is initialized with empty target items.
     * Expected: Apply returns an empty list immediately.
     */
    @Test
    void testApply_SortedCandidatesEmpty() {
        setUpDatabase();

        Map<String, Basket.Item> emptyTargetItems = new HashMap<>();
        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", emptyTargetItems, 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store
        );

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<OfferApplication> result = applier.apply(evaluation);

        assertTrue(result.isEmpty(), "Should return empty list when sortedCandidates is empty");
    }

    // --------------------------------------------------
    // Tests for Internal Helper Methods (Pick & Sort)
    // --------------------------------------------------

    /**
     * Tests getSortedCandidates with an empty target map.
     * <p>
     * Expected: Returns an empty list.
     */
    @Test
    void testGetSortedCandidates_Empty() {
        setUpDatabase();

        Map<String, Basket.Item> emptyTargetItems = new HashMap<>();
        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", emptyTargetItems, 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store
        );

        List<Basket.Item> result = applier.getSortedCandidates(null);

        assertTrue(result.isEmpty());
    }

    /**
     * Tests pickItemsFromEvaluation with valid conditions.
     * <p>
     * Checks that items are picked correctly and the evaluation map is updated.
     */
    @Test
    void testPickItemsFromEvaluation_SuccessfulPick() {
        setUpDatabase();

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 2.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", Collections.emptyMap(), 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store);

        List<Basket.Item> sortedCandidates = new ArrayList<>(basket.items);
        List<Basket.Item> result = applier.pickItemsFromEvaluation(evaluation, sortedCandidates, 1.0);

        assertEquals(1, result.size());
        assertEquals(1.0, result.get(0).quantity);
        assertEquals(1.0, evaluation.remainingQuantity("1000000000001"));
    }

    /**
     * Tests pickItemsFromEvaluation when loop continues (no break).
     */
    @Test
    void testPickItemsFromEvaluation_NoBreakNeeded() {
        setUpDatabase();

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1000000000001", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", Collections.emptyMap(), 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store);

        List<Basket.Item> sortedCandidates = new ArrayList<>(basket.items);
        List<Basket.Item> result = applier.pickItemsFromEvaluation(evaluation, sortedCandidates, 1.0);

        assertEquals(1, result.size());
    }

    /**
     * Tests pickItemsFromEvaluation when liveItem is null.
     */
    @Test
    void testPickItemsFromEvaluation_LiveItemNull() {
        setUpDatabase();

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = new ArrayList<>();
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", Collections.emptyMap(), 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store);

        List<Basket.Item> sortedCandidates = List.of(createItem("9999999999999", 1.0));
        List<Basket.Item> result = applier.pickItemsFromEvaluation(evaluation, sortedCandidates, 1.0);

        assertTrue(result.isEmpty());
    }

    /**
     * Tests pickItemsFromEvaluation when liveItem quantity is zero.
     */
    @Test
    void testPickItemsFromEvaluation_LiveItemQuantityZero() {
        setUpDatabase();

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = new ArrayList<>();
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Basket.Item zeroItem = createItem("1000000000001", 0.0);
        evaluation.getToEvaluate().put("1000000000001", new ArrayList<>(List.of(zeroItem)));

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", Collections.emptyMap(), 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store);

        List<Basket.Item> sortedCandidates = List.of(zeroItem);
        List<Basket.Item> result = applier.pickItemsFromEvaluation(evaluation, sortedCandidates, 1.0);

        assertTrue(result.isEmpty());
    }

    /**
     * Tests pickItemsFromEvaluation when pick returns null (e.g., EAN is null).
     */
    @Test
    void testPickItemsFromEvaluation_PickedItemNull() {
        setUpDatabase();

        Basket.Item nullEanItem = new Basket.Item();
        nullEanItem.produceEan = null;
        nullEanItem.quantity = 1.0;

        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = new ArrayList<>();
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        evaluation.getToEvaluate().put(null, new ArrayList<>(List.of(nullEanItem)));

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", Collections.emptyMap(), 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store);

        List<Basket.Item> sortedCandidates = List.of(nullEanItem);
        List<Basket.Item> result = applier.pickItemsFromEvaluation(evaluation, sortedCandidates, 1.0);

        assertTrue(result.isEmpty(), "Item should not be added if pickedItem is null");
    }

    /**
     * Tests createApplicationsFromPool when there are no paid items (N=0).
     *
     * @throws Exception if reflection fails.
     */
    @Test
    void testCreateApplicationsFromPool_NoPaidItems() throws Exception {
        setUpDatabase();

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", Collections.emptyMap(),
                0,
                1,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 100, store
        );

        List<Basket.Item> pickedPool = List.of(createItem("1000000000001", 1.0));

        Method method = NPlusMOfferFactory.NPlusMOfferApplier.class.getDeclaredMethod(
                "createApplicationsFromPool", List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Collection<OfferApplication> result = (Collection<OfferApplication>) method.invoke(applier, pickedPool);

        assertEquals(1, result.size());

        NPlusMOfferFactory.NPlusMApplication app = (NPlusMOfferFactory.NPlusMApplication) result.iterator().next();
        assertEquals(new BigDecimal("0.00"), app.getAmount().amountIncludingTax);
    }

    // --------------------------------------------------
    // Tests for Price Calculation (Application Level)
    // --------------------------------------------------

    /**
     * Tests calculation with a fixed amount discount.
     */
    @Test
    void testGetAmount_FixedAmountDiscount() {
        setUpDatabase();
        List<Basket.Item> paid = Collections.emptyList();
        List<Basket.Item> discounted = List.of(createItem("2000000000002", 1.0));

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.FIXED_AMOUNT, 2.0
        );

        assertEquals(new BigDecimal("8.33"), app.getAmount().amountExcludingTax);
    }

    /**
     * Tests that a fixed amount discount is capped at the product price.
     */
    @Test
    void testGetAmount_FixedAmountCapped() {
        setUpDatabase();
        List<Basket.Item> paid = Collections.emptyList();
        List<Basket.Item> discounted = List.of(createItem("2000000000002", 1.0));

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.FIXED_AMOUNT, 50.0
        );

        assertEquals(new BigDecimal("0.00"), app.getAmount().amountExcludingTax);
    }

    /**
     * Tests pro-rata distribution of a fixed discount across multiple products.
     */
    @Test
    void testGetProductAmount_FixedProRata() {
        setUpDatabase();
        List<Basket.Item> discounted = List.of(
                createItem("2000000000002", 1.0),
                createItem("1000000000001", 1.0)
        );

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", Collections.emptyList(), discounted, store, NPlusMOfferFactory.DiscountType.FIXED_AMOUNT, 6.0
        );

        AmountEvaluation amountCheap = app.getProductAmount(productCheap);
        assertEquals(new BigDecimal("8.33"), amountCheap.amountExcludingTax);

        AmountEvaluation amountExp = app.getProductAmount(productExpensive);
        assertEquals(new BigDecimal("16.67"), amountExp.amountExcludingTax);
    }

    /**
     * Tests that getProductAmount returns null if the product is not in the application.
     */
    @Test
    void testGetProductAmount_ProductNotFound() {
        setUpDatabase();
        List<Basket.Item> paid = List.of(createItem("1000000000001", 1.0));
        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, Collections.emptyList(), store, NPlusMOfferFactory.DiscountType.PERCENTAGE, 10.0
        );

        assertNull(app.getProductAmount(productCheap));
    }

    /**
     * Tests getProductQuantity for a product split between paid and discounted lists.
     */
    @Test
    void testGetProductQuantity_Split() {
        setUpDatabase();
        List<Basket.Item> paid = List.of(createItem("1000000000001", 1.5));
        List<Basket.Item> discounted = List.of(createItem("1000000000001", 0.5));

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.PERCENTAGE, 50.0
        );

        double qty = app.getProductQuantity(productExpensive);
        assertEquals(2.0, qty, 0.001);
    }

    /**
     * Tests the getType method representation.
     */
    @Test
    void testGetType() {
        setUpDatabase();
        List<Basket.Item> paid = List.of(createItem("1000000000001", 1.0));
        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "MY_OFFER", paid, Collections.emptyList(), store, NPlusMOfferFactory.DiscountType.PERCENTAGE, 50.0
        );

        String type = app.getType();
        assertTrue(type.contains("MY_OFFER"));
    }

    /**
     * Tests getProductQuantity with mixed matching and non-matching items.
     */
    @Test
    void testGetProductQuantity_MixedItems() {
        setUpDatabase();

        List<Basket.Item> paid = List.of(
                createItem("1000000000001", 1.0),
                createItem("2000000000002", 5.0)
        );

        List<Basket.Item> discounted = List.of(
                createItem("2000000000002", 2.0)
        );

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.PERCENTAGE, 50.0
        );

        double qtyExpensive = app.getProductQuantity(productExpensive);
        assertEquals(1.0, qtyExpensive, 0.001, "Should count only matching items in paid list");

        double qtyCheap = app.getProductQuantity(productCheap);
        assertEquals(7.0, qtyCheap, 0.001, "Should aggregate matching items from both lists");
    }

    /**
     * Tests getProductAmount when the product is fully paid.
     */
    @Test
    void testGetProductAmount_PaidPartIsNonZero() {
        setUpDatabase();

        List<Basket.Item> paid = List.of(createItem("1000000000001", 1.0));
        List<Basket.Item> discounted = Collections.emptyList();

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.PERCENTAGE, 50.0
        );

        AmountEvaluation result = app.getProductAmount(productExpensive);

        assertNotNull(result);
        assertEquals(new BigDecimal("20.00"), result.amountExcludingTax);
    }

    /**
     * Tests getProductAmount with a percentage discount.
     */
    @Test
    void testGetProductAmount_DiscountTypePercentage() {
        setUpDatabase();

        List<Basket.Item> paid = Collections.emptyList();
        List<Basket.Item> discounted = List.of(createItem("1000000000001", 1.0));

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.PERCENTAGE, 50.0
        );

        AmountEvaluation result = app.getProductAmount(productExpensive);

        assertNotNull(result);
        assertEquals(new BigDecimal("10.00"), result.amountExcludingTax);
    }

    /**
     * Tests getProductAmount when fixed discount exceeds the price.
     */
    @Test
    void testGetProductAmount_FixedAmountExceedsBlockPrice() {
        setUpDatabase();

        List<Basket.Item> paid = Collections.emptyList();
        List<Basket.Item> discounted = List.of(createItem("1000000000001", 1.0));

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.FIXED_AMOUNT, 50.0
        );

        AmountEvaluation result = app.getProductAmount(productExpensive);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.00"), result.amountExcludingTax);
    }

    /**
     * Tests nominal pro-rata calculation for fixed amount.
     */
    @Test
    void testGetProductAmount_FixedAmountProRata_Nominal() {
        setUpDatabase();

        List<Basket.Item> discounted = List.of(
                createItem("1000000000001", 1.0),
                createItem("2000000000002", 1.0)
        );

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", Collections.emptyList(), discounted, store, NPlusMOfferFactory.DiscountType.FIXED_AMOUNT, 6.0
        );

        AmountEvaluation resultExp = app.getProductAmount(productExpensive);
        assertEquals(new BigDecimal("16.67"), resultExp.amountExcludingTax);

        AmountEvaluation resultCheap = app.getProductAmount(productCheap);
        assertEquals(new BigDecimal("8.33"), resultCheap.amountExcludingTax);
    }

    /**
     * Tests getAmount when the discounted block is empty.
     */
    @Test
    void testGetAmount_DiscountedBlockIsEmpty() {
        setUpDatabase();

        List<Basket.Item> paid = List.of(createItem("1000000000001", 1.0));
        List<Basket.Item> discounted = Collections.emptyList();

        NPlusMOfferFactory.NPlusMApplication app = new NPlusMOfferFactory.NPlusMApplication(
                "TEST", paid, discounted, store, NPlusMOfferFactory.DiscountType.FIXED_AMOUNT, 50.0
        );

        AmountEvaluation result = app.getAmount();

        assertNotNull(result);
        assertEquals(new BigDecimal("20.00"), result.amountExcludingTax);
        assertEquals(new BigDecimal("24.00"), result.amountIncludingTax);
    }

    /**
     * Tests isApplicable returns true when EAN matches.
     */
    @Test
    void testIsApplicable_True() {
        setUpDatabase();

        Map<String, Basket.Item> targetItems = new HashMap<>();
        targetItems.put("1000000000001", createItem("1000000000001", 1.0));

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", targetItems, 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store
        );

        assertTrue(applier.isApplicable(productExpensive), "Should return true when product EAN is in targetItems");
    }

    /**
     * Tests isApplicable returns false when EAN does not match.
     */
    @Test
    void testIsApplicable_False() {
        setUpDatabase();

        Map<String, Basket.Item> targetItems = new HashMap<>();
        targetItems.put("1000000000001", createItem("1000000000001", 1.0));

        NPlusMOfferFactory.NPlusMOfferApplier applier = new NPlusMOfferFactory.NPlusMOfferApplier(
                "TEST", targetItems, 1, 0,
                NPlusMOfferFactory.SelectionStrategy.CHEAPEST,
                NPlusMOfferFactory.DiscountType.PERCENTAGE, 0, store
        );

        assertFalse(applier.isApplicable(productCheap), "Should return false when product EAN is NOT in targetItems");
    }
}