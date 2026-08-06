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
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group K — immediate vouchers &amp; vignettes — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest} application,
 * HTTP Basic as {@code admin/admin}. The group needs the referential (the two discount engines
 * price against real catalog prices), so the mirror catalog is replayed ONCE at class start through
 * the seven import endpoints in the mandated order (Stores &rarr; StoreGroups &rarr; Products &rarr;
 * ProductFamilies &rarr; Categories &rarr; Prices &rarr; Offers).
 * <p>
 * ENGINES UNDER TEST — {@code ImmediateVoucherDiscountFactory} (the "Immediate Voucher Discount :"
 * post-processor, retrieved store-only via {@code Offer.findByStoreAndType}) and
 * {@code VignetteDiscountFactory} (the "Vignette Discount:" post-processor, retrieved store+groups
 * via {@code EngineTrait.getOffers}). Both are {@code AdvantageApplierFactory}s: they run AFTER the
 * offer appliers and subtract a POSITIVE {@code discountAmount} from the total (the sign lives in
 * {@code ValuationEngine.calculateAmountEvaluation}'s subtraction, not in the stored value — the
 * historic "wrong sign" defect). Because they register as discount appliers on the covered
 * {@code BasicOfferApplier}, the standard line they touch is priced on {@code BASE_FOR_DISCOUNT}
 * (the {@code refPrice} branch of {@code BasicOfferApplier.apply}), never on {@code DEFAULT}.
 * <p>
 * THREE EXTRA IMPORT PHASES beyond the mirror seed, all additive (distinct codes / rows, no
 * checksum collision):
 * <ol>
 *   <li>Extra prices mirror a handful of EANs onto the clean K4 probe store {@code 0103} (a
 *       {@code REGION_NORTH} member with NO store-attached offer) and onto the K8 poison store
 *       {@code 0105} ({@code REGION_SUD}, outside {@code REGION_NORTH} so the K4 group offers never
 *       bleed into it).</li>
 *   <li>Non-poison IMMEDIATE_VOUCHER / VIGNETTE offers on {@code 0101} for K2/K3/K6, each targeting
 *       a DISJOINT spare EAN no other K scenario touches (a voucher builds an applier only when its
 *       target EAN is in the basket; a vignette applier only discounts a catalog EAN that is both
 *       covered by an offer AND carried in the basket's {@code vignettes} map).</li>
 *   <li>The K4 seam offers (one IMMEDIATE_VOUCHER on {@code REGION_NORTH} and its twin on
 *       {@code 0103}, plus a group DEPOSIT_BASKET / N+M / MIXED_BUNDLE / VIGNETTE) and the K8 poison
 *       VIGNETTE ({@code vignettesRequired: 0}) quarantined on {@code 0105}.</li>
 * </ol>
 * <p>
 * TRANSVERSE GUARDS — {@code offers} and {@code advantages} are {@code HashSet}s serialized in
 * arbitrary order: a discount is always located by its literal {@code type} (e.g.
 * {@code Immediate Voucher Discount : <code>} with its telltale SPACE before the colon), never by
 * index. Money is compared scale-insensitively with {@link BigDecimal#compareTo}. 4xx/5xx bodies of
 * {@code /valuation} carry no entity: the K7/K8 poison rejections are asserted on
 * {@code valuation_traces.error_message} (Panache under {@code QuarkusTransaction}), keyed by a
 * unique {@code customerCode} per probe, never the raw HTTP body.
 * <p>
 * CALIBRATION FINDINGS (catalog vs observed code):
 * <ul>
 *   <li>K1/K2/K6 — the discounted standard line is priced on {@code BASE_FOR_DISCOUNT} (not
 *       {@code DEFAULT}) because the voucher/vignette has registered as a discount applier on the
 *       {@code BasicOfferApplier}; the apple base is therefore {@code 0.99}/{@code 1.19}
 *       (priority-1 {@code BASE_FOR_DISCOUNT}), not the {@code 0.90}/{@code 1.08} DEFAULT winner.</li>
 *   <li>K7 — the unknown-EAN NPE is born in the {@code VignetteDiscountApplier} CONSTRUCTOR
 *       ({@code Collectors.toMap} with a null {@code Product::findByEan} value), inside
 *       {@code buildAppliers}, so it surfaces as {@code Error building appliers from factory: …}
 *       (the NPE message may be null), NOT the apply-time wrapper.</li>
 *   <li>K8 — {@code vignettesRequired: 0} passes the schema ({@code minimum: 0}) and the applier
 *       BUILDS cleanly; the integer division by zero fires at APPLY time, so the trace carries
 *       {@code Error applying discount logic: / by zero}.</li>
 *   <li>K4 — the seam is a pure retrieval-method contrast: store-only types (DELIVERY,
 *       DEPOSIT_BASKET, IMMEDIATE_VOUCHER, FREE_DELIVERY_THRESHOLD) go through
 *       {@code findByStoreAndType} and IGNORE a group attachment; store+group types (N+M,
 *       MIXED_BUNDLE, MEAL_VOUCHER, VIGNETTE_DISCOUNT) go through {@code getOffers} and HONOR it.
 *       Proven here with IMMEDIATE_VOUCHER + DEPOSIT_BASKET (ignored) against N+M + MIXED_BUNDLE +
 *       VIGNETTE (honored); DELIVERY / FREE_DELIVERY_THRESHOLD / MEAL_VOUCHER follow the same two
 *       retrieval methods and are documented residue.</li>
 * </ul>
 */
@QuarkusTest
class GroupKIT {

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
     * The price header shared by every extra price row.
     */
    private static final String PRICE_HEADER =
            "ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime\n";

    /**
     * Extra prices: five EANs mirrored onto the clean K4 store {@code 0103} and the water probe
     * ({@code …007}) onto the K8 poison store {@code 0105}, each with the {@code DEFAULT} and the
     * {@code BASE_FOR_DISCOUNT} usage so a discounted line resolves cleanly.
     */
    private static final String EXTRA_PRICES = PRICE_HEADER
            + "3300000000002|0103|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000002|0103|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000003|0103|0.80|0.96|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000003|0103|0.88|1.06|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000005|0103|1.20|1.44|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000005|0103|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000006|0103|5.00|6.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000006|0103|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000009|0103|2.50|3.00|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000009|0103|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0105|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0105|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n";

    /**
     * The offer header shared by every extra offer row.
     */
    private static final String OFFER_HEADER =
            "offer_code|offer_type|specification|store_code|store_group_code\n";

    /**
     * Extra offers, all additive to the mirror catalog:
     * <ul>
     *   <li>{@code K2_PCT} (PERCENTAGE 150 on {@code …002}) / {@code K2_FIX} (FIXED 10.0 on
     *       {@code …003}) — K2 uncapped formulas driving the basket total negative.</li>
     *   <li>{@code K3_LOWER} (lowercase {@code basicoffer} on {@code …005}) / {@code K3_NPM}
     *       ({@code NPlusMOffer} on {@code …001}) / {@code K3_NONE} (no-match class on {@code …006})
     *       — K3 class-targeting.</li>
     *   <li>{@code VIGNETTE_K6} (FIXED 5.0 &gt; the water price on {@code …007}) — K6 uncapped
     *       vignette.</li>
     *   <li>{@code K4S_IMM} (store {@code 0103}) vs {@code K4G_IMM} ({@code REGION_NORTH}),
     *       {@code K4G_DEPO}, {@code K4G_NPM}, {@code K4G_BUNDLE}, {@code K4G_VIG} — the K4 seam.</li>
     *   <li>{@code VIGNETTE_K8} ({@code vignettesRequired: 0}) — the K8 poison, quarantined on
     *       {@code 0105}.</li>
     * </ul>
     */
    private static final String EXTRA_OFFERS = OFFER_HEADER
            + "K2_PCT|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"BasicOffer\"], \"targetEans\": [\"3300000000002\"], \"discountType\": \"PERCENTAGE\", \"value\": 150.0}|0101|\n"
            + "K2_FIX|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"BasicOffer\"], \"targetEans\": [\"3300000000003\"], \"discountType\": \"FIXED_AMOUNT\", \"value\": 10.0}|0101|\n"
            + "K3_LOWER|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"basicoffer\"], \"targetEans\": [\"3300000000005\"], \"discountType\": \"PERCENTAGE\", \"value\": 10.0}|0101|\n"
            + "K3_NPM|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"NPlusMOffer\"], \"targetEans\": [\"3300000000001\"], \"discountType\": \"PERCENTAGE\", \"value\": 50.0}|0101|\n"
            + "K3_NONE|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"ZzzNoMatchClass\"], \"targetEans\": [\"3300000000006\"], \"discountType\": \"PERCENTAGE\", \"value\": 10.0}|0101|\n"
            + "VIGNETTE_K6|VIGNETTE_DISCOUNT|{\"catalog\": [{\"ean\": \"3300000000007\", \"vignettesRequired\": 1, \"discount\": {\"type\": \"FIXED_AMOUNT\", \"value\": 5.0}}]}|0101|\n"
            + "K4S_IMM|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"BasicOffer\"], \"targetEans\": [\"3300000000002\"], \"discountType\": \"PERCENTAGE\", \"value\": 10.0}|0103|\n"
            + "K4G_IMM|IMMEDIATE_VOUCHER|{\"targetOfferClass\": [\"BasicOffer\"], \"targetEans\": [\"3300000000002\"], \"discountType\": \"PERCENTAGE\", \"value\": 10.0}||REGION_NORTH\n"
            + "K4G_DEPO|DEPOSIT_BASKET|{\"basketVolume\": 10.0, \"basketPrice\": 0.50, \"vatRate\": 0.20}||REGION_NORTH\n"
            + "K4G_NPM|N+M|{\"targetEans\": [\"3300000000003\"], \"quantityToPay\": 2, \"discountedQuantity\": 1, \"selectionStrategy\": \"CHEAPEST\", \"discountType\": \"PERCENTAGE\", \"discountValue\": 100.0}||REGION_NORTH\n"
            + "K4G_BUNDLE|MIXED_BUNDLE|{\"bundlePrice\": 3.00, \"vatRate\": 0.20, \"contents\": [{\"ean\": \"3300000000005\", \"quantity\": 1.0}, {\"ean\": \"3300000000006\", \"quantity\": 1.0}]}||REGION_NORTH\n"
            + "K4G_VIG|VIGNETTE_DISCOUNT|{\"catalog\": [{\"ean\": \"3300000000009\", \"vignettesRequired\": 1, \"discount\": {\"type\": \"PERCENTAGE\", \"value\": 50.0}}]}||REGION_NORTH\n"
            + "VIGNETTE_K8|VIGNETTE_DISCOUNT|{\"catalog\": [{\"ean\": \"3300000000007\", \"vignettesRequired\": 0, \"discount\": {\"type\": \"PERCENTAGE\", \"value\": 10.0}}]}|0105|\n";

    /**
     * Whether the mirror catalog and the three extra phases have been imported in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the seven CSV imports plus the three extra phases once, before the first scenario, so
     * the whole group shares one catalog. The static guard runs the imports exactly once even though
     * seeding happens in a {@code @BeforeEach} (the RestAssured port is wired per test instance).
     */
    @BeforeEach
    void seedCatalogOnce() {
        if (seeded) {
            return;
        }
        for (String[] step : SEED) {
            importCsv(step[0], readResource(step[1]));
        }
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
        try (var in = GroupKIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Builds an {@code IN_STORE} basket carrying a raw {@code vignettes} map fragment.
     *
     * @param customer     The customer code stamped for trace lookup.
     * @param store        The store code.
     * @param vignettesMap The raw JSON object for the {@code vignettes} field (e.g. {@code {"…":5}}).
     * @param items        The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String basketVig(String customer, String store, String vignettesMap, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\",\"vignettes\":" + vignettesMap + ",\"items\":["
                + String.join(",", items) + "]}";
    }

    /**
     * Builds an {@code IN_STORE} basket carrying a raw {@code instructions} array fragment.
     *
     * @param customer     The customer code stamped for trace lookup.
     * @param store        The store code.
     * @param instructions The raw JSON array for the {@code instructions} field.
     * @param items        The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String basketInstr(String customer, String store, String instructions, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\",\"instructions\":" + instructions + ",\"items\":["
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
     * Returns the index of the sole advantage whose {@code type} equals the given literal, or -1
     * when none matches; fails when more than one matches.
     * <p>
     * {@code advantages} is a {@code HashSet} serialized in arbitrary order, so an advantage is
     * always located by its literal type, never by a fixed index.
     *
     * @param body The parsed evaluation.
     * @param type The exact {@code type} literal.
     * @return The advantage index, or -1 when absent.
     */
    private static int advantageIndexByType(JsonPath body, String type) {
        List<Object> types = body.getList("advantages.type");
        int found = -1;
        for (int i = 0; i < types.size(); i++) {
            if (type.equals(types.get(i))) {
                assertEquals(-1, found, "More than one advantage typed <" + type + ">: " + types);
                found = i;
            }
        }
        return found;
    }

    /**
     * Counts the advantages whose {@code type} starts with the given prefix.
     *
     * @param body   The parsed evaluation.
     * @param prefix The {@code type} prefix.
     * @return The number of matching advantages.
     */
    private static int advantageCountByPrefix(JsonPath body, String prefix) {
        List<Object> types = body.getList("advantages.type");
        int count = 0;
        for (Object t : types) {
            if (t != null && t.toString().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the offers whose {@code type} starts with the given prefix.
     *
     * @param body   The parsed evaluation.
     * @param prefix The {@code type} prefix.
     * @return The number of matching offers.
     */
    private static int offerCountByPrefix(JsonPath body, String prefix) {
        List<Object> types = body.getList("offers.type");
        int count = 0;
        for (Object t : types) {
            if (t != null && t.toString().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Reads the including-tax discount of an advantage, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body  The parsed evaluation.
     * @param index The advantage index.
     * @return The advantage's including-tax discount amount.
     */
    private static BigDecimal advantageTtc(JsonPath body, int index) {
        return new BigDecimal(body.getString("advantages[" + index + "].discountAmount.amountIncludingTax"));
    }

    /**
     * Reads the excluding-tax discount of an advantage, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body  The parsed evaluation.
     * @param index The advantage index.
     * @return The advantage's excluding-tax discount amount.
     */
    private static BigDecimal advantageHt(JsonPath body, int index) {
        return new BigDecimal(body.getString("advantages[" + index + "].discountAmount.amountExcludingTax"));
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
     * Reads the whole-basket excluding-tax total, as a scale-insensitive {@link BigDecimal}.
     *
     * @param body The parsed evaluation.
     * @return The total excluding-tax price.
     */
    private static BigDecimal totalHt(JsonPath body) {
        return new BigDecimal(body.getString("totalPrice.amountExcludingTax"));
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
    // K1 — cumulative vouchers, right sign
    // --------------------------------------------------

    /**
     * K1 — two immediate vouchers stack on the same line, both reducing the total (the historic
     * "wrong-sign" regression, now GREEN). One apple {@code …001} on {@code 0101} carries BOTH
     * {@code PROMO_STORE_101} (PERCENTAGE 15) and {@code BRI_APPLES_DISCOUNT} (FIXED 0.10), each a
     * {@code BasicOffer}-targeted IMMEDIATE_VOUCHER. Because both register as discount appliers on
     * the apple's {@code BasicOfferApplier}, the line is priced on {@code BASE_FOR_DISCOUNT}
     * ({@code 0.99}/{@code 1.19}). The percentage voucher deducts {@code 0.99 × 0.15 = 0.15} HT /
     * {@code 0.18} TTC; the fixed voucher deducts {@code 0.10 × standardQuantity(1) = 0.10} HT /
     * {@code 0.12} TTC. Both advantages carry the telltale {@code Immediate Voucher Discount : }
     * literal (SPACE before the colon), and the basket total is the base MINUS both discounts:
     * {@code 0.74} HT / {@code 0.89} TTC &mdash; strictly below the {@code 1.19} offer line.
     */
    @Test
    void k1_cumulativeVouchersRightSign() {
        JsonPath body = valuate(basket("K1", "0101", plain("3300000000001", "1")), 200).jsonPath();
        int pct = advantageIndexByType(body, "Immediate Voucher Discount : PROMO_STORE_101");
        assertTrue(pct >= 0, "The 15% store voucher must apply: " + body.getList("advantages.type"));
        assertMoney("0.15", advantageHt(body, pct), "15% of the 0.99 BASE_FOR_DISCOUNT HT");
        assertMoney("0.18", advantageTtc(body, pct), "15% of the 1.19 BASE_FOR_DISCOUNT TTC");
        int fix = advantageIndexByType(body, "Immediate Voucher Discount : BRI_APPLES_DISCOUNT");
        assertTrue(fix >= 0, "The 0.10 fixed voucher must apply: " + body.getList("advantages.type"));
        assertMoney("0.10", advantageHt(body, fix), "0.10 per standard unit HT");
        assertMoney("0.12", advantageTtc(body, fix), "0.10 grossed up at 20% TTC");
        assertMoney("0.74", totalHt(body), "Base 0.99 minus 0.15 minus 0.10");
        assertMoney("0.89", totalTtc(body), "Base 1.19 minus 0.18 minus 0.12");
        assertTrue(totalTtc(body).compareTo(new BigDecimal("1.19")) < 0,
                "The total is strictly below the offer line: discounts reduce, they do not surcharge");
    }

    // --------------------------------------------------
    // K2 — formulas & absence of a cap
    // --------------------------------------------------

    /**
     * K2 — the two discount formulas run uncapped and can drive the basket total NEGATIVE. The
     * PERCENTAGE voucher {@code K2_PCT} (value 150) on one milk {@code …002}
     * ({@code BASE_FOR_DISCOUNT} {@code 2.53}/{@code 3.04}) deducts {@code 2.53 × 1.50 = 3.80} HT /
     * {@code 4.55} TTC, so the line total is {@code 2.53 − 3.80 = −1.27} HT / {@code 3.04 − 4.55 =
     * −1.51} TTC. The FIXED_AMOUNT voucher {@code K2_FIX} (value 10.0) on one baguette {@code …003}
     * ({@code 0.88}/{@code 1.06}) deducts {@code 10.0 × standardQuantity(1) = 10.00} HT /
     * {@code 12.00} TTC, so its total is {@code −9.12} HT / {@code −10.94} TTC. No plafonnement: the
     * exact negative amounts are graved.
     */
    @Test
    void k2_uncappedFormulasNegativeTotal() {
        JsonPath pct = valuate(basket("K2-pct", "0101", plain("3300000000002", "1")), 200).jsonPath();
        int pi = advantageIndexByType(pct, "Immediate Voucher Discount : K2_PCT");
        assertTrue(pi >= 0, "The 150% voucher must apply: " + pct.getList("advantages.type"));
        assertMoney("3.80", advantageHt(pct, pi), "150% of the 2.53 base HT, uncapped");
        assertMoney("4.55", advantageTtc(pct, pi), "150% of the 3.04 base TTC, uncapped");
        assertMoney("-1.27", totalHt(pct), "2.53 minus 3.80 goes negative");
        assertMoney("-1.51", totalTtc(pct), "3.04 minus 4.55 goes negative");
        JsonPath fix = valuate(basket("K2-fix", "0101", plain("3300000000003", "1")), 200).jsonPath();
        int fi = advantageIndexByType(fix, "Immediate Voucher Discount : K2_FIX");
        assertTrue(fi >= 0, "The 10.0 fixed voucher must apply: " + fix.getList("advantages.type"));
        assertMoney("10.00", advantageHt(fix, fi), "10.0 per standard unit, above the 0.88 price");
        assertMoney("12.00", advantageTtc(fix, fi), "10.0 grossed up at 20% TTC");
        assertMoney("-9.12", totalHt(fix), "0.88 minus 10.00 goes deeply negative");
        assertMoney("-10.94", totalTtc(fix), "1.06 minus 12.00 goes deeply negative");
    }

    // --------------------------------------------------
    // K3 — targeting by offer class
    // --------------------------------------------------

    /**
     * K3 — {@code targetOfferClass} matches by a case-insensitive {@code contains}. The lowercase
     * {@code basicoffer} voucher {@code K3_LOWER} (PERCENTAGE 10) still matches the
     * {@code BasicApplication} of pâtes {@code …005} ({@code BASE_FOR_DISCOUNT} {@code 1.32}/
     * {@code 1.58}, doubled for the {@code 0.5 kg} reference &rarr; {@code 2.64}/{@code 3.16}),
     * deducting {@code 0.26} HT / {@code 0.32} TTC. The {@code NPlusMOffer} voucher {@code K3_NPM}
     * (PERCENTAGE 50, on {@code …001}) instead lands on the N+M block of three apples: the block
     * pays two apples at {@code BASE_FOR_DISCOUNT} ({@code 1.98}/{@code 2.38}), so the voucher
     * deducts {@code 0.99} HT / {@code 1.19} TTC &mdash; and the {@code BasicOffer}-targeted seed
     * vouchers ({@code PROMO_STORE_101}) fire NOT AT ALL, because the apples are consumed by the N+M
     * application and never priced as a standard line. Finally the no-match voucher {@code K3_NONE}
     * ({@code ZzzNoMatchClass}) yields no advantage and no error on huile {@code …006}.
     */
    @Test
    void k3_targetingByOfferClass() {
        JsonPath lower = valuate(basket("K3-lower", "0101", plain("3300000000005", "1")), 200).jsonPath();
        int li = advantageIndexByType(lower, "Immediate Voucher Discount : K3_LOWER");
        assertTrue(li >= 0, "Lowercase basicoffer still matches BasicApplication: " + lower.getList("advantages.type"));
        assertMoney("0.26", advantageHt(lower, li), "10% of the 2.64 HT (1.32 per 0.5kg, doubled)");
        assertMoney("0.32", advantageTtc(lower, li), "10% of the 3.16 TTC");
        JsonPath npm = valuate(basket("K3-npm", "0101", plain("3300000000001", "3")), 200).jsonPath();
        int ni = advantageIndexByType(npm, "Immediate Voucher Discount : K3_NPM");
        assertTrue(ni >= 0, "The NPlusMOffer voucher lands on the N+M block: " + npm.getList("advantages.type"));
        assertMoney("0.99", advantageHt(npm, ni), "50% of the 1.98 paid-block HT");
        assertMoney("1.19", advantageTtc(npm, ni), "50% of the 2.38 paid-block TTC");
        assertEquals(-1, advantageIndexByType(npm, "Immediate Voucher Discount : PROMO_STORE_101"),
                "The BasicOffer voucher does not fire: the apples went to the N+M block, not a standard line");
        JsonPath none = valuate(basket("K3-none", "0101", plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(-1, advantageIndexByType(none, "Immediate Voucher Discount : K3_NONE"),
                "A class matching nothing yields no advantage: " + none.getList("advantages.type"));
        assertEquals(0, advantageCountByPrefix(none, "Immediate Voucher Discount : K3_NONE"),
                "…and no error either — the valuation still returns 200");
    }

    // --------------------------------------------------
    // K4 — store/group scope asymmetry (LA couture)
    // --------------------------------------------------

    /**
     * K4 — the store/group scope seam. Store-only discount types are retrieved via
     * {@code Offer.findByStoreAndType} and IGNORE a group attachment; store+group types are
     * retrieved via {@code EngineTrait.getOffers} and HONOR it. Probed on the clean
     * {@code REGION_NORTH} member {@code 0103} (no store-attached offer of its own):
     * <ul>
     *   <li>IMMEDIATE_VOUCHER (store-only): the store twin {@code K4S_IMM} on {@code 0103} FIRES,
     *       the identical group offer {@code K4G_IMM} on {@code REGION_NORTH} is SILENTLY IGNORED.</li>
     *   <li>DEPOSIT_BASKET (store-only): the group offer {@code K4G_DEPO} is IGNORED even with the
     *       {@code "Deposit basket"} instruction present — no {@code Deposit Basket:} offer is
     *       produced (had it been store-attached, one would appear).</li>
     *   <li>N+M / MIXED_BUNDLE / VIGNETTE_DISCOUNT (store+group): the group offers {@code K4G_NPM},
     *       {@code K4G_BUNDLE}, {@code K4G_VIG} all APPLY on {@code 0103}.</li>
     * </ul>
     * DELIVERY and FREE_DELIVERY_THRESHOLD (store-only) and MEAL_VOUCHER (store+group) share the
     * exact same two retrieval methods and are documented residue, represented here by the probes
     * above.
     */
    @Test
    void k4_storeGroupScopeAsymmetry() {
        JsonPath imm = valuate(basketInstr("K4-imm", "0103", "[\"Deposit basket\"]",
                plain("3300000000002", "1")), 200).jsonPath();
        assertTrue(advantageIndexByType(imm, "Immediate Voucher Discount : K4S_IMM") >= 0,
                "The store-attached voucher fires on 0103: " + imm.getList("advantages.type"));
        assertEquals(-1, advantageIndexByType(imm, "Immediate Voucher Discount : K4G_IMM"),
                "The group-attached voucher is silently ignored (store-only retrieval)");
        assertEquals(0, offerCountByPrefix(imm, "Deposit Basket:"),
                "The group-attached DEPOSIT_BASKET is ignored even with the instruction: " + imm.getList("offers.type"));
        JsonPath npm = valuate(basket("K4-npm", "0103", plain("3300000000003", "3")), 200).jsonPath();
        assertEquals(1, offerCountByPrefix(npm, "Mixed Bundle Promo: K4G_NPM"),
                "The group-attached N+M applies on 0103 (store+group retrieval): " + npm.getList("offers.type"));
        JsonPath bundle = valuate(basket("K4-bundle", "0103",
                plain("3300000000005", "1"), plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(1, offerCountByPrefix(bundle, "MixedBundle: K4G_BUNDLE"),
                "The group-attached MIXED_BUNDLE applies on 0103: " + bundle.getList("offers.type"));
        JsonPath vig = valuate(basketVig("K4-vig", "0103", "{\"3300000000009\":1}",
                plain("3300000000009", "1")), 200).jsonPath();
        assertEquals(1, advantageCountByPrefix(vig, "Vignette Discount: K4G_VIG"),
                "The group-attached VIGNETTE applies on 0103: " + vig.getList("advantages.type"));
    }

    // --------------------------------------------------
    // K5 — vignettes, nominal
    // --------------------------------------------------

    /**
     * K5 — vignette applications are {@code min(floor(quantity), vignettes / required)}. The pan
     * {@code …031} under {@code VIGNETTE_CUISSON} (5 vignettes &rarr; −50 %,
     * {@code BASE_FOR_DISCOUNT} {@code 13.20}/{@code 15.84}): one pan with 5 vignettes applies ONCE
     * ({@code Vignette Discount: VIGNETTE_CUISSON (5 vignettes used, applied 1 times)}), deducting
     * {@code 6.60} HT / {@code 7.92} TTC. Two pans with 10 vignettes apply TWICE
     * ({@code 10 vignettes used, applied 2 times}, {@code 13.20}/{@code 15.84}); two pans with only
     * 7 vignettes apply ONCE ({@code 5 vignettes used, applied 1 times}) &mdash; the vignette stock,
     * not the quantity, caps the third.
     */
    @Test
    void k5_vignettesNominal() {
        JsonPath one = valuate(basketVig("K5-one", "0101", "{\"3300000000031\":5}",
                plain("3300000000031", "1")), 200).jsonPath();
        int oi = advantageIndexByType(one, "Vignette Discount: VIGNETTE_CUISSON (5 vignettes used, applied 1 times)");
        assertTrue(oi >= 0, "One pan + 5 vignettes applies once: " + one.getList("advantages.type"));
        assertMoney("6.60", advantageHt(one, oi), "50% of the 13.20 BASE_FOR_DISCOUNT HT");
        assertMoney("7.92", advantageTtc(one, oi), "50% of the 15.84 BASE_FOR_DISCOUNT TTC");
        JsonPath twice = valuate(basketVig("K5-twice", "0101", "{\"3300000000031\":10}",
                plain("3300000000031", "2")), 200).jsonPath();
        int ti = advantageIndexByType(twice, "Vignette Discount: VIGNETTE_CUISSON (10 vignettes used, applied 2 times)");
        assertTrue(ti >= 0, "Two pans + 10 vignettes applies twice: " + twice.getList("advantages.type"));
        assertMoney("13.20", advantageHt(twice, ti), "Twice the 6.60 unit discount HT");
        assertMoney("15.84", advantageTtc(twice, ti), "Twice the 7.92 unit discount TTC");
        JsonPath capped = valuate(basketVig("K5-capped", "0101", "{\"3300000000031\":7}",
                plain("3300000000031", "2")), 200).jsonPath();
        assertTrue(advantageIndexByType(capped,
                        "Vignette Discount: VIGNETTE_CUISSON (5 vignettes used, applied 1 times)") >= 0,
                "Two pans + 7 vignettes applies only once (7/5 = 1): " + capped.getList("advantages.type"));
    }

    // --------------------------------------------------
    // K6 — fixed vignette, uncapped
    // --------------------------------------------------

    /**
     * K6 — a FIXED_AMOUNT vignette above the product price is not plafonné: the contribution goes
     * negative (crosses K2). The water {@code …007} under {@code VIGNETTE_K6} (FIXED 5.0, 1 vignette
     * required, {@code BASE_FOR_DISCOUNT} {@code 0.55}/{@code 0.66}) deducts the full {@code 5.00}
     * TTC / {@code 5.00 / 1.20 = 4.17} HT for one application
     * ({@code Vignette Discount: VIGNETTE_K6 (1 vignettes used, applied 1 times)}), so the basket
     * total is {@code 0.55 − 4.17 = −3.62} HT / {@code 0.66 − 5.00 = −4.34} TTC.
     */
    @Test
    void k6_fixedVignetteUncapped() {
        JsonPath body = valuate(basketVig("K6", "0101", "{\"3300000000007\":1}",
                plain("3300000000007", "1")), 200).jsonPath();
        int i = advantageIndexByType(body, "Vignette Discount: VIGNETTE_K6 (1 vignettes used, applied 1 times)");
        assertTrue(i >= 0, "The fixed vignette applies once: " + body.getList("advantages.type"));
        assertMoney("4.17", advantageHt(body, i), "5.00 TTC divided by 1.20 for the HT");
        assertMoney("5.00", advantageTtc(body, i), "The full 5.00 fixed amount, above the 0.66 price");
        assertMoney("-3.62", totalHt(body), "0.55 minus 4.17 goes negative");
        assertMoney("-4.34", totalTtc(body), "0.66 minus 5.00 goes negative");
    }

    // --------------------------------------------------
    // K7 — unknown EAN in the vignettes map
    // --------------------------------------------------

    /**
     * K7 — an unknown EAN in the {@code vignettes} map is a global poison (NPE &rarr; 500). The
     * {@code VignetteDiscountApplier} constructor builds {@code productInCatalog} from the
     * {@code vignettes} KEYS via {@code Collectors.toMap(k, Product::findByEan)}; for the unknown
     * {@code 9999999999999} the finder returns {@code null}, which {@code toMap} refuses. The NPE is
     * born inside {@code buildAppliers}, so the trace carries the {@code Error building appliers from
     * factory:} wrapper. Probed on {@code 0101} (which owns {@code VIGNETTE_CUISSON}), keyed by a
     * unique customer code so the FAILED trace is unambiguous.
     */
    @Test
    void k7_unknownEanInVignettes() {
        assertPoison("K7", basketVig("K7", "0101", "{\"9999999999999\":3}", plain("3300000000007", "1")),
                "Error building appliers from factory:");
    }

    // --------------------------------------------------
    // K8 — vignettesRequired: 0
    // --------------------------------------------------

    /**
     * K8 — {@code vignettesRequired: 0} is a toxic config (crosses J6): the schema accepts it
     * ({@code minimum: 0}) and the applier BUILDS cleanly, but as soon as a covered catalog product
     * is processed the integer division {@code availableVignettes / vignettesRequired} in
     * {@code calculateMaxApplications} divides by zero. That happens at APPLY time, so the trace
     * carries {@code Error applying discount logic: / by zero}. Quarantined on {@code 0105}
     * ({@code REGION_SUD}, out of {@code REGION_NORTH}) with the water probe {@code …007} priced
     * there so no benign "No active price" preempts the poison.
     */
    @Test
    void k8_vignettesRequiredZero() {
        assertPoison("K8", basketVig("K8", "0105", "{\"3300000000007\":1}", plain("3300000000007", "1")),
                "/ by zero");
    }

    // --------------------------------------------------
    // K9 — inert vignettes
    // --------------------------------------------------

    /**
     * K9 — vignettes that touch nothing produce no advantage and no failure. With NO {@code
     * vignettes} map at all, the pan {@code …031} on {@code 0101} yields no vignette applier and no
     * {@code Vignette Discount:} advantage. With a {@code vignettes} map present but its EAN absent
     * from the basket ({@code {"…031":5}} against a water-only {@code …007} basket), the catalog
     * rule for {@code …031} finds no covered product and no water rule is triggered, so again no
     * vignette advantage and a clean 200.
     */
    @Test
    void k9_inertVignettes() {
        JsonPath noMap = valuate(basket("K9-nomap", "0101", plain("3300000000031", "1")), 200).jsonPath();
        assertEquals(0, advantageCountByPrefix(noMap, "Vignette Discount:"),
                "No vignettes map means no vignette applier at all: " + noMap.getList("advantages.type"));
        JsonPath absent = valuate(basketVig("K9-absent", "0101", "{\"3300000000031\":5}",
                plain("3300000000007", "1")), 200).jsonPath();
        assertEquals(0, advantageCountByPrefix(absent, "Vignette Discount:"),
                "Vignettes for a product absent from the basket yield no advantage: " + absent.getList("advantages.type"));
        assertNull(absent.getString("errorMessage"), "…and no failure: the valuation returns 200 cleanly");
    }
}
