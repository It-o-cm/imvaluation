package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing ProductCategoryStorage.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Manages the links between Products and their Category hierarchy paths.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class ProductCategoryStorageResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(ProductCategoryStorageResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves the list of all product category storages in the database.
     *
     * @return A list of all ProductCategoryStorage entities.
     */
    @Query
    @Description("Get the list of all product category storages")
    @RolesAllowed("MANAGER")
    public List<ProductCategoryStorage> allProductCategoryStorages() {
        LOGGER.info("Entering method allProductCategoryStorages");
        List<ProductCategoryStorage> result = ProductCategoryStorage.listAll();
        LOGGER.info("Exiting method allProductCategoryStorages");
        return result;
    }

    /**
     * Retrieves a specific product category storage by its unique identifier.
     *
     * @param id The unique ID of the storage link.
     * @return The ProductCategoryStorage entity.
     * @throws NoSuchElementException if the storage link with the given ID does not exist.
     */
    @Query
    @Description("Get a product category storage by its ID")
    @RolesAllowed("MANAGER")
    public ProductCategoryStorage productCategoryStorage(@Name("id") Long id) {
        LOGGER.info("Entering method productCategoryStorage with id: " + id);
        ProductCategoryStorage storage = ProductCategoryStorage.findById(id);
        if (storage == null) {
            LOGGER.error("ProductCategoryStorage with id " + id + " not found");
            throw new NoSuchElementException("ProductCategoryStorage with id " + id + " not found");
        }
        LOGGER.info("Exiting method productCategoryStorage");
        return storage;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // All methods delegate to the default method 'execute' from GraphQLTrait.
    // --------------------------------------------------

    /**
     * Creates a new product category storage link.
     * Validates that the Product exists and the combination (Product, Level1, Level5) is unique.
     *
     * @param input The input record containing link details.
     * @return The newly created ProductCategoryStorage entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new product category storage")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public ProductCategoryStorage createProductCategoryStorage(@Name("input") ProductCategoryStorageRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createProductCategoryStorage for product: " + input.productId);

            // Check if Product exists
            Product product = Product.findById(input.productId);
            if (product == null) {
                LOGGER.warn("Attempt to create storage for non-existent product id: " + input.productId);
                throw new NoSuchElementException("Product with id '" + input.productId + "' not found.");
            }

            // Check Uniqueness Constraint: product_id + level1 + level5 must be unique
            long existingCount = ProductCategoryStorage.count("product.id = ?1 and level1 = ?2 and level5 = ?3", input.productId, input.level1, input.level5);
            if (existingCount > 0) {
                LOGGER.warn("Attempt to create storage with conflicting combination (product, level1, level5)");
                throw new AlreadyExistsException("A storage link for this product and category path already exists.");
            }

            // Creation Logic
            ProductCategoryStorage storage = new ProductCategoryStorage();
            storage.product = product;
            storage.level1 = input.level1;
            storage.level2 = input.level2;
            storage.level3 = input.level3;
            storage.level4 = input.level4;
            storage.level5 = input.level5;

            storage.persist();

            LOGGER.info("Exiting method createProductCategoryStorage. Created ID: " + storage.id);
            return storage;
        }, ProductCategoryStorageResource.class, "createProductCategoryStorage");
    }

    /**
     * Updates an existing product category storage link identified by its ID.
     * Validates new uniqueness if the key fields (Product, Level1, Level5) are changed.
     *
     * @param id    The ID of the storage link to update.
     * @param input The input record containing fields to update.
     * @return The updated ProductCategoryStorage entity.
     * @throws GraphQLException if validation fails or the link is not found.
     */
    @Mutation
    @Description("Update an existing product category storage")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public ProductCategoryStorage updateProductCategoryStorage(@Name("id") Long id, @Name("input") ProductCategoryStorageRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateProductCategoryStorage for id: " + id);

            ProductCategoryStorage storage = ProductCategoryStorage.findById(id);
            if (storage == null) {
                LOGGER.error("ProductCategoryStorage with id " + id + " not found");
                throw new NoSuchElementException("ProductCategoryStorage with id " + id + " not found");
            }

            // If Product is being changed, check if new Product exists
            Long currentProductId = storage.product.id;
            String currentL1 = storage.level1;
            String currentL5 = storage.level5;

            if (input.productId != null && !input.productId.equals(currentProductId)) {
                Product newProduct = Product.findById(input.productId);
                if (newProduct == null) {
                    throw new NoSuchElementException("Product with id '" + input.productId + "' not found.");
                }
            }

            // Pre-validation: Check if new combination (Product, Level1, Level5) conflicts with another record
            // We must determine the 'proposed' values
            Long targetProductId = input.productId != null ? input.productId : currentProductId;
            String targetL1 = input.level1 != null ? input.level1 : currentL1;
            String targetL5 = input.level5 != null ? input.level5 : currentL5;

            // Only check uniqueness if at least one of the key fields changed
            if (!targetProductId.equals(currentProductId) || !targetL1.equals(currentL1) || !targetL5.equals(currentL5)) {
                long conflictCount = ProductCategoryStorage.count("product.id = ?1 and level1 = ?2 and level5 = ?3 and id <> ?4", targetProductId, targetL1, targetL5, id);
                if (conflictCount > 0) {
                    LOGGER.warn("Attempt to update storage to conflicting combination");
                    throw new AlreadyExistsException("Another storage link with this product and category path already exists.");
                }
            }

            // Apply Updates
            if (input.productId != null) storage.product = Product.findById(input.productId);
            if (input.level1 != null) storage.level1 = input.level1;
            if (input.level2 != null) storage.level2 = input.level2;
            if (input.level3 != null) storage.level3 = input.level3;
            if (input.level4 != null) storage.level4 = input.level4;
            if (input.level5 != null) storage.level5 = input.level5;

            LOGGER.info("Exiting method updateProductCategoryStorage");
            return storage;
        }, ProductCategoryStorageResource.class, "updateProductCategoryStorage");
    }

    /**
     * Deletes a product category storage link by its unique identifier.
     *
     * @param id The ID of the storage link to delete.
     * @return true if the storage link was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a product category storage by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteProductCategoryStorage(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteProductCategoryStorage for id: " + id);
            boolean result = ProductCategoryStorage.deleteById(id);
            LOGGER.info("Exiting method deleteProductCategoryStorage. Result: " + result);
            return result;
        }, ProductCategoryStorageResource.class, "deleteProductCategoryStorage");
    }

    /**
     * Input record type for GraphQL ProductCategoryStorage mutations.
     * <p>
     * Contains fields corresponding to the {@link ProductCategoryStorage} entity.
     * The 'productId' links the storage entry to a specific Product.
     * Levels 1 to 5 represent the hierarchical category path.
     */
    @Input
    static public class ProductCategoryStorageRecord {

        /**
         * The ID of the Product associated with this category path.
         */
        public Long productId;

        /**
         * Level 1 of the category hierarchy.
         */
        public String level1;

        /**
         * Level 2 of the category hierarchy.
         */
        public String level2;

        /**
         * Level 3 of the category hierarchy.
         */
        public String level3;

        /**
         * Level 4 of the category hierarchy.
         */
        public String level4;

        /**
         * Level 5 of the category hierarchy.
         */
        public String level5;

        /**
         * Default constructor.
         */
        public ProductCategoryStorageRecord() {
        }

        /**
         * Returns a string representation of the record.
         */
        @Override
        public String toString() {
            return "ProductCategoryStorageRecord [productId=" + productId +
                    ", level1=" + level1 + ", level2=" + level2 +
                    ", level3=" + level3 + ", level4=" + level4 +
                    ", level5=" + level5 + "]";
        }
    }
}