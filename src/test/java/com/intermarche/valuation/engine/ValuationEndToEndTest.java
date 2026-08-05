package com.intermarche.valuation.engine;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the valuation service.
 * <p>
 * Each test submits a realistic basket to {@code /valuation} and checks the response, the
 * reference data having been loaded beforehand through the real import endpoints. Nothing
 * is mocked: a failure here means the assembled application misbehaves, not that a unit
 * drifted from its contract.
 * <p>
 * Assertions favour invariants over hard-coded totals. That the items of an offer sum to
 * that offer's amount, or that the VAT breakdown sums to the basket total, must hold
 * whatever the catalog prices are; asserting the figures themselves would turn every price
 * adjustment into a batch of false failures. Amounts are asserted only where the scenario
 * is precisely about a figure.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ValuationEndToEndTest {

    /**
     * Credentials of the bootstrap account, as configured for the test profile.
     */
    private static final String USER = "admin";

    /**
     * Password of the bootstrap account, as configured for the test profile.
     */
    private static final String PASSWORD = "admin";

    /**
     * Start of the validity window of the imported prices.
     */
    private static final String PRICE_START = "2026-01-12T00:00:00";

    /**
     * Guards the one-off import: the database lives for the whole test class.
     */
    private static boolean seeded = false;

    // --------------------------------------------------
    // Reference data
    // --------------------------------------------------

    /**
     * Loads stores, products, families, prices and offers through the import endpoints.
     * <p>
     * Going through the real endpoints rather than seeding the tables directly means the
     * importers are exercised too, and that the data under test is the data the application
     * actually accepts.
     */
    @BeforeAll
    static void seedReferenceData() {
        if (seeded) {
            return;
        }
        importCsv("/stores/import", """
                code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude
                0101|Intermarche Test 1|1 Rue du Test|ZI Nord|59000|Lille|France|50.63|3.06
                0102|Intermarche Test 2|12 Avenue des Fleurs||33000|Bordeaux|France|44.83|-0.57
                """);

        importCsv("/products/import", """
                ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active
                3300000000001|Pommes Golden|Pommes fraiches bio|Brand A|1.000|2.500|WEIGHT|kg|true
                3300000000002|Lait UHT 1L|Lait demi-ecreme|Brand B|1.000|1.000|UNIT|L|true
                3300000000004|Cafe Grains 500g|Cafe moulu arabica|Brand D|0.500|1.250|UNIT|kg|true
                3300000000005|Pates Penne 500g|Pates alimentaires|Brand E|0.500|1.250|WEIGHT|kg|true
                3300000000006|Huile d'Olive 1L|Huile vierge extra|Brand F|1.000|1.000|UNIT|L|true
                3300000000007|Eau Minerale 1.5L|Eau de source|Brand G|1.500|1.500|UNIT|L|true
                3300000000013|Biscuits Chocolat 200g|Paquet de biscuits|Brand M|0.200|0.500|UNIT|kg|true
                3300000000014|Chips Classiques 150g|Chips de pomme de terre|Brand N|0.150|0.400|UNIT|kg|true
                3300000000020|Poulet Roti 1.2kg|Poulet fermier|Brand T|1.200|3.000|WEIGHT|kg|true
                3300000000031|Poele Antiadhesive 28cm|Poele fonte alum|Tefal|0.800|0.000|UNIT|pcs|true
                3300000000032|Casserole Inox 20cm|Casserole acier inox|Staub|1.200|0.000|UNIT|pcs|true
                """);

        importCsv("/product-families/import", """
                code|description|flags|product_eans|family_codes
                POMMES|Pommes a croquer|TRADITIONAL,RESTAURANT_VOUCHER_ELIGIBLE|3300000000001,3300000000004|
                EAU_MINERALE|Eaux Minerales|RESTAURANT_VOUCHER_ELIGIBLE|3300000000007|
                CUISSON|Instruments de Cuisine||3300000000031,3300000000032|
                """);

        importCsv("/prices/import", ("""
                ean|storeCode|priceExcludingTax|priceIncludingTax|vatRate|priceUsage|priority|startDateTime|endDateTime
                3300000000001|0101|1.00|1.20|0.2000|DEFAULT|0|<<D>>|
                3300000000001|0101|1.10|1.32|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000002|0101|2.50|3.00|0.2000|DEFAULT|0|<<D>>|
                3300000000002|0101|2.75|3.30|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000004|0101|3.50|4.20|0.2000|DEFAULT|0|<<D>>|
                3300000000004|0101|3.85|4.62|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000005|0101|1.20|1.44|0.2000|DEFAULT|0|<<D>>|
                3300000000005|0101|1.32|1.58|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000006|0101|5.00|6.00|0.2000|DEFAULT|0|<<D>>|
                3300000000006|0101|5.50|6.60|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000007|0101|0.50|0.60|0.2000|DEFAULT|0|<<D>>|
                3300000000007|0101|0.55|0.66|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000013|0101|2.00|2.40|0.2000|DEFAULT|0|<<D>>|
                3300000000013|0101|2.20|2.64|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000014|0101|1.50|1.80|0.2000|DEFAULT|0|<<D>>|
                3300000000014|0101|1.65|1.98|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000020|0101|10.00|10.55|0.0550|DEFAULT|0|<<D>>|
                3300000000020|0101|11.00|11.61|0.0550|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000031|0101|12.00|14.40|0.2000|DEFAULT|0|<<D>>|
                3300000000031|0101|13.20|15.84|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000032|0101|15.00|18.00|0.2000|DEFAULT|0|<<D>>|
                3300000000032|0101|16.50|19.80|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                """).replace("<<D>>", PRICE_START));

        importCsv("/offers/import", """
                offer_code|offer_type|specification|store_code|store_group_code
                PROMO_STORE_101|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer"], "targetEans": ["3300000000001"], "discountType": "PERCENTAGE", "value": 15.0}|0101|
                PROMO_2FOR1_3300|N+M|{"targetEans": ["3300000000001"], "quantityToPay": 2, "discountedQuantity": 1, "selectionStrategy": "CHEAPEST", "discountType": "PERCENTAGE", "discountValue": 100.0}|0101|
                PROMO_COFFEE_PACK|MIXED_BUNDLE|{"bundlePrice": 4.50, "vatRate": 0.20, "contents": [{"ean": "3300000000004", "quantity": 1.0}, {"ean": "3300000000013", "quantity": 1.0, "substituteEans": ["3300000000014"]}]}|0101|
                DELIVERY_HOME_0101|DELIVERY|{"tiers": [{"maxDistance": 8.0, "price": 5.90}, {"maxDistance": 16.0, "price": 9.90}], "vatRate": 0.20}|0101|
                BRI_APPLES_DISCOUNT|IMMEDIATE_VOUCHER|{"targetOfferClass": ["BasicOffer", "MixedBundleOffer"], "targetEans": ["3300000000001"], "discountType": "FIXED_AMOUNT", "value": 0.10}|0101|
                BASKET_CONSIGNMENT_0101|DEPOSIT_BASKET|{"basketVolume": 10.0, "basketPrice": 0.50, "vatRate": 0.20}|0101|
                FREE_DELIVERY_THRESHOLD_0101|FREE_DELIVERY_THRESHOLD|{"tiers": [{"threshold": 10.0, "value": 50.0, "type": "FIXED_AMOUNT"}, {"threshold": 20.0, "value": 100.0, "type": "FIXED_AMOUNT"}]}|0101|
                MEAL_VOUCHER_0101|MEAL_VOUCHER|{"flag": "RESTAURANT_VOUCHER_ELIGIBLE", "threshold": 25.00}|0101|
                VIGNETTE_CUISSON|VIGNETTE_DISCOUNT|{"catalog": [{"ean": "3300000000031", "vignettesRequired": 5, "discount": {"type": "PERCENTAGE", "value": 50.0}}, {"ean": "3300000000032", "vignettesRequired": 3, "discount": {"type": "FIXED_AMOUNT", "value": 2.00}}]}|0101|
                """);

        seeded = true;
    }

    /**
     * Posts a CSV payload to an import endpoint and fails the run if it is rejected.
     *
     * @param path The import endpoint.
     * @param csv  The pipe-separated payload.
     */
    private static void importCsv(String path, String csv) {
        given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.TEXT)
                .body(csv)
                .when().post(path)
                .then().statusCode(200);
    }

    // --------------------------------------------------
    // Scenarios
    // --------------------------------------------------

    /**
     * A single line with no offer: the baseline every other case is read against.
     */
    @Test
    @Order(1)
    @DisplayName("1 - A plain line is priced at its catalog price")
    void plainLine() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000002", "quantity": 1 } ] }
                """);
        response.then()
                .body("offers", hasSize(1))
                .body("totalPrice.amountIncludingTax", equalTo(3.00f));
        assertInvariants(response);
    }

    /**
     * Two immediate vouchers target the same product: both must apply and both must lower
     * the total, which is the regression this covers — a discount stored with the wrong
     * sign raises the total instead.
     */
    @Test
    @Order(2)
    @DisplayName("2 - Immediate vouchers stack and lower the total")
    void immediateVouchers() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000001", "quantity": 1 } ] }
                """);
        response.then().body("advantages.findAll { it.discountAmount != null }", hasSize(2));

        BigDecimal offers = sumOfferAmounts(response);
        BigDecimal total = total(response);
        assertTrue(total.compareTo(offers) < 0,
                "discounts must lower the total: offers " + offers + ", total " + total);
        assertInvariants(response);
    }

    /**
     * Three units form exactly one N+M bundle. The line is split into a paid and a free
     * part, both tracing back to the same request line.
     */
    @Test
    @Order(3)
    @DisplayName("3 - N+M splits one line into paid and free parts")
    void nPlusMExact() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000001", "quantity": 3 } ] }
                """);
        List<Map<String, Object>> items = response.jsonPath()
                .getList("offers.find { it.type.contains('PROMO_2FOR1') }.items");
        assertEquals(2, items.size(), "the bundle should expose a paid and a free part");
        items.forEach(item -> assertEquals("L1", item.get("lineId"),
                "every part must trace back to the request line"));
        assertInvariants(response);
    }

    /**
     * Five units make one bundle and leave two on a standard line, and the engine suggests
     * the unit missing for a second bundle.
     */
    @Test
    @Order(4)
    @DisplayName("4 - A partial N+M leaves a remainder and suggests the missing unit")
    void nPlusMWithRemainder() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000001", "quantity": 5 } ] }
                """);
        response.then()
                .body("advantages.find { it.suggestion != null }.suggestion.offerCode",
                        equalTo("PROMO_2FOR1_3300"));
        assertNoEmptyOffer(response);
        assertInvariants(response);
    }

    /**
     * The mixed bundle prices two different products at one fixed price, below the sum of
     * their catalog prices.
     */
    @Test
    @Order(5)
    @DisplayName("5 - A mixed bundle applies its fixed price")
    void mixedBundle() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000004", "quantity": 1 },
                  { "lineId": "L2", "produceEan": "3300000000013", "quantity": 1 } ] }
                """);
        response.then().body(
                "offers.find { it.type.contains('PROMO_COFFEE_PACK') }.amount.amountIncludingTax",
                equalTo(4.50f));
        assertInvariants(response);
    }

    /**
     * The bundle still forms when a component is replaced by one of its substitutes.
     */
    @Test
    @Order(6)
    @DisplayName("6 - A substitute completes the bundle")
    void mixedBundleWithSubstitute() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000004", "quantity": 1 },
                  { "lineId": "L2", "produceEan": "3300000000014", "quantity": 1 } ] }
                """);
        response.then().body(
                "offers.find { it.type.contains('PROMO_COFFEE_PACK') }.amount.amountIncludingTax",
                equalTo(4.50f));
        assertInvariants(response);
    }

    /**
     * Vignettes are the only path that reads the dedicated basket field; five of them halve
     * the price of the pan.
     */
    @Test
    @Order(7)
    @DisplayName("7 - Vignettes discount the targeted product")
    void vignettes() {
        Response response = evaluate("""
                { "storeCode": "0101",
                  "vignettes": { "3300000000031": 5 },
                  "items": [ { "lineId": "L1", "produceEan": "3300000000031", "quantity": 1 } ] }
                """);
        response.then().body("advantages.find { it.type.contains('Vignette') }", notNullValue());
        assertTrue(total(response).compareTo(sumOfferAmounts(response)) < 0,
                "the vignette discount must lower the total");
        assertInvariants(response);
    }

    /**
     * A home delivery far enough to fall in the second distance tier, on a basket large
     * enough for the delivery to be refunded in full, with a deposit basket on top.
     */
    @Test
    @Order(8)
    @DisplayName("8 - Delivery, deposit basket and free-delivery threshold combine")
    void deliveryDepositAndThreshold() {
        Response response = evaluate("""
                { "storeCode": "0101", "deliveryMode": "HOME_DELIVERY",
                  "deliveryAddress": { "streetLine1": "12 Rue du Test", "postalCode": "59113",
                                       "city": "Seclin", "country": "France",
                                       "latitude": 50.540, "longitude": 3.030 },
                  "items": [ { "lineId": "L1", "produceEan": "3300000000031", "quantity": 2 } ] }
                """);
        response.then()
                .body("offers.find { it.type.startsWith('Delivery') }", notNullValue())
                .body("offers.find { it.type.contains('Deposit') }", notNullValue());
        assertInvariants(response);
    }

    /**
     * A basket mixing a reduced and a standard rate. The breakdown must carry both real
     * rates and never the blended figure the total falls back to.
     */
    @Test
    @Order(9)
    @DisplayName("9 - The VAT breakdown reports real rates, never a blended one")
    void vatBreakdownAcrossRates() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000020", "quantity": 1 },
                  { "lineId": "L2", "produceEan": "3300000000002", "quantity": 2 } ] }
                """);
        List<Float> rates = response.jsonPath().getList("vatBreakdown.vatRate", Float.class);
        assertTrue(rates.contains(0.055f), "the reduced rate must appear: " + rates);
        assertTrue(rates.contains(0.20f), "the standard rate must appear: " + rates);
        rates.forEach(rate -> assertTrue(rate == 0.055f || rate == 0.20f,
                "only legal rates may appear, found " + rate));
        assertInvariants(response);
    }

    /**
     * Only flagged products count towards the meal-voucher base, and the base is net of the
     * discounts applied to the offers holding them.
     */
    @Test
    @Order(10)
    @DisplayName("10 - The meal voucher base excludes non-eligible products")
    void mealVoucherEligibility() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000001", "quantity": 2 },
                  { "lineId": "L2", "produceEan": "3300000000007", "quantity": 3 },
                  { "lineId": "L3", "produceEan": "3300000000002", "quantity": 1 } ] }
                """);
        Float eligible = response.jsonPath()
                .getFloat("advantages.find { it.type == 'MEAL_VOUCHER' }.totalEligibleAmount");
        assertTrue(eligible > 0f, "an eligible amount is expected");
        assertTrue(BigDecimal.valueOf(eligible).compareTo(total(response)) < 0,
                "the milk is not eligible, so the base must stay under the basket total");
        assertInvariants(response);
    }

    /**
     * The three manual gestures on three lines. Each is ultra-priority, so each line is
     * captured by its own gesture offer and no discount may reach it.
     */
    @Test
    @Order(11)
    @DisplayName("11 - The three manual gestures apply and bar every discount")
    void manualGestures() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000005", "quantity": 1, "manualForcedPrice": 1.00 },
                  { "lineId": "L2", "produceEan": "3300000000006", "quantity": 1, "manualDiscountAmount": 0.50 },
                  { "lineId": "L3", "produceEan": "3300000000031", "quantity": 1, "manualDiscountPercent": 50 } ] }
                """);
        response.then()
                .body("offers.findAll { it.type.startsWith('Manual Gesture') }", hasSize(3))
                // Forced price: the catalog price is replaced outright.
                .body("offers.find { it.type.contains('3300000000005') }.amount.amountIncludingTax",
                        equalTo(1.00f))
                // Fixed amount: 6.00 catalog less 0.50.
                .body("offers.find { it.type.contains('3300000000006') }.amount.amountIncludingTax",
                        equalTo(5.50f))
                // Percentage: half of 14.40.
                .body("offers.find { it.type.contains('3300000000031') }.amount.amountIncludingTax",
                        equalTo(7.20f));

        List<Map<String, Object>> discounts = response.jsonPath()
                .getList("advantages.findAll { it.discountAmount != null }");
        assertTrue(discounts.isEmpty(),
                "a line under a manual gesture must not receive any discount");
        assertInvariants(response);
    }

    /**
     * The same product on two lines at two different prices, one of them under a manual
     * gesture. They must not be merged, and the gesture must not spill over the other line.
     */
    @Test
    @Order(12)
    @DisplayName("12 - Two prices for one product stay on separate lines")
    void sameProductTwoPrices() {
        Response response = evaluate("""
                { "storeCode": "0101", "items": [
                  { "lineId": "L1", "produceEan": "3300000000001", "quantity": 3 },
                  { "lineId": "L2", "produceEan": "3300000000001", "quantity": 2, "manualDiscountAmount": 0.30 } ] }
                """);
        // The gesture captures exactly its own line, and only its quantity.
        List<Map<String, Object>> gestureItems = response.jsonPath()
                .getList("offers.find { it.type.startsWith('Manual Gesture') }.items");
        assertEquals(1, gestureItems.size(), "the gesture covers a single line");
        assertEquals("L2", gestureItems.get(0).get("lineId"));
        assertEquals(2.0, ((Number) gestureItems.get(0).get("quantity")).doubleValue(), 0.001,
                "the gesture must not consume the other line's quantity");

        // The other line is still free to enter an offer.
        response.then().body("offers.findAll { it.items.any { i -> i.lineId == 'L1' } }",
                hasSize(greaterThanOrEqualTo(1)));
        assertInvariants(response);
    }

    // --------------------------------------------------
    // Rejections
    // --------------------------------------------------

    /**
     * Two gestures on one line contradict each other and must be refused rather than
     * silently resolved in favour of one of them.
     */
    @Test
    @Order(13)
    @DisplayName("13 - Two manual gestures on one line are rejected")
    void twoGesturesRejected() {
        given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.JSON)
                .body("""
                        { "storeCode": "0101", "items": [
                          { "lineId": "L1", "produceEan": "3300000000001", "quantity": 1,
                            "manualForcedPrice": 1.00, "manualDiscountPercent": 50 } ] }
                        """)
                .when().post("/valuation")
                .then().statusCode(greaterThanOrEqualTo(400));
    }

    /**
     * An unknown product cannot be priced and must not yield a partial valuation.
     */
    @Test
    @Order(14)
    @DisplayName("14 - An unknown EAN is rejected")
    void unknownEanRejected() {
        given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.JSON)
                .body("""
                        { "storeCode": "0101", "items": [
                          { "lineId": "L1", "produceEan": "9999999999999", "quantity": 1 } ] }
                        """)
                .when().post("/valuation")
                .then().statusCode(greaterThanOrEqualTo(400));
    }

    /**
     * An unknown store leaves no offer context and must be refused.
     */
    @Test
    @Order(15)
    @DisplayName("15 - An unknown store is rejected")
    void unknownStoreRejected() {
        given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.JSON)
                .body("""
                        { "storeCode": "9999", "items": [
                          { "lineId": "L1", "produceEan": "3300000000001", "quantity": 1 } ] }
                        """)
                .when().post("/valuation")
                .then().statusCode(greaterThanOrEqualTo(400));
    }

    /**
     * The schema refuses a basket without any line.
     */
    @Test
    @Order(16)
    @DisplayName("16 - An empty basket is rejected")
    void emptyBasketRejected() {
        given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.JSON)
                .body("{ \"storeCode\": \"0101\", \"items\": [] }")
                .when().post("/valuation")
                .then().statusCode(greaterThanOrEqualTo(400));
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Submits a basket and asserts the call succeeded.
     *
     * @param basketJson The basket payload.
     * @return The valuation response, for further inspection.
     */
    private Response evaluate(String basketJson) {
        Response response = given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.JSON)
                .body(basketJson)
                .when().post("/valuation");
        response.then().statusCode(200);
        return response;
    }

    /**
     * Checks the structural invariants every valuation must satisfy.
     * <p>
     * These hold whatever the catalog prices are, so they catch a regression without having
     * to be rewritten each time a price moves: the items of an offer account for that
     * offer's amount, the VAT breakdown accounts for the basket total, and every breakdown
     * line carries a rate that is really charged.
     *
     * @param response The valuation response.
     */
    private void assertInvariants(Response response) {
        assertItemsSumToOfferAmount(response);
        assertBreakdownSumsToTotal(response);
        assertNoEmptyOffer(response);
    }

    /**
     * Asserts that, for every offer exposing valued items, those items sum to its amount.
     *
     * @param response The valuation response.
     */
    private void assertItemsSumToOfferAmount(Response response) {
        List<Map<String, Object>> offers = response.jsonPath().getList("offers");
        for (Map<String, Object> offer : offers) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) offer.get("items");
            if (items == null || items.isEmpty()) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> item : items) {
                sum = sum.add(amountOf(item.get("amount")));
            }
            BigDecimal expected = amountOf(offer.get("amount"));
            assertEquals(0, scaled(expected).compareTo(scaled(sum)),
                    "items of offer '" + offer.get("type") + "' sum to " + sum
                            + " but the offer amount is " + expected);
        }
    }

    /**
     * Asserts that the VAT breakdown accounts for the whole basket total.
     *
     * @param response The valuation response.
     */
    private void assertBreakdownSumsToTotal(Response response) {
        List<Map<String, Object>> lines = response.jsonPath().getList("vatBreakdown");
        if (lines == null || lines.isEmpty()) {
            return;
        }
        BigDecimal sumTtc = BigDecimal.ZERO;
        BigDecimal sumHt = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            BigDecimal ht = decimal(line.get("amountExcludingTax"));
            BigDecimal ttc = decimal(line.get("amountIncludingTax"));
            BigDecimal vat = decimal(line.get("vatAmount"));
            assertEquals(0, scaled(ttc.subtract(ht)).compareTo(scaled(vat)),
                    "on the " + line.get("vatRate") + " line, tax should be the difference "
                            + "between the two amounts");
            sumHt = sumHt.add(ht);
            sumTtc = sumTtc.add(ttc);
        }
        assertEquals(0, scaled(total(response)).compareTo(scaled(sumTtc)),
                "the VAT breakdown sums to " + sumTtc + " but the total is " + total(response));
    }

    /**
     * Asserts that no offer was emitted without consuming anything.
     * <p>
     * An offer at zero with no item is the signature of an applier that decided it could
     * apply on stale quantities and produced an empty application anyway.
     *
     * @param response The valuation response.
     */
    private void assertNoEmptyOffer(Response response) {
        List<Map<String, Object>> offers = response.jsonPath().getList("offers");
        for (Map<String, Object> offer : offers) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) offer.get("items");
            BigDecimal amount = amountOf(offer.get("amount"));
            boolean empty = (items == null || items.isEmpty())
                    && amount.compareTo(BigDecimal.ZERO) == 0;
            assertFalse(empty, "offer '" + offer.get("type") + "' is empty and priced at zero");
        }
    }

    /**
     * Reads the tax-included figure of an amount node.
     *
     * @param amountNode The serialized amount, may be null.
     * @return The tax-included amount, zero when absent.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal amountOf(Object amountNode) {
        if (!(amountNode instanceof Map)) {
            return BigDecimal.ZERO;
        }
        return decimal(((Map<String, Object>) amountNode).get("amountIncludingTax"));
    }

    /**
     * Converts a JSON number to a decimal.
     *
     * @param value The raw value, may be null.
     * @return The value as a decimal, zero when absent.
     */
    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    /**
     * Normalises a decimal to the cent, for comparisons.
     *
     * @param value The value to normalise.
     * @return The value at two decimal places.
     */
    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the basket total, tax included.
     *
     * @param response The valuation response.
     * @return The total including tax.
     */
    private BigDecimal total(Response response) {
        return decimal(response.jsonPath().get("totalPrice.amountIncludingTax"));
    }

    /**
     * Returns the sum of the offer amounts, before any discount is deducted.
     *
     * @param response The valuation response.
     * @return The summed offer amounts, tax included.
     */
    private BigDecimal sumOfferAmounts(Response response) {
        List<Map<String, Object>> offers = response.jsonPath().getList("offers");
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> offer : offers) {
            sum = sum.add(amountOf(offer.get("amount")));
        }
        return sum;
    }
}
