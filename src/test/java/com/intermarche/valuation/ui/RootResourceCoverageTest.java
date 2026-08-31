package com.intermarche.valuation.ui;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Coverage test for {@link RootResource}, exercised over real HTTP so the container
 * instruments the redirect endpoint.
 */
@QuarkusTest
public class RootResourceCoverageTest {

    /**
     * Tests that the application root redirects an authenticated caller to the offers screen.
     */
    @Test
    @TestSecurity(user = "test_root", roles = "VIEWER")
    void testRoot_redirectsToOffers() {
        given().redirects().follow(false)
                .when().get("/")
                .then().statusCode(303)
                .header("location", containsString("/ui/offers"));
    }
}
