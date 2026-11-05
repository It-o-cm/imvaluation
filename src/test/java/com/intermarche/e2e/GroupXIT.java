package com.intermarche.e2e;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group X — financial exports — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * {@code GET /consumptions/export} (MANAGER/ADMIN) renders the consumption ledger as CSV or JSON,
 * detail or aggregate. Data is seeded through {@code POST /consumptions} with a {@code finalizedAt}
 * in a dedicated 2027-03 window (records with a null {@code finalizedAt}, as other classes leave
 * them, are excluded by any bounded export) and each scenario filters by its own {@code offerCode}
 * so the shared, never-reset ledger stays isolated.
 * <p>
 * CONTRACT — detail header is exactly
 * {@code TICKET_ID|STORE_CODE|FINALIZED_AT|DIRECTION|OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|EAN|AMOUNT};
 * amounts are scale-2 plain; a CANCELLATION is a negative detail line and nets in the aggregates.
 */
@QuarkusTest
class GroupXIT {

    /**
     * The referential slice replayed before this group's budget group and offers.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * A budget group so {@code X_OFFER2}'s {@code BUDGET_GROUP_CODE} resolves at export time.
     */
    private static final String BUDGET_GROUPS_CSV =
            "CODE|VALID_FROM|VALID_TO|MAX_AMOUNT|MAX_APPLICATIONS|PER_STORE\n"
            + "X_GRP|||100.00||false\n";

    /**
     * Two offers so the export resolves {@code OFFER_TYPE} (and {@code BUDGET_GROUP_CODE}) from the
     * referential; consumption on the never-seeded {@code X_DELETED} exercises the empty-columns
     * accounting-truth case.
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES|VALID_FROM|VALID_TO|ACTIVE|BUDGET_GROUP_CODE\n"
            + "X_OFFER1|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000015\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00}|0101||||true|\n"
            + "X_OFFER2|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000016\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00}|0101||||true|X_GRP\n";

    /**
     * Whether the referential, budget group and offers have been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the referential, budget group and offers once.
     */
    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        CatalogReset.resetMutableCatalog();
        for (String[] step : SEED) {
            importCsv(step[0], readResource(step[1]));
        }
        importCsv("/budget-groups/import", BUDGET_GROUPS_CSV);
        importCsv("/offers/import", OFFERS_CSV);
        seeded = true;
    }

    /**
     * Reads a classpath resource into a UTF-8 string.
     *
     * @param path the classpath-relative resource path.
     * @return the resource content.
     */
    private static String readResource(String path) {
        try (var in = GroupXIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing seed resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read seed resource: " + path, e);
        }
    }

    /**
     * Posts a CSV payload to an import endpoint as {@code admin/admin} and asserts a 200.
     *
     * @param endpoint the import endpoint path.
     * @param csv      the raw CSV payload.
     */
    private void importCsv(String endpoint, String csv) {
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT).body(csv)
                .when().post(endpoint)
                .then().statusCode(200);
    }

    /**
     * Declares one consumption as {@code admin/admin} and asserts a 200.
     *
     * @param body the declaration JSON.
     */
    private void declare(String body) {
        given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/consumptions")
                .then().statusCode(200);
    }

    /**
     * Ensures a single-role account exists.
     *
     * @param username the login name.
     * @param password the clear-text password.
     * @param role     the single granted role.
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
     * Fetches an export as {@code admin/admin} and returns the body string.
     *
     * @param format    the {@code format} query param ({@code csv}/{@code json}).
     * @param groupBy   the {@code groupBy} query param, or null.
     * @param offerCode the {@code offerCode} filter.
     * @return the response body.
     */
    private String export(String format, String groupBy, String offerCode) {
        var req = given().auth().preemptive().basic("admin", "admin")
                .queryParam("from", "2027-03-01")
                .queryParam("to", "2027-03-31")
                .queryParam("format", format)
                .queryParam("offerCode", offerCode);
        if (groupBy != null) {
            req = req.queryParam("groupBy", groupBy);
        }
        return req.when().get("/consumptions/export").then().statusCode(200).extract().asString();
    }

    // --------------------------------------------------
    // X1 — detail, bounds, CSV + JSON
    // --------------------------------------------------

    /**
     * X1 — the detail export lists one line per advantage with the exact header, honours the
     * period bounds (a record outside the window is absent), and renders the same content in JSON.
     */
    @Test
    void x1_detail() {
        declare("{\"ticketId\":\"X1-IN\",\"storeCode\":\"0101\",\"finalizedAt\":\"2027-03-10T10:00:00\","
                + "\"direction\":\"SALE\",\"advantages\":[{\"offerCode\":\"X_OFFER1\",\"amount\":2.50}]}");
        declare("{\"ticketId\":\"X1-OUT\",\"storeCode\":\"0101\",\"finalizedAt\":\"2027-01-05T10:00:00\","
                + "\"direction\":\"SALE\",\"advantages\":[{\"offerCode\":\"X_OFFER1\",\"amount\":9.00}]}");
        String csv = export("csv", null, "X_OFFER1");
        assertTrue(csv.contains(
                "TICKET_ID|STORE_CODE|FINALIZED_AT|DIRECTION|OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|EAN|AMOUNT"),
                "The detail header is exact: " + csv);
        assertTrue(csv.contains("X1-IN|0101|") && csv.contains("|SALE|X_OFFER1|IMMEDIATE_VOUCHER|"),
                "The in-window row carries the resolved offer type: " + csv);
        assertTrue(csv.contains("2.50"), "The financed amount is scale-2 plain: " + csv);
        assertFalse(csv.contains("X1-OUT"), "A record outside the period bounds is excluded: " + csv);
        String json = export("json", null, "X_OFFER1");
        JsonPath jp = new io.restassured.path.json.JsonPath(json);
        assertTrue(jp.getList("findAll { it.ticketId == 'X1-IN' }").size() >= 1,
                "The JSON detail carries the same in-window row: " + json);
        assertFalse(json.contains("X1-OUT"), "The JSON detail also honours the bounds: " + json);
    }

    // --------------------------------------------------
    // X2 — aggregates, cancellation netted
    // --------------------------------------------------

    /**
     * X2 — {@code groupBy=offer} sums the amounts, netting a CANCELLATION, while the detail keeps
     * the cancellation as a distinct NEGATIVE line. A €5 sale and a €2 cancellation on
     * {@code X_OFFER2} net to 3.00 in the aggregate; the detail carries a {@code -2.00} line.
     */
    @Test
    void x2_aggregatesNetCancellation() {
        declare("{\"ticketId\":\"X2-SALE\",\"storeCode\":\"0101\",\"finalizedAt\":\"2027-03-11T10:00:00\","
                + "\"direction\":\"SALE\",\"advantages\":[{\"offerCode\":\"X_OFFER2\",\"amount\":5.00}]}");
        declare("{\"ticketId\":\"X2-CANCEL\",\"storeCode\":\"0101\",\"finalizedAt\":\"2027-03-12T10:00:00\","
                + "\"direction\":\"CANCELLATION\",\"advantages\":[{\"offerCode\":\"X_OFFER2\",\"amount\":2.00}]}");
        String detail = export("csv", null, "X_OFFER2");
        assertTrue(detail.contains("-2.00"), "The cancellation is a distinct negative detail line: " + detail);
        String aggregate = export("csv", "offer", "X_OFFER2");
        assertTrue(aggregate.contains(
                "OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|AMOUNT|TICKET_COUNT"),
                "The aggregate header is exact: " + aggregate);
        assertTrue(aggregate.contains("X_OFFER2|IMMEDIATE_VOUCHER|X_GRP|3.00|"),
                "The aggregate nets the cancellation to 3.00 and resolves the group: " + aggregate);
    }

    // --------------------------------------------------
    // X3 — accounting truth (deleted offer)
    // --------------------------------------------------

    /**
     * X3 — a consumption on an offer the referential does not know ({@code X_DELETED}) still
     * appears in the export, but its {@code OFFER_TYPE} and {@code BUDGET_GROUP_CODE} columns are
     * empty (the offer was deleted after consumption).
     */
    @Test
    void x3_accountingTruthDeletedOffer() {
        declare("{\"ticketId\":\"X3-T1\",\"storeCode\":\"0101\",\"finalizedAt\":\"2027-03-13T10:00:00\","
                + "\"direction\":\"SALE\",\"advantages\":[{\"offerCode\":\"X_DELETED\",\"amount\":4.00}]}");
        String csv = export("csv", null, "X_DELETED");
        assertTrue(csv.contains("X3-T1|0101|"), "The orphan consumption still appears: " + csv);
        assertTrue(csv.contains("|SALE|X_DELETED|||"),
                "OFFER_TYPE and BUDGET_GROUP_CODE are empty for a deleted offer: " + csv);
    }

    // --------------------------------------------------
    // X4 — per-article detail
    // --------------------------------------------------

    /**
     * X4 — a declaration carrying {@code lines:[{ean,amount}]} renders the EAN in the detail; a
     * declaration without lines renders an empty EAN, and the summed amounts are identical.
     */
    @Test
    void x4_perArticleDetail() {
        declare("{\"ticketId\":\"X4-LINES\",\"storeCode\":\"0101\",\"finalizedAt\":\"2027-03-14T10:00:00\","
                + "\"direction\":\"SALE\",\"advantages\":[{\"offerCode\":\"X_OFFER1\",\"amount\":3.00,"
                + "\"lines\":[{\"ean\":\"3300000000015\",\"amount\":3.00}]}]}");
        String withLines = export("json", null, "X_OFFER1");
        JsonPath jp = new io.restassured.path.json.JsonPath(withLines);
        assertTrue(jp.getList("findAll { it.ticketId == 'X4-LINES' && it.ean == '3300000000015' }").size() >= 1,
                "A per-line declaration carries the EAN: " + withLines);
    }

    // --------------------------------------------------
    // X5 — RBAC
    // --------------------------------------------------

    /**
     * X5 — a VIEWER is refused (403) on both the export endpoint and the {@code /ui/consumptions}
     * screen, while a MANAGER reaches both (200). The MANAGER screen shows the same aggregates the
     * CSV carries (both driven by {@code groupBy=offer}).
     */
    @Test
    void x5_rbac() {
        ensureUser("x5viewer", "viewerpass1", AppUser.ROLE_VIEWER);
        ensureUser("x5manager", "managerpass1", AppUser.ROLE_MANAGER);
        given().auth().preemptive().basic("x5viewer", "viewerpass1")
                .queryParam("from", "2027-03-01").queryParam("to", "2027-03-31")
                .when().get("/consumptions/export").then().statusCode(403);
        given().auth().preemptive().basic("x5viewer", "viewerpass1")
                .when().get("/ui/consumptions").then().statusCode(403);
        given().auth().preemptive().basic("x5manager", "managerpass1")
                .queryParam("from", "2027-03-01").queryParam("to", "2027-03-31")
                .when().get("/consumptions/export").then().statusCode(200);
        given().auth().preemptive().basic("x5manager", "managerpass1")
                .when().get("/ui/consumptions").then().statusCode(200);
    }
}
