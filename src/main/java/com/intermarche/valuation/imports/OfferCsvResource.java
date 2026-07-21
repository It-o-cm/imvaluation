package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
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
 * REST Endpoint for bulk importing or updating Offers from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to provide specific logic for {@link Offer} entities.
 * It handles CSV parsing, bulk fetching of related entities (Stores, StoreGroups),
 * and leverages the parent class for the staged transaction management (1000 -> 100 -> 10 -> 1).
 * <p>
 * CSV Format (5 columns):
 * offer_code|offer_type|specification|store_code|store_group_code
 * <p>
 * Note: store_code and store_group_code can contain multiple values separated by commas (e.g., "0101,0102").
 */
@Path("/offers/import")
@ApplicationScoped
@RunOnVirtualThread
public class OfferCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(OfferCsvResource.class);

    // Keys used to store auxiliary maps (Stores, Groups) in the generic context map
    private static final String CTX_STORES = "__CTX_STORES__";
    private static final String CTX_GROUPS = "__CTX_GROUPS__";

    /**
     * Imports or updates offers from a CSV stream.
     * Delegates the stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importOffers(InputStream inputStream) {
        // 5 columns expected: code, type, spec, stores, groups
        return this.importCsvStream(inputStream, 5);
    }

    /**
     * Implements the chunk processing logic for Offers.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Performs bulk fetching of Offers, Stores, and StoreGroups based on the data in the current chunk.
     * Constructs a "Context Map" containing all three maps to be passed to the generic algorithm.
     * <p>
     * <b>Phase 2 (Generic):</b>
     * Delegates to {@link ImporterCsvResource#processWithStages}, passing the context map.
     * This triggers the 1000 -> 100 -> 10 -> 1 algorithm.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Offer codes in this chunk (Column 0).
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map containing all entities (Offers, Stores, Groups) needed for processing.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // Create the Context Map for the Generic Algorithm
        // We put the Offer map directly (keyed by code) for the generic find logic.
        // We put Store/Group maps under specific internal keys so processLineLogic can access them.
        Map<String, Object> contextMap = new HashMap<>();
        Map<String, Offer> offerMap = getOfferMap(targetCodes);
        contextMap.putAll(offerMap);
        Map<String, Store> storeMap = getStoreMap(parsedLines);
        contextMap.put(CTX_STORES, storeMap);
        Map<String, StoreGroup> groupMap = getStoreGroupMap(parsedLines);
        contextMap.put(CTX_GROUPS, groupMap);
        return contextMap;
    }

    /**
     * Retrieves a map of existing Offers based on a set of codes.
     *
     * @param targetCodes The set of offer codes to search for.
     * @return A map of offer code to Offer entity.
     */
    private static Map<String, Offer> getOfferMap(Set<String> targetCodes) {
        Map<String, Offer> offerMap = new HashMap<>();
        if (!targetCodes.isEmpty()) {
            List<Offer> existingOffers = Offer.list("code IN ?1", targetCodes);
            for (Offer o : existingOffers) {
                offerMap.put(o.code, o);
            }
        }
        return offerMap;
    }

    /**
     * Retrieves a map of Stores referenced in the provided lines.
     * <p>
     * Extracts store codes from column 3 and performs a bulk fetch.
     *
     * @param parsedLines The list of parsed data containing store codes.
     * @return A map of store code to Store entity.
     */
    private Map<String, Store> getStoreMap(List<LineData> parsedLines) {
        Set<String> storeCodesToFetch = new HashSet<>();
        for (LineData data : parsedLines) {
            storeCodesToFetch.addAll(parseCodes(data.parts[3]));
        }
        Map<String, Store> storeMap = new HashMap<>();
        if (!storeCodesToFetch.isEmpty()) {
            List<Store> stores = Store.list("code IN ?1", storeCodesToFetch);
            for (Store s : stores) {
                storeMap.put(s.code, s);
            }
        }
        return storeMap;
    }

    /**
     * Retrieves a map of StoreGroups referenced in the provided lines.
     * <p>
     * Extracts group codes from column 4 and performs a bulk fetch.
     *
     * @param parsedLines The list of parsed data containing group codes.
     * @return A map of group code to StoreGroup entity.
     */
    private Map<String, StoreGroup> getStoreGroupMap(List<LineData> parsedLines) {
        Set<String> groupCodesToFetch = new HashSet<>();
        for (LineData data : parsedLines) {
            groupCodesToFetch.addAll(parseCodes(data.parts[4]));
        }
        Map<String, StoreGroup> groupMap = new HashMap<>();
        if (!groupCodesToFetch.isEmpty()) {
            List<StoreGroup> groups = StoreGroup.list("code IN ?1", groupCodesToFetch);
            for (StoreGroup g : groups) {
                groupMap.put(g.code, g);
            }
        }
        return groupMap;
    }

    /**
     * Implements the specific logic for creating or updating an Offer entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Offer, Store, and Group maps from the context map.
     * If the auxiliary maps are missing (e.g., in 1-by-1 fallback mode), it performs individual fetches.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map containing Offers, Stores, and Groups.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // 1. Retrieve Offer
        Offer offer = (Offer) entityMap.get(data.code);
        // 2. Retrieve Auxiliary Maps (Stores/Groups)
        // Note: In 1-by-1 fallback, the parent class creates a minimal map with only the fresh Entity.
        // We must check if our specific keys exist. If not, we are in fallback mode and need manual fetches.
        // Handle Fallback Data Fetching (if maps are null)
        List<String> requestedStoreCodes = parseCodes(data.parts[3]);
        Map<String, Store> storeMap = retrieveStores(entityMap, requestedStoreCodes);
        List<String> requestedGroupCodes = parseCodes(data.parts[4]);
        Map<String, StoreGroup> groupMap = retrieveStoreGroups(entityMap, requestedGroupCodes);
        // 3. Business Logic (Create/Update/Link)
        boolean isNew = (offer == null);
        if (isNew) {
            offer = new Offer();
            offer.code = data.code;
        }
        else {
            offer = Offer.findById(offer.id);
        }
        prepareOffer(data, offer, requestedStoreCodes, requestedGroupCodes, storeMap, groupMap);
        // Checksum Logic
        int incomingChecksum = computeIncomingChecksum(data, requestedStoreCodes, requestedGroupCodes);
        if (isNew) {
            counters[0]++;
            Panache.getEntityManager().persist(offer);
        } else {
            if (offer.checksum != incomingChecksum) {
                counters[1]++;
            }
        }
    }

    /**
     * Prepares the Offer entity by setting fields and linking Stores and Sub-Groups.
     * <p>
     * Validates that at least one target (Store or StoreGroup) is defined.
     * Throws an exception if a referenced entity is not found, triggering transaction rollback.
     *
     * @param data                The parsed CSV line data.
     * @param offer               The Offer entity to populate.
     * @param requestedStoreCodes  The list of store codes from the CSV.
     * @param requestedGroupCodes  The list of group codes from the CSV.
     * @param storeMap            The map of available stores.
     * @param groupMap            The map of available groups.
     */
    private void prepareOffer(LineData data, Offer offer, List<String> requestedStoreCodes, List<String> requestedGroupCodes,
                              Map<String, Store> storeMap, Map<String, StoreGroup> groupMap) {
        // Update Fields
        offer.type = safeGet(data.parts, 1);
        offer.specification = safeGet(data.parts, 2);
        // Handle Target Linking
        // Validate: At least one target
        if (requestedStoreCodes.isEmpty() && requestedGroupCodes.isEmpty()) {
            // Throw exception to trigger rollback
            throw new IllegalArgumentException("Line " + data.lineNumber + ": Must define at least one store_code or store_group_code.");
        }
        offer.stores.clear();
        offer.storeGroups.clear();
        for (String sCode : requestedStoreCodes) {
            Store s = storeMap.get(sCode);
            if (s != null) {
                s = Store.findById(s.id);
                offer.stores.add(s);
            } else {
                // If we are in fallback mode (maps were fetched just above), and a store is missing, it's a hard error.
                throw new IllegalArgumentException("Store code '" + sCode + "' not found.");
            }
        }
        for (String gCode : requestedGroupCodes) {
            StoreGroup g = groupMap.get(gCode);
            if (g != null) {
                g = StoreGroup.findById(g.id);
                offer.storeGroups.add(g);
            } else {
                throw new IllegalArgumentException("StoreGroup code '" + gCode + "' not found.");
            }
        }
    }

    /**
     * Retrieves the map of StoreGroups from the entity map, performing a fetch if missing.
     * <p>
     * If the map is missing from the context (indicating 1-by-1 fallback mode),
     * this method performs a database query to fetch the necessary groups.
     *
     * @param entityMap           The context map.
     * @param requestedGroupCodes The list of group codes needed.
     * @return The map of StoreGroups.
     */
    private Map<String, StoreGroup> retrieveStoreGroups(Map<String, Object> entityMap, List<String> requestedGroupCodes) {
        @SuppressWarnings("unchecked")
        Map<String, StoreGroup> groupMap = (Map<String, StoreGroup>) entityMap.get(CTX_GROUPS);
        if (groupMap == null && !requestedGroupCodes.isEmpty()) {
            groupMap = new HashMap<>();
            List<StoreGroup> groups = StoreGroup.list("code IN ?1", requestedGroupCodes);
            for (StoreGroup g : groups) {
                groupMap.put(g.code, g);
            }
        }
        return groupMap;
    }

    /**
     * Retrieves the map of Stores from the entity map, performing a fetch if missing.
     * <p>
     * If the map is missing from the context (indicating 1-by-1 fallback mode),
     * this method performs a database query to fetch the necessary stores.
     *
     * @param entityMap          The context map.
     * @param requestedStoreCodes The list of store codes needed.
     * @return The map of Stores.
     */
    private Map<String, Store> retrieveStores(Map<String, Object> entityMap, List<String> requestedStoreCodes) {
        @SuppressWarnings("unchecked")
        Map<String, Store> storeMap = (Map<String, Store>) entityMap.get(CTX_STORES);
        if (storeMap == null && !requestedStoreCodes.isEmpty()) {
            storeMap = new HashMap<>();
            List<Store> stores = Store.list("code IN ?1", requestedStoreCodes);
            for (Store s : stores) storeMap.put(s.code, s);
        }
        return storeMap;
    }

    /**
     * Implements the specific logic to find a fresh Offer from the database.
     * <p>
     * Used by the generic 1-by-1 fallback to ensure data freshness.
     *
     * @param data The parsed CSV line data.
     * @return The Offer entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Offer.find("code", data.code).firstResult();
    }

    /**
     * Computes a checksum for incoming CSV data.
     * <p>
     * Replicates {@link Offer#getChecksum()} logic using the lists of codes provided.
     *
     * @param data       The parsed CSV line data.
     * @param storeCodeList The list of store codes for this line.
     * @param groupCodeList The list of group codes for this line.
     * @return The integer hash of incoming data.
     */
    private int computeIncomingChecksum(LineData data, List<String> storeCodeList, List<String> groupCodeList) {
        String type = safeGet(data.parts, 1);
        String spec = safeGet(data.parts, 2);
        // Note: storeCodes and groupCodes are already sorted by parseCodes()
        String storeCodes = storeCodeList.stream()
                .sorted()
                .collect(Collectors.joining("|"));
        String groupCodes = groupCodeList.stream()
                .sorted()
                .collect(Collectors.joining("|"));
        return Objects.hash(data.code, type, spec,
                storeCodes,
                groupCodes);
    }
}