package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.ProductType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link ProductCategoryStorageCsvResource}.
 * <p>
 * Tests the CSV import endpoint for ProductCategoryStorage, covering creation, updates,
 * checksum optimization, validation of Product EANs, and the fallback mechanism.
 */
@QuarkusTest
public class ProductCategoryStorageCsvResourceTest {

    @Inject
    ProductCategoryStorageCsvResource resource;

    @Inject
    TransactionManager tm;

    /**
     * Cleans the database before each test to ensure isolation.
     * <p>
     * Every {@code @QuarkusTest} class shares one application and one database, so this
     * clears the whole reference set rather than only the entities this class handles: a
     * row left by an earlier class would otherwise survive and skew the assertions.
     * <p>
     * The order is the reverse of the dependencies. Prices hold a foreign key on both
     * stores and products, and offers on stores and store groups, so clearing a parent
     * before its children fails on a referential integrity violation.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        ProductCategoryStorage.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Tests the successful import of new category storages using Product EANs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewStoragesSuccess() {
        // Setup: Create a Product with a specific EAN
        String productEan = "1234567890123";
        Long productId = withTransaction(() -> {
            Product p = new Product();
            p.ean = productEan;
            p.name = "Test Product";
            p.productType = ProductType.UNIT;
            p.persist();
            return p.id;
        });

        // CSV uses EAN instead of ID
        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                productEan + "|Food|Fresh|Dairy|Yogurts|Bio\n" +
                productEan + "|Food|Fresh|Dairy|Yogurts|Classic";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"));

        // Verify database state
        List<ProductCategoryStorage> storages = ProductCategoryStorage.list("product.id", productId);
        assertEquals(2, storages.size());
        assertTrue(storages.stream().anyMatch(s -> "Bio".equals(s.level5)));
    }

    /**
     * Tests the update of an existing storage when the incoming data differs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingStorage_WithDifferentChecksum() {
        String productEan = "9876543210987";
        Long productId = withTransaction(() -> {
            Product p = new Product();
            p.ean = productEan;
            p.name = "Update Prod";
            p.productType = ProductType.UNIT;
            p.persist();

            ProductCategoryStorage s = new ProductCategoryStorage();
            s.product = p;
            s.level1 = "Old L1";
            s.level5 = "Old L5";
            s.persist();
            return p.id;
        });

        // CSV with different levels
        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                productEan + "|Old L1|New L2|New L3|New L4|Old L5";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        List<ProductCategoryStorage> storages = ProductCategoryStorage.list("product.id", productId);
        assertEquals(1, storages.size());
        ProductCategoryStorage updated = storages.get(0);
        assertEquals("New L2", updated.level2);
    }

    /**
     * Tests the update of an existing storage when the incoming data is identical.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingStorage_WithSameChecksum_NoUpdate() {
        String productEan = "1111111111111";
        Long productId = withTransaction(() -> {
            Product p = new Product();
            p.ean = productEan;
            p.name = "Same Prod";
            p.productType = ProductType.UNIT;
            p.persist();

            ProductCategoryStorage s = new ProductCategoryStorage();
            s.product = p;
            s.level1 = "L1";
            s.level2 = "L2";
            s.level3 = "L3";
            s.level4 = "L4";
            s.level5 = "L5";
            s.persist();
            return p.id;
        });

        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                productEan + "|L1|L2|L3|L4|L5";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the error handling when the Product EAN does not exist in the database.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportStorage_ProductNotFound() {
        String nonExistentEan = "0000000000000";
        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                nonExistentEan + "|Food|Fresh|Dairy|Yogurts|Bio";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Product with EAN '" + nonExistentEan + "' not found"));
    }

    /**
     * Tests the error handling when the Product EAN is invalid (e.g., empty or null).
     * Note: Since EANs are Strings, format validation is less strict, but an empty string should fail lookup.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportStorage_InvalidProductEan() {
        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                "|Food|Fresh|Dairy|Yogurts|Bio"; // Empty EAN

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Product with EAN '' not found"));
    }

    /**
     * Tests the fallback mechanism by mixing valid and invalid lines.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_ThroughFallback() {
        String validEan = "2222222222222";
        withTransaction(() -> {
            Product p = new Product();
            p.ean = validEan;
            p.name = "Fallback Prod";
            p.productType = ProductType.UNIT;
            p.persist();
            return p.id;
        });

        String invalidEan = "BAD_EAN";

        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                validEan + "|F1|F2|F3|F4|F5\n" +
                validEan + "|G1|G2|G3|G4|G5\n" +
                invalidEan + "|H1|H2|H3|H4|H5"; // Non-existent EAN triggers error

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));

        // Verify valid lines were persisted
        assertEquals(2, ProductCategoryStorage.count());
    }

    /**
     * Tests {@link ProductCategoryStorageCsvResource#processChunkWithFallback} when the parsed lines list is empty.
     */
    @Test
    void testProcessChunkWithFallback_EmptyParsedLines() {
        List<ImporterCsvResource.LineData> parsedLines = Collections.emptyList();
        Set<String> targetCodes = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = resource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests {@link ProductCategoryStorageCsvResource#processChunkWithFallback} when target codes are empty.
     */
    @Test
    void testProcessChunkWithFallback_EmptyTargetCodes() {
        // Even if lines exist, if targetCodes (EANs) is empty, map should be empty
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1, "", new String[]{"", "L1", "L2", "L3", "L4", "L5"}
        );
        List<ImporterCsvResource.LineData> parsedLines = List.of(line);
        Set<String> targetCodes = Collections.emptySet();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = resource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        // The method initializes maps, puts them in context, and returns.
        // CTX_PRODUCTS should be empty because targetCodes is empty.
        assertNotNull(result);
        assertFalse(result.isEmpty()); // Context map contains the keys, but inner maps are empty
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "productEan|level1|level2|level3|level4|level5\n" +
                "123|Food|Fresh|Dairy|Yogurts|Bio";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-category-storages/import")
                .then()
                .statusCode(401); // Unauthorized
    }

    /**
     * Tests that {@link ProductCategoryStorageCsvResource#prepareContextForLine(LineData)}
     * correctly retrieves an existing storage and adds it to the storageMap with the correct key.
     * This verifies the behavior of the "Update" scenario where storage != null.
     */
    @Test
    void testPrepareContextForLine_StorageExists_PopulatesMapCorrectly() {
        // 1. Setup: Create a Product and an existing Storage
        String ean = "5555555555555";
        String l1 = "ExistingL1";
        String l5 = "ExistingL5";

        withTransaction(() -> {
            Product p = new Product();
            p.ean = ean;
            p.name = "Context Test Product";
            p.productType = ProductType.UNIT;
            p.persist();

            ProductCategoryStorage s = new ProductCategoryStorage();
            s.product = p;
            s.level1 = l1;
            s.level5 = l5;
            s.persist();
            return null;
        });
        // 2. Prepare Input Data (LineData) matching the existing entity
        // Array structure: [ean, level1, level2, level3, level4, level5]
        String[] parts = {ean, l1, "L2", "L3", "L4", l5};
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, ean, parts);
        // 3. Execute the method under test
        Map<String, Object> contextMap = resource.prepareContextForLine(data);
        // 4. Verify the Context Map
        assertNotNull(contextMap, "Context map should not be null");
        // Extract the storage map using the constant key (accessible via same package)
        @SuppressWarnings("unchecked")
        Map<String, ProductCategoryStorage> storageMap =
                (Map<String, ProductCategoryStorage>) contextMap.get(ProductCategoryStorageCsvResource.CTX_STORAGES);
        assertNotNull(storageMap, "Storage map should be present in context");
        assertFalse(storageMap.isEmpty(), "Storage map should contain the existing entity");
        // Verify the Key generation logic (EAN + ":" + L1 + ":" + L5)
        String expectedKey = ean + ":" + l1 + ":" + l5;
        assertTrue(storageMap.containsKey(expectedKey), "Storage map should contain the correct composite key");
        // Verify the Content
        ProductCategoryStorage retrievedStorage = storageMap.get(expectedKey);
        assertNotNull(retrievedStorage, "Retrieved storage should not be null");
        assertEquals(l1, retrievedStorage.level1);
        assertEquals(l5, retrievedStorage.level5);
    }

    /**
     * Unit test for {@link ProductCategoryStorageCsvResource#findEntityForLine(LineData)}.
     * Verifies that the SQL query correctly matches the Product ID, Level1, and Level5.
     */
    @Test
    void testFindEntityForLine_MatchExistingStorage() {
        // 1. Setup Database
        String ean = "7777777777777";
        String l1 = "QueryL1";
        String l5 = "QueryL5";

        Long storageId = withTransaction(() -> {
            Product p = new Product();
            p.ean = ean;
            p.name = "Query Test Prod";
            p.productType = ProductType.UNIT;
            p.persist();

            // Create the specific storage we expect to find
            ProductCategoryStorage s = new ProductCategoryStorage();
            s.product = p;
            s.level1 = l1;
            s.level2 = "Other";
            s.level5 = l5;
            s.persist();
            return s.id;
        });

        // 2. Prepare CSV Data that matches the DB record exactly
        // Order: EAN, L1, L2, L3, L4, L5
        String[] parts = {ean, l1, "IrrelevantL2", "IrrelevantL3", "IrrelevantL4", l5};
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, ean, parts);

        // 3. Execute
        ProductCategoryStorage result = (ProductCategoryStorage) resource.findEntityForLine(data);

        // 4. Verify
        assertNotNull(result, "Should find the existing storage");
        assertEquals(storageId, result.id);
        assertEquals(l1, result.level1);
        assertEquals(l5, result.level5);
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Helper method to manually execute logic within a transaction context.
     */
    public <R> R withTransaction(javax.inject.Provider<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (Exception e) {
            try {
                tm.setRollbackOnly();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            throw new RuntimeException(e);
        }
    }
}