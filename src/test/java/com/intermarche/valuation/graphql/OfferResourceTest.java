package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OfferResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class OfferResourceTest {

    @Inject
    OfferResource resource;

    // --------------------------------------------------
    // Setup & Helpers
    // --------------------------------------------------

    /**
     * Sets up initial data for tests.
     * Creates a Store, StoreGroup, and an Offer linked to both.
     * <p>
     * The tables are emptied first. Several tests read {@code Offer.listAll().get(0)} and so
     * assume the fixture is the only offer present, but every {@code @QuarkusTest} class
     * shares one database and a class that ran before may have committed its own rows.
     * Prices are cleared ahead of stores, which they reference. The class runs under
     * {@code @TestTransaction}, so this deletion is rolled back with the rest of the test.
     */
    void setUp() {
        Price.deleteAll();
        Offer.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();

        Store store = createAndPersistStore("STORE_01", "Main Store");
        StoreGroup group = createAndPersistStoreGroup("GROUP_01", "Main Group");
        createAndPersistOffer("OFFER_01", "PROMO", "{}", Set.of(store), Set.of(group));
    }

    /**
     * Helper to create and persist a Store entity.
     *
     * @param code The unique code of the store.
     * @param name The name of the store.
     * @return The persisted Store entity.
     */
    private Store createAndPersistStore(String code, String name) {
        Store store = new Store();
        store.code = code;
        store.name = name;
        store.persist();
        return store;
    }

    /**
     * Helper to create and persist a StoreGroup entity.
     *
     * @param code The unique code of the group.
     * @param name The name of the group.
     * @return The persisted StoreGroup entity.
     */
    private StoreGroup createAndPersistStoreGroup(String code, String name) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = name;
        group.persist();
        return group;
    }

    /**
     * Helper to create and persist an Offer entity.
     * <b>Important:</b> Uses addAll() to preserve the mutable HashSet initialized in the entity constructor.
     *
     * @param code   The offer code.
     * @param type   The offer type.
     * @param spec   The specification JSON string.
     * @param stores The set of stores to link.
     * @param groups The set of store groups to link.
     * @return The persisted Offer entity.
     */
    private Offer createAndPersistOffer(String code, String type, String spec, Set<Store> stores, Set<StoreGroup> groups) {
        Offer offer = new Offer();
        offer.code = code;
        offer.type = type;
        offer.specification = spec;
        if (stores != null) {
            offer.stores.addAll(stores);
        }
        if (groups != null) {
            offer.storeGroups.addAll(groups);
        }
        offer.persist();
        return offer;
    }

    // --------------------------------------------------
    // Query Tests (MANAGER)
    // --------------------------------------------------

    /**
     * Tests retrieving all offers.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllOffers() {
        setUp();
        List<Offer> list = resource.allOffers();
        assertNotNull(list);
        assertFalse(list.isEmpty());
    }

    /**
     * Tests retrieving an offer by ID (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOfferById_Success() {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);
        Offer found = resource.offer(existing.id);
        assertNotNull(found);
        assertEquals("OFFER_01", found.code);
    }

    /**
     * Tests retrieving an offer by ID (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOfferById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> resource.offer(9999L));
    }

    /**
     * Tests retrieving an offer by Code (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOfferByCode_Success() {
        setUp();
        Offer found = resource.offerByCode("OFFER_01");
        assertNotNull(found);
        assertEquals("PROMO", found.type);
    }

    /**
     * Tests retrieving an offer by Code (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOfferByCode_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> resource.offerByCode("UNKNOWN"));
    }

    /**
     * Tests retrieving offers by stores and type (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoresAndType_Success() {
        setUp();
        List<Offer> list = resource.offersByStoresAndType(List.of("STORE_01"), "PROMO");
        assertFalse(list.isEmpty());
        assertEquals(1, list.size());
    }

    /**
     * Tests retrieving offers by stores when the input list is null.
     * <p>
     * Validates: {@code (storeCodes == null)} is TRUE.
     * Expectation: Returns an empty list.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoresAndType_NullList() {
        setUp();
        List<Offer> result = resource.offersByStoresAndType(null, "PROMO");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests retrieving offers by stores when the input list is empty.
     * <p>
     * Validates: {@code (storeCodes.isEmpty())} is TRUE.
     * Expectation: Returns an empty list.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoresAndType_EmptyList() {
        setUp();
        List<Offer> result = resource.offersByStoresAndType(Collections.emptyList(), "PROMO");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests retrieving offers by stores (Store Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoresAndType_StoreNotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                resource.offersByStoresAndType(List.of("STORE_99"), "PROMO"));
    }

    /**
     * Tests retrieving offers by store groups and type (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoreGroupsAndType_Success() {
        setUp();
        List<Offer> list = resource.offersByStoreGroupsAndType(List.of("GROUP_01"), "PROMO");
        assertFalse(list.isEmpty());
        assertEquals(1, list.size());
    }

    /**
     * Tests retrieving offers by store groups when the input list is null.
     * <p>
     * Validates: {@code (storeGroupCodes == null)} is TRUE.
     * Expectation: Returns an empty list.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoreGroupsAndType_NullList() {
        setUp();
        List<Offer> result = resource.offersByStoreGroupsAndType(null, "PROMO");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests retrieving offers by store groups when the input list is empty.
     * <p>
     * Validates: {@code (storeGroupCodes.isEmpty())} is TRUE.
     * Expectation: Returns an empty list.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoreGroupsAndType_EmptyList() {
        setUp();
        List<Offer> result = resource.offersByStoreGroupsAndType(Collections.emptyList(), "PROMO");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests retrieving offers by groups (Group Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testOffersByStoreGroupsAndType_GroupNotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                resource.offersByStoreGroupsAndType(List.of("GROUP_99"), "PROMO"));
    }

    // --------------------------------------------------
    // Mutation Tests: Create (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful creation of an offer with Store codes.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOffer_Success() throws GraphQLException {
        setUp();
        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.code = "OFFER_02";
        input.type = "DISCOUNT";
        input.specification = "{\"key\":\"value\"}";
        input.storeCodes = List.of("STORE_01");

        Offer created = resource.createOffer(input);

        assertNotNull(created.id);
        assertEquals("DISCOUNT", created.type);
        assertEquals(1, created.stores.size());
    }

    /**
     * Tests successful creation of an offer with valid StoreGroupCodes.
     * <p>
     * Validates the condition where all group codes provided are found in the database.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOffer_WithGroupCodes_Success() throws GraphQLException {
        setUp();
        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.code = "OFFER_GROUP_01";
        input.type = "PROMO";
        input.specification = "{}";
        input.storeGroupCodes = List.of("GROUP_01");

        Offer created = resource.createOffer(input);

        assertNotNull(created.id);
        assertEquals("OFFER_GROUP_01", created.code);
        assertEquals(1, created.storeGroups.size());
        assertTrue(created.storeGroups.stream().anyMatch(g -> g.code.equals("GROUP_01")));
    }

    /**
     * Tests creation failure due to duplicate code.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOffer_DuplicateCode() {
        setUp();
        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.code = "OFFER_01";
        input.storeCodes = List.of("STORE_01");

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createOffer(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests creation failure when targets are missing.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOffer_MissingTargets() {
        setUp();
        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.code = "OFFER_02";
        input.type = "DISCOUNT";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createOffer(input));
        assertTrue(ex.getMessage().contains("An error occurred during createOffer"));
    }

    /**
     * Tests creation failure due to non-existent Store.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOffer_StoreNotFound() {
        setUp();
        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.code = "OFFER_02";
        input.storeCodes = List.of("STORE_99");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.createOffer(input));
        assertTrue(ex.getMessage().contains("Store codes were not found"));
    }

    /**
     * Tests creation failure due to non-existent StoreGroup.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateOffer_GroupNotFound() {
        setUp();
        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.code = "OFFER_02";
        input.storeGroupCodes = List.of("GROUP_99");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.createOffer(input));
        assertTrue(ex.getMessage().contains("StoreGroup codes were not found"));
    }

    // --------------------------------------------------
    // Mutation Tests: Update (ADMIN)
    // --------------------------------------------------

    /**
     * Tests update failure when offer is not found.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                resource.updateOffer(9999L, new OfferResource.OfferRecord()));
    }

    /**
     * Tests update success changing type and specification.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_ChangeFields() throws GraphQLException {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.type = "NEW_TYPE";
        input.specification = "New Spec";

        Offer updated = resource.updateOffer(existing.id, input);
        assertEquals("NEW_TYPE", updated.type);
        assertEquals("New Spec", updated.specification);
    }

    /**
     * Tests update success changing Stores.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_ChangeStores() throws GraphQLException {
        setUp();
        Store newStore = createAndPersistStore("STORE_02", "Second Store");
        Offer existing = (Offer) Offer.listAll().get(0);

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeCodes = List.of("STORE_02");

        Offer updated = resource.updateOffer(existing.id, input);
        assertEquals(1, updated.stores.size());
        assertTrue(updated.stores.stream().anyMatch(s -> s.code.equals("STORE_02")));
    }

    /**
     * Tests update success clearing Stores (empty list).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_ClearStores() throws GraphQLException {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);
        assertFalse(existing.stores.isEmpty());

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeCodes = Collections.emptyList();

        Offer updated = resource.updateOffer(existing.id, input);
        assertTrue(updated.stores.isEmpty());
    }

    /**
     * Tests update keeping Stores (null list).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_KeepStores() throws GraphQLException {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);
        int initialSize = existing.stores.size();

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeCodes = null;

        Offer updated = resource.updateOffer(existing.id, input);
        assertEquals(initialSize, updated.stores.size());
    }

    /**
     * Tests update failure when changing to a non-existent Store.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_StoreNotFound() {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeCodes = List.of("STORE_99");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.updateOffer(existing.id, input));
        assertTrue(ex.getMessage().contains("Store codes were not found"));
    }

    /**
     * Tests update success changing StoreGroups.
     * <p>
     * Validates that the old group is removed and the new one is added.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_ChangeStoreGroups_Success() throws GraphQLException {
        setUp();
        StoreGroup newGroup = createAndPersistStoreGroup("GROUP_02", "Second Group");
        Offer existing = (Offer) Offer.listAll().get(0);

        assertEquals(1, existing.storeGroups.size());

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeGroupCodes = List.of("GROUP_02");

        Offer updated = resource.updateOffer(existing.id, input);

        assertEquals(1, updated.storeGroups.size());
        assertTrue(updated.storeGroups.stream().anyMatch(g -> g.code.equals("GROUP_02")));
        assertFalse(updated.storeGroups.stream().anyMatch(g -> g.code.equals("GROUP_01")));
    }

    /**
     * Tests update success clearing StoreGroups (empty list).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_ClearStoreGroups() throws GraphQLException {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);
        assertFalse(existing.storeGroups.isEmpty());

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeGroupCodes = Collections.emptyList();

        Offer updated = resource.updateOffer(existing.id, input);
        assertTrue(updated.storeGroups.isEmpty());
    }

    /**
     * Tests update keeping StoreGroups (null list).
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_KeepStoreGroups() throws GraphQLException {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);
        int initialSize = existing.storeGroups.size();

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeGroupCodes = null;

        Offer updated = resource.updateOffer(existing.id, input);
        assertEquals(initialSize, updated.storeGroups.size());
    }

    /**
     * Tests update failure when changing to a non-existent StoreGroup.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateOffer_StoreGroupNotFound() {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);

        OfferResource.OfferRecord input = new OfferResource.OfferRecord();
        input.storeGroupCodes = List.of("GROUP_99");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> resource.updateOffer(existing.id, input));
        assertTrue(ex.getMessage().contains("StoreGroup codes were not found"));
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful deletion.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteOffer_Success() throws GraphQLException {
        setUp();
        Offer existing = (Offer) Offer.listAll().get(0);
        assertTrue(resource.deleteOffer(existing.id));
        assertNull(Offer.findById(existing.id));
    }

    /**
     * Tests deletion of non-existent offer.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteOffer_NotFound() throws GraphQLException {
        setUp();
        assertFalse(resource.deleteOffer(9999L));
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    /**
     * Tests the toString method of the Record with a single store code.
     */
    @Test
    void testRecord() {
        OfferResource.OfferRecord record = new OfferResource.OfferRecord();
        record.code = "CODE";
        record.type = "TYPE";
        record.specification = "SPEC";
        record.storeCodes = List.of("S1");
        record.storeGroupCodes = null;

        String expected = "OfferRecord [code=CODE, type=TYPE, specification=SPEC, storeCodes=[S1], storeGroupCodes=null]";
        assertEquals(expected, record.toString());
    }

    /**
     * Tests the toString method of the Record with populated lists.
     */
    @Test
    void testRecord_WithPopulatedLists() {
        OfferResource.OfferRecord record = new OfferResource.OfferRecord();
        record.code = "CODE";
        record.type = "TYPE";
        record.specification = "SPEC";
        record.storeCodes = List.of("STORE_01", "STORE_02");
        record.storeGroupCodes = List.of("GROUP_01");

        String expected = "OfferRecord [code=CODE, type=TYPE, specification=SPEC, storeCodes=[STORE_01, STORE_02], storeGroupCodes=[GROUP_01]]";
        assertEquals(expected, record.toString());
    }

    /**
     * Tests the toString method of the Record when lists are null.
     */
    @Test
    void testRecord_NullLists() {
        OfferResource.OfferRecord record = new OfferResource.OfferRecord();
        record.code = "CODE";
        record.type = "TYPE";
        record.specification = "SPEC";
        record.storeCodes = null;
        record.storeGroupCodes = null;

        String expected = "OfferRecord [code=CODE, type=TYPE, specification=SPEC, storeCodes=null, storeGroupCodes=null]";
        assertEquals(expected, record.toString());
    }
}