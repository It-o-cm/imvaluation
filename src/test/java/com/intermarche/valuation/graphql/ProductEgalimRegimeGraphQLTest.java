package com.intermarche.valuation.graphql;

import com.intermarche.valuation.CoverageDbReset;
import com.intermarche.valuation.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GraphQL exposure tests for the {@code egalimRegime} field of {@link ProductResource},
 * exercised over the real {@code /graphql} transport so the schema mapping — not only the
 * Java field — is verified end to end.
 * <p>
 * The four contract cases of the spec are covered: the field is readable and defaults to
 * {@code EXEMPT}, the three enum values are writable and read back, an unknown value is
 * rejected with the same clean error the other enums produce, and an update omitting the
 * field leaves the product's classification untouched.
 * <p>
 * The {@code /graphql} endpoint is pinned to the Basic auth mechanism by the API permission,
 * so the tests authenticate with the bootstrap {@code admin} account (which holds VIEWER,
 * MANAGER and ADMIN) rather than {@code @TestSecurity}. The products created here are committed
 * by the HTTP calls, so the shared in-memory catalog is cleared before each test and after the
 * class.
 */
@QuarkusTest
public class ProductEgalimRegimeGraphQLTest {

    /**
     * Clears the shared database after this class so its committed products cannot collide
     * with another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }

    /**
     * Removes every product before each test so a committed row cannot skew EAN uniqueness.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        Product.deleteAll();
    }

    /**
     * Posts a GraphQL document and returns the raw response body for assertions.
     *
     * @param query The GraphQL query or mutation document, with inner quotes escaped.
     * @return The response body as a string.
     */
    private String graphql(String query) {
        return given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON)
                .body("{\"query\":\"" + query + "\"}")
                .when().post("/graphql")
                .then().statusCode(200)
                .extract().asString();
    }

    /**
     * Tests that a product created without an EGAlim regime reads back as {@code EXEMPT},
     * proving both the query exposure of the field and its fail-open default, through
     * {@code productByEan} and {@code allProducts}.
     */
    @Test
    void testRead_defaultRegimeIsExempt() {
        graphql("mutation { createProduct(input: {ean: \\\"EGDEF\\\", name: \\\"Egalim Default\\\", "
                + "productType: \\\"UNIT\\\"}) { id } }");
        String byEan = graphql("{ productByEan(ean: \\\"EGDEF\\\") { ean egalimRegime } }");
        assertTrue(byEan.contains("\"egalimRegime\":\"EXEMPT\""),
                "productByEan should expose the default EXEMPT regime: " + byEan);
        String all = graphql("{ allProducts { ean egalimRegime } }");
        assertTrue(all.contains("\"egalimRegime\":\"EXEMPT\""),
                "allProducts should expose the default EXEMPT regime: " + all);
    }

    /**
     * Tests that each of the three EGAlim regimes is accepted on creation and read back
     * verbatim, exercising the write path for {@code FOOD_34}, {@code DPH_40} and
     * {@code EXEMPT}.
     */
    @Test
    void testWrite_allThreeRegimesRoundTrip() {
        assertRegimeRoundTrip("EGFOOD", "FOOD_34");
        assertRegimeRoundTrip("EGDPH", "DPH_40");
        assertRegimeRoundTrip("EGEX", "EXEMPT");
    }

    /**
     * Creates a product carrying an explicit regime and asserts it reads back with that value.
     *
     * @param ean    The EAN of the product to create.
     * @param regime The EGAlim regime name to write and expect back.
     */
    private void assertRegimeRoundTrip(String ean, String regime) {
        graphql("mutation { createProduct(input: {ean: \\\"" + ean + "\\\", name: \\\"" + ean
                + " name\\\", productType: \\\"UNIT\\\", egalimRegime: \\\"" + regime + "\\\"}) { id } }");
        String read = graphql("{ productByEan(ean: \\\"" + ean + "\\\") { egalimRegime } }");
        assertTrue(read.contains("\"egalimRegime\":\"" + regime + "\""),
                "Product " + ean + " should read back regime " + regime + ": " + read);
    }

    /**
     * Tests that an unknown EGAlim regime is rejected with the clean generic error the other
     * enums produce, and that no product is created as a side effect.
     */
    @Test
    void testWrite_unknownRegimeRejected() {
        String response = graphql("mutation { createProduct(input: {ean: \\\"EGBAD\\\", "
                + "name: \\\"Egalim Bad\\\", productType: \\\"UNIT\\\", egalimRegime: \\\"BOGUS\\\"}) { id } }");
        assertTrue(response.contains("An error occurred during createProduct."),
                "An unknown regime should yield the clean generic error: " + response);
        String read = graphql("{ productByEan(ean: \\\"EGBAD\\\") { id } }");
        assertFalse(read.contains("\"id\""),
                "No product should have been created for the rejected regime: " + read);
    }

    /**
     * Tests that an update omitting the EGAlim regime leaves the product's classification
     * untouched, following the optional-field pattern of the other mutation inputs.
     */
    @Test
    void testUpdate_withoutRegimeKeepsValue() {
        String created = graphql("mutation { createProduct(input: {ean: \\\"EGKEEP\\\", "
                + "name: \\\"Egalim Keep\\\", productType: \\\"UNIT\\\", egalimRegime: \\\"FOOD_34\\\"}) { id } }");
        String id = extractId(created);
        graphql("mutation { updateProduct(id: " + id
                + ", input: {name: \\\"Egalim Keep Renamed\\\"}) { id egalimRegime } }");
        String read = graphql("{ productByEan(ean: \\\"EGKEEP\\\") { name egalimRegime } }");
        assertTrue(read.contains("\"egalimRegime\":\"FOOD_34\""),
                "An update without the regime must keep FOOD_34: " + read);
        assertTrue(read.contains("Egalim Keep Renamed"),
                "The update should still have applied the changed name: " + read);
    }

    /**
     * Extracts the numeric id of a just-created product from a createProduct response body.
     *
     * @param body The response body carrying {@code "createProduct":{"id":N}}.
     * @return The id as a string.
     */
    private String extractId(String body) {
        int idx = body.indexOf("\"id\":");
        int start = idx + 5;
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return body.substring(start, end);
    }
}
