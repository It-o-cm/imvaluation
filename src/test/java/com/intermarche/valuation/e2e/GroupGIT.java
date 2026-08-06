package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ValuationTrace;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group G — the {@code /valuation} contract — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application, HTTP Basic as {@code admin/admin} for the API. The whole group needs the
 * referential, so the mirror catalog (the {@code ImportAllClient} seed: stores 0101-0105,
 * four store groups, 33 products, nine families, ~96 valid prices from 2026-01-12, eleven
 * offers) is replayed ONCE at class start through the seven import endpoints in the mandated
 * order (Stores &rarr; StoreGroups &rarr; Products &rarr; ProductFamilies &rarr; Categories
 * &rarr; Prices &rarr; Offers).
 * <p>
 * TRANSVERSE GUARD — 4xx/5xx bodies of {@code /valuation} carry no entity: every textual
 * assertion for a rejected or failed call reads {@code valuation_traces.error_message}
 * (Panache under {@code QuarkusTransaction}), keyed by a unique {@code customerCode} per
 * probe, never the raw HTTP body. The 400 of a schema violation writes a {@code REJECTED}
 * trace; the 400 of a malformed JSON body (G7) writes NONE (the provider fails before the
 * method) — that distinction is asserted by counting traces, not by reading a body.
 * <p>
 * TRANSVERSE GUARD — {@code offers}/{@code advantages} are {@code HashSet}s: G5/G6 iterate
 * every element or find by predicate (a type prefix, a non-null discriminant), never by a
 * fixed index.
 * <p>
 * CALIBRATION — G3 (422 for unconsumed items) is UNREACHABLE through the contract. The basic
 * offer applier is a catch-all built for every unique priced EAN and consumes its whole
 * remaining quantity, so a schema-valid basket always empties {@code toEvaluate} (&rarr; 200)
 * or throws first (&rarr; 500). The only states that leave an item unconsumed — a null or
 * zero {@code quantity}, a null {@code produceEan} — are barred by the basket schema and
 * rejected at 400 before the engine runs. {@link #g3_unconsumedItems422IsUnreachable()} pins
 * that observed reality instead of a synthetic 422.
 * <p>
 * RESIDUE — the {@code [P]} sub-clause of G1 ({@code mustChangePassword=true} does not block
 * the API call) needs the prod-like enforcement filter, which {@code %test} disables; it is
 * documented in {@link #g1_apiChallengesInBasicAndAnyRoleValuates()} and reported as residue,
 * with no separate disabled method (G1's HTTP-only part is fully exercised).
 */
@QuarkusTest
class GroupGIT {

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
     * one mirror catalog. RestAssured's test port is wired per test instance, so seeding happens
     * in a {@code @BeforeEach} guarded by a static flag rather than in {@code @BeforeAll}; the
     * in-memory H2 base is created at boot and lives for the class run, so the guard runs the
     * imports exactly once.
     */
    @BeforeEach
    void seedCatalogOnce() {
        if (seeded) {
            return;
        }
        CatalogReset.resetMutableCatalog();
        for (String[] step : SEED) {
            given().auth().preemptive().basic("admin", "admin")
                    .contentType(ContentType.TEXT)
                    .body(readResource(step[1]))
                    .when().post(step[0])
                    .then().statusCode(200);
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
        try (var in = GroupGIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Posts a basket to {@code /valuation} as {@code admin/admin} and asserts the HTTP status.
     *
     * @param body           The raw basket JSON.
     * @param expectedStatus The expected HTTP status code.
     * @return The full response, for structural assertions on both the parsed tree and the raw
     *         body.
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
     * Counts all valuation traces, for the no-trace assertions of G7.
     *
     * @return The current trace row count.
     */
    private long traceCount() {
        long[] holder = new long[1];
        QuarkusTransaction.requiringNew().run(() -> holder[0] = ValuationTrace.count());
        return holder[0];
    }

    /**
     * Ensures a single-role account exists, in its own transaction.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @param role     The single role granted.
     */
    private void ensureUser(String username, String password, String role) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.findByUsername(username) != null) {
                return;
            }
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(password);
            user.setRoleSet(Set.of(role));
            user.displayName = username;
            user.active = true;
            user.mustChangePassword = false;
            user.persist();
        });
    }

    /**
     * A minimal single-line basket JSON for a given store and customer.
     *
     * @param customer The customer code stamped for trace lookup.
     * @param store    The store code.
     * @param ean      The product EAN of the single line.
     * @param quantity The line quantity, as a JSON literal.
     * @return The basket JSON string.
     */
    private String oneLine(String customer, String store, String ean, String quantity) {
        return String.format(
                "{\"customerCode\":\"%s\",\"storeCode\":\"%s\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"%s\",\"quantity\":%s}]}",
                customer, store, ean, quantity);
    }

    // --------------------------------------------------
    // G1 — Authentication
    // --------------------------------------------------

    /**
     * G1 — Authentication. Without an {@code Authorization} header {@code /valuation}
     * challenges in Basic with a bare 401 (never a 3xx redirect to the login page). Any
     * authenticated principal may value a basket: the endpoint carries no {@code @RolesAllowed},
     * so a VIEWER — the least-privileged role — gets a 200 on a valid basket. RESIDUE: the
     * {@code [P]} sub-clause "{@code mustChangePassword=true} does not block the call" needs the
     * prod-like enforcement filter (off in {@code %test}) and is not reproducible here.
     */
    @Test
    void g1_apiChallengesInBasicAndAnyRoleValuates() {
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body(oneLine("G1-anon", "0101", "3300000000002", "1"))
                .when().post("/valuation")
                .then().statusCode(401);
        ensureUser("g1viewer", "viewerpass1", AppUser.ROLE_VIEWER);
        given().auth().preemptive().basic("g1viewer", "viewerpass1")
                .contentType(ContentType.JSON)
                .body(oneLine("G1-viewer", "0101", "3300000000002", "1"))
                .when().post("/valuation")
                .then().statusCode(200);
    }

    // --------------------------------------------------
    // G2 — Schema rejections (400 + REJECTED trace)
    // --------------------------------------------------

    /**
     * G2 — schema rejections. Each malformed basket is answered with a 400 and pinned by a
     * {@code REJECTED} trace whose {@code error_message} starts with the literal
     * {@code "Error validating offer:"} (the networknt detail that follows is version-specific
     * and left uncalibrated, per the catalog); the offending field name is asserted as a stable
     * substring. Covered: empty items, absent items, absent and empty storeCode, zero and
     * negative quantity, out-of-range manual percent, off-enum delivery mode, out-of-range
     * latitude, and a negative vignette count.
     */
    @Test
    void g2_schemaViolationsAreRejectedAndTraced() {
        String[][] cases = {
                {"G2-items-empty",
                        "{\"customerCode\":\"G2-items-empty\",\"storeCode\":\"0101\",\"items\":[]}",
                        "items"},
                {"G2-items-absent",
                        "{\"customerCode\":\"G2-items-absent\",\"storeCode\":\"0101\"}",
                        "items"},
                {"G2-store-absent",
                        "{\"customerCode\":\"G2-store-absent\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1}]}",
                        "storeCode"},
                {"G2-store-empty",
                        "{\"customerCode\":\"G2-store-empty\",\"storeCode\":\"\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1}]}",
                        "storeCode"},
                {"G2-qty-zero",
                        "{\"customerCode\":\"G2-qty-zero\",\"storeCode\":\"0101\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":0}]}",
                        "quantity"},
                {"G2-qty-negative",
                        "{\"customerCode\":\"G2-qty-negative\",\"storeCode\":\"0101\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":-1}]}",
                        "quantity"},
                {"G2-percent-101",
                        "{\"customerCode\":\"G2-percent-101\",\"storeCode\":\"0101\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1,\"manualDiscountPercent\":101}]}",
                        "manualDiscountPercent"},
                {"G2-mode-drone",
                        "{\"customerCode\":\"G2-mode-drone\",\"storeCode\":\"0101\",\"deliveryMode\":\"DRONE\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1}]}",
                        "deliveryMode"},
                {"G2-lat-91",
                        "{\"customerCode\":\"G2-lat-91\",\"storeCode\":\"0101\",\"deliveryAddress\":{\"latitude\":91,\"longitude\":3},\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1}]}",
                        "latitude"},
                {"G2-vignette-neg",
                        "{\"customerCode\":\"G2-vignette-neg\",\"storeCode\":\"0101\",\"vignettes\":{\"3300000000002\":-1},\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1}]}",
                        "vignettes"},
        };
        for (String[] probe : cases) {
            valuate(probe[1], 400);
            TraceView trace = traceFor(probe[0]);
            assertTrue(trace.found, "A REJECTED trace must exist for " + probe[0]);
            assertEquals(ValuationTrace.STATUS_REJECTED, trace.status, "Status for " + probe[0]);
            assertEquals(400, trace.httpStatus, "HTTP status for " + probe[0]);
            assertNotNull(trace.errorMessage, "Error message for " + probe[0]);
            assertTrue(trace.errorMessage.startsWith("Error validating offer:"),
                    "Reject prefix for " + probe[0] + ": " + trace.errorMessage);
            assertTrue(trace.errorMessage.contains(probe[2]),
                    "Offending field for " + probe[0] + ": " + trace.errorMessage);
        }
    }

    /**
     * G2 — a field unknown to the basket schema is tolerated. Jackson drops the unknown
     * property on parse and the endpoint re-serializes the basket with NON_NULL inclusion, so
     * the extra key never reaches the validator: an otherwise valid basket carrying
     * {@code "loyaltyTier":"GOLD"} is valued normally (200, SUCCESS trace).
     */
    @Test
    void g2_unknownFieldIsTolerated() {
        String body = "{\"customerCode\":\"G2-unknown-field\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                + "\"loyaltyTier\":\"GOLD\",\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1}]}";
        valuate(body, 200);
        TraceView trace = traceFor("G2-unknown-field");
        assertTrue(trace.found, "A trace must exist for the tolerated-unknown-field basket");
        assertEquals(ValuationTrace.STATUS_SUCCESS, trace.status, "Unknown field must not reject the basket");
        assertEquals(200, trace.httpStatus, "Tolerated basket must be a 200");
    }

    // --------------------------------------------------
    // G3 — 422 unconsumed items (calibration)
    // --------------------------------------------------

    /**
     * G3 — the 422 "some items could not be processed" path is UNREACHABLE through the
     * contract. The basic applier is a catch-all that consumes every priced line, so the only
     * states that would leave {@code toEvaluate} non-empty — a missing/zero quantity, a null
     * EAN — are intercepted by the basket schema (400 REJECTED) before the engine runs, while a
     * fully valid basket always empties the working set (200 SUCCESS). This pins that observed
     * reality: the missing-quantity basket is a 400, and its valid twin is a 200 whose response
     * carries at least one standard offer line.
     */
    @Test
    void g3_unconsumedItems422IsUnreachable() {
        valuate("{\"customerCode\":\"G3-no-qty\",\"storeCode\":\"0101\","
                + "\"items\":[{\"produceEan\":\"3300000000002\"}]}", 400);
        TraceView rejected = traceFor("G3-no-qty");
        assertTrue(rejected.found, "The missing-quantity basket must be traced");
        assertEquals(ValuationTrace.STATUS_REJECTED, rejected.status,
                "A missing quantity is a schema reject, not a 422");
        assertEquals(400, rejected.httpStatus, "Missing quantity is a 400, never a 422");
        JsonPath ok = valuate(oneLine("G3-valid", "0101", "3300000000002", "1"), 200).jsonPath();
        List<String> types = ok.getList("offers.type");
        assertTrue(types.stream().anyMatch(t -> t.startsWith("Standard: EAN=")),
                "A valid basket consumes every line into standard offers: " + types);
        TraceView success = traceFor("G3-valid");
        assertEquals(ValuationTrace.STATUS_SUCCESS, success.status, "The valid twin must succeed");
    }

    // --------------------------------------------------
    // G4 — configuration errors (500 + FAILED trace, no payload)
    // --------------------------------------------------

    /**
     * G4 — configuration errors mapped to 500 with a {@code FAILED} trace and a null
     * {@code responsePayload}. Four literals from the catalog are pinned: an unknown EAN
     * (wrapped by the factory prefix), an unknown store (raised in the evaluation constructor,
     * so NOT wrapped), a date-only {@code priceDate} that fails ISO-8601 parsing, and a price
     * looked up before the seed's start date (no active price).
     */
    @Test
    void g4_configurationErrorsAreFailedAndTraced() {
        assertFailed("G4-unknown-ean",
                oneLine("G4-unknown-ean", "0101", "9999999999999", "1"),
                "Error building appliers from factory: Configuration Error: "
                        + "Product not found for EAN '9999999999999'");
        assertFailed("G4-unknown-store",
                oneLine("G4-unknown-store", "ZZZZ", "3300000000002", "1"),
                "Configuration Error: Store not found for code 'ZZZZ'");
        assertFailed("G4-bad-date",
                "{\"customerCode\":\"G4-bad-date\",\"storeCode\":\"0101\",\"items\":"
                        + "[{\"produceEan\":\"3300000000002\",\"quantity\":1,\"priceDate\":\"2026-01-12\"}]}",
                "Invalid date format '2026-01-12' for item EAN '3300000000002'. Expected ISO-8601 format.");
        assertFailed("G4-expired-price",
                "{\"customerCode\":\"G4-expired-price\",\"storeCode\":\"0101\",\"items\":"
                        + "[{\"produceEan\":\"3300000000002\",\"quantity\":1,\"priceDate\":\"2026-01-11T23:59:59\"}]}",
                "Configuration Error: No active price found for Product 'Lait UHT 1L'");
    }

    /**
     * Posts a basket expected to fail with a 500 and asserts the trace carries the literal.
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
                "Message for " + customer + " must contain <" + expectedFragment + ">, was: " + trace.errorMessage);
    }

    // --------------------------------------------------
    // G5 — shape of the 200 response
    // --------------------------------------------------

    /**
     * G5 — the shape of a 200 response. A full basket (two standard lines, a home delivery and
     * a deposit basket) is valued and its structure asserted by predicate: every offer carries
     * an {@code amount} (HT/TTC/rate) and a {@code type}; a Delivery and a Deposit Basket offer
     * expose an empty {@code items} list; {@code totalPrice.vatRate} is the blended sentinel
     * {@code 0.0000}; and {@code vatBreakdown} is present. The three advantage discriminants are
     * checked on the same response: a real discount exposes a non-null {@code discountAmount}, an
     * upsell exposes a non-null {@code suggestion}, and the meal voucher is the only advantage
     * whose {@code type} equals {@code "MEAL_VOUCHER"} (it is emitted whenever the basket holds an
     * eligible product — here the apples — independently of the threshold).
     * <p>
     * CALIBRATION — contrary to the catalog, the response carries NO {@code availableToUpcell}
     * key. The field is {@code @JsonIgnore} in {@code BasketEvaluation}, and a field-level ignore
     * masks its un-annotated public getter, so Jackson drops the property entirely; its absence
     * is pinned here.
     */
    @Test
    void g5_responseShapeAndAdvantageDiscriminants() {
        String full = "{\"customerCode\":\"G5-full\",\"storeCode\":\"0101\",\"deliveryMode\":\"HOME_DELIVERY\","
                + "\"deliveryAddress\":{\"latitude\":50.540,\"longitude\":3.030},"
                + "\"instructions\":[\"Deposit basket\"],"
                + "\"items\":[{\"produceEan\":\"3300000000002\",\"quantity\":1},"
                + "{\"produceEan\":\"3300000000001\",\"quantity\":2}]}";
        io.restassured.response.Response fullResponse = valuate(full, 200);
        JsonPath body = fullResponse.jsonPath();
        String rawBody = fullResponse.asString();
        int offerCount = body.getList("offers").size();
        assertTrue(offerCount > 0, "A full basket must produce at least one offer");
        for (int i = 0; i < offerCount; i++) {
            assertNotNull(body.getString("offers[" + i + "].type"), "Every offer carries a type");
            assertNotNull(body.getString("offers[" + i + "].amount.amountExcludingTax"), "Every offer carries HT");
            assertNotNull(body.getString("offers[" + i + "].amount.amountIncludingTax"), "Every offer carries TTC");
            assertNotNull(body.getString("offers[" + i + "].amount.vatRate"), "Every offer carries a rate");
            assertNotNull(body.getList("offers[" + i + "].items"), "Every offer carries an items list");
        }
        assertServiceOfferHasNoItems(body, "Delivery:");
        assertServiceOfferHasNoItems(body, "Deposit Basket:");
        assertEquals(0, new BigDecimal(body.getString("totalPrice.vatRate")).compareTo(BigDecimal.ZERO),
                "totalPrice.vatRate is always the blended zero");
        assertTrue(rawBody.contains("\"vatRate\":0.0000"),
                "totalPrice.vatRate is serialized as the 0.0000 scale-4 sentinel: " + rawBody);
        assertTrue(body.getList("vatBreakdown").size() > 0, "A priced basket carries a VAT breakdown");
        assertNull(body.getMap("availableToUpcell"),
                "CALIBRATION: availableToUpcell is @JsonIgnore'd and absent from the response: " + rawBody);
        assertFalse(rawBody.contains("availableToUpcell"),
                "The availableToUpcell key must not be serialized: " + rawBody);
        List<Map<String, Object>> advantages = body.getList("advantages");
        assertTrue(advantages.stream().anyMatch(a -> a.get("discountAmount") != null),
                "A real discount exposes a non-null discountAmount: " + advantages);
        assertTrue(advantages.stream().anyMatch(a -> a.get("suggestion") != null),
                "An upsell exposes a non-null suggestion: " + advantages);
        assertTrue(advantages.stream().anyMatch(a -> "MEAL_VOUCHER".equals(a.get("type"))),
                "The meal voucher is the only advantage typed MEAL_VOUCHER: " + advantages);
    }

    /**
     * Asserts that at least one offer whose type starts with the given prefix carries an empty
     * items list, and that every such offer does.
     *
     * @param body   The parsed evaluation.
     * @param prefix The offer-type prefix identifying a service offer (Delivery/Deposit).
     */
    private void assertServiceOfferHasNoItems(JsonPath body, String prefix) {
        int offerCount = body.getList("offers").size();
        boolean seen = false;
        for (int i = 0; i < offerCount; i++) {
            String type = body.getString("offers[" + i + "].type");
            if (type != null && type.startsWith(prefix)) {
                seen = true;
                assertTrue(body.getList("offers[" + i + "].items").isEmpty(),
                        "A " + prefix + " offer carries no priced items: " + type);
            }
        }
        assertTrue(seen, "The full basket must produce a " + prefix + " offer");
    }

    // --------------------------------------------------
    // G6 — structural invariants
    // --------------------------------------------------

    /**
     * G6 — structural invariants on a valued basket (two apples and one milk on 0101, which
     * yields standard lines, an immediate-voucher discount and an upsell). For every offer that
     * covers priced items, the sum of {@code items[].amount} equals the offer {@code amount} to
     * the cent; an offer with no items must carry a non-zero amount; every VAT breakdown line
     * has {@code vatAmount = TTC - HT}; the breakdown is ordered by strictly increasing rate and
     * its TTC sums to {@code totalPrice}; and no priced item ever carries the blended
     * {@code 0.0000} rate — only real product rates.
     */
    @Test
    void g6_structuralInvariantsHold() {
        String body = "{\"customerCode\":\"G6-invariants\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                + "\"items\":[{\"produceEan\":\"3300000000001\",\"quantity\":2},"
                + "{\"produceEan\":\"3300000000002\",\"quantity\":1}]}";
        JsonPath ev = valuate(body, 200).jsonPath();
        int offerCount = ev.getList("offers").size();
        for (int i = 0; i < offerCount; i++) {
            BigDecimal offerTtc = new BigDecimal(ev.getString("offers[" + i + "].amount.amountIncludingTax"));
            int itemCount = ev.getList("offers[" + i + "].items").size();
            if (itemCount == 0) {
                assertNotEquals(0, offerTtc.compareTo(BigDecimal.ZERO),
                        "An offer without items must carry a non-zero amount: "
                                + ev.getString("offers[" + i + "].type"));
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < itemCount; j++) {
                sum = sum.add(new BigDecimal(ev.getString("offers[" + i + "].items[" + j + "].amount.amountIncludingTax")));
                assertNotEquals("0.0000", ev.getString("offers[" + i + "].items[" + j + "].amount.vatRate"),
                        "A priced item never carries the blended rate: "
                                + ev.getString("offers[" + i + "].type"));
            }
            assertEquals(0, offerTtc.compareTo(sum),
                    "Sum of items must equal the offer amount for " + ev.getString("offers[" + i + "].type"));
        }
        int rateCount = ev.getList("vatBreakdown").size();
        BigDecimal breakdownTtc = BigDecimal.ZERO;
        BigDecimal previousRate = null;
        for (int k = 0; k < rateCount; k++) {
            BigDecimal rate = new BigDecimal(ev.getString("vatBreakdown[" + k + "].vatRate"));
            BigDecimal ht = new BigDecimal(ev.getString("vatBreakdown[" + k + "].amountExcludingTax"));
            BigDecimal ttc = new BigDecimal(ev.getString("vatBreakdown[" + k + "].amountIncludingTax"));
            BigDecimal vat = new BigDecimal(ev.getString("vatBreakdown[" + k + "].vatAmount"));
            assertEquals(0, vat.compareTo(ttc.subtract(ht)),
                    "vatAmount must equal TTC - HT on breakdown line " + k);
            if (previousRate != null) {
                assertTrue(rate.compareTo(previousRate) > 0,
                        "VAT breakdown must be strictly increasing by rate");
            }
            previousRate = rate;
            breakdownTtc = breakdownTtc.add(ttc);
        }
        BigDecimal totalTtc = new BigDecimal(ev.getString("totalPrice.amountIncludingTax"));
        assertEquals(0, breakdownTtc.compareTo(totalTtc),
                "The VAT breakdown TTC must sum to the total price");
    }

    // --------------------------------------------------
    // G7 — transport
    // --------------------------------------------------

    /**
     * G7 — transport. A {@code text/plain} body is refused with a 415 before the method runs; a
     * syntactically invalid JSON body under {@code application/json} is a 400 raised by the
     * Jackson provider, again before the method. Neither writes a trace — the contract's
     * "400 with a trace / 400 without a trace" distinction — so the trace count is unchanged
     * across both, unlike the schema rejects of G2 which do record.
     */
    @Test
    void g7_transportFailuresLeaveNoTrace() {
        long before = traceCount();
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT)
                .body(oneLine("G7-plain", "0101", "3300000000002", "1"))
                .when().post("/valuation")
                .then().statusCode(415);
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON)
                .body("{\"storeCode\":\"0101\", oops not json")
                .when().post("/valuation")
                .then().statusCode(400);
        assertEquals(before, traceCount(),
                "Neither a 415 nor a provider 400 may record a valuation trace");
    }

    /**
     * G1 [P] residue — {@code mustChangePassword=true} does not block the API call. This needs
     * the prod-like password-change enforcement filter, disabled by
     * {@code %test.app.password-change.enforced=false}; with no prod-like harness it cannot be
     * reproduced. Documented, not implemented.
     */
    @Test
    @Disabled("[P] requires the prod-like profile (password-change enforcement disabled in %test)")
    void g1_mustChangePasswordDoesNotBlockApi() {
    }
}
