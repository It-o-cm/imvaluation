package com.intermarche.valuation.ui;
import com.intermarche.valuation.domain.util.DomainUtils;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.ProductType;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.valuation.CoverageDbReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

/**
 * Endpoint coverage tests for {@link ValuationUiResource}, exercised through the real HTTP stack
 * so the container instruments the resource for coverage.
 * <p>
 * Authentication uses {@code @TestSecurity} to inject an identity carrying the role under test;
 * the class-level role set (VIEWER, MANAGER, ADMIN) is asserted alongside the ADMIN-only
 * configuration and purge endpoints, the browsing screens, the replay preload, and every branch
 * of the test-valuation submission flow. A minimal catalog (one store, one product, one price) is
 * seeded directly so a valid basket runs end to end through the engine.
 */
@QuarkusTest
public class ValuationUiResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * Store code shared by the seeded catalog and the submitted baskets.
     */
    private static final String STORE = "0101";

    /**
     * EAN of the single seeded product, priced at its DEFAULT usage.
     */
    private static final String EAN = "3300000000002";

    /**
     * A schema-valid basket the seeded catalog can price to completion.
     */
    private static final String VALID_BASKET =
            "{\"storeCode\":\"" + STORE + "\",\"items\":[{\"lineId\":\"L1\",\"produceEan\":\""
                    + EAN + "\",\"quantity\":1}]}";

    /**
     * Clears every mutable table before each test so rows left by another class cannot skew the
     * assertions; the order is the reverse of the foreign-key dependencies, and the trace tables
     * are cleared too since this resource browses and purges them.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        ValuationTrace.deleteAll();
        ValuationTraceConfig.deleteAll();
        Price.deleteAll();
        Offer.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Persists a store, a UNIT product and a DEFAULT price with an open validity window, so the
     * engine can price the {@link #VALID_BASKET} to completion. The prices carry no start or end
     * date, which makes them valid whatever the test clock returns.
     */
    void seedCatalog() {
        QuarkusTransaction.requiringNew().run(() -> {
            Store store = new Store();
            store.code = STORE;
            store.name = "Store " + STORE;
            store.persist();
            Product product = new Product();
            product.ean = EAN;
            product.name = "Milk";
            product.productType = ProductType.UNIT;
            product.unitName = "L";
            product.active = true;
            product.persist();
            Price price = new Price();
            price.product = product;
            price.store = store;
            price.priceUsage = PriceUsage.DEFAULT;
            price.priceExcludingTax = new BigDecimal("2.50");
            price.priceIncludingTax = new BigDecimal("3.00");
            price.vat = DomainUtils.resolveOrCreateVatRate(new BigDecimal("0.2000"));
            price.persist();
            // The engine also resolves a reference (BASE_FOR_DISCOUNT) price for every line.
            Price base = new Price();
            base.product = product;
            base.store = store;
            base.priceUsage = PriceUsage.BASE_FOR_DISCOUNT;
            base.priceExcludingTax = new BigDecimal("2.75");
            base.priceIncludingTax = new BigDecimal("3.30");
            base.vat = DomainUtils.resolveOrCreateVatRate(new BigDecimal("0.2000"));
            base.persist();
        });
    }

    /**
     * Persists a recorded trace in its own committed transaction and returns its identifier.
     *
     * @param store          The store code carried by the trace.
     * @param customer       The customer code carried by the trace.
     * @param status         The outcome status.
     * @param requestPayload The recorded request body, may be null.
     * @return The identifier of the created trace.
     */
    Long seedTrace(String store, String customer, String status, String requestPayload) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ValuationTrace trace = new ValuationTrace();
            trace.storeCode = store;
            trace.customerCode = customer;
            trace.status = status;
            trace.httpStatus = 200;
            trace.itemCount = 1;
            trace.durationMs = 5L;
            trace.totalIncludingTax = new BigDecimal("3.00");
            trace.requestPayload = requestPayload;
            trace.responsePayload = "{}";
            trace.persist();
            return trace.id;
        });
    }

    /**
     * Starts a form-encoded request without following redirects, so a 303/302 outcome can be
     * asserted directly.
     *
     * @return A request specification ready for form parameters.
     */
    private io.restassured.specification.RequestSpecification form() {
        return given().redirects().follow(false).contentType(ContentType.URLENC);
    }

    // --------------------------------------------------
    // List
    // --------------------------------------------------

    /**
     * Tests that the list screen renders for a viewer and shows a seeded trace, covering the
     * non-admin write flag and the notice display branch.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testList_rendersForViewer() {
        seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, VALID_BASKET);
        given().when().get("/ui/valuations?notice=Hello&noticeOk=false")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("Valuations"))
                .body(containsString(STORE));
    }

    /**
     * Tests that the list screen renders for a manager, covering the MANAGER arm of the
     * class-level role set.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testList_rendersForManager() {
        given().when().get("/ui/valuations").then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests every filter combination, each explicit sort key, the invalid-sort fallback, the
     * ascending direction, the page lower clamp and an out-of-range page, as an administrator so
     * the admin write flag is exercised too.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testList_filtersSortsAndPaging() {
        seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, VALID_BASKET);
        seedTrace("0102", "CUST2", ValuationTrace.STATUS_REJECTED, VALID_BASKET);
        // All three filters set: store clause, then customer and status appended with " and ".
        given().when().get("/ui/valuations?store=01&customer=cust1&status=SUCCESS"
                        + "&sort=storeCode&dir=asc&page=1")
                .then().statusCode(200).body(containsString(STORE));
        // Customer only: the where clause is empty when the customer clause is appended.
        given().when().get("/ui/valuations?customer=cust2").then().statusCode(200);
        // Status only: the where clause is empty when the status clause is appended.
        given().when().get("/ui/valuations?status=SUCCESS").then().statusCode(200);
        given().when().get("/ui/valuations?sort=durationMs").then().statusCode(200);
        given().when().get("/ui/valuations?sort=totalIncludingTax").then().statusCode(200);
        given().when().get("/ui/valuations?sort=unknown&dir=desc").then().statusCode(200);
        given().when().get("/ui/valuations?page=0").then().statusCode(200);
        given().when().get("/ui/valuations?page=999").then().statusCode(200);
    }

    /**
     * Tests that an anonymous caller is redirected to the login screen by the form authentication
     * mechanism.
     */
    @Test
    void testList_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/valuations")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }

    // --------------------------------------------------
    // Rows fragment
    // --------------------------------------------------

    /**
     * Tests the rows fragment for a viewer, covering the filters, an explicit sort key, the
     * invalid-sort fallback, the ascending direction and the page lower clamp.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testRows_rendersWithFilters() {
        seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, VALID_BASKET);
        given().when().get("/ui/valuations/rows?store=01&customer=cust&status=SUCCESS"
                        + "&sort=totalIncludingTax&dir=asc&page=0")
                .then().statusCode(200).contentType(ContentType.HTML);
        given().when().get("/ui/valuations/rows?sort=unknown").then().statusCode(200);
    }

    /**
     * Tests the rows fragment for an administrator, covering the admin write flag of that
     * endpoint.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testRows_rendersForAdmin() {
        given().when().get("/ui/valuations/rows").then().statusCode(200).contentType(ContentType.HTML);
    }

    // --------------------------------------------------
    // Detail
    // --------------------------------------------------

    /**
     * Tests that the detail screen renders for an existing trace.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testDetail_existingRenders() {
        Long id = seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, VALID_BASKET);
        given().when().get("/ui/valuations/" + id)
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("Request"));
    }

    /**
     * Tests that an unknown trace redirects back to the list with an explanatory notice rather
     * than a dead-end error page.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testDetail_unknownRedirectsToList() {
        given().redirects().follow(false)
                .when().get("/ui/valuations/999999")
                .then().statusCode(303)
                .header("location", containsString("notice"))
                .header("location", containsString("noticeOk=false"));
    }

    // --------------------------------------------------
    // Test form
    // --------------------------------------------------

    /**
     * Tests that the test form renders fresh when no replay is requested.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testNewForm_rendersFresh() {
        given().when().get("/ui/valuations/new")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("Test a valuation"));
    }

    /**
     * Tests that requesting a replay preloads the recorded request payload into the form.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testNewForm_replayPreloadsRequest() {
        Long id = seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, VALID_BASKET);
        given().when().get("/ui/valuations/new?replay=" + id)
                .then().statusCode(200).body(containsString(EAN));
    }

    /**
     * Tests that a replay of a trace whose request payload is null falls back to an empty basket,
     * covering the null-payload arm.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testNewForm_replayNullPayloadStaysEmpty() {
        Long id = seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, null);
        given().when().get("/ui/valuations/new?replay=" + id)
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that a replay of an unknown trace falls back to an empty basket, covering the
     * trace-not-found arm.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testNewForm_replayUnknownStaysEmpty() {
        given().when().get("/ui/valuations/new?replay=999999")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    // --------------------------------------------------
    // Test submission
    // --------------------------------------------------

    /**
     * Tests that a valid basket runs through the engine and its evaluation is rendered, covering
     * the success arm of the submission flow.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSubmit_validRendersResult() {
        seedCatalog();
        form().formParam("request", VALID_BASKET)
                .when().post("/ui/valuations/new")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("Result"))
                .body(containsString("totalPrice"));
    }

    /**
     * Tests that a blank basket re-renders the form with the empty-basket error, covering the
     * blank arm of the null-or-blank guard.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSubmit_blankBasketError() {
        form().formParam("request", "")
                .when().post("/ui/valuations/new")
                .then().statusCode(200).body(containsString("The basket is empty."));
    }

    /**
     * Tests that a submission carrying no request parameter re-renders the form with the
     * empty-basket error, covering the null arm of the null-or-blank guard.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSubmit_nullBasketError() {
        form().when().post("/ui/valuations/new")
                .then().statusCode(200).body(containsString("The basket is empty."));
    }

    /**
     * Tests that a schema-valid basket the engine refuses re-renders the form with the HTTP-status
     * error, covering the {@link jakarta.ws.rs.WebApplicationException} arm.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSubmit_rejectedBasketError() {
        // A schema-invalid basket (a line with neither an EAN nor a price triplet): the
        // valuation resource rejects it with a WebApplicationException, which the form
        // surfaces as an "HTTP nnn" message.
        String rejected = "{\"storeCode\":\"0101\",\"items\":[{\"lineId\":\"L1\",\"quantity\":1}]}";
        form().formParam("request", rejected)
                .when().post("/ui/valuations/new")
                .then().statusCode(200).body(containsString("HTTP "));
    }

    /**
     * Tests that a malformed JSON basket re-renders the form with the parser error, covering the
     * generic-exception arm, and that the message is not the HTTP-status one.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testSubmit_malformedJsonError() {
        form().formParam("request", "not-json")
                .when().post("/ui/valuations/new")
                .then().statusCode(200)
                .body(containsString("alert-error"))
                .body(not(containsString("HTTP ")));
    }

    // --------------------------------------------------
    // Configuration
    // --------------------------------------------------

    /**
     * Tests that a valid configuration update with tracing enabled redirects with a success
     * notice, covering the enabled-present and valid-retention arms.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testConfig_updateEnabledRedirects() {
        form().formParam("enabled", "on").formParam("retentionDays", "7")
                .when().post("/ui/valuations/config")
                .then().statusCode(303)
                .header("location", containsString("noticeOk=true"));
    }

    /**
     * Tests that a valid update without the enabled flag redirects with a success notice, covering
     * the enabled-absent arm that turns tracing off.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testConfig_updateDisabledRedirects() {
        form().formParam("retentionDays", "3")
                .when().post("/ui/valuations/config")
                .then().statusCode(303)
                .header("location", containsString("noticeOk=true"));
    }

    /**
     * Tests that a non-positive retention re-redirects with a failure notice, covering the
     * invalid-retention arm.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testConfig_invalidRetentionRedirects() {
        form().formParam("enabled", "on").formParam("retentionDays", "0")
                .when().post("/ui/valuations/config")
                .then().statusCode(303)
                .header("location", containsString("noticeOk=false"));
    }

    /**
     * Tests that a viewer is forbidden from updating the configuration.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testConfig_forbiddenForViewer() {
        form().formParam("retentionDays", "7")
                .when().post("/ui/valuations/config")
                .then().statusCode(403);
    }

    /**
     * Tests that a manager is forbidden from updating the configuration, covering the second
     * non-admin arm of the method-level role guard.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testConfig_forbiddenForManager() {
        form().formParam("retentionDays", "7")
                .when().post("/ui/valuations/config")
                .then().statusCode(403);
    }

    // --------------------------------------------------
    // Purge
    // --------------------------------------------------

    /**
     * Tests that purging redirects with the deletion count, as an administrator.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testPurge_redirects() {
        seedTrace(STORE, "CUST1", ValuationTrace.STATUS_SUCCESS, VALID_BASKET);
        given().redirects().follow(false)
                .when().post("/ui/valuations/purge")
                .then().statusCode(303)
                .header("location", containsString("deleted"));
    }

    /**
     * Tests that a viewer is forbidden from purging the traces.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testPurge_forbiddenForViewer() {
        given().redirects().follow(false)
                .when().post("/ui/valuations/purge")
                .then().statusCode(403);
    }
}
