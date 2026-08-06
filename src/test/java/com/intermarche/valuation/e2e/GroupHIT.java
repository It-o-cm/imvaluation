package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.ValuationTrace;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group H — price resolution and standard lines — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application, HTTP Basic as {@code admin/admin}. The whole group needs the referential, so
 * the mirror catalog is replayed ONCE at class start through the seven import endpoints in
 * the mandated order (Stores &rarr; StoreGroups &rarr; Products &rarr; ProductFamilies &rarr;
 * Categories &rarr; Prices &rarr; Offers). A handful of scenarios need catalog states the base
 * seed does not carry (a bounded price, a same-priority tie, WEIGHT/VOLUME products without a
 * reference); each such state is added by the owning test through the SAME import endpoints, on
 * products/stores no other H test values, so the tests stay order-independent.
 * <p>
 * SEED WINNERS (store 0101, price resolution is {@code order by priority DESC}) — the seed
 * carries two overlapping DEFAULT prices for products 001, 002 and 020, so the priority-1 row
 * wins: milk {@code 002} DEFAULT resolves to {@code 2.76} TTC (not the priority-0 {@code 3.00}),
 * apple {@code 001} DEFAULT to {@code 1.08} and its BASE_FOR_DISCOUNT to {@code 1.19}. This is
 * why H1 asserts {@code 2.76} where the catalog quotes {@code 3.00} (CALIBRATION below).
 * <p>
 * CALIBRATION — the catalog's H1 quotes a milk total of {@code 3.00}, the priority-0 DEFAULT
 * price. The seed however adds a priority-1 DEFAULT at {@code 2.76} (the very overlap H2 relies
 * on), and {@code Price.findActivePriceAtDate} orders by priority DESC, so the observed winner
 * is {@code 2.76}. H1/H2 pin the observed {@code 2.76}; the {@code 3.00} figure is the
 * superseded priority-0 price.
 * <p>
 * TRANSVERSE GUARD — 4xx/5xx bodies of {@code /valuation} carry no entity: every textual
 * assertion for a failed call reads {@code valuation_traces.error_message} (Panache under
 * {@code QuarkusTransaction}), keyed by a unique {@code customerCode} per probe, never the raw
 * HTTP body. {@code offers} is a {@code HashSet}: standard lines are always found by predicate
 * on their type, never by a fixed index.
 * <p>
 * CROSS-REFERENCE — H7's frozen quirk ("{@code standardQuantity} divides by
 * {@code referenceWeight} even for a VOLUME product") impacts the deposit-basket and fixed-
 * discount volume maths, not the standard-line pricing exercised here; it is owned by group L
 * (L6) and only the standard VOLUME pricing (which correctly uses {@code referenceVolume}) is
 * pinned in {@link #h7_weightAndVolumeProducts()}.
 */
@QuarkusTest
class GroupHIT {

    /**
     * Seeds used by the priced scenarios, in the mandated import order.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/store-groups/import", "seed/02-store-groups.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/product-families/import", "seed/04-product-families.csv"},
            {"/product-category-storages/import", "seed/05-product-category-storages.csv"},
            {"/prices/import", "seed/06-prices.csv"},
            {"/offers/import", "seed/07-offers.csv"},
    };

    /**
     * Whether the mirror catalog has already been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the seven CSV imports once, before the first scenario, so the whole group shares
     * one mirror catalog. The in-memory base lives for the class run, so the static guard runs
     * the imports exactly once even though seeding happens in a {@code @BeforeEach} (the
     * RestAssured test port is wired per test instance).
     */
    @BeforeEach
    void seedCatalogOnce() {
        if (seeded) {
            return;
        }
        for (String[] step : SEED) {
            importCsv(step[0], readResource(step[1]));
        }
        seeded = true;
    }

    /**
     * Reads a classpath resource into a string.
     *
     * @param path The classpath-relative resource path.
     * @return The resource content, UTF-8 decoded.
     */
    private static String readResource(String path) {
        try (var in = GroupHIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing seed resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read seed resource: " + path, e);
        }
    }

    // --------------------------------------------------
    // HTTP + trace helpers
    // --------------------------------------------------

    /**
     * Posts a CSV body to an import endpoint as {@code admin/admin} and asserts a 200; used both
     * for the base seed and for the per-test catalog augmentations (bounded prices, ties,
     * synthetic products).
     *
     * @param endpoint The import endpoint path.
     * @param csv      The CSV body, header line included (the importer skips line 1).
     */
    private void importCsv(String endpoint, String csv) {
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT).body(csv)
                .when().post(endpoint)
                .then().statusCode(200);
    }

    /**
     * Posts a basket to {@code /valuation} as {@code admin/admin} and asserts the HTTP status.
     *
     * @param body           The raw basket JSON.
     * @param expectedStatus The expected HTTP status code.
     * @return The full response, for structural assertions on the parsed tree.
     */
    private io.restassured.response.Response valuate(String body, int expectedStatus) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/valuation")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    /**
     * A read-only, transaction-safe projection of a valuation trace row.
     */
    private static final class TraceView {

        /**
         * Whether a trace was found for the queried customer code.
         */
        boolean found;

        /**
         * The {@code STATUS_*} discriminator.
         */
        String status;

        /**
         * The HTTP status recorded.
         */
        Integer httpStatus;

        /**
         * The recorded error message, or null on success.
         */
        String errorMessage;

        /**
         * Whether the trace carries a response payload.
         */
        boolean hasResponsePayload;
    }

    /**
     * Loads the latest trace for a customer code and projects its columns inside the
     * transaction, so the returned view survives the closed session.
     *
     * @param customerCode The unique customer code stamped on the probe.
     * @return The projected trace, its {@code found} flag false when absent.
     */
    private TraceView traceFor(String customerCode) {
        TraceView view = new TraceView();
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = ValuationTrace
                    .find("customerCode = ?1 order by createdAt desc", customerCode)
                    .firstResult();
            if (trace != null) {
                view.found = true;
                view.status = trace.status;
                view.httpStatus = trace.httpStatus;
                view.errorMessage = trace.errorMessage;
                view.hasResponsePayload = trace.responsePayload != null;
            }
        });
        return view;
    }

    /**
     * Posts a basket expected to fail with a 500 and asserts the FAILED trace carries the
     * literal fragment; the fragment is a substring match, tolerant of any engine wrapping
     * prefix ({@code Error building appliers from factory: ...}).
     *
     * @param customer         The unique customer code for the probe.
     * @param body             The basket JSON.
     * @param expectedFragment The literal that {@code error_message} must contain.
     */
    private void assertFailed(String customer, String body, String expectedFragment) {
        valuate(body, 500);
        TraceView trace = traceFor(customer);
        assertTrue(trace.found, "A FAILED trace must exist for " + customer);
        assertEquals(ValuationTrace.STATUS_FAILED, trace.status, "Status for " + customer);
        assertEquals(500, trace.httpStatus, "HTTP status for " + customer);
        assertFalse(trace.hasResponsePayload, "A FAILED trace carries no response payload: " + customer);
        assertNotNull(trace.errorMessage, "Error message for " + customer);
        assertTrue(trace.errorMessage.contains(expectedFragment),
                "Message for " + customer + " must contain <" + expectedFragment + ">, was: "
                        + trace.errorMessage);
    }

    // --------------------------------------------------
    // JSON + money helpers
    // --------------------------------------------------

    /**
     * Builds a single-store basket JSON from raw item fragments.
     *
     * @param customer The customer code stamped for trace lookup.
     * @param store    The store code.
     * @param items    The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String basket(String customer, String store, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\",\"items\":[" + String.join(",", items) + "]}";
    }

    /**
     * Builds a plain item fragment (EAN + quantity only).
     *
     * @param ean The product EAN.
     * @param qty The quantity, as a JSON literal.
     * @return The item JSON fragment.
     */
    private static String plain(String ean, String qty) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + "}";
    }

    /**
     * Builds a plain item fragment carrying a line identifier.
     *
     * @param lineId The line identifier.
     * @param ean    The product EAN.
     * @param qty    The quantity, as a JSON literal.
     * @return The item JSON fragment.
     */
    private static String line(String lineId, String ean, String qty) {
        return "{\"lineId\":\"" + lineId + "\",\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + "}";
    }

    /**
     * Builds an item fragment carrying an explicit {@code priceDate}.
     *
     * @param ean  The product EAN.
     * @param qty  The quantity, as a JSON literal.
     * @param date The price date, ISO-8601 as a raw string.
     * @return The item JSON fragment.
     */
    private static String dated(String ean, String qty, String date) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + ",\"priceDate\":\"" + date + "\"}";
    }

    /**
     * Builds an item fragment carrying a full line-borne transient price (all three fields).
     * <p>
     * The price numbers are inlined verbatim so their JSON scale (e.g. {@code 1.0} versus
     * {@code 1.00}) is preserved through Jackson into {@code BigDecimal}, which is what the
     * scale-sensitive merge quirk of H6 turns on.
     *
     * @param ean  The product EAN.
     * @param qty  The quantity, as a JSON literal.
     * @param excl The unit price excluding tax, as a raw JSON literal.
     * @param incl The unit price including tax, as a raw JSON literal.
     * @param vat  The VAT rate, as a raw JSON literal.
     * @return The item JSON fragment.
     */
    private static String priced(String ean, String qty, String excl, String incl, String vat) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty
                + ",\"pricePerUnitExclTax\":" + excl
                + ",\"pricePerUnitInclTax\":" + incl
                + ",\"vatRate\":" + vat + "}";
    }

    /**
     * Collects the indices of the standard offers covering a given EAN.
     * <p>
     * {@code offers} is a {@code HashSet} serialized in an arbitrary order, so a standard line
     * is always located by matching its {@code Standard: EAN=<ean>, } type prefix, never by a
     * fixed index.
     *
     * @param body The parsed evaluation.
     * @param ean  The product EAN.
     * @return The indices of the matching standard offers, possibly empty.
     */
    private static List<Integer> standardOffers(JsonPath body, String ean) {
        List<Integer> indices = new ArrayList<>();
        int count = body.getList("offers").size();
        for (int i = 0; i < count; i++) {
            String type = body.getString("offers[" + i + "].type");
            if (type != null && type.startsWith("Standard: EAN=" + ean + ", ")) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Reads the including-tax amount of an offer, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body  The parsed evaluation.
     * @param index The offer index.
     * @return The offer's including-tax amount.
     */
    private static BigDecimal offerTtc(JsonPath body, int index) {
        return new BigDecimal(body.getString("offers[" + index + "].amount.amountIncludingTax"));
    }

    /**
     * Reads the excluding-tax amount of an offer, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body  The parsed evaluation.
     * @param index The offer index.
     * @return The offer's excluding-tax amount.
     */
    private static BigDecimal offerHt(JsonPath body, int index) {
        return new BigDecimal(body.getString("offers[" + index + "].amount.amountExcludingTax"));
    }

    /**
     * Asserts that two money literals are numerically equal, ignoring scale.
     *
     * @param expected The expected amount.
     * @param actual   The observed amount.
     * @param message  The assertion message.
     */
    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + " (expected " + expected + ", got " + actual + ")");
    }

    // --------------------------------------------------
    // H1 — simple line
    // --------------------------------------------------

    /**
     * H1 — a simple line. One milk {@code 002} on store {@code 0101} yields exactly one standard
     * offer typed {@code Standard: EAN=3300000000002, Qty=1.0} at the 20 % catalog rate, and the
     * VAT breakdown is a single 20 % line summing to the total. CALIBRATION: the offer total is
     * the observed {@code 2.76} (the priority-1 DEFAULT winner), where the catalog quotes the
     * superseded priority-0 {@code 3.00}.
     */
    @Test
    void h1_simpleLine() {
        JsonPath body = valuate(basket("H1-simple", "0101", plain("3300000000002", "1")), 200).jsonPath();
        List<Integer> standards = standardOffers(body, "3300000000002");
        assertEquals(1, standards.size(), "A single milk line yields exactly one standard offer");
        int idx = standards.get(0);
        assertEquals("Standard: EAN=3300000000002, Qty=1.0", body.getString("offers[" + idx + "].type"),
                "The literal standard type carries the EAN and the 1.0 quantity");
        assertMoney("2.76", offerTtc(body, idx), "Milk resolves to the priority-1 DEFAULT winner");
        assertMoney("0.2000", new BigDecimal(body.getString("offers[" + idx + "].amount.vatRate")),
                "The line keeps the product's real 20% rate");
        int rates = body.getList("vatBreakdown").size();
        assertEquals(1, rates, "A single-rate basket has one VAT breakdown line");
        assertMoney("0.2000", new BigDecimal(body.getString("vatBreakdown[0].vatRate")),
                "The only breakdown line is the 20% rate");
        assertMoney(body.getString("totalPrice.amountIncludingTax"),
                new BigDecimal(body.getString("vatBreakdown[0].amountIncludingTax")),
                "The single breakdown line sums to the total");
    }

    // --------------------------------------------------
    // H2 — overlap & priority
    // --------------------------------------------------

    /**
     * H2 — overlap and priority. Two DEFAULT prices are valid at once for milk {@code 002}
     * (priorities 0 and 1); the priority-1 row wins ({@code order by priority DESC}), so the
     * standard line is the priority-1 {@code 2.76}, not the priority-0 {@code 3.00}. The tie
     * clause is documentary: a same-priority overlap is created for the otherwise-unpriced knife
     * set {@code 033} (a second DEFAULT priority-0 row at a later start date, both active today),
     * whose winner is indeterminate — the assertion accepts either amount and journals which one
     * the database returned.
     */
    @Test
    void h2_overlapAndPriority() {
        JsonPath body = valuate(basket("H2-priority", "0101", plain("3300000000002", "1")), 200).jsonPath();
        List<Integer> standards = standardOffers(body, "3300000000002");
        assertEquals(1, standards.size(), "Overlapping DEFAULT prices still collapse to one line");
        BigDecimal ttc = offerTtc(body, standards.get(0));
        assertMoney("2.76", ttc, "The priority-1 price wins the overlap");
        assertFalse(ttc.compareTo(new BigDecimal("3.00")) == 0,
                "The priority-0 3.00 must not win the overlap");
        importCsv("/prices/import",
                "ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime\n"
                        + "3300000000033|0101|20.00|24.00|0.2000|DEFAULT|0|2026-01-13T00:00:00|\n");
        JsonPath tie = valuate(basket("H2-tie", "0101", plain("3300000000033", "1")), 200).jsonPath();
        List<Integer> tieStandards = standardOffers(tie, "3300000000033");
        assertEquals(1, tieStandards.size(), "The tied product still yields one standard line");
        BigDecimal tieTtc = offerTtc(tie, tieStandards.get(0));
        boolean seededWinner = tieTtc.compareTo(new BigDecimal("30.00")) == 0;
        boolean importedWinner = tieTtc.compareTo(new BigDecimal("24.00")) == 0;
        assertTrue(seededWinner || importedWinner,
                "A same-priority tie resolves to one of the two candidate prices, was: " + tieTtc);
        System.out.println("[H2] same-priority tie winner for 3300000000033 = "
                + tieTtc.toPlainString() + " (30.00 seeded / 24.00 imported, order indeterminate)");
    }

    // --------------------------------------------------
    // H3 — validity window [start, end)
    // --------------------------------------------------

    /**
     * H3 — the validity window {@code [start, end)}. A price bounded to {@code end = T} is
     * excluded at {@code priceDate = T} (the end boundary is exclusive) and remains excluded once
     * {@code T} is in the past. A milk {@code 002} price is bounded to {@code [2026-01-12,
     * 2026-06-01)} on the otherwise-unpriced store {@code 0103}: valued a moment before the end it
     * is a 200, valued exactly at the end or with no date at all (now is past {@code 2026-06-01})
     * it is a 500 {@code No active price found}. A null end is +infinity: the seed price on
     * {@code 0101}, valued far in the future, still resolves.
     */
    @Test
    void h3_validityWindow() {
        importCsv("/prices/import",
                "ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime\n"
                        + "3300000000002|0103|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|2026-06-01T00:00:00\n"
                        + "3300000000002|0103|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|2026-06-01T00:00:00\n");
        JsonPath within = valuate(
                basket("H3-within", "0103", dated("3300000000002", "1", "2026-05-31T23:59:59")), 200).jsonPath();
        List<Integer> standards = standardOffers(within, "3300000000002");
        assertEquals(1, standards.size(), "A price valued inside its window yields a standard line");
        assertMoney("3.00", offerTtc(within, standards.get(0)), "The bounded price is used within the window");
        assertFailed("H3-at-end",
                basket("H3-at-end", "0103", dated("3300000000002", "1", "2026-06-01T00:00:00")),
                "No active price found for Product 'Lait UHT 1L'");
        assertFailed("H3-past-end",
                basket("H3-past-end", "0103", plain("3300000000002", "1")),
                "No active price found for Product 'Lait UHT 1L'");
        JsonPath future = valuate(
                basket("H3-null-end", "0101", dated("3300000000002", "1", "2030-01-01T00:00:00")), 200).jsonPath();
        assertEquals(1, standardOffers(future, "3300000000002").size(),
                "A null end date keeps the seed price active far in the future");
    }

    // --------------------------------------------------
    // H4 — explicit priceDate
    // --------------------------------------------------

    /**
     * H4 — an explicit {@code priceDate}. Against the seed's milk price (start
     * {@code 2026-01-12T00:00:00}, null end): {@code 2026-01-11T23:59:59} is before the start
     * &rarr; 500 {@code No active price}; {@code 2026-01-12T00:00:00} is the inclusive start
     * &rarr; 200 at {@code 2.76}; a date-only {@code 2026-01-12} fails ISO-8601 parsing &rarr;
     * 500 {@code Invalid date format}.
     */
    @Test
    void h4_explicitPriceDate() {
        assertFailed("H4-before-start",
                basket("H4-before-start", "0101", dated("3300000000002", "1", "2026-01-11T23:59:59")),
                "No active price found for Product 'Lait UHT 1L'");
        JsonPath atStart = valuate(
                basket("H4-at-start", "0101", dated("3300000000002", "1", "2026-01-12T00:00:00")), 200).jsonPath();
        List<Integer> standards = standardOffers(atStart, "3300000000002");
        assertEquals(1, standards.size(), "The inclusive start boundary yields a standard line");
        assertMoney("2.76", offerTtc(atStart, standards.get(0)), "At the start boundary the price is active");
        assertFailed("H4-date-only",
                basket("H4-date-only", "0101", dated("3300000000002", "1", "2026-01-12")),
                "Invalid date format '2026-01-12' for item EAN '3300000000002'. Expected ISO-8601 format.");
    }

    // --------------------------------------------------
    // H5 — line-borne price
    // --------------------------------------------------

    /**
     * H5 — a price borne by the line. When all three of {@code pricePerUnitExclTax},
     * {@code pricePerUnitInclTax} and {@code vatRate} are supplied, a transient price is used and
     * the database is never consulted: milk {@code 002} on the fully-unpriced store {@code 0104}
     * still values, at the ported {@code 4.80}. DEFAULT then equals BASE_FOR_DISCOUNT — an apple
     * {@code 001} basket, which registers an IMMEDIATE_VOUCHER on the standard applier, is valued
     * at the ported {@code 2.40} (the reference price is the ported price, not the seed's +10 %
     * {@code 1.19}), and the discount is computed on it. Supplying only one or two of the three
     * fields is silently ignored: three partial baskets fall back to the catalog {@code 2.76}.
     */
    @Test
    void h5_lineBornePrice() {
        JsonPath noDb = valuate(
                basket("H5-transient", "0104", priced("3300000000002", "1", "4.00", "4.80", "0.20")), 200).jsonPath();
        List<Integer> transientStd = standardOffers(noDb, "3300000000002");
        assertEquals(1, transientStd.size(), "A ported price values a product with no catalog price at the store");
        assertMoney("4.80", offerTtc(noDb, transientStd.get(0)), "The ported price is used verbatim, no DB lookup");
        JsonPath apple = valuate(
                basket("H5-ported-ref", "0101", priced("3300000000001", "1", "2.00", "2.40", "0.20")), 200).jsonPath();
        List<Integer> appleStd = standardOffers(apple, "3300000000001");
        assertEquals(1, appleStd.size(), "The apple line yields one standard offer");
        assertMoney("2.40", offerTtc(apple, appleStd.get(0)),
                "DEFAULT equals BASE_FOR_DISCOUNT on a ported price: the +10% seed reference is not used");
        List<Map<String, Object>> advantages = apple.getList("advantages");
        assertTrue(advantages.stream().anyMatch(a -> a.get("discountAmount") != null),
                "The immediate voucher discount is computed on the ported price: " + advantages);
        String[][] partials = {
                {"H5-partial-incl",
                        "{\"produceEan\":\"3300000000002\",\"quantity\":1,\"pricePerUnitInclTax\":99.99}"},
                {"H5-partial-excl-incl",
                        "{\"produceEan\":\"3300000000002\",\"quantity\":1,\"pricePerUnitExclTax\":88.88,\"pricePerUnitInclTax\":99.99}"},
                {"H5-partial-excl-vat",
                        "{\"produceEan\":\"3300000000002\",\"quantity\":1,\"pricePerUnitExclTax\":88.88,\"vatRate\":0.5}"},
        };
        for (String[] partial : partials) {
            JsonPath fallback = valuate(basket(partial[0], "0101", partial[1]), 200).jsonPath();
            List<Integer> std = standardOffers(fallback, "3300000000002");
            assertEquals(1, std.size(), "The partial-price line still yields one standard offer for " + partial[0]);
            assertMoney("2.76", offerTtc(fallback, std.get(0)),
                    "A partial ported price is ignored, the catalog price is used for " + partial[0]);
        }
    }

    // --------------------------------------------------
    // H6 — line merge
    // --------------------------------------------------

    /**
     * H6 — line merge. Two lines of the same EAN and the same price profile aggregate into a
     * single standard offer whose quantity is the sum and whose items restore the split line by
     * line (one valued item per source line). Different profiles stay apart: a plain line and a
     * ported-price line of the same EAN yield two standard offers; and the frozen scale quirk —
     * ported prices {@code 1.0} versus {@code 1.00}, equal in value but not in
     * {@code BigDecimal} scale — is NOT merged, again two offers.
     */
    @Test
    void h6_lineMerge() {
        JsonPath merged = valuate(
                basket("H6-merge", "0101", line("L1", "3300000000002", "1"), line("L2", "3300000000002", "1")),
                200).jsonPath();
        List<Integer> mergedStd = standardOffers(merged, "3300000000002");
        assertEquals(1, mergedStd.size(), "Same EAN and profile aggregate into one standard offer");
        int idx = mergedStd.get(0);
        assertEquals("Standard: EAN=3300000000002, Qty=2.0", merged.getString("offers[" + idx + "].type"),
                "The merged quantity is the sum of the two lines");
        assertMoney("5.52", offerTtc(merged, idx), "Two units at the 2.76 winner sum to 5.52");
        List<Map<String, Object>> items = merged.getList("offers[" + idx + "].items");
        assertEquals(2, items.size(), "The merge preserves both source lines, restored line by line");
        List<String> lineIds = merged.getList("offers[" + idx + "].items.lineId");
        assertTrue(lineIds.contains("L1") && lineIds.contains("L2"),
                "Both original line identifiers are restored: " + lineIds);
        BigDecimal itemsSum = new BigDecimal(merged.getString("offers[" + idx + "].items[0].amount.amountIncludingTax"))
                .add(new BigDecimal(merged.getString("offers[" + idx + "].items[1].amount.amountIncludingTax")));
        assertMoney("5.52", itemsSum, "The per-line amounts sum back to the offer amount");
        JsonPath profiles = valuate(
                basket("H6-profiles", "0101", plain("3300000000002", "1"),
                        priced("3300000000002", "1", "4.00", "4.80", "0.20")), 200).jsonPath();
        assertEquals(2, standardOffers(profiles, "3300000000002").size(),
                "A plain line and a ported-price line of the same EAN are two standard offers");
        JsonPath scale = valuate(
                basket("H6-scale", "0101", priced("3300000000002", "1", "1.0", "1.2", "0.20"),
                        priced("3300000000002", "1", "1.00", "1.2", "0.20")), 200).jsonPath();
        assertEquals(2, standardOffers(scale, "3300000000002").size(),
                "Scale-different ported prices (1.0 vs 1.00) are not merged: BigDecimal.equals is scale-sensitive");
    }

    // --------------------------------------------------
    // H7 — weight and volume products
    // --------------------------------------------------

    /**
     * H7 — weight and volume products. Ham {@code 008} (WEIGHT, reference {@code 0.100}) at
     * quantity {@code 0.5} is priced {@code 0.5 / 0.100 = 5} reference units &rarr; 5 &times; the
     * {@code 2.40} reference &rarr; {@code 12.00} TTC / {@code 10.00} HT. A WEIGHT product with a
     * zero reference weight fails with the {@code no valid reference weight defined} literal, and
     * a VOLUME product with a zero reference volume with the {@code no valid reference volume
     * defined} literal; a VOLUME product with a real reference volume ({@code 2.000}) at quantity
     * {@code 1.0} is priced {@code 1.0 / 2.000 = 0.5} of its reference. The three synthetic
     * products are imported through the product and price endpoints; the deposit-side quirk of
     * H7 (VOLUME divided by referenceWeight) is owned by group L.
     */
    @Test
    void h7_weightAndVolumeProducts() {
        JsonPath ham = valuate(basket("H7-ham", "0101", plain("3300000000008", "0.5")), 200).jsonPath();
        List<Integer> hamStd = standardOffers(ham, "3300000000008");
        assertEquals(1, hamStd.size(), "The weighed ham yields one standard offer");
        assertEquals("Standard: EAN=3300000000008, Qty=0.5", ham.getString("offers[" + hamStd.get(0) + "].type"),
                "The standard type carries the fractional 0.5 quantity");
        assertMoney("12.00", offerTtc(ham, hamStd.get(0)), "0.5kg / 0.100 ref = 5 x 2.40 = 12.00 TTC");
        assertMoney("10.00", offerHt(ham, hamStd.get(0)), "5 x 2.00 = 10.00 HT");
        importCsv("/products/import",
                "ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active\n"
                        + "3300000000901|Test Weight No Ref|Weight without reference|BrandZ|0.000|1.000|WEIGHT|kg|true\n"
                        + "3300000000902|Test Volume No Ref|Volume without reference|BrandZ|1.000|0.000|VOLUME|L|true\n"
                        + "3300000000903|Test Volume Ref|Volume with reference|BrandZ|1.000|2.000|VOLUME|L|true\n");
        importCsv("/prices/import",
                "ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime\n"
                        + "3300000000901|0101|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
                        + "3300000000901|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
                        + "3300000000902|0101|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
                        + "3300000000902|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
                        + "3300000000903|0101|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
                        + "3300000000903|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n");
        assertFailed("H7-weight-no-ref",
                basket("H7-weight-no-ref", "0101", plain("3300000000901", "1")),
                "is typed as WEIGHT but has no valid reference weight defined.");
        assertFailed("H7-volume-no-ref",
                basket("H7-volume-no-ref", "0101", plain("3300000000902", "1")),
                "is typed as VOLUME but has no valid reference volume defined.");
        JsonPath vol = valuate(basket("H7-volume", "0101", plain("3300000000903", "1.0")), 200).jsonPath();
        List<Integer> volStd = standardOffers(vol, "3300000000903");
        assertEquals(1, volStd.size(), "The volume product yields one standard offer");
        assertMoney("3.00", offerTtc(vol, volStd.get(0)), "1.0L / 2.000 ref = 0.5 x 6.00 = 3.00 TTC");
        assertMoney("2.50", offerHt(vol, volStd.get(0)), "0.5 x 5.00 = 2.50 HT");
    }

    // --------------------------------------------------
    // H8 — DEFAULT to BASE_FOR_DISCOUNT switch
    // --------------------------------------------------

    /**
     * H8 — the DEFAULT &rarr; BASE_FOR_DISCOUNT switch. As soon as a discount registers on the
     * standard applier the line is priced at the reference price, +10 % in the seed. An apple
     * {@code 001} on {@code 0101} carries an applicable IMMEDIATE_VOUCHER, so its standard line
     * is the priority-1 BASE_FOR_DISCOUNT {@code 1.19}, never the priority-1 DEFAULT {@code 1.08}
     * — the +10 % gap proves the switch. A milk {@code 002} line, targeted by no discount, keeps
     * the plain DEFAULT {@code 2.76}. This is the engine's most counter-intuitive behaviour: the
     * reference price rises the moment a discount exists.
     */
    @Test
    void h8_defaultToBaseForDiscountSwitch() {
        JsonPath apple = valuate(basket("H8-discounted", "0101", plain("3300000000001", "1")), 200).jsonPath();
        List<Integer> appleStd = standardOffers(apple, "3300000000001");
        assertEquals(1, appleStd.size(), "The discounted apple yields one standard offer");
        BigDecimal appleTtc = offerTtc(apple, appleStd.get(0));
        assertMoney("1.19", appleTtc, "A registered discount switches the line to BASE_FOR_DISCOUNT (+10%)");
        assertFalse(appleTtc.compareTo(new BigDecimal("1.08")) == 0,
                "The plain DEFAULT 1.08 must not be used once a discount is registered");
        JsonPath milk = valuate(basket("H8-plain", "0101", plain("3300000000002", "1")), 200).jsonPath();
        List<Integer> milkStd = standardOffers(milk, "3300000000002");
        assertEquals(1, milkStd.size(), "The undiscounted milk yields one standard offer");
        assertMoney("2.76", offerTtc(milk, milkStd.get(0)),
                "With no discount registered the line stays at the plain DEFAULT price");
    }
}
