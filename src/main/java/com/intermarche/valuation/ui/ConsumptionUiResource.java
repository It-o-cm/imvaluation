package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ConsumptionRecord;
import com.intermarche.valuation.domain.Offer;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only BO screen of the generosity consumption, aggregated by offer (spec §3, GB-01-01-27/28).
 * <p>
 * It reads the single source of truth, {@link ConsumptionRecord}, over a period (with optional
 * store, offer and budget-group filters) and shows, per offer, the financed amount and the number
 * of tickets — the same figures the export produces for {@code groupBy=offer}. Two links carry the
 * current filters to the {@code /consumptions/export} endpoint as CSV and JSON. Open to MANAGER and
 * above; a VIEWER is refused. There is no server-side pagination in v1: the aggregates are small.
 */
@Path("/ui/consumptions")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class ConsumptionUiResource {

    private static final Logger LOGGER = Logger.getLogger(ConsumptionUiResource.class);

    /**
     * Base path of the screen, used to build the list-view links.
     */
    private static final String BASE_PATH = "/ui/consumptions";

    /**
     * Path of the export endpoint the download links point to.
     */
    private static final String EXPORT_PATH = "/consumptions/export";

    /**
     * A page size large enough to hold the whole aggregate on one page (spec §3: no pagination).
     */
    private static final int PAGE_SIZE = 10_000;

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the consumption screen.
         *
         * @param view    the view model carrying the aggregated rows and the filters.
         * @param csvUrl  the CSV download URL carrying the current filters.
         * @param jsonUrl the JSON download URL carrying the current filters.
         * @param hasRange whether a valid period was supplied (else the table is empty on purpose).
         * @return the template instance to render.
         */
        public static native TemplateInstance list(ListView<ConsumptionAggregateRow> view,
                                                   String csvUrl, String jsonUrl, boolean hasRange);
    }

    /**
     * Displays the consumption aggregated by offer for the current filters.
     *
     * @param from            the inclusive lower bound on the finalization instant (ISO date or date-time).
     * @param to              the inclusive upper bound on the finalization instant.
     * @param storeCode       a store code to filter on, may be null.
     * @param offerCode       an offer code to filter on, may be null.
     * @param budgetGroupCode a budget group code to filter on, may be null.
     * @return the rendered screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("from") String from,
                                 @QueryParam("to") String to,
                                 @QueryParam("storeCode") String storeCode,
                                 @QueryParam("offerCode") String offerCode,
                                 @QueryParam("budgetGroupCode") String budgetGroupCode) {
        LOGGER.debug("Entering method list");
        LocalDateTime lower = parseLowerBound(from);
        LocalDateTime upper = parseUpperBound(to);
        boolean hasRange = lower != null && upper != null;
        List<ConsumptionAggregateRow> rows = hasRange
                ? aggregateByOffer(lower, upper, trimToNull(storeCode), trimToNull(offerCode),
                        trimToNull(budgetGroupCode))
                : List.of();
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("from", from);
        filters.put("to", to);
        filters.put("storeCode", storeCode);
        filters.put("offerCode", offerCode);
        filters.put("budgetGroupCode", budgetGroupCode);
        ListView<ConsumptionAggregateRow> view = new ListView<>(rows, BASE_PATH, filters,
                "offerCode", false, 1, 1, rows.size(), PAGE_SIZE, "offer",
                null, true, false);
        return Templates.list(view, downloadUrl(filters, "csv"), downloadUrl(filters, "json"), hasRange);
    }

    /**
     * Aggregates the records of the period by offer, netting cancellations and counting the distinct
     * tickets — the same figures as the export's {@code groupBy=offer} (spec §3, §6.6).
     *
     * @param from            the inclusive lower bound.
     * @param to              the inclusive upper bound.
     * @param storeCode       a store code filter, or null.
     * @param offerCode       an offer code filter, or null.
     * @param budgetGroupCode a budget group filter, or null.
     * @return the aggregated rows, ordered by offer code.
     */
    private List<ConsumptionAggregateRow> aggregateByOffer(LocalDateTime from, LocalDateTime to,
                                                           String storeCode, String offerCode,
                                                           String budgetGroupCode) {
        List<String> groupOffers = resolveBudgetGroupOffers(budgetGroupCode);
        List<ConsumptionRecord> records = ConsumptionRecord.listForExport(
                from, to, storeCode, offerCode, groupOffers);
        Map<String, ConsumptionAggregateRow> byOffer = new LinkedHashMap<>();
        Map<String, Set<String>> ticketsByOffer = new HashMap<>();
        for (ConsumptionRecord record : records) {
            int sign = ConsumptionRecord.DIRECTION_CANCELLATION.equals(record.direction) ? -1 : 1;
            ConsumptionAggregateRow row = byOffer.computeIfAbsent(record.offerCode, code -> {
                ConsumptionAggregateRow created = new ConsumptionAggregateRow();
                created.offerCode = code;
                created.amount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                return created;
            });
            BigDecimal signed = (record.amount == null ? BigDecimal.ZERO : record.amount)
                    .multiply(BigDecimal.valueOf(sign)).setScale(2, RoundingMode.HALF_UP);
            row.amount = row.amount.add(signed);
            ticketsByOffer.computeIfAbsent(record.offerCode, k -> new HashSet<>())
                    .add(record.storeCode + " " + record.ticketId);
        }
        List<String> codes = new ArrayList<>(byOffer.keySet());
        Map<String, Offer> offersByCode = new HashMap<>();
        if (!codes.isEmpty()) {
            for (Offer offer : Offer.<Offer>list("code in ?1", codes)) {
                offersByCode.put(offer.code, offer);
            }
        }
        List<ConsumptionAggregateRow> rows = new ArrayList<>(byOffer.values());
        for (ConsumptionAggregateRow row : rows) {
            Offer offer = offersByCode.get(row.offerCode);
            row.offerType = offer == null ? "" : (offer.type == null ? "" : offer.type);
            row.budgetGroupCode = offer == null || offer.getBudgetGroupCode() == null
                    ? "" : offer.getBudgetGroupCode();
            row.ticketCount = ticketsByOffer.getOrDefault(row.offerCode, Set.of()).size();
        }
        rows.sort((a, b) -> a.offerCode.compareTo(b.offerCode));
        return rows;
    }

    /**
     * Resolves the member offer codes of a budget group, or null when no group filter is set.
     *
     * @param budgetGroupCode the budget group code, may be null.
     * @return the member offer codes (possibly empty), or null when no filter applies.
     */
    private List<String> resolveBudgetGroupOffers(String budgetGroupCode) {
        if (budgetGroupCode == null) {
            return null;
        }
        List<Offer> offers = Offer.list("budgetGroup.code = ?1", budgetGroupCode);
        List<String> codes = new ArrayList<>();
        for (Offer offer : offers) {
            codes.add(offer.code);
        }
        return codes;
    }

    /**
     * Builds the export download URL carrying the current non-blank filters and the format.
     *
     * @param filters the current filters.
     * @param format  {@code csv} or {@code json}.
     * @return the encoded URL.
     */
    private String downloadUrl(Map<String, String> filters, String format) {
        UriBuilder builder = UriBuilder.fromPath(EXPORT_PATH);
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String value = trimToNull(entry.getValue());
            if (value != null) {
                builder.queryParam(entry.getKey(), value);
            }
        }
        builder.queryParam("format", format);
        return builder.build().toString();
    }

    /**
     * Parses the lower bound: an ISO local date-time, or an ISO date taken at the start of the day.
     *
     * @param raw the raw bound, may be null.
     * @return the parsed instant, or null when absent or malformed.
     */
    private LocalDateTime parseLowerBound(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /**
     * Parses the upper bound: an ISO local date-time, or an ISO date taken at the end of the day.
     *
     * @param raw the raw bound, may be null.
     * @return the parsed instant, or null when absent or malformed.
     */
    private LocalDateTime parseUpperBound(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(value).atTime(23, 59, 59, 999_999_999);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /**
     * Trims a string to null when it is null or blank.
     *
     * @param value the value, may be null.
     * @return the trimmed value, or null when null or blank.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * One aggregated row of the consumption screen: an offer, its resolved type and budget group,
     * the financed amount and the ticket count. Fields are public for Qute resolution.
     */
    public static class ConsumptionAggregateRow {

        /**
         * The offer code.
         */
        public String offerCode;

        /**
         * The offer type resolved at read time, empty when the offer was deleted.
         */
        public String offerType = "";

        /**
         * The budget group code resolved at read time, empty when none or the offer was deleted.
         */
        public String budgetGroupCode = "";

        /**
         * The financed amount over the period, netted of cancellations, scale 2.
         */
        public BigDecimal amount = BigDecimal.ZERO;

        /**
         * The number of distinct tickets contributing to this offer.
         */
        public int ticketCount;

        /**
         * Default constructor.
         */
        public ConsumptionAggregateRow() {
        }
    }
}
