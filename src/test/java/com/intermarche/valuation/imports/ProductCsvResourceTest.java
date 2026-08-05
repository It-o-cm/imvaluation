package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
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
import java.util.*;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link ProductCsvResource}.
 * <p>
 * Tests the CSV import endpoint, covering creation, updates, checksum optimization,
 * and error handling mechanisms.
 */
@QuarkusTest
public class ProductCsvResourceTest {

    /**
     * The Product CSV resource under test.
     */
    @Inject
    ProductCsvResource productCsvResource;

    /**
     * The TransactionManager for manual transaction control in tests.
     */
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
     * Tests the successful import of new products from a valid CSV stream.
     * <p>
     * Verifies that products are created with correct attributes and that
     * the response reflects the creation count.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewProductsSuccess() {
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "3270190123456|Yaourt Nature|Yaourt fermier|Brand A|0.5|null|WEIGHT|kg|true\n" +
                "3270190123457|Miel Pot 500g|Miel de montagne|Brand B|null|0.5|UNIT|pcs|true";
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
        // Verify database state
        Product p1 = Product.findByEan("3270190123456");
        assertNotNull(p1);
        assertEquals("Yaourt Nature", p1.name);
        assertEquals(ProductType.WEIGHT, p1.productType);
        assertEquals(new BigDecimal("0.500"), p1.referenceWeight);
        assertNull(p1.referenceVolume);
        Product p2 = Product.findByEan("3270190123457");
        assertNotNull(p2);
        assertEquals("Miel Pot 500g", p2.name);
        assertEquals(ProductType.UNIT, p2.productType);
        assertEquals(new BigDecimal("0.500"), p2.referenceVolume);
        assertNull(p2.referenceWeight);
    }

    /**
     * Tests the update of an existing product via CSV import.
     * <p>
     * Ensures that when an EAN already exists, its attributes are updated
     * based on the CSV data.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingProduct() {
        // 1. Setup: Create a product
        Long existingId = withTransaction(() -> {
            Product existing = new Product();
            existing.ean = "3270190123458";
            existing.name = "Old Name";
            existing.description = "Old Desc";
            existing.brand = "Brand X";
            existing.referenceWeight = new BigDecimal("1.0");
            existing.productType = ProductType.UNIT;
            existing.active = true;
            existing.persist();
            Panache.getEntityManager().flush();
            return existing.id;
        });
        // 2. Act: Import CSV with same EAN but different data
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "3270190123458|New Name|Updated Desc|Brand X|1.2|null|UNIT|pcs|true";
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));
        // 3. Assert: Check updates
        Product updated = Product.findById(existingId);
        assertEquals("New Name", updated.name);
        assertEquals("Updated Desc", updated.description);
        assertEquals(new BigDecimal("1.2"), updated.referenceWeight);
    }

    /**
     * Tests the import of a file containing valid and invalid data rows.
     * <p>
     * Verifies that valid rows are processed and invalid rows (e.g., bad enum) trigger
     * the fallback mechanism and are added to the error list without stopping the whole import.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportWithMalformedData_FallbackAndErrors() {
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "3270190111111|Valid Product|Desc|Brand|1.0||UNIT|pcs|true\n" + // Valid
                "3270190222222|Bad Enum|Desc|Brand|1.0||INVALID_ENUM|pcs|true";  // Invalid Enum
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));
        // Verify only the valid product was created
        assertNotNull(Product.findByEan("3270190111111"));
        assertNull(Product.findByEan("3270190222222"));
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     * <p>
     * Covers:
     * - {@code ImporterCsvResource#processChunkWithFallback}: parsedLines.isEmpty() == true
     * - {@code ImporterCsvResource#processChunkWithFallback}: !targetEans.isEmpty() == false
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testProcessChunkWithFallback_EmptyInput() {
        // CSV with only header, no data lines
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n";
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the fallback mechanism and specifically {@link ProductCsvResource#findEntityForLine}.
     * <p>
     * To trigger the fallback (1-by-1 processing), we inject a line that causes a transaction rollback
     * (e.g., a ConstraintViolation due to a null mandatory field). This forces the parent class to
     * retry processing the chunk line by line, which invokes {@code findEntityForLine}.
     * <p>
     * Covers: {@code findEntityForLine} being called.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_ThroughFallback() {
        // Setup: Create an existing product to ensure we test the "Update/Find" path in fallback as well
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "3270190999999";
            p.name = "Existing Product";
            p.productType = ProductType.UNIT;
            p.active = true;
            Panache.getEntityManager().persist(p);
            return true;
        });
        // CSV with:
        // 1. A valid line (update existing)
        // 2. A valid line (new)
        // 3. An invalid line (Empty Name -> @NotBlank violation -> Rollback -> Fallback)
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "3270190999999|Updated Name|Desc|Brand|1||UNIT|pcs|true\n" + // Update
                "3270190888888|New Product|Desc|Brand|1||UNIT|pcs|true\n" +   // Create
                "3270190777777| |Desc|Brand|1||UNIT|pcs|true";               // Invalid (Empty Name)
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))   // New Product created via fallback
                .body(containsString("\"updatedCount\":1"))   // Existing Product updated via fallback
                .body(containsString("\"errors\""))          // Invalid line added to errors
                .body(containsString("Could not commit transaction")); // Depending on how parser handles empty cols
        // Verify DB state to ensure fallback worked correctly
        Product updated = Product.findByEan("3270190999999");
        assertEquals("Updated Name", updated.name);
        Product newProduct = Product.findByEan("3270190888888");
        assertNotNull(newProduct);
    }

    /**
     * Tests updating an existing product when the incoming data differs from the stored data.
     * <p>
     * Verifies that a mismatch in checksum triggers a database update.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingProduct_WithDifferentChecksum() throws Exception {
        // 1. SETUP: Use withTransaction to create the product
        Long productId = withTransaction(() -> {
            Product existing = new Product();
            existing.ean = "3270190123458";
            existing.name = "Ancien Nom";
            existing.description = "Ancienne description";
            existing.brand = "Ancienne Marque";
            existing.referenceWeight = new BigDecimal("1.0");
            existing.referenceVolume = null;
            existing.productType = ProductType.UNIT;
            existing.unitName = "pcs";
            existing.active = true;
            Panache.getEntityManager().persist(existing);
            // Return the ID to use it in the final assertion
            return existing.id;
        });
        // 2. ACTION: Send CSV with MODIFIED data
        // The checksum will be different -> Update expected
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "\n" +
                "3270190123458|Nouveau Nom|Nouvelle description|Nouvelle Marque|2.0|null|UNIT|pcs|true";
        given()
                .auth().preemptive().basic("admin", "admin")
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));
        // 3. ASSERT: Verify that the database was updated via withTransaction
        withTransaction(() -> {
            Product updated = Panache.getEntityManager().find(Product.class, productId);
            assertNotNull(updated);
            assertEquals("Nouveau Nom", updated.name);
            assertEquals(new BigDecimal("2.0"), updated.referenceWeight);
            return true;
        });
    }

    /**
     * Tests updating an existing product when the incoming data is identical to the stored data.
     * <p>
     * Verifies that a matching checksum skips the database update (optimization).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingProduct_WithSameChecksum_NoUpdate() {
        // 1. SETUP: Create a product
        Long productId = withTransaction(() -> {
            Product existing = new Product();
            existing.ean = "3270190123459";
            existing.name = "Produit Identique";
            existing.description = "Desc";
            existing.brand = "Brand";
            existing.referenceWeight = new BigDecimal("0.5");
            existing.referenceVolume = new BigDecimal("1.0");
            existing.productType = ProductType.VOLUME;
            existing.unitName = "L";
            existing.active = true;
            Panache.getEntityManager().persist(existing);
            return existing.id;
        });
        // 2. ACTION: Send CSV with SAME data
        // The checksum will be identical -> NO update (Optimization)
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "3270190123459|Produit Identique|Desc|Brand|0.5|1.0|VOLUME|L|true";
        given()
                .auth().preemptive().basic("admin", "admin")
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
        // 3. ASSERT: Verify that the object is the same (not modified)
        withTransaction(() -> {
            Product unchanged = Panache.getEntityManager().find(Product.class, productId);
            assertNotNull(unchanged);
            assertEquals("Produit Identique", unchanged.name);
            return true;
        });
    }

    /**
     * Tests the import behavior when a line has fewer columns than expected.
     * <p>
     * Verifies that lines with insufficient columns are ignored and reported as errors.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNotEnoughColumns() {
        // Case 1: A line with fewer than 9 columns (missing separators)
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit\n" + // Incorrect Header (8 columns)
                "3270190123456|Yaourt Nature|Yaourt fermier|Brand A|0.5|null|WEIGHT|kg"; // Incorrect Data
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("not enough columns")); // Verify specific error message
    }

    /**
     * Tests {@link ProductCsvResource#processChunkWithFallback} when the parsed lines list is empty.
     * <p>
     * Covers: {@code if (parsedLines.isEmpty()) return new HashMap<>(); }
     * <p>
     * Note: This scenario is theoretically impossible via the REST endpoint because the parent class
     * {@link ImporterCsvResource} checks for empty lists before calling this method. However, this test
     * ensures the defensive check works if the method is called directly.
     */
    @Test
    void testProcessChunkWithFallback_EmptyParsedLines() {
        List<ImporterCsvResource.LineData> parsedLines = Collections.emptyList();
        Set<String> targetEans = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        Map<String, Object> result = productCsvResource.processChunkWithFallback(parsedLines, targetEans, counters, errors);
        assertNotNull(result);
        assertTrue(result.isEmpty());
        // Verify that the method returns early without modifying counters
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Tests {@link ProductCsvResource#processChunkWithFallback} when the target EANs set is empty.
     * <p>
     * Covers: {@code if (!targetEans.isEmpty()) } being false.
     * <p>
     * This forces the method to skip the database bulk fetch.
     */
    @Test
    void testProcessChunkWithFallback_EmptyTargetEans() {
        // Create a dummy line so parsedLines is NOT empty (passes the first check)
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "CODE",
                new String[]{"CODE", "Name", "Desc", "Brand", "1.0", "", "UNIT", "pcs", "true"}
        );
        List<ImporterCsvResource.LineData> parsedLines = List.of(line);
        // Explicitly pass an EMPTY set of EANs to trigger the specific condition
        Set<String> targetEans = Collections.emptySet();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        Map<String, Object> result = productCsvResource.processChunkWithFallback(parsedLines, targetEans, counters, errors);
        assertNotNull(result);
        assertTrue(result.isEmpty()); // No DB fetch happened, so map is empty
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "ean|name|description|brand|refWeight|refVolume|type|unit|active\n" +
                "3270190444444|Test|Desc|Brand|1.0||UNIT|pcs|true";
        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/products/import")
                .then()
                .statusCode(401); // Forbidden
    }

    /**
     * Tests {@link ProductCsvResource#safeParseProductType} with an index out of bounds.
     * <p>
     * Covers: {@code if (index >= parts.length) return null;}
     */
    @Test
    void testSafeParseProductType_Bounds() {
        // Array with 2 elements, trying to access index 6
        String[] parts = {"Val1", "Val2"};
        assertNull(productCsvResource.safeParseProductType(parts, 6));
    }

    /**
     * Tests {@link ProductCsvResource#safeParseProductType} with an empty value.
     * <p>
     * Covers: {@code if (val.isEmpty()) return null;}
     */
    @Test
    void testSafeParseProductType_EmptyValue() {
        // Array with enough elements, but the value at index 6 is empty
        String[] parts = {"Val1", "Val2", "Val3", "Val4", "Val5", "Val6", "", "Val8", "Val9"};
        assertNull(productCsvResource.safeParseProductType(parts, 6));
    }

    /**
     * Tests {@link ProductCsvResource#safeParseProductType} with an invalid enum value.
     * <p>
     * Covers: {@code catch (IllegalArgumentException e)}
     */
    @Test
    void testSafeParseProductType_InvalidEnum() {
        // Valid string that does not match any ProductType enum constant
        String[] parts = {"Val1", "Val2", "Val3", "Val4", "Val5", "Val6", "INVALID_ENUM_TYPE", "Val8", "Val9"};
        assertNull(productCsvResource.safeParseProductType(parts, 6));
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