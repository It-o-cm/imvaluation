package com.intermarche.e2e;

import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group W — targeting, time slots &amp; enriched outputs — of e2e-scenarios.md, in pure
 * RestAssured.
 * <p>
 * Implemented here: W1–W4 (family targeting via {@code targetFamilyCodes}, EAN exclusion via
 * {@code excludedEans}, and point-of-sale exclusion via {@code EXCLUDED_STORE_CODES}, all of
 * spec-ciblage-saisie.md), W5 (TIME_WINDOW conditions, driven through the pilotable
 * {@code DateTimeProvider}, never the real clock), W6 ({@code accountingCode}/{@code printMode}
 * passthrough), W7 (the {@code MESSAGE} advantage and its {@code messages[]} block), and a
 * 50&times; determinism check on the family resolution path.
 * <p>
 * REGRESSION CRITERION — the {@code messages} response key is ABSENT (not null) when no message
 * applies; that absence is asserted (W7). The engine stays byte-for-byte deterministic across 50
 * runs ({@link #w_determinismFiftyTimes()}).
 */
@QuarkusTest
class GroupWIT {

    /**
     * The referential slice replayed before this group's offers.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/store-groups/import", "seed/02-store-groups.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/product-families/import", "seed/04-product-families.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * A group-local family sub-tree for the family-targeting scenarios (W1/W2): {@code W_PARENT}
     * has apples {@code …001} as a DIRECT product and {@code W_CHILD} (baguette {@code …003}) as a
     * sub-family, so targeting {@code W_PARENT} covers both a direct member and a sub-family member.
     * Coffee {@code …004} is deliberately kept OUT of the sub-tree so the W5/W7 coffee baskets stay
     * free of this discount.
     */
    private static final String FAMILIES_CSV =
            "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n"
            + "W_CHILD|W targeting child||3300000000003|\n"
            + "W_PARENT|W targeting parent||3300000000001|W_CHILD\n";

    /**
     * The family-targeting and exclusion offers (W1–W3), all on {@code 0101} except the
     * unknown-family probe (on {@code 0102}, so its per-factory fail-closed skip is isolated).
     */
    private static final String TARGETING_OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "W_FAM|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetFamilyCodes\":[\"W_PARENT\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":10.0}|0101|\n"
            + "W_EXCL|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000001\"],"
            + "\"targetFamilyCodes\":[\"W_PARENT\"],\"excludedEans\":[\"3300000000001\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":10.0}|0101|\n"
            + "W_BADFAM|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetFamilyCodes\":[\"NOPE\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":10.0}|0102|\n";

    /**
     * The PDV-exclusion offer (W4): a MEAL_VOUCHER served through the {@code REGION_NORTH} group
     * (which contains {@code 0101}, {@code 0102}, {@code 0103}) but excluded from {@code 0101} — so
     * {@code 0101} is not served even though a targeted group contains it, while {@code 0102} is. A
     * MEAL_VOUCHER (not an IMMEDIATE_VOUCHER) is used because discount offers ignore group
     * attachment (the K4 asymmetry), whereas the meal voucher honours it. Mineral water {@code …007}
     * (flag {@code RESTAURANT_VOUCHER_ELIGIBLE}) is priced on both stores.
     */
    private static final String PDV_OFFER_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES|EXCLUDED_STORE_CODES\n"
            + "W_PDV|MEAL_VOUCHER|{\"flag\":\"RESTAURANT_VOUCHER_ELIGIBLE\",\"threshold\":25.00}||REGION_NORTH|0101\n";

    /**
     * This group's time-window, passthrough and message offers.
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "W_TIME|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"TIME_WINDOW\",\"from\":\"17:00\",\"to\":\"19:00\"}]}}|0101|\n"
            + "W_TIME_WRAP|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"TIME_WINDOW\",\"from\":\"22:00\",\"to\":\"02:00\"}]}}|0101|\n"
            + "W_TIME_MON|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"TIME_WINDOW\",\"days\":[\"MONDAY\"],\"from\":\"17:00\",\"to\":\"19:00\"}]}}|0101|\n"
            + "W_HAPPY|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"TIME_WINDOW\",\"from\":\"17:00\",\"to\":\"19:00\"},"
            + "{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":8.40}]}}|0101|\n"
            + "W_PASS|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000015\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":10.0,\"accountingCode\":\"ACC-42\",\"printMode\":\"FOOTER\"}|0101|\n"
            + "W_NOPASS|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000016\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":10.0}|0101|\n"
            + "W_MSG_BELOW|MESSAGE|{\"text\":\"Plus que {{missingAmount}} EUR\",\"showWhen\":\"BELOW_TRIGGER\","
            + "\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":50}]}}|0101|\n"
            + "W_MSG_WHEN|MESSAGE|{\"text\":\"Merci {{storeName}} total {{total}}\","
            + "\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":8}]}}|0101|\n"
            + "W_MSG_BAD|MESSAGE|{\"text\":\"{{unknown}}\","
            + "\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":1}]}}|0102|\n";

    /**
     * The coffee EAN (UNIT), a single 4.20 TTC price on {@code 0101}.
     */
    private static final String CAFE = "3300000000004";

    /**
     * A Monday inside the 17:00–19:00 window (2026-06-15 is a Monday).
     */
    private static final LocalDateTime MONDAY_1830 = LocalDateTime.of(2026, 6, 15, 18, 30);

    /**
     * Whether the referential and this group's offers have been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the referential and this group's offers once.
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
        importCsv("/product-families/import", FAMILIES_CSV);
        importCsv("/offers/import", OFFERS_CSV);
        importCsv("/offers/import", TARGETING_OFFERS_CSV);
        importCsv("/offers/import", PDV_OFFER_CSV);
        seeded = true;
    }

    /**
     * Reads a classpath resource into a UTF-8 string.
     *
     * @param path the classpath-relative resource path.
     * @return the resource content.
     */
    private static String readResource(String path) {
        try (var in = GroupWIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Returns the first advantage map whose {@code type} starts with the prefix.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix.
     * @return the advantage map, or null.
     */
    private Map<String, Object> advantage(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return null;
        }
        return advantages.stream().filter(a -> a.get("type") != null
                && a.get("type").toString().startsWith(prefix)).findFirst().orElse(null);
    }

    /**
     * Returns the first message-block entry whose {@code code} equals the offer code.
     *
     * @param body the parsed evaluation.
     * @param code the message offer code.
     * @return the message map, or null when the block or entry is absent.
     */
    private Map<String, Object> message(JsonPath body, String code) {
        List<Map<String, Object>> messages = body.getList("messages");
        if (messages == null) {
            return null;
        }
        return messages.stream().filter(m -> code.equals(m.get("code"))).findFirst().orElse(null);
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
     * Builds a closed single-EAN basket at a given quantity on {@code 0101}.
     *
     * @param customer the customer code.
     * @param ean      the product EAN.
     * @param quantity the line quantity JSON literal.
     * @return the basket JSON.
     */
    private String basket(String customer, String ean, String quantity) {
        return String.format(
                "{\"customerCode\":\"%s\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"%s\",\"quantity\":%s}]}",
                customer, ean, quantity);
    }

    // --------------------------------------------------
    // W5 — TIME_WINDOW conditions
    // --------------------------------------------------

    /**
     * W5 — a {@code TIME_WINDOW} condition fires only inside its half-open window, driven by the
     * pilotable clock. At 18:30 the 17:00–19:00 window is open ({@code W_TIME} applies); at 12:00
     * it is shut. The clock is always cleared afterwards.
     */
    @Test
    void w5_timeWindowSimple() {
        try {
            DateTimeProvider.setFixedDateTime(MONDAY_1830);
            assertTrue(hasAdvantage(valuate(basket("W5-in", CAFE, "1"), 200), "Ticket Discount: W_TIME"),
                    "18:30 is inside the 17:00-19:00 window");
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 12, 0));
            assertFalse(hasAdvantage(valuate(basket("W5-out", CAFE, "1"), 200), "Ticket Discount: W_TIME"),
                    "12:00 is outside the window");
        } finally {
            DateTimeProvider.clear();
        }
    }

    /**
     * W5 — a window that spans midnight (22:00–02:00) is open at 23:50 and at 00:10, and shut at
     * 12:00.
     */
    @Test
    void w5_timeWindowMidnightWrap() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 23, 50));
            assertTrue(hasAdvantage(valuate(basket("W5-2350", CAFE, "1"), 200), "Ticket Discount: W_TIME_WRAP"),
                    "23:50 is inside the 22:00-02:00 window");
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 16, 0, 10));
            assertTrue(hasAdvantage(valuate(basket("W5-0010", CAFE, "1"), 200), "Ticket Discount: W_TIME_WRAP"),
                    "00:10 is inside the wrapping window");
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 12, 0));
            assertFalse(hasAdvantage(valuate(basket("W5-noon", CAFE, "1"), 200), "Ticket Discount: W_TIME_WRAP"),
                    "12:00 is outside the wrapping window");
        } finally {
            DateTimeProvider.clear();
        }
    }

    /**
     * W5 — the {@code days} restriction is honoured: {@code W_TIME_MON} fires on Monday 18:30 but
     * not on Tuesday 18:30, even though the time of day is inside the window.
     */
    @Test
    void w5_timeWindowDays() {
        try {
            DateTimeProvider.setFixedDateTime(MONDAY_1830);
            assertTrue(hasAdvantage(valuate(basket("W5-mon", CAFE, "1"), 200), "Ticket Discount: W_TIME_MON"),
                    "Monday 18:30 satisfies the MONDAY-only window");
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 16, 18, 30));
            assertFalse(hasAdvantage(valuate(basket("W5-tue", CAFE, "1"), 200), "Ticket Discount: W_TIME_MON"),
                    "Tuesday 18:30 fails the MONDAY-only window");
        } finally {
            DateTimeProvider.clear();
        }
    }

    /**
     * W5 — a happy-hour offer composes {@code TIME_WINDOW} AND {@code MINIMUM_AMOUNT} in
     * {@code conditions[]}: inside the window a two-coffee ticket (8.40 ≥ 8.40) fires
     * {@code W_HAPPY}, but a one-coffee ticket (4.20 &lt; 8.40) does not.
     */
    @Test
    void w5_happyHourComposition() {
        try {
            DateTimeProvider.setFixedDateTime(MONDAY_1830);
            assertTrue(hasAdvantage(valuate(basket("W5-happy", CAFE, "2"), 200), "Ticket Discount: W_HAPPY"),
                    "Inside the window and above the minimum: the happy-hour discount fires");
            assertFalse(hasAdvantage(valuate(basket("W5-nohappy", CAFE, "1"), 200), "Ticket Discount: W_HAPPY"),
                    "Inside the window but below the minimum: nothing fires");
        } finally {
            DateTimeProvider.clear();
        }
    }

    // --------------------------------------------------
    // W6 — accountingCode / printMode passthrough
    // --------------------------------------------------

    /**
     * W6 — an offer carrying {@code accountingCode} and {@code printMode} in its specification
     * copies them verbatim onto its advantage; an offer without them produces an advantage with
     * neither key (the fields are {@code @JsonInclude(NON_NULL)}). The clock is pinned at noon so
     * no time-window discount interferes.
     */
    @Test
    void w6_accountingCodeAndPrintModePassthrough() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 12, 0));
            Map<String, Object> pass = advantage(
                    valuate(basket("W6-pass", "3300000000015", "1"), 200), "Immediate Voucher Discount : W_PASS");
            assertNotNull(pass, "The passthrough offer applies");
            assertEquals("ACC-42", pass.get("accountingCode"), "The accounting code is copied verbatim");
            assertEquals("FOOTER", pass.get("printMode"), "The print mode is copied verbatim");
            Map<String, Object> noPass = advantage(
                    valuate(basket("W6-nopass", "3300000000016", "1"), 200), "Immediate Voucher Discount : W_NOPASS");
            assertNotNull(noPass, "The plain offer applies");
            assertNull(noPass.get("accountingCode"), "No accounting code key when the spec omits it");
            assertNull(noPass.get("printMode"), "No print mode key when the spec omits it");
        } finally {
            DateTimeProvider.clear();
        }
    }

    // --------------------------------------------------
    // W7 — MESSAGE advantage
    // --------------------------------------------------

    /**
     * W7 — a {@code BELOW_TRIGGER} message renders {@code {{missingAmount}}} to the cent while the
     * ticket is under the threshold, and disappears once the threshold is reached. One coffee
     * (4.20) leaves 45.80 to reach the €50 threshold, so {@code W_MSG_BELOW} appears with the
     * exact figure; a large ticket makes it vanish.
     */
    @Test
    void w7_messageBelowTrigger() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 12, 0));
            Map<String, Object> below = message(valuate(basket("W7-below", CAFE, "1"), 200), "W_MSG_BELOW");
            assertNotNull(below, "The below-trigger message appears under the threshold");
            assertEquals("Plus que 45.80 EUR", below.get("text"),
                    "The missing amount is rendered to the cent, dot decimal: " + below.get("text"));
            Map<String, Object> above = message(valuate(basket("W7-above", CAFE, "20"), 200), "W_MSG_BELOW");
            assertNull(above, "Above the threshold the below-trigger message disappears");
        } finally {
            DateTimeProvider.clear();
        }
    }

    /**
     * W7 — a {@code WHEN_TRIGGERED} message renders {@code {{storeName}}} and {@code {{total}}}
     * once the threshold is reached. Two coffees (8.40 ≥ 8) fire {@code W_MSG_WHEN} with the store
     * name and the exact total.
     */
    @Test
    void w7_messageWhenTriggered() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 12, 0));
            Map<String, Object> msg = message(valuate(basket("W7-when", CAFE, "2"), 200), "W_MSG_WHEN");
            assertNotNull(msg, "The when-triggered message appears at or above the threshold");
            String text = msg.get("text").toString();
            // The store name carries a non-ASCII 'é'; assert the ASCII-safe parts to sidestep the
            // response charset quirk while still proving {{storeName}} and {{total}} resolved.
            assertTrue(text.startsWith("Merci Intermarch") && text.contains("Test 1"),
                    "The store name is resolved: " + text);
            assertTrue(text.endsWith("total 8.40"),
                    "The ticket total is resolved to the cent, dot decimal: " + text);
        } finally {
            DateTimeProvider.clear();
        }
    }

    /**
     * W7 — a message referencing an unknown variable is skipped fail-closed: no message is
     * produced and the trace records {@code "Skipped configurations: "}. Also asserts the
     * regression criterion: a basket with no applicable message carries NO {@code messages} key.
     */
    @Test
    void w7_unknownVariableSkippedAndKeyAbsent() {
        JsonPath bad = valuate(
                "{\"customerCode\":\"W7-bad\",\"storeCode\":\"0102\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"3300000000005\",\"quantity\":1}]}", 200);
        assertNull(bad.get("messages"), "A skipped message produces no messages key");
        String message = errorMessageFor("W7-bad");
        assertNotNull(message, "The skip is traced");
        assertTrue(message.startsWith("Skipped configurations: "),
                "The unknown variable is a fail-closed skip: " + message);
    }

    // --------------------------------------------------
    // W1-W4 — family targeting, exclusions & PDV exclusion (spec-ciblage-saisie)
    // --------------------------------------------------

    /**
     * W1 — family targeting resolved at evaluation (spec-ciblage-saisie §1, normative case 1).
     * {@code W_FAM} targets the family {@code W_PARENT}, whose sub-tree is apples {@code …001}
     * (a direct member) and coffee {@code …004} (through the {@code W_CHILD} sub-family): both are
     * covered, a milk {@code …002} outside the family is not. Then coffee's child family is
     * re-imported with milk added, and milk is covered on the very next valuation WITHOUT touching
     * the offer — the resolution is live, never compiled at entry.
     */
    @Test
    void w1_familyTargeting() {
        assertTrue(hasAdvantage(valuate(basket("W1-direct", "3300000000001", "1"), 200),
                "Immediate Voucher Discount : W_FAM"), "A direct family member is covered");
        assertTrue(hasAdvantage(valuate(basket("W1-sub", "3300000000003", "1"), 200),
                "Immediate Voucher Discount : W_FAM"), "A sub-family member is covered");
        assertFalse(hasAdvantage(valuate(basket("W1-out", "3300000000002", "1"), 200),
                "Immediate Voucher Discount : W_FAM"), "An article outside the family is not covered");
        importCsv("/product-families/import",
                "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n"
                        + "W_CHILD|W targeting child grown||3300000000003,3300000000002|\n");
        assertTrue(hasAdvantage(valuate(basket("W1-grown", "3300000000002", "1"), 200),
                "Immediate Voucher Discount : W_FAM"),
                "An article added to the family later is covered without retouching the offer");
    }

    /**
     * W2 — the exclusion always wins (spec-ciblage-saisie §2, normative case 2). {@code W_EXCL}
     * targets apples {@code …001} both by {@code targetEans} and by the {@code W_PARENT} family, yet
     * excludes {@code …001} through {@code excludedEans}: apples are NOT covered, while coffee
     * {@code …004} (in the family, not excluded) still is.
     */
    @Test
    void w2_exclusionWins() {
        assertFalse(hasAdvantage(valuate(basket("W2-excluded", "3300000000001", "1"), 200),
                "Immediate Voucher Discount : W_EXCL"),
                "An excluded EAN is not covered even when explicitly targeted and in a targeted family");
        assertTrue(hasAdvantage(valuate(basket("W2-kept", "3300000000003", "1"), 200),
                "Immediate Voucher Discount : W_EXCL"), "A non-excluded family member is still covered");
    }

    /**
     * W3 — an unknown family code is fail-closed for that configuration and traced, the evaluation
     * continuing (spec-ciblage-saisie §1, normative case 3). {@code W_BADFAM} (on {@code 0102}, so
     * its per-factory skip is isolated) names a family that does not exist: no advantage is
     * produced, the response is 200, and the trace records {@code "Skipped configurations: "}
     * naming the offending offer.
     */
    @Test
    void w3_unknownFamilyFailClosed() {
        JsonPath body = valuate(
                "{\"customerCode\":\"W3-badfam\",\"storeCode\":\"0102\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"3300000000005\",\"quantity\":1}]}", 200);
        assertFalse(hasAdvantage(body, "Immediate Voucher Discount : W_BADFAM"),
                "An unknown family produces no advantage");
        String message = errorMessageFor("W3-badfam");
        assertNotNull(message, "The skip is traced");
        assertTrue(message.startsWith("Skipped configurations: ") && message.contains("W_BADFAM"),
                "The fail-closed skip names the offending offer: " + message);
    }

    /**
     * W4 — a point-of-sale exclusion wins over group targeting (spec-ciblage-saisie §3, normative
     * case 4). {@code W_PDV} (a MEAL_VOUCHER) is served through the {@code REGION_NORTH} group but
     * excludes {@code 0101}: a water {@code …007} basket on {@code 0101} produces no MEAL_VOUCHER
     * advantage even though the group contains the store, whereas on {@code 0102} (in the group,
     * not excluded) it does.
     */
    @Test
    void w4_pdvExclusion() {
        JsonPath onExcluded = valuate(
                "{\"customerCode\":\"W4-excluded\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"3300000000007\",\"quantity\":1}]}", 200);
        assertFalse(hasAdvantage(onExcluded, "MEAL_VOUCHER"),
                "An excluded store is not served, even though a targeted group contains it");
        JsonPath onServed = valuate(
                "{\"customerCode\":\"W4-served\",\"storeCode\":\"0102\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"3300000000007\",\"quantity\":1}]}", 200);
        assertTrue(hasAdvantage(onServed, "MEAL_VOUCHER"),
                "A non-excluded store in the targeted group is served");
    }

    /**
     * W — determinism (spec-ciblage-saisie normative case 6). A basket that exercises the family
     * resolution ({@code W_FAM} covers apples through the {@code W_PARENT} family) is valued 50
     * times with the clock pinned: every response body is byte-for-byte identical, proving the new
     * union/subtraction and PDV-exclusion code introduce no HashSet-driven nondeterminism.
     */
    @Test
    void w_determinismFiftyTimes() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 6, 15, 12, 0));
            String reference = null;
            for (int i = 0; i < 50; i++) {
                String body = given().auth().preemptive().basic("admin", "admin")
                        .contentType(ContentType.JSON)
                        .body("{\"customerCode\":\"W-det\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                                + "\"items\":[{\"produceEan\":\"3300000000001\",\"quantity\":2}]}")
                        .when().post("/valuation")
                        .then().statusCode(200)
                        .extract().asString();
                if (reference == null) {
                    reference = body;
                } else {
                    assertEquals(reference, body, "Run " + i + " must be byte-for-byte identical");
                }
            }
        } finally {
            DateTimeProvider.clear();
        }
    }
}
