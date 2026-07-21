package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Store Groups.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Manages logical groupings of Stores and hierarchical StoreGroup relationships.
 * <p>
 * The relationship is unidirectional: A StoreGroup contains a list of Stores
 * and a list of other StoreGroups (Sub-groups).
 * <p>
 * Input for children uses Business Codes (Strings) instead of Database IDs (Longs).
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class StoreGroupResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(StoreGroupResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    @Query
    @Description("Get the list of all store groups")
    @RolesAllowed("MANAGER")
    public List<StoreGroup> allStoreGroups() {
        LOGGER.info("Entering method allStoreGroups");
        List<StoreGroup> result = StoreGroup.listAll();
        LOGGER.info("Exiting method allStoreGroups");
        return result;
    }

    @Query
    @Description("Get a store group by its ID")
    @RolesAllowed("MANAGER")
    public StoreGroup storeGroup(@Name("id") Long id) {
        LOGGER.info("Entering method storeGroup with id: " + id);
        StoreGroup group = StoreGroup.findById(id);
        if (group == null) {
            LOGGER.error("StoreGroup with id " + id + " not found");
            throw new NoSuchElementException("StoreGroup with id " + id + " not found");
        }
        LOGGER.info("Exiting method storeGroup");
        return group;
    }

    @Query
    @Description("Get a store group by its code")
    @RolesAllowed("MANAGER")
    public StoreGroup storeGroupByCode(@Name("code") String code) {
        LOGGER.info("Entering method storeGroupByCode with code: " + code);
        StoreGroup group = StoreGroup.findByCode(code);
        if (group == null) {
            LOGGER.error("StoreGroup with code " + code + " not found");
            throw new NoSuchElementException("StoreGroup with code " + code + " not found");
        }
        LOGGER.info("Exiting method storeGroupByCode");
        return group;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new store group in the database.
     * Validates that the code and name are unique before creation.
     * Fetches children by their Business Codes (Strings).
     *
     * @param input The input record containing group details and child codes.
     * @return The newly created StoreGroup entity.
     * @throws GraphQLException if validation fails, a child is not found, or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new store group")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public StoreGroup createStoreGroup(@Name("input") StoreGroupRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createStoreGroup for code: " + input.code);

            // Check Uniqueness Constraint: code
            long codeCount = StoreGroup.count("code", input.code);
            if (codeCount > 0) {
                LOGGER.warn("Attempt to create group with existing code: " + input.code);
                throw new AlreadyExistsException("StoreGroup with code '" + input.code + "' already exists.");
            }

            // Check Uniqueness Constraint: name
            long nameCount = StoreGroup.count("name", input.name);
            if (nameCount > 0) {
                LOGGER.warn("Attempt to create group with existing name: " + input.name);
                throw new AlreadyExistsException("StoreGroup with name '" + input.name + "' already exists.");
            }

            // Creation Logic
            StoreGroup group = new StoreGroup();
            group.code = input.code;
            group.name = input.name;

            // Link Stores by Code
            if (input.storeCodes != null && !input.storeCodes.isEmpty()) {
                for (String code : input.storeCodes) {
                    Store store = Store.findByCode(code);
                    if (store == null) {
                        throw new NoSuchElementException("Store with code '" + code + "' not found.");
                    }
                    group.stores.add(store);
                }
            }

            // Link Sub-Groups by Code
            if (input.storeGroupCodes != null && !input.storeGroupCodes.isEmpty()) {
                for (String code : input.storeGroupCodes) {
                    StoreGroup child = StoreGroup.findByCode(code);
                    group.storeGroups.add(child);
                }
            }

            group.persist();

            LOGGER.info("Exiting method createStoreGroup. Created ID: " + group.id);
            return group;
        }, StoreGroupResource.class, "createStoreGroup");
    }

    /**
     * Updates an existing store group identified by its ID.
     * Validates new name uniqueness if it is changed.
     * Allows updating the lists of associated stores and sub-groups using Business Codes.
     *
     * @param id    The ID of the group to update.
     * @param input The input record containing fields to update.
     * @return The updated StoreGroup entity.
     * @throws GraphQLException if validation fails, a child is not found, or the group is not found.
     */
    @Mutation
    @Description("Update an existing store group")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public StoreGroup updateStoreGroup(@Name("id") Long id, @Name("input") StoreGroupRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateStoreGroup for id: " + id);

            StoreGroup group = StoreGroup.findById(id);
            if (group == null) {
                LOGGER.error("StoreGroup with id " + id + " not found");
                throw new NoSuchElementException("StoreGroup with id " + id + " not found");
            }

            String currentName = group.name;

            // Name uniqueness check
            if (input.name != null && !input.name.equals(currentName)) {
                long nameCount = StoreGroup.count("name = ?1 and id <> ?2", input.name, id);
                if (nameCount > 0) {
                    LOGGER.warn("Attempt to update group to conflicting name: " + input.name);
                    throw new AlreadyExistsException("Another group with name '" + input.name + "' already exists.");
                }
            }

            // Apply Name Update
            if (input.name != null) group.name = input.name;

            // Update Store Relationships (Replacement Strategy by Code)
            if (input.storeCodes != null) {
                group.stores.clear();
                for (String code : input.storeCodes) {
                    Store store = Store.findByCode(code);
                    if (store == null) {
                        throw new NoSuchElementException("Store with code '" + code + "' not found.");
                    }
                    group.stores.add(store);
                }
            }
            // Update StoreGroup Relationships (Replacement Strategy by Code)
            if (input.storeGroupCodes != null) {
                group.storeGroups.clear();
                for (String code : input.storeGroupCodes) {
                    StoreGroup child = StoreGroup.findByCode(code);
                    if (child == null) {
                        throw new NoSuchElementException("StoreGroup with code '" + code + "' not found.");
                    }
                    if (child.code.equals(group.code)) {
                        throw new IllegalArgumentException("A StoreGroup cannot contain itself.");
                    }
                    group.storeGroups.add(child);
                }
            }
            LOGGER.info("Exiting method updateStoreGroup");
            return group;
        }, StoreGroupResource.class, "updateStoreGroup");
    }

    @Mutation
    @Description("Delete a store group by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteStoreGroup(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteStoreGroup for id: " + id);
            boolean result = StoreGroup.deleteById(id);
            LOGGER.info("Exiting method deleteStoreGroup. Result: " + result);
            return result;
        }, StoreGroupResource.class, "deleteStoreGroup");
    }

    /**
     * Input record type for GraphQL StoreGroup mutations.
     * <p>
     * Contains fields corresponding to the {@link StoreGroup} entity.
     * Relationships to Stores and other StoreGroups are provided as lists of String Codes.
     */
    @Input
    static public class StoreGroupRecord {

        /**
         * The unique code of the store group.
         */
        public String code;

        /**
         * The display name of the store group.
         */
        public String name;

        /**
         * List of Store codes (business keys) to be linked as children.
         */
        public List<String> storeCodes;

        /**
         * List of other StoreGroup codes to be linked as sub-groups.
         */
        public List<String> storeGroupCodes;

        /**
         * Default constructor.
         */
        public StoreGroupRecord() {
        }

        /**
         * Returns a string representation of the record.
         * Uses String.join to display list contents explicitly.
         */
        @Override
        public String toString() {
            String stores = storeCodes != null ? "[" + String.join(", ", storeCodes) + "]" : "null";
            String groups = storeGroupCodes != null ? "[" + String.join(", ", storeGroupCodes) + "]" : "null";
            return "StoreGroupRecord [code=" + code + ", name=" + name +
                    ", storeCodes=" + stores + ", storeGroupCodes=" + groups + "]";
        }
    }
}