package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Adresse;
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
import java.util.*;

/**
 * REST Endpoint for bulk importing or updating Stores from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link Store} entities.
 * It manages the embedded {@link Adresse} object and leverages the base class for the staged transaction management (1000 -> 100 -> 10 -> 1).
 * <p>
 * Expected CSV format (7+ columns, last 2 optional):
 * Code|Name|StreetLine1|StreetLine2|PostalCode|City|Country|Latitude|Longitude
 */
@Path("/stores/import")
@ApplicationScoped
@RunOnVirtualThread
public class StoreCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(StoreCsvResource.class);

    /**
     * Imports or updates stores from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importStores(InputStream inputStream) {
        // 7 mandatory columns expected (Code, Name, Address 1-5). Lat/Long are optional.
        return this.importCsvStream(inputStream, 7);
    }

    /**
     * Implements the chunk processing logic for Stores.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Bulk fetches existing Stores based on the target codes.
     * <p>
     * <b>Phase 2 (Generic):</b>
     * Delegates to {@link ImporterCsvResource#processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Store codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // 1. Bulk Fetch Existing Stores
        List<Store> existingStores = Store.list("code IN ?1", targetCodes);
        // Build the entity map (Code -> Store) for the generic algorithm
        Map<String, Object> storeMap = new HashMap<>();
        for (Store s : existingStores) {
            storeMap.put(s.code, s);
        }
        return storeMap;
    }

    /**
     * Implements the specific logic for creating or updating a Store entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Store from the provided map.
     * If the store is new, it initializes the embedded {@link Adresse}.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The map of existing stores (Key: Code, Value: Store).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // Retrieve Store
        Store store = (Store) entityMap.get(data.code);
        if (store == null) {
            // Create new
            store = new Store();
            store.code = data.code;
            feedStore(data, store);
            counters[0]++;
            Panache.getEntityManager().persist(store);
        } else {
            store = Store.findById(store.id);
            // Update existing
            int incomingChecksum = computeIncomingChecksum(data);
            if (store.checksum != incomingChecksum) {
                feedStore(data, store);
                counters[1]++;
            }
        }
    }

    /**
     * Implements the specific logic to find a fresh Store from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The Store entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Store.find("code", data.code).firstResult();
    }

    /**
     * Populates a Store entity (and its embedded Address) with data from the parsed CSV line.
     *
     * @param data  The parsed CSV line data.
     * @param store The Store entity to populate.
     */
    private void feedStore(LineData data, Store store) {
        String[] parts = data.parts;
        store.name = safeGet(parts, 1);
        // Ensure Address object exists
        if (store.address == null) {
            store.address = new Adresse();
        }
        store.address.streetLine1 = safeGet(parts, 2);
        store.address.streetLine2 = safeGet(parts, 3);
        store.address.postalCode = safeGet(parts, 4);
        store.address.city = safeGet(parts, 5);
        store.address.country = safeGet(parts, 6);
        store.address.latitude = safeParseDouble(parts, 7);
        store.address.longitude = safeParseDouble(parts, 8);
    }

    /**
     * Computes the checksum for the incoming CSV data.
     * <p>
     * Replicates {@link Store#getChecksum()} and {@link Adresse#getChecksum()} logic.
     *
     * @param data The parsed CSV line data.
     * @return The integer hash of the incoming data.
     */
    private int computeIncomingChecksum(LineData data) {
        String[] parts = data.parts;
        // 1. Calculate Address Hash
        int addressHash = Objects.hash(
                safeGet(parts, 2),             // streetLine1
                safeGet(parts, 3),             // streetLine2
                safeGet(parts, 4),             // postalCode
                safeGet(parts, 5),             // city
                safeGet(parts, 6),             // country
                safeParseDouble(parts, 7),     // latitude
                safeParseDouble(parts, 8)      // longitude
        );
        // 2. Calculate Store Hash
        int storeChecksum = Objects.hash(
                data.code,                    // code
                safeGet(parts, 1),     // name
                addressHash                   // address checksum
        );
        return storeChecksum;
    }

}