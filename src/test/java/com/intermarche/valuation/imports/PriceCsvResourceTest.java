package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link PriceCsvResource}.
 * <p>
 * Tests the CSV import endpoint, covering creation, updates based on composite keys,
 * checksum optimization, and error handling mechanisms.
 */
@QuarkusTest
public class PriceCsvResourceTest {

    /**
     * The Price CSV resource under test.
     */
    @Inject
    PriceCsvResource priceCsvResource;

    /**
     * The TransactionManager for manual transaction control in tests.
     */
    @Inject
    TransactionManager tm;

    /**
     * Cleans the database before each test to ensure isolation.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        Product.deleteAll();
        Store.deleteAll();
    }

    /**
     * Tests the successful import of new prices for valid Product/Store combinations.
     * <p>
     * Verifies that prices are created with correct attributes and linked entities.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewPricesSuccess() {
        // Setup: Create Product and Store
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190123456";
            p.name = "Test Product";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = createTestAddress();
            s.persist();
            return true;
        });

        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190123456|S001|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        // Verify database state
        Price price = (Price)Price.list("product.ean", "3270190123456").get(0);
        assertNotNull(price);
        assertEquals(new BigDecimal("10.0000"), price.priceExcludingTax);
        assertEquals(PriceUsage.DEFAULT, price.priceUsage);
        assertNotNull(price.product);
        assertNotNull(price.store);
    }

    /**
     * Tests the update of an existing price when the incoming data differs (checksum mismatch).
     * <p>
     * Verifies that the price fields are updated in the database.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingPrice_WithDifferentChecksum() {
        // 1. Setup: Create Product, Store, and an existing Price
        Long priceId = withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190999999";
            p.name = "Test Prod";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = createTestAddress();
            s.persist();

            Price price = new Price();
            price.product = p;
            price.store = s;
            price.priceUsage = PriceUsage.DEFAULT;
            price.priority = 0;
            price.startDateTime = LocalDateTime.of(2023, 1, 1, 0, 0);
            price.priceExcludingTax = new BigDecimal("5.00");
            price.priceIncludingTax = new BigDecimal("6.00");
            price.vatRate = new BigDecimal("0.2000");
            price.persist();
            return price.id;
        });

        // 2. Act: Import CSV with different PriceExcludingTax
        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190999999|S001|8.50|10.20|0.2000|DEFAULT|0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        // 3. Assert: Check updates
        Price updated = Price.findById(priceId);
        assertNotNull(updated);
        assertEquals(new BigDecimal("8.50"), updated.priceExcludingTax);
    }

    /**
     * Tests the update of an existing price when the incoming data is identical.
     * <p>
     * Verifies that a matching checksum skips the database update (optimization).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingPrice_WithSameChecksum_NoUpdate() {
        // 1. Setup
        Long priceId = withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190888888";
            p.name = "Test Prod";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = createTestAddress();
            s.persist();

            Price price = new Price();
            price.product = p;
            price.store = s;
            price.priceUsage = PriceUsage.DEFAULT;
            price.priority = 0;
            price.startDateTime = LocalDateTime.of(2023, 1, 1, 0, 0);
            price.priceExcludingTax = new BigDecimal("10.00");
            price.priceIncludingTax = new BigDecimal("12.00");
            price.vatRate = new BigDecimal("0.2000");
            price.persist();
            return price.id;
        });

        // 2. Act: Import CSV with SAME data
        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190888888|S001|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the error handling when the referenced Product does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportPrice_ProductNotFound() {
        // Setup Store only
        withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = createTestAddress();
            s.persist();
            return true;
        });

        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "NON_EXISTENT_EAN|S001|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Product with EAN NON_EXISTENT_EAN not found"));
    }

    /**
     * Tests the error handling when the referenced Store does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportPrice_StoreNotFound() {
        // Setup Product only
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190123456";
            p.name = "Test";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();
            return true;
        });

        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190123456|NON_EXISTENT_STORE|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Store with code NON_EXISTENT_STORE not found"));
    }

    /**
     * Tests the validation rule that PriceUsage is mandatory.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportPrice_PriceUsageMissing() {
        // Setup
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190123456";
            p.name = "Test";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = createTestAddress();
            s.persist();
            return true;
        });

        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190123456|S001|10.00|12.00|0.2000||0|2023-01-01T00:00:00|"; // Empty Usage

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("PriceUsage is mandatory"));
    }

    /**
     * Tests the fallback mechanism and specifically {@link PriceCsvResource#findEntityForLine}.
     * <p>
     * Triggers the fallback by mixing valid lines with an invalid line (missing usage).
     * Forces the parent class to retry processing 1-by-1, invoking the complex lookup in {@code findEntityForLine}.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_ThroughFallback() {
        // Setup
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190123456";
            p.name = "Test";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            s.address = createTestAddress();
            s.persist();
            return true;
        });

        // CSV:
        // 1. Valid
        // 2. Valid (different priority -> new price)
        // 3. Invalid (Empty usage -> Rollback -> Fallback)
        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190123456|S001|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|\n" +
                "3270190123456|S001|10.00|12.00|0.2000|DEFAULT|1|2023-01-01T00:00:00|\n" +
                "3270190123456|S001|10.00|12.00|0.2000||0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));
    }

    /**
     * Tests {@link PriceCsvResource#processChunkWithFallback} when the parsed lines list is empty.
     * <p>
     * Covers: {@code if (parsedLines.isEmpty()) return new HashMap<>(); }
     */
    @Test
    void testProcessChunkWithFallback_EmptyParsedLines() {
        List<ImporterCsvResource.LineData> parsedLines = Collections.emptyList();
        Set<String> targetCodes = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = priceCsvResource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Tests {@link PriceCsvResource#processChunkWithFallback} when the target codes set is empty.
     * <p>
     * Covers: {@code if (!targetCodes.isEmpty()) } being false in the fetching logic.
     */
    @Test
    void testProcessChunkWithFallback_EmptyTargetCodes() {
        // Create a dummy line so parsedLines is NOT empty
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "CODE",
                new String[]{"CODE", "S001", "10.00", "12.00", "0.2", "DEFAULT", "0", "2023-01-01T00:00:00", ""}
        );
        List<ImporterCsvResource.LineData> parsedLines = List.of(line);

        // Explicitly pass an EMPTY set of codes
        Set<String> targetCodes = Collections.emptySet();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = priceCsvResource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        // Map should contain auxiliary maps (Stores/Groups) fetched from the line, but no Offer map.
        // Check that we didn't crash.
    }

    /**
     * Tests {@link PriceCsvResource#safeParsePriceUsage} with an index out of bounds.
     * <p>
     * Covers: {@code if (index >= parts.length) return null; }
     */
    @Test
    void testSafeParsePriceUsage_Bounds() {
        String[] parts = {"Val1", "Val2"};
        assertNull(priceCsvResource.safeParsePriceUsage(parts, 6));
    }

    /**
     * Tests {@link PriceCsvResource#safeParsePriceUsage} with an empty value.
     * <p>
     * Covers: {@code if (val.isEmpty()) return null; }
     */
    @Test
    void testSafeParsePriceUsage_EmptyValue() {
        String[] parts = {"Val1", "Val2", "Val3", "Val4", "Val5", ""};
        assertNull(priceCsvResource.safeParsePriceUsage(parts, 5));
    }

    /**
     * Tests {@link PriceCsvResource#safeParsePriceUsage} with an invalid enum value.
     * <p>
     * Covers: {@code catch (IllegalArgumentException e) }
     */
    @Test
    void testSafeParsePriceUsage_InvalidEnum() {
        String[] parts = {"Val1", "Val2", "Val3", "Val4", "Val5", "INVALID_USAGE"};
        assertNull(priceCsvResource.safeParsePriceUsage(parts, 5));
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190123456|S001|10.00|12.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(401); // Unauthorized
    }

    /**
     * Tests {@link PriceCsvResource#getStoreMap} when the set of store codes is empty.
     * <p>
     * Covers: {@code if (!targetStoreCodes.isEmpty()) } being false.
     */
    @Test
    void testGetStoreMap_EmptyCodes() {
        // Create a line where store code is null/empty
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "EAN1",
                new String[]{"EAN1", null, "10.0", "12.0", "0.2", "DEFAULT", "0", "2023-01-01T00:00:00", ""}
        );

        List<ImporterCsvResource.LineData> lines = List.of(line);
        Set<String> targetCodes = Set.of("EAN1");
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        // Call the method under test via the protected override
        Map<String, Object> result = priceCsvResource.processChunkWithFallback(lines, targetCodes, counters, errors);

        assertNotNull(result);
        // The Store map in the context should be empty because getTargetStoreCodes returned empty
        @SuppressWarnings("unchecked")
        Map<String, Store> storeMap = (Map<String, Store>) result.get("__CTX_STORES__");
        assertTrue(storeMap.isEmpty());
    }

    /**
     * Tests {@link PriceCsvResource#getTargetStoreCodes} with a line where the store code is missing.
     * <p>
     * Covers: {@code String storeCode = safeGet(data.parts, 1); } resulting in null.
     */
    @Test
    void testGetTargetStoreCodes_NullCode() {
        // Line with insufficient columns (Index 1 doesn't exist)
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "EAN1",
                new String[]{"EAN1", "10.0", "12.0"} // Store column missing
        );

        // We can't call getTargetStoreCodes directly (private), but we can observe the result via processChunkWithFallback
        List<ImporterCsvResource.LineData> lines = List.of(line);
        Set<String> targetCodes = Set.of("EAN1");
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        priceCsvResource.processChunkWithFallback(lines, targetCodes, counters, errors);

        // If the loop works correctly with null storeCodes, it should not crash
        // The product fetch will happen, store fetch will skip.
        // We just verify it didn't throw an exception here.
        assertTrue(true);
    }

    /**
     * Tests {@link PriceCsvResource#getStore} when storeCode is null.
     * <p>
     * Covers: {@code if (storeCode != null) { }` being false.
     */
    @Test
    @TestTransaction
    void testGetStore_NullStoreCode() {
        // Setup: Create a ContextMap with a valid Product (to pass Product validation)
        Map<String, Object> entityMap = new HashMap<>();

        Product p = new Product();
        p.ean = "EAN1";
        p.name = "Prod";
        p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
        Panache.getEntityManager().persist(p);
        Panache.getEntityManager().flush(); // Ensure it's visible

        Map<String, Product> pMap = new HashMap<>();
        pMap.put("EAN1", p);
        entityMap.put("__CTX_PRODUCTS__", pMap);

        // Create LineData with null store code at index 1
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "EAN1",
                new String[]{"EAN1", null, "10.0", "12.0", "0.2", "DEFAULT", "0", "2023-01-01T00:00:00", ""}
        );

        int[] counters = {0, 0};

        // Calling processLineLogic directly.
        // It calls getStore which should fail because storeCode is null.
        assertThrows(IllegalArgumentException.class, () -> {
            priceCsvResource.processLineLogic(line, entityMap, counters);
        });
    }

    /**
     * Tests {@link PriceCsvResource#retrievePrices} (fallback mode) when the price does not exist in DB.
     * <p>
     * Covers: {@code if (existing != null) } being false.
     */
    @Test
    void testRetrievePrices_NullExisting() {
        // Setup: Create Product and Store
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "EAN1";
            p.name = "Prod";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store";
            s.address = createTestAddress();
            s.persist();
            return true;
        });

        // Create LineData for a NEW price (not in DB)
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "EAN1",
                new String[]{"EAN1", "S001", "10.0", "12.0", "0.2", "DEFAULT", "0", "2023-01-01T00:00:00", ""}
        );

        // Create a context map WITHOUT the Price map (simulating fallback mode entry)
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("__CTX_PRODUCTS__", Map.of("EAN1", Product.findByEan("EAN1")));
        entityMap.put("__CTX_STORES__", Map.of("S001", Store.findByCode("S001")));

        // Call retrievePrices. Since the map is missing, it fetches from DB.
        // Since the price doesn't exist, existing is null.
        Map<String, Price> priceMap = priceCsvResource.retrievePrices(line, entityMap, "S001");

        assertNotNull(priceMap);
        assertTrue(priceMap.isEmpty()); // The existing block was skipped, map remains empty
    }

    /**
     * Tests {@link PriceCsvResource#findEntityForLine} when storeCode is null.
     * <p>
     * Covers: {@code if (usage == null || storeCode == null) return null; }
     */
    @Test
    void testFindEntityForLine_NullStoreCode() {
        // Create LineData with null store code at index 1
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "EAN1",
                new String[]{"EAN1", null, "10.0", "12.0", "0.2", "DEFAULT", "0", "2023-01-01T00:00:00", ""}
        );

        Object result = priceCsvResource.findEntityForLine(line);
        assertNull(result);
    }

    /**
     * Tests {@link PriceCsvResource#buildPriceKey} handling of null values via Reflection.
     * <p>
     * Since {@code buildPriceKey} is private, reflection is used to verify the exact string generation logic
     * when {@code start} or {@code priority} are null.
     * <p>
     * Covers:
     * - {@code startStr = start != null ? ... : "NULL"} (else branch)
     * - {@code prioStr = priority != null ? ... : "NULL"} (else branch)
     */
    @Test
    void testBuildPriceKey_NullValues_Reflection() throws Exception {
        java.lang.reflect.Method method = PriceCsvResource.class.getDeclaredMethod(
                "buildPriceKey",
                String.class, String.class, PriceUsage.class, LocalDateTime.class, Integer.class
        );
        method.setAccessible(true);

        String ean = "123456";
        String store = "STORE01";
        PriceUsage usage = PriceUsage.DEFAULT;

        // 1. Case: start is null (Covers: startStr = start != null ? ... : "NULL")
        String keyWithNullStart = (String) method.invoke(priceCsvResource, ean, store, usage, null, 0);
        assertTrue(keyWithNullStart.contains("123456:STORE01:DEFAULT:NULL:0"),
                "Key should contain 'NULL' for start date when start is null");

        // 2. Case: priority is null (Covers: prioStr = priority != null ? ... : "NULL")
        LocalDateTime now = LocalDateTime.now();
        String keyWithNullPriority = (String) method.invoke(priceCsvResource, ean, store, usage, now, null);
        assertTrue(keyWithNullPriority.endsWith(":NULL"),
                "Key should end with ':NULL' for priority when priority is null");

        // 3. Case: Both are null
        String keyWithBothNull = (String) method.invoke(priceCsvResource, ean, store, usage, null, null);
        assertTrue(keyWithBothNull.contains(":NULL:NULL"),
                "Key should contain 'NULL' for both start and priority when they are null");
    }

    /**
     * Tests the import of prices where Start Date is null.
     * <p>
     * Verifies that {@code buildPriceKey} correctly handles null start dates (resulting in "NULL" string in the key).
     * This ensures that a price valid from "beginning of time" is treated as a distinct entity.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportPrice_NullStartDate() {
        // Setup
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190000001";
            p.name = "Test";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = "S001";
            s.name = "Store";
            s.address = createTestAddress();
            s.persist();
            return true;
        });

        // CSV:
        // Line 1: Start Date is empty (null)
        // Line 2: Start Date is valid
        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                "3270190000001|S001|10.00|12.00|0.2|DEFAULT|0||\n" + // Start null
                "3270190000001|S001|15.00|18.00|0.2|DEFAULT|0|2023-01-01T00:00:00|"; // Start valid

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"));

        // Verify we have 2 distinct prices because the keys (handling null start) differ
        List<Price> prices = Price.list("product.ean", "3270190000001");
        assertEquals(2, prices.size());

        // Verify specific states
        Price nullStartPrice = prices.stream().filter(p -> p.startDateTime == null).findFirst().orElse(null);
        assertNotNull(nullStartPrice);
        assertEquals(new BigDecimal("10.0000"), nullStartPrice.priceExcludingTax);

        Price validStartPrice = prices.stream().filter(p -> p.startDateTime != null).findFirst().orElse(null);
        assertNotNull(validStartPrice);
        assertEquals(new BigDecimal("15.0000"), validStartPrice.priceExcludingTax);
    }

    /**
     * Tests {@link PriceCsvResource#retrievePrices} in fallback mode when a price already exists.
     * <p>
     * This test forces the 1-by-1 fallback (bulk failure) and processes a line that matches
     * an existing price in the database. This triggers the execution path:
     * {@code if (priceMap == null) { ... existing = Price.find(...) ... if (existing != null) { ... put ... } }.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRetrievePrices_Fallback_WithExistingPrice() {
        // 1. Setup: Create a valid Product, Store, and an EXISTING Price.
        String ean = "3270190555555";
        String storeCode = "S005";

        withTransaction(() -> {
            Product p = new Product();
            p.ean = ean;
            p.name = "Existing Product";
            p.productType = com.intermarche.valuation.domain.ProductType.UNIT;
            p.persist();

            Store s = new Store();
            s.code = storeCode;
            s.name = "Store 5";
            s.address = createTestAddress();
            s.persist();

            Price existingPrice = new Price();
            existingPrice.product = p;
            existingPrice.store = s;
            existingPrice.priceUsage = PriceUsage.DEFAULT;
            existingPrice.priority = 0;
            existingPrice.startDateTime = LocalDateTime.of(2023, 1, 1, 0, 0);
            existingPrice.priceExcludingTax = new BigDecimal("10.00");
            existingPrice.priceIncludingTax = new BigDecimal("12.00");
            existingPrice.vatRate = new BigDecimal("0.2000");
            existingPrice.persist();
            return true;
        });

        // 2. Act: CSV with mixed lines
        // Line 1: Valid, matches existing price (Will force retrievePrices to fetch it).
        // Line 2: Invalid (Invalid Usage -> Rollback -> Fallback).
        String csvContent = "ean|store|priceET|priceIT|vat|usage|priority|start|end\n" +
                ean + "|" + storeCode + "|15.00|18.00|0.2000|DEFAULT|0|2023-01-01T00:00:00|\n" + // Valid (Update)
                "BAD_EAN|S005|10.00|12.00|0.2000|INVALID_USAGE|0|2023-01-01T00:00:00|"; // Invalid (Trigger Fallback)

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/prices/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1")) // The existing price was updated
                .body(containsString("\"errors\""));

        // 3. Assert: Verify the update happened.
        // This proves that retrievePrices found the existing price, built the key, and put it in the map,
        // allowing the checksum logic to detect the difference and update the entity.
        Price updated = (Price)Price.list("product.ean", ean).get(0);
        assertNotNull(updated);
        assertEquals(new BigDecimal("15.0000"), updated.priceExcludingTax);
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Creates a valid {@link Adresse} object for use in test data.
     *
     * @return A new Address instance.
     */
    private Adresse createTestAddress() {
        Adresse a = new Adresse();
        a.streetLine1 = "1 Test Street";
        a.city = "Paris";
        a.postalCode = "75000";
        a.country = "France";
        return a;
    }

    /**
     * Helper method to manually execute logic within a transaction context.
     * Used for test setup and teardown operations.
     *
     * @param runnable The logic to execute.
     * @param <R>      The return type of the logic.
     * @return The result of the execution.
     */
    public <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (NotSupportedException | SystemException e) {
            // Technical error
            throw new RuntimeException(e);
        } catch (Exception e) {
            try {
                tm.setRollbackOnly();
            } catch (SystemException ex) {
                throw new RuntimeException(e);
            }
            throw new RuntimeException(e);
        }
    }
}