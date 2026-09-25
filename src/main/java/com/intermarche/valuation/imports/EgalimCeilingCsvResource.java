package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.EgalimCeiling;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST endpoint bulk importing the administered EGAlim ceilings from a CSV
 * stream: the {@code EGALIM_REGIMES} feed the store node relays here verbatim,
 * under the very header its own importer writes.
 *
 * <p>Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): CODE (key), LABEL, CAP_RATE.
 *
 * <p>The CODE is the natural key and the upsert key: a legal ceiling change is
 * a new value on the same code, and no article row is rewritten — an article
 * carries its classification, never its number of percent.
 *
 * <p>The ceiling is read as a FRACTION — {@code 0.3400} for thirty-four
 * percent — like every rate of the suite; a file stating {@code 34} would
 * declare a ceiling of three thousand four hundred percent, which is no
 * ceiling at all, so a value outside [0, 1] is refused line by line.
 *
 * <p>An EMPTY ceiling is not zero: it is the ABSENCE of a ceiling, which is
 * how the exempt category is declared. CAP_RATE is therefore NOT a required
 * column — demanding it would make the one regime that has no ceiling
 * impossible to state.
 *
 * <p>Import ORDER: this feed is delivered BEFORE the articles, for the same
 * reason the VAT regimes go in before the prices — an article line naming a
 * regime needs that regime's ceiling to exist for the classification to cap
 * anything.
 */
@Path("/egalim-regimes/import")
@ApplicationScoped
@RunOnVirtualThread
public class EgalimCeilingCsvResource extends ImporterCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EgalimCeilingCsvResource.class);

    /** Header name of the natural key: the regime code. */
    static final String COL_CODE = "CODE";
    /** Header name of the human-readable label (optional column). */
    static final String COL_LABEL = "LABEL";
    /** Header name of the ceiling, as a fraction; an empty cell means uncapped. */
    static final String COL_CAP_RATE = "CAP_RATE";

    /**
     * The columns this importer cannot work without.
     *
     * <p>Empty on purpose: the key column is checked by the base class, and
     * the ceiling is optional because the exempt regime states none.
     */
    static final List<String> REQUIRED_COLUMNS = List.of();

    /**
     * Imports or updates the administered EGAlim ceilings from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importEgalimCeilings(InputStream inputStream) {
        LOGGER.info("Entering method importEgalimCeilings");
        Response response = this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
        LOGGER.info("Exiting method importEgalimCeilings");
        return response;
    }

    /**
     * Bulk-fetches the ceilings named by the current chunk.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique regime codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A map of regime code (as stored) to EgalimCeiling entity.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines,
                                                           Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        Map<String, Object> ceilingMap = new HashMap<>();
        if (parsedLines.isEmpty()) {
            return ceilingMap;
        }
        List<String> codes = new ArrayList<>();
        for (String code : targetCodes) {
            String normalized = normalize(code);
            if (normalized != null) {
                codes.add(normalized);
            }
        }
        if (!codes.isEmpty()) {
            List<EgalimCeiling> existing = EgalimCeiling.list("regimeCode IN ?1", codes);
            for (EgalimCeiling ceiling : existing) {
                ceilingMap.put(ceiling.regimeCode, ceiling);
            }
        }
        return ceilingMap;
    }

    /**
     * Creates or updates one ceiling from a parsed CSV line.
     *
     * <p>A code absent from the map is created; a present one is updated only
     * when its checksum changed — so re-importing an unchanged file counts
     * zero updates.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The map of existing ceilings (Key: regime code, Value: EgalimCeiling).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String code = normalize(data.code);
        if (code == null) {
            throw new IllegalArgumentException("EGAlim regime code is mandatory.");
        }
        BigDecimal cap = readCap(data, code);
        EgalimCeiling ceiling = (EgalimCeiling) entityMap.get(code);
        if (ceiling == null) {
            ceiling = new EgalimCeiling();
            feedCeiling(data, ceiling, code, cap);
            counters[0]++;
            Panache.getEntityManager().persist(ceiling);
        } else {
            ceiling = EgalimCeiling.findById(ceiling.id);
            if (ceiling.checksum != computeIncomingChecksum(data, code, cap)) {
                feedCeiling(data, ceiling, code, cap);
                counters[1]++;
            }
        }
    }

    /**
     * Implements the specific logic to find a fresh ceiling from the database.
     *
     * <p>Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The EgalimCeiling entity, or null when no row carries that code.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return EgalimCeiling.findByCode(normalize(data.code));
    }

    /**
     * Reads and validates the ceiling of one line.
     *
     * @param data The parsed CSV line data.
     * @param code The normalized regime code, for the error message.
     * @return The ceiling as a fraction, or null when the cell is empty.
     * @throws IllegalArgumentException when the value is not a fraction between 0 and 1.
     */
    private BigDecimal readCap(LineData data, String code) {
        BigDecimal cap = safeParseBigDecimal(data, COL_CAP_RATE);
        if (cap != null && (cap.signum() < 0 || cap.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("EGAlim cap '" + safeGet(data, COL_CAP_RATE)
                    + "' for regime " + code + " is not a fraction between 0 and 1.");
        }
        return cap;
    }

    /**
     * Populates a ceiling with the values of the parsed CSV line.
     *
     * @param data    The parsed CSV line data.
     * @param ceiling The entity to populate.
     * @param code    The normalized regime code.
     * @param cap     The ceiling already read and validated, or null for uncapped.
     */
    private void feedCeiling(LineData data, EgalimCeiling ceiling, String code, BigDecimal cap) {
        ceiling.regimeCode = code;
        ceiling.label = safeGet(data, COL_LABEL);
        ceiling.capRate = cap;
    }

    /**
     * Computes the checksum of the incoming CSV data.
     *
     * <p>Replicates {@link EgalimCeiling#getChecksum()} logic.
     *
     * @param data The parsed CSV line data.
     * @param code The normalized regime code.
     * @param cap  The ceiling already read, or null.
     * @return The integer hash of the incoming data.
     */
    private int computeIncomingChecksum(LineData data, String code, BigDecimal cap) {
        return Objects.hash(code, safeGet(data, COL_LABEL), cap);
    }

    /**
     * Normalizes a regime code the way the rows are stored.
     *
     * <p>Upper-cased, like the administration screen of the store node: a
     * referential answering differently to {@code food} and {@code FOOD} holds
     * two categories where the law has one, and the article feed is not always
     * written by the same hand as the ceiling feed.
     *
     * @param code The code as written in the file, possibly null.
     * @return The trimmed upper-cased code, or null when the cell is empty.
     */
    private static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
