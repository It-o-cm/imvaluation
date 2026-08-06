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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group J — N+M &amp; mixed bundles — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application, HTTP Basic as {@code admin/admin}. The group needs the referential (the two
 * heart-of-the-engine appliers, {@code NPlusMOfferApplier} and {@code MixedBundleOfferApplier},
 * price every bundle from the catalog), so the mirror catalog is replayed ONCE at class start
 * through the seven import endpoints in the mandated order (Stores &rarr; StoreGroups &rarr;
 * Products &rarr; ProductFamilies &rarr; Categories &rarr; Prices &rarr; Offers).
 * <p>
 * ENGINES UNDER TEST — {@code NPlusMOfferFactory} (the "Mixed Bundle Promo" N+M applier),
 * {@code MixedBundleOfferFactory} (the "MixedBundle" fixed-price / discount applier) and their
 * two upsell twins {@code NPlusMUpsellAdvantageFactory} / {@code MixedBundleUpsellAdvantageFactory}.
 * The N+M applier sizes bundles on the LIVE remaining pool ({@code floor(total / (N+M))}), sorts
 * candidates by {@code BASE_FOR_DISCOUNT} pre-tax price (CHEAPEST ascending, MOST_EXPENSIVE
 * descending), fills discounted slots FIRST then paid slots, and prices every block on
 * {@code BASE_FOR_DISCOUNT} (a PERCENTAGE cut multiplies the block, a FIXED_AMOUNT is capped at
 * the block so a line never turns negative). The bundle applier derives the VAT rate from the
 * covered goods ({@code TTC/HT - 1}) rather than the declared {@code vatRate}, which is only a
 * fallback.
 * <p>
 * TWO EXTRA IMPORT PHASES beyond the mirror seed, both additive (distinct offer codes / price
 * rows, so no checksum no-op collision):
 * <ol>
 *   <li>Custom NON-poison N+M / bundle offers are imported onto the full store {@code 0101},
 *       each targeting a DISJOINT set of UNIT products that no other J scenario touches. An N+M
 *       or bundle offer builds an applier only when a target EAN is present in the basket
 *       (and its upsell twin, though built for every store offer, yields nothing when its EANs
 *       are absent), so a basket carrying only one scenario's EANs is blind to the others.</li>
 *   <li>POISON offers (J6, J9) are quarantined on private stores this class CREATES for the
 *       purpose ({@code 0106} for J6A, {@code 0107} for J6B, {@code 0108} for J9) — codes outside
 *       the {@code 0101}–{@code 0105} mirror catalog, so no sibling {@code @QuarkusTest} class ever
 *       valuates them (the whole suite shares ONE drop-and-create H2 instance, so a poison pinned to
 *       a shared catalog store would bleed across classes). A poison offer is rejected by the upsell
 *       factory's schema during {@code createDiscountAppliers} — step 2 of the engine, BEFORE
 *       any price resolution — so EVERY valuation of that store returns 500. Quarantining keeps
 *       the poison from bleeding into the {@code 0101} scenarios. A single {@code DEFAULT}/
 *       {@code BASE_FOR_DISCOUNT} price is imported for the probe product on each poison store so
 *       no benign "No active price" error can preempt the poison message.</li>
 * </ol>
 * <p>
 * TRANSVERSE GUARDS — {@code offers} and {@code advantages} are {@code HashSet}s serialized in
 * arbitrary order: a bundle offer is always located by its literal {@code type} (never by index),
 * an upsell advantage by its {@code suggestion.offerCode}. 4xx/5xx bodies of {@code /valuation}
 * carry no entity: the J6/J9 poison rejections are asserted on
 * {@code valuation_traces.error_message} (Panache under {@code QuarkusTransaction}), keyed by a
 * unique {@code customerCode} per probe, never the raw HTTP body. Money is compared scale-
 * insensitively with {@link BigDecimal#compareTo}. The two upsell {@code type} literals go
 * through {@code String.format("%.2f")}, whose decimal separator is locale-dependent (a comma in
 * {@code fr_FR}); the expected string is rebuilt in-test with the SAME {@code String.format} in
 * the SAME JVM as the app, so the assertion matches whatever locale the app runs under.
 * <p>
 * CALIBRATION FINDINGS (catalog vs observed code):
 * <ul>
 *   <li>J5 — the 1+1 aliasing defect: {@code createApplicationsFromPool} hands the SAME two
 *       mutable lists to every {@code NPlusMApplication} then {@code clear()}s and refills them, so
 *       both applications alias the final ({@code …027}) lot and the whole N+M offer totals
 *       {@code 10.56} instead of the correct per-lot {@code 7.92}. This test GRAVES that symptom.</li>
 *   <li>J6 — both toxic configs ({@code quantityToPay:0}, and {@code quantityToPay:0} with
 *       {@code discountedQuantity:0}) fail the SAME upsell-schema guard ({@code quantityToPay}
 *       minimum 1) at step 2; that guard preempts the division-by-zero the catalog attributes to
 *       the second config, so both surface the identical validation message.</li>
 *   <li>J8 — a {@code discount}-mode bundle is unreachable (it poisons the store, see J9), so
 *       the multi-rate VAT-derivation the catalog describes is proven on the reachable FIXED-price
 *       bundle instead: the effective rate is the blend derived from the covered goods, not the
 *       declared {@code vatRate}.</li>
 *   <li>J10 — the catalog says "2 lots max" for 5 coffees + 2 biscuits + 1 chips, but the code
 *       yields {@code min(floor(5/1), floor(3/1)) = 3} bundles; three is also the only count that
 *       lets the chips substitute be "consumed as a last resort" (bundles 1-2 use the two
 *       biscuits, bundle 3 falls back to the chips). Pinned at 3.</li>
 * </ul>
 */
@QuarkusTest
class GroupJIT {

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
     * The price header shared by every extra price row imported for the poison stores.
     */
    private static final String PRICE_HEADER =
            "ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime\n";

    /**
     * The store header for the private poison stores this class creates.
     */
    private static final String STORE_HEADER =
            "code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude\n";

    /**
     * Three private stores created for this class alone, outside the {@code 0101}–{@code 0105}
     * mirror catalog, so the poison offers pinned to them can never leak into a sibling
     * {@code @QuarkusTest} class sharing the same H2 instance: {@code 0106} (J6A), {@code 0107}
     * (J6B), {@code 0108} (J9).
     */
    private static final String EXTRA_STORES = STORE_HEADER
            + "0106|Poison Quarantine J6A|1 Rue Poison||00000|Nowhere|France|0.00|0.00\n"
            + "0107|Poison Quarantine J6B|2 Rue Poison||00000|Nowhere|France|0.00|0.00\n"
            + "0108|Poison Quarantine J9|3 Rue Poison||00000|Nowhere|France|0.00|0.00\n";

    /**
     * Extra prices: the water probe ({@code …007}) is priced on the three poison stores so a
     * poison valuation reaches the applier-building step instead of failing on a missing price.
     */
    private static final String EXTRA_PRICES = PRICE_HEADER
            + "3300000000007|0106|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0106|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0107|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0107|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0108|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0108|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n";

    /**
     * The offer header shared by every extra offer row.
     */
    private static final String OFFER_HEADER =
            "offer_code|offer_type|specification|store_code|store_group_code\n";

    /**
     * Extra offers, all additive to the mirror catalog:
     * <ul>
     *   <li>{@code PROMO_J3A_CHEAP} / {@code PROMO_J3B_EXP} — J3 strategy contrast (CHEAPEST vs
     *       MOST_EXPENSIVE), disjoint pairs on {@code 0101}.</li>
     *   <li>{@code PROMO_J4_FIXED} — J4 capped fixed-amount N+M on {@code 0101}.</li>
     *   <li>{@code PROMO_J5_ALIAS} — J5 1+1 CHEAPEST, the aliasing defect, on {@code 0101}.</li>
     *   <li>{@code PROMO_J8_BUNDLE} — J8 multi-rate FIXED-price bundle on {@code 0101}.</li>
     *   <li>{@code POISON_J6A} ({@code 0106}), {@code POISON_J6B} ({@code 0107}),
     *       {@code POISON_J9} ({@code 0108}) — the quarantined poison offers, each on a private
     *       store this class creates.</li>
     * </ul>
     */
    private static final String EXTRA_OFFERS = OFFER_HEADER
            + "PROMO_J3A_CHEAP|N+M|{\"targetEans\": [\"3300000000006\", \"3300000000003\"], \"quantityToPay\": 1, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0}|0101|\n"
            + "PROMO_J3B_EXP|N+M|{\"targetEans\": [\"3300000000028\", \"3300000000010\"], \"quantityToPay\": 1, \"discountedQuantity\": 1, \"selectionStrategy\": \"MOST_EXPENSIVE\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0}|0101|\n"
            + "PROMO_J4_FIXED|N+M|{\"targetEans\": [\"3300000000012\"], \"quantityToPay\": 1, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"FIXED_AMOUNT\", \"discountValue\": 50.0}|0101|\n"
            + "PROMO_J5_ALIAS|N+M|{\"targetEans\": [\"3300000000027\", \"3300000000026\"], \"quantityToPay\": 1, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0}|0101|\n"
            + "PROMO_J8_BUNDLE|MIXED_BUNDLE|{\"bundlePrice\": 8.00, \"vatRate\": 0.20, \"contents\": [{\"ean\": \"3300000000022\", \"quantity\": 1.0}, {\"ean\": \"3300000000024\", \"quantity\": 1.0}]}|0101|\n"
            + "POISON_J6A|N+M|{\"targetEans\": [\"3300000000007\"], \"quantityToPay\": 0, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0}|0106|\n"
            + "POISON_J6B|N+M|{\"targetEans\": [\"3300000000007\"], \"quantityToPay\": 0, \"discountedQuantity\": 0, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0}|0107|\n"
            + "POISON_J9|MIXED_BUNDLE|{\"discount\": {\"type\": \"PERCENTAGE\", \"value\": 10.0}, \"vatRate\": 0.20, \"contents\": [{\"ean\": \"3300000000007\", \"quantity\": 1.0}]}|0108|\n";

    /**
     * Whether the mirror catalog and the two extra phases have been imported in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the seven CSV imports plus the two extra phases once, before the first scenario, so
     * the whole group shares one catalog. The static guard runs the imports exactly once even
     * though seeding happens in a {@code @BeforeEach} (the RestAssured port is wired per test
     * instance).
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
        importCsv("/stores/import", EXTRA_STORES);
        importCsv("/prices/import", EXTRA_PRICES);
        importCsv("/offers/import", EXTRA_OFFERS);
        seeded = true;
    }

    /**
     * Reads a classpath resource into a string.
     *
     * @param path The classpath-relative resource path.
     * @return The resource content, UTF-8 decoded.
     */
    private static String readResource(String path) {
        try (var in = GroupJIT.class.getClassLoader().getResourceAsStream(path)) {
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
    }

    /**
     * Loads the latest trace for a customer code and projects its columns inside the transaction,
     * so the returned view survives the closed session.
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
            }
        });
        return view;
    }

    /**
     * Posts a basket expected to fail with a 500 and asserts the FAILED trace carries the literal
     * fragment; the fragment is a substring match, tolerant of the engine wrapping prefix.
     *
     * @param customer         The unique customer code for the probe.
     * @param body             The basket JSON.
     * @param expectedFragment The literal that {@code error_message} must contain.
     */
    private void assertPoison(String customer, String body, String expectedFragment) {
        valuate(body, 500);
        TraceView trace = traceFor(customer);
        assertTrue(trace.found, "A FAILED trace must exist for " + customer);
        assertEquals(ValuationTrace.STATUS_FAILED, trace.status, "Status for " + customer);
        assertEquals(500, trace.httpStatus, "HTTP status for " + customer);
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
     * Builds an identified line fragment (line id + EAN + quantity).
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
     * Collects the indices of the offers whose {@code type} exactly equals the given literal.
     * <p>
     * {@code offers} is a {@code HashSet} serialized in arbitrary order, so an application is
     * always located by its literal type, never by a fixed index.
     *
     * @param body The parsed evaluation.
     * @param type The exact {@code type} literal.
     * @return The indices of the matching offers, possibly empty.
     */
    private static List<Integer> offerIndicesByType(JsonPath body, String type) {
        List<Integer> indices = new ArrayList<>();
        int count = body.getList("offers").size();
        for (int i = 0; i < count; i++) {
            if (type.equals(body.getString("offers[" + i + "].type"))) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Collects the indices of the offers whose {@code type} starts with the given prefix.
     *
     * @param body   The parsed evaluation.
     * @param prefix The {@code type} prefix.
     * @return The indices of the matching offers, possibly empty.
     */
    private static List<Integer> offerIndicesByPrefix(JsonPath body, String prefix) {
        List<Integer> indices = new ArrayList<>();
        int count = body.getList("offers").size();
        for (int i = 0; i < count; i++) {
            String type = body.getString("offers[" + i + "].type");
            if (type != null && type.startsWith(prefix)) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Finds the single N+M application of a given offer code, by its literal type, and returns its
     * offer index. Fails when the count is not exactly one.
     *
     * @param body The parsed evaluation.
     * @param code The N+M offer code.
     * @return The index of the sole matching offer.
     */
    private static int soleNplusM(JsonPath body, String code) {
        List<Integer> idx = offerIndicesByType(body, "Mixed Bundle Promo: " + code);
        assertEquals(1, idx.size(), "Exactly one N+M application for " + code + " expected, offers: "
                + body.getList("offers.type"));
        return idx.get(0);
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
     * Reads the VAT rate of an offer, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body  The parsed evaluation.
     * @param index The offer index.
     * @return The offer's VAT rate.
     */
    private static BigDecimal offerVat(JsonPath body, int index) {
        return new BigDecimal(body.getString("offers[" + index + "].amount.vatRate"));
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
     * Sums the quantity a set of offers restore for a specific EAN.
     *
     * @param body    The parsed evaluation.
     * @param indices The offer indices.
     * @param ean     The product EAN.
     * @return The total quantity of the EAN across the given offers.
     */
    private static double eanQtyIn(JsonPath body, List<Integer> indices, String ean) {
        double total = 0.0;
        for (int i : indices) {
            List<Map<String, Object>> items = body.getList("offers[" + i + "].items");
            for (Map<String, Object> it : items) {
                if (ean.equals(it.get("produceEan"))) {
                    total += ((Number) it.get("quantity")).doubleValue();
                }
            }
        }
        return total;
    }

    /**
     * Finds the upsell advantage whose {@code suggestion.offerCode} equals the given code.
     *
     * @param body      The parsed evaluation.
     * @param offerCode The offer code carried by the suggestion.
     * @return The advantage map, or null when none matches.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> upsellFor(JsonPath body, String offerCode) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        for (Map<String, Object> adv : advantages) {
            Object suggestion = adv.get("suggestion");
            if (suggestion instanceof Map
                    && offerCode.equals(((Map<String, Object>) suggestion).get("offerCode"))) {
                return adv;
            }
        }
        return null;
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
    // J1 — N+M exact
    // --------------------------------------------------

    /**
     * J1 — N+M exact. Three apples {@code …001} under {@code PROMO_2FOR1_3300} (2 pay + 1 free,
     * CHEAPEST, PERCENTAGE 100) form exactly one bundle exposed as a SINGLE application carrying
     * two items — the paid block (2 units) and the free block (1 unit), both restoring the source
     * {@code lineId}. The amount is 2 &times; the active {@code BASE_FOR_DISCOUNT} price (priority
     * 1 wins: {@code 0.99}/{@code 1.19}), so {@code 1.98} HT / {@code 2.38} TTC, the free unit
     * contributing zero. The type literal is the deliberately misleading
     * {@code Mixed Bundle Promo: PROMO_2FOR1_3300}.
     */
    @Test
    void j1_nPlusMExact() {
        JsonPath body = valuate(basket("J1", "0101", line("APL", "3300000000001", "3")), 200).jsonPath();
        int idx = soleNplusM(body, "PROMO_2FOR1_3300");
        assertMoney("2.38", offerTtc(body, idx), "2 paid units at the 1.19 BASE_FOR_DISCOUNT price");
        assertMoney("1.98", offerHt(body, idx), "2 paid units at the 0.99 BASE_FOR_DISCOUNT HT price");
        assertEquals(3.0, offerQty(body, idx), 1e-9, "The bundle covers all 3 units (2 paid + 1 free)");
        List<Map<String, Object>> items = body.getList("offers[" + idx + "].items");
        assertEquals(2, items.size(), "One paid block + one free block = exactly two items");
        for (Map<String, Object> it : items) {
            assertEquals("3300000000001", it.get("produceEan"), "Both items are the source apple");
            assertEquals("APL", it.get("lineId"), "Both items restore the source line id");
        }
    }

    // --------------------------------------------------
    // J2 — leftover + suggestion
    // --------------------------------------------------

    /**
     * J2 — leftover &amp; upsell suggestion. Five apples {@code …001} form one 2+1 bundle
     * (consuming 3) and leave 2 units priced {@code Standard: EAN=3300000000001, Qty=2.0}. The
     * remaining pool then feeds one N+M upsell advantage suggesting 1 more apple to reach the next
     * bundle: {@code suggestion = {ean:…001, quantity:1.0, offerCode:PROMO_2FOR1_3300}} and the
     * type literal {@code Upsell N+M: PROMO_2FOR1_3300 (Need 1,00 of 3300000000001)}. That literal
     * runs through {@code String.format("%.2f")}, whose separator is locale-dependent, so the
     * expectation is rebuilt with the same call in the same JVM.
     */
    @Test
    void j2_leftoverAndSuggestion() {
        JsonPath body = valuate(basket("J2", "0101", plain("3300000000001", "5")), 200).jsonPath();
        int nm = soleNplusM(body, "PROMO_2FOR1_3300");
        assertEquals(3.0, offerQty(body, nm), 1e-9, "One bundle consumes exactly 3 of the 5 apples");
        assertEquals(1, offerIndicesByType(body, "Standard: EAN=3300000000001, Qty=2.0").size(),
                "The 2 leftover apples are priced Standard: " + body.getList("offers.type"));
        Map<String, Object> upsell = upsellFor(body, "PROMO_2FOR1_3300");
        assertNotNull(upsell, "An N+M upsell suggestion must be present: " + body.getList("advantages"));
        String expectedType = String.format("Upsell N+M: %s (Need %.2f of %s)",
                "PROMO_2FOR1_3300", 1.0, "3300000000001");
        assertEquals(expectedType, upsell.get("type"), "The upsell type literal (locale-formatted)");
        @SuppressWarnings("unchecked")
        Map<String, Object> suggestion = (Map<String, Object>) upsell.get("suggestion");
        assertEquals("3300000000001", suggestion.get("ean"), "The suggestion targets the apple");
        assertEquals(1.0, ((Number) suggestion.get("quantity")).doubleValue(), 1e-9,
                "One more apple completes the next 2+1 bundle");
    }

    // --------------------------------------------------
    // J3 — selection strategies
    // --------------------------------------------------

    /**
     * J3 — selection strategies invert which block is filled first. A CHEAPEST 1+1
     * ({@code PROMO_J3A_CHEAP} on {@code …006}/{@code …003}) fills the DISCOUNTED slot with the
     * cheapest ({@code …003}, free) and PAYS the expensive {@code …006}
     * ({@code BASE_FOR_DISCOUNT} {@code 5.50}/{@code 6.60}) — total {@code 6.60} TTC. A
     * MOST_EXPENSIVE 1+1 ({@code PROMO_J3B_EXP} on {@code …028}/{@code …010}) does the inverse:
     * it discounts the expensive {@code …028} and PAYS the cheap {@code …010}
     * ({@code 1.65}/{@code 1.98}) — total {@code 1.98} TTC. Both PERCENTAGE 100, verified to the
     * cent in the two senses.
     */
    @Test
    void j3_selectionStrategies() {
        JsonPath cheap = valuate(basket("J3-cheap", "0101",
                plain("3300000000006", "1"), plain("3300000000003", "1")), 200).jsonPath();
        int cIdx = soleNplusM(cheap, "PROMO_J3A_CHEAP");
        assertMoney("6.60", offerTtc(cheap, cIdx), "CHEAPEST discounts the cheap 003, so the expensive 006 is paid");
        assertMoney("5.50", offerHt(cheap, cIdx), "The paid 006 at its BASE_FOR_DISCOUNT HT price");
        JsonPath exp = valuate(basket("J3-exp", "0101",
                plain("3300000000028", "1"), plain("3300000000010", "1")), 200).jsonPath();
        int eIdx = soleNplusM(exp, "PROMO_J3B_EXP");
        assertMoney("1.98", offerTtc(exp, eIdx), "MOST_EXPENSIVE discounts the expensive 028, so the cheap 010 is paid");
        assertMoney("1.65", offerHt(exp, eIdx), "The paid 010 at its BASE_FOR_DISCOUNT HT price");
    }

    // --------------------------------------------------
    // J4 — capped fixed-amount N+M
    // --------------------------------------------------

    /**
     * J4 — a fixed-amount discount is capped at the discounted block, never negative. A
     * {@code FIXED_AMOUNT 50.0} 1+1 ({@code PROMO_J4_FIXED} on {@code …012}) over two units builds
     * one bundle whose discounted block is a single {@code …012} worth {@code 2.51} TTC. The
     * {@code 50.0} discount is capped at the block ({@code 2.51}), zeroing the free unit rather
     * than going negative, so the bundle totals just the paid unit: {@code 2.51} TTC /
     * {@code 2.09} HT.
     */
    @Test
    void j4_cappedFixedAmount() {
        JsonPath body = valuate(basket("J4", "0101", plain("3300000000012", "2")), 200).jsonPath();
        int idx = soleNplusM(body, "PROMO_J4_FIXED");
        assertMoney("2.51", offerTtc(body, idx), "The 50.0 discount is capped at the 2.51 block: only the paid unit remains");
        assertMoney("2.09", offerHt(body, idx), "The paid unit HT, the discounted block floored at zero");
        assertTrue(offerTtc(body, idx).compareTo(BigDecimal.ZERO) >= 0, "A capped fixed discount never turns the line negative");
    }

    // --------------------------------------------------
    // J5 — heterogeneous multi-lot aliasing defect
    // --------------------------------------------------

    /**
     * J5 — the multi-lot aliasing defect, graved as a NEGATIVE test. A 1+1 CHEAPEST
     * ({@code PROMO_J5_ALIAS} on {@code …027}/{@code …026}) over 2 expensive + 2 cheap units forms
     * two lots. The per-lot pick assigns lot 1 the cheap {@code …026} (paid {@code 2.64}, one free)
     * and lot 2 the expensive {@code …027} (paid {@code 5.28}, one free), so the bug-free total is
     * {@code 2.64 + 5.28 = 7.92} TTC. But {@code createApplicationsFromPool} hands the SAME two
     * mutable lists to every {@code NPlusMApplication} then {@code clear()}s and refills them, so
     * BOTH applications end up aliasing the final ({@code …027}) lot and each reports {@code 5.28}:
     * the offer totals {@code 5.28 + 5.28 = 10.56} instead of {@code 7.92}. This test pins the
     * OBSERVED (buggy) {@code 10.56} and asserts it diverges from the correct {@code 7.92}.
     */
    @Test
    void j5_multiLotAliasingDefect() {
        JsonPath body = valuate(basket("J5", "0101",
                plain("3300000000027", "2"), plain("3300000000026", "2")), 200).jsonPath();
        List<Integer> aliased = offerIndicesByType(body, "Mixed Bundle Promo: PROMO_J5_ALIAS");
        assertFalse(aliased.isEmpty(), "The aliased N+M offer must produce at least one application");
        BigDecimal observed = BigDecimal.ZERO;
        for (int i : aliased) {
            observed = observed.add(offerTtc(body, i));
        }
        assertMoney("10.56", observed, "OBSERVED symptom: both lots alias the final 027 lot (5.28 + 5.28)");
        assertNotEquals(0, new BigDecimal("7.92").compareTo(observed),
                "The observed total diverges from the correct 7.92 (lot 1 pays cheap 026, lot 2 pays expensive 027)");
    }

    // --------------------------------------------------
    // J7 — fixed-price bundle & substitute
    // --------------------------------------------------

    /**
     * J7 — fixed-price bundle, substitute, and inert bundle. Coffee {@code …004} + biscuits
     * {@code …013} under {@code PROMO_COFFEE_PACK} (bundle price {@code 4.50}) prices one bundle
     * {@code MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€}. Swapping the biscuits for their
     * declared substitute chips {@code …014} yields the SAME {@code 4.50} bundle. Coffee ALONE
     * builds no applier at all (a missing component makes the whole offer inert) — the coffee is
     * then priced {@code Standard}.
     */
    @Test
    void j7_fixedPriceBundleAndSubstitute() {
        JsonPath withBiscuit = valuate(basket("J7-biscuit", "0101",
                plain("3300000000004", "1"), plain("3300000000013", "1")), 200).jsonPath();
        assertEquals(1, offerIndicesByType(withBiscuit, "MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€").size(),
                "Coffee + biscuits form one 4.50 bundle: " + withBiscuit.getList("offers.type"));
        JsonPath withChips = valuate(basket("J7-chips", "0101",
                plain("3300000000004", "1"), plain("3300000000014", "1")), 200).jsonPath();
        assertEquals(1, offerIndicesByType(withChips, "MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€").size(),
                "Coffee + the chips substitute also form one 4.50 bundle: " + withChips.getList("offers.type"));
        JsonPath coffeeAlone = valuate(basket("J7-alone", "0101", plain("3300000000004", "1")), 200).jsonPath();
        assertTrue(offerIndicesByPrefix(coffeeAlone, "MixedBundle: PROMO_COFFEE_PACK").isEmpty(),
                "Coffee alone builds no bundle applier (missing component): " + coffeeAlone.getList("offers.type"));
        assertEquals(1, offerIndicesByType(coffeeAlone, "Standard: EAN=3300000000004, Qty=1.0").size(),
                "The lone coffee falls back to the standard tariff: " + coffeeAlone.getList("offers.type"));
    }

    // --------------------------------------------------
    // J8 — VAT derived from covered goods
    // --------------------------------------------------

    /**
     * J8 — a multi-rate bundle's VAT rate is DERIVED from its goods, not the declared value. The
     * discount-mode bundle the catalog describes is unreachable (it poisons the store, see J9), so
     * the derivation is proven on the reachable FIXED-price bundle {@code PROMO_J8_BUNDLE}
     * (declared {@code vatRate 0.20}, price {@code 8.00}) covering riz {@code …022} at 5.5% and
     * miel {@code …024} at 20%. The reference goods total {@code 8.25} HT / {@code 9.50} TTC, so
     * the effective rate is {@code 9.50/8.25 - 1 = 0.1515} — NOT the declared {@code 0.20}. The
     * bundle is {@code 8.00} TTC / {@code 6.95} HT and its type carries the fixed TTC:
     * {@code MixedBundle: PROMO_J8_BUNDLE x1 for 8.00€}. (Cross-references G6.)
     */
    @Test
    void j8_vatDerivedFromGoods() {
        JsonPath body = valuate(basket("J8", "0101",
                plain("3300000000022", "1"), plain("3300000000024", "1")), 200).jsonPath();
        List<Integer> idx = offerIndicesByType(body, "MixedBundle: PROMO_J8_BUNDLE x1 for 8.00€");
        assertEquals(1, idx.size(), "One fixed-price bundle at 8.00 TTC: " + body.getList("offers.type"));
        int b = idx.get(0);
        assertMoney("8.00", offerTtc(body, b), "The fixed bundle price TTC");
        assertMoney("0.1515", offerVat(body, b), "The effective rate is the blend derived from the goods, not the declared 0.20");
        assertMoney("6.95", offerHt(body, b), "HT = 8.00 / (1 + 0.1515)");
    }

    // --------------------------------------------------
    // J9 — discount bundle = global poison
    // --------------------------------------------------

    /**
     * J9 — a {@code discount}-mode {@code MIXED_BUNDLE} is a global poison, graved as the priority
     * NEGATIVE test. The offer is valid for the offer factory (its schema allows either a
     * {@code bundlePrice} or a {@code discount}) so it imports cleanly, but the divergent schema of
     * {@code MixedBundleUpsellAdvantageFactory} REQUIRES {@code bundlePrice} and forbids
     * {@code discount}; that factory runs in {@code createDiscountAppliers} — step 2, before any
     * pricing — so EVERY valuation of its store ({@code 0108}) is refused with a 500 whose trace
     * carries {@code Error building appliers from factory: Error validating offer: …}. A
     * configuration-borne denial of service.
     */
    @Test
    void j9_discountBundlePoison() {
        assertPoison("J9", basket("J9", "0108", plain("3300000000007", "1")),
                "Error building appliers from factory: Error validating offer:");
    }

    // --------------------------------------------------
    // J6 — toxic N+M configurations
    // --------------------------------------------------

    /**
     * J6 — toxic N+M configurations poison the whole store. {@code quantityToPay:0} is accepted by
     * the offer schema (minimum 0) but rejected by the upsell schema (minimum 1), and that upsell
     * guard runs first in {@code createDiscountAppliers}: EVERY valuation of the store returns 500
     * with {@code Error building appliers from factory: Error validating offer: …}. The second
     * config ({@code quantityToPay:0} AND {@code discountedQuantity:0}, the catalog's
     * division-by-zero) is preempted by the SAME {@code quantityToPay} guard, so both quarantined
     * stores ({@code 0106} for J6A, {@code 0107} for J6B) surface the identical validation
     * rejection. Poison offers targeting {@code …007}, probed with a priced {@code …007} basket so
     * the poison — not a missing price — is what fails.
     */
    @Test
    void j6_toxicConfigurations() {
        assertPoison("J6A", basket("J6A", "0106", plain("3300000000007", "1")),
                "Error building appliers from factory: Error validating offer:");
        assertPoison("J6B", basket("J6B", "0107", plain("3300000000007", "1")),
                "Error building appliers from factory: Error validating offer:");
    }

    // --------------------------------------------------
    // J10 — bundle capacity & substitute ordering
    // --------------------------------------------------

    /**
     * J10 — bundle capacity is the per-component minimum, and the main EAN is consumed before its
     * substitute. Five coffees {@code …004} + 2 biscuits {@code …013} + 1 chips {@code …014} under
     * {@code PROMO_COFFEE_PACK}: the coffee component allows {@code floor(5/1)=5} bundles, the
     * biscuit-or-chips component {@code floor((2+1)/1)=3}, so {@code min = 3} bundles are formed
     * (catalog says "2" — recalibrated to 3, the only count that also makes the chips a genuine
     * "last resort"). The two biscuits fill bundles 1-2, the single chips falls back for bundle 3.
     * The bundle is {@code MixedBundle: PROMO_COFFEE_PACK x3 for 13.50€}; two leftover coffees are
     * priced {@code Standard: EAN=3300000000004, Qty=2.0}.
     */
    @Test
    void j10_bundleCapacityAndSubstituteOrder() {
        JsonPath body = valuate(basket("J10", "0101",
                plain("3300000000004", "5"), plain("3300000000013", "2"), plain("3300000000014", "1")), 200).jsonPath();
        List<Integer> bundle = offerIndicesByType(body, "MixedBundle: PROMO_COFFEE_PACK x3 for 13.50€");
        assertEquals(1, bundle.size(), "min(5,3) = 3 bundles at 4.50 each: " + body.getList("offers.type"));
        assertEquals(3.0, eanQtyIn(body, bundle, "3300000000004"), 1e-9, "Three coffees consumed, one per bundle");
        assertEquals(2.0, eanQtyIn(body, bundle, "3300000000013"), 1e-9, "Both biscuits consumed first");
        assertEquals(1.0, eanQtyIn(body, bundle, "3300000000014"), 1e-9, "The chips substitute is consumed last, for bundle 3");
        assertEquals(1, offerIndicesByType(body, "Standard: EAN=3300000000004, Qty=2.0").size(),
                "The two leftover coffees are priced Standard: " + body.getList("offers.type"));
    }

    // --------------------------------------------------
    // J11 — bundle upsell suggestion
    // --------------------------------------------------

    /**
     * J11 — a bundle upsell suggests the cheapest deficient component. Coffee {@code …004} alone
     * builds no bundle, but the bundle upsell twin targets abundance ({@code ceil}) and suggests
     * completing the missing biscuit component. Among the deficient {@code {…013, …014}}, the
     * cheapest by {@code DEFAULT} price is the chips {@code …014} ({@code 1.50} HT vs the biscuits'
     * {@code 2.00}), so the suggestion is {@code {ean:…014, quantity:1.0}} and the type literal
     * {@code Upsell Mixed Bundle: PROMO_COFFEE_PACK (Need 1,00 of 3300000000014)} (locale-
     * formatted, rebuilt in-test). Both deficient EANs are priced here, so the pick is
     * deterministic; the suggested EAN is still asserted only to be one of the valid components,
     * per the catalog's HashSet guard.
     */
    @Test
    void j11_bundleUpsellSuggestion() {
        JsonPath body = valuate(basket("J11", "0101", plain("3300000000004", "1")), 200).jsonPath();
        Map<String, Object> upsell = upsellFor(body, "PROMO_COFFEE_PACK");
        assertNotNull(upsell, "A bundle upsell suggestion must be present: " + body.getList("advantages"));
        @SuppressWarnings("unchecked")
        Map<String, Object> suggestion = (Map<String, Object>) upsell.get("suggestion");
        String suggestedEan = String.valueOf(suggestion.get("ean"));
        assertTrue(List.of("3300000000013", "3300000000014").contains(suggestedEan),
                "The suggested EAN is a valid biscuit component (HashSet guard): " + suggestedEan);
        assertEquals("3300000000014", suggestedEan, "The chips are the cheapest deficient component by DEFAULT price");
        assertEquals(1.0, ((Number) suggestion.get("quantity")).doubleValue(), 1e-9,
                "One biscuit-or-substitute completes the bundle");
        String expectedType = String.format("Upsell Mixed Bundle: %s (Need %.2f of %s)",
                "PROMO_COFFEE_PACK", 1.0, suggestedEan);
        assertEquals(expectedType, upsell.get("type"), "The bundle upsell type literal (locale-formatted)");
    }
}
