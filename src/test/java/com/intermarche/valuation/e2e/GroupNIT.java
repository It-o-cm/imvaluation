package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.ValuationEngine;
import com.intermarche.valuation.engine.ValuationTraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group N — Traces &amp; administration des valorisations — of e2e-scenarios.md.
 * <p>
 * Every scenario runs over real HTTP against the in-JVM {@code @QuarkusTest} application, HTTP
 * Basic as {@code admin/admin}. The subjects under test are {@link ValuationTraceService} (the
 * recorder and its purges), {@link ValuationTraceConfig} (the live-read on/off + retention) and
 * the administration screens of {@code ValuationUiResource} (list, detail, test form, config,
 * purge). The engine that feeds the traces is priced against the real seeds, so the mirror
 * catalog is replayed ONCE at class start through the seven import endpoints in the mandated
 * order (Stores &rarr; StoreGroups &rarr; Products &rarr; ProductFamilies &rarr; Categories
 * &rarr; Prices &rarr; Offers).
 * <p>
 * TRACE-CONTENT ASSERTIONS — a trace's columns are read back with Panache under
 * {@code QuarkusTransaction}, keyed by a unique {@code customerCode} per probe, never from a raw
 * HTTP body ({@code 4xx/5xx} bodies of {@code /valuation} carry no entity). Redirects
 * ({@code /config}, {@code /purge}, unknown detail id) are never followed: the {@code 303}
 * {@code Location} notice is asserted with {@code containsString}, spaces URL-encoded as
 * {@code +}.
 * <p>
 * SHARED STATE — the trace configuration is a single JVM-wide row and the trace table is shared
 * by the whole suite. Every recording probe forces {@code enabled=true} through Panache before
 * it runs, {@code N2} restores it, and {@code N4}'s manual purge legitimately empties the table
 * (each probe records-then-asserts on its own {@code customerCode} within one sequential method,
 * so a later purge cannot race an earlier assertion). {@link DateTimeProvider} is cleared in a
 * {@code finally} block wherever it is fixed.
 * <p>
 * CALIBRATION FINDINGS (catalog vs observed code):
 * <ul>
 *   <li>N1 — the {@code 422 FAILED with responsePayload} arm is UNREACHABLE through the HTTP
 *       contract (the basic applier is a catch-all: a schema-valid basket empties the working
 *       set to a {@code 200}, and every unconsumable state is barred by the schema to a
 *       {@code 400} or throws first to a {@code 500}; confirmed by Group G's G3 calibration).
 *       Its trace-content contract — a payload-bearing FAILED, and the {@code >2000}-char
 *       {@code errorMessage} truncated to {@code 1997 + "..."} — is therefore exercised through
 *       the injected {@link ValuationTraceService#record} with a genuine engine evaluation, the
 *       only faithful way to reach the {@code 422} branch of the recorder.</li>
 *   <li>N1 — {@code requestPayload} is the RE-SERIALIZED basket ({@code NON_NULL}), not the raw
 *       body: an unknown field sent over the wire is dropped by JAX-RS deserialization and never
 *       reappears in the re-serialized payload. Pinned directly.</li>
 *   <li>N5/N7 — the {@code [W]} sub-clauses are browser-only: the {@code 1 s} auto-refresh timer
 *       (N5) and the {@code Form}/{@code JSON} toggle plus the client-rendered {@code Readable}
 *       panel and its real em-dash placeholder (N6/N7) run in the page's JavaScript. Their
 *       SERVER surface is fully covered here over HTTP — the {@code /rows} refresh data source,
 *       the config/purge/test/replay endpoints and every server-rendered literal — and the pure
 *       JavaScript behaviours are listed as justified {@code @Disabled} residue (covered by the
 *       Vitest suite on the browser sources).</li>
 * </ul>
 */
@QuarkusTest
class GroupNIT {

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
     * A priced product line valued on store {@code 0101}: the Lait UHT 1L, priced by the seed and
     * consumed by the base offer, so a one-line basket over it always answers {@code 200}.
     */
    private static final String LAIT_EAN = "3300000000002";

    /**
     * Whether the mirror catalog has been imported in this class run.
     */
    private static boolean seeded;

    /**
     * The valuation engine, used to build a genuine evaluation for the injected-recorder probe of
     * N1's HTTP-unreachable {@code 422} arm.
     */
    @Inject
    ValuationEngine engine;

    /**
     * The trace recorder, used directly for the {@code 422}/truncation branches and the scheduled
     * purge that no HTTP path can reach.
     */
    @Inject
    ValuationTraceService traceService;

    /**
     * Replays the seven CSV imports once, before the first scenario, so the whole group shares one
     * priced catalog.
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
        try (var in = GroupNIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing seed resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read seed resource: " + path, e);
        }
    }

    // --------------------------------------------------
    // HTTP helpers
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
     * @return The full response.
     */
    private Response valuate(String body, int expectedStatus) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/valuation")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    /**
     * Fetches an administration page as {@code admin/admin} and returns its rendered body.
     *
     * @param path The page path, query string included.
     * @return The HTML body.
     */
    private String getHtml(String path) {
        return given().auth().preemptive().basic("admin", "admin")
                .when().get(path)
                .then().statusCode(200)
                .extract().asString();
    }

    /**
     * Builds a one-line {@code IN_STORE} basket JSON.
     *
     * @param customer The customer code.
     * @param store    The store code.
     * @param ean      The product EAN.
     * @param qty      The quantity, as a JSON literal.
     * @return The basket JSON string.
     */
    private static String oneLine(String customer, String store, String ean, String qty) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\",\"items\":[{\"produceEan\":\"" + ean
                + "\",\"quantity\":" + qty + "}]}";
    }

    // --------------------------------------------------
    // Trace + config reading helpers (Panache under QuarkusTransaction)
    // --------------------------------------------------

    /**
     * A projection of a trace's columns, captured inside the transaction so it survives the closed
     * session.
     */
    private static final class TraceView {

        /**
         * Whether a trace was found for the probed customer code.
         */
        boolean found;

        /**
         * The recorded status.
         */
        String status;

        /**
         * The recorded HTTP status.
         */
        Integer httpStatus;

        /**
         * The recorded error message.
         */
        String errorMessage;

        /**
         * The copied store code.
         */
        String storeCode;

        /**
         * The copied customer code.
         */
        String customerCode;

        /**
         * The submitted line count.
         */
        Integer itemCount;

        /**
         * The measured duration.
         */
        Long durationMs;

        /**
         * Whether a response payload was stored.
         */
        boolean hasResponse;

        /**
         * The recorded total including tax.
         */
        BigDecimal totalIncludingTax;

        /**
         * The re-serialized request payload.
         */
        String requestPayload;
    }

    /**
     * Loads the latest trace for a customer code and projects its columns.
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
                view.storeCode = trace.storeCode;
                view.customerCode = trace.customerCode;
                view.itemCount = trace.itemCount;
                view.durationMs = trace.durationMs;
                view.hasResponse = trace.responsePayload != null;
                view.totalIncludingTax = trace.totalIncludingTax;
                view.requestPayload = trace.requestPayload;
            }
        });
        return view;
    }

    /**
     * Counts the traces recorded for a customer code.
     *
     * @param customerCode The customer code to count.
     * @return The number of matching traces.
     */
    private long countTracesFor(String customerCode) {
        long[] count = {0};
        QuarkusTransaction.requiringNew().run(() ->
                count[0] = ValuationTrace.count("customerCode = ?1", customerCode));
        return count[0];
    }

    /**
     * Counts every recorded trace.
     *
     * @return The total number of traces.
     */
    private long totalTraceCount() {
        long[] count = {0};
        QuarkusTransaction.requiringNew().run(() -> count[0] = ValuationTrace.count());
        return count[0];
    }

    /**
     * Returns the identifier of the latest trace for a customer code.
     *
     * @param customerCode The customer code to resolve.
     * @return The trace identifier.
     */
    private Long traceIdFor(String customerCode) {
        Long[] id = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = ValuationTrace
                    .find("customerCode = ?1 order by createdAt desc", customerCode)
                    .firstResult();
            assertNotNull(trace, "Expected a trace for " + customerCode);
            id[0] = trace.id;
        });
        return id[0];
    }

    /**
     * Forces the recording flag through Panache, so a probe is isolated from the shared config.
     *
     * @param on Whether recording must be on.
     */
    private void setRecording(boolean on) {
        QuarkusTransaction.requiringNew().run(() -> ValuationTraceConfig.current().enabled = on);
    }

    /**
     * Sets the retention days through Panache.
     *
     * @param days The retention to install.
     */
    private void setRetention(int days) {
        QuarkusTransaction.requiringNew().run(() -> ValuationTraceConfig.current().retentionDays = days);
    }

    /**
     * Reads the current recording flag through Panache.
     *
     * @return Whether recording is on.
     */
    private boolean recordingEnabled() {
        boolean[] enabled = {false};
        QuarkusTransaction.requiringNew().run(() -> enabled[0] = ValuationTraceConfig.current().enabled);
        return enabled[0];
    }

    /**
     * Reads the current retention days through Panache.
     *
     * @return The configured retention.
     */
    private int retentionDays() {
        int[] days = {0};
        QuarkusTransaction.requiringNew().run(() -> days[0] = ValuationTraceConfig.current().retentionDays);
        return days[0];
    }

    /**
     * Ensures an account exists with the given single role, creating it when absent.
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

    // --------------------------------------------------
    // N1 — the four statuses, exact content
    // --------------------------------------------------

    /**
     * N1 — a {@code 200} records a {@code SUCCESS} trace whose content is exact: {@code errorMessage}
     * is null, a {@code responsePayload} and a {@code totalIncludingTax} are stored, and the context
     * columns ({@code itemCount}, {@code storeCode}, {@code customerCode}, {@code durationMs}) are
     * posted. The {@code requestPayload} is the RE-SERIALIZED basket, not the raw body: a
     * {@code mysteryField} sent over the wire is absent from it, while the real fields survive.
     */
    @Test
    void n1_successTraceContentAndReserializedRequest() {
        setRecording(true);
        String body = "{\"customerCode\":\"N1-succ\",\"storeCode\":\"0101\","
                + "\"deliveryMode\":\"IN_STORE\",\"items\":[{\"produceEan\":\"" + LAIT_EAN
                + "\",\"quantity\":1}],\"mysteryField\":\"ghost\"}";
        valuate(body, 200);
        TraceView trace = traceFor("N1-succ");
        assertTrue(trace.found, "A SUCCESS trace must exist");
        assertEquals(ValuationTrace.STATUS_SUCCESS, trace.status, "The 200 arm is SUCCESS");
        assertEquals(200, trace.httpStatus, "The recorded HTTP status is 200");
        assertNull(trace.errorMessage, "A SUCCESS trace carries no error message");
        assertTrue(trace.hasResponse, "A SUCCESS trace stores the response payload");
        assertNotNull(trace.totalIncludingTax, "A SUCCESS trace stores the total including tax");
        assertEquals(Integer.valueOf(1), trace.itemCount, "The single line is counted");
        assertEquals("0101", trace.storeCode, "The store code is copied out of the basket");
        assertEquals("N1-succ", trace.customerCode, "The customer code is copied out of the basket");
        assertNotNull(trace.durationMs, "The duration is measured");
        assertFalse(trace.requestPayload.contains("mysteryField"),
                "The request payload is re-serialized: the unknown field is dropped: " + trace.requestPayload);
        assertTrue(trace.requestPayload.contains("0101"),
                "The re-serialized payload keeps the real fields: " + trace.requestPayload);
    }

    /**
     * N1 — a {@code 400} records a {@code REJECTED} trace whose evaluation is null (no
     * {@code responsePayload}) while an {@code errorMessage} is present. Triggered by a
     * schema-invalid basket (no {@code storeCode}, which the schema requires), rejected before the
     * engine runs.
     */
    @Test
    void n1_rejectedTraceContent() {
        setRecording(true);
        valuate("{\"customerCode\":\"N1-rej\",\"items\":[{\"produceEan\":\"" + LAIT_EAN
                + "\",\"quantity\":1}]}", 400);
        TraceView trace = traceFor("N1-rej");
        assertTrue(trace.found, "A REJECTED trace must exist");
        assertEquals(ValuationTrace.STATUS_REJECTED, trace.status, "The 400 arm is REJECTED");
        assertEquals(400, trace.httpStatus, "The recorded HTTP status is 400");
        assertNotNull(trace.errorMessage, "A REJECTED trace carries the validation message");
        assertFalse(trace.hasResponse, "A REJECTED trace has a null evaluation, so no response payload");
        assertNull(trace.totalIncludingTax, "A REJECTED trace stores no total");
    }

    /**
     * N1 — a {@code 500} records a {@code FAILED} trace WITHOUT a {@code responsePayload}, with an
     * {@code errorMessage} present. Triggered by an unknown EAN, a configuration error the engine
     * raises before producing any evaluation.
     */
    @Test
    void n1_failedNoPayloadTraceContent() {
        setRecording(true);
        valuate(oneLine("N1-fail", "0101", "9999999999999", "1"), 500);
        TraceView trace = traceFor("N1-fail");
        assertTrue(trace.found, "A FAILED trace must exist");
        assertEquals(ValuationTrace.STATUS_FAILED, trace.status, "The 500 arm is FAILED");
        assertEquals(500, trace.httpStatus, "The recorded HTTP status is 500");
        assertNotNull(trace.errorMessage, "A FAILED trace carries the error message");
        assertFalse(trace.hasResponse, "A 500 FAILED trace carries no response payload");
    }

    /**
     * N1 — the {@code 422 FAILED with responsePayload} arm and the {@code >2000}-char truncation.
     * Both are HTTP-unreachable (see the class calibration), so they are exercised through the
     * injected {@link ValuationTraceService#record} with a GENUINE engine evaluation: the recorder
     * stores a non-null {@code responsePayload} for the payload-bearing FAILED, and shortens an
     * over-long {@code errorMessage} to exactly {@code 1997} characters plus a trailing
     * {@code "..."} (2000 total).
     */
    @Test
    void n1_failedWithPayloadAndTruncation() {
        setRecording(true);
        String longMessage = "x".repeat(2500);
        String expectedTruncated = longMessage.substring(0, 1997) + "...";
        QuarkusTransaction.requiringNew().run(() -> {
            Basket basket = new Basket();
            basket.customerCode = "N1-422";
            basket.storeCode = "0101";
            basket.deliveryMode = "IN_STORE";
            Basket.Item item = new Basket.Item();
            item.produceEan = LAIT_EAN;
            item.quantity = 1.0;
            Basket.Item second = new Basket.Item();
            second.produceEan = LAIT_EAN;
            second.quantity = 1.0;
            basket.items = List.of(item, second);
            BasketEvaluation evaluation = engine.evaluate(basket);
            traceService.record("{\"customerCode\":\"N1-422\"}", basket, evaluation, 422,
                    ValuationTrace.STATUS_FAILED, longMessage, 7L);
        });
        TraceView trace = traceFor("N1-422");
        assertTrue(trace.found, "The recorder must persist the 422 trace");
        assertEquals(ValuationTrace.STATUS_FAILED, trace.status, "A 422 is a FAILED status");
        assertEquals(422, trace.httpStatus, "The recorded HTTP status is 422");
        assertTrue(trace.hasResponse, "A 422 FAILED trace KEEPS its response payload");
        assertEquals(Integer.valueOf(2), trace.itemCount, "Both lines are counted");
        assertEquals(Long.valueOf(7L), trace.durationMs, "The supplied duration is stored");
        assertEquals(2000, trace.errorMessage.length(), "The error message is truncated to 2000 chars");
        assertTrue(trace.errorMessage.endsWith("..."), "The truncated message ends with an ellipsis");
        assertEquals(expectedTruncated, trace.errorMessage, "1997 kept chars plus the ellipsis");
    }

    // --------------------------------------------------
    // N2 — recording turned off
    // --------------------------------------------------

    /**
     * N2 — disabling recording (the {@code enabled} checkbox unchecked, so its form param is absent)
     * lets valuations run normally but leaves ZERO trace; the list screen shows the banner
     * {@code Recording is turned off: valuations run normally but leave no trace.}; re-enabling takes
     * effect immediately (the config is re-read on every call, no restart), so the very next
     * valuation records a trace again.
     */
    @Test
    void n2_disableLeavesNoTraceBannerAndImmediateReenable() {
        try {
            setRecording(true);
            given().auth().preemptive().basic("admin", "admin")
                    .contentType(ContentType.URLENC)
                    .formParam("retentionDays", 1)
                    .redirects().follow(false)
                    .when().post("/ui/valuations/config")
                    .then().statusCode(303)
                    .header("Location", containsString("Tracing+configuration+updated."));
            assertFalse(recordingEnabled(), "The absent checkbox turns recording off");
            valuate(oneLine("N2OFF", "0101", LAIT_EAN, "1"), 200);
            assertEquals(0, countTracesFor("N2OFF"), "A disabled recorder leaves no trace");
            String list = getHtml("/ui/valuations");
            assertTrue(list.contains("Recording is turned off: valuations run normally but leave no trace."),
                    "The off banner is shown on the list screen");
            given().auth().preemptive().basic("admin", "admin")
                    .contentType(ContentType.URLENC)
                    .formParam("enabled", "on")
                    .formParam("retentionDays", 1)
                    .redirects().follow(false)
                    .when().post("/ui/valuations/config")
                    .then().statusCode(303)
                    .header("Location", containsString("Tracing+configuration+updated."));
            assertTrue(recordingEnabled(), "The checked box turns recording back on");
            valuate(oneLine("N2ON", "0101", LAIT_EAN, "1"), 200);
            assertTrue(countTracesFor("N2ON") >= 1,
                    "Re-enabling is immediate: the next valuation is traced without a restart");
        } finally {
            setRecording(true);
        }
    }

    // --------------------------------------------------
    // N3 — configuration validation and role guards
    // --------------------------------------------------

    /**
     * N3 — the config form validates the retention and is ADMIN-only. A {@code retentionDays} of
     * {@code 0} is refused with the red notice {@code The retention must be at least one day.} (a
     * {@code 303} carrying {@code noticeOk=false}); a valid retention succeeds with
     * {@code Tracing configuration updated.} ({@code noticeOk=true}) and is actually persisted; a
     * VIEWER and a MANAGER are forbidden ({@code 403}) on both the config and the purge POSTs, while
     * ADMIN is allowed.
     */
    @Test
    void n3_configValidationAndRoleGuards() {
        try {
            ensureUser("n3viewer", "viewerpass1", AppUser.ROLE_VIEWER);
            ensureUser("n3manager", "managerpass1", AppUser.ROLE_MANAGER);
            given().auth().preemptive().basic("admin", "admin")
                    .contentType(ContentType.URLENC)
                    .formParam("enabled", "on")
                    .formParam("retentionDays", 0)
                    .redirects().follow(false)
                    .when().post("/ui/valuations/config")
                    .then().statusCode(303)
                    .header("Location", containsString("The+retention+must+be+at+least+one+day."))
                    .header("Location", containsString("noticeOk=false"));
            given().auth().preemptive().basic("admin", "admin")
                    .contentType(ContentType.URLENC)
                    .formParam("enabled", "on")
                    .formParam("retentionDays", 7)
                    .redirects().follow(false)
                    .when().post("/ui/valuations/config")
                    .then().statusCode(303)
                    .header("Location", containsString("Tracing+configuration+updated."))
                    .header("Location", containsString("noticeOk=true"));
            assertEquals(7, retentionDays(), "The valid retention is persisted");
            assertTrue(recordingEnabled(), "The enabled flag is persisted");
            for (String[] user : new String[][]{{"n3viewer", "viewerpass1"}, {"n3manager", "managerpass1"}}) {
                given().auth().preemptive().basic(user[0], user[1])
                        .contentType(ContentType.URLENC)
                        .formParam("enabled", "on")
                        .formParam("retentionDays", 7)
                        .when().post("/ui/valuations/config")
                        .then().statusCode(403);
                given().auth().preemptive().basic(user[0], user[1])
                        .when().post("/ui/valuations/purge")
                        .then().statusCode(403);
            }
        } finally {
            setRetention(1);
            setRecording(true);
        }
    }

    // --------------------------------------------------
    // N4 — purges
    // --------------------------------------------------

    /**
     * N4 — {@code Purge all now} deletes every recorded valuation and answers a {@code 303} carrying
     * {@code {n} trace(s) deleted.}. At least one trace is recorded first, then the ADMIN purge
     * empties the whole table.
     */
    @Test
    void n4_manualPurgeAll() {
        setRecording(true);
        valuate(oneLine("N4PURGE", "0101", LAIT_EAN, "1"), 200);
        assertTrue(totalTraceCount() > 0, "There is at least one trace to purge");
        given().auth().preemptive().basic("admin", "admin")
                .redirects().follow(false)
                .when().post("/ui/valuations/purge")
                .then().statusCode(303)
                .header("Location", containsString("deleted."))
                .header("Location", containsString("noticeOk=true"));
        assertEquals(0, totalTraceCount(), "Purge all empties the trace table");
    }

    /**
     * N4 — the scheduled purge deletes the traces older than {@code now − retentionDays}. The
     * threshold reads the real {@code LocalDateTime.now()} (not mockable), so the strategy is to
     * antedate a trace's {@code createdAt} by fixing {@link DateTimeProvider} at insertion, then
     * invoke the recorder's scheduled pass directly: the antedated trace is removed while a
     * freshly-created one survives.
     */
    @Test
    void n4_scheduledPurgeRemovesExpiredKeepsFresh() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.now().minusDays(10));
            QuarkusTransaction.requiringNew().run(() -> {
                ValuationTrace old = new ValuationTrace();
                old.status = ValuationTrace.STATUS_SUCCESS;
                old.customerCode = "N4OLD";
                old.storeCode = "0101";
                old.httpStatus = 200;
                old.persist();
            });
            DateTimeProvider.clear();
            QuarkusTransaction.requiringNew().run(() -> {
                ValuationTrace fresh = new ValuationTrace();
                fresh.status = ValuationTrace.STATUS_SUCCESS;
                fresh.customerCode = "N4NEW";
                fresh.storeCode = "0101";
                fresh.httpStatus = 200;
                fresh.persist();
            });
            setRetention(1);
            traceService.purgeExpired();
            assertEquals(0, countTracesFor("N4OLD"), "The trace older than the retention is purged");
            assertTrue(countTracesFor("N4NEW") >= 1, "A fresh trace survives the scheduled purge");
        } finally {
            DateTimeProvider.clear();
            setRetention(1);
        }
    }

    // --------------------------------------------------
    // N5 — the list screen
    // --------------------------------------------------

    /**
     * N5 — the list filters ({@code store} contains, {@code customer} contains, {@code status}
     * strict), the status badges ({@code Success} / {@code FAILED}) and the empty state. A SUCCESS
     * and a FAILED trace on distinct unique customers are recorded, then: the customer filter finds
     * the SUCCESS row with a {@code Success} badge; the store filter matches by fragment; a
     * mismatched status filter yields the empty state; the FAILED filter shows the {@code FAILED}
     * badge; an impossible customer yields {@code No valuation recorded yet.} and its hint.
     */
    @Test
    void n5_listFiltersBadgesAndEmptyState() {
        setRecording(true);
        valuate(oneLine("N5CUSTOK", "0101", LAIT_EAN, "1"), 200);
        valuate(oneLine("N5CUSTFAIL", "0101", "9999999999999", "1"), 500);
        String byCustomer = getHtml("/ui/valuations?customer=N5CUSTOK");
        assertTrue(byCustomer.contains("N5CUSTOK"), "The customer filter finds the row");
        assertTrue(byCustomer.contains("badge-ok\">Success"), "The SUCCESS trace shows the Success badge");
        String byStore = getHtml("/ui/valuations?store=010&customer=N5CUSTOK");
        assertTrue(byStore.contains("badge-ok\">Success"),
                "The store filter matches 0101 by the fragment 010 and shows the row");
        String mismatch = getHtml("/ui/valuations?customer=N5CUSTOK&status=FAILED");
        assertTrue(mismatch.contains("No valuation recorded yet."),
                "A strict status mismatch hides the SUCCESS row: the list falls back to the empty state");
        String byFail = getHtml("/ui/valuations?customer=N5CUSTFAIL");
        assertTrue(byFail.contains("badge-error\">FAILED"), "The FAILED trace shows the FAILED badge");
        String empty = getHtml("/ui/valuations?customer=ZZZNOSUCHCUSTOMERZZZ");
        assertTrue(empty.contains("No valuation recorded yet."), "An impossible filter is empty");
        assertTrue(empty.contains("Submit a test basket, or wait for a client to call the endpoint."),
                "The empty state shows its hint");
    }

    /**
     * N5 — the auto-refresh DATA SOURCE. The {@code 1 s} timer is browser-only, but it polls the
     * server {@code /ui/valuations/rows} endpoint, which returns only the {@code tbody} rows
     * fragment for the current filters — the same rows as the full page, without the page chrome.
     * This pins that server surface; the JavaScript timer itself is listed as residue below.
     */
    @Test
    void n5_rowsRefreshFragment() {
        setRecording(true);
        valuate(oneLine("N5ROWS", "0101", LAIT_EAN, "1"), 200);
        String rows = getHtml("/ui/valuations/rows?customer=N5ROWS");
        assertTrue(rows.contains("N5ROWS"), "The refresh fragment carries the matching row");
        assertFalse(rows.contains("<h1>Valuations</h1>"), "The fragment omits the page chrome");
    }

    /**
     * N5 [W] residue — the {@code 1 s} auto-refresh timer replacing the {@code tbody} (suspended on
     * {@code document.hidden}, in-flight requests not doubled) is a pure browser behaviour driven by
     * {@code valuation-refresh.js}; its server data source is covered by
     * {@link #n5_rowsRefreshFragment()} and its DOM logic by the Vitest suite.
     */
    @Test
    @Disabled("[W] the 1 s valuations auto-refresh is browser-only (valuation-refresh.js); server /rows source is covered")
    void n5_autoRefreshTimer() {
    }

    // --------------------------------------------------
    // N6 — the detail screen
    // --------------------------------------------------

    /**
     * N6 — the detail screen shows the meta (Status/HTTP/Duration/Total), a red banner for
     * {@code errorMessage}, {@code No response was produced.} when there is no response, and a
     * {@code 303} back to the list with the notice {@code Valuation {id} no longer exists.} for an
     * unknown id. A SUCCESS detail shows the {@code Success} badge and the money/duration units; a
     * FAILED (no-payload) detail shows the error banner and the no-response placeholder.
     */
    @Test
    void n6_detailMetaErrorBannerNoResponseAndUnknownId() {
        setRecording(true);
        valuate(oneLine("N6OK", "0101", LAIT_EAN, "1"), 200);
        Long okId = traceIdFor("N6OK");
        String okDetail = getHtml("/ui/valuations/" + okId);
        assertTrue(okDetail.contains("badge-ok\">Success"), "The SUCCESS detail shows the Success badge");
        assertTrue(okDetail.contains(" ms"), "The duration meta carries the ms unit");
        assertTrue(okDetail.contains("&euro;"), "The total meta carries the euro unit");
        valuate(oneLine("N6FAIL", "0101", "9999999999999", "1"), 500);
        Long failId = traceIdFor("N6FAIL");
        String failDetail = getHtml("/ui/valuations/" + failId);
        assertTrue(failDetail.contains("alert alert-error"), "The FAILED detail shows the red error banner");
        assertTrue(failDetail.contains("No response was produced."),
                "A FAILED trace with no payload shows the no-response placeholder");
        given().auth().preemptive().basic("admin", "admin")
                .redirects().follow(false)
                .when().get("/ui/valuations/999999999")
                .then().statusCode(303)
                .header("Location", containsString("Valuation+999999999+no+longer+exists."))
                .header("Location", containsString("noticeOk=false"));
    }

    /**
     * N6 [W] residue — the {@code Readable}/{@code JSON} tab rendering and its real em-dash
     * placeholder for a missing {@code lineId} (the placeholder no longer passing through HTML
     * escaping) are produced client-side by {@code valuation-view.js}; they are covered by the
     * Vitest suite, not reachable through server-rendered HTML.
     */
    @Test
    @Disabled("[W] the Readable/JSON client rendering and em-dash placeholder run in valuation-view.js")
    void n6_readableJsonClientRendering() {
    }

    // --------------------------------------------------
    // N7 — the test form and replay
    // --------------------------------------------------

    /**
     * N7 — the test form and its server surface. The form GET carries the literal button
     * {@code Value this basket}; an empty submission is refused with {@code The basket is empty.}
     * and records NO trace; a schema-invalid submission is rendered as {@code HTTP 400 — {message}}
     * and DOES record a REJECTED trace (the engine is called in-process); a valid submission renders
     * the {@code Result} card and records a SUCCESS trace.
     */
    @Test
    void n7_testFormSubmissions() {
        setRecording(true);
        String form = getHtml("/ui/valuations/new");
        assertTrue(form.contains("Value this basket"), "The form carries the literal submit button");
        long before = totalTraceCount();
        String emptyResult = given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.URLENC)
                .formParam("request", "")
                .when().post("/ui/valuations/new")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(emptyResult.contains("The basket is empty."), "An empty basket is refused with its message");
        assertEquals(before, totalTraceCount(), "An empty submission records no trace");
        String rejectedBody = "{\"customerCode\":\"N7-rej\",\"items\":[{\"produceEan\":\"" + LAIT_EAN
                + "\",\"quantity\":1}]}";
        String rejectedResult = given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.URLENC)
                .formParam("request", rejectedBody)
                .when().post("/ui/valuations/new")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(rejectedResult.contains("HTTP 400"), "A schema-invalid basket renders the HTTP 400 error line");
        assertEquals(ValuationTrace.STATUS_REJECTED, traceFor("N7-rej").status,
                "A rejected submission records a REJECTED trace through the in-process engine");
        String validBody = oneLine("N7-ok", "0101", LAIT_EAN, "1");
        String validResult = given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.URLENC)
                .formParam("request", validBody)
                .when().post("/ui/valuations/new")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(validResult.contains("card-title\">Result"), "A valid basket renders the Result card");
        assertTrue(validResult.contains("totalPrice"), "The Result card holds the pretty-printed evaluation");
        assertEquals(ValuationTrace.STATUS_SUCCESS, traceFor("N7-ok").status,
                "A valid submission records a SUCCESS trace");
    }

    /**
     * N7 — {@code Replay} preloads {@code requestPayload} into the form, and a replay of a trace
     * whose payload is null silently loads an empty {@code {}} with NO message (the silent failure
     * to freeze). A recorded trace's payload is preloaded into the {@code offer-specification}
     * script; a trace inserted with a null payload replays as {@code {}} without an error banner.
     */
    @Test
    void n7_replayPreloadAndSilentEmpty() {
        setRecording(true);
        valuate(oneLine("N7RPLAY", "0101", LAIT_EAN, "1"), 200);
        Long replayId = traceIdFor("N7RPLAY");
        String replay = getHtml("/ui/valuations/new?replay=" + replayId);
        assertTrue(replay.contains("N7RPLAY"), "Replay preloads the recorded request payload into the form");
        Long[] noPayloadId = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = new ValuationTrace();
            trace.status = ValuationTrace.STATUS_SUCCESS;
            trace.customerCode = "N7NOPAY";
            trace.storeCode = "0101";
            trace.httpStatus = 200;
            trace.requestPayload = null;
            trace.persist();
            noPayloadId[0] = trace.id;
        });
        String silent = getHtml("/ui/valuations/new?replay=" + noPayloadId[0]);
        assertTrue(silent.contains("id=\"offer-specification\" type=\"application/json\">{}<"),
                "A payload-less replay loads an empty object");
        assertFalse(silent.contains("alert alert-error"), "The empty replay carries no error message");
    }

    /**
     * N7 [W] residue — the {@code Form}/{@code JSON} mode switch, the client-side schema form
     * generation and the malformed-JSON toggle-cancellation live in {@code schema-form.js}; they are
     * covered by the Vitest suite, not through server-rendered HTML.
     */
    @Test
    @Disabled("[W] the Form/JSON mode switch and client schema generation run in schema-form.js")
    void n7_formJsonToggle() {
    }
}
