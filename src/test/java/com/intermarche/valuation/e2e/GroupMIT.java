package com.intermarche.valuation.e2e;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group M — titre-restaurant (assiette MEAL_VOUCHER) — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest} application,
 * HTTP Basic as {@code admin/admin}. The engine under test is {@code MealVoucherAdvantageFactory}:
 * a {@code MEAL_VOUCHER} offer emits, once per store offer, a {@code MEAL_VOUCHER} advantage whose
 * {@code totalEligibleAmount} sums the TTC of the products whose family hierarchy carries the
 * configured {@code flag}, net of the discounts attached to their offer, then (invisibly) capped at
 * {@code threshold}. The catalog is priced against real seeds, so the mirror catalog is replayed
 * ONCE at class start through the seven import endpoints in the mandated order (Stores &rarr;
 * StoreGroups &rarr; Products &rarr; ProductFamilies &rarr; Categories &rarr; Prices &rarr; Offers).
 * <p>
 * SEED FACTS the scenarios lean on (calibrated against the CSV seeds, not memory):
 * <ul>
 *   <li>{@code MEAL_VOUCHER_0101} carries {@code flag: RESTAURANT_VOUCHER_ELIGIBLE},
 *       {@code threshold: 25.00} on store {@code 0101}.</li>
 *   <li>The apple {@code …001} is eligible through family {@code POMMES}
 *       (flags {@code TRADITIONAL,RESTAURANT_VOUCHER_ELIGIBLE}); the eau {@code …007} through
 *       family {@code EAU_MINERALE} (flag {@code RESTAURANT_VOUCHER_ELIGIBLE}). CALIBRATION: the
 *       seed places the flag DIRECTLY on those leaf families, so {@code productHasFlag}'s hierarchy
 *       climb ({@code POMMES ← FRUITS ← ALIMENTAIRE}) resolves at the leaf; no seeded product is
 *       eligible through an ancestor-only flag, so the ancestor-only climb is a documented residue.</li>
 *   <li>The lait {@code …002} belongs to NO family, so it is NEVER eligible — it is the excluded
 *       line of M1's mixed plate.</li>
 *   <li>The eau {@code …007} is targeted by no product offer, so on {@code 0101} it is priced
 *       {@code 0.60} TTC by the base {@code BasicOffer} with no discount: {@code n} eau give an
 *       exact eligible plate of {@code n × 0.60}. The apple {@code …001} is targeted by two
 *       {@code IMMEDIATE_VOUCHER} discounts ({@code PROMO_STORE_101} 15%, {@code BRI_APPLES_DISCOUNT}
 *       0.10), so an apple plate is strictly net-of-discount.</li>
 * </ul>
 * <p>
 * ONE ADDITIVE IMPORT PHASE beyond the mirror seed, for M3's case-sensitivity probe: a second
 * {@code MEAL_VOUCHER} quarantined on store {@code 0102} ({@code M3_LOWERCASE_0102}) whose flag is
 * the LOWERCASE {@code restaurant_voucher_eligible}. The seed already prices the eau {@code …007}
 * on {@code 0102}, so the probe needs no extra price. {@code 0102} owns no {@code MEAL_VOUCHER} of
 * its own, so the additive offer is the sole meal voucher there.
 * <p>
 * TRANSVERSE GUARDS — {@code advantages} is a {@code HashSet} serialized in arbitrary order: the
 * meal-voucher application is always located by matching its literal {@code type} of
 * {@code MEAL_VOUCHER}, never by index, and pinned by its {@code offerCode}. Money is compared
 * scale-insensitively with {@link BigDecimal#compareTo}. The {@code payableAmount} field has no
 * getter (M2's invisible cap), so its absence is asserted on the raw response body.
 * <p>
 * CALIBRATION FINDINGS (catalog vs observed code):
 * <ul>
 *   <li>M2 — the catalog says {@code payableAmount = min(assiette, threshold)} is computed but
 *       ABSENT from the JSON. Confirmed: {@code MealVoucherAdvantageApplication} exposes only
 *       {@code offerCode}/{@code totalEligibleAmount}/{@code type}/{@code threshold}; there is no
 *       {@code getPayableAmount()}, so the field never serializes. The client sees the UNCAPPED
 *       assiette ({@code 30.00} for {@code 50 × 0.60}), never the {@code 25.00} cap.</li>
 *   <li>M3 — the flag match is EXACT-CASE ({@code getFlagsSet().contains(token.trim())} trims but
 *       never lower-cases): a {@code restaurant_voucher_eligible} offer flag does NOT match the
 *       seed's {@code RESTAURANT_VOUCHER_ELIGIBLE}, so its plate is {@code 0.00} even over eligible
 *       eau.</li>
 *   <li>M4 — the meal-voucher advantage is ALWAYS emitted, one per store {@code MEAL_VOUCHER}
 *       offer, even with an empty plate: a basket of only the flag-less lait still carries a
 *       {@code MEAL_VOUCHER} advantage at {@code 0.00}. Delivery and deposit are services (not
 *       {@code ProductAware}) and a gestured line is consumed by the ultra-priority applier, so all
 *       three stay out of the plate.</li>
 * </ul>
 */
@QuarkusTest
class GroupMIT {

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
     * The offer header shared by the additive offer row.
     */
    private static final String OFFER_HEADER =
            "offer_code|offer_type|specification|store_code|store_group_code\n";

    /**
     * The additive case-sensitivity probe: a {@code MEAL_VOUCHER} on {@code 0102} whose flag is the
     * LOWERCASE {@code restaurant_voucher_eligible}, so it never matches the seed's uppercase family
     * flag. Quarantined on {@code 0102}, which owns no meal voucher of its own.
     */
    private static final String EXTRA_OFFER = OFFER_HEADER
            + "M3_LOWERCASE_0102|MEAL_VOUCHER|{\"flag\": \"restaurant_voucher_eligible\", \"threshold\": 25.00}|0102|\n";

    /**
     * The Seclin delivery address (~10.23 km from store {@code 0101}), inside the {@code 16 km}
     * tier, for M4's delivery-exclusion probe.
     */
    private static final String SECLIN =
            "{\"latitude\":50.540,\"longitude\":3.030,\"city\":\"Seclin\",\"postalCode\":\"59113\",\"country\":\"France\"}";

    /**
     * Whether the mirror catalog and the additive phase have been imported in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the seven CSV imports plus the additive offer once, before the first scenario, so the
     * whole group shares one catalog.
     */
    @BeforeEach
    void seedCatalogOnce() {
        if (seeded) {
            return;
        }
        CatalogReset.resetMutableCatalog();
        for (String[] step : SEED) {
            importCsv(step[0], readResource(step[1]));
        }
        importCsv("/offers/import", EXTRA_OFFER);
        seeded = true;
    }

    /**
     * Reads a classpath resource into a string.
     *
     * @param path The classpath-relative resource path.
     * @return The resource content, UTF-8 decoded.
     */
    private static String readResource(String path) {
        try (var in = GroupMIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * @return The full response, for structural assertions on the parsed tree.
     */
    private Response valuate(String body, int expectedStatus) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/valuation")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    // --------------------------------------------------
    // JSON basket builders
    // --------------------------------------------------

    /**
     * Builds an {@code IN_STORE} basket.
     *
     * @param customer The customer code.
     * @param store    The store code.
     * @param items    The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String inStore(String customer, String store, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\",\"items\":[" + String.join(",", items) + "]}";
    }

    /**
     * Builds an {@code IN_STORE} basket carrying a raw {@code instructions} array fragment.
     *
     * @param customer     The customer code.
     * @param store        The store code.
     * @param instructions The raw JSON array for the {@code instructions} field.
     * @param items        The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String inStoreInstr(String customer, String store, String instructions, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\",\"instructions\":" + instructions + ",\"items\":["
                + String.join(",", items) + "]}";
    }

    /**
     * Builds a {@code HOME_DELIVERY} basket carrying a delivery address.
     *
     * @param customer The customer code.
     * @param store    The store code.
     * @param address  The raw {@code deliveryAddress} JSON object.
     * @param items    The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String homeDelivery(String customer, String store, String address, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"HOME_DELIVERY\",\"deliveryAddress\":" + address + ",\"items\":["
                + String.join(",", items) + "]}";
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
     * Builds an item fragment carrying a {@code manualForcedPrice} gesture.
     *
     * @param ean    The product EAN.
     * @param qty    The quantity, as a JSON literal.
     * @param forced The forced unit price, as a JSON literal.
     * @return The item JSON fragment.
     */
    private static String forced(String ean, String qty, String forced) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + ",\"manualForcedPrice\":" + forced + "}";
    }

    // --------------------------------------------------
    // Advantage + money reading helpers
    // --------------------------------------------------

    /**
     * Locates the sole {@code MEAL_VOUCHER} advantage in the evaluation, failing when more than one
     * is present; advantages are a {@code HashSet}, so it is found by its {@code type}, never by
     * index.
     *
     * @param body The parsed evaluation.
     * @return The meal-voucher advantage map, or null when absent.
     */
    private static Map<String, Object> mealVoucher(JsonPath body) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        Map<String, Object> found = null;
        for (Map<String, Object> advantage : advantages) {
            if ("MEAL_VOUCHER".equals(advantage.get("type"))) {
                assertNull(found, "More than one MEAL_VOUCHER advantage: " + advantages);
                found = advantage;
            }
        }
        return found;
    }

    /**
     * Reads the {@code totalEligibleAmount} of a meal-voucher advantage, scale-insensitively.
     *
     * @param mealVoucher The meal-voucher advantage map.
     * @return The eligible plate amount.
     */
    private static BigDecimal eligible(Map<String, Object> mealVoucher) {
        return new BigDecimal(String.valueOf(mealVoucher.get("totalEligibleAmount")));
    }

    /**
     * Reads the {@code threshold} of a meal-voucher advantage, scale-insensitively.
     *
     * @param mealVoucher The meal-voucher advantage map.
     * @return The configured threshold.
     */
    private static BigDecimal threshold(Map<String, Object> mealVoucher) {
        return new BigDecimal(String.valueOf(mealVoucher.get("threshold")));
    }

    /**
     * Reads the whole-basket including-tax total, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body The parsed evaluation.
     * @return The total including-tax price.
     */
    private static BigDecimal totalTtc(JsonPath body) {
        return new BigDecimal(body.getString("totalPrice.amountIncludingTax"));
    }

    /**
     * Whether at least one offer's {@code type} starts with the given prefix.
     *
     * @param body   The parsed evaluation.
     * @param prefix The {@code type} prefix.
     * @return True when an offer matches the prefix.
     */
    private static boolean offerPrefixPresent(JsonPath body, String prefix) {
        List<Object> types = body.getList("offers.type");
        for (Object type : types) {
            if (type != null && type.toString().startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
    // M1 — nominal plate
    // --------------------------------------------------

    /**
     * M1 — the nominal plate sums the eligible lines, resolves the flag through the family
     * hierarchy, excludes the flag-less lait and exposes the threshold. On {@code 0101}
     * ({@code MEAL_VOUCHER_0101}, flag {@code RESTAURANT_VOUCHER_ELIGIBLE}, threshold {@code 25.00}):
     * apples {@code …001} &times;2 (eligible through {@code POMMES}) + eau {@code …007} &times;3
     * (eligible through {@code EAU_MINERALE}) + lait {@code …002} &times;1 (no family, excluded).
     * Exactly one {@code MEAL_VOUCHER} advantage is emitted, pinned by {@code offerCode
     * MEAL_VOUCHER_0101}; its {@code threshold} is the exposed {@code 25.00}; its
     * {@code totalEligibleAmount} is {@code > 0} and strictly {@code <} the whole-basket total
     * (proving the {@code 3.00} lait line is out of the plate).
     */
    @Test
    void m1_nominalPlate() {
        JsonPath body = valuate(inStore("M1", "0101",
                plain("3300000000001", "2"),
                plain("3300000000007", "3"),
                plain("3300000000002", "1")), 200).jsonPath();
        Map<String, Object> mv = mealVoucher(body);
        assertNotNull(mv, "The MEAL_VOUCHER advantage must be emitted: " + body.getList("advantages"));
        assertEquals("MEAL_VOUCHER_0101", String.valueOf(mv.get("offerCode")),
                "The plate is pinned to the store's meal-voucher offer");
        assertMoney("25.00", threshold(mv), "The configured cap is exposed in the JSON");
        assertTrue(eligible(mv).compareTo(BigDecimal.ZERO) > 0,
                "The eligible plate is positive (apples + eau): " + mv);
        assertTrue(eligible(mv).compareTo(totalTtc(body)) < 0,
                "The plate is strictly below the total: the flag-less lait is excluded: " + mv
                        + " total=" + totalTtc(body));
    }

    // --------------------------------------------------
    // M2 — invisible cap
    // --------------------------------------------------

    /**
     * M2 — the cap is computed but invisible. On {@code 0101}, {@code 50} eau {@code …007}
     * (untargeted, priced {@code 0.60} TTC by the base offer) build an exact eligible plate of
     * {@code 50 × 0.60 = 30.00}, which exceeds the {@code 25.00} threshold. The engine computes
     * {@code payableAmount = min(30.00, 25.00) = 25.00} but exposes NO getter for it: the raw body
     * carries no {@code payableAmount} field, so the client sees only the UNCAPPED {@code 30.00}
     * assiette and the {@code 25.00} threshold, never the {@code 25.00} cap itself.
     */
    @Test
    void m2_invisibleCap() {
        Response response = valuate(inStore("M2", "0101", plain("3300000000007", "50")), 200);
        JsonPath body = response.jsonPath();
        Map<String, Object> mv = mealVoucher(body);
        assertNotNull(mv, "The MEAL_VOUCHER advantage must be emitted: " + body.getList("advantages"));
        assertMoney("30.00", eligible(mv), "50 eau at 0.60 build the uncapped 30.00 plate");
        assertMoney("25.00", threshold(mv), "The cap is exposed as the threshold");
        assertTrue(eligible(mv).compareTo(threshold(mv)) > 0,
                "The plate exceeds the cap: the cap is engaged but invisible: " + mv);
        assertFalse(response.asString().contains("payableAmount"),
                "CALIBRATION: payableAmount has no getter and never serializes: " + response.asString());
    }

    // --------------------------------------------------
    // M3 — plate net of discounts, case-sensitive flag
    // --------------------------------------------------

    /**
     * M3 — the plate is net of the discounts attached to the eligible offer, and the flag match is
     * case-sensitive.
     * <ul>
     *   <li>NET — on {@code 0101}, apples {@code …001} &times;2 are targeted by two
     *       {@code IMMEDIATE_VOUCHER} discounts ({@code PROMO_STORE_101} 15%,
     *       {@code BRI_APPLES_DISCOUNT} 0.10), so a real {@code discountAmount} advantage is present
     *       and the eligible plate is strictly {@code net-of-discount}: positive, yet {@code <} the
     *       gross apple line ({@code 2 × 1.20 = 2.40} at the catalog DEFAULT, an upper bound on any
     *       price selection).</li>
     *   <li>CASE — on {@code 0102}, the additive {@code M3_LOWERCASE_0102} carries the LOWERCASE
     *       flag {@code restaurant_voucher_eligible}. Over {@code 5} eligible eau {@code …007}, the
     *       exact-case family flag {@code RESTAURANT_VOUCHER_ELIGIBLE} never matches, so its plate is
     *       {@code 0.00} despite the eligible goods.</li>
     * </ul>
     */
    @Test
    void m3_netOfDiscountsAndCaseSensitiveFlag() {
        JsonPath net = valuate(inStore("M3-net", "0101", plain("3300000000001", "2")), 200).jsonPath();
        Map<String, Object> netMv = mealVoucher(net);
        assertNotNull(netMv, "The MEAL_VOUCHER advantage must be emitted: " + net.getList("advantages"));
        List<Map<String, Object>> netAdvantages = net.getList("advantages");
        assertTrue(netAdvantages.stream().anyMatch(a -> a.get("discountAmount") != null),
                "The apple carries a real discount, deducted from the plate: " + netAdvantages);
        assertTrue(eligible(netMv).compareTo(BigDecimal.ZERO) > 0,
                "The net plate is still positive: " + netMv);
        assertTrue(eligible(netMv).compareTo(new BigDecimal("2.40")) < 0,
                "The plate is net-of-discount, below the 2.40 gross apple line: " + netMv);
        JsonPath cased = valuate(inStore("M3-case", "0102", plain("3300000000007", "5")), 200).jsonPath();
        Map<String, Object> caseMv = mealVoucher(cased);
        assertNotNull(caseMv, "The lowercase-flag advantage is still emitted: " + cased.getList("advantages"));
        assertEquals("M3_LOWERCASE_0102", String.valueOf(caseMv.get("offerCode")),
                "The plate is the additive lowercase-flag offer");
        assertMoney("0.00", eligible(caseMv),
                "A lowercase flag never matches the uppercase family flag: empty plate: " + caseMv);
    }

    // --------------------------------------------------
    // M4 — exclusions and the always-emitted plate
    // --------------------------------------------------

    /**
     * M4 — exclusions and the always-emitted plate. Four probes on {@code 0101}:
     * <ul>
     *   <li>NO FLAG — a basket of only the flag-less lait {@code …002} still emits one
     *       {@code MEAL_VOUCHER} advantage at {@code 0.00}: the plate is always emitted, one per
     *       store offer.</li>
     *   <li>DELIVERY — {@code 5} eau {@code …007} in {@code HOME_DELIVERY} to Seclin: the plate is
     *       the exact {@code 5 × 0.60 = 3.00} eau, the {@code 9.90} delivery service is present as an
     *       offer but stays OUT of the plate, so the plate is strictly below the delivered total.</li>
     *   <li>DEPOSIT — {@code 5} eau {@code …007} {@code IN_STORE} with the {@code Deposit basket}
     *       instruction: the {@code 0.50} consignment is present as an offer, yet the plate is again
     *       the exact {@code 3.00} eau — the deposit stays out.</li>
     *   <li>GESTURE — apples {@code …001} &times;2 under {@code manualForcedPrice}: the ultra-priority
     *       gesture applier consumes the line first, so the sole eligible line is invisible to the
     *       plate, which collapses to {@code 0.00}.</li>
     * </ul>
     */
    @Test
    void m4_exclusionsAndAlwaysEmitted() {
        JsonPath noFlag = valuate(inStore("M4-noflag", "0101", plain("3300000000002", "1")), 200).jsonPath();
        Map<String, Object> noFlagMv = mealVoucher(noFlag);
        assertNotNull(noFlagMv, "The plate is emitted even with no eligible line: " + noFlag.getList("advantages"));
        assertMoney("0.00", eligible(noFlagMv), "No flagged product gives an empty but emitted plate: " + noFlagMv);
        JsonPath delivery = valuate(homeDelivery("M4-deliv", "0101", SECLIN, plain("3300000000007", "5")), 200).jsonPath();
        Map<String, Object> deliveryMv = mealVoucher(delivery);
        assertNotNull(deliveryMv, "The plate is emitted with delivery: " + delivery.getList("advantages"));
        assertMoney("3.00", eligible(deliveryMv), "5 eau at 0.60; the 9.90 delivery is out of the plate: " + deliveryMv);
        assertTrue(offerPrefixPresent(delivery, "Delivery:"),
                "The delivery service is present as an offer: " + delivery.getList("offers.type"));
        assertTrue(eligible(deliveryMv).compareTo(totalTtc(delivery)) < 0,
                "The plate is below the delivered total: the delivery cost is excluded: " + deliveryMv);
        JsonPath deposit = valuate(inStoreInstr("M4-depo", "0101", "[\"Deposit basket\"]",
                plain("3300000000007", "5")), 200).jsonPath();
        Map<String, Object> depositMv = mealVoucher(deposit);
        assertNotNull(depositMv, "The plate is emitted with a deposit: " + deposit.getList("advantages"));
        assertMoney("3.00", eligible(depositMv), "5 eau at 0.60; the 0.50 deposit is out of the plate: " + depositMv);
        assertTrue(offerPrefixPresent(deposit, "Deposit Basket:"),
                "The consignment is present as an offer: " + deposit.getList("offers.type"));
        JsonPath gesture = valuate(inStore("M4-gest", "0101", forced("3300000000001", "2", "1.0")), 200).jsonPath();
        Map<String, Object> gestureMv = mealVoucher(gesture);
        assertNotNull(gestureMv, "The plate is emitted with a gestured line: " + gesture.getList("advantages"));
        assertMoney("0.00", eligible(gestureMv),
                "A gestured eligible line is consumed first and invisible to the plate: " + gestureMv);
    }
}
