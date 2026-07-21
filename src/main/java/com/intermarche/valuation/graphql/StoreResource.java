package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Store;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Stores.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Includes pre-validation checks for Code and Name uniqueness.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class StoreResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(StoreResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves the list of all stores in the database.
     *
     * @return A list of all Store entities.
     */
    @Query
    @Description("Get the list of all stores")
    @RolesAllowed("MANAGER")
    public List<Store> allStores() {
        LOGGER.info("Entering method allStores");
        List<Store> result = Store.listAll();
        LOGGER.info("Exiting method allStores");
        return result;
    }

    /**
     * Retrieves a specific store by its unique identifier.
     *
     * @param id The unique ID of the store.
     * @return The Store entity.
     * @throws NoSuchElementException if the store with the given ID does not exist.
     */
    @Query
    @Description("Get a store by its ID")
    @RolesAllowed("MANAGER")
    public Store store(@Name("id") Long id) {
        LOGGER.info("Entering method store with id: " + id);
        Store store = Store.findById(id);
        if (store == null) {
            LOGGER.error("Store with id " + id + " not found");
            throw new NoSuchElementException("Store with id " + id + " not found");
        }
        LOGGER.info("Exiting method store");
        return store;
    }

    /**
     * Retrieves a specific store by its unique code.
     *
     * @param code The unique code of the store.
     * @return The Store entity.
     * @throws NoSuchElementException if the store with the given code does not exist.
     */
    @Query
    @Description("Get a store by its code")
    @RolesAllowed("MANAGER")
    public Store storeByCode(@Name("code") String code) {
        LOGGER.info("Entering method storeByCode with code: " + code);
        Store store = Store.findByCode(code);
        if (store == null) {
            LOGGER.error("Store with code " + code + " not found");
            throw new NoSuchElementException("Store with code " + code + " not found");
        }
        LOGGER.info("Exiting method storeByCode");
        return store;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // All methods delegate to the default method 'execute' from GraphQLTrait.
    // --------------------------------------------------

    /**
     * Creates a new store in the database.
     * Validates that the code and name are unique before creation.
     *
     * @param input The input record containing store details.
     * @return The newly created Store entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new store")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Store createStore(@Name("input") StoreRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createStore for code: " + input.code);
            // Check if Code is already used
            Store existingStoreByCode = Store.findByCode(input.code);
            if (existingStoreByCode != null) {
                LOGGER.warn("Attempt to create store with existing code: " + input.code);
                throw new AlreadyExistsException("Store with code '" + input.code + "' already exists.");
            }
            // Check if Name is already used (Assuming name should be unique)
            long nameCount = Store.count("name", input.name);
            if (nameCount > 0) {
                LOGGER.warn("Attempt to create store with existing name: " + input.name);
                throw new AlreadyExistsException("Store with name '" + input.name + "' already exists.");
            }
            // Creation Logic
            Store store = new Store();
            store.code = input.code;
            store.name = input.name;
            // Map Address fields
            Adresse address = new Adresse();
            address.streetLine1 = input.streetLine1;
            address.streetLine2 = input.streetLine2;
            address.postalCode = input.postalCode;
            address.city = input.city;
            address.country = input.country;
            address.latitude = input.latitude;
            address.longitude = input.longitude;
            store.address = address;
            store.persist();
            LOGGER.info("Exiting method createStore. Created ID: " + store.id);
            return store;
        }, StoreResource.class, "createStore");
    }

    /**
     * Updates an existing store identified by its ID.
     * Validates new code/name uniqueness if they are changed.
     *
     * @param id    The ID of the store to update.
     * @param input The input record containing fields to update.
     * @return The updated Store entity.
     * @throws GraphQLException if validation fails or the store is not found.
     */
    @Mutation
    @Description("Update an existing store")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Store updateStore(@Name("id") Long id, @Name("input") StoreRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateStore for id: " + id);
            Store store = Store.findById(id);
            if (store == null) {
                LOGGER.error("Store with id " + id + " not found");
                throw new NoSuchElementException("Store with id " + id + " not found");
            }
            // Pre-validation Checks (Update Logic)
            if (input.code != null && !input.code.equals(store.code)) {
                Store otherStoreWithCode = Store.findByCode(input.code);
                if (otherStoreWithCode != null) {
                    LOGGER.warn("Attempt to update store to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another store with code '" + input.code + "' already exists.");
                }
            }
            if (input.name != null && !input.name.equals(store.name)) {
                long nameCount = Store.count("name = ?1 and id <> ?2", input.name, id);
                if (nameCount > 0) {
                    LOGGER.warn("Attempt to update store to conflicting name: " + input.name);
                    throw new AlreadyExistsException("Another store with name '" + input.name + "' already exists.");
                }
            }
            // Apply Updates
            if (input.code != null) store.code = input.code;
            if (input.name != null) store.name = input.name;
            if (store.address == null) store.address = new Adresse();
            if (input.streetLine1 != null) store.address.streetLine1 = input.streetLine1;
            if (input.streetLine2 != null) store.address.streetLine2 = input.streetLine2;
            if (input.postalCode != null) store.address.postalCode = input.postalCode;
            if (input.city != null) store.address.city = input.city;
            if (input.country != null) store.address.country = input.country;
            if (input.latitude != null) store.address.latitude = input.latitude;
            if (input.longitude != null) store.address.longitude = input.longitude;
            LOGGER.info("Exiting method updateStore");
            return store;
        }, StoreResource.class, "updateStore");
    }

    /**
     * Deletes a store by its unique identifier.
     *
     * @param id The ID of the store to delete.
     * @return true if the store was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a store by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteStore(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteStore for id: " + id);
            boolean result = Store.deleteById(id);
            LOGGER.info("Exiting method deleteStore. Result: " + result);
            return result;
        }, StoreResource.class, "deleteStore");
    }

    /**
     * Input record type for GraphQL Store mutations.
     * <p>
     * Contains all fields corresponding to the {@link Store} entity and its embedded {@link Adresse}.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class StoreRecord {

        /**
         * The unique code of the store (e.g., "STORE_01").
         */
        public String code;

        /**
         * The name of the store.
         */
        public String name;

        /**
         * Address line 1 (Street address).
         */
        public String streetLine1;

        /**
         * Address line 2 (Apartment, suite, etc.).
         */
        public String streetLine2;

        /**
         * Postal code / ZIP code.
         */
        public String postalCode;

        /**
         * City name.
         */
        public String city;

        /**
         * Country name.
         */
        public String country;

        /**
         * GPS Latitude coordinate.
         */
        public Double latitude;

        /**
         * GPS Longitude coordinate.
         */
        public Double longitude;

        /**
         * Default constructor.
         */
        public StoreRecord() {
        }

        /**
         * Returns a string representation of the record.
         */
        @Override
        public String toString() {
            return "StoreRecord [code=" + code + ", name=" + name +
                    ", streetLine1=" + streetLine1 + ", streetLine2=" + streetLine2 +
                    ", postalCode=" + postalCode + ", city=" + city +
                    ", country=" + country + ", latitude=" + latitude +
                    ", longitude=" + longitude + "]";
        }
    }
}