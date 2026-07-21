package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration test class for {@link ProductFamily}.
 * <p>
 * Uses {@code @QuarkusTest} for application context.
 * Splits testing into:
 * <ul>
 *   <li><b>Repository Logic:</b> Transactional tests for persistence, lookups, and complex hierarchy traversal.</li>
 *   <li><b>Hierarchy Logic:</b> Tests for {@code findAllFamiliesForProduct(Product)} and recursion.</li>
 *   <li><b>Flag Logic:</b> Tests for {@code addFlag}, {@code removeFlag}, {@code hasFlag}.</li>
 *   <li><b>Helpers:</b> Tests for private static helpers {@code findAncestorsRecursive} and {@code getFlagsSet}.</li>
 * </ul>
 */
@QuarkusTest
class ProductFamilyTest {

    @Inject
    EntityManager em;

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Creates and persists a valid {@link Product} entity for testing.
     * <p>
     * Sets mandatory fields: {@code ean}, {@code name}, and {@code productType}.
     * <p>
     * <b>Note on Fix:</b> Added {@code em.flush()} to ensure ID is available
     * when linking to families.
     *
     * @param ean The EAN of the product.
     * @return The persisted Product instance.
     */
    private Product createTestProduct(String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + ean;
        product.productType = ProductType.UNIT;
        product.persist();
        em.flush(); // CRITICAL: Forces ID generation for relationships
        return product;
    }

    /**
     * Creates a valid {@link ProductFamily} entity for testing.
     *
     * @param code The code of the family.
     * @return The persisted ProductFamily instance.
     */
    private ProductFamily createTestFamily(String code) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = "Family " + code;
        family.persist();
        em.flush();
        return family;
    }

    // --------------------------------------------------
    // Database / Repository Query Tests
    // --------------------------------------------------

    /**
     * Tests that {@link ProductFamily} can be persisted successfully.
     */
    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        ProductFamily family = new ProductFamily();
        family.code = "F001";
        family.description = "Fruits";
        family.flags = "ORGANIC";
        family.persist();

        em.flush();

        assertNotNull(family.id);
    }

    /**
     * Tests persistence validation: Code is mandatory.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifCodeIsNull() {
        ProductFamily family = new ProductFamily();
        family.code = null;
        family.description = "Test Family";

        assertThrows(ConstraintViolationException.class, () -> {
            family.persist();
            em.flush();
        });
    }

    /**
     * Tests {@link ProductFamily#findByCode(String)}.
     */
    @Test
    @TestTransaction
    void findByCode_shouldReturnFamily() {
        ProductFamily family = new ProductFamily();
        family.code = "SEARCH_ME";
        family.description = "Target Family";
        family.persist();
        em.flush();

        ProductFamily result = ProductFamily.findByCode("SEARCH_ME");

        assertNotNull(result);
        assertEquals("Target Family", result.description);
    }

    /**
     * Tests {@link ProductFamily#findByCode(String)} returns null for unknown code.
     */
    @Test
    @TestTransaction
    void findByCode_shouldReturnNull_ifNotFound() {
        ProductFamily result = ProductFamily.findByCode("UNKNOWN");
        assertNull(result);
    }

    // --------------------------------------------------
    // Hierarchy Logic Tests (findAllFamiliesForProduct)
    // --------------------------------------------------

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} when product is null.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldHandleNullIDProduct() {
        // Use a product with null ID (default state) or a detached one
        Product p = new Product(); // ID is null

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(p);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Should return empty set if product is null");
    }

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} when product belongs to no family.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldReturnEmptySet_ifProductHasNoFamilies() {
        Product p = createTestProduct("1234567890123");
        // No families linked

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(p);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} with direct parent.
     * <p>
     * Product belongs to Family A. Expected result: {A}.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldReturnDirectParent() {
        Product p = createTestProduct("P1");

        ProductFamily familyA = createTestFamily("FAMILY_A");
        familyA.products.add(p);
        familyA.persist();

        em.flush();

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(p);

        assertEquals(1, result.size());
        assertTrue(result.contains(familyA));
    }

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} with a hierarchy depth of 2.
     * <p>
     * Product belongs to B, B belongs to A. Expected result: {A, B}.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldReturnAncestors_forHierarchy() {
        Product p = createTestProduct("P1");

        ProductFamily familyA = createTestFamily("FAMILY_A");
        ProductFamily familyB = createTestFamily("FAMILY_B");

        // Hierarchy: A -> B -> Product
        familyB.productFamilies.add(familyA);
        familyA.products.add(p);
        familyB.persist();

        em.flush();

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(p);

        assertEquals(2, result.size());
        assertTrue(result.contains(familyA));
        assertTrue(result.contains(familyB));
    }

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} handling of explicit null.
     * <p>
     * Verifies that when {@code product} is explicitly {@code null},
     * the method returns an empty set immediately.
     * <p>
     * <b>Note:</b> This test specifically forces the {@code product} reference to be {@code null},
     * targeting the first condition {@code product == null} in the logic.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldHandleNullProduct() {
        // Explicitly pass null
        Product product = null;

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected empty set for null product");
    }

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} handling of cycles.
     * <p>
     * A belongs to B, B belongs to A, A belongs to B (Cycle).
     * Expected result: {A, B}.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldHandleCycles() {
        Product p = createTestProduct("P_CYCLE");

        ProductFamily familyA = createTestFamily("CYCLE_A");
        ProductFamily familyB = createTestFamily("CYCLE_B");

        // A -> B -> A (Cycle)
        familyA.productFamilies.add(familyB);
        familyB.productFamilies.add(familyA);

        familyB.products.add(p);
        familyB.persist();

        em.flush();

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(p);

        assertEquals(2, result.size());
        assertTrue(result.contains(familyA));
        assertTrue(result.contains(familyB));
    }

    /**
     * Tests {@link ProductFamily#findAllFamiliesForProduct(Product)} with multiple parents.
     */
    @Test
    @TestTransaction
    void findAllFamiliesForProduct_shouldReturnMultipleParents() {
        Product p = createTestProduct("P_MULTI");

        ProductFamily parent1 = createTestFamily("PARENT_1");
        ProductFamily parent2 = createTestFamily("PARENT_2");

        parent1.products.add(p);
        parent1.persist();

        parent2.products.add(p);
        parent2.persist();

        em.flush();

        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(p);

        assertEquals(2, result.size());
        assertTrue(result.contains(parent1));
        assertTrue(result.contains(parent2));
    }

    // --------------------------------------------------
    // Flag Logic Tests
    // --------------------------------------------------

    /**
     * Tests {@link ProductFamily#addFlag(String)}.
     */
    @Test
    @TestTransaction
    void addFlag_shouldAddToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F1";
        family.flags = "ORGANIC";
        family.persist();

        family.addFlag("SEASONAL");

        ProductFamily updated = ProductFamily.findByCode("F1");
        assertNotNull(updated);
        assertTrue(updated.flags.contains("ORGANIC"));
        assertTrue(updated.flags.contains("SEASONAL"));
    }

    /**
     * Tests {@link ProductFamily#addFlag(String)} trimming.
     */
    @Test
    @TestTransaction
    void addFlag_shouldTrimToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F2";
        family.flags = "ORGANIC";
        family.persist();

        family.addFlag("  SEASONAL  ");

        ProductFamily updated = ProductFamily.findByCode("F2");
        assertNotNull(updated);
        assertEquals("ORGANIC,SEASONAL", updated.flags);
    }

    /**
     * Tests {@link ProductFamily#addFlag(String)} with null token.
     * <p>
     * Covers {@code if (token == null || token.isBlank())}.
     */
    @Test
    @TestTransaction
    void addFlag_shouldIgnoreNullToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F3";
        family.flags = "ORGANIC";
        family.persist();

        family.addFlag(null);

        ProductFamily updated = ProductFamily.findByCode("F3");
        assertNotNull(updated);
        assertEquals("ORGANIC", updated.flags);
    }

    /**
     * Tests {@link ProductFamily#addFlag(String)} with blank token.
     * <p>
     * Covers {@code token.isBlank()}.
     */
    @Test
    @TestTransaction
    void addFlag_shouldIgnoreBlankToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F4";
        family.flags = "ORGANIC";
        family.persist();

        family.addFlag("  ");

        ProductFamily updated = ProductFamily.findByCode("F4");
        assertNotNull(updated);
        assertEquals("ORGANIC", updated.flags);
    }

    /**
     * Tests {@link ProductFamily#addFlag(String)} uniqueness.
     */
    @Test
    @TestTransaction
    void addFlag_shouldNotAddDuplicate() {
        ProductFamily family = new ProductFamily();
        family.code = "F_DUP";
        family.flags = "A";
        family.persist();

        family.addFlag("A");
        family.addFlag("A");

        ProductFamily updated = ProductFamily.findByCode("F_DUP");
        assertNotNull(updated);
        assertEquals("A", updated.flags);
    }

    /**
     * Tests {@link ProductFamily#removeFlag(String)}.
     */
    @Test
    @TestTransaction
    void removeFlag_shouldRemoveToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F5";
        family.flags = "A,B,C";
        family.persist();

        family.removeFlag("B");

        ProductFamily updated = ProductFamily.findByCode("F5");
        assertNotNull(updated);
        assertEquals("A,C", updated.flags);
    }

    /**
     * Tests {@link ProductFamily#removeFlag(String)} with whitespace.
     */
    @Test
    @TestTransaction
    void removeFlag_shouldTrimToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F6";
        family.flags = "A,B";
        family.persist();

        family.removeFlag("  B  ");

        ProductFamily updated = ProductFamily.findByCode("F6");
        assertNotNull(updated);
        assertEquals("A", updated.flags);
    }

    /**
     * Tests {@link ProductFamily#removeFlag(String)} with null token.
     * <p>
     * Covers {@code if (token == null)}.
     */
    @Test
    @TestTransaction
    void removeFlag_shouldHandleNullToken() {
        ProductFamily family = new ProductFamily();
        family.code = "F7";
        family.flags = "A,B";
        family.persist();

        String originalFlags = family.flags;
        family.removeFlag(null);

        ProductFamily updated = ProductFamily.findByCode("F7");
        assertNotNull(updated);
        assertEquals(originalFlags, updated.flags);
    }

    /**
     * Tests {@link ProductFamily#removeFlag(String)} behavior when token not found.
     */
    @Test
    @TestTransaction
    void removeFlag_shouldReturnFalse_ifTokenNotFound() {
        ProductFamily family = new ProductFamily();
        family.code = "F8";
        family.flags = "A";
        family.persist();

        boolean result = family.removeFlag("B");

        assertFalse(result);
    }

    /**
     * Tests {@link ProductFamily#removeFlag(String)} behavior when removing last flag.
     */
    @Test
    @TestTransaction
    void removeFlag_shouldSetFlagsToNull_ifLastTokenRemoved() {
        ProductFamily family = new ProductFamily();
        family.code = "F9";
        family.flags = "ONLY_ONE";
        family.persist();

        family.removeFlag("ONLY_ONE");

        ProductFamily updated = ProductFamily.findByCode("F9");
        assertNotNull(updated);
        assertNull(updated.flags);
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)}.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnTrue() {
        ProductFamily family = new ProductFamily();
        family.code = "F10";
        family.flags = "ORGANIC";
        family.persist();

        Product product = createTestProduct("P_HAS_FLAG");
        family.products.add(product);
        family.persist();

        assertTrue(ProductFamily.productHasFlag(product, "ORGANIC"));
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} returns false.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse() {
        ProductFamily family = new ProductFamily();
        family.code = "F11";
        family.flags = "ORGANIC";
        family.persist();

        Product product = createTestProduct("P_NO_FLAG");
        family.products.add(product);
        family.persist();

        assertFalse(ProductFamily.productHasFlag(product, "NON_EXISTENT"));
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} case sensitivity.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldBeCaseSensitive() {
        ProductFamily family = new ProductFamily();
        family.code = "F12";
        family.flags = "ORGANIC";
        family.persist();

        Product product = createTestProduct("P_CASE");
        family.products.add(product);
        family.persist();

        assertFalse(ProductFamily.productHasFlag(product, "organic"));
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} with null product.
     * <p>
     * Covers {@code if (product == null)}.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse_ifProductIsNull() {
        ProductFamily family = new ProductFamily();
        family.code = "F13";
        family.flags = "ORGANIC";
        family.persist();

        assertFalse(ProductFamily.productHasFlag(null, "ORGANIC"), "Null product should return false");
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} with null flag.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse_ifFlagIsNull() {
        ProductFamily family = new ProductFamily();
        family.code = "F14";
        family.flags = "ORGANIC";
        family.persist();

        Product product = createTestProduct("P_NULL_FLAG");
        family.products.add(product);
        family.persist();

        assertFalse(ProductFamily.productHasFlag(product, null));
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} with blank flag.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse_ifFlagIsBlank() {
        ProductFamily family = new ProductFamily();
        family.code = "F15";
        family.flags = "ORGANIC";
        family.persist();

        Product product = createTestProduct("P_BLANK_FLAG");
        family.products.add(product);
        family.persist();

        assertFalse(ProductFamily.productHasFlag(product, "  "));
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} with null flags in entity.
     * <p>
     * Covers {@code this.flags == null || this.flags.isBlank()}.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse_ifFamilyFlagsIsNull() {
        ProductFamily family = new ProductFamily();
        family.code = "F16";
        family.flags = null; // Explicitly null
        family.persist();

        Product product = createTestProduct("P_NULL_FLAGS");
        family.products.add(product);
        family.persist();

        assertFalse(ProductFamily.productHasFlag(product, "ANY"));
    }

    /**
     * Tests {@link ProductFamily#hasFlag(Product, String)} with blank flags in entity.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse_ifFamilyFlagsIsBlank() {
        ProductFamily family = new ProductFamily();
        family.code = "F17";
        family.flags = "   "; // Blank
        family.persist();

        Product product = createTestProduct("P_BLANK_FLAGS");
        family.products.add(product);
        family.persist();

        assertFalse(ProductFamily.productHasFlag(product, "ANY"));
    }

    /**
     * Tests {@link ProductFamily#productHasFlag(Product, String)} when flag is blank.
     * <p>
     * Checks {@code flag.isBlank()} condition in the static method.
     */
    @Test
    @TestTransaction
    void productHasFlag_shouldReturnFalse_whenFlagIsBlank() {
        Product p = createTestProduct("123");

        // Passing a string containing only spaces
        boolean result = ProductFamily.productHasFlag(p, "   ");

        assertFalse(result, "Should return false for blank flag");
    }

    /**
     * Tests {@link ProductFamily#hasFlag(String)} instance method with null token.
     * <p>
     * Checks {@code if (token == null ...)} condition in the instance method.
     */
    @Test
    @TestTransaction
    void hasFlag_shouldReturnFalse_whenTokenIsNull() {
        ProductFamily family = createTestFamily("F1");

        // Calling instance method with null token
        boolean result = family.hasFlag(null);

        assertFalse(result, "Should return false for null token");
    }

    /**
     * Tests {@link ProductFamily#hasFlag(String)} instance method with blank token.
     * <p>
     * Checks {@code token.isBlank()} condition in the instance method.
     */
    @Test
    @TestTransaction
    void hasFlag_shouldReturnFalse_whenTokenIsBlank() {
        ProductFamily family = createTestFamily("F2");

        // Calling instance method with blank token (spaces)
        boolean result = family.hasFlag("   ");

        assertFalse(result, "Should return false for blank token");
    }

    /**
     * Tests {@link ProductFamily#getChecksum()}.
     */
    @Test
    @TestTransaction
    void getChecksum_shouldIncludeFields() {
        ProductFamily family = new ProductFamily();
        family.code = "C1";
        family.description = "Desc";
        family.flags = "A,B";

        int checksum1 = family.getChecksum();

        family.flags = "A,C";
        int checksum2 = family.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

}