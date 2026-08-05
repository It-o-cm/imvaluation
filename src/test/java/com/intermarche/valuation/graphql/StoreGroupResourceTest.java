package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StoreGroupResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class StoreGroupResourceTest {

    @Inject
    StoreGroupResource storeGroupResource;

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

        DomainUtils.createAndPersistStoreGroup("GROUP_01");
    }

    // --------------------------------------------------
    // Query Tests (MANAGER)
    // --------------------------------------------------

    /**
     * Tests retrieving all store groups.
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllStoreGroups() {
        setUp();
        assertFalse(storeGroupResource.allStoreGroups().isEmpty());
    }

    /**
     * Tests retrieving a store group by ID (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreGroupById_Success() {
        setUp();
        StoreGroup existing = StoreGroup.findByCode("GROUP_01");
        assertNotNull(existing);
        assertEquals("GROUP_01", storeGroupResource.storeGroup(existing.id).code);
    }

    /**
     * Tests retrieving a store group by ID (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreGroupById_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> storeGroupResource.storeGroup(9999L));
    }

    /**
     * Tests retrieving a store group by Code (Success).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreGroupByCode_Success() {
        setUp();
        assertEquals("GROUP_01", storeGroupResource.storeGroupByCode("GROUP_01").code);
    }

    /**
     * Tests retrieving a store group by Code (Not Found).
     */
    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreGroupByCode_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () -> storeGroupResource.storeGroupByCode("GROUP_99"));
    }

    // --------------------------------------------------
    // Mutation Tests: Create (ADMIN)
    // --------------------------------------------------

    /**
     * Tests creation failure due to duplicate code.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStoreGroup_DuplicateCode() {
        setUp();
        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.code = "GROUP_01";
        input.name = "Unique Name";
        GraphQLException ex = assertThrows(GraphQLException.class, () -> storeGroupResource.createStoreGroup(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests creation failure due to duplicate name.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStoreGroup_DuplicateName() {
        setUp();
        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.code = "GROUP_02";
        input.name = "GROUP_01"; // Existing Name
        GraphQLException ex = assertThrows(GraphQLException.class, () -> storeGroupResource.createStoreGroup(input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests successful creation with empty lists provided.
     * <p>
     * Validates:
     * - {@code (input.storeCodes != null)} is TRUE.
     * - {@code (!input.storeCodes.isEmpty())} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStoreGroup_EmptyLists() throws GraphQLException {
        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.code = "GROUP_EMPTY";
        input.name = "Empty Group";
        input.storeCodes = List.of();
        input.storeGroupCodes = List.of();

        StoreGroup created = storeGroupResource.createStoreGroup(input);
        assertNotNull(created);
        assertTrue(created.stores.isEmpty());
        assertTrue(created.storeGroups.isEmpty());
    }

    /**
     * Tests creation with relationships (Stores and Sub-Groups).
     * <p>
     * Validates:
     * - {@code (!input.storeCodes.isEmpty())} is TRUE.
     * - {@code (!input.storeGroupCodes.isEmpty())} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStoreGroup_WithRelations() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_R", 0.0, 0.0);
        StoreGroup subGroup = DomainUtils.createAndPersistStoreGroup("SUB_GROUP");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.code = "GROUP_PARENT";
        input.name = "Parent";
        input.storeCodes = List.of("STORE_R");
        input.storeGroupCodes = List.of("SUB_GROUP");

        StoreGroup created = storeGroupResource.createStoreGroup(input);
        assertEquals(1, created.stores.size());
        assertEquals(1, created.storeGroups.size());
    }

    /**
     * Tests creation failure when a referenced Store is missing.
     * <p>
     * Validates:
     * - {@code if (store == null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStoreGroup_MissingStore() {
        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.code = "GROUP_MISS";
        input.name = "Missing";
        input.storeCodes = List.of("STORE_GHOST");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> storeGroupResource.createStoreGroup(input));
        assertTrue(ex.getMessage().contains("Store with code 'STORE_GHOST' not found"));
    }

    // --------------------------------------------------
    // Mutation Tests: Update (ADMIN)
    // --------------------------------------------------

    /**
     * Tests update failure when the group is not found.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_NotFound() {
        setUp();
        assertThrows(NoSuchElementException.class, () ->
                storeGroupResource.updateStoreGroup(9999L, new StoreGroupResource.StoreGroupRecord()));
    }

    /**
     * Tests update failure due to conflicting name.
     * <p>
     * Validates:
     * - {@code if (nameCount > 0)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_ConflictingName() {
        setUp();
        DomainUtils.createAndPersistStoreGroup("GROUP_CONFLICT");
        StoreGroup target = StoreGroup.findByCode("GROUP_01");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.name = "GROUP_CONFLICT";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> storeGroupResource.updateStoreGroup(target.id, input));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    /**
     * Tests update success when name is provided but identical to current.
     * <p>
     * Validates:
     * - {@code !input.name.equals(currentName)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_SameName_Success() throws GraphQLException {
        setUp();
        StoreGroup existing = StoreGroup.findByCode("GROUP_01");
        String originalName = existing.name;

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.name = originalName;

        StoreGroup updated = storeGroupResource.updateStoreGroup(existing.id, input);
        assertEquals(originalName, updated.name);
    }

    /**
     * Tests update success when changing name to a unique value.
     * <p>
     * Validates:
     * - {@code if (nameCount > 0)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_ChangeName_Success() throws GraphQLException {
        setUp();
        StoreGroup existing = StoreGroup.findByCode("GROUP_01");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.name = "New Unique Name";

        StoreGroup updated = storeGroupResource.updateStoreGroup(existing.id, input);
        assertEquals("New Unique Name", updated.name);
    }

    /**
     * Tests update where input name is null.
     * <p>
     * Validates:
     * - {@code (input.name != null)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_NullName() throws GraphQLException {
        setUp();
        StoreGroup existing = StoreGroup.findByCode("GROUP_01");
        String originalName = existing.name;

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();

        StoreGroup updated = storeGroupResource.updateStoreGroup(existing.id, input);
        assertEquals(originalName, updated.name);
    }

    /**
     * Tests successful update of relationships.
     * <p>
     * Validates:
     * - {@code if (store == null)} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_ReplaceRelations_Success() throws GraphQLException {
        setUp();
        Store s1 = DomainUtils.createAndPersistStore("S1", 0.0, 0.0);
        Store s2 = DomainUtils.createAndPersistStore("S2", 0.0, 0.0);
        StoreGroup target = StoreGroup.findByCode("GROUP_01");
        target.stores.add(s1);

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.storeCodes = List.of("S2");

        StoreGroup updated = storeGroupResource.updateStoreGroup(target.id, input);
        assertEquals(1, updated.stores.size());
        assertEquals("S2", updated.stores.iterator().next().code);
    }

    /**
     * Tests update failure when referencing a missing Store.
     * <p>
     * Validates:
     * - {@code if (store == null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_MissingStore() {
        setUp();
        StoreGroup target = StoreGroup.findByCode("GROUP_01");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.storeCodes = List.of("STORE_GHOST");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () ->
                storeGroupResource.updateStoreGroup(target.id, input));
        assertTrue(ex.getMessage().contains("Store with code 'STORE_GHOST' not found"));
    }

    /**
     * Tests successful update of sub-group relationships.
     * <p>
     * Validates:
     * - {@code if (child == null)} is FALSE.
     * - {@code if (child.code.equals(group.code))} is FALSE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_ValidChildGroup() throws GraphQLException {
        setUp();
        StoreGroup parent = StoreGroup.findByCode("GROUP_01");
        StoreGroup child = DomainUtils.createAndPersistStoreGroup("GROUP_CHILD");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.storeGroupCodes = List.of("GROUP_CHILD");

        StoreGroup updated = storeGroupResource.updateStoreGroup(parent.id, input);
        assertEquals(1, updated.storeGroups.size());
    }

    /**
     * Tests update failure when referencing a missing Sub-Group.
     * <p>
     * Validates:
     * - {@code if (child == null)} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_MissingChildGroup() {
        setUp();
        StoreGroup target = StoreGroup.findByCode("GROUP_01");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.storeGroupCodes = List.of("GROUP_GHOST");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () ->
                storeGroupResource.updateStoreGroup(target.id, input));
        assertTrue(ex.getMessage().contains("StoreGroup with code 'GROUP_GHOST' not found"));
    }

    /**
     * Tests update failure when referencing self as sub-group.
     * <p>
     * Validates:
     * - {@code if (child.code.equals(group.code))} is TRUE.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateStoreGroup_SelfReference() {
        setUp();
        StoreGroup target = StoreGroup.findByCode("GROUP_01");

        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.storeGroupCodes = List.of("GROUP_01");

        GraphQLException ex = assertThrows(GraphQLException.class, () ->
                storeGroupResource.updateStoreGroup(target.id, input));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (ADMIN)
    // --------------------------------------------------

    /**
     * Tests successful deletion of a store group.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteStoreGroup_Success() throws GraphQLException {
        setUp();
        StoreGroup existing = StoreGroup.findByCode("GROUP_01");
        assertTrue(storeGroupResource.deleteStoreGroup(existing.id));
        assertNull(Store.findById(existing.id));
    }

    /**
     * Tests deletion of a non-existent store group.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteStoreGroup_NotFound() throws GraphQLException {
        setUp();
        assertFalse(storeGroupResource.deleteStoreGroup(9999L));
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    /**
     * Tests the toString method of StoreGroupRecord with populated lists.
     */
    @Test
    void testStoreGroupRecord() {
        var record = new StoreGroupResource.StoreGroupRecord();
        record.code = "REC_CODE";
        record.name = "Record Name";
        record.storeCodes = Arrays.asList("S1", "S2");
        record.storeGroupCodes = Arrays.asList("G1", "G2", "G3");

        String expected = "StoreGroupRecord [code=REC_CODE, name=Record Name, " +
                "storeCodes=[S1, S2], storeGroupCodes=[G1, G2, G3]]";
        assertEquals(expected, record.toString());
    }

    /**
     * Tests the toString method of StoreGroupRecord with null lists.
     */
    @Test
    void testStoreGroupRecord_NullLists() {
        var record = new StoreGroupResource.StoreGroupRecord();
        record.code = "REC_NULL";
        record.name = "Null Lists";

        String expected = "StoreGroupRecord [code=REC_NULL, name=Null Lists, storeCodes=null, storeGroupCodes=null]";
        assertEquals(expected, record.toString());
    }

    /**
     * Tests creation when input lists are strictly null.
     * <p>
     * Validates:
     * - {@code (input.storeCodes != null)} is FALSE.
     * - {@code (input.storeGroupCodes != null)} is FALSE.
     * Expectation: Linking loops are skipped, creation succeeds.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateStoreGroup_NullLists() throws GraphQLException {
        StoreGroupResource.StoreGroupRecord input = new StoreGroupResource.StoreGroupRecord();
        input.code = "GROUP_NULL";
        input.name = "Null Lists";
        // input.storeCodes is null by default
        // input.storeGroupCodes is null by default

        StoreGroup created = storeGroupResource.createStoreGroup(input);

        assertNotNull(created);
        assertTrue(created.stores.isEmpty());
        assertTrue(created.storeGroups.isEmpty());
    }

}