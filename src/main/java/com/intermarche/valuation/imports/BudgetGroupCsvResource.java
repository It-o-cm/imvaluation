package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.BudgetGroup;
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
 * REST endpoint for bulk importing or updating budget groups from a CSV stream (spec §2).
 * <p>
 * This class extends {@link ImporterCsvResource} to handle {@link BudgetGroup} entities and
 * leverages the base class for the staged transaction management (1000 -&gt; 100 -&gt; 10 -&gt; 1)
 * and the checksum no-op on a byte-identical re-import.
 * <p>
 * Budget groups are delivered BEFORE offers (dependency order): the offer feed may reference a
 * group by its {@code BUDGET_GROUP_CODE}, and an unknown code rejects the offer line.
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared feed are ignored):
 * CODE (key), VALID_FROM, VALID_TO (ISO local date-time; blank leaves the bound open),
 * MAX_AMOUNT (euros, scale 2; blank means no amount ceiling), MAX_APPLICATIONS (integer; blank
 * means no application ceiling), PER_STORE (true/false; blank defaults to false).
 */
@Path("/budget-groups/import")
@ApplicationScoped
@RunOnVirtualThread
public class BudgetGroupCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(BudgetGroupCsvResource.class);

    /** Header name of the natural key: the budget group code. */
    static final String COL_CODE = "CODE";
    /** Header name of the optional period start (inclusive). */
    static final String COL_VALID_FROM = "VALID_FROM";
    /** Header name of the optional period end (exclusive). */
    static final String COL_VALID_TO = "VALID_TO";
    /** Header name of the optional amount envelope in euros. */
    static final String COL_MAX_AMOUNT = "MAX_AMOUNT";
    /** Header name of the optional ticket envelope. */
    static final String COL_MAX_APPLICATIONS = "MAX_APPLICATIONS";
    /** Header name of the per-store flag; blank defaults to false. */
    static final String COL_PER_STORE = "PER_STORE";

    /** The columns this importer expects in the header (values may be blank). */
    static final List<String> REQUIRED_COLUMNS = List.of(
            COL_VALID_FROM, COL_VALID_TO, COL_MAX_AMOUNT, COL_MAX_APPLICATIONS, COL_PER_STORE);

    /**
     * Imports or updates budget groups from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importBudgetGroups(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches the existing budget groups of the chunk into a code-keyed map.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique group codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A map of group code to BudgetGroup entity.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        List<BudgetGroup> existing = BudgetGroup.list("code IN ?1", targetCodes);
        Map<String, Object> groupMap = new HashMap<>();
        for (BudgetGroup g : existing) {
            groupMap.put(g.code, g);
        }
        return groupMap;
    }

    /**
     * Creates or updates one budget group, with the checksum no-op on a byte-identical row.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The map of existing groups (Key: Code, Value: BudgetGroup).
     * @param counters  An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        BudgetGroup group = (BudgetGroup) entityMap.get(data.code);
        if (group == null) {
            group = new BudgetGroup();
            group.code = data.code;
            feedGroup(data, group);
            counters[0]++;
            Panache.getEntityManager().persist(group);
        } else {
            group = BudgetGroup.findById(group.id);
            int incomingChecksum = computeIncomingChecksum(data);
            if (group.changedFrom(incomingChecksum)) {
                feedGroup(data, group);
                counters[1]++;
            }
        }
    }

    /**
     * Finds a fresh budget group for the 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The BudgetGroup entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return BudgetGroup.find("code", data.code).firstResult();
    }

    /**
     * Populates a budget group from the parsed CSV line.
     *
     * @param data  The parsed CSV line data.
     * @param group The BudgetGroup entity to populate.
     */
    private void feedGroup(LineData data, BudgetGroup group) {
        group.validFrom = safeParseDateTime(data, COL_VALID_FROM);
        group.validTo = safeParseDateTime(data, COL_VALID_TO);
        group.maxAmount = safeParseBigDecimal(data, COL_MAX_AMOUNT);
        group.maxApplications = safeParseInt(data, COL_MAX_APPLICATIONS);
        group.perStore = safeParseBoolean(data, COL_PER_STORE);
    }

    /**
     * Computes the checksum for the incoming CSV data, replicating {@link BudgetGroup#getChecksum()}.
     *
     * @param data The parsed CSV line data.
     * @return The integer hash of the incoming data.
     */
    private int computeIncomingChecksum(LineData data) {
        return Objects.hash(
                data.code,
                safeParseDateTime(data, COL_VALID_FROM),
                safeParseDateTime(data, COL_VALID_TO),
                safeParseBigDecimal(data, COL_MAX_AMOUNT),
                safeParseInt(data, COL_MAX_APPLICATIONS),
                safeParseBoolean(data, COL_PER_STORE));
    }
}
