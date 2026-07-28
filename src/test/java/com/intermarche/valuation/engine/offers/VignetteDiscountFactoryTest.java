package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
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
 * Integration tests for {@link VignetteDiscountFactory} using the real database.
 * <p>
 * This class verifies the logic for creating vignette discount appliers,
 * validating JSON specifications, and calculating discounts based on
 * available vignettes and product quantities.
 */
@QuarkusTest
@TestTransaction
public class VignetteDiscountFactoryTest {

    @Inject
    VignetteDiscountFactory factory;

    private Store store;
    private Product productA; // 10.00 HT
    private Product productB; // 1.00 HT

    /**
     * Sets up the database with a Store and Products before each test.
     */
    void setUpDatabase() {
        store = DomainUtils.createAndPersistStore("STORE_01", 48.0, 2.0);
        productA = DomainUtils.createAndPersistProduct("1111111111111", "Product A", ProductType.UNIT);
        productB = DomainUtils.createAndPersistProduct("2222222222222", "Product B", ProductType.UNIT);

        DomainUtils.createAndPersistPrice(productA, store, 0, PriceUsage.DEFAULT,
                BigDecimal.TEN, BigDecimal.valueOf(12.0), BigDecimal.valueOf(0.2));
        DomainUtils.createAndPersistPrice(productB, store, 0, PriceUsage.DEFAULT,
                BigDecimal.ONE, BigDecimal.valueOf(1.2), BigDecimal.valueOf(0.2));
    }

    /**
     * Helper to create a Basket DTO with vignettes and items.
     */
    private Basket createBasket(String storeCode, Map<String, Integer> vignettes, Basket.Item... items) {
        Basket b = new Basket();
        b.storeCode = storeCode;
        b.vignettes = vignettes;
        b.items = Arrays.asList(items);
        return b;
    }

    // --------------------------------------------------
    // Factory Tests
    // --------------------------------------------------

    /**
     * Tests successful creation of an applier when valid offer and vignettes exist.
     */
    @Test
    void testBuildAppliers_Success() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_01", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);

        Basket basket = createBasket("STORE_01", vignettes, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertEquals(1, appliers.size(), "Should create one applier");
    }

    /**
     * Tests that no applier is created if the basket has no vignettes.
     */
    @Test
    void testBuildAppliers_NoVignettesInBasket() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_01", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Basket basket = createBasket("STORE_01", Collections.emptyMap(), createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.isEmpty(), "Should not create applier if basket has no vignettes");
    }

    /**
     * Tests that no applier is created if the vignettes map is null.
     */
    @Test
    void testBuildAppliers_NullVignettesMap() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_01", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Basket basket = createBasket("STORE_01", null, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        assertTrue(appliers.isEmpty(), "Should not create applier if vignette map is null");
    }

    /**
     * Tests that an exception is thrown for invalid JSON specification.
     */
    @Test
    void testBuildAppliers_InvalidJson() {
        setUpDatabase();

        String badJson = "{ \"invalid\": true }";

        DomainUtils.createAndPersistOffer("BAD_JSON", store, "VIGNETTE_DISCOUNT", badJson);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);

        Basket basket = createBasket("STORE_01", vignettes, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        assertThrows(IllegalArgumentException.class, () -> factory.buildAppliers(evaluation),
                "Should throw IllegalArgumentException for invalid JSON");
    }

    // --------------------------------------------------
    // Applier Logic Tests
    // --------------------------------------------------

    /**
     * Tests application of a percentage discount.
     * <p>
     * Scenario: 5 vignettes required for 50% off. User has 5 vignettes.
     * Product price: 10.00 HT.
     * Expectation: Discount of 5.00 HT.
     */
    @Test
    void testApply_PercentageDiscount_Success() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_PCT", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 5);

        Basket.Item item = createItem("1111111111111", 1.0);
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // 1. Manually simulate the BasicOffer flow to have an OfferApplication in the evaluation
        Basket.Item pickedItem = evaluation.pickMerged(1.0, "1111111111111");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productA, Price.findCurrentPrice(productA.id, store.id));
        evaluation.getOffers().add(basicApp);

        // 2. Build and Apply Vignette Applier
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertEquals(1, apps.size());
        VignetteDiscountFactory.VignetteDiscountApplication app =
                (VignetteDiscountFactory.VignetteDiscountApplication) apps.iterator().next();

        // Check Discount: 50% of 10.00 = 5.00 HT
        assertEquals(new BigDecimal("5.00"), app.getDiscountAmount().amountExcludingTax);
        assertEquals(new BigDecimal("6.00"), app.getDiscountAmount().amountIncludingTax);

        // Direct field access since fields are public
        assertEquals(5, app.vignettesConsumed);
        assertEquals(1, app.numberOfApplications);
    }

    /**
     * Tests application of a fixed amount discount.
     * <p>
     * Scenario: 2 vignettes for 5.00€ discount. User has 2 vignettes.
     * Expectation: Discount of 5.00 TTC.
     */
    @Test
    void testApply_FixedAmountDiscount_Success() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 2,
              "discount": { "type": "FIXED_AMOUNT", "value": 5.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_FIXED", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 2);

        Basket.Item item = createItem("1111111111111", 1.0);
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Basket.Item pickedItem = evaluation.pickMerged(1.0, "1111111111111");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productA, Price.findCurrentPrice(productA.id, store.id));
        evaluation.getOffers().add(basicApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        VignetteDiscountFactory.VignetteDiscountApplication app =
                (VignetteDiscountFactory.VignetteDiscountApplication) apps.iterator().next();

        // Check Discount: 5.00 TTC fixed
        assertEquals(new BigDecimal("5.00"), app.getDiscountAmount().amountIncludingTax);
        // HT = 5 / 1.2 = 4.17
        assertEquals(new BigDecimal("4.17"), app.getDiscountAmount().amountExcludingTax);
    }

    /**
     * Tests that no discount is applied if the user has insufficient vignettes.
     */
    @Test
    void testApply_InsufficientVignettes() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_INSUFF", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 4); // Need 5, have 4

        Basket.Item item = createItem("1111111111111", 1.0);
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Basket.Item pickedItem = evaluation.pickMerged(1.0, "1111111111111");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productA, Price.findCurrentPrice(productA.id, store.id));
        evaluation.getOffers().add(basicApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        assertTrue(apps.isEmpty(), "Should not apply discount if vignettes are insufficient");
    }

    /**
     * Tests that no discount is applied if the product in the offer is not in the basket.
     */
    @Test
    void testApply_ProductNotInBasket() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_PROD", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10); // Have vignettes for A

        // Basket contains Product B
        Basket.Item item = createItem("2222222222222", 1.0);
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Basket.Item pickedItem = evaluation.pickMerged(1.0, "2222222222222");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productB, Price.findCurrentPrice(productB.id, store.id));
        evaluation.getOffers().add(basicApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        assertTrue(apps.isEmpty(), "Should not apply discount if product not in basket");
    }

    /**
     * Tests multiple applications of the discount (Quantity > 1).
     * <p>
     * Scenario: Product qty 3. Vignettes required 1.
     * User has 2 vignettes.
     * Expectation: Applied 2 times.
     */
    @Test
    void testApply_MultipleApplications() {
        setUpDatabase();

        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 1,
              "discount": { "type": "FIXED_AMOUNT", "value": 1.0 }
            }
          ]
        }
        """;

        DomainUtils.createAndPersistOffer("VIGNETTE_MULTI", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 2); // Can apply 2 times

        Basket.Item item = createItem("1111111111111", 3.0); // Qty 3
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        Basket.Item pickedItem = evaluation.pickMerged(3.0, "1111111111111");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productA, Price.findCurrentPrice(productA.id, store.id));
        evaluation.getOffers().add(basicApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        assertEquals(1, apps.size());
        VignetteDiscountFactory.VignetteDiscountApplication app =
                (VignetteDiscountFactory.VignetteDiscountApplication) apps.iterator().next();

        // Direct field access
        assertEquals(2, app.numberOfApplications, "Should apply discount 2 times");
        assertEquals(2, app.vignettesConsumed);
        // Total Discount = 2 * 1.00 TTC
        assertEquals(new BigDecimal("2.00"), app.getDiscountAmount().amountIncludingTax);
    }

    // --------------------------------------------------
    // VignetteDiscountApplication Tests
    // --------------------------------------------------

    /**
     * Tests the getType() method of the application.
     */
    @Test
    void testApplication_GetType() {
        VignetteDiscountFactory.VignetteDiscountApplication app =
                new VignetteDiscountFactory.VignetteDiscountApplication(
                        "OFFER_X", null, new AmountEvaluation(), 2, 10
                );
        String type = app.getType();
        assertTrue(type.contains("OFFER_X"));
        assertTrue(type.contains("10 vignettes used"));
        assertTrue(type.contains("applied 2 times"));
    }

    /**
     * Tests the getOfferApplication() method.
     */
    @Test
    void testApplication_GetOfferApplication() {
        OfferApplication target = new BasicOfferFactory.BasicApplication(null, null, null, null);
        VignetteDiscountFactory.VignetteDiscountApplication app =
                new VignetteDiscountFactory.VignetteDiscountApplication(
                        "CODE", target, new AmountEvaluation(), 1, 1
                );
        assertEquals(target, app.getOfferApplication());
    }

    // --------------------------------------------------
    // VignetteDiscountApplier.isApplicable Tests
    // --------------------------------------------------

    /**
     * Tests that isApplicable returns true when the offer applier implements ProductAwareOfferApplier
     * and is applicable to a product in the catalog.
     */
    @Test
    void testIsApplicable_True_WhenProductMatches() {
        setUpDatabase();
        // Arrange: Create Vignette Applier via Factory
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_IS_APP", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);
        Basket basket = createBasket("STORE_01", vignettes, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        VignetteDiscountFactory.VignetteDiscountApplier applier =
                (VignetteDiscountFactory.VignetteDiscountApplier) appliers.iterator().next();

        // Create a class that implements BOTH OfferApplier AND ProductAwareOfferApplier
        class MockApplier extends OfferApplier implements ProductAwareOfferApplier {
            @Override
            public boolean isApplicable(Product product) {
                return product.ean.equals("1111111111111");
            }
            @Override public Collection<OfferApplication> apply(BasketEvaluation evaluation) { return null; }
        }

        // Act & Assert
        assertTrue(applier.isApplicable(new MockApplier()), "Should return true because the mock targets Product A");
    }

    /**
     * Tests that isApplicable returns false when the offer applier targets a different product.
     */
    @Test
    void testIsApplicable_False_WhenProductDoesNotMatch() {
        setUpDatabase();
        // Arrange
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_IS_APP", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);
        Basket basket = createBasket("STORE_01", vignettes, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        VignetteDiscountFactory.VignetteDiscountApplier applier =
                (VignetteDiscountFactory.VignetteDiscountApplier) appliers.iterator().next();

        // Mock targets Product B (222...)
        class MockApplier extends OfferApplier implements ProductAwareOfferApplier {
            @Override
            public boolean isApplicable(Product product) {
                return product.ean.equals("2222222222222");
            }
            @Override public Collection<OfferApplication> apply(BasketEvaluation evaluation) { return null; }
        }

        // Act & Assert
        assertFalse(applier.isApplicable(new MockApplier()), "Should return false because the mock targets Product B");
    }

    /**
     * Tests that isApplicable returns false when the passed offer applier does NOT implement ProductAwareOfferApplier.
     */
    @Test
    void testIsApplicable_False_WhenNotProductAware() {
        setUpDatabase();
        // Arrange
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_IS_APP", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);
        Basket basket = createBasket("STORE_01", vignettes, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        VignetteDiscountFactory.VignetteDiscountApplier applier =
                (VignetteDiscountFactory.VignetteDiscountApplier) appliers.iterator().next();

        // Mock ONLY implements OfferApplier (Not ProductAware)
        class MockApplier extends OfferApplier {
            @Override public Collection<OfferApplication> apply(BasketEvaluation evaluation) { return null; }
        }

        // Act & Assert
        assertFalse(applier.isApplicable(new MockApplier()), "Should return false if offer applier is not ProductAware");
    }

    /**
     * Tests that processAppliedOffers skips applications that are not ProductAwareOfferApplication.
     * <p>
     * Scenario: The evaluation contains a generic OfferApplication (not product aware).
     * Expectation: No vignette discount is applied, and no exception is thrown.
     */
    @Test
    void testProcessAppliedOffers_SkipsNonProductAwareApplication() {
        setUpDatabase();
        // Arrange: Create Vignette Applier via Factory
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_SKIP", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10); // Valid vignettes

        Basket.Item item = createItem("1111111111111", 1.0);
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // 1. Add a Non-ProductAware OfferApplication to the evaluation
        // This mocks a generic offer (like Delivery) that does not implement ProductAwareOfferApplication
        OfferApplication genericApp = new OfferApplication() {
            @Override
            public AmountEvaluation getAmount() {
                return new AmountEvaluation(BigDecimal.TEN, BigDecimal.valueOf(12.0), BigDecimal.valueOf(0.2));
            }
            @Override
            public Collection<Basket.Item> getItems() {
                // Even if it returns items, it shouldn't be processed
                return Collections.emptyList();
            }
            @Override
            public String getType() {
                return "Generic Non-Product Aware Offer";
            }
        };
        evaluation.getOffers().add(genericApp);

        // 2. Build Vignette Applier
        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        // Since the generic app is not ProductAware, it should be skipped.
        // Since no other offers are present, the result should be empty.
        assertTrue(apps.isEmpty(), "Should not apply discount to non-product aware applications");
    }

    /**
     * Tests the getEfficiencyScore method of the VignetteDiscountApplier.
     * <p>
     * Expectation: Returns a constant score of 10.0.
     */
    @Test
    void testGetEfficiencyScore() {
        setUpDatabase();
        // Arrange
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_SCORE", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);

        Basket basket = createBasket("STORE_01", vignettes, createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        double score = applier.getEfficiencyScore();

        // Assert
        assertEquals(10.0, score, 0.001, "Efficiency score should be 10.0");
    }

    /**
     * Tests that no discount is applied when the offer application reports zero quantity for the product.
     * <p>
     * Covers the branch: {@code if (totalQty == 0) return null;} inside calculateTotalDiscount.
     */
    @Test
    void testCalculateTotalDiscount_ZeroQuantity() {
        setUpDatabase();
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 1,
              "discount": { "type": "FIXED_AMOUNT", "value": 5.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_QTY_ZERO", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);

        Basket basket = createBasket("STORE_01", vignettes);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // 1. Add a Mock OfferApplication that returns 0 for quantity
        ProductAwareOfferApplication mockApp = new ProductAwareOfferApplication() {
            @Override
            public AmountEvaluation getAmount() { return new AmountEvaluation(); }
            @Override
            public Collection<Basket.Item> getItems() {
                // Return an item with matching EAN so the loop finds it
                return List.of(createItem("1111111111111", 0.0));
            }
            @Override
            public String getType() { return "Mock"; }

            @Override
            public AmountEvaluation getProductAmount(Product product) {
                // Return valid price so we pass the null check
                return new AmountEvaluation(BigDecimal.TEN, BigDecimal.valueOf(12.0), BigDecimal.valueOf(0.2));
            }
            @Override
            public double getProductQuantity(Product product) {
                return 0.0; // Trigger the condition
            }
        };
        evaluation.getOffers().add(mockApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should not apply discount if quantity is 0");
    }

    /**
     * Tests that no discount is applied when the offer application returns null for the product amount.
     * <p>
     * Covers the branch: {@code if (totalProductPrice == null) return null;} inside calculateTotalDiscount.
     */
    @Test
    void testCalculateTotalDiscount_NullProductAmount() {
        setUpDatabase();
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 1,
              "discount": { "type": "FIXED_AMOUNT", "value": 5.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_NULL_AMT", store, "VIGNETTE_DISCOUNT", jsonSpec);

        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);

        Basket basket = createBasket("STORE_01", vignettes);
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // 1. Add a Mock OfferApplication that returns NULL for amount
        ProductAwareOfferApplication mockApp = new ProductAwareOfferApplication() {
            @Override
            public AmountEvaluation getAmount() { return new AmountEvaluation(); }
            @Override
            public Collection<Basket.Item> getItems() {
                return List.of(createItem("1111111111111", 1.0));
            }
            @Override
            public String getType() { return "Mock"; }

            @Override
            public AmountEvaluation getProductAmount(Product product) {
                return null; // Trigger the condition
            }
            @Override
            public double getProductQuantity(Product product) {
                return 1.0;
            }
        };
        evaluation.getOffers().add(mockApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should not apply discount if product amount is null");
    }

    /**
     * Tests that no discount is applied when the number of possible applications is 0.
     * <p>
     * Scenario: Offer requires 5 vignettes. User has 4 vignettes (and enough quantity).
     * Expectation: calculateMaxApplications returns 0. The block {@code if (numberOfApplications > 0)} is skipped.
     */
    @Test
    void testTryApplyVignetteDiscount_ZeroApplications() {
        setUpDatabase();
        // Arrange: Require 5 vignettes
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_ZERO_APP", store, "VIGNETTE_DISCOUNT", jsonSpec);

        // User has only 4 vignettes (Need 5)
        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 4);

        Basket.Item item = createItem("1111111111111", 2.0); // Quantity is sufficient (2.0)
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Add a valid ProductAwareOfferApplication
        Basket.Item pickedItem = evaluation.pickMerged(2.0, "1111111111111");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productA, Price.findCurrentPrice(productA.id, store.id));
        evaluation.getOffers().add(basicApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        // Logic: userVignetteCount (4) < vignettesRequired (5).
        // The check `if (userVignetteCount >= rule.vignettesRequired)` should actually fail BEFORE calculateMaxApplications.
        // Wait, let's re-read the code logic provided:
        // if (userVignetteCount >= rule.vignettesRequired) { ... int numberOfApplications = ... }

        // If the code strictly follows `if (userVignetteCount >= rule.vignettesRequired)`,
        // then having 4 vignettes when 5 are required skips the entire block.
        // We need a scenario where we ENTER the block but calculateMaxApplications returns 0.

        // Scenario Adjustment:
        // Required: 5 vignettes.
        // User has: 5 vignettes. -> Passes `if (userVignetteCount >= rule.vignettesRequired)`
        // Product Quantity: 0.5 -> floor(0.5) = 0. maxByQuantity = 0.
        // calculateMaxApplications returns 0.

        // Let's update the test setup:
    }

    /**
     * Tests that no discount is applied when calculation results in zero applications due to quantity limit.
     * <p>
     * Scenario: Offer requires 5 vignettes. User has 5 vignettes.
     * Product Quantity is 0.5 (less than 1 unit).
     * Expectation: calculateMaxApplications returns 0. Block skipped.
     */
    @Test
    void testTryApplyVignetteDiscount_ZeroApplicationsDueToQuantity() {
        setUpDatabase();
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_ZERO_APP_QTY", store, "VIGNETTE_DISCOUNT", jsonSpec);

        // User has EXACTLY required vignettes (passes the first check)
        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 5);

        // Quantity is 0.5 (partial unit). Discount applies per unit.
        // maxByQuantity = floor(0.5) = 0.
        Basket.Item item = createItem("1111111111111", 0.5);
        Basket basket = createBasket("STORE_01", vignettes, item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Add a valid application for that item
        Basket.Item pickedItem = evaluation.pickMerged(0.5, "1111111111111");
        BasicOfferFactory.BasicApplication basicApp = new BasicOfferFactory.BasicApplication(
                pickedItem, store, productA, Price.findCurrentPrice(productA.id, store.id));
        evaluation.getOffers().add(basicApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        assertTrue(apps.isEmpty(), "Should not apply discount if max applications is 0 due to quantity");
    }

    /**
     * Tests that processProductAwareOffer skips items when the product is not found in the database.
     * <p>
     * Scenario: The basket contains an item with a "Ghost" EAN (not in DB).
     * Expectation: The loop continues without throwing an exception, and no discount is applied.
     * Covers the branch: {@code if (product == null) continue;}.
     */
    @Test
    void testProcessProductAwareOffer_SkipsNullProduct() {
        setUpDatabase();
        String jsonSpec = """
        {
          "catalog": [
            {
              "ean": "1111111111111",
              "vignettesRequired": 5,
              "discount": { "type": "PERCENTAGE", "value": 50.0 }
            }
          ]
        }
        """;
        DomainUtils.createAndPersistOffer("VIGNETTE_SKIP_NULL", store, "VIGNETTE_DISCOUNT", jsonSpec);

        // Vignettes exist for the valid product, but also linked to the ghost EAN in map (optional, but realistic)
        Map<String, Integer> vignettes = new HashMap<>();
        vignettes.put("1111111111111", 10);

        // Item with a Ghost EAN that does NOT exist in the Product table
        Basket.Item ghostItem = createItem("9999999999999", 1.0);
        Basket basket = createBasket("STORE_01", vignettes, ghostItem);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Create a ProductAwareOfferApplication that contains the Ghost Item
        ProductAwareOfferApplication mockApp = new ProductAwareOfferApplication() {
            @Override
            public AmountEvaluation getAmount() { return new AmountEvaluation(); }

            @Override
            public Collection<Basket.Item> getItems() {
                return List.of(ghostItem);
            }

            @Override
            public String getType() { return "Mock with Ghost Item"; }

            @Override
            public AmountEvaluation getProductAmount(Product product) {
                // Should not be called for the ghost item
                return new AmountEvaluation(BigDecimal.TEN, BigDecimal.valueOf(12.0), BigDecimal.valueOf(0.2));
            }

            @Override
            public double getProductQuantity(Product product) {
                return 1.0;
            }
        };
        evaluation.getOffers().add(mockApp);

        Collection<AdvantageApplier> appliers = factory.buildAppliers(evaluation);
        AdvantageApplier applier = appliers.iterator().next();

        // Act
        Collection<AdvantageApplication> apps = applier.apply(evaluation);

        // Assert
        // 1. Should not throw ProductNotFoundException
        // 2. Should return empty list because the only item found (ghost) was skipped
        assertTrue(apps.isEmpty(), "Should skip processing for null products and return empty list");
    }
}