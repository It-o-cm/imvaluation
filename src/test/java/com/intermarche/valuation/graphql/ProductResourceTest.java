package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ProductResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ProductResourceTest {

    @Inject
    ProductResource productResource;

    /**
     * Sets up initial data for tests.
     */
    void setUp() {
        // Create a default product for query tests
        DomainUtils.createAndPersistProduct("1111111111111", "Product A", ProductType.UNIT);
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllProducts() {
        setUp();
        List<Product> products = productResource.allProducts();
        assertNotNull(products);
        assertFalse(products.isEmpty(), "Should return at least one product");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductById_Success() {
        setUp();
        // Find the existing product from setup
        Product existing = Product.findByEan("1111111111111");
        assertNotNull(existing);
        Product found = productResource.product(existing.id);
        assertNotNull(found);
        assertEquals("Product A", found.name);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> {
            productResource.product(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductByEan_Success() {
        setUp();
        Product found = productResource.productByEan("1111111111111");
        assertNotNull(found);
        assertEquals("Product A", found.name);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductByEan_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> {
            productResource.productByEan("9999999999999");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProduct_DuplicateEan() {
        setUp();
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "1111111111111"; // Existing EAN from setup
        input.name = "Some Unique Name";
        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productResource.createProduct(input);
        });
        assertTrue(ex.getMessage().contains("already exists"), "Exception message should indicate duplicate");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProduct_DuplicateName() {
        setUp();
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "3333333333333"; // New EAN
        input.name = "Product A"; // Existing Name from setup
        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productResource.createProduct(input);
        });
        assertTrue(ex.getMessage().contains("already exists"), "Exception message should indicate duplicate");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_ChangeEan() throws GraphQLException {
        setUp();
        Product existing = Product.findByEan("1111111111111");
        assertNotNull(existing);
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "4444444444444"; // New valid EAN
        Product updated = productResource.updateProduct(existing.id, input);
        assertEquals("4444444444444", updated.ean);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_ConflictingEan() {
        setUp();
        // Create another product to conflict with
        DomainUtils.createAndPersistProduct("5555555555555", "Conflict Product", ProductType.UNIT);
        Product target = Product.findByEan("1111111111111");
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "5555555555555"; // Try to assign existing EAN of the other product
        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productResource.updateProduct(target.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_ConflictingName() {
        setUp();
        // Create another product to conflict with
        DomainUtils.createAndPersistProduct("6666666666666", "Conflict Name", ProductType.UNIT);
        Product target = Product.findByEan("1111111111111");
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.name = "Conflict Name"; // Try to assign existing name
        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productResource.updateProduct(target.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_NotFound() {
        setUp();
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.name = "Ghost";
        assertThrows(NoSuchElementException.class, () -> {
            productResource.updateProduct(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProduct_Success() throws GraphQLException {
        setUp();
        Product existing = Product.findByEan("1111111111111");
        assertNotNull(existing);
        boolean result = productResource.deleteProduct(existing.id);
        assertTrue(result, "Delete should return true");
        // Verify it's gone
        assertNull(Product.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProduct_NotFound() throws GraphQLException {
        setUp();
        boolean result = productResource.deleteProduct(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    @Test
    void testProductRecord() {
        var productResource = new ProductResource.ProductRecord();
        productResource.ean = "100000000001";
        productResource.productType = "WEIGHT";
        productResource.name = "Product 1";
        productResource.referenceWeight = new BigDecimal("1.5");
        productResource.referenceVolume = new BigDecimal("2.5");
        productResource.active = true;
        productResource.unitName = "Kg";
        productResource.description = "Product 1 description";
        assertEquals("ProductRecord [ean=100000000001, name=Product 1, description=Product 1 description, " +
                "brand=null, referenceWeight=1.5, referenceVolume=2.5, productType=WEIGHT, unitName=Kg, active=true",
                productResource.toString());
    }

    /**
     * Tests updating all fields of a product successfully.
     * <p>
     * Validates:
     * - {@code (input.field != null)} is TRUE for all fields.
     * - {@code !input.ean.equals(product.ean)} is TRUE (change EAN).
     * - {@code !input.name.equals(product.name)} is TRUE (change Name).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_AllFields_Success() throws GraphQLException {
        // 1. Setup: Create a distinct product to avoid conflicts with default setup
        Product existing = DomainUtils.createAndPersistProduct("2222222222222", "Old Name", ProductType.UNIT);
        existing.description = "Old Desc";
        existing.brand = "Old Brand";
        existing.referenceWeight = new BigDecimal("1.0");
        existing.referenceVolume = new BigDecimal("0.5");
        existing.unitName = "PCS";
        existing.active = false;
        // 2. Input: Change ALL fields
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "3333333333333";          // New EAN
        input.name = "New Name";              // New Name
        input.description = "New Desc";       // New Desc
        input.brand = "New Brand";            // New Brand
        input.referenceWeight = new BigDecimal("2.0"); // New Weight
        input.referenceVolume = new BigDecimal("1.5");  // New Volume
        input.productType = "WEIGHT";         // New Type
        input.unitName = "KG";                // New Unit
        input.active = true;                  // New Active
        // 3. Execute
        Product updated = productResource.updateProduct(existing.id, input);
        // 4. Assert all fields were updated
        assertEquals("3333333333333", updated.ean);
        assertEquals("New Name", updated.name);
        assertEquals("New Desc", updated.description);
        assertEquals("New Brand", updated.brand);
        assertEquals(new BigDecimal("2.0"), updated.referenceWeight);
        assertEquals(new BigDecimal("1.5"), updated.referenceVolume);
        assertEquals(ProductType.WEIGHT, updated.productType);
        assertEquals("KG", updated.unitName);
        assertTrue(updated.active);
    }

    /**
     * Tests updating a product where input fields are either same as existing or null.
     * <p>
     * Validates:
     * - {@code !input.ean.equals(product.ean)} is FALSE (EAN same).
     * - {@code !input.name.equals(product.name)} is FALSE (Name same).
     * - {@code (input.field != null)} is FALSE for all other fields.
     * Expectation: No exception thrown (duplicate checks skipped), values remain unchanged.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_PartialAndNoChange_Success() throws GraphQLException {
        // 1. Setup: Create product with specific values
        Product existing = DomainUtils.createAndPersistProduct("4444444444444", "Stable Name", ProductType.UNIT);
        existing.description = "Stable Desc";
        existing.brand = "Stable Brand";
        existing.referenceWeight = new BigDecimal("5.0");
        existing.referenceVolume = new BigDecimal("3.0");
        existing.unitName = "L";
        existing.active = false;
        // 2. Input: Provide SAME values for unique keys, NULL for others
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "4444444444444";           // Same EAN
        input.name = "Stable Name";           // Same Name
        // Other fields are null by default in Record
        // 3. Execute
        Product updated = productResource.updateProduct(existing.id, input);
        // 4. Assert values remained UNCHANGED
        assertEquals("4444444444444", updated.ean, "EAN should not change");
        assertEquals("Stable Name", updated.name, "Name should not change");
        // Verify null inputs did not overwrite existing values
        assertEquals("Stable Desc", updated.description);
        assertEquals("Stable Brand", updated.brand);
        assertEquals(new BigDecimal("5.0"), updated.referenceWeight);
        assertEquals(new BigDecimal("3.0"), updated.referenceVolume);
        assertEquals(ProductType.UNIT, updated.productType);
        assertEquals("L", updated.unitName);
        assertFalse(updated.active);
    }

    /**
     * Tests successful creation of a product with all fields provided.
     * <p>
     * Validates:
     * - {@code (input.active != null)} is TRUE.
     * - Assignment of all optional fields.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProduct_AllFields_Success() throws GraphQLException {
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "2222222222222";
        input.name = "Complete Product";
        input.description = "Full Description";
        input.brand = "Brand X";
        input.referenceWeight = new BigDecimal("1.5");
        input.referenceVolume = new BigDecimal("2.5");
        input.productType = "WEIGHT";
        input.unitName = "KG";
        input.active = false; // Explicitly setting active to false
        Product created = productResource.createProduct(input);
        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("2222222222222", created.ean);
        assertEquals("Complete Product", created.name);
        assertEquals("Full Description", created.description);
        assertEquals("Brand X", created.brand);
        assertEquals(new BigDecimal("1.5"), created.referenceWeight);
        assertEquals(new BigDecimal("2.5"), created.referenceVolume);
        assertEquals(ProductType.WEIGHT, created.productType);
        assertEquals("KG", created.unitName);
        assertFalse(created.active, "Active should be false as provided in input");
    }

    /**
     * Tests successful creation with minimal fields (null checks).
     * <p>
     * Validates:
     * - {@code (input.field != null)} is FALSE for optional fields.
     * - {@code input.active != null ? input.active : true} defaults to TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProduct_NullFields_DefaultActive_Success() throws GraphQLException {
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "3333333333333";
        input.name = "Minimal Product";
        // description, brand, weight, volume, unit are null by default
        // productType is null
        // active is null
        Product created = productResource.createProduct(input);
        assertNotNull(created);
        assertEquals("3333333333333", created.ean);
        assertEquals("Minimal Product", created.name);
        // Validate null branches
        assertNull(created.description, "Description should be null");
        assertNull(created.brand, "Brand should be null");
        assertNull(created.referenceWeight, "Weight should be null");
        assertNull(created.referenceVolume, "Volume should be null");
        assertNull(created.productType, "ProductType should be null");
        assertNull(created.unitName, "UnitName should be null");
        // Validate default active logic
        assertTrue(created.active, "Active should default to true when input is null");
    }

    /**
     * Tests updating a product where input EAN and Name are null.
     * <p>
     * Validates that the pre-validation checks for duplicates are skipped
     * because {@code (input.ean != null)} and {@code (input.name != null)} are FALSE.
     * Expectation: Update proceeds without exception, changing only other fields.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProduct_NullEanAndName() throws GraphQLException {
        // 1. Setup
        Product existing = DomainUtils.createAndPersistProduct("7777777777777", "Test Null Input", ProductType.UNIT);
        // 2. Input: EAN and Name are NULL (default for Record)
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.brand = "Brand Update Only"; // Update something else to verify flow
        // 3. Execute
        Product updated = productResource.updateProduct(existing.id, input);
        // 4. Assert
        assertNotNull(updated);
        // EAN and Name should remain unchanged
        assertEquals("7777777777777", updated.ean, "EAN should remain unchanged when input is null");
        assertEquals("Test Null Input", updated.name, "Name should remain unchanged when input is null");
        // Other field should be updated
        assertEquals("Brand Update Only", updated.brand, "Brand should be updated");
    }
}