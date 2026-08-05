package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
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
 * Test class for {@link OfferCsvResource}.
 * <p>
 * Tests the CSV import endpoint, covering creation, updates, relationship linking (Stores, StoreGroups),
 * checksum optimization, and error handling mechanisms.
 */
@QuarkusTest
public class OfferCsvResourceTest {

    /**
     * The Offer CSV resource under test.
     */
    @Inject
    OfferCsvResource offerCsvResource;

    /**
     * The TransactionManager for manual transaction control in tests.
     */
    @Inject
    TransactionManager tm;

    /**
     * Cleans the database before each test to ensure isolation.
     * <p>
     * Rows are removed in reverse dependency order. Prices come first because they hold a
     * foreign key on stores: clearing stores while a price still points at one fails on a
     * referential integrity violation. This test creates no price itself, but every
     * {@code @QuarkusTest} class shares one database, so any class that ran before may have
     * left some.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Price.deleteAll();
        Offer.deleteAll();
        Store.deleteAll();
        StoreGroup.deleteAll();
    }

    /**
     * Starts a request already carrying the credentials the import endpoints expect.
     * <p>
     * The import paths sit behind the {@code api} permission policy, which requires an
     * authenticated caller through the basic mechanism; an anonymous call is answered with
     * 401 before it ever reaches the resource.
     *
     * @return A request specification authenticated as the bootstrap administrator.
     */
    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("admin", "admin");
    }

    /**
     * Tests the successful import of new offers with valid targets (Stores and Groups).
     * <p>
     * Verifies that offers are created, linked to entities, and the response reflects the creation count.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewOffersSuccess() {
        // Setup: Create referenced entities
        withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            // ADDED: Assign address to the store
            s.address = new Adresse();
            s.address.streetLine1 = "1 Test Street";
            s.address.city = "Paris";
            s.address.postalCode = "75000";
            s.address.country = "France";
            s.persist();

            StoreGroup g = new StoreGroup();
            g.code = "G001";
            g.name = "Group 1";
            g.persist();
            return true;
        });

        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER01|PROMO|{\"ean\":\"123\"}|S001|G001\n" +
                "OFFER02|DISCOUNT|{\"ean\":\"456\"}|S001|"; // Only store

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        // Verify database state
        Offer o1 = Offer.findByCode("OFFER01");
        assertNotNull(o1);
        assertEquals("PROMO", o1.type);
        assertEquals("{\"ean\":\"123\"}", o1.specification);
        assertEquals(1, o1.stores.size());
        assertEquals(1, o1.storeGroups.size());
        assertTrue(o1.stores.stream().anyMatch(s -> s.code.equals("S001")));

        Offer o2 = Offer.findByCode("OFFER02");
        assertNotNull(o2);
        assertEquals(1, o2.stores.size());
        assertTrue(o2.storeGroups.isEmpty());
    }

    /**
     * Tests the update of an existing offer when the incoming data differs.
     * <p>
     * Verifies that a mismatch in checksum triggers a database update.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingOffer_WithDifferentChecksum() {
        // 1. Setup: Create Offer and a Store
        Long offerId = withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            // ADDED: Assign address to the store
            s.address = new Adresse();
            s.address.streetLine1 = "1 Test Street";
            s.address.city = "Paris";
            s.address.postalCode = "75000";
            s.address.country = "France";
            s.persist();

            Offer o = new Offer();
            o.code = "OFFER_UPDATE";
            o.type = "OLD_TYPE";
            o.specification = "{}";
            o.stores.add(s);
            o.persist();
            return o.id;
        });

        // 2. Act: Import CSV with different type and spec
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER_UPDATE|NEW_TYPE|{\"new\":\"data\"}|S001|";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        // 3. Assert: Check updates
        Offer updated = Offer.findById(offerId);
        assertNotNull(updated);
        assertEquals("NEW_TYPE", updated.type);
        assertEquals("{\"new\":\"data\"}", updated.specification);
    }

    /**
     * Tests the update of an existing offer when the incoming data is identical.
     * <p>
     * Verifies that a matching checksum skips the database update (optimization).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingOffer_WithSameChecksum_NoUpdate() {
        // 1. Setup
        Long offerId = withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            // ADDED: Assign address to the store
            s.address = new Adresse();
            s.address.streetLine1 = "1 Test Street";
            s.address.city = "Paris";
            s.address.postalCode = "75000";
            s.address.country = "France";
            s.persist();

            Offer o = new Offer();
            o.code = "OFFER_SAME";
            o.type = "TYPE";
            o.specification = "{}";
            o.stores.add(s);
            o.persist();
            return o.id;
        });

        // 2. Act: Import CSV with SAME data
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER_SAME|TYPE|{}|S001|";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"));
    }

    /**
     * Tests the validation rule requiring at least one target (Store or StoreGroup).
     * <p>
     * Expects an error when both store and group lists are empty.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportOffer_NoTargetDefined() {
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER_BAD|PROMO|Spec||"; // Both empty

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Must define at least one store_code or store_group_code"));
    }

    /**
     * Tests the error handling when a referenced Store does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportOffer_StoreNotFound() {
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER_NO_STORE|PROMO|Spec|NON_EXISTENT_STORE|";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Store code 'NON_EXISTENT_STORE' not found"));
    }

    /**
     * Tests the error handling when a referenced StoreGroup does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportOffer_GroupNotFound() {
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER_NO_GROUP|PROMO|Spec||NON_EXISTENT_GROUP";

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("StoreGroup code 'NON_EXISTENT_GROUP' not found"));
    }

    /**
     * Tests the fallback mechanism by mixing valid and invalid lines.
     * <p>
     * Forces the parent class to process lines individually after a transaction rollback.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testFindEntityForLine_ThroughFallback() {
        // Setup Store
        withTransaction(() -> {
            Store s = new Store();
            s.code = "S001";
            s.name = "Store 1";
            // ADDED: Assign address to the store
            s.address = new Adresse();
            s.address.streetLine1 = "1 Test Street";
            s.address.city = "Paris";
            s.address.postalCode = "75000";
            s.address.country = "France";
            s.persist();
            return true;
        });

        // CSV with:
        // 1. Valid
        // 2. Valid
        // 3. Invalid (No target -> Rollback -> Fallback)
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER01|PROMO|{}|S001|\n" +
                "OFFER02|PROMO|{}|S001|\n" +
                "OFFER03|PROMO|{}||"; // Invalid

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":2"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""));

        assertNotNull(Offer.findByCode("OFFER01"));
        assertNotNull(Offer.findByCode("OFFER02"));
        assertNull(Offer.findByCode("OFFER03"));
    }

    /**
     * Tests the fallback mechanism specifically for StoreGroup retrieval.
     * <p>
     * Forces the code to enter the 1-by-1 fallback mode, where the bulk context map is missing.
     * This triggers the {@code retrieveStoreGroups} method, which performs a DB query
     * and populates the local map via the loop {@code for (StoreGroup g : groups) ...}.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRetrieveStoreGroups_Fallback_WithGroups() {
        // 1. Setup: Create a StoreGroup in the database
        withTransaction(() -> {
            StoreGroup g = new StoreGroup();
            g.code = "G_FALLBACK";
            g.name = "Test Group";
            g.persist();
            return true;
        });

        // 2. Act: Send CSV with a valid line (using the Group) and an invalid line.
        // The invalid line (no target) causes a transaction rollback.
        // The parent class then retries in 1-by-1 mode, triggering retrieveStoreGroups.
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER_FALLBACK|PROMO|{}||G_FALLBACK\n" + // Valid
                "OFFER_ERROR|PROMO|{}||"; // Invalid (No target, triggers rollback)

        authenticated()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1")) // Valid one created via fallback
                .body(containsString("\"errors\""));

        // 3. Assert: Verify that the group was successfully linked.
        // This proves that retrieveStoreGroups was called, the list was fetched,
        // iterated over by the loop, and the map was used to link the entities.
        Offer o = Offer.findByCode("OFFER_FALLBACK");
        assertNotNull(o);
        assertEquals(1, o.storeGroups.size());
        assertEquals("G_FALLBACK", o.storeGroups.iterator().next().code);
    }

    /**
     * Tests {@link OfferCsvResource#processChunkWithFallback} when the parsed lines list is empty.
     * <p>
     * Covers: {@code if (parsedLines.isEmpty()) return new HashMap<>(); }
     */
    @Test
    void testProcessChunkWithFallback_EmptyParsedLines() {
        List<ImporterCsvResource.LineData> parsedLines = Collections.emptyList();
        Set<String> targetCodes = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = offerCsvResource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Tests {@link OfferCsvResource#processChunkWithFallback} when the target codes set is empty.
     * <p>
     * Covers: {@code if (!targetCodes.isEmpty()) } being false in the fetching logic.
     */
    @Test
    void testProcessChunkWithFallback_EmptyTargetCodes() {
        // Create a dummy line so parsedLines is NOT empty
        ImporterCsvResource.LineData line = new ImporterCsvResource.LineData(
                1,
                "CODE",
                new String[]{"CODE", "PROMO", "Spec", "", ""}
        );
        List<ImporterCsvResource.LineData> parsedLines = List.of(line);

        // Explicitly pass an EMPTY set of codes
        Set<String> targetCodes = Collections.emptySet();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();

        Map<String, Object> result = offerCsvResource.processChunkWithFallback(parsedLines, targetCodes, counters, errors);

        assertNotNull(result);
        // Map should contain the auxiliary maps (Stores/Groups) fetched from the line, but no Offer map.
        // Check that we didn't crash.
    }

    /**
     * Tests that an anonymous caller is denied access to the import endpoint.
     * <p>
     * This one deliberately does not authenticate: it asserts the permission policy answers
     * an unauthenticated call with 401 before the resource is ever reached.
     */
    @Test
    void testSecurity_AccessDeniedForAnonymousCaller() {
        String csvContent = "code|type|specification|store_codes|group_codes\n" +
                "OFFER01|PROMO|Spec|S001|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/offers/import")
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