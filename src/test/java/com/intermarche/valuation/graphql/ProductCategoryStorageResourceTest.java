package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ProductCategoryStorageResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ProductCategoryStorageResourceTest {

    @Inject
    ProductCategoryStorageResource resource;

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

        Product product = DomainUtils.createAndPersistProduct("PROD_CAT", "Product Cat", ProductType.UNIT);
        createAndPersistStorage(product, "L1", "L2", "L3", "L4", "L5");
    }

    /**
     * Helper to create and persist a ProductCategoryStorage entity.
     *
     * @param product The product to link.
     * @param l1      Level 1 category.
     * @param l2      Level 2 category.
     * @param l3      Level 3 category.
     * @param l4      Level 4 category.
     * @param l5      Level 5 category.
     * @return The persisted entity.
     */
    private ProductCategoryStorage createAndPersistStorage(Product product, String l1, String l2, String l3, String l4, String l5) {
        ProductCategoryStorage storage = new ProductCategoryStorage();
        storage.product = product;
        storage.level1 = l1;
        storage.level2 = l2;
        storage.level3 = l3;
        storage.level4 = l4;
        storage.level5 = l5;
        storage.persist();
        return storage;
    }

    // --------------------------------------------------
    // Query Tests (MANAGER)
    // --------------------------------------------------

    /**
     * Tests retrieving all product category storages.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllProductCategoryStorages() {
        setUp();
        assertFalse(resource.allProductCategoryStorages().isEmpty());
    }

    /**
     * Tests retrieving a storage by ID (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductCategoryStorageById_Success() {
        setUp();
        Product product = Product.findByEan("PROD_CAT");
        ProductCategoryStorage existing = ProductCategoryStorage.find("product", product).firstResult();
        assertNotNull(existing);
        assertEquals("L1", resource.productCategoryStorage(existing.id).level1);
    }

    /**
     * Tests retrieving a storage by ID (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductCategoryStorageById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> resource.productCategoryStorage(9999L));
    }

    // --------------------------------------------------
    // Mutation Tests: Create (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful creation of a storage.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductCategoryStorage_Success() throws GraphQLException {
        setUp();
        Product product = Product.findByEan("PROD_CAT");

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.productId = product.id;
        input.level1 = "NewL1";
        input.level5 = "NewL5";

        ProductCategoryStorage created = resource.createProductCategoryStorage(input);

        assertNotNull(created.id);
        assertEquals("NewL1", created.level1);
    }

    /**
     * Tests creation failure due to non-existent product.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductCategoryStorage_ProductNotFound() {
        setUp();
        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.productId = 9999L;
        input.level1 = "L1";

        assertThrows(NoSuchElementException.class, () -> resource.createProductCategoryStorage(input));
    }

    /**
     * Tests creation failure due to duplicate unique constraint (Product + L1 + L5).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductCategoryStorage_Duplicate() {
        setUp();
        Product product = Product.findByEan("PROD_CAT");

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.productId = product.id;
        input.level1 = "L1";
        input.level5 = "L5";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createProductCategoryStorage(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    // --------------------------------------------------
    // Mutation Tests: Update (ADMIN)
    // --------------------------------------------------

    /**
     * Tests update failure when storage is not found.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                resource.updateProductCategoryStorage(9999L, new ProductCategoryStorageResource.ProductCategoryStorageRecord()));
    }

    /**
     * Tests update where input fields are null (No change).
     * <p>
     * Validates that the key fields (L1, L5) remain unchanged.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_NullFields() throws GraphQLException {
        setUp();
        ProductCategoryStorage existing = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorage updated = resource.updateProductCategoryStorage(existing.id, new ProductCategoryStorageResource.ProductCategoryStorageRecord());

        assertEquals("L1", updated.level1);
        assertEquals("L5", updated.level5);
    }

    /**
     * Tests update failure when changing to a non-existent product.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_ProductNotFound() {
        setUp();
        ProductCategoryStorage existing = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.productId = 9999L;

        assertThrows(NoSuchElementException.class, () -> resource.updateProductCategoryStorage(existing.id, input));
    }

    /**
     * Tests update where the Product ID is changed to a valid new Product.
     * <p>
     * Validates:
     * - {@code (input.productId != null && !input.productId.equals(currentProductId))} is TRUE.
     * - {@code if (conflictCount > 0)} is FALSE (No conflict).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_ChangeProduct_Success() throws GraphQLException {
        setUp();
        Product newProduct = DomainUtils.createAndPersistProduct("PROD_NEW", "New Prod", ProductType.UNIT);
        ProductCategoryStorage target = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.productId = newProduct.id;

        ProductCategoryStorage updated = resource.updateProductCategoryStorage(target.id, input);
        assertEquals(newProduct.id, updated.product.id);
    }

    /**
     * Tests update where the same Product ID is provided (No change).
     * <p>
     * Validates:
     * - {@code (input.productId != null)} is TRUE.
     * - {@code !input.productId.equals(currentProductId)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_SameProductId() throws GraphQLException {
        setUp();
        ProductCategoryStorage target = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.productId = target.product.id;

        ProductCategoryStorage updated = resource.updateProductCategoryStorage(target.id, input);
        assertEquals(target.product.id, updated.product.id);
    }

    /**
     * Tests update of non-key fields (Level 2, 3, 4).
     * <p>
     * Validates:
     * - Key change condition is FALSE.
     * - Uniqueness check is skipped.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_NonKeyFields() throws GraphQLException {
        setUp();
        ProductCategoryStorage target = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.level2 = "NEW_L2";
        input.level3 = "NEW_L3";
        input.level4 = "NEW_L4";
        ProductCategoryStorage updated = resource.updateProductCategoryStorage(target.id, input);

        assertEquals("NEW_L2", updated.level2);
        assertEquals("L1", updated.level1);
        assertEquals("L5", updated.level5);
    }

    /**
     * Tests update where only Level 1 is changed (Key change).
     * <p>
     * Validates:
     * - {@code !targetL1.equals(currentL1)} is TRUE.
     * - {@code if (conflictCount > 0)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_ChangeLevel1_Success() throws GraphQLException {
        setUp();
        ProductCategoryStorage target = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.level1 = "NEW_L1";

        ProductCategoryStorage updated = resource.updateProductCategoryStorage(target.id, input);
        assertEquals("NEW_L1", updated.level1);
    }

    /**
     * Tests update where only Level 5 is changed (Key change).
     * <p>
     * Validates:
     * - {@code !targetL5.equals(currentL5)} is TRUE.
     * - {@code if (conflictCount > 0)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_ChangeLevel5_Success() throws GraphQLException {
        setUp();
        ProductCategoryStorage target = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.level5 = "NEW_L5";

        ProductCategoryStorage updated = resource.updateProductCategoryStorage(target.id, input);
        assertEquals("NEW_L5", updated.level5);
    }

    /**
     * Tests update failure due to unique constraint violation on (Product, L1, L5).
     * <p>
     * Scenario:
     * 1. Existing Entry 1: (Prod_A, "L1", "L5")
     * 2. Existing Entry 2: (Prod_A, "L1_ALT", "L5_ALT")
     * 3. Update Entry 1 to match Entry 2's unique key.
     * <p>
     * Validates:
     * - {@code if (!targetL1.equals(currentL1) || !targetL5.equals(currentL5))} is TRUE.
     * - {@code if (conflictCount > 0)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductCategoryStorage_Conflict() {
        setUp();
        Product product = Product.findByEan("PROD_CAT");
        createAndPersistStorage(product, "L1_ALT", "...", "...", "...", "L5_ALT");

        ProductCategoryStorage target = ProductCategoryStorage.find("level1", "L1").firstResult();

        ProductCategoryStorageResource.ProductCategoryStorageRecord input = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        input.level1 = "L1_ALT";
        input.level5 = "L5_ALT";

        GraphQLException ex = assertThrows(GraphQLException.class, () ->
                resource.updateProductCategoryStorage(target.id, input));

        assertTrue(ex.getMessage().contains("already exists"));
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful deletion.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProductCategoryStorage_Success() throws GraphQLException {
        setUp();
        ProductCategoryStorage existing = ProductCategoryStorage.find("level1", "L1").firstResult();
        assertTrue(resource.deleteProductCategoryStorage(existing.id));
        assertNull(ProductCategoryStorage.findById(existing.id));
    }

    /**
     * Tests deletion of non-existent storage.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProductCategoryStorage_NotFound() throws GraphQLException {
        setUp();
        assertFalse(resource.deleteProductCategoryStorage(9999L));
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    /**
     * Tests the toString method of the Record.
     */
    @Test
    void testRecord() {
        var record = new ProductCategoryStorageResource.ProductCategoryStorageRecord();
        record.productId = 1L;
        record.level1 = "L1";
        record.level2 = "L2";
        record.level3 = "L3";
        record.level4 = "L4";
        record.level5 = "L5";

        String expected = "ProductCategoryStorageRecord [productId=1, level1=L1, level2=L2, level3=L3, level4=L4, level5=L5]";
        assertEquals(expected, record.toString());
    }
}