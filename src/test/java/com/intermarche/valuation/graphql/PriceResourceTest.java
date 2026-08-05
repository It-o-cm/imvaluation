package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PriceResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class PriceResourceTest {

    @Inject
    PriceResource resource;

    /**
     * Sets up initial data for tests.
     */
    void setUp() {
        // Every @QuarkusTest class shares one database, and a class that ran before may have
        // committed rows of its own. Several tests below read "the first" entity or rely on
        // a code being unused, so the fixture starts from an empty set. Deletion follows the
        // reverse of the dependencies: prices reference stores and products, offers
        // reference stores and groups. The class runs under @TestTransaction, so this is
        // rolled back with the rest of the test.
        Price.deleteAll();
        ProductCategoryStorage.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();

        Product product = DomainUtils.createAndPersistProduct("PROD_01", "Product 01", ProductType.UNIT);
        Store store = DomainUtils.createAndPersistStore("STORE_01", 0.0, 0.0);
        createAndPersistPrice(product, store, PriceUsage.DEFAULT, 1, LocalDateTime.now());
    }

    /**
     * Helper to create and persist a Price entity.
     */
    private Price createAndPersistPrice(Product product, Store store, PriceUsage usage, int priority, LocalDateTime start) {
        Price price = new Price();
        price.product = product;
        price.store = store;
        price.priceUsage = usage;
        price.priority = priority;
        price.startDateTime = start;
        price.priceExcludingTax = BigDecimal.TEN;
        price.priceIncludingTax = BigDecimal.valueOf(12.0);
        price.vatRate = BigDecimal.valueOf(0.2);
        price.persist();
        return price;
    }

    // --------------------------------------------------
    // Query Tests (MANAGER)
    // --------------------------------------------------

    /**
     * Tests retrieving all prices.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllPrices() {
        setUp();
        List<Price> list = resource.allPrices();
        assertNotNull(list);
        assertFalse(list.isEmpty());
    }

    /**
     * Tests retrieving a price by ID (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPriceById_Success() {
        setUp();
        Price existing = (Price) Price.listAll().get(0);
        Price found = resource.price(existing.id);
        assertNotNull(found);
        assertEquals(PriceUsage.DEFAULT, found.priceUsage);
    }

    /**
     * Tests retrieving a price by ID (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPriceById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> resource.price(9999L));
    }

    /**
     * Tests retrieving current price (Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testCurrentPrice_Found() {
        setUp();
        Product product = Product.findByEan("PROD_01");
        Store store = Store.findByCode("STORE_01");

        Price found = resource.currentPrice(product.id, store.id);
        assertNotNull(found);
    }

    /**
     * Tests retrieving current price (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testCurrentPrice_NotFound() {
        setUp();
        // Non-existent combination
        Price found = resource.currentPrice(999L, 888L);
        assertNull(found);
    }

    // --------------------------------------------------
    // Mutation Tests: Create (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful creation of a price.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePrice_Success() throws GraphQLException {
        setUp();
        Product product = Product.findByEan("PROD_01");
        Store store = Store.findByCode("STORE_01");

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = product.id;
        input.storeId = store.id;
        input.priceUsage = PriceUsage.BASE_FOR_DISCOUNT; // Different usage to avoid conflict
        input.priority = 1;
        input.startDateTime = LocalDateTime.now();
        input.priceExcludingTax = BigDecimal.ONE;

        Price created = resource.createPrice(input);

        assertNotNull(created.id);
        assertEquals(PriceUsage.BASE_FOR_DISCOUNT, created.priceUsage);
    }

    /**
     * Tests creation failure when PriceUsage is null.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePrice_NullUsage() {
        setUp();
        Product product = Product.findByEan("PROD_01");
        Store store = Store.findByCode("STORE_01");

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = product.id;
        input.storeId = store.id;
        input.priceUsage = null; // Null usage

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createPrice(input));
        assertTrue(ex.getMessage().contains("An error occurred during createPrice."));
    }

    /**
     * Tests creation failure due to non-existent Product.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePrice_ProductNotFound() {
        setUp();
        Store store = Store.findByCode("STORE_01");

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = 9999L;
        input.storeId = store.id;
        input.priceUsage = PriceUsage.DEFAULT;

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.createPrice(input));
        assertTrue(ex.getMessage().contains("Product with id"));
    }

    /**
     * Tests creation failure due to non-existent Store.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePrice_StoreNotFound() {
        setUp();
        Product product = Product.findByEan("PROD_01");

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = product.id;
        input.storeId = 9999L;
        input.priceUsage = PriceUsage.DEFAULT;

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.createPrice(input));
        assertTrue(ex.getMessage().contains("Store with id"));
    }

    /**
     * Tests creation failure due to duplicate unique constraint.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePrice_Duplicate() {
        setUp();
        Product product = Product.findByEan("PROD_01");
        Store store = Store.findByCode("STORE_01");
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = product.id;
        input.storeId = store.id;
        input.priceUsage = existing.priceUsage;
        input.priority = existing.priority;
        input.startDateTime = existing.startDateTime; // Same keys

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createPrice(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    // --------------------------------------------------
    // Mutation Tests: Update (ADMIN)
    // --------------------------------------------------

    /**
     * Tests update failure when price is not found.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                resource.updatePrice(9999L, new PriceResource.PriceRecord()));
    }

    /**
     * Tests update where input fields are null (No change).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_NullFields() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        // All null

        Price updated = resource.updatePrice(existing.id, input);

        assertEquals(existing.priceUsage, updated.priceUsage);
        assertEquals(existing.priority, updated.priority);
    }

    /**
     * Tests update failure when changing to a non-existent Product.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ProductNotFound() {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = 9999L;

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.updatePrice(existing.id, input));
        assertTrue(ex.getMessage().contains("Product with id"));
    }

    /**
     * Tests update failure when changing to a non-existent Store.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_StoreNotFound() {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.storeId = 9999L;

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.updatePrice(existing.id, input));
        assertTrue(ex.getMessage().contains("Store with id"));
    }

    /**
     * Tests update success changing Product.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeProduct_Success() throws GraphQLException {
        setUp();
        Product newProduct = DomainUtils.createAndPersistProduct("PROD_NEW", "New Prod", ProductType.UNIT);
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = newProduct.id;

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(newProduct.id, updated.product.id);
    }

    /**
     * Tests update success changing Store.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeStore_Success() throws GraphQLException {
        setUp();
        Store newStore = DomainUtils.createAndPersistStore("STORE_NEW", 1.0, 1.0);
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.storeId = newStore.id;

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(newStore.id, updated.store.id);
    }

    /**
     * Tests update success changing PriceUsage.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeUsage_Success() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.priceUsage = PriceUsage.BASE_FOR_DISCOUNT; // Change usage

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(PriceUsage.BASE_FOR_DISCOUNT, updated.priceUsage);
    }

    /**
     * Tests update success changing non-key fields (Price HT/TTC).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeAmounts() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.priceExcludingTax = BigDecimal.valueOf(100.0);

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(BigDecimal.valueOf(100.0), updated.priceExcludingTax);
    }

    /**
     * Tests update failure due to conflicting unique constraint.
     * <p>
     * Scenario: Two prices exist. Updating Price 1 to match Price 2's keys.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_Conflict() {
        setUp();
        Product product = Product.findByEan("PROD_01");
        Store store = Store.findByCode("STORE_01");

        // Create a second price with different keys
        LocalDateTime futureStart = LocalDateTime.now().plusDays(1);
        createAndPersistPrice(product, store, PriceUsage.DEFAULT, 1, futureStart);

        Price target = (Price) Price.listAll().get(0); // The one from setUp (earliest time)

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.startDateTime = futureStart; // Try to match the second price's start time

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.updatePrice(target.id, input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests update success changing Price Including Tax.
     * <p>
     * Validates:
     * - {@code (input.priceIncludingTax != null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangePriceIncludingTax() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.priceIncludingTax = BigDecimal.valueOf(50.0);

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(BigDecimal.valueOf(50.0), updated.priceIncludingTax);
    }

    /**
     * Tests update success changing VAT Rate.
     * <p>
     * Validates:
     * - {@code (input.vatRate != null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeVatRate() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.vatRate = BigDecimal.valueOf(0.5);

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(BigDecimal.valueOf(0.5), updated.vatRate);
    }

    /**
     * Tests update success changing End Date Time.
     * <p>
     * Validates:
     * - {@code (input.endDateTime != null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeEndDateTime() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);
        LocalDateTime newEnd = LocalDateTime.now().plusDays(10);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.endDateTime = newEnd;

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(newEnd, updated.endDateTime);
    }

    /**
     * Tests update success changing Priority (Key change).
     * <p>
     * Validates:
     * - {@code (input.priority != null)} is TRUE.
     * - {@code !targetPriority.equals(currentPriority)} is TRUE.
     * - {@code if (conflictCount > 0)} is FALSE (No conflict).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangePriority_Success() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);
        // Default priority in setUp is 1. We change it to 2.

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.priority = 2;

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(2, updated.priority);
    }

    /**
     * Tests update success changing Start Date Time (Key change).
     * <p>
     * Validates:
     * - {@code (input.startDateTime != null)} is TRUE.
     * - {@code !targetStart.equals(currentStart)} is TRUE.
     * - {@code if (conflictCount > 0)} is FALSE (No conflict).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_ChangeStartDateTime_Success() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);
        LocalDateTime newStart = LocalDateTime.now().plusDays(5);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.startDateTime = newStart;

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(newStart, updated.startDateTime);
    }

    /**
     * Tests update where the same Product ID is provided (No change).
     * <p>
     * Validates:
     * - {@code (input.productId != null)} is TRUE.
     * - {@code !input.productId.equals(currentProductId)} is FALSE.
     * Expectation: Skips product existence check, updates nothing.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_SameProductId() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = existing.product.id; // Same ID

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(existing.product.id, updated.product.id);
    }

    /**
     * Tests update where the same Store ID is provided (No change).
     * <p>
     * Validates:
     * - {@code (input.storeId != null)} is TRUE.
     * - {@code !input.storeId.equals(currentStoreId)} is FALSE.
     * Expectation: Skips store existence check, updates nothing.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePrice_SameStoreId() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);

        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.storeId = existing.store.id; // Same ID

        Price updated = resource.updatePrice(existing.id, input);
        assertEquals(existing.store.id, updated.store.id);
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful deletion.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeletePrice_Success() throws GraphQLException {
        setUp();
        Price existing = (Price) Price.listAll().get(0);
        assertTrue(resource.deletePrice(existing.id));
        assertNull(Price.findById(existing.id));
    }

    /**
     * Tests deletion of non-existent price.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeletePrice_NotFound() throws GraphQLException {
        setUp();
        assertFalse(resource.deletePrice(9999L));
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    /**
     * Tests the toString method of the Record.
     */
    @Test
    void testRecord() {
        var record = new PriceResource.PriceRecord();
        record.productId = 1L;
        record.storeId = 2L;
        record.priceUsage = PriceUsage.DEFAULT;
        record.priceExcludingTax = BigDecimal.TEN;
        record.priceIncludingTax = BigDecimal.valueOf(12.0);
        record.vatRate = BigDecimal.valueOf(0.2);
        record.priority = 1;
        record.startDateTime = LocalDateTime.of(2023, 1, 1, 0, 0);
        record.endDateTime = null;

        String expected = "PriceRecord [productId=1, storeId=2, priceUsage=DEFAULT, priceExcludingTax=10, priceIncludingTax=12.0, vatRate=0.2, priority=1, startDateTime=2023-01-01T00:00, endDateTime=null]";
        assertEquals(expected, record.toString());
    }
}