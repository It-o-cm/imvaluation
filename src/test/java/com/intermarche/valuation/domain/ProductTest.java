package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration test class for {@link Product}.
 * <p>
 * Uses {@code @QuarkusTest} for application context.
 * Splits testing into:
 * <ul>
 *   <li><b>Business Logic:</b> Tests for {@code standardQuantity()} conversion and {@code getChecksum()}.</li>
 *   <li><b>Repository Logic:</b> Transactional tests for persistence, validation, and lookups.</li>
 * </ul>
 */
@QuarkusTest
class ProductTest {

    @Inject
    EntityManager em;

    // --------------------------------------------------
    // Business Logic Tests (No Database)
    // --------------------------------------------------

    /**
     * Tests {@link Product#standardQuantity(double)} for {@link ProductType#UNIT}.
     * <p>
     * When product type is UNIT, -> quantity should be returned as-is.
     */
    @Test
    void standardQuantity_shouldReturnQuantity_forUnitType() {
        Product product = new Product();
        product.productType = ProductType.UNIT;
        double input =5.0;
        BigDecimal result = product.standardQuantity(input);
        assertEquals(0, new BigDecimal("5.0").compareTo(result));
    }

    /**
     * Tests {@link Product#standardQuantity(double)} for {@link ProductType#WEIGHT}.
     * <p>
     * When product type is WEIGHT, quantity should be divided by reference weight.
     */
    @Test
    void standardQuantity_shouldDivideByRefWeight_forWeightType() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = new BigDecimal("0.500"); // 500g per unit
        // 1.5kg / 0.5kg = 3 units
        BigDecimal result = product.standardQuantity(1.5);
        assertEquals(0, new BigDecimal("3.000000").compareTo(result));
    }

    /**
     * Tests {@link Product#standardQuantity(double)} for {@link ProductType#VOLUME}.
     * <p>
     * Tests that -> else block is executed for VOLUME type.
     * Note: The provided entity code divides by referenceWeight even for VOLUME.
     * This test validates -> current implementation behavior.
     */
    @Test
    void standardQuantity_shouldDivideByRefWeight_forVolumeType() {
        Product product = new Product();
        product.productType = ProductType.VOLUME;
        product.referenceWeight = new BigDecimal("2.0");
        BigDecimal result = product.standardQuantity(10.0);
        assertEquals(0, new BigDecimal("5.000000").compareTo(result));
    }

    /**
     * Tests {@link Product#standardQuantity(double)} when reference weight is null.
     * <p>
     * Should return {@link BigDecimal#ZERO}.
     */
    @Test
    void standardQuantity_shouldReturnZero_ifRefWeightIsNull() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = null;
        BigDecimal result = product.standardQuantity(100.0);
        assertEquals(BigDecimal.ZERO, result);
    }

    /**
     * Tests {@link Product#standardQuantity(double)} when reference weight is zero.
     * <p>
     * Should return {@link BigDecimal#ZERO} to avoid division by zero.
     */
    @Test
    void standardQuantity_shouldReturnZero_ifRefWeightIsZero() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = BigDecimal.ZERO;
        BigDecimal result = product.standardQuantity(100.0);
        assertEquals(BigDecimal.ZERO, result);
    }

    /**
     * Tests {@link Product#getChecksum()} calculation.
     */
    @Test
    void getChecksum_shouldIncludeFields() {
        Product product = new Product();
        product.ean = "1234567890123";
        product.name = "Test Product";
        product.referenceWeight = new BigDecimal("1.5");
        product.productType = ProductType.UNIT;
        int checksum1 = product.getChecksum();
        product.active = false;
        int checksum2 = product.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Database / Repository Query Tests
    // --------------------------------------------------

    /**
     * Tests that {@link Product} can be persisted successfully.
     */
    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Product product = new Product();
        product.ean = "1234567890123";
        product.name = "Orange";
        product.description = "Fresh Orange";
        product.brand = "Sunkist";
        product.referenceWeight = new BigDecimal("1.500");
        product.referenceVolume = new BigDecimal("2.000");
        product.productType = ProductType.WEIGHT;
        product.unitName = "kg";
        product.active = true;
        product.persist();
        // No flush needed here for success, but good practice to verify
        em.flush();
        assertNotNull(product.id);
    }

    /**
     * Tests persistence validation: EAN is mandatory.
     * <p>
     * Note: We call {@code em.flush()} inside the assertion block because
     * JPA constraints are often validated at flush time in Quarkus.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifEanIsNull() {
        Product product = new Product();
        product.ean = null; // Missing mandatory field
        product.name = "Apple";
        product.productType = ProductType.UNIT;
        assertThrows(ConstraintViolationException.class, () -> {
            product.persist();
            em.flush(); // Force validation to trigger immediately
        });
    }

    /**
     * Tests persistence validation: Name is mandatory.
     * <p>
     * Note: We call {@code em.flush()} to trigger validation immediately.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifNameIsNull() {
        Product product = new Product();
        product.ean = "1234567890123";
        product.name = null; // Missing mandatory field
        product.productType = ProductType.UNIT;
        assertThrows(ConstraintViolationException.class, () -> {
            product.persist();
            em.flush(); // Force validation
        });
    }

    /**
     * Tests persistence validation: ProductType is mandatory.
     * <p>
     * Note: We call {@code em.flush()} to trigger validation immediately.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifProductTypeIsNull() {
        Product product = new Product();
        product.ean = "1234567890123";
        product.name = "Banana";
        product.productType = null; // Missing mandatory field
        assertThrows(ConstraintViolationException.class, () -> {
            product.persist();
            em.flush(); // Force validation
        });
    }

    /**
     * Tests {@link Product#findByEan(String)}.
     */
    @Test
    @TestTransaction
    void findByEan_shouldReturnProduct() {
        Product product = new Product();
        product.ean = "9999999999999";
        product.name = "Test Prod";
        product.productType = ProductType.UNIT;
        product.persist();
        em.flush();
        Product result = Product.findByEan("9999999999999");
        assertNotNull(result);
        assertEquals("Test Prod", result.name);
    }

    /**
     * Tests {@link Product#findByEan(String)} returns null for unknown EAN.
     */
    @Test
    @TestTransaction
    void findByEan_shouldReturnNull_ifNotFound() {
        Product result = Product.findByEan("0000000000000");
        assertNull(result);
    }

    /**
     * Tests {@link Product#findActiveByEan(String)} for an active product.
     */
    @Test
    @TestTransaction
    void findActiveByEan_shouldReturnProduct_ifActive() {
        Product product = new Product();
        product.ean = "8888888888888";
        product.name = "Active Prod";
        product.productType = ProductType.UNIT;
        product.active = true;
        product.persist();
        em.flush();
        Product result = Product.findActiveByEan("8888888888888");
        assertNotNull(result);
        assertTrue(result.active);
    }

    /**
     * Tests {@link Product#findActiveByEan(String)} returns null for an inactive product.
     */
    @Test
    @TestTransaction
    void findActiveByEan_shouldReturnNull_ifInactive() {
        Product product = new Product();
        product.ean = "7777777777777";
        product.name = "Inactive Prod";
        product.productType = ProductType.UNIT;
        product.active = false;
        product.persist();
        em.flush();
        Product result = Product.findActiveByEan("7777777777777");
        assertNull(result);
    }

    // --------------------------------------------------
    // Helper / Utils
    // --------------------------------------------------

    /**
     * Tests that constants defined in {@link ProductType} enum exist.
     * <p>
     * Validates -> enum values used in {@link Product#standardQuantity()}.
     */
    @Test
    void productType_shouldContainExpectedValues() {
        assertNotNull(ProductType.UNIT);
        assertNotNull(ProductType.WEIGHT);
        assertNotNull(ProductType.VOLUME);
    }
}