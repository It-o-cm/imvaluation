package com.intermarche.valuation.imports;

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

/**
 * REST Endpoint for defining StoreGroup hierarchies and content using list-based CSV columns.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link StoreGroup} entities.
 * <p>
 * <b>Simplified Logic (Sorted CSV):</b>
 * This version assumes the CSV is sorted (Parents defined before Children).
 * It processes line by line. For each line, it ensures the StoreGroup exists (Create or Update),
 * then links the associated Stores and Sub-Groups.
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the
 * shared feed are ignored): CODE (key), NAME, STORE_CODES,
 * STORE_GROUP_CODES.
 * <p>
 * Note: Lists use semicolon ';' as separator.
 */
@Path("/store-groups/import")
@ApplicationScoped
@RunOnVirtualThread
public class StoreGroupCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(StoreGroupCsvResource.class);
    private static final String LIST_SEPARATOR = ";";

    // Keys for Context Map
    static final String CTX_GROUPS = "__CTX_GROUPS__";
    static final String CTX_STORES = "__CTX_STORES__";
    static final String CTX_CHILD_GROUPS = "__CTX_CHILD_GROUPS__";

    /** Header name of the natural key: the group code. */
    static final String COL_CODE = "CODE";
    /** Header name of the group label. */
    static final String COL_NAME = "NAME";
    /** Header name of the semicolon-separated member store codes. */
    static final String COL_STORE_CODES = "STORE_CODES";
    /** Header name of the semicolon-separated child group codes. */
    static final String COL_STORE_GROUP_CODES = "STORE_GROUP_CODES";

    /** The columns this importer cannot work without. */
    static final List<String> REQUIRED_COLUMNS = List.of(
            COL_NAME, COL_STORE_CODES, COL_STORE_GROUP_CODES);

    /**
     * Imports or updates the hierarchy of StoreGroups from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importHierarchy(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
    }

    /**
     * Implements the chunk processing logic for StoreGroups.
     * <p>
     * Prepares the context maps by bulk fetching existing Groups, Stores, and Sub-Groups.
     * This populates the maps with data currently in the database.
     * Delegates the rest (Create/Update/Link) to the generic {@link #processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique group codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map containing the pre-fetched data for the chunk.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();

        // Step 1: Bulk Fetch Main Groups (The lines we are processing)
        Map<String, StoreGroup> groupMap = getStoreGroupsMap(targetCodes);

        // Step 2: Collect codes for Children (Stores & Sub-Groups) to perform Bulk Fetch
        Set<String> storeCodesToFetch = getCodesFromColumn(parsedLines, COL_STORE_CODES);
        Set<String> childGroupCodesToFetch = getCodesFromColumn(parsedLines, COL_STORE_GROUP_CODES);

        // Step 3: Bulk Fetch Children
        Map<String, Store> storeMap = getStoresMap(storeCodesToFetch);
        Map<String, StoreGroup> childGroupMap = getStoreGroupsMap(childGroupCodesToFetch);

        // Step 4: Assemble Context Map
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(CTX_GROUPS, groupMap);
        contextMap.put(CTX_STORES, storeMap);
        contextMap.put(CTX_CHILD_GROUPS, childGroupMap);

        return contextMap;
    }

    /**
     * Prepares the context for a SINGLE line (Fallback mode 1-by-1).
     * <p>
     * Since we are in fallback, we cannot rely on the bulk map.
     * We fetch the Main Group, the Stores, and the Child Groups specifically for this line.
     *
     * @param data The parsed line data.
     * @return A map containing the specific entities for this line.
     */
    @Override
    protected Map<String, Object> prepareContextForLine(LineData data) {
        // Step 1: Fetch Main Group (The entity for the current line)
        StoreGroup group = (StoreGroup) findEntityForLine(data);
        Map<String, StoreGroup> groupMap = new HashMap<>();
        if (group != null) {
            groupMap.put(data.code, group);
        }

        // Step 2: Fetch Stores
        String[] storeCodes = parseSemicolonCodes(safeGet(data, COL_STORE_CODES));
        Map<String, Store> storeMap = new HashMap<>();
        if (storeCodes.length > 0) {
            List<Store> stores = Store.list("code IN ?1", Arrays.asList(storeCodes));
            for (Store s : stores) {
                storeMap.put(s.code, s);
            }
        }

        // Step 3: Fetch Child Groups
        String[] childGroupCodes = parseSemicolonCodes(safeGet(data, COL_STORE_GROUP_CODES));
        Map<String, StoreGroup> childGroupMap = new HashMap<>();
        if (childGroupCodes.length > 0) {
            List<StoreGroup> groups = StoreGroup.list("code IN ?1", Arrays.asList(childGroupCodes));
            for (StoreGroup g : groups) {
                childGroupMap.put(g.code, g);
            }
        }

        // Step 4: Assemble
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(CTX_GROUPS, groupMap);
        contextMap.put(CTX_STORES, storeMap);
        contextMap.put(CTX_CHILD_GROUPS, childGroupMap);
        return contextMap;
    }

    /**
     * Implements the specific logic for creating or updating a StoreGroup entity and its links.
     * <p>
     * 1. Retrieve or Create the StoreGroup.
     * 2. Link Stores.
     * 3. Link Sub-Groups.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map containing Groups, Stores, and Sub-Groups.
     * @param counters  An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        @SuppressWarnings("unchecked")
        Map<String, Store> storeMap = (Map<String, Store>) entityMap.get(CTX_STORES);
        @SuppressWarnings("unchecked")
        Map<String, StoreGroup> childGroupMap = (Map<String, StoreGroup>) entityMap.get(CTX_CHILD_GROUPS);
        @SuppressWarnings("unchecked")
        Map<String, StoreGroup> groupMap = (Map<String, StoreGroup>) entityMap.get(CTX_GROUPS);

        String groupName = safeGet(data, COL_NAME);
        List<String> requestedStoreCodes = sortedCodes(safeGet(data, COL_STORE_CODES));
        List<String> requestedSubCodes = sortedCodes(safeGet(data, COL_STORE_GROUP_CODES));
        // A4 (report §3): the checksum includes the (sorted) member codes, so a re-import that only
        // changes membership is detected as a real update rather than a silent no-op.
        int incomingChecksum = computeChecksum(data.code, groupName, requestedStoreCodes, requestedSubCodes);

        StoreGroup group = groupMap.get(data.code);
        if (group == null) {
            group = StoreGroup.find("code", data.code).firstResult();
        }
        if (group == null) {
            // CREATE
            group = new StoreGroup();
            group.code = data.code;
            group.name = groupName;
            replaceStores(requestedStoreCodes, storeMap, group);
            replaceSubGroups(requestedSubCodes, childGroupMap, group);
            Panache.getEntityManager().persist(group);
            counters[0]++;
            groupMap.put(data.code, group);
        } else if (group.checksum != incomingChecksum) {
            // UPDATE — re-attach the (possibly detached) instance so the changes are flushed, then
            // REPLACE the members (A4): a member dropped from the feed is removed from the group.
            group = StoreGroup.findById(group.id);
            group.name = groupName;
            replaceStores(requestedStoreCodes, storeMap, group);
            replaceSubGroups(requestedSubCodes, childGroupMap, group);
            counters[1]++;
        }
    }

    /**
     * Splits a semicolon-separated code list into a sorted, de-duplicated, trimmed list.
     * <p>
     * Sorting makes the membership checksum order-independent, matching {@link StoreGroup#getChecksum()}.
     *
     * @param raw the raw semicolon-separated cell, may be null.
     * @return the sorted distinct non-empty codes; never null.
     */
    private static List<String> sortedCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    // --------------------------------------------------
    // Logic Helpers
    // --------------------------------------------------

    /**
     * Retrieves an existing StoreGroup from the map/DB or creates a new one.
     * <p>
     * This replaces the "Phase 1" logic. Since the CSV is assumed to be sorted,
     * checking the DB allows us to find a group created in a previous line of the same chunk.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @param counters  The counters array to update.
     * @return The managed StoreGroup entity.
     */
    /**
     * Replaces the group's stores with exactly the requested set (A4 replace semantics).
     * <p>
     * Clears the current stores and re-adds the requested ones, so a store dropped from the feed
     * leaves the group.
     *
     * @param requestedCodes The sorted requested store codes.
     * @param storeMap       The map of available stores.
     * @param group          The parent StoreGroup entity.
     * @throws IllegalArgumentException if a requested store code is not found.
     */
    void replaceStores(List<String> requestedCodes, Map<String, Store> storeMap, StoreGroup group) {
        group.stores.clear();
        for (String sCode : requestedCodes) {
            Store s = storeMap.get(sCode);
            if (s == null) {
                throw new IllegalArgumentException("Store '" + sCode + "' not found.");
            }
            group.stores.add(s);
        }
    }

    /**
     * Replaces the group's sub-groups with exactly the requested set (A4 replace semantics).
     *
     * @param requestedCodes The sorted requested sub-group codes.
     * @param childGroupMap  The map of available child groups.
     * @param group          The parent StoreGroup entity.
     * @throws IllegalArgumentException if a requested sub-group code is not found.
     */
    void replaceSubGroups(List<String> requestedCodes, Map<String, StoreGroup> childGroupMap, StoreGroup group) {
        group.storeGroups.clear();
        for (String gCode : requestedCodes) {
            StoreGroup child = childGroupMap.get(gCode);
            if (child == null) {
                throw new IllegalArgumentException("StoreGroup '" + gCode + "' not found. Check CSV order (Parent must be defined before Child).");
            }
            group.storeGroups.add(child);
        }
    }

    // --------------------------------------------------
    // Fetch Helpers (Bulk)
    // --------------------------------------------------

    /**
     * Retrieves a map of StoreGroups based on a set of codes.
     *
     * @param codes The set of codes to search for.
     * @return A map of code to StoreGroup.
     */
    Map<String, StoreGroup> getStoreGroupsMap(Set<String> codes) {
        Map<String, StoreGroup> map = new HashMap<>();
        if (!codes.isEmpty()) {
            List<StoreGroup> list = StoreGroup.list("code IN ?1", codes);
            for (StoreGroup g : list) map.put(g.code, g);
        }
        return map;
    }

    /**
     * Retrieves a map of Stores based on a set of codes.
     *
     * @param codes The set of codes to search for.
     * @return A map of code to Store.
     */
    Map<String, Store> getStoresMap(Set<String> codes) {
        Map<String, Store> map = new HashMap<>();
        if (!codes.isEmpty()) {
            List<Store> list = Store.list("code IN ?1", codes);
            for (Store s : list) map.put(s.code, s);
        }
        return map;
    }

    /**
     * Extracts and aggregates codes from a column resolved by name across all lines.
     *
     * @param parsedLines  The list of parsed lines.
     * @param column       The header name of the column to extract codes from.
     * @return A set of unique codes found.
     */
    Set<String> getCodesFromColumn(List<LineData> parsedLines, String column) {
        Set<String> codes = new HashSet<>();
        for (LineData data : parsedLines) {
            String[] parts = parseSemicolonCodes(safeGet(data, column));
            Collections.addAll(codes, parts);
        }
        return codes;
    }

    // --------------------------------------------------
    // Utilities
    // --------------------------------------------------

    /**
     * Implements the specific logic to find a fresh StoreGroup from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The StoreGroup entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return StoreGroup.find("code", data.code).firstResult();
    }

    /**
     * Helper method to parse semicolon-separated codes.
     *
     * @param raw The raw string from the CSV cell.
     * @return An array of trimmed codes.
     */
    String[] parseSemicolonCodes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new String[0];
        }
        return raw.split(LIST_SEPARATOR);
    }

    /**
     * Computes the checksum for the incoming CSV data (A4, report §3).
     * <p>
     * Mirrors {@link StoreGroup#getChecksum()} exactly — code, name and the sorted member codes —
     * so a membership change is detected and applied instead of being a silent no-op. The member
     * code lists must already be sorted (see {@link #sortedCodes(String)}).
     *
     * @param code           The group code.
     * @param name           The group name.
     * @param storeCodes     The sorted requested store codes.
     * @param subGroupCodes  The sorted requested sub-group codes.
     * @return The integer hash of the incoming data.
     */
    int computeChecksum(String code, String name, List<String> storeCodes, List<String> subGroupCodes) {
        return Objects.hash(code, name, storeCodes, subGroupCodes);
    }
}