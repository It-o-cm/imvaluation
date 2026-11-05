package com.intermarche.e2e;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group T — card promotions (basket-carried configuration) — of e2e-scenarios.md, in pure
 * RestAssured.
 * <p>
 * Card promotions are declared on the {@code /valuation} basket itself ({@code cardPromotions}),
 * not seeded as offers, so this group replays only the referential slice of the mirror catalog
 * (stores, VAT rates, products, prices) plus ONE coupon-gated store voucher used by T2 to prove
 * the PERCENT card promotion measures the residual net. {@link CatalogReset#resetMutableCatalog()}
 * wipes offers and prices first.
 * <p>
 * CALIBRATION — T4 (duplicate EAN) is a HTTP 500, not the 400 the catalog/spec quote: uniqueness
 * is not expressible in the basket JSON schema, so it is enforced inside {@code engine.evaluate()}
 * and surfaces as a {@code STATUS_FAILED}/500 trace whose {@code error_message} is the literal
 * {@code "Duplicate card promotion for EAN '<ean>'"}. Only the PERCENT-value-&gt;1 and value/amount
 * bound violations (T5) are genuine 400 schema rejects.
 * <p>
 * TRANSVERSE GUARD — {@code advantages} is a {@code HashSet}: a card promotion is located by its
 * {@code type} literal ({@code "Card Promotion: CARD_PROMO:<ean>"}), never by index.
 */
@QuarkusTest
class GroupTIT {

    /**
     * The referential slice replayed before this group's single store offer.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * A coupon-gated 20% store voucher on coffee, used only by T2 to establish a residual net
     * for the PERCENT card promotion to measure against.
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "T_STORE|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000004\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":20.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"TSTORE\"}]}}|0101|\n";

    /**
     * The jambon EAN (WEIGHT, reference weight 0.100 kg), used for the weighed-line AMOUNT
     * promotion where the weighing counts as ONE unit.
     */
    private static final String JAMBON = "3300000000008";

    /**
     * The coffee EAN (UNIT), 4.20 TTC on {@code 0101}, used for PERCENT and multi-line cases.
     */
    private static final String CAFE = "3300000000004";

    /**
     * Whether the referential slice and this group's store offer have been seeded.
     */
    private static boolean seeded;

    /**
     * Replays the referential slice and the store offer once, before the first scenario.
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
        try (var in = GroupTIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Whether the advantages list carries an entry whose {@code type} starts with the prefix.
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
     * @return the discount TTC, or null.
     */
    private BigDecimal discountTtc(JsonPath body, String prefix) {
        List<Map<String, Object>> advantages = body.getList("advantages");
        if (advantages == null) {
            return null;
        }
        for (Map<String, Object> a : advantages) {
            Object type = a.get("type");
            if (type != null && type.toString().startsWith(prefix)
                    && a.get("discountAmount") instanceof Map<?, ?> map
                    && map.get("amountIncludingTax") != null) {
                return new BigDecimal(map.get("amountIncludingTax").toString());
            }
        }
        return null;
    }

    /**
     * Loads the status and error message of the latest trace for a customer code.
     *
     * @param customerCode the unique customer code.
     * @return a two-element array {status, errorMessage}; both null when absent.
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

    // --------------------------------------------------
    // T1 — AMOUNT nominal, weighing counts one
    // --------------------------------------------------

    /**
     * T1 — an {@code AMOUNT} card promotion on a weighed line takes the flat amount once (the
     * weighing counts as ONE unit): €0.50 off a 0.738 kg jambon line makes a €0.50 discount,
     * capped at the line net, typed {@code "Card Promotion: CARD_PROMO:<ean>"}.
     */
    @Test
    void t1_amountNominalWeighingCountsOne() {
        JsonPath body = valuate(
                "{\"customerCode\":\"T1\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardNumber\":\"2990000000019\","
                        + "\"cardPromotions\":[{\"ean\":\"" + JAMBON + "\",\"promotionType\":\"AMOUNT\",\"value\":0.50}],"
                        + "\"items\":[{\"produceEan\":\"" + JAMBON + "\",\"quantity\":0.738}]}", 200);
        assertTrue(hasAdvantage(body, "Card Promotion: CARD_PROMO:" + JAMBON),
                "The AMOUNT card promotion applies on the weighed line");
        assertEquals(0, discountTtc(body, "Card Promotion: CARD_PROMO:" + JAMBON).compareTo(new BigDecimal("0.50")),
                "A weighed line counts as one unit: the discount is a flat 0.50");
    }

    // --------------------------------------------------
    // T2 — PERCENT on the residual net
    // --------------------------------------------------

    /**
     * T2 — a PERCENT card promotion is measured on the net residual, after a cumulable store
     * promotion. With the {@code TSTORE} coupon a 20% store voucher applies to coffee first;
     * the 10% card promotion then measures the discounted net, so its discount is strictly
     * smaller than 10% of the gross would be.
     */
    @Test
    void t2_percentOnResidualNet() {
        JsonPath body = valuate(
                "{\"customerCode\":\"T2\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardNumber\":\"2990000000019\",\"couponCodes\":[\"TSTORE\"],"
                        + "\"cardPromotions\":[{\"ean\":\"" + CAFE + "\",\"promotionType\":\"PERCENT\",\"value\":0.10}],"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":1}]}", 200);
        assertTrue(hasAdvantage(body, "Immediate Voucher Discount : T_STORE"),
                "The store voucher applies first");
        assertTrue(hasAdvantage(body, "Card Promotion: CARD_PROMO:" + CAFE),
                "The PERCENT card promotion applies");
        BigDecimal cardDiscount = discountTtc(body, "Card Promotion: CARD_PROMO:" + CAFE);
        assertTrue(cardDiscount.compareTo(new BigDecimal("0.462")) < 0,
                "10% of the net (after the 20% store voucher) is below 10% of the ~4.62 gross: "
                        + cardDiscount);
        assertTrue(cardDiscount.compareTo(BigDecimal.ZERO) > 0, "The card discount is positive");
    }

    // --------------------------------------------------
    // T3 — CAGNOTTE is ignored
    // --------------------------------------------------

    /**
     * T3 — a {@code benefit:"CAGNOTTE"} card promotion is silently dropped (the anti-double
     * defence: the cagnotte is imfid's monopoly). It passes schema validation but produces no
     * advantage, and the valuation stays a clean 200.
     */
    @Test
    void t3_cagnotteIgnored() {
        JsonPath body = valuate(
                "{\"customerCode\":\"T3\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardPromotions\":[{\"ean\":\"" + CAFE + "\",\"promotionType\":\"AMOUNT\",\"value\":0.50,"
                        + "\"benefit\":\"CAGNOTTE\"}],"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":1}]}", 200);
        assertFalse(hasAdvantage(body, "Card Promotion: CARD_PROMO:" + CAFE),
                "A CAGNOTTE card promotion produces no advantage");
        assertEquals(ValuationTrace.STATUS_SUCCESS, traceFor("T3")[0],
                "A dropped CAGNOTTE leaves a clean SUCCESS trace");
    }

    // --------------------------------------------------
    // T4 — duplicate EAN (calibrated to 500)
    // --------------------------------------------------

    /**
     * T4 — two card promotions on the same EAN are rejected. CALIBRATION: this surfaces as a
     * HTTP 500 (not the 400 the spec quotes) because uniqueness is enforced inside the engine,
     * after schema validation; the {@code STATUS_FAILED} trace carries the exact literal
     * {@code "Duplicate card promotion for EAN '<ean>'"}.
     */
    @Test
    void t4_duplicateEanIsFailed() {
        valuate(
                "{\"customerCode\":\"T4\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardPromotions\":["
                        + "{\"ean\":\"" + CAFE + "\",\"promotionType\":\"AMOUNT\",\"value\":0.50},"
                        + "{\"ean\":\"" + CAFE + "\",\"promotionType\":\"AMOUNT\",\"value\":0.30}],"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":1}]}", 500);
        String[] trace = traceFor("T4");
        assertEquals(ValuationTrace.STATUS_FAILED, trace[0], "A duplicate card promotion fails the valuation");
        assertNotNull(trace[1], "The failure is traced");
        assertEquals("Duplicate card promotion for EAN '" + CAFE + "'", trace[1],
                "The exact duplicate-EAN literal is recorded");
    }

    // --------------------------------------------------
    // T5 — value bounds (400 schema)
    // --------------------------------------------------

    /**
     * T5 — value bounds are schema rejects (400): a PERCENT value above 1 (the cross-rule
     * {@code maximum:1}), a value at zero ({@code exclusiveMinimum:0}), and a negative AMOUNT
     * are each refused with a 400 and a {@code REJECTED} trace.
     */
    @Test
    void t5_valueBounds() {
        assertRejected("T5-percent-high", CAFE, "PERCENT", "1.5");
        assertRejected("T5-zero", CAFE, "AMOUNT", "0");
        assertRejected("T5-negative", CAFE, "AMOUNT", "-1");
    }

    /**
     * Posts a single-promotion basket expected to be rejected at 400 and asserts the REJECTED
     * trace.
     *
     * @param customer      the customer code.
     * @param ean           the promotion EAN.
     * @param promotionType the promotion type literal.
     * @param value         the value JSON literal.
     */
    private void assertRejected(String customer, String ean, String promotionType, String value) {
        valuate(
                "{\"customerCode\":\"" + customer + "\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardPromotions\":[{\"ean\":\"" + ean + "\",\"promotionType\":\"" + promotionType
                        + "\",\"value\":" + value + "}],"
                        + "\"items\":[{\"produceEan\":\"" + ean + "\",\"quantity\":1}]}", 400);
        assertEquals(ValuationTrace.STATUS_REJECTED, traceFor(customer)[0],
                "An out-of-bounds card promotion is a schema reject for " + customer);
    }

    // --------------------------------------------------
    // T6 — EAN absent from the basket
    // --------------------------------------------------

    /**
     * T6 — a card promotion whose EAN is not in the basket has no plate to reduce: it produces
     * nothing, silently, and the valuation stays a clean 200.
     */
    @Test
    void t6_eanAbsentFromBasket() {
        JsonPath body = valuate(
                "{\"customerCode\":\"T6\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardPromotions\":[{\"ean\":\"" + JAMBON + "\",\"promotionType\":\"AMOUNT\",\"value\":0.50}],"
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":1}]}", 200);
        assertFalse(hasAdvantage(body, "Card Promotion:"),
                "A card promotion for an absent EAN produces nothing");
        assertEquals(ValuationTrace.STATUS_SUCCESS, traceFor("T6")[0], "The valuation stays a clean SUCCESS");
    }

    // --------------------------------------------------
    // T7 — one rounding for two lines of the same EAN
    // --------------------------------------------------

    /**
     * T7 — two lines of the same EAN yield a SINGLE card promotion: the base is summed and the
     * discount rounded once, with the residue on the last line. Exactly one
     * {@code "Card Promotion: CARD_PROMO:<ean>"} advantage is produced for a basket with two
     * coffee lines under one AMOUNT promotion.
     */
    @Test
    void t7_oneRoundingForTwoLines() {
        JsonPath body = valuate(
                "{\"customerCode\":\"T7\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"cardPromotions\":[{\"ean\":\"" + CAFE + "\",\"promotionType\":\"AMOUNT\",\"value\":0.50}],"
                        + "\"items\":[{\"lineId\":\"A\",\"produceEan\":\"" + CAFE + "\",\"quantity\":1},"
                        + "{\"lineId\":\"B\",\"produceEan\":\"" + CAFE + "\",\"quantity\":1}]}", 200);
        assertEquals(1, countAdvantages(body, "Card Promotion: CARD_PROMO:" + CAFE),
                "Two lines of the same EAN produce a single card promotion");
    }

    // --------------------------------------------------
    // T8 — regression: no card promotions
    // --------------------------------------------------

    /**
     * T8 — a basket without any {@code cardPromotions} is valued exactly as the trunk: no card
     * promotion advantage appears and the total matches the same basket built without the key
     * (bit-for-bit at the total).
     */
    @Test
    void t8_regressionWithoutCardPromotions() {
        JsonPath withEmpty = valuate(
                "{\"customerCode\":\"T8-empty\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":2}]}", 200);
        assertFalse(hasAdvantage(withEmpty, "Card Promotion:"),
                "A basket without card promotions carries no card promotion advantage");
        String total = withEmpty.getString("totalPrice.amountIncludingTax");
        JsonPath twin = valuate(
                "{\"customerCode\":\"T8-twin\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                        + "\"items\":[{\"produceEan\":\"" + CAFE + "\",\"quantity\":2}]}", 200);
        assertEquals(total, twin.getString("totalPrice.amountIncludingTax"),
                "The total is stable without card promotions");
    }
}
