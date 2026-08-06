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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group L — livraison, consigne, franco de port — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest} application,
 * HTTP Basic as {@code admin/admin}. The three engines under test — {@code DeliveryOfferFactory}
 * (the {@code Delivery:} offer), {@code DepositBasketOfferFactory} (the {@code Deposit Basket:}
 * offer) and {@code FreeDeliveryThresholdDiscountFactory} (the {@code Free Delivery Threshold
 * Discount:} advantage) — all price against the real catalog, so the mirror catalog is replayed
 * ONCE at class start through the seven import endpoints in the mandated order (Stores &rarr;
 * StoreGroups &rarr; Products &rarr; ProductFamilies &rarr; Categories &rarr; Prices &rarr;
 * Offers).
 * <p>
 * RETRIEVAL — all three types are retrieved store-only via {@code Offer.findByStoreAndType}: a
 * group attachment is ignored. The seed's {@code PROMO_GROUP_NORD} ({@code FREE_DELIVERY_THRESHOLD}
 * on {@code REGION_NORTH}) is therefore DEAD on every store, so on {@code 0101} the only franco is
 * {@code FREE_DELIVERY_THRESHOLD_0101}.
 * <p>
 * THREE EXTRA IMPORT PHASES beyond the mirror seed, all additive (distinct codes / rows, no
 * checksum collision):
 * <ol>
 *   <li>A coordinate-less store {@code 0199} (empty {@code latitude}/{@code longitude}) for the L3
 *       store-guard probe.</li>
 *   <li>Extra prices mirror the pâtes {@code …005} onto {@code 0104} (the L5 poison store) and
 *       {@code 0199} (the L3 no-coordinates store) so their probe baskets price cleanly.</li>
 *   <li>The L5 configuration poisons quarantined on {@code 0104}: a SECOND {@code DELIVERY}
 *       ({@code L5_DELIV_A}/{@code L5_DELIV_B}) and a SECOND {@code DEPOSIT_BASKET}
 *       ({@code L5_DEPO_A}/{@code L5_DEPO_B}). They are quarantined off {@code 0101} on purpose:
 *       a second offer on {@code 0101} would poison L1's nominal delivery and L6's nominal
 *       consignment, which share that store.</li>
 * </ol>
 * <p>
 * TRANSVERSE GUARDS — {@code offers} and {@code advantages} are {@code HashSet}s serialized in
 * arbitrary order: an application is always located by its literal {@code type} prefix (e.g.
 * {@code Delivery: DELIVERY_HOME_0101 (}), never by index. Money is compared scale-insensitively
 * with {@link BigDecimal#compareTo}. 4xx/5xx bodies of {@code /valuation} carry no entity: the L3
 * and L5 rejections are asserted on {@code valuation_traces.error_message} (Panache under
 * {@code QuarkusTransaction}), keyed by a unique {@code customerCode} per probe, never the raw HTTP
 * body.
 * <p>
 * CALIBRATION FINDINGS (catalog vs observed code):
 * <ul>
 *   <li>L1 — the catalog quotes a Seclin distance of {@code ~11 km} and a type of {@code (11,xx
 *       km)}; the Haversine formula (R=6371) between the store {@code 0101} ({@code 50.63}/
 *       {@code 3.06}) and Seclin ({@code 50.540}/{@code 3.030}) actually yields {@code 10.23 km},
 *       still inside the second tier ({@code distance &le; 16}) so priced {@code 9.90€}. The
 *       {@code %.2f} distance is locale-formatted (a comma under {@code fr_FR}), so the type is
 *       asserted by prefix + {@code km) for 9.90€} suffix, never on the decimal separator.</li>
 *   <li>L5 — the catalog quotes store {@code '0101'} in the {@code Multiple … offers} message; the
 *       poison is quarantined on {@code 0104} to keep {@code 0101} single-offered for L1/L6, so the
 *       asserted literal carries {@code '0104'}. The message FORMAT (Configuration Error / Expected
 *       1, found 2) is verbatim.</li>
 *   <li>L7 — the catalog reads the tiers as {@code 10 &rarr; 50 %} / {@code 20 &rarr; 100 %}, but
 *       {@code FREE_DELIVERY_THRESHOLD_0101} stores both as {@code FIXED_AMOUNT} ({@code 50.0} /
 *       {@code 100.0}). Both fixed amounts exceed the {@code 9.90€} delivery cost, so BOTH tiers
 *       cap to the full delivery refund: the discount is {@code 9.90} TTC / {@code 8.25} HT
 *       regardless of which tier wins, and the delivered total collapses back to the merchandise
 *       total.</li>
 * </ul>
 */
@QuarkusTest
class GroupLIT {

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
     * The store header shared by the extra store row.
     */
    private static final String STORE_HEADER =
            "code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude\n";

    /**
     * The extra store {@code 0199} with EMPTY coordinates (trailing empty {@code latitude}/
     * {@code longitude} columns), so the L3 store-guard probe reaches the second guard.
     */
    private static final String EXTRA_STORE = STORE_HEADER
            + "0199|Intermarche No Coords|1 Rue Sans Coordonnees|ZI|59000|Lille|France||\n";

    /**
     * The price header shared by every extra price row.
     */
    private static final String PRICE_HEADER =
            "ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime\n";

    /**
     * Extra prices: the pâtes {@code …005} mirrored onto the L5 poison store {@code 0104} and the
     * L3 no-coordinates store {@code 0199}, each with the {@code DEFAULT} and {@code
     * BASE_FOR_DISCOUNT} usage so the probe lines resolve before the guard fires.
     */
    private static final String EXTRA_PRICES = PRICE_HEADER
            + "3300000000005|0104|1.20|1.44|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000005|0104|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000005|0199|1.20|1.44|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000005|0199|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n";

    /**
     * The offer header shared by every extra offer row.
     */
    private static final String OFFER_HEADER =
            "offer_code|offer_type|specification|store_code|store_group_code\n";

    /**
     * Extra offers, all additive to the mirror catalog and quarantined on {@code 0104}: a second
     * {@code DELIVERY} and a second {@code DEPOSIT_BASKET} feed the two L5 configuration poisons
     * without touching the single-offered {@code 0101} that L1/L6 rely on.
     */
    private static final String EXTRA_OFFERS = OFFER_HEADER
            + "L5_DELIV_A|DELIVERY|{\"tiers\": [{\"maxDistance\": 8.0, \"price\": 5.90}, {\"maxDistance\": 16.0, \"price\": 9.90}], \"vatRate\": 0.20}|0104|\n"
            + "L5_DELIV_B|DELIVERY|{\"tiers\": [{\"maxDistance\": 8.0, \"price\": 5.90}, {\"maxDistance\": 16.0, \"price\": 9.90}], \"vatRate\": 0.20}|0104|\n"
            + "L5_DEPO_A|DEPOSIT_BASKET|{\"basketVolume\": 10.0, \"basketPrice\": 0.50, \"vatRate\": 0.20}|0104|\n"
            + "L5_DEPO_B|DEPOSIT_BASKET|{\"basketVolume\": 10.0, \"basketPrice\": 0.50, \"vatRate\": 0.20}|0104|\n";

    /**
     * The Seclin delivery address (~10.23 km from store {@code 0101}), inside the {@code 16 km}
     * tier.
     */
    private static final String SECLIN =
            "{\"latitude\":50.540,\"longitude\":3.030,\"city\":\"Seclin\",\"postalCode\":\"59113\",\"country\":\"France\"}";

    /**
     * A far delivery address (~299 km from store {@code 0101}), beyond every tier.
     */
    private static final String FAR =
            "{\"latitude\":47.94,\"longitude\":3.06,\"city\":\"Loin\",\"country\":\"France\"}";

    /**
     * A delivery address WITHOUT coordinates, for the L3 missing-coordinates probe.
     */
    private static final String ADDR_NO_COORDS =
            "{\"city\":\"Seclin\",\"postalCode\":\"59113\",\"country\":\"France\"}";

    /**
     * Whether the mirror catalog and the three extra phases have been imported in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the seven CSV imports plus the three extra phases once, before the first scenario, so
     * the whole group shares one catalog.
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
        importCsv("/stores/import", EXTRA_STORE);
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
        try (var in = GroupLIT.class.getClassLoader().getResourceAsStream(path)) {
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
    // JSON basket builders
    // --------------------------------------------------

    /**
     * Builds a {@code HOME_DELIVERY} basket carrying a delivery address.
     *
     * @param customer The customer code stamped for trace lookup.
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
     * Builds a {@code HOME_DELIVERY} basket with NO delivery address at all.
     *
     * @param customer The customer code stamped for trace lookup.
     * @param store    The store code.
     * @param items    The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String homeDeliveryNoAddress(String customer, String store, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"HOME_DELIVERY\",\"items\":[" + String.join(",", items) + "]}";
    }

    /**
     * Builds a basket with an explicit delivery mode and an optional delivery address, for the L4
     * "modes without delivery" probes.
     *
     * @param customer The customer code stamped for trace lookup.
     * @param store    The store code.
     * @param mode     The delivery mode literal, or null to omit the field entirely.
     * @param address  The raw {@code deliveryAddress} JSON object, or null to omit it.
     * @param items    The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String modeBasket(String customer, String store, String mode, String address, String... items) {
        String head = "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\"";
        if (mode != null) {
            head += ",\"deliveryMode\":\"" + mode + "\"";
        }
        if (address != null) {
            head += ",\"deliveryAddress\":" + address;
        }
        return head + ",\"items\":[" + String.join(",", items) + "]}";
    }

    /**
     * Builds an {@code IN_STORE} basket carrying a raw {@code instructions} array fragment.
     *
     * @param customer     The customer code stamped for trace lookup.
     * @param store        The store code.
     * @param instructions The raw JSON array for the {@code instructions} field, or null to omit.
     * @param items        The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String inStoreInstr(String customer, String store, String instructions, String... items) {
        String head = "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"" + store + "\","
                + "\"deliveryMode\":\"IN_STORE\"";
        if (instructions != null) {
            head += ",\"instructions\":" + instructions;
        }
        return head + ",\"items\":[" + String.join(",", items) + "]}";
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

    // --------------------------------------------------
    // JSON reading + money helpers
    // --------------------------------------------------

    /**
     * Returns the index of the sole offer whose {@code type} starts with the given prefix, or -1
     * when none matches; fails when more than one matches.
     *
     * @param body   The parsed evaluation.
     * @param prefix The {@code type} prefix.
     * @return The offer index, or -1 when absent.
     */
    private static int offerIndexByPrefix(JsonPath body, String prefix) {
        List<Object> types = body.getList("offers.type");
        int found = -1;
        for (int i = 0; i < types.size(); i++) {
            Object t = types.get(i);
            if (t != null && t.toString().startsWith(prefix)) {
                assertEquals(-1, found, "More than one offer prefixed <" + prefix + ">: " + types);
                found = i;
            }
        }
        return found;
    }

    /**
     * Returns the index of the sole advantage whose {@code type} starts with the given prefix, or
     * -1 when none matches; fails when more than one matches.
     *
     * @param body   The parsed evaluation.
     * @param prefix The {@code type} prefix.
     * @return The advantage index, or -1 when absent.
     */
    private static int advantageIndexByPrefix(JsonPath body, String prefix) {
        List<Object> types = body.getList("advantages.type");
        int found = -1;
        for (int i = 0; i < types.size(); i++) {
            Object t = types.get(i);
            if (t != null && t.toString().startsWith(prefix)) {
                assertEquals(-1, found, "More than one advantage prefixed <" + prefix + ">: " + types);
                found = i;
            }
        }
        return found;
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
    // L1 — nominal home delivery
    // --------------------------------------------------

    /**
     * L1 — a nominal home delivery prices the matching distance tier. {@code HOME_DELIVERY} to
     * Seclin ({@code 50.540}/{@code 3.030}) on {@code 0101} ({@code DELIVERY_HOME_0101}, tiers
     * {@code 8 km &rarr; 5.90}, {@code 16 km &rarr; 9.90}): the Haversine distance (R=6371) is
     * {@code 10.23 km}, above the first tier and at-or-below the second, so the first matching tier
     * (ascending sort, {@code distance &le; maxDistance}) is {@code 9.90€}. The delivery offer
     * carries the {@code Delivery: DELIVERY_HOME_0101 (…km) for 9.90€} literal, {@code 9.90} TTC /
     * {@code 8.25} HT ({@code 9.90 / 1.20}) and NO valued items (a service covers no priced line).
     * With only a {@code 6.00€} olive-oil line, the merchandise stays below the {@code 10€} franco
     * threshold, so no free-delivery refund fires and the total is the merchandise plus the full
     * delivery: {@code 15.90} TTC / {@code 13.25} HT.
     */
    @Test
    void l1_nominalHomeDelivery() {
        JsonPath body = valuate(homeDelivery("L1", "0101", SECLIN, plain("3300000000006", "1")), 200).jsonPath();
        int di = offerIndexByPrefix(body, "Delivery: DELIVERY_HOME_0101 (");
        assertTrue(di >= 0, "The home delivery offer must apply: " + body.getList("offers.type"));
        assertTrue(body.getString("offers[" + di + "].type").endsWith("km) for 9.9€"),
                "The 16 km tier prices 9.90€ (rendered 9.9€, BigDecimal.toString drops the trailing zero): "
                        + body.getString("offers[" + di + "].type"));
        assertMoney("9.90", offerTtc(body, di), "The second tier price, TTC");
        assertMoney("8.25", offerHt(body, di), "9.90 divided by 1.20 for the HT");
        assertTrue(body.getList("offers[" + di + "].items").isEmpty(),
                "Delivery is a service: it carries no valued items");
        assertEquals(-1, advantageIndexByPrefix(body, "Free Delivery Threshold Discount:"),
                "6.00 merchandise is below the 10 threshold: no franco refund");
        assertMoney("15.90", totalTtc(body), "6.00 merchandise plus 9.90 delivery");
        assertMoney("13.25", totalHt(body), "5.00 merchandise plus 8.25 delivery");
    }

    // --------------------------------------------------
    // L2 — distance beyond every tier
    // --------------------------------------------------

    /**
     * L2 — a distance beyond every tier applies NO delivery and raises NO error. {@code
     * HOME_DELIVERY} to a point {@code 299 km} from {@code 0101} exceeds both the {@code 8 km} and
     * {@code 16 km} tiers, so the applier finds no tier, prints a {@code System.err} note and adds
     * no application. The valuation still returns 200 and the rest of the basket is priced
     * normally: the lone {@code 6.00€} olive-oil line is the whole total, with no {@code Delivery:}
     * offer present.
     */
    @Test
    void l2_distanceBeyondTiers() {
        JsonPath body = valuate(homeDelivery("L2", "0101", FAR, plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(-1, offerIndexByPrefix(body, "Delivery:"),
                "A distance beyond every tier adds no delivery offer: " + body.getList("offers.type"));
        assertMoney("6.00", totalTtc(body), "The merchandise is valued normally, delivery excluded");
        assertMoney("5.00", totalHt(body), "The olive-oil HT, delivery excluded");
    }

    // --------------------------------------------------
    // L3 — missing coordinates guards
    // --------------------------------------------------

    /**
     * L3 — the delivery guards reject a {@code HOME_DELIVERY} without usable coordinates with a 500,
     * and the address guard PRECEDES the offer lookup. Four probes, each keyed by a unique customer
     * code and asserted on {@code valuation_traces.error_message}:
     * <ul>
     *   <li>{@code L3a} on {@code 0101} (which owns a delivery offer) with NO {@code
     *       deliveryAddress} &rarr; {@code Delivery address or coordinates missing for basket
     *       L3a}.</li>
     *   <li>{@code L3b} on {@code 0101} with a {@code deliveryAddress} but no {@code latitude}/
     *       {@code longitude} &rarr; the same message.</li>
     *   <li>{@code L3c} on {@code 0102}, which owns NO delivery offer, with no {@code
     *       deliveryAddress} &rarr; STILL the address message, proving the guard fires before the
     *       offer search.</li>
     *   <li>{@code L3d} on the coordinate-less store {@code 0199} with a VALID address &rarr; the
     *       address guard passes and the store guard fires: {@code Store address or coordinates
     *       missing for store 0199}.</li>
     * </ul>
     */
    @Test
    void l3_missingCoordinatesGuards() {
        assertPoison("L3a", homeDeliveryNoAddress("L3a", "0101", plain("3300000000006", "1")),
                "Delivery address or coordinates missing for basket L3a");
        assertPoison("L3b", homeDelivery("L3b", "0101", ADDR_NO_COORDS, plain("3300000000006", "1")),
                "Delivery address or coordinates missing for basket L3b");
        assertPoison("L3c", homeDeliveryNoAddress("L3c", "0102", plain("3300000000005", "1")),
                "Delivery address or coordinates missing for basket L3c");
        assertPoison("L3d", homeDelivery("L3d", "0199", SECLIN, plain("3300000000005", "1")),
                "Store address or coordinates missing for store 0199");
    }

    // --------------------------------------------------
    // L4 — modes without delivery
    // --------------------------------------------------

    /**
     * L4 — only {@code HOME_DELIVERY} triggers a delivery offer; {@code PICKUP}, {@code IN_STORE}
     * and an absent mode never do, and never fail, EVEN with a delivery address provided. All three
     * probes run on {@code 0101} (which owns {@code DELIVERY_HOME_0101}) with a single {@code 6.00€}
     * olive-oil line: none produce a {@code Delivery:} offer and each totals the merchandise alone,
     * {@code 6.00} TTC. The {@code PICKUP} probe even carries the Seclin address, proving the mode —
     * not the address — gates the offer.
     */
    @Test
    void l4_modesWithoutDelivery() {
        JsonPath pickup = valuate(modeBasket("L4-pickup", "0101", "PICKUP", SECLIN,
                plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(-1, offerIndexByPrefix(pickup, "Delivery:"),
                "PICKUP with an address still adds no delivery: " + pickup.getList("offers.type"));
        assertMoney("6.00", totalTtc(pickup), "PICKUP totals the merchandise alone");
        JsonPath inStore = valuate(modeBasket("L4-instore", "0101", "IN_STORE", null,
                plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(-1, offerIndexByPrefix(inStore, "Delivery:"),
                "IN_STORE adds no delivery: " + inStore.getList("offers.type"));
        assertMoney("6.00", totalTtc(inStore), "IN_STORE totals the merchandise alone");
        JsonPath absent = valuate(modeBasket("L4-absent", "0101", null, null,
                plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(-1, offerIndexByPrefix(absent, "Delivery:"),
                "An absent mode adds no delivery: " + absent.getList("offers.type"));
        assertMoney("6.00", totalTtc(absent), "An absent mode totals the merchandise alone");
    }

    // --------------------------------------------------
    // L5 — multiple offers are a configuration poison
    // --------------------------------------------------

    /**
     * L5 — a second {@code DELIVERY} or {@code DEPOSIT_BASKET} offer on the same store is a
     * configuration poison (500). Both poisons are quarantined on {@code 0104} (two of each) so
     * {@code 0101} stays single-offered for L1/L6.
     * <ul>
     *   <li>{@code HOME_DELIVERY} on {@code 0104} with a valid address &rarr; {@code Configuration
     *       Error: Multiple DELIVERY offers found for store '0104'. Expected 1, found 2.}</li>
     *   <li>{@code IN_STORE} on {@code 0104} with the {@code Deposit basket} instruction &rarr;
     *       {@code Configuration Error: Multiple DEPOSIT_BASKET offers found for store '0104'.
     *       Expected 1, found 2.}</li>
     * </ul>
     * The catalog quotes {@code '0101'}; the message FORMAT is verbatim, only the quarantine store
     * code differs.
     */
    @Test
    void l5_multipleOffersPoison() {
        assertPoison("L5-deliv", homeDelivery("L5-deliv", "0104", SECLIN, plain("3300000000005", "1")),
                "Configuration Error: Multiple DELIVERY offers found for store '0104'. Expected 1, found 2.");
        assertPoison("L5-depo", inStoreInstr("L5-depo", "0104", "[\"Deposit basket\"]",
                        plain("3300000000005", "1")),
                "Configuration Error: Multiple DEPOSIT_BASKET offers found for store '0104'. Expected 1, found 2.");
    }

    // --------------------------------------------------
    // L6 — deposit basket (consigne)
    // --------------------------------------------------

    /**
     * L6 — the deposit basket is a volume-driven service, gated by a trimmed, case-insensitive
     * {@code Deposit basket} instruction, on {@code 0101} ({@code BASKET_CONSIGNMENT_0101},
     * {@code basketVolume 10 L}, {@code basketPrice 0.50€}, {@code vatRate 0.20}):
     * <ul>
     *   <li>NOMINAL — {@code 5} apples {@code …001} (WEIGHT, {@code referenceWeight 1.000},
     *       {@code referenceVolume 2.500 L}) with the messy instruction {@code "  deposit BASKET  "}
     *       (trim + case-insensitive) give a volume of {@code standardQuantity(5) × 2.500 = 5 ×
     *       2.500 = 12.5 L}; {@code ceil(12.5 / 10) = 2} baskets &rarr; {@code Deposit Basket: 2 x
     *       0.50€}, {@code 1.00} TTC / {@code 0.83} HT ({@code 1.00 / 1.20}). The apples also feed
     *       the N+M and voucher offers, but the deposit reads the ORIGINAL basket volume,
     *       independent of any offer consumption.</li>
     *   <li>NO INSTRUCTION — the same apples with no instruction produce NO deposit offer.</li>
     *   <li>NULL VOLUME — one pan {@code …031} ({@code referenceVolume 0.000}) with the instruction
     *       gives a total volume of {@code 0}, so NO deposit offer is produced despite the
     *       instruction.</li>
     * </ul>
     */
    @Test
    void l6_depositBasket() {
        JsonPath nominal = valuate(inStoreInstr("L6-nominal", "0101", "[\"  deposit BASKET  \"]",
                plain("3300000000001", "5")), 200).jsonPath();
        int di = offerIndexByPrefix(nominal, "Deposit Basket:");
        assertTrue(di >= 0, "The trimmed, case-insensitive instruction triggers the deposit: "
                + nominal.getList("offers.type"));
        assertEquals("Deposit Basket: 2 x 0.5€", nominal.getString("offers[" + di + "].type"),
                "12.5 L over 10 L baskets rounds up to 2 baskets (price rendered 0.5€, trailing zero dropped)");
        assertMoney("1.00", offerTtc(nominal, di), "2 baskets at 0.50 TTC");
        assertMoney("0.83", offerHt(nominal, di), "1.00 divided by 1.20 for the HT");
        JsonPath noInstr = valuate(inStoreInstr("L6-noinstr", "0101", null,
                plain("3300000000001", "5")), 200).jsonPath();
        assertEquals(-1, offerIndexByPrefix(noInstr, "Deposit Basket:"),
                "Without the instruction, no deposit offer: " + noInstr.getList("offers.type"));
        JsonPath nullVolume = valuate(inStoreInstr("L6-nullvol", "0101", "[\"Deposit basket\"]",
                plain("3300000000031", "1")), 200).jsonPath();
        assertEquals(-1, offerIndexByPrefix(nullVolume, "Deposit Basket:"),
                "A zero-volume basket produces no deposit despite the instruction: "
                        + nullVolume.getList("offers.type"));
    }

    // --------------------------------------------------
    // L7 — free delivery threshold (franco de port)
    // --------------------------------------------------

    /**
     * L7 — the franco refunds the delivery cost above a merchandise threshold, capped at that cost.
     * On {@code 0101} ({@code FREE_DELIVERY_THRESHOLD_0101}, tiers {@code 10 &rarr; FIXED 50.0},
     * {@code 20 &rarr; FIXED 100.0}, sorted descending): the merchandise total sums only {@code
     * ProductAware} offers (delivery and deposit excluded), and the discount is capped at the
     * delivery cost — both fixed amounts exceed the {@code 9.90€} delivery, so the refund is the
     * full {@code 9.90} TTC / {@code 8.25} HT.
     * <ul>
     *   <li>ABOVE — {@code 4} olive-oil lines ({@code 24.00€} merchandise, above the {@code 20}
     *       tier) with a Seclin {@code HOME_DELIVERY}: exactly one {@code Free Delivery Threshold
     *       Discount: FREE_DELIVERY_THRESHOLD_0101} refunds {@code 9.90} TTC / {@code 8.25} HT, so
     *       the total collapses back to the {@code 24.00} merchandise ({@code 20.00} HT) — delivery
     *       fully neutralised.</li>
     *   <li>BELOW — one {@code 6.00€} line ({@code below 10}) keeps the delivery: the delivery
     *       offer is present but NO franco fires.</li>
     *   <li>NO DELIVERY — {@code 4} olive-oil lines {@code IN_STORE} (no delivery at all): despite
     *       {@code 24.00€} merchandise, NO franco fires (nothing to refund).</li>
     * </ul>
     */
    @Test
    void l7_freeDeliveryThreshold() {
        JsonPath above = valuate(homeDelivery("L7-above", "0101", SECLIN,
                plain("3300000000006", "4")), 200).jsonPath();
        int fi = advantageIndexByPrefix(above, "Free Delivery Threshold Discount: FREE_DELIVERY_THRESHOLD_0101");
        assertTrue(fi >= 0, "24.00 merchandise clears the 20 tier: " + above.getList("advantages.type"));
        assertMoney("9.90", advantageTtc(above, fi), "The refund is capped at the 9.90 delivery cost");
        assertMoney("8.25", advantageHt(above, fi), "9.90 divided by 1.20 for the HT");
        assertTrue(offerIndexByPrefix(above, "Delivery: DELIVERY_HOME_0101 (") >= 0,
                "The delivery offer is present to be refunded: " + above.getList("offers.type"));
        assertMoney("24.00", totalTtc(above), "24.00 merchandise plus 9.90 delivery minus 9.90 refund");
        assertMoney("20.00", totalHt(above), "20.00 merchandise plus 8.25 delivery minus 8.25 refund");
        JsonPath below = valuate(homeDelivery("L7-below", "0101", SECLIN,
                plain("3300000000006", "1")), 200).jsonPath();
        assertEquals(-1, advantageIndexByPrefix(below, "Free Delivery Threshold Discount:"),
                "6.00 merchandise is below the 10 threshold: no refund: " + below.getList("advantages.type"));
        assertTrue(offerIndexByPrefix(below, "Delivery: DELIVERY_HOME_0101 (") >= 0,
                "…but the delivery itself is still charged: " + below.getList("offers.type"));
        JsonPath noDelivery = valuate(modeBasket("L7-nodeliv", "0101", "IN_STORE", null,
                plain("3300000000006", "4")), 200).jsonPath();
        assertEquals(-1, advantageIndexByPrefix(noDelivery, "Free Delivery Threshold Discount:"),
                "With no delivery in the basket, nothing to refund: " + noDelivery.getList("advantages.type"));
        assertEquals(-1, offerIndexByPrefix(noDelivery, "Delivery:"),
                "…and no delivery offer either: " + noDelivery.getList("offers.type"));
    }
}
