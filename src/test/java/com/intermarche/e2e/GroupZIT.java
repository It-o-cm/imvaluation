package com.intermarche.e2e;

import com.intermarche.valuation.domain.ConsumptionRecord;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group Z — the digital sheet (spec-digital-ecommerce.md, normative cases 1, 3, 4, 5, 7, 8, 10),
 * in pure RestAssured, HTTP Basic as {@code admin/admin}.
 * <p>
 * The engine keeps no basket: the scenarios play the selling front, which values each state of
 * its basket on {@code /valuation}, declares the sale on {@code /consumptions}, keeps the closing
 * request and answer as the snapshot, and hands it back to {@code /returns/valuation}. The group
 * letter is outside e2e-scenarios.md on purpose: the class seeds the shared referential, so it
 * must run after {@code GroupAIT}, whose first import pass expects an empty one.
 * <p>
 * The class replays the referential slice of the mirror catalog once, then seeds its own offer
 * {@code DG_WEB10} (10% off the ticket from €20 with the coupon WEB10), two families of its own
 * ({@code DG_CAFE} under {@code DG_RAYON}) and a 2025 historical price of the milk. The reference
 * order is milk x6 + baguette x4 with the coupon: 22.48 before discount, 20.23 paid.
 */
@QuarkusTest
class GroupZIT {

    /**
     * The referential slice replayed before this class's own data, in the mandated order.
     */
    private static final String[][] SEED = {
            {"/stores/import", "seed/01-stores.csv"},
            {"/vat-rates/import", "seed/00-vat-rates.csv"},
            {"/products/import", "seed/03-products.csv"},
            {"/prices/import", "seed/06-prices.csv"},
    };

    /**
     * The class's own offer: 10% off the ticket from €20 with the coupon WEB10.
     */
    private static final String OFFERS_CSV = "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "DG_WEB10|TICKET_DISCOUNT|{\"discountType\":\"PERCENTAGE\",\"value\":10,\"trigger\":{\"conditions\":["
            + "{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":20},"
            + "{\"kind\":\"COUPON_CODE\",\"code\":\"WEB10\"}]}}|0101|\n";

    /**
     * The class's own families: the coffee in DG_CAFE, itself under DG_RAYON.
     */
    private static final String FAMILIES_CSV = "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n"
            + "DG_CAFE|Cafés||3300000000004|\n"
            + "DG_RAYON|Rayon petit déjeuner|||DG_CAFE\n";

    /**
     * A 2025 price of the milk, for the price-at-date return.
     */
    private static final String HISTORICAL_PRICE_CSV =
            "EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n"
            + "3300000000002|0101|0.80|0.96|0.2000|DEFAULT|0|2025-01-01T00:00:00|2026-01-11T23:59:59\n";

    /**
     * The reference order as the selling front sends it to {@code /valuation} at close.
     */
    private static final String ORDER_BASKET = "{\"storeCode\":\"0101\",\"couponCodes\":[\"WEB10\"],\"closed\":true,\"items\":["
            + "{\"lineId\":\"1\",\"produceEan\":\"3300000000002\",\"quantity\":6},"
            + "{\"lineId\":\"2\",\"produceEan\":\"3300000000003\",\"quantity\":4}]}";

    /**
     * The content type of the CSV imports: UTF-8 declared, so the accented labels survive.
     */
    private static final String CSV = "text/plain; charset=UTF-8";

    /**
     * The content type of the JSON calls.
     */
    private static final String JSON = "application/json; charset=UTF-8";

    /**
     * Whether the catalog was seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Seeds the referential slice and the class's own data once.
     */
    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        CatalogReset.resetMutableCatalog();
        for (String[] step : SEED) {
            post(step[0], CSV, readResource(step[1]), 200);
        }
        post("/prices/import", CSV, HISTORICAL_PRICE_CSV, 200);
        post("/product-families/import", CSV, FAMILIES_CSV, 200);
        post("/offers/import", CSV, OFFERS_CSV, 200);
        seeded = true;
    }

    /**
     * Reads a classpath resource into a UTF-8 string.
     *
     * @param path the classpath-relative resource path.
     * @return the resource content.
     */
    private static String readResource(String path) {
        try (var in = GroupZIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing seed resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read seed resource: " + path, e);
        }
    }

    /**
     * Posts a body as {@code admin/admin}, asserts the status and returns the raw answer.
     *
     * @param path   the path.
     * @param type   the content type, charset included.
     * @param body   the body.
     * @param status the expected status.
     * @return the raw answer body.
     */
    private static String post(String path, String type, String body, int status) {
        return given().auth().preemptive().basic("admin", "admin").contentType(type).body(body)
                .when().post(path).then().statusCode(status).extract().asString();
    }

    /**
     * Posts a return request and parses the answer.
     *
     * @param body   the return request.
     * @param status the expected status.
     * @return the parsed answer.
     */
    private static JsonPath returns(String body, int status) {
        return new JsonPath(post("/returns/valuation", JSON, body, status));
    }

    /**
     * Values the reference order and builds its closing snapshot, as the selling front keeps it.
     *
     * @return the {@code snapshot} member of a return request.
     */
    private static String snapshot() {
        String answer = post("/valuation", JSON, ORDER_BASKET, 200);
        return "{\"request\":" + ORDER_BASKET + ",\"evaluation\":" + answer + "}";
    }

    /**
     * Asserts a decimal of a JSON answer by value, whatever its scale on the wire.
     *
     * @param expected the expected value.
     * @param body     the answer.
     * @param path     the JSON path.
     */
    private static void assertDecimal(String expected, JsonPath body, String path) {
        BigDecimal actual = new BigDecimal(body.getString(path));
        assertEquals(0, new BigDecimal(expected).compareTo(actual), path + " = " + actual);
    }

    /**
     * Counts the rows of an entity, under a transaction.
     *
     * @param entity the entity name.
     * @return the number of rows.
     */
    private static long rows(String entity) {
        return QuarkusTransaction.requiringNew().call(() ->
                (Long) Panache.getEntityManager().createQuery("select count(e) from " + entity + " e").getSingleResult());
    }

    /**
     * Counts the rows of every entity but the consumption truth and the exchange log, under a
     * transaction.
     *
     * @return the number of rows per entity.
     */
    private static Map<String, Long> otherRows() {
        List<String> names = QuarkusTransaction.requiringNew().call(() -> Panache.getEntityManager().getMetamodel()
                .getEntities().stream().map(EntityType::getName).sorted().collect(Collectors.toList()));
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (String name : names) {
            if (!List.of("ConsumptionRecord", "ConsumptionCounter", "ValuationTrace").contains(name)) {
                counts.put(name, rows(name));
            }
        }
        return counts;
    }

    /**
     * Normative case 1 (DG-01-01-01 to -06): the front opens, modifies, holds, resumes and closes
     * its basket on /valuation, then declares the SALE once; the five answers share one structure,
     * the replayed declaration is a duplicate, and nothing but the consumption truth (and the
     * existing exchange log) is written.
     */
    @Test
    void dg0101_cycle_isStateless() {
        Map<String, Long> before = otherRows();
        String line1 = "{\"lineId\":\"1\",\"produceEan\":\"3300000000002\",\"quantity\":6}";
        String line2 = "{\"lineId\":\"2\",\"produceEan\":\"3300000000003\",\"quantity\":4}";
        String[] states = {
                "{\"storeCode\":\"0101\",\"closed\":false,\"items\":[" + line1 + "]}",
                "{\"storeCode\":\"0101\",\"closed\":false,\"couponCodes\":[\"WEB10\"],\"items\":[" + line1 + "," + line2 + "]}",
                "{\"storeCode\":\"0101\",\"closed\":false,\"couponCodes\":[\"WEB10\"],\"items\":[" + line1 + "," + line2 + "]}",
                "{\"storeCode\":\"0101\",\"couponCodes\":[\"WEB10\"],\"items\":[" + line1 + "," + line2 + "]}",
                ORDER_BASKET};
        java.util.Set<String> structure = null;
        for (String state : states) {
            JsonPath answer = new JsonPath(post("/valuation", JSON, state, 200));
            java.util.Set<String> keys = new java.util.TreeSet<>(answer.<String, Object>getMap("$").keySet());
            structure = structure == null ? keys : structure;
            assertEquals(structure, keys);
        }
        String sale = "{\"ticketId\":\"DG-Z-SALE\",\"storeCode\":\"0101\",\"direction\":\"SALE\","
                + "\"advantages\":[{\"offerCode\":\"DG_WEB10\",\"amount\":2.25}]}";
        assertFalse(new JsonPath(post("/consumptions", JSON, sale, 200)).getBoolean("duplicate"));
        assertTrue(new JsonPath(post("/consumptions", JSON, sale, 200)).getBoolean("duplicate"));
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() ->
                ConsumptionRecord.count("ticketId = ?1 and direction = ?2", "DG-Z-SALE", "SALE")));
        assertEquals(before, otherRows());
    }

    /**
     * DG-01-01-01, normative case 4: every valued line carries the product label and its family
     * of attachment (code and label); an article unknown to the referential gets null fields — a
     * line without EAN, and an EAN absent from the referential valued on its pushed price.
     */
    @Test
    void dg0101_lines_carryLabelAndFamily() {
        JsonPath answer = new JsonPath(post("/valuation", JSON, "{\"storeCode\":\"0101\",\"items\":["
                + "{\"lineId\":\"1\",\"produceEan\":\"3300000000004\",\"quantity\":1},"
                + "{\"lineId\":\"2\",\"quantity\":1,\"pricePerUnitExclTax\":1.50,\"pricePerUnitInclTax\":1.80,\"vatRate\":0.20},"
                + "{\"lineId\":\"3\",\"produceEan\":\"3300000000099\",\"quantity\":2,"
                + "\"pricePerUnitExclTax\":1.50,\"pricePerUnitInclTax\":1.80,\"vatRate\":0.20}]}", 200));
        List<Map<String, Object>> items = answer.getList("offers.items.flatten()");
        Map<String, Object> coffee = items.stream().filter(i -> "1".equals(i.get("lineId"))).findFirst().orElseThrow();
        assertEquals("Café Grains 500g", coffee.get("label"));
        assertEquals("DG_CAFE", coffee.get("familyCode"));
        assertEquals("Cafés", coffee.get("familyLabel"));
        Map<String, Object> unknown = items.stream().filter(i -> "2".equals(i.get("lineId"))).findFirst().orElseThrow();
        assertTrue(unknown.containsKey("familyCode") && unknown.get("familyCode") == null, "unknown: " + unknown);
        assertTrue(unknown.containsKey("label") && unknown.get("label") == null, "unknown: " + unknown);
        Map<String, Object> absent = items.stream().filter(i -> "3".equals(i.get("lineId"))).findFirst().orElseThrow();
        assertEquals("3300000000099", absent.get("produceEan"));
        assertTrue(absent.containsKey("label") && absent.get("label") == null, "absent: " + absent);
        assertTrue(absent.containsKey("familyCode") && absent.get("familyCode") == null, "absent: " + absent);
        assertEquals(0, new BigDecimal("3.60").compareTo(
                new BigDecimal(String.valueOf(((Map<?, ?>) absent.get("amount")).get("amountIncludingTax")))), "absent: " + absent);
    }

    /**
     * DG-01-02-03, normative case 3: a pushed price with only part of its fields is refused with
     * a 400 whose body names each missing field and the line.
     */
    @Test
    void dg0102_partialPushedPrice_is400() {
        String error = new JsonPath(post("/valuation", JSON, "{\"storeCode\":\"0101\",\"items\":[{\"produceEan\":"
                + "\"3300000000002\",\"quantity\":1,\"pricePerUnitInclTax\":1.80}]}", 400)).getString("error");
        assertTrue(error.contains("items[0]") && error.contains("pricePerUnitExclTax") && error.contains("vatRate"), error);
    }

    /**
     * DG-01-03-01, normative case 5: a FULL return refunds the snapshot to the cent and takes back
     * every advantage; nothing is recorded by imvaluation.
     */
    @Test
    void dg0103_fullReturn_refundsSnapshot() {
        String snapshot = snapshot();
        long consumptions = rows("ConsumptionRecord");
        JsonPath full = returns("{\"mode\":\"FULL\",\"orderReference\":\"DG-ORDER\",\"snapshot\":" + snapshot + "}", 200);
        assertDecimal("20.23", full, "totalRefund");
        assertDecimal("16.41", full, "lines[0].amount");
        assertDecimal("3.04", full, "lines[0].referencePrice");
        assertDecimal("3.82", full, "lines[1].amount");
        assertEquals("DG_WEB10", full.getString("advantagesTakenBack[0].offerCode"));
        assertDecimal("2.25", full, "advantagesTakenBack[0].amount");
        assertDecimal("20.23", full, "vatBreakdown[0].amountIncludingTax");
        assertEquals(consumptions, rows("ConsumptionRecord"));
    }

    /**
     * DG-01-03-03, normative case 7: a PARTIAL return that drops the kept basket under the
     * discount threshold takes the lost generosity back and lists the lost offer; the next return,
     * told what the first took (extension), gives that generosity back, so the refunds sum to what
     * was paid.
     */
    @Test
    void dg0103_partialReturn_takesBackLostDiscount() {
        String snapshot = snapshot();
        JsonPath first = returns("{\"mode\":\"PARTIAL\",\"snapshot\":" + snapshot + ",\"lines\":[{\"lineId\":\"1\","
                + "\"produceEan\":\"3300000000002\",\"quantity\":2,\"priceSource\":\"ORDER\"}]}", 200);
        assertDecimal("5.35", first, "lines[0].amount");
        assertDecimal("5.35", first, "totalRefund");
        assertEquals("DG_WEB10", first.getString("advantagesTakenBack[0].offerCode"));
        JsonPath rest = returns("{\"mode\":\"PARTIAL\",\"snapshot\":" + snapshot + ",\"previousReturns\":{\"returned\":{\"1\":2},"
                + "\"withdrawnGenerosity\":" + first.getString("withdrawnGenerosity") + "},"
                + "\"lines\":[{\"lineId\":\"1\",\"quantity\":4},{\"lineId\":\"2\",\"quantity\":4}]}", 200);
        assertDecimal("14.88", rest, "totalRefund");
        assertTrue(rest.getList("advantagesTakenBack").isEmpty());
        returns("{\"mode\":\"PARTIAL\",\"snapshot\":" + snapshot + ",\"lines\":[{\"lineId\":\"1\",\"quantity\":7}]}", 400);
    }

    /**
     * DG-01-03-04/-06/-07/-08, normative case 8: one line per price source — the current
     * referential price (2.76), the price paid on the order, the price at a date (0.96 on
     * 15/06/2025) and the pushed price (1.80); an ORDER price without snapshot is refused.
     */
    @Test
    void dg0103_lines_onEveryPriceSource() {
        JsonPath lines = returns("{\"mode\":\"LINES\",\"storeCode\":\"0101\",\"snapshot\":" + snapshot() + ","
                + "\"lines\":[{\"produceEan\":\"3300000000002\",\"quantity\":1,\"priceSource\":\"CURRENT\"},"
                + "{\"lineId\":\"1\",\"quantity\":1,\"priceSource\":\"ORDER\"},"
                + "{\"produceEan\":\"3300000000002\",\"quantity\":1,\"priceSource\":\"DATE\",\"priceDate\":\"2025-06-15\"},"
                + "{\"produceEan\":\"3300000000002\",\"quantity\":1,\"priceSource\":\"PUSHED\","
                + "\"pricePerUnitExclTax\":1.50,\"pricePerUnitInclTax\":1.80,\"vatRate\":0.20}]}", 200);
        assertDecimal("2.76", lines, "lines[0].amount");
        assertDecimal("2.74", lines, "lines[1].amount");
        assertDecimal("0.96", lines, "lines[2].amount");
        assertDecimal("1.80", lines, "lines[3].amount");
        assertEquals(List.of("CURRENT", "ORDER", "DATE", "PUSHED"), lines.getList("lines.priceSource"));
        assertEquals("An ORDER price requires the snapshot.", returns("{\"mode\":\"LINES\",\"storeCode\":\"0101\","
                + "\"lines\":[{\"lineId\":\"1\",\"quantity\":1,\"priceSource\":\"ORDER\"}]}", 400).getString("error"));
        assertTrue(returns("{\"mode\":\"LINES\",\"storeCode\":\"0101\",\"lines\":[{\"produceEan\":\"9999\","
                + "\"quantity\":1,\"priceSource\":\"CURRENT\"}]}", 422).getString("error").contains("9999"));
    }

    /**
     * Normative case 10, over HTTP: no entity of the persistence unit models a basket, an order or
     * a return, and there is no {@code /baskets} route.
     */
    @Test
    void dg01_noBasketIsStored() {
        List<String> entities = QuarkusTransaction.requiringNew().call(() -> Panache.getEntityManager().getMetamodel()
                .getEntities().stream().map(EntityType::getName).sorted().collect(Collectors.toList()));
        assertFalse(entities.stream().anyMatch(name -> name.contains("Basket") || name.contains("Order")
                || name.contains("Return")), "entities: " + entities);
        given().auth().preemptive().basic("admin", "admin").contentType(JSON).body("{\"storeCode\":\"0101\",\"items\":[]}")
                .when().post("/baskets").then().statusCode(404);
    }
}
