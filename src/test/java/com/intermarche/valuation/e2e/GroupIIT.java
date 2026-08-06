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
 * Group I — manual cash-desk gestures carried by the line — of e2e-scenarios.md, in pure
 * RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application, HTTP Basic as {@code admin/admin}. The group needs the referential (real
 * catalog prices back every gesture, and the discount/vignette/meal-voucher surfaces of I4),
 * so the mirror catalog is replayed ONCE at class start through the seven import endpoints in
 * the mandated order (Stores &rarr; StoreGroups &rarr; Products &rarr; ProductFamilies &rarr;
 * Categories &rarr; Prices &rarr; Offers).
 * <p>
 * ENGINE UNDER TEST — {@code ManualGestureOfferFactory}. A line carrying exactly one of
 * {@code manualForcedPrice} / {@code manualDiscountAmount} / {@code manualDiscountPercent} is
 * handled by an ultra-priority applier (score {@code Double.MAX_VALUE}) that consumes the line
 * before any other offer and is deliberately NOT fed to the upsell pool. The gesture is a
 * plain per-unit arithmetic on the resolved {@code PriceUsage.DEFAULT} TTC price
 * ({@code base.priceIncludingTax}), floored at zero, multiplied by the line quantity — it does
 * NOT go through the WEIGHT/VOLUME reference divisor, so products {@code 001}/{@code 005}
 * (WEIGHT) are priced on their raw catalog TTC exactly like a UNIT product. The application
 * lands in {@code offers} (not {@code advantages}) with the literal type
 * {@code Manual Gesture: EAN=<ean> (<gesture>)}, where {@code <gesture>} preserves the JSON
 * scale of the number (so {@code 1.0} stays {@code 1.0}), the reason every I1/I6 fragment is
 * sent with its exact scale.
 * <p>
 * TRANSVERSE GUARD — {@code offers} is a {@code HashSet} serialized in arbitrary order: a
 * gesture offer is always located by its {@code Manual Gesture: EAN=<ean> (} type prefix,
 * never by a fixed index. 4xx/5xx bodies of {@code /valuation} carry no entity: the I3 double-
 * gesture rejection is asserted on {@code valuation_traces.error_message} (Panache under
 * {@code QuarkusTransaction}), keyed by a unique {@code customerCode} per probe, never the raw
 * HTTP body.
 * <p>
 * CALIBRATION (inherited from group G's G5) — {@code availableToUpcell} is {@code @JsonIgnore}
 * in {@code BasketEvaluation} and never serialized, so I4's "absent from availableToUpcell" is
 * pinned by the key's absence PLUS the absence of any upsell {@code suggestion} advantage on a
 * fully-gestured basket (the gesture never feeds the upsell pool). A gestured line is likewise
 * invisible to every {@code discountAmount}-bearing advantage (IMMEDIATE_VOUCHER, VIGNETTE,
 * the FREE_DELIVERY franco reduction) and to the MEAL_VOUCHER eligible plate; I4 asserts a
 * before/after contrast on the very apple line that is discounted, upsold and meal-voucher
 * eligible when NOT gestured.
 */
@QuarkusTest
class GroupIIT {

    /**
     * Seeds used by every scenario, in the mandated import order.
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
     * one mirror catalog. The static guard runs the imports exactly once even though seeding
     * happens in a {@code @BeforeEach} (the RestAssured test port is wired per test instance).
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
        try (var in = GroupIIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Posts a CSV body to an import endpoint as {@code admin/admin} and asserts a 200.
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
     * Builds a single-store {@code IN_STORE} basket JSON from raw item fragments.
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
     * Builds an item fragment carrying a manual forced price gesture.
     * <p>
     * The forced-price number is inlined verbatim so its JSON scale (e.g. {@code 1.0} vs
     * {@code 1.00}) survives Jackson into {@code BigDecimal} and is reflected literally in the
     * {@code (forced price <n>)} offer type.
     *
     * @param ean    The product EAN.
     * @param qty    The quantity, as a JSON literal.
     * @param forced The forced unit TTC price, as a raw JSON literal.
     * @return The item JSON fragment.
     */
    private static String forced(String ean, String qty, String forced) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + ",\"manualForcedPrice\":" + forced + "}";
    }

    /**
     * Builds an item fragment carrying a manual fixed-amount discount gesture.
     *
     * @param ean    The product EAN.
     * @param qty    The quantity, as a JSON literal.
     * @param amount The per-unit amount deducted, as a raw JSON literal.
     * @return The item JSON fragment.
     */
    private static String amount(String ean, String qty, String amount) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + ",\"manualDiscountAmount\":" + amount + "}";
    }

    /**
     * Builds an item fragment carrying a manual percentage discount gesture.
     *
     * @param ean     The product EAN.
     * @param qty     The quantity, as a JSON literal.
     * @param percent The percentage deducted, as a raw JSON literal.
     * @return The item JSON fragment.
     */
    private static String percent(String ean, String qty, String percent) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + ",\"manualDiscountPercent\":" + percent + "}";
    }

    /**
     * Builds an identified line fragment carrying a manual fixed-amount discount gesture.
     *
     * @param lineId The line identifier.
     * @param ean    The product EAN.
     * @param qty    The quantity, as a JSON literal.
     * @param amount The per-unit amount deducted, as a raw JSON literal.
     * @return The item JSON fragment.
     */
    private static String lineAmount(String lineId, String ean, String qty, String amount) {
        return "{\"lineId\":\"" + lineId + "\",\"produceEan\":\"" + ean + "\",\"quantity\":" + qty
                + ",\"manualDiscountAmount\":" + amount + "}";
    }

    /**
     * Builds an identified plain line fragment (line id + EAN + quantity).
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
     * Collects the indices of the manual-gesture offers covering a given EAN.
     * <p>
     * {@code offers} is a {@code HashSet} serialized in an arbitrary order, so a gesture is
     * located by matching its {@code Manual Gesture: EAN=<ean> (} type prefix, never by index.
     *
     * @param body The parsed evaluation.
     * @param ean  The product EAN.
     * @return The indices of the matching gesture offers, possibly empty.
     */
    private static List<Integer> gestureOffers(JsonPath body, String ean) {
        List<Integer> indices = new ArrayList<>();
        int count = body.getList("offers").size();
        for (int i = 0; i < count; i++) {
            String type = body.getString("offers[" + i + "].type");
            if (type != null && type.startsWith("Manual Gesture: EAN=" + ean + " (")) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Collects the indices of the non-gesture offers covering a given EAN.
     * <p>
     * Any offer whose type carries the EAN but is NOT a {@code Manual Gesture} type — a
     * {@code Standard}/{@code N+M}/etc. offer that a non-gestured line stays eligible for.
     *
     * @param body The parsed evaluation.
     * @param ean  The product EAN.
     * @return The indices of the matching non-gesture offers, possibly empty.
     */
    private static List<Integer> nonGestureOffers(JsonPath body, String ean) {
        List<Integer> indices = new ArrayList<>();
        int count = body.getList("offers").size();
        for (int i = 0; i < count; i++) {
            String type = body.getString("offers[" + i + "].type");
            if (type != null && type.contains(ean) && !type.startsWith("Manual Gesture:")) {
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
     * Sums the quantities of the valued items an offer restores.
     *
     * @param body  The parsed evaluation.
     * @param index The offer index.
     * @return The total quantity the offer covers.
     */
    private static double offerQty(JsonPath body, int index) {
        List<Object> qtys = body.getList("offers[" + index + "].items.quantity");
        double total = 0.0;
        for (Object q : qtys) {
            total += ((Number) q).doubleValue();
        }
        return total;
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
    // I1 — the three gestures
    // --------------------------------------------------

    /**
     * I1 — the three gestures. On store {@code 0101}: {@code manualForcedPrice: 1.0} on the
     * penne {@code …005} (catalog {@code 1.44}) yields exactly one gesture offer at {@code 1.00}
     * TTC keeping the {@code 0.2000} catalog rate; {@code manualDiscountAmount: 0.5} on the olive
     * oil {@code …006} (catalog {@code 6.00}) yields {@code 5.50}; {@code manualDiscountPercent:
     * 50} on the pan {@code …031} (catalog {@code 14.40}) yields {@code 7.20}. The three literal
     * types are pinned exactly: {@code (forced price 1.0)}, {@code (amount -0.5)},
     * {@code (percent -50%)}. Each gestured line consumes itself, so its EAN yields NO standard
     * offer beside the gesture.
     */
    @Test
    void i1_theThreeGestures() {
        JsonPath fp = valuate(basket("I1-forced", "0101", forced("3300000000005", "1", "1.0")), 200).jsonPath();
        List<Integer> fpG = gestureOffers(fp, "3300000000005");
        assertEquals(1, fpG.size(), "A forced-price line yields exactly one gesture offer");
        assertEquals("Manual Gesture: EAN=3300000000005 (forced price 1.0)",
                fp.getString("offers[" + fpG.get(0) + "].type"), "The forced-price literal type is pinned");
        assertMoney("1.00", offerTtc(fp, fpG.get(0)), "The forced price replaces the catalog price outright");
        assertMoney("0.2000", new BigDecimal(fp.getString("offers[" + fpG.get(0) + "].amount.vatRate")),
                "The catalog 20% rate is kept under a forced price");
        assertTrue(nonGestureOffers(fp, "3300000000005").isEmpty(),
                "A gestured line consumes itself, leaving no standard offer for its EAN");
        JsonPath am = valuate(basket("I1-amount", "0101", amount("3300000000006", "1", "0.5")), 200).jsonPath();
        List<Integer> amG = gestureOffers(am, "3300000000006");
        assertEquals(1, amG.size(), "A fixed-amount line yields exactly one gesture offer");
        assertEquals("Manual Gesture: EAN=3300000000006 (amount -0.5)",
                am.getString("offers[" + amG.get(0) + "].type"), "The fixed-amount literal type is pinned");
        assertMoney("5.50", offerTtc(am, amG.get(0)), "6.00 minus the 0.50 amount is 5.50");
        JsonPath pc = valuate(basket("I1-percent", "0101", percent("3300000000031", "1", "50")), 200).jsonPath();
        List<Integer> pcG = gestureOffers(pc, "3300000000031");
        assertEquals(1, pcG.size(), "A percentage line yields exactly one gesture offer");
        assertEquals("Manual Gesture: EAN=3300000000031 (percent -50%)",
                pc.getString("offers[" + pcG.get(0) + "].type"), "The percentage literal type is pinned");
        assertMoney("7.20", offerTtc(pc, pcG.get(0)), "50% off 14.40 is 7.20");
    }

    // --------------------------------------------------
    // I2 — zero floor
    // --------------------------------------------------

    /**
     * I2 — the zero floor. A {@code manualDiscountAmount: 20.0} on a line worth {@code 12.00}
     * TTC (a line-borne transient price {@code 10.00}/{@code 12.00}/{@code 0.20}, so the base is
     * exactly {@code 12.00} regardless of the catalog) floors the unit at {@code 0.00} rather
     * than going negative: both HT and TTC are {@code 0.00}. The floor is per unit, so the line
     * total is {@code 0.00} whatever the quantity.
     */
    @Test
    void i2_zeroFloor() {
        String item = "{\"produceEan\":\"3300000000006\",\"quantity\":1,"
                + "\"pricePerUnitExclTax\":10.00,\"pricePerUnitInclTax\":12.00,\"vatRate\":0.20,"
                + "\"manualDiscountAmount\":20.0}";
        JsonPath body = valuate(basket("I2-floor", "0101", item), 200).jsonPath();
        List<Integer> g = gestureOffers(body, "3300000000006");
        assertEquals(1, g.size(), "The over-discounted line still yields one gesture offer");
        assertEquals("Manual Gesture: EAN=3300000000006 (amount -20.0)",
                body.getString("offers[" + g.get(0) + "].type"), "The amount literal type is pinned");
        assertMoney("0.00", offerTtc(body, g.get(0)), "A discount above the price floors the unit TTC at zero");
        assertMoney("0.00", offerHt(body, g.get(0)), "The floored line is zero HT too, never negative");
    }

    // --------------------------------------------------
    // I3 — one gesture at most
    // --------------------------------------------------

    /**
     * I3 — a double gesture is forbidden. Two gestures on the same line — every pair and the
     * full triplet — are rejected with a {@code 500} whose {@code valuation_traces.error_message}
     * carries the literal {@code Item EAN '3300000000006' carries more than one manual gesture
     * (amount, percentage, forced price); only one is allowed.} The line is validated in
     * {@code buildAppliers} before any pricing, so the exact EAN is quoted in the message.
     */
    @Test
    void i3_atMostOneGesture() {
        String frag = "Item EAN '3300000000006' carries more than one manual gesture "
                + "(amount, percentage, forced price); only one is allowed.";
        String amountPercent = "{\"produceEan\":\"3300000000006\",\"quantity\":1,"
                + "\"manualDiscountAmount\":0.5,\"manualDiscountPercent\":10}";
        assertFailed("I3-amount-percent", basket("I3-amount-percent", "0101", amountPercent), frag);
        String amountForced = "{\"produceEan\":\"3300000000006\",\"quantity\":1,"
                + "\"manualDiscountAmount\":0.5,\"manualForcedPrice\":1.0}";
        assertFailed("I3-amount-forced", basket("I3-amount-forced", "0101", amountForced), frag);
        String percentForced = "{\"produceEan\":\"3300000000006\",\"quantity\":1,"
                + "\"manualDiscountPercent\":10,\"manualForcedPrice\":1.0}";
        assertFailed("I3-percent-forced", basket("I3-percent-forced", "0101", percentForced), frag);
        String triplet = "{\"produceEan\":\"3300000000006\",\"quantity\":1,"
                + "\"manualDiscountAmount\":0.5,\"manualDiscountPercent\":10,\"manualForcedPrice\":1.0}";
        assertFailed("I3-triplet", basket("I3-triplet", "0101", triplet), frag);
    }

    // --------------------------------------------------
    // I4 — ultra priority & total exclusion
    // --------------------------------------------------

    /**
     * I4 — ultra priority and total exclusion. The apple {@code …001} on {@code 0101} is
     * normally discounted (IMMEDIATE_VOUCHER &rarr; a non-null {@code discountAmount} advantage),
     * upsold (the 2FOR1 &rarr; a non-null {@code suggestion} advantage) and meal-voucher eligible
     * (a {@code MEAL_VOUCHER} advantage with a positive eligible plate). The CONTROL probe (qty
     * 2, no gesture) pins all three. Once the SAME line carries a gesture ({@code manualForcedPrice})
     * the ultra-priority applier consumes it first and excludes it from everything: the GESTURED
     * probe carries the gesture offer, ZERO {@code discountAmount} advantages, ZERO
     * {@code suggestion} advantages, and a MEAL_VOUCHER plate of {@code 0.00} — the gestured line
     * feeds neither the discounts, the vignette, the franco merchandise total nor the till plate.
     * CALIBRATION (G5): {@code availableToUpcell} is {@code @JsonIgnore}'d, so its absence from
     * the body is asserted directly, the upsell exclusion being observed through the missing
     * {@code suggestion} advantage.
     */
    @Test
    void i4_ultraPriorityAndTotalExclusion() {
        JsonPath control = valuate(basket("I4-control", "0101", plain("3300000000001", "2")), 200).jsonPath();
        List<Map<String, Object>> controlAdv = control.getList("advantages");
        assertTrue(controlAdv.stream().anyMatch(a -> a.get("discountAmount") != null),
                "CONTROL: the ungestured apple carries a real discount: " + controlAdv);
        assertTrue(controlAdv.stream().anyMatch(a -> a.get("suggestion") != null),
                "CONTROL: the ungestured apple carries an upsell suggestion: " + controlAdv);
        assertTrue(controlAdv.stream().anyMatch(a -> "MEAL_VOUCHER".equals(a.get("type"))),
                "CONTROL: the ungestured apple is meal-voucher eligible: " + controlAdv);
        io.restassured.response.Response gestedResp =
                valuate(basket("I4-gested", "0101", forced("3300000000001", "2", "1.0")), 200);
        JsonPath gested = gestedResp.jsonPath();
        String rawBody = gestedResp.asString();
        assertEquals(1, gestureOffers(gested, "3300000000001").size(),
                "The gestured apple yields exactly one gesture offer");
        List<Map<String, Object>> gestedAdv = gested.getList("advantages");
        assertTrue(gestedAdv.stream().noneMatch(a -> a.get("discountAmount") != null),
                "A 100% gestured basket carries no discount advantage (voucher/vignette/franco): " + gestedAdv);
        assertTrue(gestedAdv.stream().noneMatch(a -> a.get("suggestion") != null),
                "A gestured line never feeds the upsell pool, so no suggestion advantage: " + gestedAdv);
        assertFalse(rawBody.contains("availableToUpcell"),
                "CALIBRATION: availableToUpcell is @JsonIgnore'd and never serialized: " + rawBody);
        for (Map<String, Object> adv : gestedAdv) {
            if ("MEAL_VOUCHER".equals(adv.get("type"))) {
                assertMoney("0.00", new BigDecimal(String.valueOf(adv.get("totalEligibleAmount"))),
                        "The gestured line is invisible to the meal-voucher plate: " + adv);
            }
        }
    }

    // --------------------------------------------------
    // I5 — targeted multi-line gesture
    // --------------------------------------------------

    /**
     * I5 — a targeted gesture never spills to a neighbouring line of the same EAN. Two apple
     * {@code …001} lines: {@code L1} &times;3 with no gesture and {@code L2} &times;2 with
     * {@code manualDiscountAmount: 0.3}. Because the merge keys on the manual-gesture profile the
     * two lines stay apart, and {@code pickMatching} consumes {@code L2}'s own entry: exactly one
     * gesture offer covers quantity {@code 2.0} and restores only line {@code L2}, its amount the
     * catalog DEFAULT {@code 1.08} minus {@code 0.30} &times; 2 = {@code 1.56} TTC. {@code L1}
     * remains eligible — at least one non-gesture offer covers its {@code 001} quantity {@code 3.0}.
     */
    @Test
    void i5_targetedMultiLineGesture() {
        JsonPath body = valuate(basket("I5-multi", "0101",
                line("L1", "3300000000001", "3"),
                lineAmount("L2", "3300000000001", "2", "0.3")), 200).jsonPath();
        List<Integer> g = gestureOffers(body, "3300000000001");
        assertEquals(1, g.size(), "Exactly one gesture offer covers the gestured EAN");
        int idx = g.get(0);
        assertEquals("Manual Gesture: EAN=3300000000001 (amount -0.3)",
                body.getString("offers[" + idx + "].type"), "The fixed-amount literal type is pinned");
        assertEquals(2.0, offerQty(body, idx), 1e-9, "The gesture covers exactly the L2 quantity, never L1's");
        assertMoney("1.56", offerTtc(body, idx), "0.78 per unit (1.08 DEFAULT minus 0.30) over 2 units is 1.56");
        List<String> gestLines = body.getList("offers[" + idx + "].items.lineId");
        assertTrue(gestLines.contains("L2"), "The gesture restores its own source line L2: " + gestLines);
        assertFalse(gestLines.contains("L1"), "The gesture never spills onto the neighbouring line L1: " + gestLines);
        double l1Qty = 0.0;
        boolean sawNonGesture = false;
        int count = body.getList("offers").size();
        for (int i = 0; i < count; i++) {
            String type = body.getString("offers[" + i + "].type");
            if (type != null && type.startsWith("Manual Gesture:")) {
                continue;
            }
            List<Map<String, Object>> items = body.getList("offers[" + i + "].items");
            for (Map<String, Object> it : items) {
                if ("3300000000001".equals(it.get("produceEan"))) {
                    sawNonGesture = true;
                    l1Qty += ((Number) it.get("quantity")).doubleValue();
                }
            }
        }
        assertTrue(sawNonGesture, "L1 stays eligible: a non-gesture offer still prices the apple EAN");
        assertEquals(3.0, l1Qty, 1e-9, "The non-gesture offers cover exactly L1's quantity of 3.0, the gesture only L2's 2.0");
    }

    // --------------------------------------------------
    // I6 — per-unit gesture
    // --------------------------------------------------

    /**
     * I6 — the gesture is per unit, not a line lump sum. A {@code manualForcedPrice: 5.0} on a
     * quantity of {@code 2} of the olive oil {@code …006} yields {@code 10.00} TTC (5.00 &times;
     * 2), proving the forced price multiplies by the quantity rather than pricing the whole line
     * at a flat {@code 5.00}.
     */
    @Test
    void i6_perUnitGesture() {
        JsonPath body = valuate(basket("I6-perunit", "0101", forced("3300000000006", "2", "5.0")), 200).jsonPath();
        List<Integer> g = gestureOffers(body, "3300000000006");
        assertEquals(1, g.size(), "The per-unit forced price yields one gesture offer");
        int idx = g.get(0);
        assertEquals("Manual Gesture: EAN=3300000000006 (forced price 5.0)",
                body.getString("offers[" + idx + "].type"), "The forced-price literal type is pinned");
        assertEquals(2.0, offerQty(body, idx), 1e-9, "The gesture covers the full quantity of 2");
        assertMoney("10.00", offerTtc(body, idx), "A 5.00 forced price over 2 units is 10.00, not a 5.00 flat line");
    }
}
