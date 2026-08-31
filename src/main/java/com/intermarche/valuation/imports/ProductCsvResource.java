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
 * {@link Product} entities. It names the columns it consumes from the
 * shared feed (header-driven, see the COL_* dictionary) and the business
 * logic for creating/updating products.
 * <p>
 * It leverages the parent's Staged Fallback algorithm (1000 -> 100 -> 10 -> 1).
 */
@Path("/products/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ProductCsvResource.class);

    /** Header name of the natural key: the product EAN. */
    static final String COL_EAN = "EAN";
    /** Header name of the product label. */
    static final String COL_NAME = "NAME";
    /** Header name of the product description. */
    static final String COL_DESCRIPTION = "DESCRIPTION";
    /** Header name of the brand. */
    static final String COL_BRAND = "BRAND";
    /** Header name of the reference weight. */
    static final String COL_REFERENCE_WEIGHT = "REFERENCE_WEIGHT";
    /** Header name of the reference volume. */
    static final String COL_REFERENCE_VOLUME = "REFERENCE_VOLUME";
    /** Header name of the product type enum. */
    static final String COL_PRODUCT_TYPE = "PRODUCT_TYPE";
    /** Header name of the unit label. */
    static final String COL_UNIT_NAME = "UNIT_NAME";
    /** Header name of the active flag. */
    static final String COL_ACTIVE = "ACTIVE";

    /** The columns this importer cannot work without. */
    static final List<String> REQUIRED_COLUMNS = List.of(
            COL_NAME, COL_DESCRIPTION, COL_BRAND, COL_REFERENCE_WEIGHT,
            COL_REFERENCE_VOLUME, COL_PRODUCT_TYPE, COL_UNIT_NAME, COL_ACTIVE);

    /**
     * Imports or updates products from a CSV stream.
     * <p>
     * Delegates the stream reading and chunking to the abstract base class.
     * <p>
     * Consumed columns (resolved by header name; unknown columns of the
     * shared feed are ignored): EAN (key), NAME, DESCRIPTION, BRAND,
     * REFERENCE_WEIGHT, REFERENCE_VOLUME, PRODUCT_TYPE, UNIT_NAME, ACTIVE.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importProducts(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_EAN, REQUIRED_COLUMNS);
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
        product.name = data.get(COL_NAME);
        product.description = safeGet(data, COL_DESCRIPTION);
        product.brand = safeGet(data, COL_BRAND);
        product.referenceWeight = safeParseBigDecimal(data, COL_REFERENCE_WEIGHT);
        product.referenceVolume = safeParseBigDecimal(data, COL_REFERENCE_VOLUME);
        product.productType = safeParseProductType(data, COL_PRODUCT_TYPE);
        product.unitName = safeGet(data, COL_UNIT_NAME);
        product.active = safeParseBoolean(data, COL_ACTIVE);
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
        return Objects.hash(
                data.code,                                      // ean
                data.get(COL_NAME),                             // name
                safeGet(data, COL_DESCRIPTION),                 // description
                safeGet(data, COL_BRAND),                       // brand
                safeParseBigDecimal(data, COL_REFERENCE_WEIGHT),// referenceWeight
                safeParseBigDecimal(data, COL_REFERENCE_VOLUME),// referenceVolume
                safeParseProductType(data, COL_PRODUCT_TYPE),   // productType
                safeGet(data, COL_UNIT_NAME),                   // unitName
                safeParseBoolean(data, COL_ACTIVE)              // active
        );
    }

    /**
     * Safely parses a ProductType Enum from a column resolved by name.
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The ProductType value, or null on any missing/invalid input.
     */
    ProductType safeParseProductType(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return null;
        try {
            // Assuming enum constants are stored as strings (e.g., "UNIT", "WEIGHT")
            return ProductType.valueOf(val.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown ProductType value: " + val + " in column " + column);
            return null;
        }
    }
}