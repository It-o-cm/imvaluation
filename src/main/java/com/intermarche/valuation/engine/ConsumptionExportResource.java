package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ConsumptionRecord;
import com.intermarche.valuation.domain.Offer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
 * Financial export of the generosity consumption (spec §2): the single source is
 * {@link ConsumptionRecord} — the truth of what was cashed — never the traces nor the evaluations.
 * <p>
 * {@code GET /consumptions/export} serves either a flat pipe-delimited CSV or the same content as
 * JSON for third-party systems (the "API and/or file" of the RFP), for MANAGER and above. It bounds
 * on {@code finalizedAt} ({@code from}/{@code to}, mandatory) and narrows optionally by store, offer
 * and budget group. The detail lists one line per advantage — and per article when the per-article
 * breakdown is present, an empty EAN otherwise; cancellations come out as negative lines. The
 * aggregates sum the (signed) amounts and count the tickets per key, so a cancellation nets there.
 * <p>
 * {@code OFFER_TYPE} and {@code BUDGET_GROUP_CODE} are resolved at export time from the referential:
 * an offer deleted since the consumption comes out with those columns empty, the line still
 * present — accounting truth over referential currency.
 */
@Path("/consumptions")
@ApplicationScoped
@RunOnVirtualThread
public class ConsumptionExportResource {

    private static final Logger LOGGER = Logger.getLogger(ConsumptionExportResource.class);

    /**
     * The CSV media type, UTF-8, used for the flat file export.
     */
    private static final String CSV_MEDIA_TYPE = "text/csv; charset=UTF-8";

    /**
     * The detail CSV header (spec §2), one column per exported field.
     */
    private static final String DETAIL_HEADER =
            "TICKET_ID|STORE_CODE|FINALIZED_AT|DIRECTION|OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|EAN|AMOUNT|FUNDING";

    /**
     * Exports the consumption, in detail or aggregated, as CSV or JSON.
     *
     * @param fromRaw         the inclusive lower bound on the finalization instant (mandatory).
     * @param toRaw           the inclusive upper bound on the finalization instant (mandatory).
     * @param storeCode       a store code to filter on, may be null.
     * @param offerCode       an offer code to filter on, may be null.
     * @param budgetGroupCode a budget group code to filter on (its member offers), may be null.
     * @param format          {@code csv} (default) or {@code json}.
     * @param groupBy         {@code none} (default, detail), {@code offer}, {@code store}, {@code day}
     *                        or {@code funding}.
     * @return the export as CSV or JSON, or a 400 when the mandatory bounds are missing or malformed.
     */
    @GET
    @Path("/export")
    @RolesAllowed({AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
    @Produces({CSV_MEDIA_TYPE, MediaType.APPLICATION_JSON})
    @Transactional
    public Response export(@QueryParam("from") String fromRaw,
                           @QueryParam("to") String toRaw,
                           @QueryParam("storeCode") String storeCode,
                           @QueryParam("offerCode") String offerCode,
                           @QueryParam("budgetGroupCode") String budgetGroupCode,
                           @QueryParam("format") @DefaultValue("csv") String format,
                           @QueryParam("groupBy") @DefaultValue("none") String groupBy) {
        LOGGER.debug("Entering method export");
        LocalDateTime from = parseLowerBound(fromRaw);
        LocalDateTime to = parseUpperBound(toRaw);
        if (from == null || to == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"from and to are mandatory ISO date or date-time bounds.\"}")
                    .type(MediaType.APPLICATION_JSON).build();
        }
        boolean json = "json".equalsIgnoreCase(format);
        List<String> offerCodesFilter = resolveBudgetGroupOffers(budgetGroupCode);
        List<ConsumptionRecord> records = ConsumptionRecord.listForExport(
                from, to, trimToNull(storeCode), trimToNull(offerCode), offerCodesFilter);
        Map<String, Offer> offersByCode = resolveOffers(records);
        String key = normalizeGroupBy(groupBy);
        if ("none".equals(key)) {
            return json ? detailJson(records, offersByCode) : detailCsv(records, offersByCode);
        }
        return json ? aggregateJson(records, offersByCode, key) : aggregateCsv(records, offersByCode, key);
    }

    // --------------------------------------------------
    // Detail
    // --------------------------------------------------

    /**
     * Builds the detail CSV: one line per advantage, and per article when the breakdown is present.
     *
     * @param records      the exported records.
     * @param offersByCode the referential offers resolved by code.
     * @return the CSV response.
     */
    private Response detailCsv(List<ConsumptionRecord> records, Map<String, Offer> offersByCode) {
        StringBuilder csv = new StringBuilder(DETAIL_HEADER).append('\n');
        for (ConsumptionRecord record : records) {
            Offer offer = offersByCode.get(record.offerCode);
            String type = offer == null ? "" : nullToEmpty(offer.type);
            String bgCode = offer == null ? "" : nullToEmpty(offer.getBudgetGroupCode());
            int sign = ConsumptionRecord.DIRECTION_CANCELLATION.equals(record.direction) ? -1 : 1;
            for (DetailLine line : detailLinesOf(record, sign)) {
                csv.append(sanitize(record.ticketId)).append('|')
                        .append(sanitize(record.storeCode)).append('|')
                        .append(sanitize(record.finalizedAt == null ? "" : record.finalizedAt.toString())).append('|')
                        .append(sanitize(record.direction)).append('|')
                        .append(sanitize(record.offerCode)).append('|')
                        .append(sanitize(type)).append('|')
                        .append(sanitize(bgCode)).append('|')
                        .append(sanitize(line.ean)).append('|')
                        .append(plain(line.amount)).append('|')
                        .append(sanitize(nullToEmpty(record.funding))).append('\n');
            }
        }
        return csv(csv.toString(), "consumptions.csv");
    }

    /**
     * Builds the detail JSON: the same content as {@link #detailCsv} for third-party systems.
     *
     * @param records      the exported records.
     * @param offersByCode the referential offers resolved by code.
     * @return the JSON response.
     */
    private Response detailJson(List<ConsumptionRecord> records, Map<String, Offer> offersByCode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConsumptionRecord record : records) {
            Offer offer = offersByCode.get(record.offerCode);
            String type = offer == null ? "" : nullToEmpty(offer.type);
            String bgCode = offer == null ? "" : nullToEmpty(offer.getBudgetGroupCode());
            int sign = ConsumptionRecord.DIRECTION_CANCELLATION.equals(record.direction) ? -1 : 1;
            for (DetailLine line : detailLinesOf(record, sign)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticketId", record.ticketId);
                row.put("storeCode", record.storeCode);
                row.put("finalizedAt", record.finalizedAt == null ? null : record.finalizedAt.toString());
                row.put("direction", record.direction);
                row.put("offerCode", record.offerCode);
                row.put("offerType", type);
                row.put("budgetGroupCode", bgCode);
                row.put("ean", line.ean);
                row.put("amount", line.amount);
                row.put("funding", record.funding);
                rows.add(row);
            }
        }
        return Response.ok(rows).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Expands a record into its detail lines: one per per-article breakdown entry (signed), or a
     * single blank-EAN line carrying the whole amount when no breakdown is present.
     *
     * @param record the record.
     * @param sign   {@code +1} for a SALE, {@code -1} for a CANCELLATION.
     * @return the detail lines, never empty.
     */
    private List<DetailLine> detailLinesOf(ConsumptionRecord record, int sign) {
        List<DetailLine> lines = new ArrayList<>();
        if (record.lines == null || record.lines.isEmpty()) {
            lines.add(new DetailLine("", signed(record.amount, sign)));
            return lines;
        }
        for (ConsumptionRecord.ConsumptionLine line : record.lines) {
            lines.add(new DetailLine(nullToEmpty(line.ean), signed(line.amount, sign)));
        }
        return lines;
    }

    // --------------------------------------------------
    // Aggregates
    // --------------------------------------------------

    /**
     * Builds the aggregated CSV: summed (signed) amounts and ticket counts per key.
     *
     * @param records      the exported records.
     * @param offersByCode the referential offers resolved by code.
     * @param key          the grouping key: {@code offer}, {@code store}, {@code day} or {@code funding}.
     * @return the CSV response.
     */
    private Response aggregateCsv(List<ConsumptionRecord> records, Map<String, Offer> offersByCode, String key) {
        Map<String, Aggregate> aggregates = aggregate(records, key);
        StringBuilder csv = new StringBuilder(aggregateHeader(key)).append('\n');
        for (Aggregate aggregate : aggregates.values()) {
            switch (key) {
                case "offer" -> {
                    Offer offer = offersByCode.get(aggregate.key);
                    String type = offer == null ? "" : nullToEmpty(offer.type);
                    String bgCode = offer == null ? "" : nullToEmpty(offer.getBudgetGroupCode());
                    csv.append(sanitize(aggregate.key)).append('|')
                            .append(sanitize(type)).append('|')
                            .append(sanitize(bgCode)).append('|')
                            .append(plain(aggregate.amount)).append('|')
                            .append(aggregate.tickets.size()).append('\n');
                }
                default -> csv.append(sanitize(aggregate.key)).append('|')
                        .append(plain(aggregate.amount)).append('|')
                        .append(aggregate.tickets.size()).append('\n');
            }
        }
        return csv(csv.toString(), "consumptions-" + key + ".csv");
    }

    /**
     * Builds the aggregated JSON: the same content as {@link #aggregateCsv}.
     *
     * @param records      the exported records.
     * @param offersByCode the referential offers resolved by code.
     * @param key          the grouping key: {@code offer}, {@code store}, {@code day} or {@code funding}.
     * @return the JSON response.
     */
    private Response aggregateJson(List<ConsumptionRecord> records, Map<String, Offer> offersByCode, String key) {
        Map<String, Aggregate> aggregates = aggregate(records, key);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            switch (key) {
                case "offer" -> {
                    Offer offer = offersByCode.get(aggregate.key);
                    row.put("offerCode", aggregate.key);
                    row.put("offerType", offer == null ? "" : nullToEmpty(offer.type));
                    row.put("budgetGroupCode", offer == null ? "" : nullToEmpty(offer.getBudgetGroupCode()));
                }
                case "store" -> row.put("storeCode", aggregate.key);
                case "funding" -> row.put("funding", aggregate.key);
                default -> row.put("day", aggregate.key);
            }
            row.put("amount", aggregate.amount);
            row.put("ticketCount", aggregate.tickets.size());
            rows.add(row);
        }
        return Response.ok(rows).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Aggregates the records by key, summing the signed amount (cancellations net) and collecting
     * the distinct tickets per key. Keys are kept in insertion order for a stable output.
     *
     * @param records the exported records.
     * @param key     the grouping key: {@code offer}, {@code store}, {@code day} or {@code funding}.
     * @return the aggregates by key value.
     */
    private Map<String, Aggregate> aggregate(List<ConsumptionRecord> records, String key) {
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (ConsumptionRecord record : records) {
            String value = switch (key) {
                case "offer" -> record.offerCode;
                case "store" -> record.storeCode;
                case "funding" -> nullToEmpty(record.funding);
                default -> record.finalizedAt == null ? "" : record.finalizedAt.toLocalDate().toString();
            };
            int sign = ConsumptionRecord.DIRECTION_CANCELLATION.equals(record.direction) ? -1 : 1;
            Aggregate aggregate = aggregates.computeIfAbsent(value, Aggregate::new);
            aggregate.amount = aggregate.amount.add(signed(record.amount, sign));
            aggregate.tickets.add(record.storeCode + " " + record.ticketId);
        }
        return aggregates;
    }

    /**
     * Returns the aggregated CSV header for a grouping key.
     *
     * @param key the grouping key.
     * @return the header line.
     */
    private String aggregateHeader(String key) {
        return switch (key) {
            case "offer" -> "OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|AMOUNT|TICKET_COUNT";
            case "store" -> "STORE_CODE|AMOUNT|TICKET_COUNT";
            case "funding" -> "FUNDING|AMOUNT|TICKET_COUNT";
            default -> "DAY|AMOUNT|TICKET_COUNT";
        };
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Resolves the member offer codes of a budget group, or null when no group filter is set.
     *
     * @param budgetGroupCode the budget group code, may be null or blank.
     * @return the member offer codes (possibly empty when the group has none), or null when no
     *         group filter applies.
     */
    private List<String> resolveBudgetGroupOffers(String budgetGroupCode) {
        String code = trimToNull(budgetGroupCode);
        if (code == null) {
            return null;
        }
        List<Offer> offers = Offer.list("budgetGroup.code = ?1", code);
        List<String> codes = new ArrayList<>();
        for (Offer offer : offers) {
            codes.add(offer.code);
        }
        return codes;
    }

    /**
     * Batch-resolves the referential offers named by the records, to avoid an N+1 lookup.
     *
     * @param records the exported records.
     * @return the offers by code; a code absent from the map is an offer deleted since.
     */
    private Map<String, Offer> resolveOffers(List<ConsumptionRecord> records) {
        Set<String> codes = new HashSet<>();
        for (ConsumptionRecord record : records) {
            codes.add(record.offerCode);
        }
        Map<String, Offer> offersByCode = new HashMap<>();
        if (!codes.isEmpty()) {
            for (Offer offer : Offer.<Offer>list("code in ?1", codes)) {
                offersByCode.put(offer.code, offer);
            }
        }
        return offersByCode;
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
     * Parses the upper bound: an ISO local date-time, or an ISO date taken at the end of the day so
     * a plain-date {@code to} includes the whole day.
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
     * Normalizes the grouping key to one of the accepted values, defaulting to {@code none}.
     *
     * @param groupBy the raw grouping key.
     * @return {@code none}, {@code offer}, {@code store} or {@code day}.
     */
    private String normalizeGroupBy(String groupBy) {
        if (groupBy == null) {
            return "none";
        }
        String value = groupBy.trim().toLowerCase();
        return switch (value) {
            case "offer", "store", "day", "funding" -> value;
            default -> "none";
        };
    }

    /**
     * Applies the direction sign to an absolute amount and scales it to the cent.
     *
     * @param amount the absolute amount, may be null.
     * @param sign   {@code +1} for a SALE, {@code -1} for a CANCELLATION.
     * @return the signed amount at scale 2.
     */
    private BigDecimal signed(BigDecimal amount, int sign) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.multiply(BigDecimal.valueOf(sign)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Renders an amount in plain notation at scale 2.
     *
     * @param amount the amount.
     * @return the plain string.
     */
    private String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Builds a CSV attachment response.
     *
     * @param body     the CSV body.
     * @param filename the attachment file name.
     * @return the response.
     */
    private Response csv(String body, String filename) {
        return Response.ok(body).type(CSV_MEDIA_TYPE)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /**
     * Neutralizes a CSV cell: strips line breaks, replaces the pipe separator, and defuses a
     * spreadsheet formula-injection lead character.
     *
     * @param value the raw cell value, may be null.
     * @return the safe cell value, never null.
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", "").replace("\n", " ").replace('|', '/');
        if (!cleaned.isEmpty() && "=+-@\t".indexOf(cleaned.charAt(0)) >= 0) {
            cleaned = "'" + cleaned;
        }
        return cleaned;
    }

    /**
     * Returns an empty string for a null value, the value otherwise.
     *
     * @param value the value, may be null.
     * @return the value or an empty string.
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
     * One expanded detail line: an EAN (possibly blank) and its signed amount.
     */
    private static final class DetailLine {

        /**
         * The EAN of the line, blank when the record carries no per-article breakdown.
         */
        private final String ean;

        /**
         * The signed amount of the line, scale 2.
         */
        private final BigDecimal amount;

        /**
         * Builds a detail line.
         *
         * @param ean    the EAN, possibly blank.
         * @param amount the signed amount, scale 2.
         */
        private DetailLine(String ean, BigDecimal amount) {
            this.ean = ean;
            this.amount = amount;
        }
    }

    /**
     * A running aggregate for one grouping key value: the netted amount and the distinct tickets.
     */
    private static final class Aggregate {

        /**
         * The grouping key value (offer code, store code or day).
         */
        private final String key;

        /**
         * The summed signed amount, scale 2.
         */
        private BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        /**
         * The distinct tickets contributing to this key, as {@code storeCode\0ticketId}.
         */
        private final Set<String> tickets = new HashSet<>();

        /**
         * Builds an aggregate for a key value.
         *
         * @param key the key value.
         */
        private Aggregate(String key) {
            this.key = key;
        }
    }
}
