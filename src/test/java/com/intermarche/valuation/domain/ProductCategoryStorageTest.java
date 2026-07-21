package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration test class for {@link ProductCategoryStorage}.
 * <p>
 * Uses {@code @QuarkusTest} for application context.
 * Splits testing into:
 * <ul>
 *   <li><b>Repository Logic:</b> Transactional tests for persistence and constraint violations.</li>
 *   <li><b>Business Logic:</b> Tests for {@code getChecksum()} calculation.</li>
 * </ul>
 */
@QuarkusTest
public class ProductCategoryStorageTest {

    @Inject
    EntityManager em;

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Creates and persists a valid {@link Product} entity for testing.
     * <p>
     * Since {@link ProductCategoryStorage} has a mandatory relation to Product,
     * we need a persistent Product instance to link against.
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
        em.flush(); // Ensure ID is available for foreign key constraint
        return product;
    }

    /**
     * Creates a valid {@link ProductCategoryStorage} entity with all levels set.
     * <p>
     * Helper to simplify test setup.
     *
     * @param product The product to associate with.
     * @param l1   Level 1 name.
     * @param l2   Level 2 name.
     * @param l3   Level 3 name.
     * @param l4   Level 4 name.
     * @param l5   Level 5 name.
     * @return The configured entity.
     */
    private ProductCategoryStorage createStorage(Product product, String l1, String l2, String l3, String l4, String l5) {
        ProductCategoryStorage storage = new ProductCategoryStorage();
        storage.product = product;
        storage.level1 = l1;
        storage.level2 = l2;
        storage.level3 = l3;
        storage.level4 = l4;
        storage.level5 = l5;
        storage.persist();
        em.flush();
        return storage;
    }

    // --------------------------------------------------
    // Persistence Tests
    // --------------------------------------------------

    /**
     * Tests that {@link ProductCategoryStorage} can be persisted successfully.
     */
    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Product p = createTestProduct("1234567890123");
        ProductCategoryStorage storage = createStorage(p, "Food", "Fresh", "Dairy", "Yogurts", "Bio");
        em.flush();
        assertNotNull(storage.id);
    }

    /**
     * Tests the unique constraint violation when level fields are duplicated.
     * <p>
     * The unique constraint definition includes {@code level1} through {@code level5}.
     * This verifies that the combination of all 5 levels must be unique.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifLevelsAreDuplicate() {
        Product p = createTestProduct("DUPLICATE_2");
        ProductCategoryStorage storage1 = createStorage(p, "Root", "Cat1", "Cat2", "Cat3", "Cat4");
        em.flush();
        assertNotNull(storage1.id);
        assertThrows(Exception.class, () -> {
            ProductCategoryStorage storage2 = new ProductCategoryStorage();
            storage2.product = p; // Different product, but same levels
            // Exact duplicate of all 5 levels
            storage2.level1 = "Root";
            storage2.level2 = "Cat1";
            storage2.level3 = "Cat2";
            storage2.level4 = "Cat3";
            storage2.level5 = "Cat4";
            storage2.persist();
            em.flush();
        });
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    /**
     * Tests {@link ProductCategoryStorage#getChecksum()} calculation.
     * <p>
     * Verifies that checksum includes the product ID and all 5 levels.
     */
    @Test
    @TestTransaction
    void getChecksum_shouldIncludeFields() {
        Product p = createTestProduct("CHK");
        ProductCategoryStorage storage = createStorage(p, "A", "B", "C", "D", "E");
        int checksum1 = storage.getChecksum();
        storage.level2 = "B2"; // Change a level
        int checksum2 = storage.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    /**
     * Tests {@link ProductCategoryStorage#getChecksum()} change when levels change.
     */
    @Test
    @TestTransaction
    void getChecksum_shouldChange_whenLevelsChange() {
        Product p = createTestProduct("CHK_2");
        // Create 2 storages with same product but different levels
        ProductCategoryStorage storage1 = createStorage(p, "X", "Y", "Z", "W", "V");
        ProductCategoryStorage storage2 = createStorage(p, "X", "Y", "Z", "W", "V2"); // Changed 'V'
        assertNotEquals(storage1.getChecksum(), storage2.getChecksum());
    }
}