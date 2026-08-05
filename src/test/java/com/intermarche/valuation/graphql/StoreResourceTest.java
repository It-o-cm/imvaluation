package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StoreResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class StoreResourceTest {

    @Inject
    StoreResource storeResource;

    /**
     * Sets up initial data for tests.
     */
    void setUp() {
        // Every @QuarkusTest class shares one database, and a class that ran before may have
        // committed rows of its own. Several tests below read "the first" entity or rely on
        // a code being unused, so the fixture starts from an empty set. Deletion follows the
        // reverse of the dependencies: prices reference stores and products, offers
        // reference stores and groups. The class runs under @TestTransaction, so this is
        // rolled back with the rest of the test.
        Price.deleteAll();
        ProductCategoryStorage.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();

        // Create a default store for query tests
        DomainUtils.createAndPersistStore("STORE_01", 48.0, 2.0);
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllStores() {
        setUp();
        List<Store> stores = storeResource.allStores();
        assertNotNull(stores);
        assertFalse(stores.isEmpty(), "Should return at least one store");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreById_Success() {
        setUp();
        // Find the existing store from setup
        Store existing = Store.findByCode("STORE_01");
        assertNotNull(existing);

        Store found = storeResource.store(existing.id);
        assertNotNull(found);
        assertEquals("STORE_01", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> {
            storeResource.store(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreByCode_Success() {
        setUp();
        Store found = storeResource.storeByCode("STORE_01");
        assertNotNull(found);
        assertEquals("STORE_01", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreByCode_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> {
            storeResource.storeByCode("STORE_99");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStore_DuplicateCode() {
        setUp();
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_01"; // Existing Code from setup
        input.name = "Some Unique Name";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storeResource.createStore(input);
        });

        assertTrue(ex.getMessage().contains("already exists"), "Exception message should indicate duplicate");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStore_DuplicateName() {
        setUp();
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_02"; // New Code
        input.name = "Store STORE_01"; // Existing Name (Store uses code for name in DomainUtils, assuming name uniqueness check)

        // Note: DomainUtils.createAndPersistStore uses code as name.
        // The resource checks name uniqueness.
        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storeResource.createStore(input);
        });

        assertTrue(ex.getMessage().contains("already exists"), "Exception message should indicate duplicate");
    }

    /**
     * Tests successful creation of a store with all fields provided.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStore_AllFields_Success() throws GraphQLException {
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_02";
        input.name = "New Store Name";
        input.streetLine1 = "1 Main St";
        input.streetLine2 = "Apt 1";
        input.postalCode = "75001";
        input.city = "Paris";
        input.country = "France";
        input.latitude = 48.8566;
        input.longitude = 2.3522;

        Store created = storeResource.createStore(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("STORE_02", created.code);
        assertEquals("New Store Name", created.name);

        // Verify Address fields
        assertNotNull(created.address);
        assertEquals("1 Main St", created.address.streetLine1);
        assertEquals("Apt 1", created.address.streetLine2);
        assertEquals("75001", created.address.postalCode);
        assertEquals("Paris", created.address.city);
        assertEquals("France", created.address.country);
        assertEquals(48.8566, created.address.latitude);
        assertEquals(2.3522, created.address.longitude);
    }

    /**
     * Tests successful creation with minimal fields (null checks).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStore_NullFields_Success() throws GraphQLException {
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_03";
        input.name = "Minimal Store";
        // Address fields are null

        Store created = storeResource.createStore(input);

        assertNotNull(created);
        assertEquals("STORE_03", created.code);
        assertEquals("Minimal Store", created.name);

        // Validate null branches for address
        assertNotNull(created.address);
        assertNull(created.address.streetLine1);
        assertNull(created.address.latitude);
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_ChangeCode() throws GraphQLException {
        setUp();
        Store existing = Store.findByCode("STORE_01");
        assertNotNull(existing);

        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_99"; // New valid Code

        Store updated = storeResource.updateStore(existing.id, input);
        assertEquals("STORE_99", updated.code);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_ConflictingCode() {
        setUp();
        // Create another store to conflict with
        DomainUtils.createAndPersistStore("STORE_05", 0.0, 0.0);

        Store target = Store.findByCode("STORE_01");

        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_05"; // Try to assign existing Code of the other store

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storeResource.updateStore(target.id, input);
        });

        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_ConflictingName() {
        setUp();
        // Create another store to conflict with
        DomainUtils.createAndPersistStore("STORE_06", 0.0, 0.0);

        Store target = Store.findByCode("STORE_01");

        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.name = "Store STORE_06"; // Try to assign existing name

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storeResource.updateStore(target.id, input);
        });

        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_NotFound() {
        setUp();
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.name = "Ghost";

        assertThrows(NoSuchElementException.class, () -> {
            storeResource.updateStore(9999L, input);
        });
    }

    /**
     * Tests updating all fields of a store successfully.
     * <p>
     * Setup ensures address is NOT null.
     * Validates:
     * - {@code (input.field != null)} is TRUE for all fields.
     * - {@code if (store.address == null)} is FALSE (Address exists, we modify it).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_AllFields_Success() throws GraphQLException {
        // 1. Setup: Create store with an existing address (condition FALSE)
        Store existing = DomainUtils.createAndPersistStore("STORE_10", 0.0, 0.0);
        existing.name = "Old Name";
        existing.address = new Adresse(); // Initialize Address
        existing.address.streetLine1 = "Old St";

        // 2. Input: Change ALL fields
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_11";
        input.name = "New Name";
        input.streetLine1 = "New St";
        input.streetLine2 = "New St2";
        input.postalCode = "12345";
        input.city = "New City";
        input.country = "New Country";
        input.latitude = 10.0;
        input.longitude = 20.0;

        // 3. Execute
        Store updated = storeResource.updateStore(existing.id, input);

        // 4. Assert all fields were updated
        assertEquals("STORE_11", updated.code);
        assertEquals("New Name", updated.name);
        assertEquals("New St", updated.address.streetLine1);
        assertEquals("New St2", updated.address.streetLine2);
        assertEquals("12345", updated.address.postalCode);
        assertEquals("New City", updated.address.city);
        assertEquals("New Country", updated.address.country);
        assertEquals(10.0, updated.address.latitude);
        assertEquals(20.0, updated.address.longitude);
    }

    /**
     * Tests updating a store where input fields are either same as existing or null.
     * <p>
     * Setup ensures address IS null.
     * Validates:
     * - {@code !input.code.equals(store.code)} is FALSE (Code same).
     * - {@code !input.name.equals(store.name)} is FALSE (Name same).
     * - {@code (input.field != null)} is FALSE for other fields.
     * - {@code if (store.address == null)} is TRUE (Address is null, we instantiate it).
     * Expectation: No exception thrown, address initialized but empty.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_PartialAndNoChange_Success() throws GraphQLException {
        // 1. Setup: Create store with NULL address (condition TRUE)
        Store existing = DomainUtils.createAndPersistStore("STORE_20", 5.0, 5.0);
        existing.name = "Stable Name";
        existing.address = null; // Force Address to NULL

        // 2. Input: Same Code/Name, Null for others
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = "STORE_20";
        input.name = "Stable Name";

        // 3. Execute
        Store updated = storeResource.updateStore(existing.id, input);

        // 4. Assert values remained UNCHANGED
        assertEquals("STORE_20", updated.code);
        assertEquals("Stable Name", updated.name);

        // Verify the null check branch created the object
        assertNotNull(updated.address, "Address should be instantiated by the null-check guard");
        assertNull(updated.address.streetLine1, "Fields should remain null as input was null");
    }

    /**
     * Tests updating a store where input Code and Name are null.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStore_NullCodeAndName() throws GraphQLException {
        // 1. Setup
        Store existing = DomainUtils.createAndPersistStore("STORE_30", 0.0, 0.0);
        existing.address = new Adresse();

        // 2. Input: Code and Name are NULL
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.city = "Updated City"; // Change something else

        // 3. Execute
        Store updated = storeResource.updateStore(existing.id, input);

        // 4. Assert
        assertEquals("STORE_30", updated.code, "Code should remain unchanged");
        assertEquals("Store STORE_30", updated.name, "Name should remain unchanged (was Store STORE_30 in setup)"); // Name was null in DomainUtils
        assertEquals("Updated City", updated.address.city, "City should be updated");
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteStore_Success() throws GraphQLException {
        setUp();
        Store existing = Store.findByCode("STORE_01");
        assertNotNull(existing);

        boolean result = storeResource.deleteStore(existing.id);
        assertTrue(result, "Delete should return true");

        // Verify it's gone
        assertNull(Store.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteStore_NotFound() throws GraphQLException {
        setUp();
        boolean result = storeResource.deleteStore(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    @Test
    void testStoreRecord() {
        var record = new StoreResource.StoreRecord();
        record.code = "REC_01";
        record.name = "Record Store";
        record.streetLine1 = "1 Rec St";
        record.latitude = 1.0;
        record.longitude = 2.0;

        assertEquals("StoreRecord [code=REC_01, name=Record Store, streetLine1=1 Rec St, streetLine2=null, postalCode=null, city=null, country=null, latitude=1.0, longitude=2.0]",
                record.toString());
    }

}