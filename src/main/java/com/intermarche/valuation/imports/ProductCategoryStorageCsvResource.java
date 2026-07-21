package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductCategoryStorage;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST Endpoint for bulk importing or updating Product Category Storages from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link ProductCategoryStorage} entities.
 * It manages the composite key matching (Product EAN + Level1 + Level5) and leverages
 * the base class for the staged transaction management (1000 -> 100 -> 10 -> 1).
 * <p>
 * Expected CSV format (6 columns):
 * ProductEAN|Level1|Level2|Level3|Level4|Level5
 */
@Path("/product-category-storages/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductCategoryStorageCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ProductCategoryStorageCsvResource.class);

    // Keys used to store auxiliary maps in the generic context map
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";
    static final String CTX_STORAGES = "__CTX_STORAGES__";

    /**
     * Imports or updates product category storages from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importCategoryStorages(InputStream inputStream) {
        // 6 columns expected: EAN, L1, L2, L3, L4, L5
        return this.importCsvStream(inputStream, 6);
    }

    /**
     * Implements the chunk processing logic for ProductCategoryStorage.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Performs bulk fetching of Products (by EAN) and existing Storages.
     * Constructs a "Context Map" containing both maps to be passed to the generic algorithm.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Product EANs in this chunk (Column 0).
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map containing the Product map and Storage map needed for processing.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // 1. Bulk Fetch Products by EAN
        Map<String, Product> productMap = getProductMap(targetCodes);
        // 2. Bulk Fetch Existing Storages
        // We fetch storages based on the Product IDs we just found
        Set<Long> productIds = productMap.values().stream().map(p -> p.id).collect(Collectors.toSet());
        Map<String, ProductCategoryStorage> storageMap = getStorageMap(productIds);
        // 3. Create Context Map
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(CTX_PRODUCTS, productMap);
        contextMap.put(CTX_STORAGES, storageMap);
        return contextMap;
    }

    /**
     * Retrieves a map of Products based on a set of EANs.
     */
    private Map<String, Product> getProductMap(Set<String> targetEans) {
        Map<String, Product> map = new HashMap<>();
        if (!targetEans.isEmpty()) {
            List<Product> products = Product.list("ean IN ?1", targetEans);
            for (Product p : products) {
                map.put(p.ean, p);
            }
        }
        return map;
    }

    /**
     * Retrieves a map of existing Storages based on a set of Product IDs.
     * Keys the map by "ean:level1:level5" for easy lookup against CSV data.
     */
    private Map<String, ProductCategoryStorage> getStorageMap(Set<Long> productIds) {
        Map<String, ProductCategoryStorage> map = new HashMap<>();
        if (!productIds.isEmpty()) {
            List<ProductCategoryStorage> storages = ProductCategoryStorage.list("product.id IN ?1", productIds);
            for (ProductCategoryStorage s : storages) {
                // Build key using EAN from the product relationship
                String key = buildStorageKey(s.product.ean, s.level1, s.level5);
                map.put(key, s);
            }
        }
        return map;
    }

    /**
     * Implements the specific logic for creating or updating a ProductCategoryStorage entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Product and Storage maps from the context map.
     * If auxiliary maps are missing (1-by-1 fallback mode), it performs individual fetches.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map containing Products and Storages.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String ean = data.code; // Column 0 is now EAN
        String level1 = safeGet(data.parts, 1);
        String level5 = safeGet(data.parts, 5);
        // 1. Retrieve Product
        Map<String, Product> productMap = retrieveProducts(entityMap);
        Product product = productMap.get(ean);
        if (product == null) {
            throw new IllegalArgumentException("Product with EAN '" + ean + "' not found.");
        }
        // 2. Retrieve Storage
        Map<String, ProductCategoryStorage> storageMap = retrieveStorages(entityMap);
        String key = buildStorageKey(ean, level1, level5);
        ProductCategoryStorage storage = storageMap.get(key);
        // 3. Business Logic (Create/Update)
        boolean isNew = (storage == null);
        processProductStorage(data, counters, isNew, storage, product);
    }

    /**
     * Prepares the context map (Product and Storage maps) for a single line.
     * <p>
     * This method is called by the generic fallback mechanism {@code processLineByLine}
     * to ensure that the specific business logic in {@code processLineLogic} has access
     * to fresh entities within the current transaction.
     *
     * @param data The parsed line data containing the EAN and category levels.
     * @return A map containing {@link #CTX_PRODUCTS} and {@link #CTX_STORAGES}.
     * @throws IllegalArgumentException if the Product with the given EAN cannot be found.
     */
    @Override
    protected Map<String, Object> prepareContextForLine(LineData data) {
        // 1. Retrieve the Product (Essential for the context)
        Product product = Product.findByEan(data.code);
        if (product == null) {
            // Throw an error here so it is caught by the onFailure callback in processLineByLine
            throw new IllegalArgumentException("Product with EAN '" + data.code + "' not found.");
        }
        // 2. Retrieve the existing Storage via the existing helper method
        ProductCategoryStorage storage = (ProductCategoryStorage) findEntityForLine(data);
        // 3. Build the Product Map
        Map<String, Product> productMap = new HashMap<>();
        productMap.put(data.code, product);
        // 4. Build the Storage Map
        Map<String, ProductCategoryStorage> storageMap = new HashMap<>();
        if (storage != null) {
            String key = buildStorageKey(data.code, safeGet(data.parts, 1), safeGet(data.parts, 5));
            storageMap.put(key, storage);
        }
        // 5. Assemble and return the full context expected by processLineLogic
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(CTX_PRODUCTS, productMap);
        contextMap.put(CTX_STORAGES, storageMap);
        return contextMap;
    }

    /**
     * Retrieves the map of Products from the entity map, performing a fetch if missing.
     */
    private Map<String, Product> retrieveProducts(Map<String, Object> entityMap) {
        return (Map<String, Product>) entityMap.get(CTX_PRODUCTS);
    }

    /**
     * Retrieves the map of Storages from the entity map, performing a fetch if missing.
     */
    private Map<String, ProductCategoryStorage> retrieveStorages(Map<String, Object> entityMap) {
        return (Map<String, ProductCategoryStorage>) entityMap.get(CTX_STORAGES);
    }

    /**
     * Core logic to create or update a ProductCategoryStorage entity.
     */
    private void processProductStorage(LineData data, int[] counters, boolean isNew, ProductCategoryStorage storage, Product product) {
        if (isNew) {
            storage = new ProductCategoryStorage();
            storage.product = product;
            feedCategoryStorage(data, storage);
            counters[0]++;
            Panache.getEntityManager().persist(storage);
        } else {
            // Ensure we are attached in the current transaction
            storage = ProductCategoryStorage.findById(storage.id);
            // Checksum Optimization
            int incomingChecksum = computeIncomingChecksum(data, product.id);
            if (storage.checksum != incomingChecksum) {
                feedCategoryStorage(data, storage);
                counters[1]++;
            }
        }
    }

    /**
     * Implements the specific logic to find a fresh ProductCategoryStorage from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The ProductCategoryStorage entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        String ean = data.code;
        Product product = Product.findByEan(ean);
        return ProductCategoryStorage.find(
                "product.id = ?1 and level1 = ?2 and level5 = ?3",
                product.id,
                safeGet(data.parts, 1),
                safeGet(data.parts, 5)
        ).firstResult();
    }

    /**
     * Builds a consistent composite key for map lookup.
     *
     * @param ean    The Product EAN.
     * @param level1 Level 1 category name.
     * @param level5 Level 5 category name.
     * @return A string representing the composite key.
     */
    private String buildStorageKey(String ean, String level1, String level5) {
        return ean + ":" + level1 + ":" + level5;
    }

    /**
     * Populates a ProductCategoryStorage entity with data from the parsed CSV line.
     */
    private void feedCategoryStorage(LineData data, ProductCategoryStorage storage) {
        String[] parts = data.parts;
        storage.level1 = safeGet(parts, 1);
        storage.level2 = safeGet(parts, 2);
        storage.level3 = safeGet(parts, 3);
        storage.level4 = safeGet(parts, 4);
        storage.level5 = safeGet(parts, 5);
    }

    /**
     * Computes the checksum for incoming CSV data.
     * <p>
     * Replicates {@link ProductCategoryStorage#getChecksum()} logic.
     * Note: The entity checksum relies on Product ID, so we resolve it here.
     *
     * @param data      The parsed CSV line data.
     * @param productId The resolved Product ID.
     * @return The integer hash of incoming data.
     */
    private int computeIncomingChecksum(LineData data, Long productId) {
        String[] parts = data.parts;
        return Objects.hash(
                productId,
                safeGet(parts, 1),
                safeGet(parts, 2),
                safeGet(parts, 3),
                safeGet(parts, 4),
                safeGet(parts, 5)
        );
    }
}