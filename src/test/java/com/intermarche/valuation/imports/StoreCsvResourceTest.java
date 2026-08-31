package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Store;
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
import java.util.*;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link StoreCsvResource}.
 * <p>
 * Tests the CSV import endpoint for Stores, covering creation, updates, embedded address handling,
 * checksum optimization, and error handling mechanisms.
 */
@QuarkusTest
public class StoreCsvResourceTest {

    /**
     * Starts a request already carrying the credentials the import endpoints expect.
     * <p>
     * The import paths sit behind a permission policy that requires an authenticated caller
     * through the basic mechanism. {@code @TestSecurity} injects an identity without going
     * through any mechanism, so it does not satisfy that policy: the call is answered with
     * 401 before the resource is reached. Real credentials are sent instead.
     *
     * @return A request specification authenticated as the bootstrap administrator.
     */
    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("admin", "admin");
    }

    /**
     * The Store CSV resource under test.
     */
    @Inject
    StoreCsvResource storeCsvResource;

    /** Header names of the test rows, in cell order (the canonical store feed). */
    private static final String[] TEST_HEADER = {
            "CODE", "NAME", "STREET_LINE1", "STREET_LINE2", "POSTAL_CODE",
            "CITY", "COUNTRY", "LATITUDE", "LONGITUDE"};

    /**
     * Builds a header-bound row for the importer: the header maps the
     * TEST_HEADER names onto the cell positions and CODE is the key column.
     *
     * @param lineNumber The 1-based line number.
     * @param cells The raw cells of the row.
     * @return The header-bound line.
     */
    private static ImporterCsvResource.LineData line(int lineNumber, String[] cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) {
            header.put(TEST_HEADER[i], i);
        }
        return new ImporterCsvResource.LineData(lineNumber, header, cells, TEST_HEADER[0]);
    }

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
     * Tests the successful import of new stores with valid address details.
     * <p>
     * Verifies that stores are created with correct attributes and embedded address.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewStoresSuccess() {
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n" +
                "S001|Store Paris|1 Rue de Paris|Bat 5|75001|Paris|France|48.8566|2.3522\n" +
                "S002|Store Lyon|1 Rue de Lyon||69001|Lyon|France||";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        // Verify database state
        Store s1 = Store.findByCode("S001");
        assertNotNull(s1);
        assertEquals("Store Paris", s1.name);
        assertNotNull(s1.address);
        assertEquals("1 Rue de Paris", s1.address.streetLine1);
        assertEquals("Bat 5", s1.address.streetLine2);
        assertEquals(48.8566, s1.address.latitude);

        Store s2 = Store.findByCode("S002");
        assertNotNull(s2);
        assertEquals("Store Lyon", s2.name);
        assertEquals("", s2.address.streetLine2);
        assertNull(s2.address.latitude);
    }

    /**
     * Tests the update of an existing store when the incoming data differs.
     * <p>
     * Verifies that a mismatch in checksum triggers a database update.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingStore_WithDifferentChecksum() {
        // 1. Setup: Create a Store
        Long storeId = withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Old Name";
            s.address = new Adresse();
            s.address.city = "Old City";
            s.persist();
            return s.id;
        });

        // 2. Act: Import CSV with different name and city
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n" +
                "S001|New Name|New Street||Zip|New City|Country||";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        // 3. Assert: Check updates
        Store updated = Store.findById(storeId);
        assertNotNull(updated);
        assertEquals("New Name", updated.name);
        assertEquals("New City", updated.address.city);
    }

    /**
     * Tests the update of an existing store when the incoming data is identical.
     * <p>
     * Verifies that a matching checksum skips the database update (optimization).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingStore_WithSameChecksum_NoUpdate() {
        // 1. Setup: Create a Store with a COMPLETE address
        Long storeId = withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store Identique";

            // Création de l'adresse complète
            Adresse address = new Adresse();
            address.streetLine1 = "10 Avenue des Champs-Elysees";
            address.streetLine2 = "Appt 5B";
            address.postalCode = "75008";
            address.city = "Paris";
            address.country = "France";
            address.latitude = 48.8698;
            address.longitude = 2.3075;

            s.address = address;
            s.persist();
            return s.id;
        });

        // 2. Act: Import CSV with SAME data (complete address)
        // LATITUDE/LONGITUDE are optional columns of the feed; here they are
        // present and resolved by name like every other column.
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n" +
                "S001|Store Identique|10 Avenue des Champs-Elysees|Appt 5B|75008|Paris|France|48.8698|2.3075";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests that a strictly identical re-import of a store without address is a no-op.
     * <p>
     * The store is created with a null address (as a seed row without address columns
     * would be), then the same address-less line is imported. The stored checksum of an
     * absent address must equal the checksum the importer computes from blank address
     * columns, so the re-import reports {@code updatedCount:0} rather than a needless update.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportAddresslessStore_NoUpdate() {
        withTransaction(() -> {
            Store s = new Store();
            s.code = "S003";
            s.name = "Store NoAddr";
            s.address = null;
            s.persist();
            return s.id;
        });
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n" +
                "S003|Store NoAddr|||||||";
        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     * <p>
     * Covers: {@code processChunkWithFallback} with empty input.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportStore_EmptyInput() {
        // CSV with only header, no data lines
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the fallback mechanism and specifically {@link StoreCsvResource#findEntityForLine}.
     * <p>
     * Forces the parent class to process lines individually after a transaction rollback.
     * Uses a line with an empty name (violating @NotBlank) to trigger the rollback.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_ThroughFallback() {
        // CSV with:
        // 1. Valid
        // 2. Valid
        // 3. Invalid (Empty Name -> Rollback -> Fallback)
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n" +
                "S001|Store 1|Str1||Zip|City|France||\n" + // Valid
                "S002|Store 2|Str1||Zip|City|France||\n" + // Valid
                "S003| |Str1||Zip|City|France||"; // Invalid (Empty Name)

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));

        assertNotNull(Store.findByCode("S001"));
        assertNotNull(Store.findByCode("S002"));
        assertNull(Store.findByCode("S003"));
    }

    /**
     * Tests {@link StoreCsvResource#processChunkWithFallback} when the parsed lines list is empty.
     * <p>
     * Covers: {@code if (parsedLines.isEmpty()) return new HashMap<>(); }
     */
    @Test
    void testProcessChunkWithFallback_EmptyParsedLines() {
        List<ImporterCsvResource.LineData> parsedLines = Collections.emptyList();
        Set<String> targetCodes = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = storeCsvResource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Tests {@link StoreCsvResource#processChunkWithFallback} when the target codes set is empty.
     * <p>
     * Covers: {@code if (!targetCodes.isEmpty()) } being false in the fetching logic.
     */
    @Test
    void testProcessChunkWithFallback_EmptyTargetCodes() {
        // Create a dummy line so parsedLines is NOT empty
        ImporterCsvResource.LineData line = line(1,
                new String[]{"CODE", "Name", "Str", "", "Zip", "City", "France", "", ""});
        List<ImporterCsvResource.LineData> parsedLines = List.of(line);

        // Explicitly pass an EMPTY set of codes
        Set<String> targetCodes = Collections.emptySet();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = storeCsvResource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        // Map should be empty because bulk fetch was skipped.
        assertTrue(result.isEmpty());
    }

    /**
     * Tests the inherited {@code safeParseDouble} method used for GPS coordinates.
     * <p>
     * Covers valid values, missing cells, empty values, and invalid formats.
     */
    @Test
    void testSafeParseDouble() {
        // Valid case
        assertEquals(12.5, storeCsvResource.safeParseDouble(line(1, new String[]{"12.5"}), "CODE"));

        // Case cell beyond the line's cells (resolved to null)
        assertNull(storeCsvResource.safeParseDouble(line(1, new String[]{"12.5"}), "NAME"));

        // Case val.isEmpty() (true)
        assertNull(storeCsvResource.safeParseDouble(line(1, new String[]{""}), "CODE"));

        // Case NumberFormatException
        assertNull(storeCsvResource.safeParseDouble(line(1, new String[]{"not_a_double"}), "CODE"));
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n" +
                "S001|Store|Str1||Zip|City|France||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/stores/import")
                .then()
                .statusCode(401); // Unauthorized
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

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