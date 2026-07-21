package com.intermarche.valuation.graphql;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Product Families.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Manages logical groupings of Products and hierarchical ProductFamily relationships.
 * <p>
 * The relationship is unidirectional: A ProductFamily contains a list of Products
 * and a list of other ProductFamilies (Sub-families).
 * <p>
 * Input for children uses Business Codes (Strings) instead of Database IDs (Longs).
 */
@GraphQLApi
@ApplicationScoped
public class ProductFamilyResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(ProductFamilyResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    @Query
    @Description("Get the list of all product families")
    @RolesAllowed("MANAGER")
    public List<ProductFamily> allProductFamilies() {
        LOGGER.info("Entering method allProductFamilies");
        List<ProductFamily> result = ProductFamily.listAll();
        LOGGER.info("Exiting method allProductFamilies");
        return result;
    }

    @Query
    @Description("Get a product family by its ID")
    @RolesAllowed("MANAGER")
    public ProductFamily productFamily(@Name("id") Long id) {
        LOGGER.info("Entering method productFamily with id: " + id);
        ProductFamily family = ProductFamily.findById(id);
        if (family == null) {
            LOGGER.error("ProductFamily with id " + id + " not found");
            throw new NoSuchElementException("ProductFamily with id " + id + " not found");
        }
        LOGGER.info("Exiting method productFamily");
        return family;
    }

    @Query
    @Description("Get a product family by its code")
    @RolesAllowed("MANAGER")
    public ProductFamily productFamilyByCode(@Name("code") String code) {
        LOGGER.info("Entering method productFamilyByCode with code: " + code);
        ProductFamily family = ProductFamily.findByCode(code);
        if (family == null) {
            LOGGER.error("ProductFamily with code " + code + " not found");
            throw new NoSuchElementException("ProductFamily with code " + code + " not found");
        }
        LOGGER.info("Exiting method productFamilyByCode");
        return family;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new product family in the database.
     * Validates that the code and description are unique before creation.
     * Fetches children by their Business Codes (Strings).
     *
     * @param input The input record containing family details and child codes.
     * @return The newly created ProductFamily entity.
     * @throws GraphQLException if validation fails, a child is not found, or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new product family")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public ProductFamily createProductFamily(@Name("input") ProductFamilyRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createProductFamily for code: " + input.code);

            // Check Uniqueness Constraint: code
            long codeCount = ProductFamily.count("code", input.code);
            if (codeCount > 0) {
                LOGGER.warn("Attempt to create family with existing code: " + input.code);
                throw new AlreadyExistsException("ProductFamily with code '" + input.code + "' already exists.");
            }

            // Check Uniqueness Constraint: description
            long descCount = ProductFamily.count("description", input.description);
            if (descCount > 0) {
                LOGGER.warn("Attempt to create family with existing description: " + input.description);
                throw new AlreadyExistsException("ProductFamily with description '" + input.description + "' already exists.");
            }

            // Creation Logic
            ProductFamily family = new ProductFamily();
            family.code = input.code;
            family.description = input.description;

            // Link Products by Code
            if (input.productEans != null && !input.productEans.isEmpty()) {
                for (String ean : input.productEans) {
                    Product product = Product.findByEan(ean);
                    if (product == null) {
                        throw new NoSuchElementException(String.format("Product with ean '%s' not found.", ean));
                    }
                    family.products.add(product);
                }
            }

            // Link Sub-Families by Code
            if (input.productFamilyCodes != null && !input.productFamilyCodes.isEmpty()) {
                for (String code : input.productFamilyCodes) {
                    ProductFamily child = ProductFamily.findByCode(code);
                    if (child == null) {
                        throw new NoSuchElementException("ProductFamily with code '" + code + "' not found.");
                    }
                    family.productFamilies.add(child);
                }
            }

            family.persist();

            LOGGER.info("Exiting method createProductFamily. Created ID: " + family.id);
            return family;
        }, ProductFamilyResource.class, "createProductFamily");
    }

    /**
     * Updates an existing product family identified by its ID.
     * Validates new description uniqueness if it is changed.
     * Allows updating the lists of associated products and sub-families using Business Codes.
     *
     * @param id    The ID of the family to update.
     * @param input The input record containing fields to update.
     * @return The updated ProductFamily entity.
     * @throws GraphQLException if validation fails, a child is not found, or the family is not found.
     */
    @Mutation
    @Description("Update an existing product family")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public ProductFamily updateProductFamily(@Name("id") Long id, @Name("input") ProductFamilyRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateProductFamily for id: " + id);

            ProductFamily family = ProductFamily.findById(id);
            if (family == null) {
                LOGGER.error("ProductFamily with id " + id + " not found");
                throw new NoSuchElementException("ProductFamily with id " + id + " not found");
            }

            String currentDescription = family.description;

            // Description uniqueness check
            if (input.description != null && !input.description.equals(currentDescription)) {
                long descCount = ProductFamily.count("description = ?1 and id <> ?2", input.description, id);
                if (descCount > 0) {
                    LOGGER.warn("Attempt to update family to conflicting description: " + input.description);
                    throw new AlreadyExistsException("Another family with description '" + input.description + "' already exists.");
                }
            }

            // Apply Description Update
            if (input.description != null) family.description = input.description;

            // Update Product Relationships (Replacement Strategy by Code)
            if (input.productEans != null) {
                family.products.clear();
                for (String ean : input.productEans) {
                    Product product = Product.findByEan(ean);
                    if (product == null) {
                        throw new NoSuchElementException(String.format("Product with code '%s' not found.", ean));
                    }
                    family.products.add(product);
                }
            }

            // Update ProductFamily Relationships (Replacement Strategy by Code)
            if (input.productFamilyCodes != null) {
                family.productFamilies.clear();
                for (String code : input.productFamilyCodes) {
                    ProductFamily child = ProductFamily.findByCode(code);
                    if (child == null) {
                        throw new NoSuchElementException("ProductFamily with code '" + code + "' not found.");
                    }
                    if (child.code.equals(family.code)) {
                        throw new IllegalArgumentException("A ProductFamily cannot contain itself.");
                    }
                    family.productFamilies.add(child);
                }
            }

            LOGGER.info("Exiting method updateProductFamily");
            return family;
        }, ProductFamilyResource.class, "updateProductFamily");
    }

    @Mutation
    @Description("Delete a product family by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteProductFamily(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteProductFamily for id: " + id);
            boolean result = ProductFamily.deleteById(id);
            LOGGER.info("Exiting method deleteProductFamily. Result: " + result);
            return result;
        }, ProductFamilyResource.class, "deleteProductFamily");
    }

    /**
     * Input record type for GraphQL ProductFamily mutations.
     * <p>
     * Contains fields corresponding to the {@link ProductFamily} entity.
     * Relationships to Products and other ProductFamilies are provided as lists of String Codes (EANs and Family Codes).
     */
    @Input
    static public class ProductFamilyRecord {

        /**
         * The unique code of the product family.
         */
        public String code;

        /**
         * The description of the product family.
         */
        public String description;

        /**
         * List of Product EANs (business keys) to be linked as children.
         */
        public List<String> productEans;

        /**
         * List of other ProductFamily codes to be linked as sub-families.
         */
        public List<String> productFamilyCodes;

        /**
         * Default constructor.
         */
        public ProductFamilyRecord() {
        }

        /**
         * Returns a string representation of the record.
         * Uses String.join to display list contents explicitly.
         */
        @Override
        public String toString() {
            String eans = productEans != null ? "[" + String.join(", ", productEans) + "]" : "null";
            String families = productFamilyCodes != null ? "[" + String.join(", ", productFamilyCodes) + "]" : "null";
            return "ProductFamilyRecord [code=" + code + ", description=" + description +
                    ", productEans=" + eans + ", productFamilyCodes=" + families + "]";
        }
    }
}