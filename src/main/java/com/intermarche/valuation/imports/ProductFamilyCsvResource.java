package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
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

/**
 * REST Endpoint for bulk importing or updating ProductFamilies from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link ProductFamily} entities.
 * It manages the linking of Products and Sub-Families, and leverages the base class for the staged transaction management (1000 -> 100 -> 10 -> 1).
 * <p>
 * CSV Format (5 columns):
 * code|description|flags|product_eans|family_codes
 * <p>
 * Note: product_eans and family_codes can contain multiple values separated by commas.
 */
@Path("/product-families/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductFamilyCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ProductFamilyCsvResource.class);

    // Keys used to store auxiliary maps in the generic context map
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";
    private static final String CTX_SUB_FAMILIES = "__CTX_SUB_FAMILIES__";

    /**
     * Imports or updates product families from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importProductFamilies(InputStream inputStream) {
        // 5 columns expected: code, description, flags, products, sub-families
        return this.importCsvStream(inputStream, 5);
    }

    /**
     * Implements the chunk processing logic for ProductFamilies.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Extracts codes for Products and Sub-Families. Performs bulk fetching of Families, Products, and Sub-Families.
     * Stores them in a "Context Map".
     * <p>
     * <b>Phase 2 (Generic):</b>
     * Delegates to {@link ImporterCsvResource#processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Family codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map containing all entities (Families, Products, Sub-Families) needed for processing.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        Set<String> targetProductEans = getTargetProductEans(parsedLines);
        Set<String> targetSubFamilyCodes = getProductSubFamilyCodes(parsedLines);
        // Create Context Map
        Map<String, Object> contextMap = new HashMap<>();
        Map<String, ProductFamily> familyMap = getProductFamilies(targetCodes);
        contextMap.putAll(familyMap);
        Map<String, Product> productMap = getProductMap(targetProductEans);
        contextMap.put(CTX_PRODUCTS, productMap);
        Map<String, ProductFamily> subFamilyMap = getProductSubFamilies(targetSubFamilyCodes);
        contextMap.put(CTX_SUB_FAMILIES, subFamilyMap);
        // 6. Trigger Generic Staged Fallback
        return contextMap;
    }

    /**
     * Bulk fetches Sub-Families based on a set of codes.
     *
     * @param targetSubFamilyCodes The set of sub-family codes to fetch.
     * @return A map of sub-family code to {@link ProductFamily} entity.
     */
    private static Map<String, ProductFamily> getProductSubFamilies(Set<String> targetSubFamilyCodes) {
        // 4. Bulk Fetch Sub-Families (Children)
        Map<String, ProductFamily> subFamilyMap = new HashMap<>();
        if (!targetSubFamilyCodes.isEmpty()) {
            List<ProductFamily> subFamilies = ProductFamily.list("code IN ?1", targetSubFamilyCodes);
            for (ProductFamily sf : subFamilies) {
                subFamilyMap.put(sf.code, sf);
            }
        }
        return subFamilyMap;
    }

    /**
     * Extracts unique sub-family codes from the parsed lines.
     *
     * @param parsedLines The list of parsed line data.
     * @return A set of unique sub-family codes.
     */
    private Set<String> getProductSubFamilyCodes(List<LineData> parsedLines) {
        Set<String> targetSubFamilyCodes = new HashSet<>();
        for (LineData data : parsedLines) {
            targetSubFamilyCodes.addAll(parseCodes(data.parts[4]));
        }
        return targetSubFamilyCodes;
    }

    /**
     * Bulk fetches Parent Families based on a set of codes.
     *
     * @param targetCodes The set of family codes to fetch.
     * @return A map of family code to {@link ProductFamily} entity.
     */
    private static Map<String, ProductFamily> getProductFamilies(Set<String> targetCodes) {
        // 2. Bulk Fetch Families (Parents)
        List<ProductFamily> existingFamilies = ProductFamily.list("code IN ?1", targetCodes);
        Map<String, ProductFamily> familyMap = new HashMap<>();
        for (ProductFamily f : existingFamilies) {
            familyMap.put(f.code, f);
        }
        return familyMap;
    }

    /**
     * Extracts unique Product EANs from the parsed lines.
     *
     * @param parsedLines The list of parsed line data.
     * @return A set of unique product EANs.
     */
    private Set<String> getTargetProductEans(List<LineData> parsedLines) {
        // 1. Extract sets of related codes for bulk fetching
        Set<String> targetProductEans = new HashSet<>();
        for (LineData data : parsedLines) {
            targetProductEans.addAll(parseCodes(data.parts[3]));
        }
        return targetProductEans;
    }

    /**
     * Bulk fetches Products based on a set of EAN codes.
     *
     * @param targetProductEans The set of product EANs to fetch.
     * @return A map of EAN to {@link Product} entity.
     */
    private static Map<String, Product> getProductMap(Set<String> targetProductEans) {
        // 3. Bulk Fetch Products (Children)
        Map<String, Product> productMap = new HashMap<>();
        if (!targetProductEans.isEmpty()) {
            List<Product> products = Product.list("ean IN ?1", targetProductEans);
            for (Product p : products) {
                productMap.put(p.ean, p);
            }
        }
        return productMap;
    }

    /**
     * Implements the specific logic for creating or updating a ProductFamily entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Product and Sub-Family maps from the context.
     * If maps are missing (1-by-1 fallback mode), it performs individual fetches.
     * <p>
     * It throws exceptions for missing dependencies (products/sub-families) or invalid logic (self-reference)
     * to trigger the transaction rollback and fallback mechanism.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map containing Families, Products, and Sub-Families.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // 1. Retrieve Family
        ProductFamily family = (ProductFamily) entityMap.get(data.code);
        // 2. Retrieve Auxiliary Maps
        List<String> requestedEans = parseCodes(data.parts[3]);
        Map<String, Product> productMap = retrieveProducts(entityMap, requestedEans);
        List<String> requestedSubCodes = parseCodes(data.parts[4]);
        Map<String, ProductFamily> subFamilyMap = retrieveSubProductFamilies(entityMap, requestedSubCodes);
        // 4. Business Logic (Create/Update)
        boolean isNew = (family == null);
        if (isNew) {
            family = new ProductFamily();
            family.code = data.code;
        }
        prepareProductFamily(data, family, requestedEans, productMap);
        linkSubFamilies(family, requestedSubCodes, subFamilyMap);
        // 5. Persist or Update
        int incomingChecksum = computeIncomingChecksum(data);
        if (isNew) {
            counters[0]++;
            Panache.getEntityManager().persist(family);
        } else {
            if (family.checksum != incomingChecksum) {
                counters[1]++;
            }
        }
    }

    /**
     * Links the specified sub-families to the parent family.
     * <p>
     * Clears existing links before adding new ones. Validates that the family does not link to itself.
     *
     * @param family           The parent family.
     * @param requestedSubCodes The list of sub-family codes to link.
     * @param subFamilyMap      The map of available sub-families.
     * @throws IllegalArgumentException if a sub-family is not found or if a self-reference is detected.
     */
    private static void linkSubFamilies(ProductFamily family, List<String> requestedSubCodes, Map<String, ProductFamily> subFamilyMap) {
        // Handle Sub-Family Linking
        family.productFamilies.clear();
        for (String subCode : requestedSubCodes) {
            ProductFamily sub = subFamilyMap.get(subCode);
            if (sub != null) {
                if (sub.code.equals(family.code)) {
                    throw new IllegalArgumentException("Family '" + subCode + "' cannot contain itself.");
                } else {
                    family.productFamilies.add(sub);
                }
            } else {
                throw new IllegalArgumentException("SubFamily code '" + subCode + "' not found.");
            }
        }
    }

    /**
     * Prepares the ProductFamily entity with data from the CSV line.
     * <p>
     * Updates description and flags. Clears and re-links products based on the provided EANs.
     *
     * @param data       The parsed CSV line data.
     * @param family     The ProductFamily entity to update.
     * @param requestedEans The list of product EANs to link.
     * @param productMap  The map of available products.
     * @throws IllegalArgumentException if a referenced product is not found.
     */
    private void prepareProductFamily(LineData data, ProductFamily family, List<String> requestedEans, Map<String, Product> productMap) {
        // Update Fields
        family.description = safeGet(data.parts, 1);
        family.flags = safeGet(data.parts, 2);
        // Handle Product Linking
        family.products.clear();
        for (String ean : requestedEans) {
            Product p = productMap.get(ean);
            if (p != null) {
                family.products.add(p);
            } else {
                // Throw exception to trigger rollback and fallback
                throw new IllegalArgumentException("Product EAN '" + ean + "' not found.");
            }
        }
    }

    /**
     * Retrieves the map of sub-families.
     * <p>
     * If the map is missing from the context (fallback mode), it performs a database lookup for the requested codes.
     *
     * @param entityMap         The context map.
     * @param requestedSubCodes The list of sub-family codes required for processing the current line.
     * @return A map of sub-family code to {@link ProductFamily}.
     */
    private static Map<String, ProductFamily> retrieveSubProductFamilies(Map<String, Object> entityMap, List<String> requestedSubCodes) {
        @SuppressWarnings("unchecked")
        Map<String, ProductFamily> subFamilyMap = (Map<String, ProductFamily>) entityMap.get(CTX_SUB_FAMILIES);
        if (subFamilyMap == null && !requestedSubCodes.isEmpty()) {
            subFamilyMap = new HashMap<>();
            List<ProductFamily> subFamilies = ProductFamily.list("code IN ?1", requestedSubCodes);
            for (ProductFamily sf : subFamilies) subFamilyMap.put(sf.code, sf);
        }
        return subFamilyMap;
    }

    /**
     * Retrieves the map of products.
     * <p>
     * If the map is missing from the context (fallback mode), it performs a database lookup for the requested EANs.
     *
     * @param entityMap      The context map.
     * @param requestedEans The list of product EANs required for processing the current line.
     * @return A map of EAN to {@link Product}.
     */
    private static Map<String, Product> retrieveProducts(Map<String, Object> entityMap, List<String> requestedEans) {
        @SuppressWarnings("unchecked")
        Map<String, Product> productMap = (Map<String, Product>) entityMap.get(CTX_PRODUCTS);
        if (productMap == null && !requestedEans.isEmpty()) {
            productMap = new HashMap<>();
            List<Product> products = Product.list("ean IN ?1", requestedEans);
            for (Product p : products) productMap.put(p.ean, p);
        }
        return productMap;
    }

    /**
     * Implements the specific logic to find a fresh ProductFamily from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The ProductFamily entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return ProductFamily.find("code", data.code).firstResult();
    }

    /**
     * Computes checksum for incoming CSV data.
     * <p>
     * Replicates {@link ProductFamily#getChecksum()} logic.
     * <p>
     * Note: Children (products/subfamilies) are excluded from checksum in the entity.
     *
     * @param data The parsed CSV line data.
     * @return The integer hash of incoming data.
     */
    private int computeIncomingChecksum(LineData data) {
        return Objects.hash(
                data.code,
                safeGet(data.parts, 1), // description
                safeGet(data.parts, 2)  // flags
        );
    }
}