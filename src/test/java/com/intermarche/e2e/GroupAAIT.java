package com.intermarche.e2e;

import com.intermarche.valuation.domain.ConsumptionRecord;
import com.intermarche.valuation.engine.DisplayValuationEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group AA — display valuation e-commerce (batch, read-only) — of e2e-scenarios.md, in pure
 * RestAssured against {@code POST /display-valuation} (spec-valorisation-affichage-ecommerce).
 * <p>
 * The endpoint is a very special multiple basket: n one-unit mini-baskets, each on its own
 * pseudo-ticket, valued against a context built ONCE per call; strictly read-only, the engine never
 * resolves a card (promotions are PUSHED by the caller). Every scenario asserts the display contract
 * and, for AA2/AA3, the to-the-cent coherence with a mono-line {@code /valuation} (rule 6).
 * <p>
 * Isolation: all scenarios value their OWN dedicated AA EANs on the mirror store {@code 0101}, which
 * carries no ticket-level or guard offer; the store-wide EGAlim/SRP guards of AA6 live on a
 * dedicated store {@code 0199} so they never perturb the other products. The class seeds its slice
 * once after {@link CatalogReset#resetMutableCatalog()} so the suite stays order-independent.
 */
@QuarkusTest
class GroupAAIT {

    /**
     * The valuation date every call uses; all AA prices and offers are in force at it.
     */
    private static final String DATE = "2026-06-01";

    /**
     * The extra stores: a dedicated guard store so AA6's store-wide guards never touch 0101.
     */
    private static final String STORES_CSV =
            "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n"
            + "0199|AA Guard Store|1 Rue AA||59000|Lille|France|50.63|3.06\n";

    /**
     * The AA-specific products: dedicated EANs, one group per scenario, plus the three measure
     * variants of AA7 (weight only, volume only, no reference).
     */
    private static final String PRODUCTS_CSV =
            "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE|EGALIM_REGIME\n"
            + "3309999999902|AA Plain|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999903|AA Mark|x|B|0.250|0.600|UNIT|kg|true|\n"
            + "3309999999914|AA Mark2|x|B|0.250|0.600|UNIT|kg|true|\n"
            + "3309999999904|AA Card|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999905|AA NplusM|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999916|AA MinQty|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999919|AA Tiered|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999920|AA Bundle|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999921|AA BundlePartner|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999906|AA Ticket|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999918|AA AntiWaste|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999901|AA Egalim|x|B|1.000|0.000|UNIT|kg|true|FOOD_34\n"
            + "3309999999909|AA Srp|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999908|AA Weight|x|B|0.250||UNIT|kg|true|\n"
            + "3309999999907|AA Volume|x|B||1.500|UNIT|L|true|\n"
            + "3309999999900|AA NoRef|x|B|||UNIT||true|\n"
            + "3309999999910|AA Iso1|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999911|AA Iso2|x|B|1.000|1.000|UNIT|L|true|\n"
            + "3309999999912|AA ReadOnly|x|B|1.000|1.000|UNIT|L|true|\n";

    /**
     * The AA-specific prices: clean single-priority values so the arithmetic is exact to the cent.
     * The guard products carry their reference/floor rows on the dedicated store 0199.
     */
    private static final String PRICES_CSV =
            "EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n"
            + "3309999999902|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999903|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999903|0101|2.50|3.00|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999914|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999914|0101|2.50|3.00|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999904|0101|5.00|6.00|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999904|0101|5.00|6.00|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999905|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999916|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999919|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999920|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999906|0199|4.00|4.80|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999918|0199|4.00|4.80|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999908|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999907|0101|1.25|1.50|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999900|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999921|0101|3.00|3.60|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999910|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999910|0101|2.00|2.40|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999911|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999911|0101|2.00|2.40|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999912|0101|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999901|0199|1.00|1.20|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999901|0199|1.00|1.20|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999909|0199|2.00|2.40|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n"
            + "3309999999909|0199|2.00|2.40|0.2000|BASE_FOR_DISCOUNT|0|2026-01-01T00:00:00|\n"
            + "3309999999909|0199|1.50|1.80|0.2000|SRP_FLOOR|0|2026-01-01T00:00:00|\n";

    /**
     * The AA-specific offers: product markdowns on 0101, the basket-dependent badge offers, the
     * excluded ticket/anti-waste offers, and the two guards (plus their markdowns) on 0199.
     */
    private static final String OFFERS_CSV =
            "CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n"
            + "AA_NEWPRICE|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999903\"],\"newPrice\":2.00}]}|0101|\n"
            + "AA_AMOUNT|AMOUNT_PER_ITEM_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999914\"],\"amountPerItem\":0.50}]}|0101|\n"
            + "AA_NPLUSM|N+M|{\"targetEans\":[\"3309999999905\"],\"quantityToPay\":2,\"discountedQuantity\":1,\"selectionStrategy\":\"CHEAPEST\",\"discountType\":\"PERCENTAGE\",\"discountValue\":100.0}|0101|\n"
            + "AA_MINQTY|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999916\"],\"newPrice\":1.00}],\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_QUANTITY\",\"eans\":[\"3309999999916\"],\"threshold\":3}]}}|0101|\n"
            + "AA_TIERED|TIERED_DISCOUNT|{\"scope\":\"ITEMS\",\"metric\":\"QUANTITY\",\"mode\":\"HIGHEST_REACHED\",\"targetEans\":[\"3309999999919\"],\"tiers\":[{\"threshold\":3,\"award\":{\"type\":\"PERCENTAGE\",\"value\":20}}]}|0101|\n"
            + "AA_BUNDLE|MIXED_BUNDLE|{\"vatRate\":0.20,\"bundlePrice\":5.00,\"contents\":[{\"ean\":\"3309999999920\",\"quantity\":1.0},{\"ean\":\"3309999999921\",\"quantity\":1.0}]}|0101|\n"
            + "AA_TICKET|TICKET_DISCOUNT|{\"discountType\":\"PERCENTAGE\",\"value\":10.0}|0199|\n"
            + "AA_ANTIWASTE|ANTI_WASTE_DISCOUNT|{\"targetEans\":[\"3309999999918\"],\"tiers\":[{\"maxRemainingDays\":3,\"percent\":50.0}]}|0199|\n"
            + "AA_ISO|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999910\",\"3309999999911\"],\"newPrice\":1.50}]}|0101|\n"
            + "AA_RO|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999912\"],\"newPrice\":1.50}]}|0101|\n"
            + "AA_EGALIMGUARD|EGALIM_GUARD|{}|0199|\n"
            + "AA_EGALIMMARK|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999901\"],\"newPrice\":0.50}]}|0199|\n"
            + "AA_SRPGUARD|SRP_GUARD|{}|0199|\n"
            + "AA_SRPMARK|NEW_PRICE_DISCOUNT|{\"targets\":[{\"eans\":[\"3309999999909\"],\"newPrice\":1.00}]}|0199|\n";

    /**
     * Whether the AA slice has been seeded in this class run.
     */
    private static boolean seeded;

    /**
     * Replays the mirror slice (vat, stores, products) and the AA-specific prices/offers once.
     */
    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        CatalogReset.resetMutableCatalog();
        importCsv("/vat-rates/import", readResource("seed/00-vat-rates.csv"));
        importCsv("/stores/import", readResource("seed/01-stores.csv"));
        importCsv("/store-groups/import", readResource("seed/02-store-groups.csv"));
        importCsv("/products/import", readResource("seed/03-products.csv"));
        importCsv("/stores/import", STORES_CSV);
        importCsv("/products/import", PRODUCTS_CSV);
        importCsv("/prices/import", PRICES_CSV);
        importCsv("/offers/import", OFFERS_CSV);
        seeded = true;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Reads a classpath resource into a UTF-8 string.
     *
     * @param path the classpath-relative resource path.
     * @return the resource content.
     */
    private static String readResource(String path) {
        try (var in = GroupAAIT.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing seed resource: " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
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
     * Builds a {@code display-valuation} request body from a store, date and EANs, with an optional
     * raw JSON fragment (card number, promotions) spliced in.
     *
     * @param storeCode the store code.
     * @param eans      the EANs to value.
     * @param extraJson a raw JSON fragment appended inside the object (e.g. card data), or null.
     * @return the request body JSON.
     */
    private static String body(String storeCode, List<String> eans, String extraJson) {
        String eansJson = eans.stream().map(e -> "\"" + e + "\"").collect(Collectors.joining(","));
        StringBuilder sb = new StringBuilder("{\"storeCode\":\"").append(storeCode)
                .append("\",\"valuationDate\":\"").append(DATE).append("\",\"eans\":[").append(eansJson).append("]");
        if (extraJson != null) {
            sb.append(",").append(extraJson);
        }
        return sb.append("}").toString();
    }

    /**
     * Posts a display-valuation request and returns the raw response.
     *
     * @param requestBody the request JSON.
     * @return the RestAssured response.
     */
    private Response post(String requestBody) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(requestBody)
                .when().post("/display-valuation");
    }

    /**
     * Posts a display-valuation request, asserts a 200, and deserializes the entries so money fields
     * are compared as exact {@link BigDecimal}s.
     *
     * @param storeCode the store code.
     * @param eans      the EANs to value.
     * @param extraJson an optional raw JSON fragment (card data), or null.
     * @return the valued entries, in request order.
     */
    private DisplayValuationEntry[] display(String storeCode, List<String> eans, String extraJson) {
        return post(body(storeCode, eans, extraJson)).then().statusCode(200)
                .extract().as(DisplayValuationEntry[].class);
    }

    /**
     * Finds the entry of an EAN in a batch response.
     *
     * @param entries the response entries.
     * @param ean     the EAN to find.
     * @return the entry.
     */
    private static DisplayValuationEntry entryOf(DisplayValuationEntry[] entries, String ean) {
        for (DisplayValuationEntry entry : entries) {
            if (ean.equals(entry.ean)) {
                return entry;
            }
        }
        throw new AssertionError("No entry for EAN " + ean);
    }

    /**
     * Returns the tax-included net total a mono-line {@code /valuation} applies to one unit of an
     * EAN in a store at the date, with an optional card fragment — the oracle of the coherence rule.
     *
     * @param storeCode the store code.
     * @param ean       the EAN.
     * @param cardJson  an optional {@code "cardNumber"/"cardPromotions"} fragment, or null.
     * @return the net total, tax included.
     */
    private BigDecimal valuationNetTtc(String storeCode, String ean, String cardJson) {
        StringBuilder sb = new StringBuilder("{\"storeCode\":\"").append(storeCode)
                .append("\",\"closed\":true,\"items\":[{\"produceEan\":\"").append(ean)
                .append("\",\"quantity\":1,\"priceDate\":\"").append(DATE).append("T00:00:00\"}]");
        if (cardJson != null) {
            sb.append(",").append(cardJson);
        }
        sb.append("}");
        String json = given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(sb.toString())
                .when().post("/valuation").then().statusCode(200).extract().asString();
        return new BigDecimal(new JsonPath(json).getString("totalPrice.amountIncludingTax"));
    }

    /**
     * Asserts two amounts are equal to the cent.
     *
     * @param expected the expected amount (e.g. "2.40").
     * @param actual   the actual amount.
     * @param message  the assertion message.
     */
    private static void assertCents(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message + " (null)");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + " expected " + expected + " got " + actual);
    }

    // --------------------------------------------------
    // AA1 — bare public price
    // --------------------------------------------------

    /**
     * AA1 — a product with no offer: {@code displayedPrice} equals {@code basePrice}, no
     * {@code immediateDiscount}, empty {@code offerBadges}, and a {@code vatRate} read from the
     * resolved price line (not a pushed value).
     */
    @Test
    void aa1_barePublicPrice() {
        DisplayValuationEntry e = entryOf(display("0101", List.of("3309999999902"), null), "3309999999902");
        assertCents("2.40", e.displayedPrice.amountIncludingTax, "AA1 displayed");
        assertCents("2.40", e.basePrice.amountIncludingTax, "AA1 base");
        assertNull(e.immediateDiscount, "AA1 no immediate discount");
        assertTrue(e.offerBadges.isEmpty(), "AA1 no badges");
        assertCents("0.2000", e.vatRate, "AA1 vat from the resolved price line");
        assertNotNull(e.priceVersion, "AA1 price version present");
    }

    // --------------------------------------------------
    // AA2 — unconditional markdown & basket coherence
    // --------------------------------------------------

    /**
     * AA2 — a {@code NEW_PRICE_DISCOUNT} then an {@code AMOUNT_PER_ITEM_DISCOUNT} without trigger are
     * reflected in {@code displayedPrice}, compared TO THE CENT with the net of a mono-line
     * {@code /valuation} of the same product, same store and date (rule 6).
     */
    @Test
    void aa2_unconditionalMarkdownCoherence() {
        DisplayValuationEntry newPrice = entryOf(display("0101", List.of("3309999999903"), null), "3309999999903");
        assertCents("2.00", newPrice.displayedPrice.amountIncludingTax, "AA2 new price displayed");
        assertCents("3.00", newPrice.basePrice.amountIncludingTax, "AA2 new price base (reference)");
        assertCents("1.00", newPrice.immediateDiscount.amountIncludingTax, "AA2 new price discount");
        assertEquals(0, valuationNetTtc("0101", "3309999999903", null)
                .compareTo(newPrice.displayedPrice.amountIncludingTax), "AA2 new price coherent with /valuation");
        DisplayValuationEntry amount = entryOf(display("0101", List.of("3309999999914"), null), "3309999999914");
        assertCents("2.50", amount.displayedPrice.amountIncludingTax, "AA2 amount-per-item displayed");
        assertCents("3.00", amount.basePrice.amountIncludingTax, "AA2 amount-per-item base");
        assertCents("0.50", amount.immediateDiscount.amountIncludingTax, "AA2 amount-per-item discount");
        assertEquals(0, valuationNetTtc("0101", "3309999999914", null)
                .compareTo(amount.displayedPrice.amountIncludingTax), "AA2 amount coherent with /valuation");
    }

    // --------------------------------------------------
    // AA3 — pushed personalization
    // --------------------------------------------------

    /**
     * AA3 — with a pushed {@code cardNumber} + {@code cardPromotions} the displayed price is
     * personalized; the same call WITHOUT the block returns the public price; a pushed
     * {@code CAGNOTTE} entry is silently ignored, exactly as at the basket.
     */
    @Test
    void aa3_pushedPersonalization() {
        String card = "\"cardNumber\":\"CARD-AA3\",\"cardPromotions\":["
                + "{\"ean\":\"3309999999904\",\"promotionType\":\"PERCENT\",\"value\":0.10},"
                + "{\"ean\":\"3309999999902\",\"promotionType\":\"PERCENT\",\"value\":0.50,\"benefit\":\"CAGNOTTE\"}]";
        DisplayValuationEntry withCard = entryOf(display("0101", List.of("3309999999904"), card), "3309999999904");
        assertCents("5.40", withCard.displayedPrice.amountIncludingTax, "AA3 personalized displayed (10% off 6.00)");
        assertCents("0.60", withCard.immediateDiscount.amountIncludingTax, "AA3 personalized discount");
        assertEquals(0, valuationNetTtc("0101", "3309999999904",
                "\"cardNumber\":\"CARD-AA3\",\"cardPromotions\":[{\"ean\":\"3309999999904\",\"promotionType\":\"PERCENT\",\"value\":0.10}]")
                .compareTo(withCard.displayedPrice.amountIncludingTax), "AA3 coherent with /valuation");
        DisplayValuationEntry anonymous = entryOf(display("0101", List.of("3309999999904"), null), "3309999999904");
        assertCents("6.00", anonymous.displayedPrice.amountIncludingTax, "AA3 anonymous public price");
        assertNull(anonymous.immediateDiscount, "AA3 anonymous has no discount");
    }

    // --------------------------------------------------
    // AA4 — basket-dependent badges, never an invented price
    // --------------------------------------------------

    /**
     * AA4 — N+M, {@code TIERED_DISCOUNT}, {@code MIXED_BUNDLE} and a {@code MINIMUM_QUANTITY}-triggered
     * offer targeting the product surface as {@code offerBadges} only; {@code displayedPrice} stays
     * the price without those offers and no field converts their effect to a unit price.
     */
    @Test
    void aa4_basketDependentBadges() {
        assertBadge("3309999999905", "AA_NPLUSM", "N+M", "3.60");
        assertBadge("3309999999919", "AA_TIERED", "TIERED_DISCOUNT", "3.60");
        assertBadge("3309999999920", "AA_BUNDLE", "MIXED_BUNDLE", "3.60");
        assertBadge("3309999999916", "AA_MINQTY", "NEW_PRICE_DISCOUNT", "3.60");
    }

    /**
     * Asserts a product carries exactly the expected badge and keeps its undiscounted displayed
     * price (no invented unit price).
     *
     * @param ean      the product EAN.
     * @param code     the expected badge offer code.
     * @param type     the expected badge offer type.
     * @param baseTtc  the expected undiscounted displayed price, tax included.
     */
    private void assertBadge(String ean, String code, String type, String baseTtc) {
        DisplayValuationEntry e = entryOf(display("0101", List.of(ean), null), ean);
        assertCents(baseTtc, e.displayedPrice.amountIncludingTax, "AA4 " + code + " displayed unchanged");
        assertCents(baseTtc, e.basePrice.amountIncludingTax, "AA4 " + code + " base");
        assertNull(e.immediateDiscount, "AA4 " + code + " no immediate discount");
        boolean found = e.offerBadges.stream()
                .anyMatch(b -> code.equals(b.code) && type.equals(b.type) && b.label != null);
        assertTrue(found, "AA4 badge " + code + " (" + type + ") present: " + describe(e));
    }

    // --------------------------------------------------
    // AA5 — display exclusions
    // --------------------------------------------------

    /**
     * AA5 — an unconditional {@code TICKET_DISCOUNT} in force is absent from {@code displayedPrice}
     * (a ticket advantage, not a product price), and an {@code ANTI_WASTE_DISCOUNT} in force is never
     * applied (no best-before date at display); neither yields a price nor a badge.
     */
    @Test
    void aa5_displayExclusions() {
        DisplayValuationEntry ticket = entryOf(display("0199", List.of("3309999999906"), null), "3309999999906");
        assertCents("4.80", ticket.displayedPrice.amountIncludingTax, "AA5 ticket discount excluded");
        assertNull(ticket.immediateDiscount, "AA5 no ticket discount in displayed price");
        assertTrue(ticket.offerBadges.isEmpty(), "AA5 ticket discount is not a badge");
        DisplayValuationEntry antiWaste = entryOf(display("0199", List.of("3309999999918"), null), "3309999999918");
        assertCents("4.80", antiWaste.displayedPrice.amountIncludingTax, "AA5 anti-waste never applied");
        assertNull(antiWaste.immediateDiscount, "AA5 no anti-waste in displayed price");
        assertTrue(antiWaste.offerBadges.isEmpty(), "AA5 anti-waste is not a badge");
    }

    // --------------------------------------------------
    // AA6 — legal floors at display
    // --------------------------------------------------

    /**
     * AA6 — a markdown that would pierce the EGAlim ceiling (then the SRP floor) is clamped to the
     * floor, {@code floorGuard} naming the binding guard; the displayed price never descends below
     * the legal floor. Run on the dedicated guard store 0199.
     */
    @Test
    void aa6_legalFloors() {
        DisplayValuationEntry egalim = entryOf(display("0199", List.of("3309999999901"), null), "3309999999901");
        assertCents("0.80", egalim.displayedPrice.amountIncludingTax, "AA6 EGAlim clamped to ceiling");
        assertNotNull(egalim.floorGuard, "AA6 EGAlim floor guard present");
        assertEquals("EGALIM", egalim.floorGuard.type, "AA6 EGAlim guard type");
        DisplayValuationEntry srp = entryOf(display("0199", List.of("3309999999909"), null), "3309999999909");
        assertCents("1.80", srp.displayedPrice.amountIncludingTax, "AA6 SRP clamped to floor");
        assertNotNull(srp.floorGuard, "AA6 SRP floor guard present");
        assertEquals("SRP", srp.floorGuard.type, "AA6 SRP guard type");
        assertCents("1.80", srp.floorGuard.floorPrice, "AA6 SRP floor price");
    }

    // --------------------------------------------------
    // AA7 — unit price of measure
    // --------------------------------------------------

    /**
     * AA7 — a product with a {@code referenceWeight} (then a {@code referenceVolume}) carries a
     * {@code unitPriceMeasure} computed from {@code displayedPrice}, rounded 2 HALF_UP, in its
     * {@code unitName}; a product with no measure reference omits the field.
     */
    @Test
    void aa7_unitPriceOfMeasure() {
        DisplayValuationEntry weight = entryOf(display("0101", List.of("3309999999908"), null), "3309999999908");
        assertNotNull(weight.unitPriceMeasure, "AA7 weight reference present");
        assertCents("9.60", weight.unitPriceMeasure.value, "AA7 €/kg = 2.40 / 0.250");
        assertEquals("kg", weight.unitPriceMeasure.unit, "AA7 weight unit");
        DisplayValuationEntry volume = entryOf(display("0101", List.of("3309999999907"), null), "3309999999907");
        assertNotNull(volume.unitPriceMeasure, "AA7 volume reference present");
        assertCents("1.00", volume.unitPriceMeasure.value, "AA7 €/L = 1.50 / 1.500");
        assertEquals("L", volume.unitPriceMeasure.unit, "AA7 volume unit");
        DisplayValuationEntry none = entryOf(display("0101", List.of("3309999999900"), null), "3309999999900");
        assertNull(none.unitPriceMeasure, "AA7 no measure reference omits the field");
    }

    // --------------------------------------------------
    // AA8 — isolation between EANs (no cross-contamination)
    // --------------------------------------------------

    /**
     * AA8 — an offer targeting several EANs of the same call gives each product its whole markdown
     * (its own pseudo-ticket); an EAN's entry is identical whether it is alone, accompanied, or the
     * order of the EANs is reversed.
     */
    @Test
    void aa8_isolationBetweenEans() {
        DisplayValuationEntry[] together = display("0101", List.of("3309999999910", "3309999999911"), null);
        assertCents("1.50", entryOf(together, "3309999999910").displayedPrice.amountIncludingTax, "AA8 iso1 together");
        assertCents("1.50", entryOf(together, "3309999999911").displayedPrice.amountIncludingTax, "AA8 iso2 together");
        DisplayValuationEntry alone = entryOf(display("0101", List.of("3309999999910"), null), "3309999999910");
        assertCents("1.50", alone.displayedPrice.amountIncludingTax, "AA8 iso1 alone identical");
        DisplayValuationEntry[] reversed = display("0101", List.of("3309999999911", "3309999999910"), null);
        assertCents("1.50", entryOf(reversed, "3309999999910").displayedPrice.amountIncludingTax, "AA8 iso1 reversed identical");
        assertCents("1.50", entryOf(reversed, "3309999999911").displayedPrice.amountIncludingTax, "AA8 iso2 reversed identical");
    }

    // --------------------------------------------------
    // AA9 — refusals
    // --------------------------------------------------

    /**
     * AA9 — more EANs than the configured cap is a 400 naming the limit and the count received; an
     * unknown store or an unreadable date is a named refusal; an unknown EAN is an error on ITS
     * entry while the other EANs of the call are valued normally.
     */
    @Test
    void aa9_refusals() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(String.format("99%011d", i));
        }
        post(body("0101", tooMany, null)).then().statusCode(400)
                .body("error", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("200"),
                        org.hamcrest.Matchers.containsString("201")));
        post(body("9999", List.of("3309999999902"), null)).then().statusCode(422)
                .body("error", org.hamcrest.Matchers.containsString("9999"));
        post("{\"storeCode\":\"0101\",\"valuationDate\":\"not-a-date\",\"eans\":[\"3309999999902\"]}")
                .then().statusCode(400).body("error", org.hamcrest.Matchers.containsString("valuationDate"));
        DisplayValuationEntry[] mixed = display("0101", List.of("3309999999902", "0000000000000"), null);
        assertNull(entryOf(mixed, "3309999999902").error, "AA9 known EAN valued");
        assertCents("2.40", entryOf(mixed, "3309999999902").displayedPrice.amountIncludingTax, "AA9 known EAN price");
        assertNotNull(entryOf(mixed, "0000000000000").error, "AA9 unknown EAN carries its own error");
        assertNull(entryOf(mixed, "0000000000000").displayedPrice, "AA9 unknown EAN has no price");
    }

    // --------------------------------------------------
    // AA10 — read-only & fingerprint
    // --------------------------------------------------

    /**
     * AA10 — a batch writes nothing (no {@code ConsumptionRecord} appears); {@code priceVersion} is
     * stable for a given {@code (store, date)} and changes after an in-force price of that store is
     * updated.
     */
    @Test
    void aa10_readOnlyAndFingerprint() {
        long before = countConsumptionRecords();
        DisplayValuationEntry first = entryOf(display("0101", List.of("3309999999912"), null), "3309999999912");
        DisplayValuationEntry second = entryOf(display("0101", List.of("3309999999912"), null), "3309999999912");
        assertEquals(before, countConsumptionRecords(), "AA10 no consumption record written");
        assertEquals(first.priceVersion, second.priceVersion, "AA10 price version stable for same store/date");
        importCsv("/prices/import",
                "EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE\n"
                + "3309999999912|0101|2.50|3.00|0.2000|DEFAULT|0|2026-01-01T00:00:00|\n");
        DisplayValuationEntry third = entryOf(display("0101", List.of("3309999999912"), null), "3309999999912");
        assertTrue(!first.priceVersion.equals(third.priceVersion),
                "AA10 price version changes after a price update: " + first.priceVersion + " vs " + third.priceVersion);
    }

    /**
     * Counts the consumption records, under a read transaction.
     *
     * @return the number of {@code ConsumptionRecord} rows.
     */
    private static long countConsumptionRecords() {
        return QuarkusTransaction.requiringNew().call(() -> ConsumptionRecord.count());
    }

    // --------------------------------------------------
    // AA11 — determinism
    // --------------------------------------------------

    /**
     * AA11 — the same batch call, replayed 50 times, returns 50 byte-for-byte identical responses;
     * {@code /valuation} still answers on the shared catalog (the chantier leaves the basket
     * evaluation untouched).
     */
    @Test
    void aa11_determinism() {
        String request = body("0101", List.of("3309999999903", "3309999999905", "3309999999902"), null);
        String reference = post(request).then().statusCode(200).extract().asString();
        for (int i = 0; i < 50; i++) {
            String replay = post(request).then().statusCode(200).extract().asString();
            assertEquals(reference, replay, "AA11 run " + i + " must be byte-identical");
        }
        assertEquals(0, valuationNetTtc("0101", "3309999999903", null).compareTo(new BigDecimal("2.00")),
                "AA11 /valuation still evaluates the basket unchanged");
    }

    /**
     * Renders an entry for an assertion message.
     *
     * @param entry the entry.
     * @return a short description.
     */
    private static String describe(DisplayValuationEntry entry) {
        return "ean=" + entry.ean + " badges="
                + entry.offerBadges.stream().map(b -> b.code + "/" + b.type).collect(Collectors.joining(","));
    }
}
