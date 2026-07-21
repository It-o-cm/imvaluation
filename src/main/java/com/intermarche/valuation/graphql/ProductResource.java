package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Products.
 * <p>
 * This resource provides CRUD operations for the {@link Product} entity.
 * It enforces role-based access control: MANAGERs can read, ADMINs can write.
 * <p>
 * Implements {@link GraphQLTrait} to centralize exception handling and transaction wrapping
 * via the {@code execute} template method.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class ProductResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(ProductResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves a list of all products in the database.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @return A list of all Product entities.
     */
    @Query
    @Description("Get a list of all products")
    @RolesAllowed("MANAGER")
    public List<Product> allProducts() {
        LOGGER.info("Entering method allProducts");
        List<Product> result = Product.listAll();
        LOGGER.info("Exiting method allProducts");
        return result;
    }

    /**
     * Retrieves a specific product by its unique identifier.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @param id The unique ID of the product.
     * @return The Product entity.
     * @throws NoSuchElementException if the product with the given ID does not exist.
     */
    @Query
    @Description("Get a product by its ID")
    @RolesAllowed("MANAGER")
    public Product product(@Name("id") Long id) {
        LOGGER.info("Entering method product with id: " + id);
        Product product = Product.findById(id);
        if (product == null) {
            LOGGER.error("Product with id " + id + " not found");
            throw new NoSuchElementException("Product with id " + id + " not found");
        }
        LOGGER.info("Exiting method product");
        return product;
    }

    /**
     * Retrieves a specific product by its unique EAN code.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @param ean The unique EAN code of the product.
     * @return The Product entity.
     * @throws NoSuchElementException if the product with the given EAN does not exist.
     */
    @Query
    @Description("Get a product by its EAN")
    @RolesAllowed("MANAGER")
    public Product productByEan(@Name("ean") String ean) {
        LOGGER.info("Entering method productByEan with ean: " + ean);
        Product product = Product.findByEan(ean);
        if (product == null) {
            LOGGER.error("Product with ean " + ean + " not found");
            throw new NoSuchElementException("Product with ean " + ean + " not found");
        }
        LOGGER.info("Exiting method productByEan");
        return product;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // All methods delegate to the default method 'execute' from GraphQLTrait.
    // --------------------------------------------------

    /**
     * Creates a new product in the database.
     * <p>
     * Validates that the EAN and name are unique before creation.
     * If the 'active' field is not provided in the input, it defaults to 'true'.
     *
     * @param input The input record containing product details.
     * @return The newly created Product entity.
     * @throws GraphQLException if validation fails (duplicate EAN/Name) or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new product")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Product createProduct(@Name("input") ProductRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createProduct for ean: " + input.ean);

            // --- Validation: Check EAN uniqueness ---
            Product existingProductByEan = Product.findByEan(input.ean);
            if (existingProductByEan != null) {
                LOGGER.warn("Attempt to create product with existing ean: " + input.ean);
                throw new AlreadyExistsException("Product with ean '" + input.ean + "' already exists.");
            }

            // --- Validation: Check Name uniqueness ---
            // Note: We assume product names must be unique.
            long nameCount = Product.count("name", input.name);
            if (nameCount > 0) {
                LOGGER.warn("Attempt to create product with existing name: " + input.name);
                throw new AlreadyExistsException("Product with name '" + input.name + "' already exists.");
            }

            // --- Creation Logic ---
            Product product = new Product();
            product.ean = input.ean;
            product.name = input.name;
            // Set optional fields only if provided
            product.description = input.description;
            product.brand = input.brand;
            product.referenceWeight = input.referenceWeight;
            product.referenceVolume = input.referenceVolume;
            // Convert String enum to Java Enum safely
            product.productType = input.productType != null ? ProductType.valueOf(input.productType) : null;
            product.unitName = input.unitName;
            // Default 'active' to true if not specified
            product.active = input.active != null ? input.active : true;

            product.persist();

            LOGGER.info("Exiting method createProduct. Created ID: " + product.id);
            return product;
        }, ProductResource.class, "createProduct");
    }

    /**
     * Updates an existing product identified by its ID.
     * <p>
     * Validates new EAN/name uniqueness only if they are being changed.
     * Only non-null fields in the input are updated (partial update support).
     *
     * @param id    The ID of the product to update.
     * @param input The input record containing fields to update.
     * @return The updated Product entity.
     * @throws GraphQLException if validation fails or the product is not found.
     */
    @Mutation
    @Description("Update an existing product")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Product updateProduct(@Name("id") Long id, @Name("input") ProductRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateProduct for id: " + id);

            Product product = Product.findById(id);
            if (product == null) {
                LOGGER.error("Product with id " + id + " not found");
                throw new NoSuchElementException("Product with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---

            // 1. EAN Uniqueness:
            // Only check if the input EAN is provided AND different from the current EAN.
            if (input.ean != null && !input.ean.equals(product.ean)) {
                Product otherProductWithEan = Product.findByEan(input.ean);
                if (otherProductWithEan != null) {
                    LOGGER.warn("Attempt to update product to conflicting ean: " + input.ean);
                    throw new AlreadyExistsException("Another product with ean '" + input.ean + "' already exists.");
                }
            }

            // 2. Name Uniqueness:
            // Only check if the input Name is provided AND different from the current Name.
            if (input.name != null && !input.name.equals(product.name)) {
                // Query: count products with the target name, excluding the current product ID.
                long nameCount = Product.count("name = ?1 and id <> ?2", input.name, id);
                if (nameCount > 0) {
                    LOGGER.warn("Attempt to update product to conflicting name: " + input.name);
                    throw new AlreadyExistsException("Another product with name '" + input.name + "' already exists.");
                }
            }

            // --- Apply Updates (Partial Update Logic) ---
            // Update fields only if they are non-null in the input.
            if (input.ean != null) product.ean = input.ean;
            if (input.name != null) product.name = input.name;
            if (input.description != null) product.description = input.description;
            if (input.brand != null) product.brand = input.brand;
            if (input.referenceWeight != null) product.referenceWeight = input.referenceWeight;
            if (input.referenceVolume != null) product.referenceVolume = input.referenceVolume;
            if (input.productType != null) product.productType = ProductType.valueOf(input.productType);
            if (input.unitName != null) product.unitName = input.unitName;
            if (input.active != null) product.active = input.active;

            LOGGER.info("Exiting method updateProduct");
            return product;
        }, ProductResource.class, "updateProduct");
    }

    /**
     * Deletes a product by its unique identifier.
     *
     * @param id The ID of the product to delete.
     * @return true if the product was deleted successfully, false if the product was not found.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a product by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteProduct(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteProduct for id: " + id);
            boolean result = Product.deleteById(id);
            LOGGER.info("Exiting method deleteProduct. Result: " + result);
            return result;
        }, ProductResource.class, "deleteProduct");
    }

    /**
     * Input record type for GraphQL Product mutations.
     * <p>
     * Contains all fields that can be used for creating or updating a product.
     * Fields are nullable to support partial updates.
     */
    @Input
    static public class ProductRecord {
        public String ean;
        public String name;
        public String description;
        public String brand;
        public BigDecimal referenceWeight;
        public BigDecimal referenceVolume;
        public String productType;
        public String unitName;
        public Boolean active;

        public ProductRecord() {
        }

        /**
         * Returns a string representation of the record.
         * Note: Used mainly for debugging/logging purposes.
         */
        public String toString() {
            return "ProductRecord [ean=" + ean + ", name=" + name + ", description=" + description +
                    ", brand=" + brand + ", referenceWeight=" + referenceWeight + ", referenceVolume=" + referenceVolume +
                    ", productType=" + productType + ", unitName=" + unitName + ", active=" + active;
        }

    }


}