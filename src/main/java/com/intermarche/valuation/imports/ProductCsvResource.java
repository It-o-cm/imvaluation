package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST Endpoint for bulk importing or updating Products from a CSV file stream.
 * <p>
 * This specific implementation extends {@link ImporterCsvResource} to handle
 * {@link Product} entities. It defines the CSV structure (9 columns) and the
 * business logic for creating/updating products.
 * <p>
 * It leverages the parent's Staged Fallback algorithm (1000 -> 100 -> 10 -> 1).
 */
@Path("/products/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ProductCsvResource.class);

    /**
     * Imports or updates products from a CSV stream.
     * <p>
     * Delegates the stream reading and chunking to the abstract base class.
     * <p>
     * Expected CSV format (9 columns):
     * EAN, Name, Description, Brand, ReferenceWeight, ReferenceVolume, ProductType, UnitName, Active
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importProducts(InputStream inputStream) {
        // 9 columns expected
        return this.importCsvStream(inputStream, 9);
    }

    /**
     * Implements the chunk processing logic for Products.
     * <p>
     * <b>Phase 1 (Specific):</b> Bulk fetches existing Products for the current chunk.
     * <b>Phase 2 (Generic):</b> Delegates to {@link ImporterCsvResource#processWithStages}
     * to handle the transactional staging logic.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetEans  The set of unique EAN codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetEans, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // SPECIFIC: Bulk Fetch existing products
        Map<String, Object> contextMap = new HashMap<>();
        if (!targetEans.isEmpty()) {
            List<Product> existingProducts = Product.list("ean IN ?1", targetEans);
            for (Product p : existingProducts) {
                contextMap.put(p.ean, p);
            }
        }
        return contextMap;
    }

    /**
     * Implements the specific logic for creating or updating a Product entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It uses the provided entityMap (which may contain pre-fetched or fresh entities).
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  A map of existing entities (Key: EAN, Value: Product).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // Retrieve product from map (cast from Object)
        Product product = (Product) entityMap.get(data.code);
        if (product == null) {
            // Create new
            product = new Product();
            product.ean = data.code;
            feedProduct(data, product);
            counters[0]++; // Created
            Panache.getEntityManager().persist(product);
        } else {
            // Update existing if data changed (Checksum Optimization)
            int incomingChecksum = computeIncomingChecksum(data);
            if (product.checksum != incomingChecksum) {
                product = Product.findById(product.id);
                feedProduct(data, product);
                counters[1]++; // Updated
            }
        }
    }

    /**
     * Implements the specific logic to find a fresh Product from the database.
     * <p>
     * Used by the generic 1-by-1 fallback to ensure data freshness.
     *
     * @param data The parsed CSV line data.
     * @return The Product entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Product.find("ean", data.code).firstResult();
    }

    // --------------------------------------------------
    // Specific Helpers for Product
    // --------------------------------------------------

    /**
     * Populates a Product entity with data from the parsed CSV line.
     *
     * @param data    The parsed CSV line data.
     * @param product The Product entity to populate.
     */
    private void feedProduct(LineData data, Product product) {
        String[] parts = data.parts;
        product.name = parts[1].trim();
        product.description = safeGet(parts, 2);
        product.brand = safeGet(parts, 3);
        product.referenceWeight = safeParseBigDecimal(parts, 4);
        product.referenceVolume = safeParseBigDecimal(parts, 5);
        product.productType = safeParseProductType(parts, 6);
        product.unitName = safeGet(parts, 7);
        product.active = safeParseBoolean(parts, 8);
    }

    /**
     * Computes a checksum for incoming CSV data.
     * <p>
     * This method replicates the logic found in {@link Product#getChecksum()}
     * to calculate a hash in memory without persisting the object.
     *
     * @param data The parsed CSV line data.
     * @return The integer hash of incoming data.
     */
    private int computeIncomingChecksum(LineData data) {
        String[] parts = data.parts;
        return Objects.hash(
                data.code,                    // ean
                parts[1].trim(),             // name
                safeGet(parts, 2),           // description
                safeGet(parts, 3),           // brand
                safeParseBigDecimal(parts, 4),// referenceWeight
                safeParseBigDecimal(parts, 5),// referenceVolume
                safeParseProductType(parts, 6),// productType
                safeGet(parts, 7),           // unitName
                safeParseBoolean(parts, 8)    // active
        );
    }

    /**
     * Safely parses a ProductType Enum from an array by index.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The ProductType value or null if parsing fails or index is out of bounds.
     */
    ProductType safeParseProductType(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            // Assuming enum constants are stored as strings (e.g., "UNIT", "WEIGHT")
            return ProductType.valueOf(val.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown ProductType value: " + val + " at index " + index);
            return null;
        }
    }
}