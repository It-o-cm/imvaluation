package com.intermarche.valuation.engine.offers;

import com.intermarche.valuation.domain.*;
import com.intermarche.valuation.domain.util.ReflectionUtils;
import com.intermarche.valuation.engine.*;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.intermarche.valuation.domain.util.DomainUtils.createItem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BasicOfferFactory} using real database.
 * <p>
 * This test uses {@link io.quarkus.test.junit.QuarkusTest} and {@link TestTransaction}
 * to interact with a real database. Data is persisted before each test and rolled back
 * automatically after the test finishes, ensuring test isolation.
 */
@QuarkusTest
@TestTransaction
public class BasicOfferFactoryTest {

    @Inject
    BasicOfferFactory factory;

    private Store store;
    private Product product1;
    private Product product2;
    private Price defaultPrice;
    private Price discountPrice;

    /**
     * Sets up the database with necessary entities before each test.
     * <p>
     * Persists Store, Products, and Prices so that {@link BasketEvaluation}
     * can actually fetch them via Panache.
     */
    void setUpDatabase() {
        // 1. Create and Persist Store
        store = new Store();
        store.code = "STORE_01";
        store.name = "Test Store";

        // FIX: Initialize the embedded Address to avoid NullPointerException in @PrePersist
        // Store.getChecksum() calls address.getChecksum(), so address must not be null.
        store.address = new Adresse();
        store.persist();

        // 2. Create and Persist Products
        product1 = DomainUtils.createAndPersistProduct("1111111111111", "Product 1", ProductType.UNIT);
        product2 = DomainUtils.createAndPersistProduct("2222222222222","Product 2", ProductType.UNIT);
        // 3. Create and Persist Prices (linked to Product and Store)
        defaultPrice = DomainUtils.createAndPersistPrice(product1, store, 1, PriceUsage.DEFAULT,
                BigDecimal.TEN, BigDecimal.valueOf(12.0), BigDecimal.valueOf(0.2));
        discountPrice = DomainUtils.createAndPersistPrice(product1, store,1, PriceUsage.BASE_FOR_DISCOUNT,
                BigDecimal.valueOf(11.0), BigDecimal.valueOf(13.2), BigDecimal.valueOf(0.2));
        // Persist another price for product2
        DomainUtils.createAndPersistPrice(product2, store, 0, PriceUsage.DEFAULT,
                BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.2), BigDecimal.valueOf(0.2));
        DomainUtils.createAndPersistPrice(product2, store, 0, PriceUsage.BASE_FOR_DISCOUNT,
                BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.2), BigDecimal.valueOf(0.2));

        Panache.getEntityManager().flush();
    }

    /**
     * Tests {@link BasicOfferFactory#buildAppliers(BasketEvaluation)} ensuring that one applier
     * is created for each unique EAN found in the basket.
     * <p>
     * Verifies that the factory correctly queries the DB and maps entities.
     */
    @Test
    void testBuildAppliers_CreatesOneApplierPerUniqueEan() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1111111111111", 1.0),
                createItem("2222222222222", 2.0)
        );
        // Create BasketEvaluation (this triggers DB lookups inside Item.getPrice())
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        // Assert
        assertEquals(2, appliers.size(), "Should create 2 appliers for 2 different EANs");
    }

    /**
     * Tests {@link BasicOfferFactory#buildAppliers(BasketEvaluation)} ensuring that items
     * sharing the same EAN result in a single applier (deduplication).
     * <p>
     * Verifies that the Set logic in the factory works correctly with real DB entities.
     */
    @Test
    void testBuildAppliers_DeduplicatesItemsWithSameEan() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1111111111111", 1.0),
                createItem("1111111111111", 1.0) // Same EAN
        );
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        // Assert
        assertEquals(1, appliers.size(), "Should create only 1 applier for duplicate EANs");
        // Verify via reflection that the applier targets the correct product
        OfferApplier applier = appliers.iterator().next();
        if (applier instanceof BasicOfferFactory.BasicOfferApplier) {
            Product targetProduct = ReflectionUtils.getField(applier, "product");
            assertEquals("1111111111111", targetProduct.ean);
        }
    }

    /**
     * Tests the application logic of the generated {@link BasicOfferFactory.BasicOfferApplier}.
     * <p>
     * Verifies that the applier successfully picks the item from the evaluation context,
     * consumes it, and generates a valid application based on real DB prices.
     */
    @Test
    void testBasicOfferApplier_Apply_PicksItemAndCalculatesPrice() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1111111111111", 2.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();
        // Act
        Collection<OfferApplication> applications = applier.apply(evaluation);
        // Assert
        assertFalse(applications.isEmpty(), "Application list should not be empty");
        // Verify that the item was picked (removed from toEvaluate)
        assertTrue(evaluation.getToEvaluate().isEmpty(), "Item should be removed from toEvaluate");
        // Verify application details via reflection
        BasicOfferFactory.BasicApplication app = (BasicOfferFactory.BasicApplication) applications.iterator().next();
        assertNotNull(ReflectionUtils.getField(app, "price"), "Price should be set");
        // Verify calculation: Qty 2 * Price 10.00 = 20.00 HT
        AmountEvaluation amount = app.getAmount();
        assertEquals(new BigDecimal("20.00"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("24.00"), amount.amountIncludingTax);
    }

    /**
     * Tests {@link BasicOfferFactory#buildAppliers(BasketEvaluation)} ensuring that items
     * with a null EAN are safely skipped.
     * <p>
     * Verifies that the guard condition {@code item.produceEan != null} prevents
     * the factory from attempting to fetch prices or products for invalid items.
     */
    @Test
    void testBuildAppliers_SkipsItemWithNullEan() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(
                createItem("1111111111111", 1.0),
                createItem(null, 1.0), // Item with Null EAN
                createItem("2222222222222", 2.0)
        );
        // Create BasketEvaluation (feedFrom handles null EAN in map key)
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Act
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        // Assert
        // 3 items were in the basket, but one has a null EAN.
        // The factory should only create 2 appliers.
        assertEquals(2, appliers.size(), "Should create 2 appliers, skipping the item with null EAN");
    }


    /**
     * Tests {@link BasicOfferFactory.BasicOfferApplier#apply(BasketEvaluation)} ensuring that
     * Reference Price is used when discount appliers are registered.
     * <p>
     * Condition tested: {@code this.getDiscountAppliers().isEmpty()} is {@code false}.
     * Uses a real implementation of {@code AdvantageApplier} instead of a mock.
     */
    @Test
    void testBasicOfferApplier_Apply_UsesRefPrice_WhenDiscountsExist() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        // Create applier manually using real entities
        BasicOfferFactory.BasicOfferApplier applier =
                new BasicOfferFactory.BasicOfferApplier(store, product1, defaultPrice, discountPrice);
        // Register a real discount applier to trigger the logic branch
        SimpleDiscountApplier realDiscount = new SimpleDiscountApplier();
        applier.registerDiscountApplier(realDiscount);
        // Act
        Collection<OfferApplication> applications = applier.apply(evaluation);
        // Assert
        assertFalse(applications.isEmpty());
        // Verify via reflection that REF price was used (not DEFAULT price)
        BasicOfferFactory.BasicApplication app = (BasicOfferFactory.BasicApplication) applications.iterator().next();
        Price usedPrice = ReflectionUtils.getField(app, "price");
        assertEquals(discountPrice, usedPrice, "Should use Reference Price when discount appliers exist");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicOfferApplier#apply(BasketEvaluation)} when the item
     * is not present in the evaluation map.
     * <p>
     * Condition tested: {@code if (availableItem != null)} is {@code false}.
     * This happens when the item has already been picked by another applier.
     */
    @Test
    void testBasicOfferApplier_Apply_ItemNotInToEvaluate() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(createItem("1111111111111", 1.0));
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();
        // Manually clear the map to simulate that the item was picked by another applier
        evaluation.getToEvaluate().clear();
        // Act
        Collection<OfferApplication> applications = applier.apply(evaluation);
        // Assert
        assertTrue(applications.isEmpty(), "Should return empty list if item is no longer available");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicOfferApplier#apply(BasketEvaluation)} when the item
     * quantity is null.
     * <p>
     * Condition tested: {@code if (pickedItem != null)} is {@code false}.
     * The {@code BasketEvaluation.pick()} method returns null if quantity is null.
     */
    @Test
    void testBasicOfferApplier_Apply_ItemWithNullQuantity() {
        setUpDatabase();
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        // Create item with null quantity
        Basket.Item item = createItem("1111111111111", null);
        basket.items = List.of(item);
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);
        Collection<OfferApplier> appliers = factory.buildAppliers(evaluation);
        OfferApplier applier = appliers.iterator().next();
        // Act
        Collection<OfferApplication> applications = applier.apply(evaluation);
        // Assert
        assertTrue(applications.isEmpty(), "Should return empty list if quantity is null (pick returns null)");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicOfferApplier#isApplicable(Product)}.
     * <p>
     * Verifies that the method returns false when the provided product has a different EAN.
     */
    @Test
    void testBasicOfferApplier_IsApplicable_ReturnsFalseForDifferentEan() {
        setUpDatabase();
        // Arrange
        // Create applier for product1 (already setup in @BeforeEach)
        BasicOfferFactory.BasicOfferApplier applier =
                new BasicOfferFactory.BasicOfferApplier(store, product1, defaultPrice, discountPrice);
        // Act
        boolean result = applier.isApplicable(product2); // product2 has EAN 2222222222222
        // Assert
        assertFalse(result, "Should return false for a different product EAN");
    }

    // --------------------------------------------------
    // Tests for BasicApplication (Getters)
    // --------------------------------------------------

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getItems()}.
     * <p>
     * Verifies that it returns a collection containing the single item.
     */
    @Test
    void testBasicApplication_GetItems_ReturnsItem() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 2.5);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        Collection<Basket.Item> result = app.getItems();
        // Assert
        assertEquals(1, result.size());
        assertEquals(item, result.iterator().next());
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getItems()} when item is null.
     * <p>
     * Verifies the guard condition {@code if (item == null)}.
     * Uses reflection to set the private 'item' field to null.
     */
    @Test
    void testBasicApplication_GetItems_NullItem() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 1.0);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Use reflection to simulate null item scenario
        ReflectionUtils.setField(app, "item", null);
        // Act
        Collection<Basket.Item> result = app.getItems();
        // Assert
        assertTrue(result.isEmpty(), "Should return empty list if item is null");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getType()}.
     * <p>
     * Verifies the string format representation of the application.
     */
    @Test
    void testBasicApplication_GetType() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 3.5);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        String type = app.getType();
        // Assert
        assertTrue(type.contains("Standard"));
        assertTrue(type.contains("1111111111111"));
        assertTrue(type.contains("3.5"));
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getProductAmount(Product)}.
     * <p>
     * Verifies that it returns the amount when the provided product EAN matches the item's EAN.
     */
    @Test
    void testBasicApplication_GetProductAmount_Match() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 1.0);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        AmountEvaluation amount = app.getProductAmount(product1);
        // Assert
        assertNotNull(amount);
        // 1.0 * 10.00 (DefaultPrice)
        assertEquals(new BigDecimal("10.00"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("12.00"), amount.amountIncludingTax);
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getProductAmount(Product)}.
     * <p>
     * Verifies that it returns null when the provided product EAN does not match.
     */
    @Test
    void testBasicApplication_GetProductAmount_NoMatch() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 1.0);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        AmountEvaluation amount = app.getProductAmount(product2); // product2 has different EAN
        // Assert
        assertNull(amount, "Should return null for non-matching product");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getProductQuantity(Product)}.
     * <p>
     * Verifies that it returns the item quantity when the provided product EAN matches.
     */
    @Test
    void testBasicApplication_GetProductQuantity_Match() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 4.5);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        double quantity = app.getProductQuantity(product1);
        // Assert
        assertEquals(4.5, quantity, "Should return the item quantity");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getProductQuantity(Product)}.
     * <p>
     * Verifies that it returns 0.0 when the provided product EAN does not match.
     */
    @Test
    void testBasicApplication_GetProductQuantity_NoMatch() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 2.0);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        double quantity = app.getProductQuantity(product2); // product2 has different EAN
        // Assert
        assertEquals(0.0, quantity, "Should return 0.0 for non-matching product");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getProductAmount(Product)} when product is null.
     * <p>
     * Condition tested: {@code product != null} is {@code false}.
     * Verifies that the method handles null input gracefully without throwing NullPointerException.
     */
    @Test
    void testBasicApplication_GetProductAmount_NullProduct() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 1.0);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        AmountEvaluation amount = app.getProductAmount(null);
        // Assert
        assertNull(amount, "Should return null when provided product is null");
    }

    /**
     * Tests {@link BasicOfferFactory.BasicApplication#getProductQuantity(Product)} when product is null.
     * <p>
     * Condition tested: {@code product != null} is {@code false}.
     * Verifies that the method returns 0.0 when provided product is null.
     */
    @Test
    void testBasicApplication_GetProductQuantity_NullProduct() {
        setUpDatabase();
        // Arrange
        Basket.Item item = createItem("1111111111111", 1.0);
        BasicOfferFactory.BasicApplication app = new BasicOfferFactory.BasicApplication(item, store, product1, defaultPrice);
        // Act
        double quantity = app.getProductQuantity(null);
        // Assert
        assertEquals(0.0, quantity, "Should return 0.0 when provided product is null");
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Simple real implementation of {@code AdvantageApplier} used for testing.
     * <p>
     * This replaces Mockito mocks to satisfy the "no mocks" requirement.
     */
    private static class SimpleDiscountApplier implements com.intermarche.valuation.engine.AdvantageApplier {

        @Override
        public boolean isApplicable(com.intermarche.valuation.engine.OfferApplier offerApplier) {
            return true; // Always applicable for testing purposes
        }

        @Override
        public Collection<com.intermarche.valuation.engine.AdvantageApplication> apply(
                com.intermarche.valuation.engine.BasketEvaluation basketEvaluation) {
            return Collections.emptyList(); // No actual discount logic needed
        }

        @Override
        public double getEfficiencyScore() {
            return 0.0; // Score is irrelevant for this test
        }
    }

}