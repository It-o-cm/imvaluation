package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.*;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link ProductFamilyCsvResource}.
 * <p>
 * Tests the CSV import endpoint for ProductFamilies, covering creation, updates,
 * relationship linking (Products, Sub-Families), checksum optimization, self-reference validation,
 * and error handling mechanisms.
 */
@QuarkusTest
public class ProductFamilyCsvResourceTest {

    /**
     * The ProductFamily CSV resource under test.
     */
    @Inject
    ProductFamilyCsvResource resource;

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
     * Tests the successful import of new families with valid targets (Products and Sub-Families).
     * <p>
     * Verifies that families are created, linked to entities, and the response reflects the creation count.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewFamiliesSuccess() {
        // Setup: Create referenced entities (Product and Sub-Family)
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "1234567890123";
            p.name = "Test Product";
            p.productType = ProductType.UNIT;
            p.persist();

            ProductFamily sub = new ProductFamily();
            sub.code = "SUB01";
            sub.description = "Sub Family 1";
            sub.persist();
            return true;
        });

        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM01|Family 1|FLAG_A|1234567890123|SUB01\n" +
                "FAM02|Family 2|FLAG_B||"; // No links

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"));

        // Verify database state
        ProductFamily f1 = ProductFamily.findByCode("FAM01");
        assertNotNull(f1);
        assertEquals("Family 1", f1.description);
        assertEquals("FLAG_A", f1.flags);
        assertEquals(1, f1.products.size());
        assertEquals(1, f1.productFamilies.size());
        assertTrue(f1.products.stream().anyMatch(p -> p.ean.equals("1234567890123")));
        assertTrue(f1.productFamilies.stream().anyMatch(sf -> sf.code.equals("SUB01")));

        ProductFamily f2 = ProductFamily.findByCode("FAM02");
        assertNotNull(f2);
        assertTrue(f2.products.isEmpty());
        assertTrue(f2.productFamilies.isEmpty());
    }

    /**
     * Tests the update of an existing family when the incoming data differs.
     * <p>
     * Verifies that a mismatch in checksum triggers a database update.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingFamily_WithDifferentChecksum() {
        // 1. Setup: Create Family
        Long familyId = withTransaction(() -> {
            ProductFamily f = new ProductFamily();
            f.code = "FAM_UPDATE";
            f.description = "Old Desc";
            f.flags = "OLD";
            f.persist();
            return f.id;
        });

        // 2. Act: Import CSV with different description
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM_UPDATE|New Desc|NEW||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        // 3. Assert: Check updates
        ProductFamily updated = ProductFamily.findById(familyId);
        assertNotNull(updated);
        assertEquals("New Desc", updated.description);
        assertEquals("NEW", updated.flags);
    }

    /**
     * Tests the update of an existing family when the incoming data is identical.
     * <p>
     * Verifies that a matching checksum skips the database update (optimization).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingFamily_WithSameChecksum_NoUpdate() {
        // 1. Setup
        Long familyId = withTransaction(() -> {
            ProductFamily f = new ProductFamily();
            f.code = "FAM_SAME";
            f.description = "Desc";
            f.flags = "FLAG";
            f.persist();
            return f.id;
        });

        // 2. Act: Import CSV with SAME data
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM_SAME|Desc|FLAG||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the error handling when a referenced Product does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamily_ProductNotFound() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM_BAD|Bad||9999999999999|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Product EAN '9999999999999' not found"));
    }

    /**
     * Tests the error handling when a referenced Sub-Family does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamily_SubFamilyNotFound() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM_BAD_SUB|Bad Sub|||NO_EXIST";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("SubFamily code 'NO_EXIST' not found"));
    }

    /**
     * Tests the fallback mechanism by mixing valid and invalid lines.
     * <p>
     * Forces the parent class to process lines individually after a transaction rollback.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_ThroughFallback() {
        // Setup Product
        withTransaction(() -> {
            Product p = new Product();
            p.ean = "1111111111111";
            p.name = "Test Prod";
            p.productType = ProductType.UNIT;
            p.persist();
            return true;
        });

        // CSV with:
        // 1. Valid
        // 2. Valid
        // 3. Invalid (Bad Product EAN -> Rollback -> Fallback)
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM01|F1||1111111111111|\n" +
                "FAM02|F2||1111111111111|\n" +
                "FAM03|F3||9999999999999|"; // Invalid Product

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));

        assertNotNull(ProductFamily.findByCode("FAM01"));
        assertNotNull(ProductFamily.findByCode("FAM02"));
        assertNull(ProductFamily.findByCode("FAM03"));
    }

    /**
     * Tests the fallback mechanism specifically for Sub-Family retrieval.
     * <p>
     * Forces the code to enter the 1-by-1 fallback mode, where the bulk context map is missing.
     * This triggers the {@code retrieveSubProductFamilies} method.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRetrieveSubProductFamilies_Fallback_WithSubFamilies() {
        // 1. Setup: Create a Sub-Family in the database
        withTransaction(() -> {
            ProductFamily sub = new ProductFamily();
            sub.code = "SUB_FALLBACK";
            sub.description = "Sub";
            sub.persist();
            return true;
        });

        // 2. Act: Send CSV with a valid line (using the SubFamily) and an invalid line.
        // The invalid line triggers rollback and fallback.
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM_MAIN|Main|||SUB_FALLBACK\n" + // Valid
                "FAM_ERR|Err|||NO_EXIST"; // Invalid

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"errors\""));

        // 3. Assert: Verify that the sub-family was successfully linked via fallback logic.
        ProductFamily f = ProductFamily.findByCode("FAM_MAIN");
        assertNotNull(f);
        assertEquals(1, f.productFamilies.size());
        assertEquals("SUB_FALLBACK", f.productFamilies.iterator().next().code);
    }

    /**
     * Tests {@link ProductFamilyCsvResource#processChunkWithFallback} when the parsed lines list is empty.
     * <p>
     * Covers: {@code if (parsedLines.isEmpty()) return new HashMap<>(); }
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
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Tests {@link ProductFamilyCsvResource#processChunkWithFallback} when the target codes set is empty.
     * <p>
     * Covers: {@code if (!targetCodes.isEmpty()) } being false in the fetching logic.
     */
    @Test
    void testProcessChunkWithFallback_EmptyTargetCodes() {
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "CODE",
                new String[]{"CODE", "Desc", "Flag", "", ""}
        );
        List<ImporterCsvResource.LineData> parsedLines = List.of(line);
        Set<String> targetCodes = Collections.emptySet();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = resource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        // Map should contain the auxiliary maps (Products/SubFamilies) fetched from the line, but no Family map.
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM01|Desc|||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(401); // Unauthorized
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Tests that an existing family cannot be updated to contain itself as a sub-family.
     * <p>
     * Setup: Creates the family in the DB first.
     * Act: Attempts to update it via CSV to include its own code in the sub-family list.
     * Assert: Expects the "cannot contain itself" error and no update to occur.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingFamily_SelfReference() {
        // 1. Setup: Create the Family in the database
        withTransaction(() -> {
            ProductFamily f = new ProductFamily();
            f.code = "FAM_SELF";
            f.description = "Original Desc";
            f.persist();
            return true;
        });

        // 2. Act: Try to update it to link to itself
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "FAM_SELF|Updated Desc|||FAM_SELF"; // Attempting self-reference

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0")) // No update because of the error
                .body(containsString("\"errors\""))
                .body(containsString("cannot contain itself"));

        // 3. Verify: Ensure the original data was NOT updated
        ProductFamily f = ProductFamily.findByCode("FAM_SELF");
        assertNotNull(f);
        assertEquals("Original Desc", f.description); // Should remain unchanged
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