package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST Endpoint for bulk importing or updating Prices from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link Price} entities.
 * It manages the composite key matching (Product + Store + Usage + Dates) and leverages
 * the base class for the staged transaction management (1000 -> 100 -> 10 -> 1).
 * <p>
 * Expected CSV format (9 columns):
 * EAN|StoreCode|PriceExcludingTax|PriceIncludingTax|VatRate|PriceUsage|Priority|StartDateTime|EndDateTime
 */
@Path("/prices/import")
@ApplicationScoped
@RunOnVirtualThread
public class PriceCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(PriceCsvResource.class);

    // Keys used to store auxiliary maps in the generic context map
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";
    private static final String CTX_STORES = "__CTX_STORES__";
    private static final String CTX_PRICES = "__CTX_PRICES__";

    /**
     * Imports or updates prices from a CSV stream.
     * Delegates stream reading and chunking to the the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importPrices(InputStream inputStream) {
        // 9 columns expected
        return this.importCsvStream(inputStream, 9);
    }

    /**
     * Implements the chunk processing logic for Prices.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Performs bulk fetching of Products, Stores, and Prices based on data in the current chunk.
     * Builds a "Context Map" containing all three maps to be passed to the generic algorithm.
     * <p>
     * <b>Phase 2 (Generic):</b>
     * Delegates to {@link ImporterCsvResource#processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique EAN codes in this chunk (Column 0).
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map containing all entities (Products, Stores, Prices) needed for processing.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();

        // 1. Create Context Map
        Map<String, Object> contextMap = new HashMap<>();

        // Bulk Fetch Products
        Map<String, Product> productMap = getProductMap(targetCodes);
        contextMap.put(CTX_PRODUCTS, productMap);

        // Bulk Fetch Stores
        Set<String> targetStoreCodes = getTargetStoreCodes(parsedLines);
        Map<String, Store> storeMap = getStoreMap(targetStoreCodes);
        contextMap.put(CTX_STORES, storeMap);

        // Bulk Fetch Existing Prices (Composite Key: EAN|Store|Usage|Start|Priority)
        Map<String, Price> priceMap = getPriceMap(targetCodes, targetStoreCodes);
        contextMap.put(CTX_PRICES, priceMap);

        // 5. Return Context Map to trigger Generic Staged Fallback
        return contextMap;
    }

    /**
     * Retrieves a map of Prices based on Product and Store criteria.
     * <p>
     * Performs a broad query to fetch all prices matching the products and stores in the current chunk.
     * Builds a map indexed by a composite key ("ean:store:usage:start:priority") for fast lookup.
     *
     * @param targetCodes      The set of product EANs.
     * @param targetStoreCodes  The set of store codes.
     * @return A map of composite keys to Price entities.
     */
    private Map<String, Price> getPriceMap(Set<String> targetCodes, Set<String> targetStoreCodes) {
        // 3. Bulk Fetch Existing Prices
        // We use a broad query first, then build a map with a composite key
        List<Price> existingPrices = Price.list("product.ean IN ?1 AND store.code IN ?2", targetCodes, targetStoreCodes);

        Map<String, Price> priceMap = new HashMap<>();
        for (Price p : existingPrices) {
            String key = buildPriceKey(p.product.ean, p.store.code, p.priceUsage, p.startDateTime, p.priority);
            priceMap.put(key, p);
        }
        return priceMap;
    }

    /**
     * Retrieves a map of Stores based on a set of codes.
     *
     * @param targetStoreCodes The set of store codes to search for.
     * @return A map of store code to Store entity.
     */
    private static Map<String, Store> getStoreMap(Set<String> targetStoreCodes) {
        Map<String, Store> storeMap = new HashMap<>();
        if (!targetStoreCodes.isEmpty()) {
            List<Store> stores = Store.list("code IN ?1", targetStoreCodes);
            for (Store s : stores) {
                storeMap.put(s.code, s);
            }
        }
        return storeMap;
    }

    /**
     * Extracts unique Store codes from the parsed lines.
     *
     * @param parsedLines The list of data for the current chunk.
     * @return A set of unique store codes found in Column 1.
     */
    private Set<String> getTargetStoreCodes(List<LineData> parsedLines) {
        Set<String> targetStoreCodes = new HashSet<>();
        for (LineData data : parsedLines) {
            String storeCode = safeGet(data.parts, 1);
            if (storeCode != null) {
                targetStoreCodes.add(storeCode);
            }
        }
        return targetStoreCodes;
    }

    /**
     * Retrieves a map of Products based on a set of EAN codes.
     *
     * @param targetCodes The set of product EANs to search for.
     * @return A map of EAN to Product entity.
     */
    private static Map<String, Product> getProductMap(Set<String> targetCodes) {
        // 2. Bulk Fetch Dependencies (Products & Stores)
        Map<String, Product> productMap = new HashMap<>();
        if (!targetCodes.isEmpty()) {
            List<Product> products = Product.list("ean IN ?1", targetCodes);
            for (Product p : products) {
                productMap.put(p.ean, p);
            }
        }
        return productMap;
    }

    /**
     * Implements the specific logic for creating or updating a Price entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Product, Store, and Price maps from the context.
     * If maps are missing (1-by-1 fallback mode), it performs individual fetches.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map containing Products, Stores, and Prices.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // 1. Retrieve or Fetch Dependencies
        @SuppressWarnings("unchecked")
        Map<String, Product> productMap = (Map<String, Product>) entityMap.get(CTX_PRODUCTS);
        @SuppressWarnings("unchecked")
        Map<String, Store> storeMap = (Map<String, Store>) entityMap.get(CTX_STORES);

        Product product = getProduct(data, productMap);
        String storeCode = safeGet(data.parts, 1);
        Store store = getStore(storeMap, storeCode);
        Map<String, Price> priceMap = retrievePrices(data, entityMap, storeCode);

        // 3. Process Price Logic
        processPriceLogic(data, counters, storeCode, priceMap, product, store);
    }

    /**
     * Core logic to create or update a Price entity.
     * <p>
     * It checks if a price with the same composite key (Product + Store + Usage + Date + Priority) already exists.
     * If it exists and the data has changed (checksum), it updates the fields. Otherwise, it creates a new one.
     *
     * @param data         The parsed CSV line data.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param storeCode    The store code associated with this price.
     * @param priceMap     The map of existing prices (indexed by composite key).
     * @param product      The Product entity associated with this price.
     * @param store        The Store entity associated with this price.
     */
    private void processPriceLogic(LineData data, int[] counters, String storeCode, Map<String, Price> priceMap, Product product, Store store) {
        PriceUsage usage = safeParsePriceUsage(data.parts, 5);
        LocalDateTime start = safeParseDateTime(data.parts, 7);
        Integer priority = safeParseInt(data.parts, 6);
        String key = buildPriceKey(data.code, storeCode, usage, start, priority);
        Price price = priceMap.get(key);

        boolean isNew = (price == null);
        if (isNew) {
            price = new Price();
            price.product = product;
            price.store = store;
            feedPrice(data, price);
            counters[0]++;
            Panache.getEntityManager().persist(price);
        } else {
            // Checksum Optimization
            int incomingChecksum = computeIncomingChecksum(data, product, store);
            if (price.checksum != incomingChecksum) {
                price = Price.findById(price.id);
                product = Product.findById(product.id);
                store = Store.findById(store.id);
                price.product = product;
                price.store = store;
                feedPrice(data, price);
                counters[1]++;
            }
        }
    }

    /**
     * Retrieves the Product entity for the current line.
     * <p>
     * Looks up the product in the provided map. If the map is null (1-by-1 fallback mode), it fetches the product from the database.
     *
     * @param data       The parsed CSV line data.
     * @param productMap The map of products (can be null).
     * @return The Product entity.
     * @throws IllegalArgumentException if the product is not found.
     */
    private static Product getProduct(LineData data, Map<String, Product> productMap) {
        if (productMap == null) {
            productMap = new HashMap<>();
            Product p = Product.findByEan(data.code);
            if (p != null) productMap.put(p.ean, p);
        }
        Product product = productMap.get(data.code);
        if (product == null) {
            throw new IllegalArgumentException("Product with EAN " + data.code + " not found.");
        }
        return product;
    }

    /**
     * Retrieves the Store entity for the current line.
     * <p>
     * Looks up the store in the provided map. If the map is null (1-by-1 fallback mode), it fetches the store from the database.
     *
     * @param storeMap The map of stores (can be null).
     * @param storeCode The store code to look for.
     * @return The Store entity.
     * @throws IllegalArgumentException if the store is not found.
     */
    private static Store getStore(Map<String, Store> storeMap, String storeCode) {
        if (storeMap == null) {
            storeMap = new HashMap<>();
            if (storeCode != null) {
                Store s = Store.findByCode(storeCode);
                if (s != null) storeMap.put(s.code, s);
            }
        }
        Store store = storeMap.get(storeCode);
        if (store == null) {
            throw new IllegalArgumentException("Store with code " + storeCode + " not found.");
        }
        return store;
    }

    /**
     * Retrieves the Price map for the current line.
     * <p>
     * Returns the map from the context. If the map is missing (1-by-1 fallback mode), it performs a database lookup
     * using the composite key.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @param storeCode The store code (extracted from the line).
     * @return A map of composite keys to Price entities.
     */
    Map<String, Price> retrievePrices(LineData data, Map<String, Object> entityMap, String storeCode) {
        @SuppressWarnings("unchecked")
        Map<String, Price> priceMap = (Map<String, Price>) entityMap.get(CTX_PRICES);
        if (priceMap == null) {
            // 1-by-1 fallback: look for the specific price in DB
            PriceUsage usage = safeParsePriceUsage(data.parts, 5);
            LocalDateTime start = safeParseDateTime(data.parts, 7);
            Integer priority = safeParseInt(data.parts, 6);

            if (usage == null) {
                throw new IllegalArgumentException("PriceUsage is mandatory");
            }
            Price existing = Price.find(
                    "product.ean = ?1 and store.code = ?2 and priceUsage = ?3 and startDateTime = ?4 and priority = ?5",
                    data.code, storeCode, usage, start, priority
            ).firstResult();

            priceMap = new HashMap<>();
            if (existing != null) {
                String key = buildPriceKey(data.code, storeCode, usage, start, priority);
                priceMap.put(key, existing);
            }
        }
        return priceMap;
    }

    /**
     * Implements the specific logic to find a fresh Price from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     * Note: Since Price uses a composite key, this method must perform a full lookup based on the CSV columns.
     *
     * @param data The parsed CSV line data.
     * @return The Price entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        String storeCode = safeGet(data.parts, 1);
        PriceUsage usage = safeParsePriceUsage(data.parts, 5);
        LocalDateTime start = safeParseDateTime(data.parts, 7);
        Integer priority = safeParseInt(data.parts, 6);

        if (usage == null || storeCode == null) return null;

        return Price.find(
                "product.ean = ?1 and store.code = ?2 and priceUsage = ?3 and startDateTime = ?4 and priority = ?5",
                data.code, storeCode, usage, start, priority
        ).firstResult();
    }

    /**
     * Builds a consistent composite key for map lookup.
     * <p>
     * The key combines Product EAN, Store Code, Price Usage, Start Date, and Priority.
     * Null values are represented by the string "NULL".
     *
     * @param ean       The Product EAN.
     * @param storeCode The Store Code.
     * @param usage     The Price Usage enum.
     * @param start     The Start Date Time.
     * @param priority  The Priority integer.
     * @return A string representing the composite key.
     */
    private String buildPriceKey(String ean, String storeCode, PriceUsage usage, LocalDateTime start, Integer priority) {
        String startStr = start != null ? start.toString() : "NULL";
        String usageStr = usage != null ? usage.name() : "NULL";
        String prioStr = priority != null ? priority.toString() : "NULL";
        return ean + ":" + storeCode + ":" + usageStr + ":" + startStr + ":" + prioStr;
    }

    /**
     * Populates a Price entity with data from the parsed CSV line.
     *
     * @param data  The parsed CSV line data.
     * @param price The Price entity to populate.
     */
    private void feedPrice(LineData data, Price price) {
        String[] parts = data.parts;
        price.priceExcludingTax = safeParseBigDecimal(parts, 2);
        price.priceIncludingTax = safeParseBigDecimal(parts, 3);
        price.vatRate = safeParseBigDecimal(parts, 4);
        price.priceUsage = safeParsePriceUsage(parts, 5);
        if (price.priceUsage == null) {
            throw new IllegalArgumentException("PriceUsage is mandatory at column 5");
        }
        price.priority = safeParseInt(parts, 6);
        price.startDateTime = safeParseDateTime(parts, 7);
        price.endDateTime = safeParseDateTime(parts, 8);
    }

    /**
     * Computes the checksum for incoming CSV data.
     * <p>
     * Replicates {@link Price#getChecksum()} logic.
     *
     * @param data    The parsed CSV line data.
     * @param product The Product entity (to access ean).
     * @param store   The Store entity (to access code).
     * @return The integer hash of incoming data.
     */
    private int computeIncomingChecksum(LineData data, Product product, Store store) {
        String[] parts = data.parts;
        return Objects.hash(
                product.ean,
                store.code,
                safeParsePriceUsage(parts, 5),
                safeParseBigDecimal(parts, 2),
                safeParseBigDecimal(parts, 3),
                safeParseBigDecimal(parts, 4),
                safeParseInt(parts, 6),
                safeParseDateTime(parts, 7),
                safeParseDateTime(parts, 8)
        );
    }

    // --------------------------------------------------
    // Helper Parsers (Specific to Price)
    // --------------------------------------------------

    /**
     * Safely parses a PriceUsage enum from an array by index.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The PriceUsage value or null if parsing fails or index is out of bounds.
     */
    PriceUsage safeParsePriceUsage(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return PriceUsage.valueOf(val);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid PriceUsage at index " + index + ": " + val);
            return null;
        }
    }
}