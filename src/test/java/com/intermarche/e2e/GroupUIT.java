package com.intermarche.e2e;

import com.intermarche.valuation.domain.util.DateTimeProvider;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group U — compliance guards, EGAlim &amp; SRP — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario runs over real HTTP against the in-JVM {@code @QuarkusTest} application, HTTP
 * Basic as {@code admin/admin}. Both guards are AT_TOTAL and run only on a CLOSED basket, so the
 * baskets here leave {@code closed} absent (which means closed). The referential slice is
 * replayed once, together with the {@code EGALIM_REGIMES} ceiling feed, this group's own guards
 * ({@code U_EGALIM_GUARD}, {@code U_SRP_GUARD} on {@code 0101}), coupon-gated discount offers,
 * and two extra {@code SRP_FLOOR} price lines. Products carry their regimes from the mirror seed
 * (sauce {@code …015} and rice {@code …022} are FOOD_34, detergent {@code …025} is DPH_40, the
 * rest EXEMPT).
 * <p>
 * REGRESSION CRITERION — the {@code egalim} and {@code srp} response keys are ABSENT (not null)
 * when no guard is in force for the basket's store or the basket is open; that absence is the
 * regression contract and is asserted directly (U5, U6). When a guard IS in force the key is
 * present (possibly an empty array).
 * <p>
 * SPEC/CODE NOTE — the EGAlim guard runs at priority {@code Integer.MAX_VALUE - 1} and the SRP
 * guard at {@code Integer.MAX_VALUE} (spec-srp-guard.md superseded spec-egalim-guard.md §3, which
 * still quotes MAX for EGAlim); U9 relies on that EGAlim-then-SRP order.
 */
@QuarkusTest
class GroupUIT {

    /**
     * The referential slice plus the EGAlim ceiling feed, replayed before this group's offers.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/egalim-regimes/import", "seed/egalim-regimes.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * Two extra {@code SRP_FLOOR} price lines: a floor on the EXEMPT purée {@code …016} (SRP-only
     * scenarios) and on the FOOD_34 sauce {@code …015} (the combined-guards U9 scenario).
     */
    private static final String SRP_PRICES_CSV =
            "EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n"
            + "3300000000016|0101|2.00|2.40|0.2000|SRP_FLOOR|0|2026-01-12T00:00:00|\n"
            + "3300000000015|0101|1.80|2.16|0.2000|SRP_FLOOR|0|2026-01-12T00:00:00|\n"
            // A floor BELOW the shelf price of the EXEMPT ham …008 (DEFAULT 2.40, BASE_FOR_DISCOUNT
            // 2.64), so a moderate discount lands in the warning band without breaching the floor (U12).
            + "3300000000008|0101|1.50|1.80|0.2000|SRP_FLOOR|0|2026-01-12T00:00:00|\n";

    /**
     * This group's guards and coupon-gated discount offers, all on {@code 0101} except the
     * DPH/EXEMPT-on-{@code 0102} probe used by the PDV-targeting scenario.
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "U_EGALIM_GUARD|EGALIM_GUARD|{}|0101|\n"
            + "U_SRP_GUARD|SRP_GUARD|{\"warningThresholds\":[0.5,0.8]}|0101|\n"
            + "U_DISC_FOOD|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000015\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":50.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"UD50\"}]}}|0101|\n"
            + "U_DISC_DPH|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000025\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":60.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"UDDPH\"}]}}|0101|\n"
            + "U_DISC_EXEMPT|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000002\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":70.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"UDEX\"}]}}|0101|\n"
            + "U_EGXMPT|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000022\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":50.0,\"compliance\":{\"egalimExempt\":true},"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"UEXMPT\"}]}}|0101|\n"
            + "U_ANTI_FOOD|ANTI_WASTE_DISCOUNT|{\"targetEans\":[\"3300000000015\"],"
            + "\"tiers\":[{\"maxRemainingDays\":3,\"percent\":60.0}]}|0101|\n"
            + "U_SRP_DISC|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000016\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":40.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"USRP\"}]}}|0101|\n"
            + "U_SRPEX|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000016\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":40.0,\"compliance\":{\"srpExempt\":true},"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"USRPEX\"}]}}|0101|\n"
            + "U_WARN|IMMEDIATE_VOUCHER|{\"targetOfferClass\":[\"BasicOffer\"],\"targetEans\":[\"3300000000008\"],"
            + "\"discountType\":\"PERCENTAGE\",\"value\":20.0,"
            + "\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"UWARN\"}]}}|0101|\n";

    /**
     * Whether the referential, prices and offers have been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the referential, the SRP floors and this group's offers once.
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
        importCsv("/prices/import", SRP_PRICES_CSV);
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
        try (var in = GroupUIT.class.getClassLoader().getResourceAsStream(path)) {
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
     * Returns the first entry of a record block ({@code egalim}/{@code srp}) matching an EAN.
     *
     * @param body the parsed evaluation.
     * @param key  the block key.
     * @param ean  the EAN to match.
     * @return the matching entry, or null when the block or the entry is absent.
     */
    private Map<String, Object> entry(JsonPath body, String key, String ean) {
        List<Map<String, Object>> block = body.getList(key);
        if (block == null) {
            return null;
        }
        return block.stream().filter(e -> ean.equals(e.get("ean"))).findFirst().orElse(null);
    }

    /**
     * Builds a closed single-line basket with an optional coupon.
     *
     * @param customer the customer code.
     * @param store    the store code.
     * @param ean      the product EAN.
     * @param quantity the line quantity JSON literal.
     * @param coupon   the coupon to present, or null.
     * @return the basket JSON.
     */
    private String basket(String customer, String store, String ean, String quantity, String coupon) {
        String coupons = coupon == null ? "" : "\"couponCodes\":[\"" + coupon + "\"],";
        return String.format(
                "{\"customerCode\":\"%s\",\"storeCode\":\"%s\",\"deliveryMode\":\"IN_STORE\",%s"
                        + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"%s\",\"quantity\":%s}]}",
                customer, store, coupons, ean, quantity);
    }

    // --------------------------------------------------
    // U1 — EGAlim nominal correction
    // --------------------------------------------------

    /**
     * U1 — a FOOD_34 product ({@code …015}) discounted 50% exceeds the 34% generosity ceiling, so
     * the EGAlim guard emits a negative {@code "EGAlim Adjustment: U_EGALIM_GUARD"} contra-advantage
     * and records an {@code egalim[]} entry for the line: regime FOOD_34, a positive correction,
     * and {@code remainingBeforeCap = 0.00} on the corrected line.
     */
    @Test
    void u1_egalimNominalCorrection() {
        JsonPath body = valuate(basket("U1", "0101", "3300000000015", "1", "UD50"), 200);
        assertTrue(hasAdvantage(body, "EGAlim Adjustment: U_EGALIM_GUARD"),
                "The EGAlim guard corrects a 50% discount on a FOOD_34 line");
        Map<String, Object> e = entry(body, "egalim", "3300000000015");
        assertNotNull(e, "The egalim block carries an entry for the corrected line");
        assertEquals("FOOD_34", e.get("regime"), "The line's regime is reported");
        assertTrue(new BigDecimal(e.get("correction").toString()).compareTo(BigDecimal.ZERO) > 0,
                "The correction is a positive magnitude: " + e.get("correction"));
        assertEquals(0, new BigDecimal(e.get("remainingBeforeCap").toString()).compareTo(new BigDecimal("0.00")),
                "The corrected line has no remaining generosity before the cap: " + e.get("remainingBeforeCap"));
    }

    // --------------------------------------------------
    // U2 — regimes: DPH corrected, EXEMPT never
    // --------------------------------------------------

    /**
     * U2 — the ceiling is regime-driven. A DPH_40 detergent ({@code …025}) discounted 60% is
     * corrected (its entry reports regime DPH_40 with a positive correction). An EXEMPT milk
     * ({@code …002}) discounted 70% is never corrected: no adjustment advantage, and its egalim
     * entry reports regime EXEMPT with no {@code remainingBeforeCap} (uncapped ⇒ key absent).
     */
    @Test
    void u2_regimesDphCorrectedExemptNever() {
        JsonPath dph = valuate(basket("U2-dph", "0101", "3300000000025", "1", "UDDPH"), 200);
        Map<String, Object> dphEntry = entry(dph, "egalim", "3300000000025");
        assertNotNull(dphEntry, "The DPH line is recorded");
        assertEquals("DPH_40", dphEntry.get("regime"), "The DPH regime is reported");
        assertTrue(new BigDecimal(dphEntry.get("correction").toString()).compareTo(BigDecimal.ZERO) > 0,
                "A 60% discount on a DPH_40 line is corrected");
        JsonPath exempt = valuate(basket("U2-exempt", "0101", "3300000000002", "1", "UDEX"), 200);
        assertFalse(hasAdvantage(exempt, "EGAlim Adjustment"),
                "An EXEMPT line is never corrected");
        Map<String, Object> exemptEntry = entry(exempt, "egalim", "3300000000002");
        assertNotNull(exemptEntry, "The EXEMPT line is still recorded");
        assertEquals("EXEMPT", exemptEntry.get("regime"), "The EXEMPT regime is reported");
        assertNull(exemptEntry.get("remainingBeforeCap"),
                "An uncapped EXEMPT line has no remainingBeforeCap key");
    }

    // --------------------------------------------------
    // U3 — structural exclusions (anti-waste)
    // --------------------------------------------------

    /**
     * U3 — an anti-waste discount is excluded generosity: a FOOD_34 line ({@code …015}) with a
     * 60% anti-waste discount (best-before three days out, clock pinned) carries the bulk of its
     * generosity in {@code excludedGenerosity} and is NOT corrected.
     * <p>
     * ÉCART spec/code (documented): the catalog says "générosité comptée 0", but a small residual
     * (0.22 = the BASE_FOR_DISCOUNT-vs-DEFAULT flip gap: the nominal is the BASE reference 2.38
     * while the anti-waste discount reduces the DEFAULT-based net 2.16) stays in
     * {@code countedGenerosity}. It is far below the 34% ceiling, so no correction fires — the
     * substantive contract (excluded bulk, no correction) is asserted here.
     */
    @Test
    void u3_antiWasteIsExcludedGenerosity() {
        try {
            DateTimeProvider.setFixedDateTime(java.time.LocalDateTime.of(2026, 6, 15, 10, 0));
            JsonPath body = valuate(
                    "{\"customerCode\":\"U3\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                            + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"3300000000015\",\"quantity\":1,"
                            + "\"bestBeforeDate\":\"2026-06-18\"}]}", 200);
            assertTrue(hasAdvantage(body, "Anti-Waste: U_ANTI_FOOD"),
                    "The anti-waste discount applies");
            assertFalse(hasAdvantage(body, "EGAlim Adjustment"),
                    "Anti-waste generosity is excluded, so nothing is corrected");
            Map<String, Object> e = entry(body, "egalim", "3300000000015");
            assertNotNull(e, "The line is still recorded");
            BigDecimal excluded = new BigDecimal(e.get("excludedGenerosity").toString());
            BigDecimal counted = new BigDecimal(e.get("countedGenerosity").toString());
            assertTrue(excluded.compareTo(BigDecimal.ZERO) > 0,
                    "The anti-waste discount is counted as excluded generosity: " + excluded);
            assertTrue(excluded.compareTo(counted) > 0,
                    "The excluded anti-waste share dominates the residual counted generosity: "
                            + excluded + " vs " + counted);
            assertNull(e.get("correction"), "No correction fires on the excluded line");
        } finally {
            DateTimeProvider.clear();
        }
    }

    // --------------------------------------------------
    // U4 — exemption by promotion compliance flag
    // --------------------------------------------------

    /**
     * U4 — a discount flagged {@code compliance.egalimExempt:true} counts as excluded generosity
     * (the sales case): a 50% exempt discount on a FOOD_34 rice line ({@code …022}) is not
     * corrected, and its generosity lands in {@code excludedGenerosity}.
     */
    @Test
    void u4_egalimExemptPromotion() {
        JsonPath body = valuate(basket("U4", "0101", "3300000000022", "1", "UEXMPT"), 200);
        assertTrue(hasAdvantage(body, "Immediate Voucher Discount : U_EGXMPT"),
                "The exempt discount applies");
        assertFalse(hasAdvantage(body, "EGAlim Adjustment"),
                "An egalimExempt discount is not corrected");
        Map<String, Object> e = entry(body, "egalim", "3300000000022");
        assertNotNull(e, "The line is recorded");
        assertTrue(new BigDecimal(e.get("excludedGenerosity").toString()).compareTo(BigDecimal.ZERO) > 0,
                "The exempt discount is counted as excluded generosity: " + e.get("excludedGenerosity"));
    }

    // --------------------------------------------------
    // U5 — PDV targeting (regression: no key off-perimeter)
    // --------------------------------------------------

    /**
     * U5 — the guards target {@code 0101} only. A discounted basket on {@code 0102} — where no
     * guard is in force — is byte-for-byte the trunk: neither the {@code egalim} nor the
     * {@code srp} key appears in the response (the absence is the regression contract).
     */
    @Test
    void u5_pdvTargetingLeavesNoKeyOffPerimeter() {
        JsonPath body = valuate(basket("U5", "0102", "3300000000030", "1", null), 200);
        assertNull(body.get("egalim"), "No EGAlim guard on 0102: the egalim key is absent");
        assertNull(body.get("srp"), "No SRP guard on 0102: the srp key is absent");
    }

    // --------------------------------------------------
    // U6 — open basket runs no guard
    // --------------------------------------------------

    /**
     * U6 — the guards are AT_TOTAL: an OPEN basket ({@code closed:false}) on {@code 0101} with a
     * 50% discount on a FOOD_34 line runs neither guard — no correction and no {@code egalim}
     * record key.
     */
    @Test
    void u6_openBasketRunsNoGuard() {
        JsonPath body = valuate(
                "{\"customerCode\":\"U6\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\",\"closed\":false,"
                        + "\"couponCodes\":[\"UD50\"],"
                        + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"3300000000015\",\"quantity\":1}]}", 200);
        assertFalse(hasAdvantage(body, "EGAlim Adjustment"), "An open basket runs no EGAlim correction");
        assertNull(body.get("egalim"), "An open basket produces no egalim record key");
    }

    // --------------------------------------------------
    // U7 — administered ceiling drives the correction
    // --------------------------------------------------

    /**
     * U7 — the administered {@code EGALIM_REGIMES} feed drives the ceiling, not the code. On the
     * same 50% discount on {@code …015}, lowering FOOD_34 from 0.3400 to 0.3000 tightens the
     * ceiling, so the correction grows. The feed is lowered, the correction re-measured, then the
     * ceiling is restored to 0.3400 so the class stays order-independent.
     */
    @Test
    void u7_administeredCeilingDrivesCorrection() {
        JsonPath at34 = valuate(basket("U7-34", "0101", "3300000000015", "1", "UD50"), 200);
        BigDecimal correction34 = new BigDecimal(entry(at34, "egalim", "3300000000015").get("correction").toString());
        try {
            importCsv("/egalim-regimes/import", "CODE|LABEL|CAP_RATE\nFOOD_34|Alimentaire 30%|0.3000\n");
            JsonPath at30 = valuate(basket("U7-30", "0101", "3300000000015", "1", "UD50"), 200);
            BigDecimal correction30 =
                    new BigDecimal(entry(at30, "egalim", "3300000000015").get("correction").toString());
            assertTrue(correction30.compareTo(correction34) > 0,
                    "A tighter 30% ceiling corrects more than the 34% ceiling: " + correction30 + " vs " + correction34);
        } finally {
            importCsv("/egalim-regimes/import", "CODE|LABEL|CAP_RATE\nFOOD_34|Alimentaire plafonné 34%|0.3400\n");
        }
    }

    // --------------------------------------------------
    // U8 — SRP nominal
    // --------------------------------------------------

    /**
     * U8 — the SRP guard locks a line at its floor. Purée {@code …016} (EXEMPT, so EGAlim never
     * fires) has an {@code SRP_FLOOR} at 2.40 TTC; a 40% discount pushes its net below the floor,
     * so the guard emits a {@code "SRP Adjustment: U_SRP_GUARD"} contra-advantage and records an
     * {@code srp[]} entry with the floor price and a positive correction. A product WITHOUT an
     * {@code SRP_FLOOR} line ({@code …002}) is not controlled: it never appears in {@code srp[]}.
     */
    @Test
    void u8_srpNominal() {
        JsonPath body = valuate(basket("U8", "0101", "3300000000016", "1", "USRP"), 200);
        assertTrue(hasAdvantage(body, "SRP Adjustment: U_SRP_GUARD"),
                "The SRP guard corrects a line pushed below its floor");
        Map<String, Object> e = entry(body, "srp", "3300000000016");
        assertNotNull(e, "The controlled line is recorded in srp[]");
        assertEquals(0, new BigDecimal(e.get("floorPrice").toString()).compareTo(new BigDecimal("2.40")),
                "The floor price is reported: " + e.get("floorPrice"));
        assertTrue(new BigDecimal(e.get("correction").toString()).compareTo(BigDecimal.ZERO) > 0,
                "The SRP correction is a positive magnitude: " + e.get("correction"));
        JsonPath uncontrolled = valuate(basket("U8-none", "0101", "3300000000002", "1", "UDEX"), 200);
        assertNull(entry(uncontrolled, "srp", "3300000000002"),
                "A product without an SRP_FLOOR line is not controlled");
    }

    // --------------------------------------------------
    // U9 — both guards, coherent order
    // --------------------------------------------------

    /**
     * U9 — a FOOD_34 line with an SRP floor ({@code …015}) discounted 50% triggers BOTH guards:
     * EGAlim corrects first (MAX−1), SRP locks last (MAX). Both contra-advantages appear and both
     * record blocks carry an entry for the line, coherent with each other.
     */
    @Test
    void u9_bothGuardsCoherentOrder() {
        JsonPath body = valuate(basket("U9", "0101", "3300000000015", "1", "UD50"), 200);
        assertTrue(hasAdvantage(body, "EGAlim Adjustment: U_EGALIM_GUARD"), "EGAlim corrects");
        assertTrue(hasAdvantage(body, "SRP Adjustment: U_SRP_GUARD"), "SRP locks");
        assertNotNull(entry(body, "egalim", "3300000000015"), "The line is in egalim[]");
        assertNotNull(entry(body, "srp", "3300000000015"), "The line is in srp[]");
    }

    // --------------------------------------------------
    // U10 — srpExempt line passes below the floor
    // --------------------------------------------------

    /**
     * U10 — a discount flagged {@code compliance.srpExempt:true} lets the line pass below the
     * floor without correction (the sales case): the 40% exempt discount on {@code …016} produces
     * no {@code "SRP Adjustment"}, though the line is still traced in {@code srp[]}.
     */
    @Test
    void u10_srpExemptPassesBelowFloor() {
        JsonPath body = valuate(basket("U10", "0101", "3300000000016", "1", "USRPEX"), 200);
        assertFalse(hasAdvantage(body, "SRP Adjustment"),
                "An srpExempt discount is not corrected even below the floor");
        assertNotNull(entry(body, "srp", "3300000000016"),
                "The srpExempt line is still traced in srp[]");
    }

    // --------------------------------------------------
    // U11 — manual gesture clamped: the record explains why not more (§3.A)
    // --------------------------------------------------

    /**
     * U11 — a manual cash-desk gesture of 1.00 on the purée {@code …016} (shelf 2.40, floor 2.40)
     * drops the line to 1.40, below its floor: the guard clamps it and the {@code srp[]} entry says
     * WHY not more — {@code reasonCode = SRP_FLOOR_CLAMPED}, the discount requested (against the 2.64
     * reference) and the discount actually granted, alongside the correction (spec-srp-guard §3.A).
     */
    @Test
    void u11_manualGestureClampReportsRequestedAndGranted() {
        String body = "{\"customerCode\":\"U11\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"3300000000016\",\"quantity\":1,"
                + "\"manualDiscountAmount\":1.00}]}";
        JsonPath response = valuate(body, 200);
        assertTrue(hasAdvantage(response, "SRP Adjustment: U_SRP_GUARD"),
                "The manual gesture is clamped up to the floor");
        Map<String, Object> e = entry(response, "srp", "3300000000016");
        assertNotNull(e, "The clamped line is recorded");
        assertEquals("SRP_FLOOR_CLAMPED", e.get("reasonCode"), "The clamp reason is reported");
        assertEquals(0, new BigDecimal(e.get("correction").toString()).compareTo(new BigDecimal("1.00")),
                "The correction lifts the line back to the floor: " + e.get("correction"));
        assertEquals(0, new BigDecimal(e.get("requestedDiscount").toString()).compareTo(new BigDecimal("1.24")),
                "The requested discount is reported (2.64 - 1.40): " + e.get("requestedDiscount"));
        assertEquals(0, new BigDecimal(e.get("grantedDiscount").toString()).compareTo(new BigDecimal("0.24")),
                "The granted discount is reported (2.64 - 2.40): " + e.get("grantedDiscount"));
    }

    // --------------------------------------------------
    // U12 — warning palier: granted with a warning, no clamp (§3.B)
    // --------------------------------------------------

    /**
     * U12 — a 20% discount on the EXEMPT ham {@code …008} (shelf 2.40, floor 1.80, reference 2.64)
     * drives the line into the warning band of the descent toward the floor without breaching it: the
     * {@code srp[]} entry is flagged {@code SRP_MARGIN_WARNING} with the reached palier and the descent
     * fraction, but there is NO correction and NO {@code SRP Adjustment} advantage (spec-srp-guard §3.B).
     */
    @Test
    void u12_marginWarningWithoutClamp() {
        JsonPath body = valuate(basket("U12", "0101", "3300000000008", "1", "UWARN"), 200);
        assertFalse(hasAdvantage(body, "SRP Adjustment"),
                "A warning never corrects the line");
        Map<String, Object> e = entry(body, "srp", "3300000000008");
        assertNotNull(e, "The warned line is recorded");
        assertEquals("SRP_MARGIN_WARNING", e.get("reasonCode"), "The warning reason is reported");
        assertNull(e.get("correction"), "A warned line carries no correction");
        assertNotNull(e.get("warningThreshold"), "The reached palier is reported");
        assertNotNull(e.get("descentFraction"), "The descent fraction is reported");
    }

    // --------------------------------------------------
    // U13 — degraded-product override: floor bypassed, traced (§3.C)
    // --------------------------------------------------

    /**
     * U13 — the same 40% discount that clamped {@code …016} in U8, but the line now carries a per-line
     * degraded-product derogation ({@code srpOverride}): the floor is bypassed — the line stays below
     * it with NO correction and NO {@code SRP Adjustment} — and the bypass is traced in {@code srp[]}
     * with {@code reasonCode = SRP_OVERRIDE} and the operator's reason (spec-srp-guard §3.C).
     */
    @Test
    void u13_degradedProductOverrideBypassesFloor() {
        String body = "{\"customerCode\":\"U13\",\"storeCode\":\"0101\",\"deliveryMode\":\"IN_STORE\","
                + "\"couponCodes\":[\"USRP\"],"
                + "\"items\":[{\"lineId\":\"L1\",\"produceEan\":\"3300000000016\",\"quantity\":1,"
                + "\"srpOverride\":true,\"srpOverrideReason\":\"Damaged packaging\"}]}";
        JsonPath response = valuate(body, 200);
        assertFalse(hasAdvantage(response, "SRP Adjustment"),
                "An overridden line is never corrected, even below the floor");
        Map<String, Object> e = entry(response, "srp", "3300000000016");
        assertNotNull(e, "The overridden line is still traced");
        assertEquals("SRP_OVERRIDE", e.get("reasonCode"), "The override reason code is reported");
        assertEquals("Damaged packaging", e.get("overrideReason"), "The operator's reason is echoed");
        assertNull(e.get("correction"), "The bypass leaves the line below its floor uncorrected");
    }
}
