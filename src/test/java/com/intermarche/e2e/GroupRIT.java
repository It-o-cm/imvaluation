package com.intermarche.e2e;

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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group R — triggers &amp; arbitration (C1/C2) — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario runs over real HTTP against the in-JVM {@code @QuarkusTest} application, HTTP
 * Basic as {@code admin/admin}. The group needs only priced lines and its own trigger-bearing
 * offers, so it replays the referential slice of the mirror catalog (stores, VAT rates,
 * products, prices) ONCE at class start and then seeds ITS OWN offers through
 * {@code /offers/import} — never the base {@code 07-offers.csv}, whose store-{@code 0101}
 * promotions (an unconditional 15% voucher, a 2FOR1, a meal voucher…) would otherwise fire on
 * the same baskets and mask the trigger under test. {@link CatalogReset#resetMutableCatalog()}
 * wipes offers and prices first so the slice is pristine and the class is order-independent.
 * <p>
 * TRANSVERSE GUARD — {@code advantages} is a {@code HashSet}: a discount is located by a
 * {@code type} prefix (never by index). Fail-closed configurations (R7) leave a
 * {@code STATUS_SUCCESS}/200 trace whose {@code error_message} starts with
 * {@code "Skipped configurations: "}; that column is read back under a transaction keyed by a
 * unique {@code customerCode}, never from the HTTP body (skips are {@code @JsonIgnore}).
 * <p>
 * TRIGGER BASIS — a {@code MINIMUM_AMOUNT} threshold is measured tax-INCLUDED and reached
 * inclusively ({@code compareTo >= 0}, no epsilon): R1 straddles the boundary to the cent.
 */
@QuarkusTest
class GroupRIT {

    /**
     * The referential slice replayed before this group's own offers, in the mandated order.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * This group's own offers, all on store {@code 0101}, each carrying a distinct trigger.
     * <ul>
     *   <li>{@code R_TICKET_MIN} — a €1 ticket discount gated by a €6.00 (TTC) ticket minimum
     *       (R1, R5).</li>
     *   <li>{@code R_QTY_MIN} — gated by a minimum quantity of 3 on the apples EAN (R2).</li>
     *   <li>{@code R_COUPON} — gated by the coupon code {@code HIVER24} (R3).</li>
     *   <li>{@code R_AND} — gated by BOTH a €6.00 ticket minimum AND the coupon {@code COMBO}
     *       (R4, the implicit AND of {@code conditions[]}).</li>
     *   <li>{@code R_EXCL_A}/{@code R_EXCL_B} — two ticket discounts sharing an exclusion group,
     *       at priorities 100 and 200, both gated by the coupon {@code EXCL} so they stay out of
     *       R1–R5 (R6).</li>
     *   <li>{@code R_BAD_TRIGGER} — an unknown condition kind, imported without schema
     *       validation, skipped fail-closed at valuation. It is targeted at {@code 0102} (not
     *       {@code 0101}) because the fail-closed catch is per FACTORY TYPE, not per offer: a
     *       poison {@code TICKET_DISCOUNT} on {@code 0101} would skip the whole
     *       {@code TicketDiscountFactory} and drop every other ticket discount on that store
     *       (observed in {@link ValuationEngine#createDiscountAppliers}). On its own store its
     *       skip is isolated (R7).</li>
     * </ul>
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "R_TICKET_MIN|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":8.40}]}}|0101|\n"
            + "R_QTY_MIN|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_QUANTITY\",\"eans\":[\"3300000000001\"],\"threshold\":3}]}}|0101|\n"
            + "R_COUPON|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"HIVER24\"}]}}|0101|\n"
            + "R_AND|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":8.40},"
            + "{\"kind\":\"COUPON_CODE\",\"code\":\"COMBO\"}]}}|0101|\n"
            + "R_EXCL_A|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"EXCL\"}]},"
            + "\"arbitration\":{\"priority\":100,\"exclusionGroups\":[\"R_ONLY_ONE\"]}}|0101|\n"
            + "R_EXCL_B|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":2.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"EXCL\"}]},"
            + "\"arbitration\":{\"priority\":200,\"exclusionGroups\":[\"R_ONLY_ONE\"]}}|0101|\n"
            + "R_BAD_TRIGGER|TICKET_DISCOUNT|{\"discountType\":\"AMOUNT\",\"value\":1.00,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"UNKNOWN\"}]}}|0102|\n";

    /**
     * The apples EAN (WEIGHT, reference weight 1.000 kg), used for the quantity trigger.
     */
    private static final String APPLES = "3300000000001";

    /**
     * The milk EAN (UNIT); its priority-1 DEFAULT (2.76 TTC) wins over priority-0, so it is
     * used only where the ticket amount is irrelevant.
     */
    private static final String MILK = "3300000000002";

    /**
     * The coffee EAN (UNIT), a single unambiguous 4.20 TTC price on {@code 0101}: two units make
     * exactly 8.40, straddling the €8.40 minimum-amount threshold to the cent.
     */
    private static final String CAFE = "3300000000004";

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
        try (var in = GroupRIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * given prefix.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix to look for.
     * @return true when a matching advantage exists.
     */
    private boolean hasAdvantage(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return false;
        }
        return advantages.stream().anyMatch(a -> {
            Object type = a.get("type");
            return type != null && type.toString().startsWith(prefix);
        });
    }

    /**
     * Counts the advantages whose {@code type} starts with the given prefix.
     *
     * @param body   the parsed evaluation.
     * @param prefix the advantage-type prefix to look for.
     * @return the number of matching advantages.
     */
    private long countAdvantages(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return 0;
        }
        return advantages.stream()
                .filter(a -> a.get("type") != null && a.get("type").toString().startsWith(prefix))
                .count();
    }

    /**
     * Loads the error message of the latest trace for a customer code, inside a transaction.
     *
     * @param customerCode the unique customer code stamped on the probe.
     * @return the recorded error message, or null when absent.
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
     * Loads the status of the latest trace for a customer code, inside a transaction.
     *
     * @param customerCode the unique customer code stamped on the probe.
     * @return the {@code STATUS_*} discriminator, or null when absent.
     */
    private String statusFor(String customerCode) {
        String[] holder = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = ValuationTrace
                    .find("customerCode = ?1 order by createdAt desc", customerCode)
                    .firstResult();
            if (trace != null) {
                holder[0] = trace.status;
            }
        });
        return holder[0];
    }

    /**
     * Builds a single-line closed basket JSON.
     *
     * @param customer the customer code stamped for trace lookup.
     * @param ean      the product EAN.
     * @param quantity the line quantity as a JSON literal.
     * @return the basket JSON string.
     */
    private String oneLine(String customer, String ean, String quantity) {
        return String.format(
                "{\"customerCode\":\"%s\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"%s\",\"quantity\":%s}]}",
                customer, ean, quantity);
    }

    // --------------------------------------------------
    // R1 — minimum amount, inclusive boundary
    // --------------------------------------------------

    /**
     * R1 — a {@code MINIMUM_AMOUNT} (scope TICKET, threshold €8.40) fires inclusively with no
     * epsilon. A single coffee (4.20 TTC) leaves the ticket at 4.20 &lt; 8.40 → the
     * {@code R_TICKET_MIN} discount is absent; two coffees bring the ticket to exactly 8.40 TTC,
     * which reaches the threshold ({@code compareTo >= 0}) → the discount is present. The
     * boundary is asserted to the cent (coffee has a single unambiguous DEFAULT price, so the
     * measured ticket is exact).
     */
    @Test
    void r1_minimumAmountInclusiveBoundary() {
        JsonPath below = valuate(oneLine("R1-below", CAFE, "1"), 200);
        assertFalse(hasAdvantage(below, "Ticket Discount: R_TICKET_MIN"),
                "A 4.20 ticket is below the 8.40 threshold: no discount");
        JsonPath atThreshold = valuate(oneLine("R1-at", CAFE, "2"), 200);
        assertTrue(hasAdvantage(atThreshold, "Ticket Discount: R_TICKET_MIN"),
                "An 8.40 ticket reaches the 8.40 threshold inclusively: discount applies");
    }

    // --------------------------------------------------
    // R2 — minimum quantity at weight
    // --------------------------------------------------

    /**
     * R2 — a {@code MINIMUM_QUANTITY} (threshold 3) on the apples EAN sums BigDecimal
     * quantities exactly. Three kilograms (qty 3.000) reach the threshold and fire the
     * {@code R_QTY_MIN} discount; 2.999 kg stays strictly below and fires nothing — no
     * floating-point rounding lets it slip through.
     */
    @Test
    void r2_minimumQuantityAtWeight() {
        JsonPath below = valuate(oneLine("R2-below", APPLES, "2.999"), 200);
        assertFalse(hasAdvantage(below, "Ticket Discount: R_QTY_MIN"),
                "2.999 kg is strictly below the quantity threshold of 3");
        JsonPath atThreshold = valuate(oneLine("R2-at", APPLES, "3.000"), 200);
        assertTrue(hasAdvantage(atThreshold, "Ticket Discount: R_QTY_MIN"),
                "3.000 kg reaches the quantity threshold of 3");
    }

    // --------------------------------------------------
    // R3 — coupon code
    // --------------------------------------------------

    /**
     * R3 — a {@code COUPON_CODE} condition. Without any {@code couponCodes} the
     * {@code R_COUPON} discount is absent; presenting {@code HIVER24} makes it apply; presenting
     * a code no offer knows ({@code UNKNOWN_COUPON}) is tolerated — no discount, no error, a
     * clean SUCCESS trace.
     */
    @Test
    void r3_couponCode() {
        JsonPath none = valuate(oneLine("R3-none", MILK, "2"), 200);
        assertFalse(hasAdvantage(none, "Ticket Discount: R_COUPON"),
                "No coupon presented: the coupon-gated discount is absent");
        JsonPath withCode = valuate(
                "{\"customerCode\":\"R3-code\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"couponCodes\":[\"HIVER24\"],"
                        + "\"items\":[{\"produceEan\":\"" + MILK + "\",\"quantity\":2}]}", 200);
        assertTrue(hasAdvantage(withCode, "Ticket Discount: R_COUPON"),
                "The exact coupon HIVER24 fires the discount");
        JsonPath unknown = valuate(
                "{\"customerCode\":\"R3-unknown\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"couponCodes\":[\"UNKNOWN_COUPON\"],"
                        + "\"items\":[{\"produceEan\":\"" + MILK + "\",\"quantity\":2}]}", 200);
        assertFalse(hasAdvantage(unknown, "Ticket Discount: R_COUPON"),
                "An unknown coupon is tolerated: no discount");
        assertEquals(ValuationTrace.STATUS_SUCCESS, statusFor("R3-unknown"),
                "An unknown coupon leaves a clean SUCCESS trace");
        org.junit.jupiter.api.Assertions.assertNull(errorMessageFor("R3-unknown"),
                "A tolerated unknown coupon leaves no error message on the trace");
    }

    // --------------------------------------------------
    // R4 — conditions[] is an implicit AND
    // --------------------------------------------------

    /**
     * R4 — {@code conditions[]} is an implicit AND. {@code R_AND} needs BOTH a €6.00 ticket and
     * the coupon {@code COMBO}. Two milks with no coupon (amount met, coupon missing) fire
     * nothing; two milks WITH the coupon (both met) fire the discount.
     */
    @Test
    void r4_conditionsAreImplicitAnd() {
        JsonPath amountOnly = valuate(oneLine("R4-amount-only", CAFE, "2"), 200);
        assertFalse(hasAdvantage(amountOnly, "Ticket Discount: R_AND"),
                "Amount met but coupon missing: the AND is not satisfied");
        JsonPath both = valuate(
                "{\"customerCode\":\"R4-both\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"couponCodes\":[\"COMBO\"],"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":2}]}", 200);
        assertTrue(hasAdvantage(both, "Ticket Discount: R_AND"),
                "Both conditions satisfied: the discount applies");
    }

    // --------------------------------------------------
    // R5 — open vs closed basket
    // --------------------------------------------------

    /**
     * R5 — AT_TOTAL advantages only run on a closed basket. {@code R_TICKET_MIN} defaults to
     * {@code applicationMoment=AT_TOTAL}; a qualifying basket with {@code closed:false} produces
     * no ticket discount, whereas the same basket with {@code closed:true} — and with the field
     * absent (absent ⇒ closed, e-commerce closed by nature) — produces it.
     */
    @Test
    void r5_openBasketSkipsAtTotal() {
        JsonPath open = valuate(
                "{\"customerCode\":\"R5-open\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"closed\":false,"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":2}]}", 200);
        assertFalse(hasAdvantage(open, "Ticket Discount: R_TICKET_MIN"),
                "An open basket does not run AT_TOTAL advantages");
        JsonPath closed = valuate(
                "{\"customerCode\":\"R5-closed\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"closed\":true,"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":2}]}", 200);
        assertTrue(hasAdvantage(closed, "Ticket Discount: R_TICKET_MIN"),
                "A closed basket runs AT_TOTAL advantages");
        JsonPath absent = valuate(oneLine("R5-absent", CAFE, "2"), 200);
        assertTrue(hasAdvantage(absent, "Ticket Discount: R_TICKET_MIN"),
                "An absent closed flag means closed: AT_TOTAL advantages run");
    }

    // --------------------------------------------------
    // R6 — arbitration policy (exclusion group)
    // --------------------------------------------------

    /**
     * R6 — two ticket discounts sharing the exclusion group {@code R_ONLY_ONE} cannot both
     * apply on the same plate. {@code R_EXCL_A} (priority 100) is arbitrated before
     * {@code R_EXCL_B} (priority 200), so the lower-priority-number one wins; exactly one of the
     * two appears in {@code advantages}. The valuation stays a 200 SUCCESS.
     * <p>
     * CALIBRATION — the catalog says the refused member's reason is "traced", but an
     * exclusion-group refusal is NOT written to {@code valuation_traces.error_message} (that
     * column carries only fail-closed skips and budget refusals — see N8); the observable
     * contract here is the arbitration outcome, asserted below.
     */
    @Test
    void r6_exclusionGroupKeepsOnlyOne() {
        JsonPath body = valuate(
                "{\"customerCode\":\"R6-excl\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"couponCodes\":[\"EXCL\"],"
                        + "\"items\":[{\"produceEan\":\"" + MILK + "\",\"quantity\":2}]}", 200);
        long a = countAdvantages(body, "Ticket Discount: R_EXCL_A");
        long b = countAdvantages(body, "Ticket Discount: R_EXCL_B");
        assertEquals(1, a, "The priority-100 exclusion-group member applies");
        assertEquals(0, b, "The priority-200 exclusion-group member is refused");
        assertEquals(ValuationTrace.STATUS_SUCCESS, statusFor("R6-excl"),
                "An arbitration refusal does not fail the valuation");
    }

    // --------------------------------------------------
    // R7 — invalid trigger is fail-closed
    // --------------------------------------------------

    /**
     * R7 — an unknown condition kind is imported without schema validation (the CSV path does
     * not validate) and then skipped fail-closed at valuation: the response is 200, no
     * {@code R_BAD_TRIGGER} advantage is produced, and the trace is a {@code STATUS_SUCCESS}
     * whose {@code error_message} starts with {@code "Skipped configurations: "} and names the
     * offending offer code.
     */
    @Test
    void r7_invalidTriggerIsFailClosed() {
        JsonPath body = valuate(
                "{\"customerCode\":\"R7-bad\",\"storeCode\":\"0102\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"3300000000005\",\"quantity\":2}]}", 200);
        assertFalse(hasAdvantage(body, "Ticket Discount: R_BAD_TRIGGER"),
                "A skipped configuration produces no advantage");
        assertEquals(ValuationTrace.STATUS_SUCCESS, statusFor("R7-bad"),
                "A fail-closed skip is a SUCCESS, not a failure");
        String message = errorMessageFor("R7-bad");
        assertNotNull(message, "The skip must be recorded on the trace");
        assertTrue(message.startsWith("Skipped configurations: "),
                "The skip prefix must be present: " + message);
        assertTrue(message.contains("R_BAD_TRIGGER"),
                "The skip must name the offending offer: " + message);
    }
}
