package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.ValuationTrace;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the tiered discount and instrument grant offer types.
 * <p>
 * One offer of each new type (TIERED_DISCOUNT, VOUCHER_GRANT, COUPON_GRANT) is seeded on a
 * dedicated store through the real import endpoints, then a basket that triggers all three
 * is submitted to {@code /valuation}. The scenario asserts the arithmetic contract that ties
 * the offers, the discount advantages and the basket total together, that the two grants are
 * informational (no discount amount, a positive granted amount), and that removing them
 * leaves the total untouched — the neutrality the RFP requires from an issued instrument.
 * <p>
 * The store code {@code 0109} is unique to this class so it never collides with the mirror
 * catalog other end-to-end classes rely on, and the reference rows are cleared afterwards in
 * reverse dependency order.
 */
@QuarkusTest
public class TieredInstrumentEndToEndTest {

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

    /**
     * Loads the store, its products, prices and the three new offer types through the import
     * endpoints, once for the whole class.
     * <p>
     * Run before each test but guarded so it happens once: the extension points RestAssured
     * at the test port from its own before-each callback, so a call issued from a
     * {@code @BeforeAll} would still target the default port and be refused.
     */
    @BeforeEach
    void seedReferenceData() {
        if (seeded) {
            return;
        }
        importCsv("/stores/import", """
                CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE
                0109|Intermarche Tier|9 Rue du Palier||59000|Lille|France|50.63|3.06
                """);

        importCsv("/products/import", """
                EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE
                3300000000201|Produit A|Article standard|Brand A|1.000|1.000|UNIT|pcs|true
                3300000000202|Produit B|Article standard|Brand B|1.000|1.000|UNIT|pcs|true
                """);

        importCsv("/prices/import", ("""
                EAN|STORE_CODE|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRICE_USAGE|PRIORITY|START_DATE|END_DATE
                3300000000201|0109|25.00|30.00|0.2000|DEFAULT|0|<<D>>|
                3300000000201|0109|25.00|30.00|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                3300000000202|0109|40.00|48.00|0.2000|DEFAULT|0|<<D>>|
                3300000000202|0109|40.00|48.00|0.2000|BASE_FOR_DISCOUNT|0|<<D>>|
                """).replace("<<D>>", PRICE_START));

        importCsv("/offers/import", """
                CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES
                TIERED_RICE_0109|TIERED_DISCOUNT|{"scope": "TICKET", "metric": "AMOUNT", "mode": "HIGHEST_REACHED", "tiers": [{"threshold": 50.0, "award": {"type": "PERCENTAGE", "value": 5.0}}]}|0109|
                VOUCHER_TICKET_0109|VOUCHER_GRANT|{"scope": "TICKET", "metric": "AMOUNT", "mode": "HIGHEST_REACHED", "usage": {"validityDays": 30}, "tiers": [{"threshold": 50.0, "award": {"type": "AMOUNT", "value": 5.0}}]}|0109|
                COUPON_POINTS_0109|COUPON_GRANT|{"scope": "TICKET", "metric": "AMOUNT", "mode": "HIGHEST_REACHED", "unit": "POINTS", "tiers": [{"threshold": 50.0, "award": {"type": "AMOUNT", "value": 10.0}}]}|0109|
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

    /**
     * Removes the reference data this class inserted, in reverse dependency order so no
     * foreign key is ever left dangling for the classes that follow in the shared database.
     */
    @AfterAll
    static void clearReferenceData() {
        QuarkusTransaction.requiringNew().run(() -> {
            Price.deleteAll();
            Offer.deleteAll();
            ProductFamily.deleteAll();
            Product.deleteAll();
            StoreGroup.deleteAll();
            Store.deleteAll();
        });
        seeded = false;
    }

    /**
     * The basket that triggers the three offers: two lines summing to 78€, above every
     * offer's 50€ threshold.
     */
    private static final String BASKET = """
            { "storeCode": "0109", "items": [
              { "lineId": "L1", "produceEan": "3300000000201", "quantity": 1 },
              { "lineId": "L2", "produceEan": "3300000000202", "quantity": 1 } ] }
            """;

    /**
     * Submits the shared basket, checks the tiered discount and both grants applied, asserts
     * the offers/discounts/total arithmetic to the cent, and finally proves the grants are
     * neutral by deleting them and re-evaluating.
     */
    @Test
    void tieredDiscountAndGrantsCombine() {
        Response response = evaluate(BASKET);
        List<Map<String, Object>> advantages = response.jsonPath().getList("advantages");
        assertNotNull(advantages, "the response must carry advantages");

        // The tiered discount is present as a discount advantage carrying an amount.
        Map<String, Object> tiered = advantages.stream()
                .filter(a -> String.valueOf(a.get("type")).startsWith("Tiered Discount:"))
                .findFirst().orElse(null);
        assertNotNull(tiered, "a Tiered Discount advantage is expected");
        assertNotNull(tiered.get("discountAmount"), "the tiered discount must carry a discount amount");

        // Both grants are present, informational (no discount amount) and value-bearing.
        assertGrant(advantages, "Voucher Grant:");
        assertGrant(advantages, "Coupon Grant:");

        // sum(offer amounts) - sum(discount amounts) == total, to the cent.
        BigDecimal offers = sumOfferAmounts(response);
        BigDecimal discounts = sumDiscountAmounts(advantages);
        assertEquals(0, scaled(offers.subtract(discounts)).compareTo(scaled(total(response))),
                "offers " + offers + " minus discounts " + discounts + " must equal total " + total(response));

        // Neutrality: dropping the two grants leaves the total unchanged.
        BigDecimal totalWithGrants = total(response);
        QuarkusTransaction.requiringNew().run(() -> {
            Offer.delete("code = ?1", "VOUCHER_TICKET_0109");
            Offer.delete("code = ?1", "COUPON_POINTS_0109");
        });
        Response withoutGrants = evaluate(BASKET);
        assertEquals(0, scaled(totalWithGrants).compareTo(scaled(total(withoutGrants))),
                "the grants must not change the total: " + totalWithGrants + " vs " + total(withoutGrants));
        List<Map<String, Object>> remaining = withoutGrants.jsonPath().getList("advantages");
        assertTrue(remaining.stream().noneMatch(
                        a -> String.valueOf(a.get("type")).startsWith("Voucher Grant:")
                                || String.valueOf(a.get("type")).startsWith("Coupon Grant:")),
                "no grant advantage should remain once the grant offers are removed");
    }

    /**
     * Asserts the basket line schema: a line priced by its EAN is accepted, a line priced by
     * its own triplet (no EAN) is accepted and valued as a generic line, and a line carrying
     * neither is rejected by the schema with the "Error validating offer" message.
     */
    @Test
    void basketSchemaAcceptsEanOrTripletRejectsNeither() {
        // A line identified by its EAN is priced from the catalog.
        evaluate("""
                { "storeCode": "0109", "items": [
                  { "lineId": "L1", "produceEan": "3300000000201", "quantity": 1 } ] }
                """);
        // A line without an EAN but with the full price triplet is valued as a generic line.
        Response generic = evaluate("""
                { "storeCode": "0109", "items": [
                  { "lineId": "L1", "quantity": 2,
                    "pricePerUnitExclTax": 5.00, "pricePerUnitInclTax": 6.00, "vatRate": 0.20 } ] }
                """);
        List<Map<String, Object>> offers = generic.jsonPath().getList("offers");
        assertTrue(offers.stream().anyMatch(o -> String.valueOf(o.get("type")).startsWith("Generic")),
                "the no-EAN line must be valued as a generic line");
        assertEquals(0, scaled(new BigDecimal("12.00")).compareTo(scaled(total(generic))),
                "the generic line is valued at 2 x 6.00 = 12.00");
        // A line carrying neither an EAN nor the price triplet is rejected by the schema.
        given().auth().preemptive().basic(USER, PASSWORD)
                .contentType(ContentType.JSON)
                .body("""
                        { "storeCode": "0109", "items": [
                          { "lineId": "L1", "quantity": 1 } ] }
                        """)
                .when().post("/valuation")
                .then().statusCode(400);
        // The 400 body carries no entity: the message is asserted from the recorded trace.
        String[] message = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ValuationTrace trace = ValuationTrace
                    .find("status = ?1 order by createdAt desc", ValuationTrace.STATUS_REJECTED)
                    .firstResult();
            message[0] = trace == null ? null : trace.errorMessage;
        });
        assertNotNull(message[0], "the rejection must have been traced");
        assertTrue(message[0].contains("Error validating offer"),
                "the schema rejection message must start with 'Error validating offer', was: " + message[0]);
    }

    /**
     * Asserts that a grant advantage of the given type prefix is informational: it carries a
     * positive granted amount and no discount amount.
     *
     * @param advantages The advantages of the response.
     * @param prefix     The type prefix identifying the grant ("Voucher Grant:" or "Coupon Grant:").
     */
    @SuppressWarnings("unchecked")
    private void assertGrant(List<Map<String, Object>> advantages, String prefix) {
        Map<String, Object> grant = advantages.stream()
                .filter(a -> String.valueOf(a.get("type")).startsWith(prefix))
                .findFirst().orElse(null);
        assertNotNull(grant, "a " + prefix + " advantage is expected");
        assertNull(grant.get("discountAmount"), prefix + " must not carry a discount amount");
        Map<String, Object> grantNode = (Map<String, Object>) grant.get("grant");
        assertNotNull(grantNode, prefix + " must expose its granted instrument");
        assertTrue(decimal(grantNode.get("amount")).compareTo(BigDecimal.ZERO) > 0,
                prefix + " must grant a positive amount");
    }

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

    /**
     * Returns the sum of the discount amounts across the advantages that carry one.
     *
     * @param advantages The advantages of the response.
     * @return The summed discount amounts, tax included.
     */
    private BigDecimal sumDiscountAmounts(List<Map<String, Object>> advantages) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> advantage : advantages) {
            sum = sum.add(amountOf(advantage.get("discountAmount")));
        }
        return sum;
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
}
