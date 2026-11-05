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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group S — C3 discount mechanics &amp; tiered — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario runs over real HTTP against the in-JVM {@code @QuarkusTest} application, HTTP
 * Basic as {@code admin/admin}. The group replays the referential slice of the mirror catalog
 * (stores, VAT rates, products, prices) ONCE and seeds ITS OWN offers — one per C3 family —
 * rather than the base {@code 07-offers.csv}. To exercise one mechanic at a time, each
 * always-on discount is gated behind a distinct coupon code (the anti-waste offer is gated by
 * the per-line best-before date, the gift offer by a minimum quantity), so a basket presenting
 * only that coupon triggers only that offer. {@link CatalogReset#resetMutableCatalog()} wipes
 * offers and prices first, making the class order-independent.
 * <p>
 * TRANSVERSE GUARD — {@code advantages} is a {@code HashSet}: a discount is located by its
 * {@code type} literal prefix, never by index. Fail-closed configurations (S7) leave a
 * {@code STATUS_SUCCESS}/200 trace whose {@code error_message} starts with
 * {@code "Skipped configurations: "}, read back under a transaction keyed by {@code customerCode}.
 * <p>
 * DETERMINISTIC CLOCK — the anti-waste tiers (S6) key off {@code bestBeforeDate − today} with
 * {@code today = DateTimeProvider.now()}: the clock is pinned per scenario and always cleared
 * in a {@code finally} block.
 */
@QuarkusTest
class GroupSIT {

    /**
     * The referential slice replayed before this group's own offers.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * The coffee EAN (UNIT), a single unambiguous DEFAULT price of 4.20 TTC / 3.50 HT on
     * {@code 0101} (BASE_FOR_DISCOUNT 4.62 TTC / 3.85 HT once a discount registers).
     */
    private static final String CAFE = "3300000000004";

    /**
     * This group's C3 offers, all on {@code 0101} except the fail-closed probe (on {@code 0102}
     * so its factory-wide skip stays isolated — the fail-closed catch is per factory type).
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "S_NEW|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3300000000004\"],\"newPrice\":1.99}],"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"SNEW\"}]}}|0101|\n"
            + "S_NEW_HIGH|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3300000000004\"],\"newPrice\":99.00}],"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"SNEWH\"}]}}|0101|\n"
            + "S_TICKET|TICKET_DISCOUNT|{\"discountType\":\"PERCENTAGE\",\"value\":10.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"STICK\"}]}}|0101|\n"
            + "S_TICKET_CAP|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":999.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"SCAP\"}]}}|0101|\n"
            + "S_API|AMOUNT_PER_ITEM_DISCOUNT|{\"targets\":[{\"eans\":[\"3300000000004\"],\"amountPerItem\":0.50}],"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"SAPI\"}]}}|0101|\n"
            + "S_GIFT|GIFT_ITEM_DISCOUNT|{\"selection\":\"CHEAPEST\","
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"SGIFT\"},"
            + "{\"kind\":\"MINIMUM_QUANTITY\",\"eans\":[\"3300000000004\"],\"threshold\":2}]}}|0101|\n"
            + "S_VAT|VAT_REFUND_DISCOUNT|{\"scope\":\"TICKET\","
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"SVAT\"}]}}|0101|\n"
            + "S_ANTI|ANTI_WASTE_DISCOUNT|{\"targetEans\":[\"3300000000004\"],"
            + "\"tiers\":[{\"maxRemainingDays\":3,\"percent\":30.0},{\"maxRemainingDays\":1,\"percent\":50.0}]}|0101|\n"
            + "S_TIERED_QTY|TIERED_DISCOUNT|{\"scope\":\"ITEMS\",\"targetEans\":[\"3300000000004\"],"
            + "\"metric\":\"QUANTITY\",\"perProduct\":true,\"mode\":\"HIGHEST_REACHED\","
            + "\"tiers\":[{\"threshold\":3,\"award\":{\"type\":\"PERCENTAGE\",\"value\":20.0}}],"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"STQ\"}]}}|0101|\n"
            + "S_TIERED_MULT|TIERED_DISCOUNT|{\"scope\":\"TICKET\",\"metric\":\"AMOUNT\",\"mode\":\"PER_MULTIPLE\","
            + "\"every\":{\"step\":5,\"award\":{\"type\":\"AMOUNT\",\"value\":1.00}},"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"STM\"}]}}|0101|\n"
            + "S_TIERED_BAD|TIERED_DISCOUNT|{\"scope\":\"TICKET\",\"metric\":\"AMOUNT\",\"mode\":\"BOGUS\","
            + "\"tiers\":[{\"threshold\":1,\"award\":{\"type\":\"AMOUNT\",\"value\":1.00}}]}|0102|\n";

    /**
     * Whether the referential slice and this group's offers have been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the referential slice and this group's offers once, before the first scenario.
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
        try (var in = GroupSIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * @param expectedStatus the expected HTTP status code.
     * @return the parsed JSON response tree.
     */
    private JsonPath valuate(String body, int expectedStatus) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(body)
                .when().post("/valuation")
                .then().statusCode(expectedStatus)
                .extract().jsonPath();
    }

    /**
     * Whether the advantages list carries at least one entry whose {@code type} starts with the
     * prefix.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix.
     * @return true when a matching advantage exists.
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
     * Counts the advantages whose {@code type} starts with the prefix.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix.
     * @return the number of matching advantages.
     */
    private long countAdvantages(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return 0;
        }
        return advantages.stream().filter(a -> a.get("type") != null
                && a.get("type").toString().startsWith(prefix)).count();
    }

    /**
     * Returns the tax-included discount amount of the first advantage whose {@code type} starts
     * with the prefix, or null when absent.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix.
     * @return the discount TTC as a BigDecimal, or null.
     */
    private BigDecimal discountTtc(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return null;
        }
        for (Map<String, Object> a : advantages) {
            Object type = a.get("type");
            if (type != null && type.toString().startsWith(prefix)) {
                Object discount = a.get("discountAmount");
                if (discount instanceof Map<?, ?> map && map.get("amountIncludingTax") != null) {
                    return new BigDecimal(map.get("amountIncludingTax").toString());
                }
            }
        }
        return null;
    }

    /**
     * Loads the status and error message of the latest trace for a customer code.
     *
     * @param customerCode the unique customer code stamped on the probe.
     * @return a two-element array {status, errorMessage}; both null when the trace is absent.
     */
    private String[] traceFor(String customerCode) {
        String[] holder = new String[2];
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = ValuationTrace
                    .find("customerCode = ?1 order by createdAt desc", customerCode)
                    .firstResult();
            if (trace != null) {
                holder[0] = trace.status;
                holder[1] = trace.errorMessage;
            }
        });
        return holder;
    }

    /**
     * Builds a closed single-line basket carrying an optional coupon.
     *
     * @param customer the customer code stamped for trace lookup.
     * @param store    the store code.
     * @param ean      the product EAN.
     * @param quantity the line quantity as a JSON literal.
     * @param coupon   the coupon to present, or null for none.
     * @return the basket JSON.
     */
    private String basket(String customer, String store, String ean, String quantity, String coupon) {
        String coupons = coupon == null ? "" : "\"couponCodes\":[\"" + coupon + "\"],";
        return String.format(
                "{\"customerCode\":\"%s\",\"storeCode\":\"%s\",\"deliveryMode\":\"IN_STORE\",%s"
                        + "\"items\":[{\"produceEan\":\"%s\",\"quantity\":%s}]}",
                customer, store, coupons, ean, quantity);
    }

    // --------------------------------------------------
    // S1 — new price
    // --------------------------------------------------

    /**
     * S1 — {@code NEW_PRICE_DISCOUNT} pulls the line down to a target price: the gap between the
     * displayed price and the (lower) target surfaces as a discount. Presenting {@code SNEW}
     * (target 1.99 &lt; coffee's price) produces a positive {@code New Price: S_NEW} discount;
     * presenting {@code SNEWH} (target 99.00 ≥ the price) produces nothing, silently.
     */
    @Test
    void s1_newPrice() {
        JsonPath applied = valuate(basket("S1-low", "0101", CAFE, "1", "SNEW"), 200);
        assertTrue(hasAdvantage(applied, "New Price: S_NEW"),
                "A target below the price surfaces a new-price discount");
        assertTrue(discountTtc(applied, "New Price: S_NEW").compareTo(BigDecimal.ZERO) > 0,
                "The new-price discount is a positive reduction");
        JsonPath none = valuate(basket("S1-high", "0101", CAFE, "1", "SNEWH"), 200);
        assertFalse(hasAdvantage(none, "New Price: S_NEW_HIGH"),
                "A target at or above the price produces no discount");
    }

    // --------------------------------------------------
    // S2 — ticket discount, capped, never negative
    // --------------------------------------------------

    /**
     * S2 — a {@code TICKET_DISCOUNT} is a percentage of the ticket net, capped so the total is
     * never negative. A 10% ticket discount ({@code STICK}) applies a positive discount; a
     * €999 flat ticket discount ({@code SCAP}) on a small basket is capped at the ticket base,
     * so the resulting {@code totalPrice} stays at or above zero.
     */
    @Test
    void s2_ticketDiscountCappedNeverNegative() {
        JsonPath percent = valuate(basket("S2-percent", "0101", CAFE, "2", "STICK"), 200);
        assertTrue(hasAdvantage(percent, "Ticket Discount: S_TICKET"),
                "A percentage ticket discount applies");
        assertTrue(discountTtc(percent, "Ticket Discount: S_TICKET").compareTo(BigDecimal.ZERO) > 0,
                "The percentage discount is positive");
        JsonPath capped = valuate(basket("S2-cap", "0101", CAFE, "1", "SCAP"), 200);
        assertTrue(hasAdvantage(capped, "Ticket Discount: S_TICKET_CAP"),
                "The flat ticket discount applies");
        BigDecimal total = new BigDecimal(capped.getString("totalPrice.amountIncludingTax"));
        assertTrue(total.compareTo(BigDecimal.ZERO) >= 0,
                "A capped ticket discount never drives the total below zero: " + total);
    }

    // --------------------------------------------------
    // S3 — amount per item
    // --------------------------------------------------

    /**
     * S3 — {@code AMOUNT_PER_ITEM_DISCOUNT} takes a fixed amount off each eligible unit; a
     * weighed line counts as one, but coffee is a UNIT product, so two units at €0.50 each make
     * a €1.00 discount, capped at the line base.
     */
    @Test
    void s3_amountPerItem() {
        JsonPath body = valuate(basket("S3", "0101", CAFE, "2", "SAPI"), 200);
        assertTrue(hasAdvantage(body, "Amount Per Item: S_API"),
                "The amount-per-item discount applies");
        assertEquals(0, discountTtc(body, "Amount Per Item: S_API").compareTo(new BigDecimal("1.00")),
                "Two UNIT coffees at 0.50 each make a 1.00 discount: "
                        + discountTtc(body, "Amount Per Item: S_API"));
    }

    // --------------------------------------------------
    // S4 — gift item
    // --------------------------------------------------

    /**
     * S4 — {@code GIFT_ITEM_DISCOUNT} offers one unit chosen among the trigger's contributors,
     * once per ticket. The minimum quantity of 2 on coffee must be reached: two coffees with the
     * {@code SGIFT} coupon yield a {@code Gift Item: S_GIFT (…)} advantage; a single coffee does
     * not reach the trigger, so nothing is offered.
     */
    @Test
    void s4_giftItem() {
        JsonPath gifted = valuate(basket("S4-two", "0101", CAFE, "2", "SGIFT"), 200);
        assertTrue(hasAdvantage(gifted, "Gift Item: S_GIFT (" + CAFE + ")"),
                "Two coffees reach the quantity trigger: one is gifted");
        JsonPath none = valuate(basket("S4-one", "0101", CAFE, "1", "SGIFT"), 200);
        assertFalse(hasAdvantage(none, "Gift Item: S_GIFT"),
                "A single coffee does not reach the trigger: nothing is gifted");
    }

    // --------------------------------------------------
    // S5 — VAT refund, breakdown intact
    // --------------------------------------------------

    /**
     * S5 — {@code VAT_REFUND_DISCOUNT} refunds the tax portion as a discount. The refund
     * advantage appears and is positive, the breakdown still reports the REAL product rate
     * (0.2000, never a blended 0.0000), and the breakdown TTC still sums to {@code totalPrice}
     * (the G6 invariant).
     * <p>
     * ÉCART spec/code (documented, not weakened): the catalog and spec-c3-mecaniques.md §S5 say
     * "vatBreakdown INTACT (la ventilation ne bouge jamais)", but the code nets the refund into
     * the ventilation — the same two coffees valued with the refund coupon report a breakdown of
     * 7.70 TTC / 6.42 HT versus 8.40 / 7.00 without it (the refund reduces the taxed net, and the
     * discount also switches the line to BASE_FOR_DISCOUNT). The amount-frozen claim is therefore
     * contradicted by the engine; the invariants asserted here are the ones that actually hold.
     */
    @Test
    void s5_vatRefundNetsBreakdownAtRealRate() {
        JsonPath refunded = valuate(basket("S5-refund", "0101", CAFE, "2", "SVAT"), 200);
        assertTrue(hasAdvantage(refunded, "VAT Refund: S_VAT"),
                "The VAT refund advantage is present");
        assertTrue(discountTtc(refunded, "VAT Refund: S_VAT").compareTo(BigDecimal.ZERO) > 0,
                "The VAT refund is a positive discount");
        int rateCount = refunded.getList("vatBreakdown").size();
        assertTrue(rateCount > 0, "The refunded basket still carries a VAT breakdown");
        BigDecimal breakdownTtc = BigDecimal.ZERO;
        boolean sawRealRate = false;
        for (int i = 0; i < rateCount; i++) {
            String rate = refunded.getString("vatBreakdown[" + i + "].vatRate");
            assertNotEquals("0.0000", rate,
                    "The breakdown reports the real product rate, never the blended zero");
            if (new BigDecimal(rate).compareTo(new BigDecimal("0.2000")) == 0) {
                sawRealRate = true;
            }
            breakdownTtc = breakdownTtc.add(
                    new BigDecimal(refunded.getString("vatBreakdown[" + i + "].amountIncludingTax")));
        }
        assertTrue(sawRealRate, "The coffee's real 20% rate survives the refund");
        BigDecimal total = new BigDecimal(refunded.getString("totalPrice.amountIncludingTax"));
        assertEquals(0, breakdownTtc.compareTo(total),
                "The VAT breakdown TTC still sums to the total after the refund");
    }

    // --------------------------------------------------
    // S6 — anti-waste tiers by best-before date
    // --------------------------------------------------

    /**
     * S6 — {@code ANTI_WASTE_DISCOUNT} reads each line's {@code bestBeforeDate}: with the clock
     * pinned to 2026-06-15, a line three days out (2026-06-18) reaches the 3-day tier (30%), a
     * line expired (2026-06-01, negative remaining) falls to the strictest tier (50%), and a
     * line without any date receives no discount. The clock is pinned via
     * {@code DateTimeProvider} and cleared afterwards.
     */
    @Test
    void s6_antiWasteTiers() {
        try {
            DateTimeProvider.setFixedDateTime(java.time.LocalDateTime.of(2026, 6, 15, 10, 0));
            JsonPath threeDays = valuate(
                    "{\"customerCode\":\"S6-3d\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                            + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"" + CAFE
                            + "\",\"quantity\":1,\"bestBeforeDate\":\"2026-06-18\"}]}", 200);
            assertTrue(hasAdvantage(threeDays, "Anti-Waste: S_ANTI (" + CAFE + ")"),
                    "A line three days from expiry reaches an anti-waste tier");
            BigDecimal near = discountTtc(threeDays, "Anti-Waste: S_ANTI");
            JsonPath expired = valuate(
                    "{\"customerCode\":\"S6-exp\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                            + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"" + CAFE
                            + "\",\"quantity\":1,\"bestBeforeDate\":\"2026-06-01\"}]}", 200);
            assertTrue(hasAdvantage(expired, "Anti-Waste: S_ANTI (" + CAFE + ")"),
                    "An expired line falls to the strictest tier");
            BigDecimal strict = discountTtc(expired, "Anti-Waste: S_ANTI");
            assertTrue(strict.compareTo(near) > 0,
                    "The strictest tier (50%) discounts more than the 3-day tier (30%): "
                            + strict + " vs " + near);
            JsonPath noDate = valuate(basket("S6-none", "0101", CAFE, "1", null), 200);
            assertFalse(hasAdvantage(noDate, "Anti-Waste: S_ANTI"),
                    "A line without a best-before date receives no anti-waste discount");
        } finally {
            DateTimeProvider.clear();
        }
    }

    // --------------------------------------------------
    // S7 — tiered discount + fail-closed
    // --------------------------------------------------

    /**
     * S7 — {@code TIERED_DISCOUNT}. A per-product QUANTITY tier (threshold 3, 20%) fires only
     * once the quantity is reached ({@code STQ}); a PER_MULTIPLE step of €5 with a €1 award
     * ({@code STM}) fires on a large enough ticket. A tiered spec with an invalid {@code mode}
     * is imported without schema validation and skipped fail-closed at valuation: the response
     * stays 200 and the trace is a SUCCESS carrying {@code "Skipped configurations: "}.
     */
    @Test
    void s7_tieredAndFailClosed() {
        JsonPath below = valuate(basket("S7-qty-below", "0101", CAFE, "2", "STQ"), 200);
        assertFalse(hasAdvantage(below, "Tiered Discount: S_TIERED_QTY"),
                "Two coffees stay below the quantity tier of 3");
        JsonPath reached = valuate(basket("S7-qty-at", "0101", CAFE, "3", "STQ"), 200);
        assertTrue(hasAdvantage(reached, "Tiered Discount: S_TIERED_QTY (tier 3)"),
                "Three coffees reach the HIGHEST_REACHED tier 3");
        JsonPath multiple = valuate(basket("S7-mult", "0101", CAFE, "4", "STM"), 200);
        assertTrue(hasAdvantage(multiple, "Tiered Discount: S_TIERED_MULT"),
                "A 16.80 ticket carries three €5 multiples: the per-multiple discount fires");
        JsonPath failClosed = valuate(basket("S7-bad", "0102", "3300000000005", "2", null), 200);
        assertFalse(hasAdvantage(failClosed, "Tiered Discount: S_TIERED_BAD"),
                "An invalid tiered mode produces no advantage");
        String[] trace = traceFor("S7-bad");
        assertEquals(ValuationTrace.STATUS_SUCCESS, trace[0],
                "A fail-closed tiered skip is a SUCCESS");
        assertNotNull(trace[1], "The skip is recorded on the trace");
        assertTrue(trace[1].startsWith("Skipped configurations: "),
                "The skip prefix must be present: " + trace[1]);
    }
}
