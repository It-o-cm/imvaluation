package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Coverage tests for {@link PasswordChangeFilter}, run under a profile that re-enables the
 * pending-password-change enforcement (disabled by default in the test profile).
 * <p>
 * Every arm of the filter is exercised over real HTTP: the anonymous short-circuit, the
 * non-browser (Basic header / non-HTML accept) short-circuits, the exempt-path pass-through,
 * the unknown and up-to-date user pass-throughs, and the redirect to the password screen for
 * an account that still owes a password change.
 */
@QuarkusTest
@TestProfile(PasswordChangeFilterCoverageTest.EnforcedProfile.class)
public class PasswordChangeFilterCoverageTest {

    /**
     * Test profile that turns the pending-password-change enforcement on.
     */
    public static class EnforcedProfile implements QuarkusTestProfile {

        /**
         * Enables the filter's redirect enforcement for this test's boot.
         *
         * @return The configuration override enabling enforcement.
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.password-change.enforced", "true");
        }
    }

    /**
     * Removes the test-created accounts before each test so the seeded state is deterministic.
     */
    @BeforeEach
    @Transactional
    void cleanUsers() {
        AppUser.delete("username like ?1", "test_%");
    }

    /**
     * Persists an account with the given pending-change flag.
     *
     * @param username         The account login, prefixed {@code test_}.
     * @param mustChange Whether the account still owes a password change.
     */
    void seedUser(String username, boolean mustChange) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = username;
            user.password = "hash";
            user.roles = AppUser.ROLE_VIEWER;
            user.displayName = username;
            user.active = true;
            user.mustChangePassword = mustChange;
            user.persist();
        });
    }

    /**
     * Tests that an anonymous request is left untouched by the filter (identity short-circuit).
     */
    @Test
    void testAnonymous_passesThrough() {
        given().accept("text/html").redirects().follow(false)
                .when().get("/ui/login")
                .then().statusCode(200);
    }

    /**
     * Tests that a Basic-authenticated (API) request from an account owing a password change — the
     * bootstrap admin at its initial password — is denied with 403 rather than passed through
     * (report H4): the confinement now covers the API, not only the browser.
     */
    @Test
    void testBasicHeader_pendingChangeIsDeniedWith403() {
        given().auth().preemptive().basic("admin", "admin").accept("text/html")
                .redirects().follow(false)
                .when().get("/ui/offers")
                .then().statusCode(403);
    }

    /**
     * Tests that a non-HTML accept header marks the request as an API call and is left
     * untouched (non-browser short-circuit).
     */
    @Test
    @TestSecurity(user = "test_json", roles = "VIEWER")
    void testNonHtmlAccept_passesThrough() {
        given().accept("application/json")
                .when().get("/ui/lookup/products?q=abc")
                .then().statusCode(200);
    }

    /**
     * Tests that an exempt path (the password screen) is reached even while a change is
     * pending, so the user is never trapped.
     */
    @Test
    @TestSecurity(user = "test_exempt", roles = "VIEWER")
    void testExemptPath_passesThrough() {
        seedUser("test_exempt", true);
        given().accept("text/html").redirects().follow(false)
                .when().get("/ui/password")
                .then().statusCode(200);
    }

    /**
     * Tests that a browser request from an unknown account passes through (no user row).
     */
    @Test
    @TestSecurity(user = "test_ghost", roles = "VIEWER")
    void testUnknownUser_passesThrough() {
        given().accept("text/html").redirects().follow(false)
                .when().get("/ui/offers")
                .then().statusCode(200);
    }

    /**
     * Tests that a browser request from an up-to-date account passes through.
     */
    @Test
    @TestSecurity(user = "test_ok", roles = "VIEWER")
    void testUpToDateUser_passesThrough() {
        seedUser("test_ok", false);
        given().accept("text/html").redirects().follow(false)
                .when().get("/ui/offers")
                .then().statusCode(200);
    }

    /**
     * Tests that a browser request from an account owing a password change is redirected to
     * the password screen.
     */
    @Test
    @TestSecurity(user = "test_force", roles = "VIEWER")
    void testPendingChange_redirectsToPasswordScreen() {
        seedUser("test_force", true);
        given().accept("text/html").redirects().follow(false)
                .when().get("/ui/offers")
                .then().statusCode(303)
                .header("location", containsString("/ui/password"));
    }
}
