package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group P — transverse audit seams (2nd audit pass) — of e2e-scenarios.md, in pure RestAssured
 * (plus a handful of GraphQL delete mutations and two direct {@link DateTimeProvider} calls, the app
 * running in-JVM under {@code @QuarkusTest}). HTTP Basic as {@code admin/admin} throughout.
 * <p>
 * This group audits behaviours that cut across the whole engine rather than a single applier, so it
 * needs the full referential: the mirror catalog is replayed ONCE at class start through the seven
 * import endpoints in the mandated order (Stores &rarr; StoreGroups &rarr; Products &rarr;
 * ProductFamilies &rarr; Categories &rarr; Prices &rarr; Offers), then two additive phases layer the
 * quarantined poison offers (P1/P3) and the {@code eans}-index probes (P4) on top.
 * <p>
 * SCENARIOS &amp; TIERS — all unmarked, hence {@code @QuarkusTest} + RestAssured:
 * <ul>
 *   <li>P1 — observable idempotence of {@code /valuation} (10&times; the same basket), plus the
 *       calibration that the "zero dry amount &rarr; division by zero in the efficiency score"
 *       ({@code Infinity}/{@code NaN}, {@code OfferApplier.computeEfficiencyScore}) is UNREACHABLE
 *       through any schema-valid offer.</li>
 *   <li>P2 — the maximal reference basket: every offer family fires at once and every G6 structural
 *       invariant holds.</li>
 *   <li>P3 — the two offer-specification failure paths (unparsable JSON refused at PERSIST via CSV;
 *       valid-JSON-but-schema-nonconforming imports fine yet is refused at VALUATION).</li>
 *   <li>P4 — the {@code Offer.eans} index: recursive extraction of every {@code ean}/{@code eans}
 *       key ({@code targetEans}, {@code contents[].ean}, {@code substituteEans}) drives the UI EAN
 *       filter, and editing the spec re-derives the index ({@code @PreUpdate}).</li>
 *   <li>P5 — 80&nbsp;000-product volumetry &amp; the quadratic dry-score load test: {@code @Disabled}
 *       justified residue (no automated harness, see the method).</li>
 *   <li>P6 — the upsell pool {@code availableToUpcell}: a fully-consumed basket leaves it empty and
 *       emits no suggestion.</li>
 *   <li>P7 — the scale-sensitive price checksum: re-importing {@code 1.20} as {@code 1.2000} counts
 *       as one update though the value is unchanged.</li>
 *   <li>P8 — inter-scenario hygiene: {@code DateTimeProvider.clear()} restores live-time pricing, and
 *       a disposable graph is torn down in reverse dependency order via the GraphQL deletes (never
 *       {@code deleteProductFamily}, whose F7 cascade is destructive).</li>
 * </ul>
 * <p>
 * TRANSVERSE GUARDS — {@code offers}/{@code advantages} are {@code HashSet}s serialized in arbitrary
 * order: an application is always located by its literal {@code type}, never by index. 4xx/5xx bodies
 * of {@code /valuation} carry no entity, so the P1/P3 poison rejections are asserted on
 * {@code valuation_traces.error_message} (Panache under {@code QuarkusTransaction}), keyed by a unique
 * {@code customerCode}. Money is compared scale-insensitively with {@link BigDecimal#compareTo}.
 * <p>
 * CALIBRATION FINDINGS (catalog vs observed code):
 * <ul>
 *   <li>P1 — the catalog expects an offer "whose dry amount is 0" to divide by zero
 *       ({@code Infinity}/{@code NaN}) in {@code OfferApplier.computeEfficiencyScore} (line 87-89).
 *       Observed: no schema-valid offer can reach it. {@code bundlePrice} carries
 *       {@code exclusiveMinimum:0} and N+M {@code quantityToPay} carries {@code minimum:1} in the
 *       upsell schema, so both zeroing configs are rejected at valuation ({@code Error validating
 *       offer:}). The division is dead code masked by the schemas; the test GRAVES that.</li>
 *   <li>P2 — "each offer family appears once" is calibrated to "appears": the two immediate-voucher
 *       offers targeting the apple ({@code PROMO_STORE_101} and {@code BRI_APPLES_DISCOUNT}) both fire
 *       on the standard apple line, and both group franco offers ({@code FREE_DELIVERY_THRESHOLD_0101}
 *       and the {@code REGION_NORTH} {@code PROMO_GROUP_NORD}) apply, so a couple of families legitimately
 *       appear more than once. The structural G6 invariants are the real non-regression assertion.</li>
 *   <li>P3 — the wire message at valuation is double-wrapped
 *       ({@code Error building appliers from factory: Error validating offer: …}); the
 *       {@code Error validating offer:} fragment is asserted as a substring.</li>
 *   <li>P5 — {@code MassProductImporterClient} exists but is a standalone {@code main()} bound to the
 *       fixed port {@code 8090}, not a {@code @QuarkusTest} fixture; disabled.</li>
 * </ul>
 */
@QuarkusTest
class GroupPIT {

    /**
     * Seeds used by every scenario needing the referential, in the mandated import order.
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
     * Extra prices: the water probe ({@code …007}) is priced on the three quarantine stores so a
     * P1/P3 poison valuation reaches the applier-building step instead of failing on a missing price.
     */
    private static final String EXTRA_PRICES = PRICE_HEADER
            + "3300000000007|0103|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0103|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0104|0.50|0.60|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n"
            + "3300000000007|0104|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|2026-01-12T00:00:00|\n"
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
     *   <li>{@code P1_ZERO_BUNDLE} ({@code 0105}) — a {@code MIXED_BUNDLE} with {@code bundlePrice:0.0};
     *       valid JSON so it imports, but {@code exclusiveMinimum:0} rejects it at valuation. This is the
     *       only shape that would zero an offer's dry amount, so its rejection proves the division-by-zero
     *       is unreachable.</li>
     *   <li>{@code P1_NM_ZERO} ({@code 0104}) — an N+M with {@code quantityToPay:0}; the upsell schema's
     *       {@code minimum:1} rejects it at valuation (the J6 mechanism), preempting the same division.</li>
     *   <li>{@code P3_NONCONFORMING} ({@code 0103}) — a {@code MIXED_BUNDLE} spec of {@code {"foo":1}}:
     *       valid JSON (imports), but missing {@code vatRate}/{@code contents} and {@code additionalProperties:false}
     *       reject it at valuation.</li>
     *   <li>{@code P4_BUNDLE} / {@code P4_NM} ({@code 0102}) — the {@code eans}-index probes carrying
     *       {@code contents[].ean}, {@code substituteEans} and {@code targetEans} over EANs no seed offer
     *       references. Never valuated; only their extracted index is observed through the UI filter.</li>
     * </ul>
     */
    private static final String EXTRA_OFFERS = OFFER_HEADER
            + "P1_ZERO_BUNDLE|MIXED_BUNDLE|{\"vatRate\":0.20,\"bundlePrice\":0.0,\"contents\":[{\"ean\":\"3300000000007\",\"quantity\":1.0}]}|0105|\n"
            + "P1_NM_ZERO|N+M|{\"targetEans\":[\"3300000000007\"],\"quantityToPay\":0,\"discountedQuantity\":1,\"selectionStrategy\":\"CHEAPEST\",\"discountType\":\"PERCENTAGE\",\"discountValue\":100.0}|0104|\n"
            + "P3_NONCONFORMING|MIXED_BUNDLE|{\"foo\":1}|0103|\n"
            + "P4_BUNDLE|MIXED_BUNDLE|{\"vatRate\":0.20,\"bundlePrice\":9.99,\"contents\":[{\"ean\":\"3300000000015\",\"quantity\":1.0,\"substituteEans\":[\"3300000000016\"]},{\"ean\":\"3300000000023\",\"quantity\":1.0}]}|0102|\n"
            + "P4_NM|N+M|{\"targetEans\":[\"3300000000017\"],\"quantityToPay\":1,\"discountedQuantity\":1,\"selectionStrategy\":\"CHEAPEST\",\"discountType\":\"PERCENTAGE\",\"discountValue\":100.0}|0102|\n";

    /**
     * The Seclin delivery address (~10.23 km from store {@code 0101}), inside the {@code 16 km}
     * delivery tier — reused for the P2 maximal home-delivery basket.
     */
    private static final String SECLIN =
            "{\"latitude\":50.540,\"longitude\":3.030,\"city\":\"Seclin\",\"postalCode\":\"59113\",\"country\":\"France\"}";

    /**
     * Whether the mirror catalog and the two extra phases have been imported in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the seven CSV imports plus the two extra phases once, before the first scenario, so the
     * whole group shares one catalog. The static guard runs the imports exactly once even though seeding
     * happens in a {@code @BeforeEach} (the RestAssured port is wired per test instance).
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
        try (var in = GroupPIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Posts a CSV body to an import endpoint as {@code admin/admin} and returns the raw JSON summary,
     * without asserting the status (some line errors surface at 200, some at 500).
     *
     * @param endpoint The import endpoint path.
     * @param csv      The CSV body.
     * @return The response, for body/counter inspection.
     */
    private Response importCsvRaw(String endpoint, String csv) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.TEXT).body(csv)
                .when().post(endpoint)
                .then().extract().response();
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

    /**
     * Posts a GraphQL document as {@code admin/admin} and returns the parsed response.
     *
     * @param query The GraphQL document.
     * @return The parsed GraphQL response.
     */
    private JsonPath graphql(String query) {
        String body = "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/graphql")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /**
     * Runs a delete mutation and returns its boolean result.
     *
     * @param mutation The mutation field call, e.g. {@code deletePrice(id: 7)}.
     * @param field    The mutation field name whose boolean value to read.
     * @return The boolean returned by the mutation.
     */
    private boolean deleteMutation(String mutation, String field) {
        JsonPath body = graphql("mutation { " + mutation + " }");
        return Boolean.TRUE.equals(body.getBoolean("data." + field));
    }

    // --------------------------------------------------
    // Trace helpers
    // --------------------------------------------------

    /**
     * A read-only, transaction-safe projection of a valuation trace row.
     */
    private static final class TraceView {

        /**
         * Whether a trace was found for the queried customer code.
         */
        boolean found;

        /**
         * The recorded error message, or null on success.
         */
        String errorMessage;
    }

    /**
     * Loads the latest trace for a customer code and projects its error message inside the
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
        assertNotNull(trace.errorMessage, "Error message for " + customer);
        assertTrue(trace.errorMessage.contains(expectedFragment),
                "Message for " + customer + " must contain <" + expectedFragment + ">, was: "
                        + trace.errorMessage);
    }

    // --------------------------------------------------
    // Basket + JSON helpers
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
     * Builds the P2 maximal home-delivery basket to Seclin carrying a vignettes map, a deposit
     * instruction and the given item fragments.
     *
     * @param customer The customer code stamped for trace lookup.
     * @param items    The raw item JSON fragments.
     * @return The basket JSON string.
     */
    private static String maximalBasket(String customer, String... items) {
        return "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"0101\","
                + "\"deliveryMode\":\"HOME_DELIVERY\",\"deliveryAddress\":" + SECLIN + ","
                + "\"vignettes\":{\"3300000000031\":5},\"instructions\":[\"deposit basket\"],"
                + "\"items\":[" + String.join(",", items) + "]}";
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
     * Builds an item fragment carrying a manual discount amount (a gesture consumed OUTSIDE the
     * standard pool).
     *
     * @param ean    The product EAN.
     * @param qty    The quantity, as a JSON literal.
     * @param amount The manual discount amount, as a JSON literal.
     * @return The item JSON fragment.
     */
    private static String gesture(String ean, String qty, String amount) {
        return "{\"produceEan\":\"" + ean + "\",\"quantity\":" + qty + ",\"manualDiscountAmount\":" + amount + "}";
    }

    /**
     * Builds a stable, order-independent signature of an evaluation: the sorted list of every offer's
     * {@code type=amountIncludingTax} and every advantage's {@code type=discountIncludingTax}. Because
     * {@code offers}/{@code advantages} are {@code HashSet}s, the signature is sorted so two identical
     * evaluations compare equal regardless of serialization order.
     *
     * @param body The parsed evaluation.
     * @return The sorted signature list.
     */
    private static List<String> signatureOf(JsonPath body) {
        List<String> parts = new ArrayList<>();
        int offers = body.getList("offers").size();
        for (int i = 0; i < offers; i++) {
            parts.add("O:" + body.getString("offers[" + i + "].type") + "="
                    + body.getString("offers[" + i + "].amount.amountIncludingTax"));
        }
        int advantages = body.getList("advantages").size();
        for (int i = 0; i < advantages; i++) {
            parts.add("A:" + body.getString("advantages[" + i + "].type") + "="
                    + body.getString("advantages[" + i + "].discountAmount.amountIncludingTax"));
        }
        parts.add("T=" + body.getString("totalPrice.amountIncludingTax"));
        Collections.sort(parts);
        return parts;
    }

    /**
     * Whether any {@code offers[].type} starts with the given prefix.
     *
     * @param body   The parsed evaluation.
     * @param prefix The type prefix.
     * @return {@code true} when at least one offer matches.
     */
    private static boolean offerPresent(JsonPath body, String prefix) {
        return typePresent(body.getList("offers.type"), prefix);
    }

    /**
     * Whether any {@code advantages[].type} starts with the given prefix.
     *
     * @param body   The parsed evaluation.
     * @param prefix The type prefix.
     * @return {@code true} when at least one advantage matches.
     */
    private static boolean advantagePresent(JsonPath body, String prefix) {
        return typePresent(body.getList("advantages.type"), prefix);
    }

    /**
     * Whether any type literal in a list starts with the given prefix.
     *
     * @param types  The type literals.
     * @param prefix The type prefix.
     * @return {@code true} when at least one type matches.
     */
    private static boolean typePresent(List<Object> types, String prefix) {
        for (Object t : types) {
            if (t != null && t.toString().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------
    // Panache id lookups (P8)
    // --------------------------------------------------

    /**
     * Resolves the id of a store by code, or null when absent, inside a fresh transaction.
     *
     * @param code The store code.
     * @return The store id, or null.
     */
    private Long storeId(String code) {
        Long[] holder = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Store s = Store.find("code", code).firstResult();
            holder[0] = s == null ? null : s.id;
        });
        return holder[0];
    }

    /**
     * Resolves the id of a product by EAN, or null when absent, inside a fresh transaction.
     *
     * @param ean The product EAN.
     * @return The product id, or null.
     */
    private Long productId(String ean) {
        Long[] holder = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Product p = Product.findByEan(ean);
            holder[0] = p == null ? null : p.id;
        });
        return holder[0];
    }

    /**
     * Resolves the id of the price of a product at a store, or null when absent, inside a fresh
     * transaction.
     *
     * @param ean       The product EAN.
     * @param storeCode The store code.
     * @return The price id, or null.
     */
    private Long priceId(String ean, String storeCode) {
        Long[] holder = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Price p = Price.find("product.ean = ?1 and store.code = ?2", ean, storeCode).firstResult();
            holder[0] = p == null ? null : p.id;
        });
        return holder[0];
    }

    // ==================================================
    // P1 — idempotence & the unreachable division by zero
    // ==================================================

    /**
     * P1 — observable idempotence, and the division-by-zero score frozen as UNREACHABLE.
     * <p>
     * Idempotence: the engine mutates only per-request working copies (a deep-copied
     * {@code BasketEvaluation}, a throwaway one for the dry score), never the catalog, so posting the
     * SAME basket ten times yields byte-for-byte the same evaluation. The five-apple basket exercises
     * an N+M bundle, standard leftovers, two immediate vouchers and an upsell in one shot; its stable
     * signature (sorted offer/advantage {@code type=amountIncludingTax} plus the total) is identical
     * across all ten calls.
     * <p>
     * Division by zero: {@code OfferApplier.computeEfficiencyScore} divides by the offer's own dry
     * amount (line 87-89), which the catalog flags as {@code Infinity}/{@code NaN} when that amount is
     * 0. The only offers that could zero the amount are rejected by their schema BEFORE they ever
     * score: a {@code bundlePrice:0.0} bundle fails {@code exclusiveMinimum:0}, and an N+M
     * {@code quantityToPay:0} fails the upsell schema's {@code minimum:1}. Both are valid JSON so they
     * import, but every valuation of their quarantine store is refused 500 with {@code Error
     * validating offer:} — proving the division is dead code masked by the schemas.
     */
    @Test
    void p1_idempotenceAndUnreachableDivisionByZero() {
        String probe = basket("P1-idem", "0101", plain("3300000000001", "5"), plain("3300000000002", "1"));
        List<String> reference = signatureOf(valuate(probe, 200).jsonPath());
        assertFalse(reference.isEmpty(), "The reference evaluation must carry offers");
        for (int i = 0; i < 10; i++) {
            List<String> repeat = signatureOf(valuate(probe, 200).jsonPath());
            assertEquals(reference, repeat, "Repetition " + i + " must yield the identical evaluation");
        }
        assertPoison("P1-zerobundle", basket("P1-zerobundle", "0105", plain("3300000000007", "1")),
                "Error validating offer:");
        assertPoison("P1-nmzero", basket("P1-nmzero", "0104", plain("3300000000007", "1")),
                "Error validating offer:");
    }

    // ==================================================
    // P2 — the maximal reference basket
    // ==================================================

    /**
     * P2 — the maximal reference non-regression basket. Seven distinct lines on store {@code 0101} in
     * {@code HOME_DELIVERY} to Seclin, with a vignettes map and a deposit instruction, fire every
     * offer family at once: three apples {@code …001} (N+M 2+1 + the standard leftover carrying two
     * immediate vouchers), coffee {@code …004} + biscuits {@code …013} (the fixed bundle), the poêle
     * {@code …031} redeemed with five vignettes, milk {@code …002}, riz {@code …022} and lentilles
     * {@code …023} at 5.5%. Home delivery adds the delivery service, the instruction the deposit
     * service, the crossed thresholds the franco discount, and the store its meal-voucher plate.
     * <p>
     * Rather than pin dozens of hand-computed cents (fragile), the scenario asserts the FIVE G6
     * structural invariants over the whole evaluation — {@code Σ items = offer amount} (2 dec.
     * HALF_UP) where items exist, an item-less offer carries a non-zero amount, no priced item shows
     * the blended {@code 0.0000} rate, each breakdown line has {@code vatAmount = TTC − HT} and the
     * breakdown is strictly increasing, and {@code Σ breakdown TTC = totalPrice} — plus the presence
     * of every family. Families are located by their literal {@code type} (HashSet guard), never by
     * index.
     */
    @Test
    void p2_maximalReferenceBasket() {
        JsonPath body = valuate(maximalBasket("P2",
                plain("3300000000001", "5"),
                plain("3300000000002", "1"),
                plain("3300000000004", "1"),
                plain("3300000000013", "1"),
                plain("3300000000022", "2"),
                plain("3300000000023", "2"),
                plain("3300000000031", "1")), 200).jsonPath();
        assertG6Invariants(body);
        assertTrue(offerPresent(body, "Standard:"), "A standard line must be priced: " + body.getList("offers.type"));
        assertTrue(offerPresent(body, "Mixed Bundle Promo:"), "The N+M family must fire: " + body.getList("offers.type"));
        assertTrue(offerPresent(body, "MixedBundle:"), "The bundle family must fire: " + body.getList("offers.type"));
        assertTrue(offerPresent(body, "Delivery:"), "The delivery service must fire: " + body.getList("offers.type"));
        assertTrue(offerPresent(body, "Deposit Basket:"), "The deposit service must fire: " + body.getList("offers.type"));
        assertTrue(advantagePresent(body, "Immediate Voucher Discount :"),
                "An immediate voucher must fire: " + body.getList("advantages.type"));
        assertTrue(advantagePresent(body, "Vignette Discount:"),
                "The vignette discount must fire: " + body.getList("advantages.type"));
        assertTrue(advantagePresent(body, "Free Delivery Threshold Discount:"),
                "The franco discount must fire: " + body.getList("advantages.type"));
        assertTrue(advantagePresent(body, "MEAL_VOUCHER"),
                "The meal-voucher plate must be emitted: " + body.getList("advantages.type"));
    }

    /**
     * Asserts the five G6 structural invariants over an evaluation.
     *
     * @param ev The parsed evaluation.
     */
    private static void assertG6Invariants(JsonPath ev) {
        int offerCount = ev.getList("offers").size();
        for (int i = 0; i < offerCount; i++) {
            BigDecimal offerTtc = new BigDecimal(ev.getString("offers[" + i + "].amount.amountIncludingTax"));
            int itemCount = ev.getList("offers[" + i + "].items").size();
            if (itemCount == 0) {
                assertNotEquals(0, offerTtc.compareTo(BigDecimal.ZERO),
                        "An offer without items must carry a non-zero amount: " + ev.getString("offers[" + i + "].type"));
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < itemCount; j++) {
                sum = sum.add(new BigDecimal(ev.getString("offers[" + i + "].items[" + j + "].amount.amountIncludingTax")));
                assertNotEquals("0.0000", ev.getString("offers[" + i + "].items[" + j + "].amount.vatRate"),
                        "A priced item never carries the blended rate: " + ev.getString("offers[" + i + "].type"));
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
            assertEquals(0, vat.compareTo(ttc.subtract(ht)), "vatAmount must equal TTC - HT on breakdown line " + k);
            if (previousRate != null) {
                assertTrue(rate.compareTo(previousRate) > 0, "VAT breakdown must be strictly increasing by rate");
            }
            previousRate = rate;
            breakdownTtc = breakdownTtc.add(ttc);
        }
        BigDecimal totalTtc = new BigDecimal(ev.getString("totalPrice.amountIncludingTax"));
        assertEquals(0, breakdownTtc.compareTo(totalTtc), "The VAT breakdown TTC must sum to the total price");
    }

    // ==================================================
    // P3 — the two specification failure paths
    // ==================================================

    /**
     * P3 — an offer specification is refused on two divergent paths, proving the CSV import and the
     * valuation validate at different depths.
     * <p>
     * PERSIST path: a spec that is not even valid JSON ({@code {bad json}}) is refused at
     * {@code @PrePersist} by {@code Offer.updateEansFromSpecification} — the CSV import surfaces
     * {@code Failed to parse specification for Offer P3A_BAD} in its error report and creates nothing.
     * <p>
     * VALUATION path: {@code P3_NONCONFORMING} carries the perfectly-parsable but schema-nonconforming
     * spec {@code {"foo":1}}; the CSV import performs NO schema validation so it imports cleanly, yet
     * the first valuation of its store rebuilds the appliers, runs the networknt schema, and is refused
     * 500 with the double-wrapped {@code … Error validating offer: …} recorded in the trace. UI form and
     * CSV therefore diverge: the UI would reject it, the CSV does not.
     */
    @Test
    void p3_specificationFailurePaths() {
        String bad = OFFER_HEADER
                + "P3A_BAD|MIXED_BUNDLE|{bad json}|0101|\n";
        Response persist = importCsvRaw("/offers/import", bad);
        String reported = persist.getBody().asString();
        assertTrue(reported.contains("Failed to parse specification for Offer P3A_BAD"),
                "The unparsable spec must be reported at persist, was: " + reported);
        assertPoison("P3B", basket("P3B", "0103", plain("3300000000007", "1")),
                "Error validating offer:");
    }

    // ==================================================
    // P4 — the Offer.eans recursive index
    // ==================================================

    /**
     * P4 — the {@code Offer.eans} index is extracted recursively and follows spec edits.
     * <p>
     * {@code P4_BUNDLE} declares {@code contents[].ean} ({@code …015}, {@code …023}) and a
     * {@code substituteEans} ({@code …016}); the UI EAN filter ({@code GET /ui/offers/export?ean=…})
     * locates it by EACH of the three nested EANs — proving the recursion walks nested objects and
     * arrays for every key ending in {@code ean}/{@code eans} — and NOT by an unrelated EAN. The index
     * order is a {@code HashSet}, so membership, never position, is asserted.
     * <p>
     * {@code @PreUpdate}: re-importing {@code P4_NM} with {@code targetEans:[…018]} instead of
     * {@code […017]} re-derives the index in place, so the filter that found it by {@code …017} no
     * longer does, while {@code …018} now does.
     */
    @Test
    void p4_recursiveEanIndex() {
        assertTrue(listByEan("3300000000015").contains("P4_BUNDLE"),
                "The bundle must be found by its contents[].ean …015");
        assertTrue(listByEan("3300000000023").contains("P4_BUNDLE"),
                "The bundle must be found by its second contents[].ean …023");
        assertTrue(listByEan("3300000000016").contains("P4_BUNDLE"),
                "The bundle must be found by its substituteEans …016");
        assertFalse(listByEan("3300000000025").contains("P4_BUNDLE"),
                "The bundle must NOT be found by an EAN absent from its spec");
        assertTrue(listByEan("3300000000017").contains("P4_NM"),
                "The N+M must be found by its targetEans …017 before the edit");
        importCsv("/offers/import", OFFER_HEADER
                + "P4_NM|N+M|{\"targetEans\":[\"3300000000018\"],\"quantityToPay\":1,\"discountedQuantity\":1,\"selectionStrategy\":\"CHEAPEST\",\"discountType\":\"PERCENTAGE\",\"discountValue\":100.0}|0102|\n");
        assertFalse(listByEan("3300000000017").contains("P4_NM"),
                "After the @PreUpdate the stale …017 no longer indexes the N+M");
        assertTrue(listByEan("3300000000018").contains("P4_NM"),
                "After the @PreUpdate the new …018 indexes the N+M");
    }

    /**
     * Lists the offers whose {@code eans} index matches an EAN, as the rendered admin HTML, via the UI
     * filter ({@code GET /ui/offers?ean=…}). The filter runs the same {@code exists (… o.eans e where e
     * like ?)} query the export uses, so the matching offer codes appearing in the page prove the index.
     *
     * @param ean The EAN to filter on.
     * @return The HTML body listing the matching offers.
     */
    private String listByEan(String ean) {
        return given().auth().preemptive().basic("admin", "admin")
                .queryParam("ean", ean)
                .when().get("/ui/offers")
                .then().statusCode(200)
                .extract().body().asString();
    }

    // ==================================================
    // P5 — volumetry (justified residue)
    // ==================================================

    /**
     * P5 — 80&nbsp;000-product volumetry and the quadratic dry-score load test. Disabled as justified
     * residue: {@code MassProductImporterClient} exists only as a standalone {@code main()} HTTP client
     * bound to the fixed port {@code 8090} (not the random {@code @QuarkusTest} port), with no
     * assertion, time budget or counter parsing. An 80&nbsp;000-product import plus a 50-line
     * quadratic-cost soak test has no automated harness and would pollute the shared catalog every other
     * scenario relies on; there is nothing to observe deterministically in CI.
     */
    @Test
    @Disabled("P5: no automated harness — MassProductImporterClient is a standalone main() on fixed port 8090, not a @QuarkusTest fixture")
    void p5_volumetry() {
        // Intentionally empty: see the Javadoc for the justified residue.
    }

    // ==================================================
    // P6 — the upsell pool availableToUpcell
    // ==================================================

    /**
     * P6 — a fully-consumed basket leaves {@code availableToUpcell} empty and emits no suggestion.
     * {@code availableToUpcell} is fed ONLY by the standard per-product applier
     * ({@code BasicOfferApplier} calls {@code addAvailableToUpcell} for each slice it prices). A basket
     * where nothing reaches the standard pool — three apples {@code …001} entirely consumed by the 2+1
     * N+M, coffee {@code …004} + biscuits {@code …013} entirely consumed by the fixed bundle, and milk
     * {@code …002} carried away by a manual gesture — therefore leaves the pool empty (Jackson omits the
     * empty map, so {@code availableToUpcell} is absent rather than {@code {}}) and emits no advantage
     * carrying a {@code suggestion}. The always-on meal-voucher plate (a suggestion-less
     * {@code MEAL_VOUCHER} advantage) is tolerated.
     */
    @Test
    void p6_emptyUpsellPool() {
        JsonPath body = valuate(basket("P6", "0101",
                plain("3300000000001", "3"),
                plain("3300000000004", "1"),
                plain("3300000000013", "1"),
                gesture("3300000000002", "1", "0.5")), 200).jsonPath();
        Map<String, Object> upcell = body.getMap("availableToUpcell");
        assertTrue(upcell == null || upcell.isEmpty(),
                "A fully-consumed basket leaves availableToUpcell empty (omitted or {}), was: " + upcell);
        int advantages = body.getList("advantages").size();
        for (int i = 0; i < advantages; i++) {
            assertNull(body.get("advantages[" + i + "].suggestion"),
                    "No upsell suggestion must be emitted, offending advantage: "
                            + body.getString("advantages[" + i + "].type"));
        }
    }

    // ==================================================
    // P7 — scale-sensitive price checksum
    // ==================================================

    /**
     * P7 — the price checksum is scale-sensitive, so re-importing an identical value at a wider scale
     * counts as an update. The checksum ({@code Objects.hash(… priceExcludingTax, priceIncludingTax …)})
     * hashes {@code BigDecimal}, whose {@code hashCode} distinguishes {@code 1.20} (scale 2) from
     * {@code 1.2000} (scale 4) though {@code compareTo} finds them equal. A fresh price for the couteaux
     * {@code …033} on store {@code 0102} imports as one CREATE; re-importing the SAME row with the value
     * widened to {@code 1.2000}/{@code 1.4400} yields one UPDATE — recurring import noise in operations,
     * frozen here.
     */
    @Test
    void p7_scaleSensitiveChecksum() {
        String create = PRICE_HEADER
                + "3300000000033|0102|1.20|1.44|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n";
        JsonPath created = importCsvRaw("/prices/import", create).jsonPath();
        assertEquals(1, created.getInt("createdCount"), "The fresh price is created once");
        assertEquals(0, created.getInt("updatedCount"), "Nothing is updated on the first import");
        String widened = PRICE_HEADER
                + "3300000000033|0102|1.2000|1.4400|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n";
        JsonPath updated = importCsvRaw("/prices/import", widened).jsonPath();
        assertEquals(0, updated.getInt("createdCount"), "The wider-scale row hits the same key, no create");
        assertEquals(1, updated.getInt("updatedCount"),
                "The scale-sensitive checksum flags the numerically-identical value as one update");
    }

    // ==================================================
    // P8 — inter-scenario hygiene
    // ==================================================

    /**
     * P8 — inter-scenario hygiene: the fixed clock is cleared, and a disposable graph is torn down in
     * reverse dependency order.
     * <p>
     * {@code DateTimeProvider}: pinning the clock to {@code 2026-01-01} — before the seed price start of
     * {@code 2026-01-12} — makes the apple {@code …001} un-priceable, so its valuation is refused 500
     * with {@code No active price found … Checked at date}. {@code DateTimeProvider.clear()} (always run
     * in the {@code finally}, the systematic end-of-scenario reset the catalog mandates) restores live
     * time, and the very same basket then valuates 200.
     * <p>
     * Reverse-order teardown: a disposable store/product/price graph is seeded through the import
     * endpoints, then deleted Price &rarr; Product &rarr; Store via the GraphQL mutations — each returns
     * {@code true} and leaves no row behind — while re-deleting a now-absent id returns {@code false}
     * without an exception. {@code deleteProductFamily} is deliberately NOT used to clean up (its F7
     * cascade would take the linked products with it); it is only probed on an absent id to confirm the
     * same null-safe {@code false}.
     */
    @Test
    void p8_hygieneClockAndReverseOrderCleanup() {
        try {
            DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
            assertPoison("P8-past", basket("P8-past", "0101", plain("3300000000001", "1")),
                    "No active price found");
            TraceView pastTrace = traceFor("P8-past");
            assertTrue(pastTrace.errorMessage.contains("Checked at date"),
                    "The no-price error must quote the checked date, was: " + pastTrace.errorMessage);
        } finally {
            DateTimeProvider.clear();
        }
        valuate(basket("P8-live", "0101", plain("3300000000001", "1")), 200);
        importCsv("/stores/import",
                "code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude\n"
                        + "P8DISP|P8 Disposable|1 Rue|Nord|59000|Lille|France|50.63|3.06\n");
        importCsv("/products/import",
                "ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active\n"
                        + "3309999999999|P8 Produit|desc|BrandZ|1.000|2.500|UNIT|pcs|true\n");
        importCsv("/prices/import", PRICE_HEADER
                + "3309999999999|P8DISP|1.00|1.20|0.2000|DEFAULT|0|2026-01-12T00:00:00|\n");
        Long store = storeId("P8DISP");
        Long product = productId("3309999999999");
        Long price = priceId("3309999999999", "P8DISP");
        assertNotNull(store, "The disposable store must exist before teardown");
        assertNotNull(product, "The disposable product must exist before teardown");
        assertNotNull(price, "The disposable price must exist before teardown");
        assertTrue(deleteMutation("deletePrice(id: " + price + ")", "deletePrice"), "The price deletes first");
        assertTrue(deleteMutation("deleteProduct(id: " + product + ")", "deleteProduct"), "The product deletes next");
        assertTrue(deleteMutation("deleteStore(id: " + store + ")", "deleteStore"), "The store deletes last");
        assertNull(priceId("3309999999999", "P8DISP"), "The price row is gone");
        assertNull(productId("3309999999999"), "The product row is gone");
        assertNull(storeId("P8DISP"), "The store row is gone");
        assertFalse(deleteMutation("deleteStore(id: " + store + ")", "deleteStore"),
                "Re-deleting an absent id returns false without an exception");
        assertFalse(deleteMutation("deleteProductFamily(id: 999999999)", "deleteProductFamily"),
                "deleteProductFamily on an absent id is the null-safe false (its cascade is never relied on)");
    }
}
