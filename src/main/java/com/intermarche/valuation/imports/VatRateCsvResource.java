package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.VatRate;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST Endpoint for bulk importing or updating VAT regimes from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link VatRate}
 * entities. The regime NUMBER is the natural key and the upsert key: a rate that changes
 * legally is a new value on the same number, never a new row, and the price rows that name
 * that number are untouched. It leverages the base class for the staged transaction management
 * (1000 -> 100 -> 10 -> 1) and the per-line checksum comparison that makes re-importing the
 * same file a no-op.
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared feed are ignored):
 * NUMBER (key), RATE, LABEL — LABEL optional.
 */
@Path("/vat-rates/import")
@ApplicationScoped
@RunOnVirtualThread
public class VatRateCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(VatRateCsvResource.class);

    /** Header name of the natural key: the regime number. */
    static final String COL_NUMBER = "NUMBER";
    /** Header name of the rate, as a fraction (0.0550). */
    static final String COL_RATE = "RATE";
    /** Header name of the human label (optional column). */
    static final String COL_LABEL = "LABEL";

    /** The columns this importer cannot work without (LABEL stays optional). */
    static final List<String> REQUIRED_COLUMNS = List.of(COL_RATE);

    /**
     * Imports or updates VAT regimes from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importVatRates(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_NUMBER, REQUIRED_COLUMNS);
    }

    /**
     * Implements the chunk processing logic for VAT regimes.
     * <p>
     * <b>Phase 1 (Specific):</b> Bulk fetches existing regimes whose number is in the chunk.
     * <p>
     * <b>Phase 2 (Generic):</b> Delegates to {@link ImporterCsvResource#processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique regime numbers (as strings) in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A map of number string to VatRate entity for the generic staged fallback.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        List<Integer> numbers = new ArrayList<>();
        for (String code : targetCodes) {
            Integer number = parseNumber(code);
            if (number != null) {
                numbers.add(number);
            }
        }
        Map<String, Object> regimeMap = new HashMap<>();
        if (!numbers.isEmpty()) {
            List<VatRate> existing = VatRate.list("number IN ?1", numbers);
            for (VatRate regime : existing) {
                regimeMap.put(String.valueOf(regime.number), regime);
            }
        }
        return regimeMap;
    }

    /**
     * Implements the specific logic for creating or updating a VAT regime.
     * <p>
     * This method is called by the generic staging algorithm for each line. A regime absent
     * from the map is created; a present one is updated only when its checksum changed — so a
     * re-import of an unchanged file counts zero updates.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The map of existing regimes (Key: number string, Value: VatRate).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        VatRate regime = (VatRate) entityMap.get(data.code);
        if (regime == null) {
            regime = new VatRate();
            feedVatRate(data, regime);
            counters[0]++;
            Panache.getEntityManager().persist(regime);
        } else {
            regime = VatRate.findById(regime.id);
            int incomingChecksum = computeIncomingChecksum(data);
            if (regime.checksum != incomingChecksum) {
                feedVatRate(data, regime);
                counters[1]++;
            }
        }
    }

    /**
     * Implements the specific logic to find a fresh VAT regime from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The VatRate entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return VatRate.findByNumber(parseNumber(data.code));
    }

    /**
     * Populates a VAT regime with data from the parsed CSV line.
     *
     * @param data   The parsed CSV line data.
     * @param regime The VatRate entity to populate.
     * @throws IllegalArgumentException when the number or the rate cannot be parsed.
     */
    private void feedVatRate(LineData data, VatRate regime) {
        Integer number = parseNumber(data.code);
        if (number == null) {
            throw new IllegalArgumentException("Invalid VAT number: " + data.code);
        }
        BigDecimal rate = safeParseBigDecimal(data, COL_RATE);
        if (rate == null) {
            throw new IllegalArgumentException("Invalid VAT rate for number " + number);
        }
        regime.number = number;
        regime.rate = rate;
        regime.label = safeGet(data, COL_LABEL);
    }

    /**
     * Computes the checksum for the incoming CSV data.
     * <p>
     * Replicates {@link VatRate#getChecksum()} logic.
     *
     * @param data The parsed CSV line data.
     * @return The integer hash of the incoming data.
     */
    private int computeIncomingChecksum(LineData data) {
        return Objects.hash(
                parseNumber(data.code),
                safeParseBigDecimal(data, COL_RATE),
                safeGet(data, COL_LABEL)
        );
    }

    /**
     * Parses a regime number from its string form.
     *
     * @param code The number as a string, possibly null.
     * @return The number, or null when it is absent or not an integer.
     */
    private Integer parseNumber(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
