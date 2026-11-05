package com.intermarche.valuation.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the CSRF protection of the UI (spec-durcissement-web §1): a state-changing form request is
 * refused without a valid token and accepted with one, while the Basic-auth machine APIs stay exempt.
 * <p>
 * The verification is off in the default test profile (so the other {@code /ui} POST tests need no
 * token); this class re-enables it through {@link CsrfEnabledProfile}.
 */
@QuarkusTest
@TestProfile(CsrfFilterTest.CsrfEnabledProfile.class)
public class CsrfFilterTest {

    /**
     * Test profile turning the CSRF verification on for this class.
     */
    public static class CsrfEnabledProfile implements QuarkusTestProfile {

        /**
         * Enables the CSRF verification.
         *
         * @return the config override.
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("valuation.csrf.enabled", "true");
        }
    }

    /**
     * A state-changing UI POST without any CSRF token is refused with a 403.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testStateChangingWithoutToken_forbidden() {
        given().redirects().follow(false)
                .when().post("/ui/valuations/purge")
                .then().statusCode(403);
    }

    /**
     * A GET that renders a form issues the token cookie and echoes it in the hidden field; replaying
     * both on the POST passes the double-submit check.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testStateChangingWithToken_accepted() {
        Response page = given().when().get("/ui/valuations").then().statusCode(200).extract().response();
        String token = page.cookie("csrf-token");
        assertNotNull(token, "the form GET must set the CSRF cookie");
        page.then().body(org.hamcrest.CoreMatchers.containsString(
                "name=\"csrf-token\" value=\"" + token + "\""));
        given().redirects().follow(false)
                .cookie("csrf-token", token)
                .formParam("csrf-token", token)
                .when().post("/ui/valuations/purge")
                .then().statusCode(303);
    }

    /**
     * A token that does not match the cookie is refused.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testMismatchedToken_forbidden() {
        given().redirects().follow(false)
                .cookie("csrf-token", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .formParam("csrf-token", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .when().post("/ui/valuations/purge")
                .then().statusCode(403);
    }

    /**
     * The multipart import carries its token in the query string; a matching cookie passes the
     * double-submit check (the filter's query-parameter branch), a missing one is a 403.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testMultipartImport_queryToken() {
        Response page = given().when().get("/ui/imports").then().statusCode(200).extract().response();
        String token = page.cookie("csrf-token");
        assertNotNull(token);
        given().redirects().follow(false)
                .cookie("csrf-token", token)
                .multiPart("domain", "STORES")
                .multiPart("file", "data.csv",
                        "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY|LATITUDE|LONGITUDE\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/octet-stream")
                .when().post("/ui/imports/run?csrf-token=" + token)
                .then().statusCode(303);
        given().redirects().follow(false)
                .multiPart("domain", "STORES")
                .multiPart("file", "data.csv", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/octet-stream")
                .when().post("/ui/imports/run")
                .then().statusCode(403);
    }

    /**
     * A machine-to-machine Basic-auth API call carries no CSRF token and is not refused by the filter
     * (spec §1: CSRF protects cookie sessions, not API clients).
     */
    @Test
    void testBasicAuthApi_noTokenPasses() {
        String body = given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ __typename }\"}")
                .when().post("/graphql")
                .then().statusCode(200)
                .extract().asString();
        assertFalse(body.contains("Invalid or missing CSRF token"), "GraphQL is exempt from CSRF");
    }
}
