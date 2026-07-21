package com.intermarche.valuation.domain;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration test class for {@link Price}.
 * <p>
 * Uses {@code @QuarkusTest} for application context.
 * Takes into account the structure of {@link Product} and {@link Store}.
 * Splits testing into:
 * <ul>
 *   <li><b>Business Logic:</b> Tests for {@code isActive()} logic and checksum calculation.</li>
 *   <li><b>Repository Logic:</b> Transactional tests for database queries, date validity, and priority handling.</li>
 * </ul>
 */
@QuarkusTest
class PriceTest {

    @Inject
    EntityManager em;

    /**
     * Resets static {@link DateTimeProvider} after each test
     * to prevent side effects between time-dependent tests.
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Creates and persists a valid {@link Product} entity for testing.
     * <p>
     * Sets mandatory fields required by the {@link Product} entity: {@code ean}, {@code name}, and {@code productType}.
     *
     * @param ean The EAN of the product.
     * @return The persisted Product instance.
     */
    private Product createTestProduct(String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + ean;
        product.productType = ProductType.UNIT; // productType is mandatory in Product
        product.persist();
        return product;
    }

    /**
     * Creates and persists a valid {@link Store} entity for testing.
     *
     * @param code The code of the store.
     * @return The persisted Store instance.
     */
    private Store createTestStore(String code) {
        Store store = new Store();
        store.code = code;
        store.name = "Store " + code;
        store.address = new Adresse(); // Assumes Adresse is an Embeddable class
        store.persist();
        return store;
    }

    /**
     * Creates a price with default monetary values, ready for persistence.
     * <p>
     * Sets tax and VAT to dummy values.
     */
    private Price createPrice(Product product, Store store, PriceUsage usage, Integer priority) {
        Price price = new Price();
        price.product = product;
        price.store = store;
        price.priceUsage = usage;
        price.priceExcludingTax = new BigDecimal("10.00");
        price.priceIncludingTax = new BigDecimal("12.00");
        price.vatRate = new BigDecimal("0.2000");
        price.priority = priority;
        return price;
    }

    // --------------------------------------------------
    // Business Logic Tests (No Database needed for simple logic)
    // --------------------------------------------------

    /**
     * Tests {@link Price#isActive()} when current time is strictly within validity interval.
     */
    @Test
    @TestTransaction
    void isActive_shouldReturnTrue_whenCurrentTimeIsWithinValidityInterval() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = now.minusDays(1); // Started yesterday
        price.endDateTime = now.plusDays(1);   // Ends tomorrow
        assertTrue(price.isActive());
    }

    /**
     * Tests {@link Price#isActive()} when start time is in the future.
     */
    @Test
    @TestTransaction
    void isActive_shouldReturnFalse_whenStartTimeIsInFuture() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = now.plusHours(1); // Starts in 1 hour
        assertFalse(price.isActive());
    }

    /**
     * Tests {@link Price#isActive()} when end time has passed.
     */
    @Test
    @TestTransaction
    void isActive_shouldReturnFalse_whenEndTimeHasPassed() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = now.minusDays(2);
        price.endDateTime = now.minusHours(1); // Ended 1 hour ago
        assertFalse(price.isActive());
    }

    /**
     * Tests {@link Price#isActive()} validity at the exact start boundary.
     * <p>
     * Code uses `startDateTime <= now`. Equality should return true.
     */
    @Test
    @TestTransaction
    void isActive_shouldReturnTrue_whenCurrentTimeIsExactlyAtStart() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = now; // Starts exactly now
        price.endDateTime = null;
        assertTrue(price.isActive());
    }

    /**
     * Tests {@link Price#isActive()} validity at the exact end boundary.
     * <p>
     * Code uses `endDateTime > now`. Equality should return false.
     */
    @Test
    @TestTransaction
    void isActive_shouldReturnFalse_whenCurrentTimeIsExactlyAtEnd() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = now.minusDays(1);
        price.endDateTime = now; // Ends exactly now
        assertFalse(price.isActive());
    }

    /**
     * Tests {@link Price#isActive()} with null start and end dates.
     */
    @Test
    @TestTransaction
    void isActive_shouldReturnTrue_withNullDates() {
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2023, 5, 10, 12, 0));
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = null;
        price.endDateTime = null;
        assertTrue(price.isActive());
    }

    /**
     * Tests {@link Price#getChecksum()} calculation.
     * <p>
     * Verifies that the checksum includes {@code product.ean} and {@code store.code}.
     */
    @Test
    void getChecksum_shouldIncludeFields() {
        Product p = new Product();
        p.ean = "1234567890123";
        p.productType = ProductType.UNIT;
        Store s = new Store();
        s.code = "S1";
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 5);
        int checksum1 = price.getChecksum();
        price.priority = 10;
        int checksum2 = price.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Database / Repository Query Tests
    // --------------------------------------------------

    /**
     * Tests {@link Price#findActivePriceAtDate(Long, Long, LocalDateTime, PriceUsage)}.
     * <p>
     * Verifies basic retrieval of a price that matches all criteria.
     */
    @Test
    @TestTransaction
    void findActivePriceAtDate_shouldReturnMatchingPrice() {
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        LocalDateTime targetDate = LocalDateTime.of(2023, 5, 10, 12, 0);
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = targetDate.minusDays(1);
        price.endDateTime = targetDate.plusDays(1);
        price.persist();
        em.flush();
        Price result = Price.findActivePriceAtDate(p.id, s.id, targetDate, PriceUsage.DEFAULT);
        assertNotNull(result);
        assertEquals(p.id, result.product.id);
    }

    /**
     * Tests {@link Price#findActivePriceAtDate(Long, Long, LocalDateTime, PriceUsage)} priority logic.
     * <p>
     * If multiple prices are valid, the one with the HIGHEST priority must be returned.
     */
    @Test
    @TestTransaction
    void findActivePriceAtDate_shouldReturnHighestPriorityPrice() {
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        LocalDateTime targetDate = LocalDateTime.of(2023, 5, 10, 12, 0);
        Price lowPriority = createPrice(p, s, PriceUsage.DEFAULT, 0);
        lowPriority.startDateTime = targetDate.minusDays(1);
        lowPriority.endDateTime = targetDate.plusDays(1);
        lowPriority.persist();
        Price highPriority = createPrice(p, s, PriceUsage.DEFAULT, 10);
        highPriority.startDateTime = targetDate.minusDays(1);
        highPriority.endDateTime = targetDate.plusDays(1);
        highPriority.persist();
        em.flush();
        Price result = Price.findActivePriceAtDate(p.id, s.id, targetDate, PriceUsage.DEFAULT);
        assertNotNull(result);
        assertEquals(10, result.priority, "Should return price with higher priority");
    }

    /**
     * Tests {@link Price#findActivePriceAtDate(Long, Long, LocalDateTime, PriceUsage)} date validity (Start Date).
     * <p>
     * Returns null if {@code startDateTime} is after {@code targetDate}.
     */
    @Test
    @TestTransaction
    void findActivePriceAtDate_shouldReturnNull_ifStartDateIsInFuture() {
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        LocalDateTime targetDate = LocalDateTime.of(2023, 5, 10, 12, 0);
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = targetDate.plusDays(1); // Starts in future relative to target
        price.persist();
        em.flush();
        Price result = Price.findActivePriceAtDate(p.id, s.id, targetDate, PriceUsage.DEFAULT);
        assertNull(result);
    }

    /**
     * Tests {@link Price#findActivePriceAtDate(Long, Long, LocalDateTime, PriceUsage)} date validity (End Date).
     * <p>
     * Returns null if {@code endDateTime} is before or equal to {@code targetDate}.
     */
    @Test
    @TestTransaction
    void findActivePriceAtDate_shouldReturnNull_ifEndDateHasPassed() {
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        LocalDateTime targetDate = LocalDateTime.of(2023, 5, 10, 12, 0);
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = targetDate.minusDays(2);
        price.endDateTime = targetDate.minusHours(1); // Ended before target date
        price.persist();
        em.flush();
        Price result = Price.findActivePriceAtDate(p.id, s.id, targetDate, PriceUsage.DEFAULT);
        assertNull(result);
    }

    /**
     * Tests {@link Price#findActivePriceAtDate(Long, Long, LocalDateTime, PriceUsage)} handling of null Start Date.
     * <p>
     * Treats null start date as beginning of time (-infinity).
     */
    @Test
    @TestTransaction
    void findActivePriceAtDate_shouldWorkWithNullStartDate() {
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        LocalDateTime targetDate = LocalDateTime.of(2023, 5, 10, 12, 0);
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = null; // No start date (always valid from past)
        price.endDateTime = targetDate.plusDays(1);
        price.persist();
        em.flush();
        Price result = Price.findActivePriceAtDate(p.id, s.id, targetDate, PriceUsage.DEFAULT);
        assertNotNull(result);
    }

    /**
     * Tests {@link Price#findActivePriceAtDate(Long, Long, LocalDateTime, PriceUsage)} handling of null End Date.
     * <p>
     * Treats null end date as end of time (+infinity).
     */
    @Test
    @TestTransaction
    void findActivePriceAtDate_shouldWorkWithNullEndDate() {
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        LocalDateTime targetDate = LocalDateTime.of(2023, 5, 10, 12, 0);
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = targetDate.minusDays(1);
        price.endDateTime = null; // No end date (always valid to future)
        price.persist();
        em.flush();
        Price result = Price.findActivePriceAtDate(p.id, s.id, targetDate, PriceUsage.DEFAULT);
        assertNotNull(result);
    }

    /**
     * Tests {@link Price#findCurrentPrice(Long, Long)}.
     * <p>
     * Uses {@link DateTimeProvider} to determine "now".
     */
    @Test
    @TestTransaction
    void findCurrentPrice_shouldUseProviderNow() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        Price price = createPrice(p, s, PriceUsage.DEFAULT, 0);
        price.startDateTime = now.minusDays(1);
        price.endDateTime = now.plusDays(1);
        price.persist();
        em.flush();
        Price result = Price.findCurrentPrice(p.id, s.id);
        assertNotNull(result);
        assertEquals(price.id, result.id);
    }

    /**
     * Tests {@link Price#findCurrentPrice(Long, Long, PriceUsage)} with specific usage.
     */
    @Test
    @TestTransaction
    void findCurrentPrice_shouldFilterByUsage() {
        LocalDateTime now = LocalDateTime.of(2023, 5, 10, 12, 0);
        DateTimeProvider.setFixedDateTime(now);
        Product p = createTestProduct("1234567890123");
        Store s = createTestStore("S1");
        // Create a DEFAULT price
        Price defaultPrice = createPrice(p, s, PriceUsage.DEFAULT, 0);
        defaultPrice.startDateTime = now.minusDays(1);
        defaultPrice.persist();
        // Create a PROMO price
        Price promoPrice = createPrice(p, s, PriceUsage.BASE_FOR_DISCOUNT, 0);
        promoPrice.startDateTime = now.minusDays(1);
        promoPrice.persist();
        em.flush();
        // Query for DEFAULT usage
        Price result = Price.findCurrentPrice(p.id, s.id, PriceUsage.DEFAULT);
        assertNotNull(result);
        assertEquals(PriceUsage.DEFAULT, result.priceUsage);
    }
}