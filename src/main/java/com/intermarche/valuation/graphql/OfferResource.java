package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GraphQL API for managing Offers.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Offers can be linked to multiple {@link Store} and/or multiple {@link StoreGroup}.
 * <p>
 * All associations use Business Codes (Strings) instead of Database IDs.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class OfferResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(OfferResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves a list of all offers in the database.
     *
     * @return A list of all Offer entities.
     */
    @Query
    @Description("Get the list of all offers")
    @RolesAllowed("MANAGER")
    public List<Offer> allOffers() {
        LOGGER.info("Entering method allOffers");
        List<Offer> result = Offer.listAll();
        LOGGER.info("Exiting method allOffers");
        return result;
    }

    /**
     * Retrieves a specific offer by its unique identifier.
     *
     * @param id The unique ID of the offer.
     * @return The Offer entity.
     * @throws NoSuchElementException if the offer with the given ID does not exist.
     */
    @Query
    @Description("Get an offer by its ID")
    @RolesAllowed("MANAGER")
    public Offer offer(@Name("id") Long id) {
        LOGGER.info("Entering method offer with id: " + id);
        Offer offer = Offer.findById(id);
        if (offer == null) {
            LOGGER.error("Offer with id " + id + " not found");
            throw new NoSuchElementException("Offer with id " + id + " not found");
        }
        LOGGER.info("Exiting method offer");
        return offer;
    }

    /**
     * Retrieves a specific offer by its unique code.
     *
     * @param code The unique code of the offer.
     * @return The Offer entity.
     * @throws NoSuchElementException if the offer with the given code does not exist.
     */
    @Query
    @Description("Get an offer by its code")
    @RolesAllowed("MANAGER")
    public Offer offerByCode(@Name("code") String code) {
        LOGGER.info("Entering method offerByCode with code: " + code);
        Offer offer = Offer.findByCode(code);
        if (offer == null) {
            LOGGER.error("Offer with code " + code + " not found");
            throw new NoSuchElementException("Offer with code " + code + " not found");
        }
        LOGGER.info("Exiting method offerByCode");
        return offer;
    }

    /**
     * Retrieves offers linked to a specific list of stores and of a specific type.
     *
     * @param storeCodes A list of store codes.
     * @param type       The type of the offer.
     * @return A list of matching Offer entities.
     * @throws NoSuchElementException if any of the stores with the given codes do not exist.
     */
    @Query
    @Description("Get offers by a list of store codes and type")
    @RolesAllowed("MANAGER")
    public List<Offer> offersByStoresAndType(@Name("storeCodes") List<String> storeCodes, @Name("type") String type) {
        LOGGER.info("Entering method offersByStoresAndType for type: " + type);
        if (storeCodes == null || storeCodes.isEmpty()) {
            return List.of();
        }
        List<Store> stores = Store.list("code IN ?1", storeCodes);
        if (stores.size() != storeCodes.size()) {
            throw new NoSuchElementException("One or more Store codes provided do not exist.");
        }
        // Query offers where the store collection contains any of the provided stores
        List<Offer> result = Offer.list("select o from Offer o join o.stores s where s in ?1 and o.type = ?2", stores, type);
        LOGGER.info("Exiting method offersByStoresAndType");
        return result;
    }

    /**
     * Retrieves offers linked to a list of store groups and of a specific type.
     *
     * @param storeGroupCodes A list of store group codes.
     * @param type            The type of the offer.
     * @return A list of matching Offer entities.
     * @throws NoSuchElementException if any of the store groups with the given codes do not exist.
     */
    @Query
    @Description("Get offers by a list of store group codes and type")
    @RolesAllowed("MANAGER")
    public List<Offer> offersByStoreGroupsAndType(@Name("storeGroupCodes") List<String> storeGroupCodes, @Name("type") String type) {
        LOGGER.info("Entering method offersByStoreGroupsAndType for type: " + type);
        if (storeGroupCodes == null || storeGroupCodes.isEmpty()) {
            return List.of();
        }
        List<StoreGroup> groups = StoreGroup.list("code IN ?1", storeGroupCodes);
        if (groups.size() != storeGroupCodes.size()) {
            throw new NoSuchElementException("One or more StoreGroup codes provided do not exist.");
        }
        List<Offer> result = Offer.findByStoreGroupsAndType(groups, type);
        LOGGER.info("Exiting method offersByStoreGroupsAndType");
        return result;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new offer in the database.
     * Validates that the code is unique.
     * Validates that the offer is linked to at least one Store or StoreGroup using Business Codes.
     *
     * @param input The input record containing offer details and target codes.
     * @return The newly created Offer entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new offer")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Offer createOffer(@Name("input") OfferRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createOffer for code: " + input.code);
            // Check Uniqueness Constraint: code
            long codeCount = Offer.count("code", input.code);
            if (codeCount > 0) {
                LOGGER.warn("Attempt to create offer with existing code: " + input.code);
                throw new AlreadyExistsException("Offer with code '" + input.code + "' already exists.");
            }
            // Validation: At least one target list must be present
            boolean hasStores = input.storeCodes != null && !input.storeCodes.isEmpty();
            boolean hasGroups = input.storeGroupCodes != null && !input.storeGroupCodes.isEmpty();
            if (!hasStores && !hasGroups) {
                throw new IllegalArgumentException("Offer must be linked to at least one Store OR one StoreGroup.");
            }
            // Creation Logic
            Offer offer = new Offer();
            offer.code = input.code;
            offer.type = input.type;
            offer.specification = input.specification;
            // Link Targets
            if (hasStores) {
                Set<Store> stores = resolveStores(input.storeCodes);
                offer.stores.addAll(stores);
            }
            if (hasGroups) {
                Set<StoreGroup> groups = resolveStoreGroups(input.storeGroupCodes);
                offer.storeGroups.addAll(groups);
            }
            offer.persist();
            LOGGER.info("Exiting method createOffer. Created ID: " + offer.id);
            return offer;
        }, OfferResource.class, "createOffer");
    }

    /**
     * Updates an existing offer identified by its ID.
     * Validates the target relationships using Business Codes if modified.
     * <p>
     * If a list is provided (even empty), it replaces the current associations.
     * If a list is null, the current associations are kept unchanged.
     *
     * @param id    The ID of the offer to update.
     * @param input The input record containing fields to update.
     * @return The updated Offer entity.
     * @throws GraphQLException if validation fails or the offer is not found.
     */
    @Mutation
    @Description("Update an existing offer")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Offer updateOffer(@Name("id") Long id, @Name("input") OfferRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateOffer for id: " + id);
            Offer offer = Offer.findById(id);
            if (offer == null) {
                LOGGER.error("Offer with id " + id + " not found");
                throw new NoSuchElementException("Offer with id " + id + " not found");
            }
            // Apply Updates
            if (input.type != null) offer.type = input.type;
            if (input.specification != null) offer.specification = input.specification;
            // Handle Store Relationship Updates
            if (input.storeCodes != null) {
                offer.stores.clear();
                if (!input.storeCodes.isEmpty()) {
                    Set<Store> stores = resolveStores(input.storeCodes);
                    offer.stores.addAll(stores);
                }
            }
            // Handle StoreGroup Relationship Updates
            if (input.storeGroupCodes != null) {
                offer.storeGroups.clear();
                if (!input.storeGroupCodes.isEmpty()) {
                    Set<StoreGroup> groups = resolveStoreGroups(input.storeGroupCodes);
                    offer.storeGroups.addAll(groups);
                }
            }
            LOGGER.info("Exiting method updateOffer");
            return offer;
        }, OfferResource.class, "updateOffer");
    }

    /**
     * Deletes an offer by its unique identifier.
     *
     * @param id The ID of the offer to delete.
     * @return true if the offer was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete an offer by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteOffer(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteOffer for id: " + id);
            boolean result = Offer.deleteById(id);
            LOGGER.info("Exiting method deleteOffer. Result: " + result);
            return result;
        }, OfferResource.class, "deleteOffer");
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Resolves a list of store codes to a Set of Store entities.
     * Throws an exception if any code is not found.
     */
    private Set<Store> resolveStores(List<String> codes) {
        List<Store> stores = Store.list("code in ?1", codes);
        if (stores.size() != codes.size()) {
            // Find which codes are missing for a better error message
            Set<String> foundCodes = stores.stream().map(s -> s.code).collect(Collectors.toSet());
            List<String> missing = codes.stream().filter(c -> !foundCodes.contains(c)).toList();
            throw new NoSuchElementException("The following Store codes were not found: " + missing);
        }
        return new HashSet<>(stores);
    }

    /**
     * Resolves a list of store group codes to a Set of StoreGroup entities.
     * Throws an exception if any code is not found.
     */
    Set<StoreGroup> resolveStoreGroups(List<String> codes) {
        List<StoreGroup> groups = StoreGroup.list("code in ?1", codes);
        if (groups.size() != codes.size()) {
            Set<String> foundCodes = groups.stream().map(g -> g.code).collect(Collectors.toSet());
            List<String> missing = codes.stream().filter(c -> !foundCodes.contains(c)).toList();
            throw new NoSuchElementException("The following StoreGroup codes were not found: " + missing);
        }
        return new HashSet<>(groups);
    }

    /**
     * Input record type for GraphQL Offer mutations.
     * <p>
     * Contains fields corresponding to the {@link Offer} entity.
     * Relationships to Stores and StoreGroups are provided as lists of String Codes.
     */
    @Input
    static public class OfferRecord {

        /**
         * The unique code of the offer.
         */
        public String code;

        /**
         * The type of the offer (e.g., "VIGNETTE_DISCOUNT", "N+M").
         */
        public String type;

        /**
         * The JSON specification defining the offer rules.
         */
        public String specification;

        /**
         * List of Store codes (business keys) to be linked to this offer.
         */
        public List<String> storeCodes;

        /**
         * List of StoreGroup codes (business keys) to be linked to this offer.
         */
        public List<String> storeGroupCodes;

        /**
         * Default constructor.
         */
        public OfferRecord() {
        }

        /**
         * Returns a string representation of the record.
         * Uses String.join to display list contents explicitly.
         */
        @Override
        public String toString() {
            String stores = storeCodes != null ? "[" + String.join(", ", storeCodes) + "]" : "null";
            String groups = storeGroupCodes != null ? "[" + String.join(", ", storeGroupCodes) + "]" : "null";
            return "OfferRecord [code=" + code +
                    ", type=" + type +
                    ", specification=" + specification +
                    ", storeCodes=" + stores +
                    ", storeGroupCodes=" + groups + "]";
        }
    }
}