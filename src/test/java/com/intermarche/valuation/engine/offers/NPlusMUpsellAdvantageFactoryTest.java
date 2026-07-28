package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.domain.util.DomainUtils;
import com.intermarche.valuation.engine.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link NPlusMUpsellAdvantageFactory} using the real database.
 * <p>
 * This class verifies the logic for creating N+M upsell suggestion appliers,
 * validating offer specifications, and calculating the correct suggestion details
 * (missing quantity and cheapest product identification).
 * <p>
 * It ensures that the factory correctly parses JSON specifications against the schema
 * and that the applier logic correctly handles the remaining items in the basket
 * to generate appropriate upsell opportunities.
 */
@QuarkusTest
@TestTransaction
public class NPlusMUpsellAdvantageFactoryTest {

    @Inject
    NPlusMUpsellAdvantageFactory factory;

    private Store store;
    private Product productA; // Expensive
    private Product productB; // Cheap

    /**
     * Sets up the database with a Store and Products before each test.
     * <p>
     * Two products are created:
     * <ul>
     *   <li>Product A: Expensive (10.00 HT)</li>
     *   <li>Product B: Cheap (1.00 HT)</li>
     * </ul>
     * This setup is used to verify the "cheapest product suggestion" logic.
     */
    void setUpDatabase() {
        // 1. Create Store
        store = DomainUtils.createAndPersistStore("STORE_01", 48.8566, 2.352214);

        // 2. Create Products
        productA = DomainUtils.createAndPersistProduct("1111111111111", "Product A", ProductType.UNIT);
        productB = DomainUtils.createAndPersistProduct("2222222222222", "Product B", ProductType.UNIT);

        // 3. Create Prices
        // Product A: 10.00 HT
        DomainUtils.createAndPersistPrice(productA, store, 0, PriceUsage.DEFAULT,
                BigDecimal.TEN, BigDecimal.valueOf(12.0), BigDecimal.valueOf(0.2));
        // Product B: 1.00 HT (Cheaper)
        DomainUtils.createAndPersistPrice(productB, store, 0, PriceUsage.DEFAULT,
                BigDecimal.ONE, BigDecimal.valueOf(1.2), BigDecimal.valueOf(0.2));
    }

    // --------------------------------------------------
    // Utility Methods
    // --------------------------------------------------

    /**
     * Helper method to create a valid N+M JSON specification.
     *
     * @param eans               The list of target EANs.
     * @param quantityToPay      The quantity N.
     * @param discountedQuantity The quantity M.
     * @return A JSON string representing the specification.
     */
    private String createSpec(List<String> eans, int quantityToPay, int discountedQuantity) {
        String eansJson = eans.stream()
                .map(e -> "\"" + e + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return String.format(
                "{ \"targetEans\": [%s], \"quantityToPay\": %d, \"discountedQuantity\": %d, " +
                        "\"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 50.0 }",
                eansJson, quantityToPay, discountedQuantity
        );
    }

    /**
     * Helper method to create a configured {@link Basket} DTO.
     *
     * @param storeCode The code of the store.
     * @param items     The items to include in the basket.
     * @return A configured {@link Basket} object.
     */
    private Basket createBasket(String storeCode, Basket.Item... items) {
        Basket b = new Basket();
        b.storeCode = storeCode;
        b.items = Arrays.asList(items);
        return b;
    }

    // --------------------------------------------------
    // Tests for buildAppliers (Factory Logic)
    // --------------------------------------------------

    /**
     * Tests the successful creation of an applier.
     * <p>
     * Scenario: A valid N+M offer exists in the database.
     * Expectation: One applier is created.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();
        // Arrange: Offer "2+1" on Product A
        String jsonSpec = createSpec(List.of("1111111111111"), 2, 1);
        DomainUtils.createAndPersistOffer("NPLUSM_01", store, "N+M", jsonSpec);

        Basket basket = createBasket("STORE_01", createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertEquals(1, appliers.size(), "Should create one applier for the configured offer");
    }

    /**
     * Tests the scenario where no N+M offers are found for the store.
     * <p>
     * Expectation: Returns an empty list.
     */
    @Test
    void testBuildAppliers_NoOffersFound() {
        setUpDatabase();
        // Arrange: No offers created
        Basket basket = createBasket("STORE_01", createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertTrue(appliers.isEmpty(), "Should return empty list if no offers are configured");
    }

    /**
     * Tests the scenario where the offer specification is invalid (JSON parsing error or schema validation error).
     * <p>
     * Expectation: Throws {@link IllegalArgumentException} via {@code processSpecification}.
     */
    @Test
    void testBuildAppliers_InvalidJson() {
        setUpDatabase();
        // Arrange: Bad JSON
        String badJson = "{ \"invalid\": \"true\" }";
        DomainUtils.createAndPersistOffer("BAD_JSON", store, "N+M", badJson);

        Basket basket = createBasket("STORE_01", createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw IllegalArgumentException for invalid JSON");
    }

    /**
     * Tests the scenario where the offer specification is missing a required field ("targetEans").
     * <p>
     * Expectation: Throws {@link IllegalArgumentException} (Schema validation failure).
     */
    @Test
    void testBuildAppliers_MissingTargetEans() {
        setUpDatabase();
        // Arrange: Missing targetEans
        String jsonSpec = "{ \"quantityToPay\": 2, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 50.0 }";
        DomainUtils.createAndPersistOffer("NO_EANS", store, "N+M", jsonSpec);

        Basket basket = createBasket("STORE_01", createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if targetEans is missing (Schema validation)");
    }

    /**
     * Tests the scenario where {@code quantityToPay} is 0 (invalid per schema constraints).
     * <p>
     * Expectation: Throws {@link IllegalArgumentException} (Schema validation failure: minimum 1).
     */
    @Test
    void testBuildAppliers_InvalidQuantityToPay() {
        setUpDatabase();
        // Arrange: quantityToPay = 0 (Schema requires minimum 1)
        String jsonSpec = createSpec(List.of("1111111111111"), 0, 1);
        DomainUtils.createAndPersistOffer("INVALID_QTY", store, "N+M", jsonSpec);

        Basket basket = createBasket("STORE_01", createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw if quantityToPay is 0 (Schema validation)");
    }

    /**
     * Tests parsing of "targetEans" when it is a single string value instead of an array.
     * <p>
     * Expectation: Applier is created successfully with the single EAN.
     */
    @Test
    void testBuildAppliers_TargetEansAsString() {
        setUpDatabase();
        // Arrange: targetEans is a string
        String jsonSpec = "{ \"targetEans\": \"1111111111111\", \"quantityToPay\": 2, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 50.0 }";
        DomainUtils.createAndPersistOffer("STRING_EAN", store, "N+M", jsonSpec);

        Basket basket = createBasket("STORE_01", createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);

        // Assert
        assertEquals(1, appliers.size(), "Should handle targetEans as a single string");
    }

    // --------------------------------------------------
    // Tests for NPlusMUpsellAdvantageApplier Logic
    // --------------------------------------------------

    /**
     * Tests the upsell calculation when the basket already has a complete bundle.
     * <p>
     * Scenario: Offer "2+1". Basket has exactly 3 items.
     * Expectation: No suggestion generated (returns empty collection).
     */
    @Test
    void testApply_CompleteBundleExists() {
        setUpDatabase();
        // Arrange
        String jsonSpec = createSpec(List.of("1111111111111"), 2, 1); // 2+1
        DomainUtils.createAndPersistOffer("OFFER_01", store, "N+M", jsonSpec);

        Basket.Item item = createItem("1111111111111", 3.0);
        Basket basket = createBasket("STORE_01", item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        evaluation.addAvailableToUpcell(evaluation.pickMerged(3.0, "1111111111111"));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should not suggest anything if bundle is complete");
    }

    /**
     * Tests the scenario where remaining items are empty.
     * <p>
     * Expectation: Returns empty collection.
     */
    @Test
    void testApply_NoRemainingItems() {
        setUpDatabase();
        // Arrange
        String jsonSpec = createSpec(List.of("1111111111111"), 2, 1);
        DomainUtils.createAndPersistOffer("OFFER_01", store, "N+M", jsonSpec);

        Basket basket = createBasket("STORE_01"); // Empty basket
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should return empty if no items in basket");
    }

    /**
     * Tests the upsell calculation when the basket items do not match the target EANs.
     * <p>
     * Expectation: Returns empty collection.
     */
    @Test
    void testApply_NoMatchingItems() {
        setUpDatabase();
        // Arrange: Offer targets Product A
        String jsonSpec = createSpec(List.of("1111111111111"), 2, 1);
        DomainUtils.createAndPersistOffer("OFFER_01", store, "N+M", jsonSpec);

        // Basket contains Product B
        Basket.Item item = createItem("2222222222222", 5.0);
        Basket basket = createBasket("STORE_01", item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        evaluation.addAvailableToUpcell(evaluation.pickMerged(5.0, "2222222222222"));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should return empty if basket items do not match target EANs");
    }

    // --------------------------------------------------
    // Tests for NPlusMUpsellAdvantageApplication
    // --------------------------------------------------

    /**
     * Tests the {@link NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication#getType()} method.
     */
    @Test
    void testApplication_GetType() {
        // Arrange
        NPlusMUpsellAdvantageFactory.UpsellSuggestion suggestion =
                new NPlusMUpsellAdvantageFactory.UpsellSuggestion("EAN_TEST", 2.5, "OFFER_X");
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                new NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication("OFFER_X", suggestion);

        // Act
        String type = app.getType();

        // Assert
        assertTrue(type.contains("Upsell N+M"));
        assertTrue(type.contains("OFFER_X"));
        assertTrue(type.contains("2,50"));
        assertTrue(type.contains("EAN_TEST"));
    }

    /**
     * Tests the {@link NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication#getOffer()} method.
     */
    @Test
    void testApplication_GetOffer() {
        // Arrange
        NPlusMUpsellAdvantageFactory.UpsellSuggestion suggestion =
                new NPlusMUpsellAdvantageFactory.UpsellSuggestion("EAN", 1.0, "CODE");
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                new NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication("CODE", suggestion);

        // Act
        String offer = app.getOffer();

        // Assert
        assertEquals(app.getType(), offer);
    }

    /**
     * Tests the {@link NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication#getOfferApplication()} method.
     */
    @Test
    void testApplication_GetOfferApplication() {
        // Arrange
        NPlusMUpsellAdvantageFactory.UpsellSuggestion suggestion =
                new NPlusMUpsellAdvantageFactory.UpsellSuggestion("EAN", 1.0, "CODE");
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                new NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication("CODE", suggestion);

        // Act & Assert
        assertNull(app.getOfferApplication(), "Should return null as this is a suggestion for a potential offer");
    }

    /**
     * Tests the {@link NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplier#isApplicable(OfferApplier)} method.
     */
    @Test
    void testApplier_IsApplicable() {
        // Arrange
        NPlusMUpsellAdvantageFactory.NPlusMOfferConfig config =
                new NPlusMUpsellAdvantageFactory.NPlusMOfferConfig("CODE", Collections.emptySet(), 1, 0);
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplier applier =
                new NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplier(config, store);

        // Act & Assert
        assertFalse(applier.isApplicable(null), "Should always return false");
    }

    /**
     * Tests the {@link NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplier#getEfficiencyScore()} method.
     */
    @Test
    void testApplier_GetEfficiencyScore() {
        // Arrange
        NPlusMUpsellAdvantageFactory.NPlusMOfferConfig config =
                new NPlusMUpsellAdvantageFactory.NPlusMOfferConfig("CODE", Collections.emptySet(), 1, 0);
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplier applier =
                new NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplier(config, store);

        // Act & Assert
        assertEquals(-100.0, applier.getEfficiencyScore(), 0.001, "Efficiency score should be -100.0");
    }

    /**
     * Tests the upsell calculation when the basket is missing one item to complete a bundle.
     * <p>
     * Scenario: Offer "2+1" (Bundle size 3). Basket has 2 items.
     * Expectation: Suggests adding 1.0 quantity.
     */
    @Test
    void testApply_MissingOneItem() {
        setUpDatabase();
        // Arrange
        String jsonSpec = createSpec(List.of("1111111111111"), 2, 1); // 2+1 (Size 3)
        DomainUtils.createAndPersistOffer("OFFER_01", store, "N+M", jsonSpec);

        // Basket has 2 items.
        // Calculation: targetBundles = ceil(2/3) = 1. neededQty = (1*3) - 2 = 1.
        Basket.Item item = createItem("1111111111111", 2.0);
        Basket basket = createBasket("STORE_01", item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Fix: Add to availableToUpcell (simulating standard flow)
        // but current Applier implementation looks in toEvaluate.
        evaluation.addAvailableToUpcell(evaluation.pickMerged(2.0, "1111111111111"));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        // THIS WILL FAIL if Applier looks in 'toEvaluate' instead of 'availableToUpcell'.
        assertEquals(1, apps.size());
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                (NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication) apps.iterator().next();

        assertEquals("1111111111111", app.getSuggestion().ean);
        assertEquals(1.0, app.getSuggestion().quantity, 0.001, "Need 1 more item to complete bundle of 3");
    }

    /**
     * Tests the upsell calculation when multiple products are targeted.
     * <p>
     * Scenario: Offer targets Product A (Expensive) and Product B (Cheap).
     * Basket has partial quantity.
     * Expectation: Suggests the CHEAPEST product (Product B).
     */
    @Test
    void testApply_MultipleTargets_SuggestsCheapest() {
        setUpDatabase();
        // Arrange: Offer 2+1 (Size 3) on both A and B
        String jsonSpec = createSpec(List.of("1111111111111", "2222222222222"), 2, 1);
        DomainUtils.createAndPersistOffer("MULTI_TARGET", store, "N+M", jsonSpec);

        // Basket has 2 items total (1 of each).
        // Calculation: targetBundles = ceil(2/3) = 1. neededQty = (1*3) - 2 = 1.
        Basket.Item itemA = createItem("1111111111111", 1.0);
        Basket.Item itemB = createItem("2222222222222", 1.0);
        Basket basket = createBasket("STORE_01", itemA, itemB);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        evaluation.addAvailableToUpcell(evaluation.pickMerged(1.0, "1111111111111"));
        evaluation.addAvailableToUpcell(evaluation.pickMerged(1.0, "2222222222222"));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size());
        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                (NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication) apps.iterator().next();

        // Verify Quantity Suggested
        assertEquals(1.0, app.getSuggestion().quantity, 0.001, "Need 1 more item to complete bundle of 3");

        // Verify Product Choice
        // Should suggest Product B (EAN 222...) because it is cheaper (1.00 vs 10.00)
        assertEquals("2222222222222", app.getSuggestion().ean, "Should suggest the cheapest product to complete the deal");
    }

    /**
     * Tests the fallback logic in {@code findCheapestTargetEan} when NO products have prices.
     * <p>
     * Scenario: Target products exist but have no prices. Basket has partial quantity.
     * Expectation: Returns the first EAN in the set (fallback iterator logic).
     */
    @Test
    void testFindCheapestTargetEan_Fallback_WhenNoPricesAtAll() {
        setUpDatabase();
        // Arrange: Product D and E have no price
        Product productD = DomainUtils.createAndPersistProduct("4444444444444", "Product D", ProductType.UNIT);
        Product productE = DomainUtils.createAndPersistProduct("5555555555555", "Product E", ProductType.UNIT);

        String jsonSpec = createSpec(List.of("4444444444444", "5555555555555"), 2, 1); // Size 3
        DomainUtils.createAndPersistOffer("OFFER_NO_PRICES", store, "N+M", jsonSpec);

        // IMPORTANT: Basket must contain at least ONE target item to trigger the calculation logic.
        // Calculation: targetBundles = ceil(1/3) = 1. neededQty = (1*3) - 1 = 2.
        Basket.Item item = createItem("4444444444444", 1.0);
        Basket basket = createBasket("STORE_01", item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        evaluation.addAvailableToUpcell(evaluation.pickMerged(1.0, "4444444444444"));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size(), "Should generate suggestion even if prices are missing");

        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                (NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication) apps.iterator().next();

        // Verify Quantity
        assertEquals(2.0, app.getSuggestion().quantity, 0.001, "Need 2 items to complete bundle of 3");

        // Verify Product Choice (Fallback to first in set)
        String suggestedEan = app.getSuggestion().ean;
        assertTrue(suggestedEan.equals("4444444444444") || suggestedEan.equals("5555555555555"),
                "Should return first EAN as fallback when no prices exist");
    }

    /**
     * Tests the fallback logic in {@code findCheapestTargetEan} when a target EAN does not exist in the database.
     * <p>
     * Scenario: Offer targets a valid Product A and an invalid EAN (Ghost).
     * Expectation: The logic skips the null product and falls back to the next available target.
     */
    @Test
    void testFindCheapestTargetEan_Fallback_WhenProductIsNull() {
        setUpDatabase();
        // Arrange: Offer targets Product A (Valid, Price 10.00) and a Ghost EAN (Does not exist in DB)
        String ghostEan = "9999999999999";
        String jsonSpec = createSpec(List.of("1111111111111", ghostEan), 2, 1); // Size 3
        DomainUtils.createAndPersistOffer("OFFER_GHOST", store, "N+M", jsonSpec);

        // Basket has 1 item (The valid one)
        // Calculation: targetBundles = ceil(1/3) = 1. neededQty = (1*3) - 1 = 2.
        Basket.Item item = createItem("1111111111111", 1.0);
        Basket basket = createBasket("STORE_01", item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        evaluation.feedFrom(basket);
        evaluation.addAvailableToUpcell(evaluation.pickMerged(1.0, "1111111111111"));

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size(), "Should generate suggestion even if one target product is missing in DB");

        NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication app =
                (NPlusMUpsellAdvantageFactory.NPlusMUpsellAdvantageApplication) apps.iterator().next();

        // Verify Quantity
        assertEquals(2.0, app.getSuggestion().quantity, 0.001, "Need 2 items to complete bundle of 3");

        // Verify Product Choice
        // Since Ghost EAN has no product (null), it is skipped.
        // Since Product A has a price, it is selected.
        assertEquals("1111111111111", app.getSuggestion().ean, "Should suggest the valid product as the ghost one is null");
    }

}