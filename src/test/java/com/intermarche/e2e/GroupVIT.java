package com.intermarche.e2e;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ValuationTrace;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group V — budget groups &amp; consumption — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Consumption is declared through {@code POST /consumptions} (ADMIN), budgets are read at
 * valuation from an offer's {@code budget} spec block (and from the linked {@code BudgetGroup}
 * entity), and status is queried through GraphQL ({@code budgetStatus} / {@code groupBudgetStatus},
 * MANAGER). The class replays the referential slice, a group-local {@code BUDGET_GROUPS} feed and
 * its own budgeted offers. {@code ConsumptionCounter} is NOT reset between classes, so every
 * scenario uses a DISTINCT offer code and ticket id to stay collision-free.
 * <p>
 * BUDGET REFUSAL — an exhausted budget removes the advantage silently (HTTP 200) and records the
 * reason in {@code valuation_traces.error_message} as {@code "Skipped configurations: "} +
 * {@code "BUDGET_EXHAUSTED: offer <code>"} or {@code "GROUP_BUDGET_EXHAUSTED: offer <code> group <group>"}
 * (the exhaustion test is {@code consumed >= max}); the body carries no refusal marker. GraphQL
 * not-found paths surface as the SmallRye {@code "System error"} mask.
 */
@QuarkusTest
class GroupVIT {

    /**
     * The referential slice replayed before this group's budget groups and offers.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * Group-local budget groups: a tight €5 envelope (V5), an expired-period group (V7), and a
     * status group (V9).
     */
    private static final String BUDGET_GROUPS_CSV =
            "CODE|VALID_FROM|VALID_TO|MAX_AMOUNT|MAX_APPLICATIONS|PER_STORE\n"
            + "V_GRP|||5.00||false\n"
            + "V_GRP_EXP|2025-01-01T00:00:00|2025-12-31T23:59:59|100.00||false\n"
            + "V_GRP_STAT|||30.00||false\n";

    /**
     * This group's budgeted offers (one per scenario, distinct EANs and codes for isolation).
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES|VALID_FROM|VALID_TO|ACTIVE|BUDGET_GROUP_CODE\n"
            + "V_OFFER_AMT|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000015\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.50,\"budget\":{\"maxAmount\":10.00}}|0101||||true|\n"
            + "V_OFFER_APP|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000016\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00,\"budget\":{\"maxApplications\":2}}|0101||||true|\n"
            + "V_GROUP_A|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000022\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00}|0101||||true|V_GRP\n"
            + "V_GROUP_B|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000023\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00}|0101||||true|V_GRP\n"
            + "V_PERSTORE|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000005\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00,\"budget\":{\"maxAmount\":5.00,\"perStore\":true}}|0101,0102||||true|\n"
            + "V_PERIOD|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000026\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00}|0101||||true|V_GRP_EXP\n"
            + "V_STATUS|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000013\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00,\"budget\":{\"maxAmount\":20.00}}|0101||||true|V_GRP_STAT\n"
            + "V_NO_BUDGET|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000014\"],"
            + "\"discountType\":\"FIXED_AMOUNT\",\"value\":1.00}|0101||||true|\n";

    /**
     * Whether the referential, budget groups and offers have been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the referential, the budget groups and this group's offers once.
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
        try (var in = GroupVIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing seed resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read seed resource: " + path, e);
        }
    }

    /**
     * Posts a CSV payload to an import endpoint as {@code admin/admin} and returns the body.
     *
     * @param endpoint the import endpoint path.
     * @param csv      the raw CSV payload.
     * @return the response body string.
     */
    private String importCsv(String endpoint, String csv) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT).body(csv)
                .when().post(endpoint)
                .then().statusCode(200)
                .extract().asString();
    }

    /**
     * Posts a consumption declaration as the given credentials and asserts the HTTP status.
     *
     * @param user           the username.
     * @param pass           the password.
     * @param body           the declaration JSON.
     * @param expectedStatus the expected HTTP status.
     * @return the parsed JSON response (empty on non-2xx).
     */
    private JsonPath consume(String user, String pass, String body, int expectedStatus) {
        return given().auth().preemptive().basic(user, pass)
                .contentType(ContentType.JSON).body(body)
                .when().post("/consumptions")
                .then().statusCode(expectedStatus)
                .extract().jsonPath();
    }

    /**
     * Declares one SALE consumption of an offer as {@code admin/admin}.
     *
     * @param ticketId  the ticket id.
     * @param storeCode the store code.
     * @param offerCode the offer code.
     * @param amount    the financed amount JSON literal.
     */
    private void sale(String ticketId, String storeCode, String offerCode, String amount) {
        consume("admin", "admin",
                "{\"ticketId\":\"" + ticketId + "\",\"storeCode\":\"" + storeCode
                        + "\",\"direction\":\"SALE\",\"advantages\":[{\"offerCode\":\"" + offerCode
                        + "\",\"amount\":" + amount + "}]}", 200);
    }

    /**
     * Posts a basket to {@code /valuation} as {@code admin/admin} and asserts the HTTP status.
     *
     * @param body           the raw basket JSON.
     * @param expectedStatus the expected HTTP status.
     * @return the parsed JSON response.
     */
    private JsonPath valuate(String body, int expectedStatus) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/valuation")
                .then().statusCode(expectedStatus)
                .extract().jsonPath();
    }

    /**
     * Posts a GraphQL query as the given credentials and returns the body string.
     *
     * @param user  the username.
     * @param pass  the password.
     * @param query the GraphQL query (inner quotes escaped).
     * @return the response body string.
     */
    private String graphql(String user, String pass, String query) {
        return given().auth().preemptive().basic(user, pass)
                .contentType(ContentType.JSON)
                .body("{\"query\":\"" + query + "\"}")
                .when().post("/graphql")
                .then().statusCode(200)
                .extract().asString();
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
     * Whether an advantage of the given type prefix is present.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix.
     * @return true when present.
     */
    private boolean hasAdvantage(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return false;
        }
        return advantages.stream().anyMatch(a -> a.get("type") != null
                && a.get("type").toString().startsWith(prefix));
    }

    /**
     * Loads the error message of the latest trace for a customer code.
     *
     * @param customerCode the unique customer code.
     * @return the recorded error message, or null.
     */
    private String errorMessageFor(String customerCode) {
        String[] holder = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = ValuationTrace
                    .find("customerCode = ?1 order by createdAt desc", customerCode)
                    .firstResult();
            if (trace != null) {
                holder[0] = trace.errorMessage;
            }
        });
        return holder[0];
    }

    /**
     * Builds a closed single-line basket.
     *
     * @param customer the customer code.
     * @param store    the store code.
     * @param ean      the product EAN.
     * @return the basket JSON.
     */
    private String basket(String customer, String store, String ean) {
        return String.format(
                "{\"customerCode\":\"%s\",\"storeCode\":\"%s\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"%s\",\"quantity\":1}]}",
                customer, store, ean);
    }

    // --------------------------------------------------
    // V1 — ingestion nominal + RBAC
    // --------------------------------------------------

    /**
     * V1 — {@code POST /consumptions} (ADMIN) with two advantages returns
     * {@code duplicate:false} and {@code offersAffected:2}. A MANAGER is refused with a 403.
     */
    @Test
    void v1_ingestionNominalAndRbac() {
        JsonPath ok = consume("admin", "admin",
                "{\"ticketId\":\"V1-T1\",\"storeCode\":\"0101\",\"direction\":\"SALE\",\"advantages\":["
                        + "{\"offerCode\":\"V1_A\",\"amount\":1.50},{\"offerCode\":\"V1_B\",\"amount\":5.00}]}", 200);
        assertFalse(ok.getBoolean("duplicate"), "A first declaration is not a duplicate");
        assertEquals(2, ok.getInt("offersAffected"), "Two distinct offers are affected");
        ensureUser("v1manager", "managerpass1", AppUser.ROLE_MANAGER);
        consume("v1manager", "managerpass1",
                "{\"ticketId\":\"V1-T2\",\"storeCode\":\"0101\",\"direction\":\"SALE\",\"advantages\":["
                        + "{\"offerCode\":\"V1_A\",\"amount\":1.50}]}", 403);
    }

    // --------------------------------------------------
    // V2 — idempotence
    // --------------------------------------------------

    /**
     * V2 — replaying the same {@code (storeCode, ticketId, direction)} triplet is idempotent: the
     * second post returns {@code duplicate:true} and {@code offersAffected:0}.
     */
    @Test
    void v2_idempotence() {
        consume("admin", "admin",
                "{\"ticketId\":\"V2-T1\",\"storeCode\":\"0101\",\"direction\":\"SALE\",\"advantages\":["
                        + "{\"offerCode\":\"V2_A\",\"amount\":2.00}]}", 200);
        JsonPath replay = consume("admin", "admin",
                "{\"ticketId\":\"V2-T1\",\"storeCode\":\"0101\",\"direction\":\"SALE\",\"advantages\":["
                        + "{\"offerCode\":\"V2_A\",\"amount\":2.00}]}", 200);
        assertTrue(replay.getBoolean("duplicate"), "A strict replay is a duplicate");
        assertEquals(0, replay.getInt("offersAffected"), "A duplicate affects no offer");
    }

    // --------------------------------------------------
    // V3 — cancellation
    // --------------------------------------------------

    /**
     * V3 — a {@code CANCELLATION} is a different triplet from the {@code SALE} of the same ticket,
     * so it is accepted (not a duplicate); and a CANCELLATION with no prior SALE is also accepted
     * (out-of-order relay).
     */
    @Test
    void v3_cancellation() {
        consume("admin", "admin",
                "{\"ticketId\":\"V3-T1\",\"storeCode\":\"0101\",\"direction\":\"SALE\",\"advantages\":["
                        + "{\"offerCode\":\"V3_A\",\"amount\":3.00}]}", 200);
        JsonPath cancel = consume("admin", "admin",
                "{\"ticketId\":\"V3-T1\",\"storeCode\":\"0101\",\"direction\":\"CANCELLATION\",\"advantages\":["
                        + "{\"offerCode\":\"V3_A\",\"amount\":3.00}]}", 200);
        assertFalse(cancel.getBoolean("duplicate"), "A cancellation is a distinct triplet, not a duplicate");
        JsonPath orphan = consume("admin", "admin",
                "{\"ticketId\":\"V3-T2\",\"storeCode\":\"0101\",\"direction\":\"CANCELLATION\",\"advantages\":["
                        + "{\"offerCode\":\"V3_A\",\"amount\":3.00}]}", 200);
        assertFalse(orphan.getBoolean("duplicate"), "A cancellation without a prior sale is accepted");
    }

    // --------------------------------------------------
    // V4 — offer budget exhausted (amount + applications)
    // --------------------------------------------------

    /**
     * V4 — an offer's own budget. With {@code maxAmount 10} and 9 consumed, a ticket's €1.50
     * discount still APPLIES (the overshoot is bounded, never a partial discount); once
     * consumption reaches 11 (≥ 10), the next ticket loses the advantage and the trace records
     * {@code BUDGET_EXHAUSTED}. With {@code maxApplications 2}, the third ticket loses it too.
     */
    @Test
    void v4_offerBudgetExhausted() {
        sale("V4-amt-1", "0101", "V_OFFER_AMT", "9.00");
        JsonPath applied = valuate(basket("V4-below", "0101", "3300000000015"), 200);
        assertTrue(hasAdvantage(applied, "Immediate Voucher Discount : V_OFFER_AMT"),
                "Below the amount ceiling the full discount applies");
        sale("V4-amt-2", "0101", "V_OFFER_AMT", "2.00");
        JsonPath exhausted = valuate(basket("V4-exhausted", "0101", "3300000000015"), 200);
        assertFalse(hasAdvantage(exhausted, "Immediate Voucher Discount : V_OFFER_AMT"),
                "Once consumed reaches the ceiling the advantage is refused");
        String message = errorMessageFor("V4-exhausted");
        assertNotNull(message, "The refusal is traced");
        assertTrue(message.contains("BUDGET_EXHAUSTED: offer V_OFFER_AMT"),
                "The budget-exhausted reason names the offer: " + message);
        sale("V4-app-1", "0101", "V_OFFER_APP", "1.00");
        sale("V4-app-2", "0101", "V_OFFER_APP", "1.00");
        JsonPath capped = valuate(basket("V4-app", "0101", "3300000000016"), 200);
        assertFalse(hasAdvantage(capped, "Immediate Voucher Discount : V_OFFER_APP"),
                "After two applications the third ticket loses the advantage");
    }

    // --------------------------------------------------
    // V5 — group envelope exhausted
    // --------------------------------------------------

    /**
     * V5 — two offers of the same {@code V_GRP} (€5 envelope) share the budget: consuming €5
     * through {@code V_GROUP_A} exhausts the group, so {@code V_GROUP_B} is refused with a
     * {@code GROUP_BUDGET_EXHAUSTED} reason naming the group.
     */
    @Test
    void v5_groupEnvelopeExhausted() {
        sale("V5-T1", "0101", "V_GROUP_A", "5.00");
        JsonPath body = valuate(basket("V5-b", "0101", "3300000000023"), 200);
        assertFalse(hasAdvantage(body, "Immediate Voucher Discount : V_GROUP_B"),
                "The sibling offer is refused once the group envelope is spent");
        String message = errorMessageFor("V5-b");
        assertNotNull(message, "The group refusal is traced");
        assertTrue(message.contains("GROUP_BUDGET_EXHAUSTED: offer V_GROUP_B group V_GRP"),
                "The reason names the offer and the group: " + message);
    }

    // --------------------------------------------------
    // V6 — per-store scope
    // --------------------------------------------------

    /**
     * V6 — a per-store budget is scoped to the point of sale: spending €5 in {@code 0101}
     * exhausts it there (the advantage is refused on {@code 0101}) while {@code 0102} stays
     * pristine (the advantage applies on {@code 0102}).
     */
    @Test
    void v6_perStoreScope() {
        sale("V6-T1", "0101", "V_PERSTORE", "5.00");
        JsonPath onA = valuate(basket("V6-a", "0101", "3300000000005"), 200);
        assertFalse(hasAdvantage(onA, "Immediate Voucher Discount : V_PERSTORE"),
                "The per-store budget is exhausted on 0101");
        JsonPath onB = valuate(basket("V6-b", "0102", "3300000000005"), 200);
        assertTrue(hasAdvantage(onB, "Immediate Voucher Discount : V_PERSTORE"),
                "A pristine store still serves the advantage");
    }

    // --------------------------------------------------
    // V7 — group period
    // --------------------------------------------------

    /**
     * V7 — an offer linked to a group whose {@code validFrom}/{@code validTo} window has closed
     * ({@code V_GRP_EXP}, 2025) is no longer served by the in-force finders: its advantage never
     * appears.
     */
    @Test
    void v7_groupPeriod() {
        JsonPath body = valuate(basket("V7", "0101", "3300000000026"), 200);
        assertFalse(hasAdvantage(body, "Immediate Voucher Discount : V_PERIOD"),
                "An offer in an out-of-period group is not served");
    }

    // --------------------------------------------------
    // V8 — budget-groups flux
    // --------------------------------------------------

    /**
     * V8 — {@code POST /budget-groups/import} is idempotent by checksum (a strict re-import is a
     * no-op), and an {@code OFFERS} line naming an unknown {@code BUDGET_GROUP_CODE} is rejected
     * with the literal {@code "BudgetGroup code '<code>' not found."}.
     */
    @Test
    void v8_budgetGroupsFlux() {
        String reimport = importCsv("/budget-groups/import", BUDGET_GROUPS_CSV);
        assertTrue(reimport.contains("\"createdCount\":0"), "A strict re-import creates nothing: " + reimport);
        assertTrue(reimport.contains("\"updatedCount\":0"), "A strict re-import updates nothing: " + reimport);
        String rejected = importCsv("/offers/import",
                "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES|VALID_FROM|VALID_TO|ACTIVE|BUDGET_GROUP_CODE\n"
                        + "V_BADGRP|IMMEDIATE_VOUCHER|{\"discountType\":\"FIXED_AMOUNT\",\"value\":1.0}|0101||||true|NOPE\n");
        assertTrue(rejected.contains("BudgetGroup code 'NOPE' not found."),
                "An unknown budget group code is rejected: " + rejected);
    }

    // --------------------------------------------------
    // V9 — status GraphQL
    // --------------------------------------------------

    /**
     * V9 — {@code budgetStatus}/{@code groupBudgetStatus} (MANAGER) report the consumed and
     * remaining figures coherently: consuming €7 through {@code V_STATUS} (max €20) yields
     * {@code consumedAmount 7} and {@code remainingAmount 13}; the status group reports the same
     * consumption. An unknown offer code surfaces the SmallRye {@code "System error"} mask.
     */
    @Test
    void v9_statusGraphql() {
        sale("V9-T1", "0101", "V_STATUS", "7.00");
        ensureUser("v9manager", "managerpass1", AppUser.ROLE_MANAGER);
        String status = graphql("v9manager", "managerpass1",
                "{ budgetStatus(offerCode: \\\"V_STATUS\\\") { maxAmount consumedAmount remainingAmount } }");
        JsonPath sp = new io.restassured.path.json.JsonPath(status);
        assertEquals(0, new java.math.BigDecimal(sp.getString("data.budgetStatus.consumedAmount"))
                .compareTo(new java.math.BigDecimal("7")), "The consumed amount is 7: " + status);
        assertEquals(0, new java.math.BigDecimal(sp.getString("data.budgetStatus.remainingAmount"))
                .compareTo(new java.math.BigDecimal("13")), "The remaining amount is 13: " + status);
        String group = graphql("v9manager", "managerpass1",
                "{ groupBudgetStatus(groupCode: \\\"V_GRP_STAT\\\") { consumedAmount } }");
        assertEquals(0, new java.math.BigDecimal(new io.restassured.path.json.JsonPath(group)
                        .getString("data.groupBudgetStatus.consumedAmount")).compareTo(new java.math.BigDecimal("7")),
                "The group sums the same consumption: " + group);
        String unknown = graphql("v9manager", "managerpass1",
                "{ budgetStatus(offerCode: \\\"NOPE\\\") { consumedAmount } }");
        assertTrue(unknown.contains("System error"),
                "An unknown offer code surfaces the SmallRye mask: " + unknown);
    }

    // --------------------------------------------------
    // V10 — regression
    // --------------------------------------------------

    /**
     * V10 — an offer with no budget and no group is a strict no-op regardless of consumption: its
     * advantage always applies (bit-for-bit regression).
     */
    @Test
    void v10_regressionNoBudget() {
        sale("V10-T1", "0101", "V_NO_BUDGET", "999.00");
        JsonPath body = valuate(basket("V10", "0101", "3300000000014"), 200);
        assertTrue(hasAdvantage(body, "Immediate Voucher Discount : V_NO_BUDGET"),
                "An unbudgeted offer is never refused, whatever the recorded consumption");
    }
}
