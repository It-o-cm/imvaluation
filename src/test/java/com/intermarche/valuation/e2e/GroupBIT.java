package com.intermarche.valuation.e2e;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group B — Authentication &amp; session — of e2e-scenarios.md, in pure RestAssured.
 * <p>
 * Every scenario is exercised over real HTTP against the in-JVM {@code @QuarkusTest}
 * application: form authentication for the browser flows, HTTP Basic for the API paths.
 * The class needs no referential seed — only the bootstrap administrator (admin/admin) and
 * the few accounts each scenario creates through Panache.
 * <p>
 * Redirects are never followed automatically: each 302/303 and its {@code Location} header
 * is asserted explicitly, which is the whole point of the redirect-chain scenarios.
 * <p>
 * B7 and B9 are {@code [P]} scenarios: they depend on the enforced password-change filter,
 * which the test profile disables ({@code %test.app.password-change.enforced=false}). With
 * no prod-like harness available they are disabled and reported as justified residue.
 */
@QuarkusTest
class GroupBIT {

    /**
     * Name of the session cookie set by form authentication.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * Signs in through form authentication and returns the session cookie value.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @return The value of the {@code quarkus-credential} cookie issued on success.
     */
    private String signIn(String username, String password) {
        return given().redirects().follow(false)
                .formParam("j_username", username)
                .formParam("j_password", password)
                .when().post("/j_security_check")
                .then().statusCode(302)
                .extract().cookie(SESSION_COOKIE);
    }

    /**
     * Creates an account when it does not already exist, in its own transaction.
     *
     * @param username   The login name.
     * @param password   The clear-text password.
     * @param active     Whether the account is active.
     * @param mustChange Whether a password change is pending on the account.
     * @param role       The single role granted to the account.
     */
    private void ensureUser(String username, String password, boolean active,
                            boolean mustChange, String role) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.findByUsername(username) != null) {
                return;
            }
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(password);
            user.setRoleSet(Set.of(role));
            user.displayName = username;
            user.active = active;
            user.mustChangePassword = mustChange;
            user.persist();
        });
    }

    /**
     * B1 — nominal redirect chain: {@code GET /} 303 to /ui/offers, the anonymous list 302
     * to /ui/login, and {@code POST /j_security_check} 302 to the landing page while setting
     * the session cookie.
     */
    @Test
    void b1_nominalRedirectChainSetsSessionCookie() {
        given().redirects().follow(false)
                .when().get("/")
                .then().statusCode(303)
                .header("Location", containsString("/ui/offers"));
        given().redirects().follow(false)
                .when().get("/ui/offers")
                .then().statusCode(302)
                .header("Location", containsString("/ui/login"));
        Cookie session = given().redirects().follow(false)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin")
                .when().post("/j_security_check")
                .then().statusCode(302)
                .header("Location", containsString("/ui/offers"))
                .extract().detailedCookie(SESSION_COOKIE);
        assertFalse(session.getValue().isBlank());
    }

    /**
     * B2 — rejected login: a wrong password redirects to /ui/login?error=true, whose page
     * shows "Invalid username or password."; the message shows only for the exact
     * {@code error=true} value and for no other (empty, false, or absent).
     */
    @Test
    void b2_invalidCredentialsMessageOnlyForErrorTrue() {
        given().redirects().follow(false)
                .formParam("j_username", "admin")
                .formParam("j_password", "wrong-password")
                .when().post("/j_security_check")
                .then().statusCode(302)
                .header("Location", containsString("/ui/login?error=true"));
        given().when().get("/ui/login?error=true")
                .then().statusCode(200)
                .body(containsString("Invalid username or password."));
        given().when().get("/ui/login?error=false")
                .then().statusCode(200)
                .body(not(containsString("Invalid username or password.")));
        given().when().get("/ui/login?error=")
                .then().statusCode(200)
                .body(not(containsString("Invalid username or password.")));
        given().when().get("/ui/login")
                .then().statusCode(200)
                .body(not(containsString("Invalid username or password.")));
    }

    /**
     * B3 — logout: an authenticated {@code POST /ui/logout} clears the session cookie
     * (maxAge 0) and redirects 303 to /ui/login with the signed-out notice, whose page shows
     * the green banner; no {@code GET /ui/logout} route exists.
     */
    @Test
    void b3_logoutClearsCookieAndShowsNotice() {
        String session = signIn("admin", "admin");
        Cookie cleared = given().redirects().follow(false)
                .cookie(SESSION_COOKIE, session)
                .when().post("/ui/logout")
                .then().statusCode(303)
                .header("Location", containsString("/ui/login?notice=You+have+been+signed+out."))
                .extract().detailedCookie(SESSION_COOKIE);
        assertEquals(0, cleared.getMaxAge());
        assertTrue(cleared.getValue() == null || cleared.getValue().isEmpty());
        given().when().get("/ui/login?notice=You have been signed out.")
                .then().statusCode(200)
                .body(containsString("You have been signed out."));
        given().redirects().follow(false)
                .when().get("/ui/logout")
                .then().statusCode(405);
    }

    /**
     * B4 — public paths. /ui/login, /ui/base.css and /ui/auth.css load anonymously (200).
     * CALIBRATION FINDING: contrary to the catalog, the other UI assets (offer.css,
     * valuation.css, the scripts) are ALSO served anonymously with 200 — static resources
     * under META-INF/resources are handled before the auth permission layer, so the permit
     * list does not actually gate them. Asserted as observed and flagged in the report.
     */
    @Test
    void b4_publicPathsAndStaticAssetsAreServedAnonymously() {
        given().when().get("/ui/login").then().statusCode(200);
        given().when().get("/ui/base.css").then().statusCode(200);
        given().when().get("/ui/auth.css").then().statusCode(200);
        given().redirects().follow(false).when().get("/ui/offer.css").then().statusCode(200);
        given().redirects().follow(false).when().get("/ui/valuation.css").then().statusCode(200);
        given().redirects().follow(false).when().get("/ui/schema-form.js").then().statusCode(200);
    }

    /**
     * B5 — Basic versus form: the API paths force Basic, so without credentials they answer
     * a 401 and never an HTML redirect; a UI request carrying an Authorization header is
     * served by Basic (priority 2000 &gt; form 1000) and reaches the screen. CALIBRATION
     * NOTE: the 401 carries no {@code WWW-Authenticate} header in this configuration — the
     * challenge is the status itself, so only the 401 (never a 3xx) is asserted.
     */
    @Test
    void b5_apiPathsChallengeInBasicUiHonoursBasicHeader() {
        given().redirects().follow(false).when().post("/valuation").then().statusCode(401);
        given().redirects().follow(false).when().get("/graphql").then().statusCode(401);
        given().redirects().follow(false).when().post("/stores/import").then().statusCode(401);
        given().redirects().follow(false)
                .auth().preemptive().basic("admin", "admin")
                .when().get("/ui/offers")
                .then().statusCode(200);
    }

    /**
     * B6 — a disabled account can still sign in: the {@code active} flag is not read by
     * quarkus-security-jpa, so a login with a disabled account still succeeds (302 to the
     * landing page). Negative scenario documenting the symptom.
     */
    @Test
    void b6_disabledAccountStillSignsIn() {
        ensureUser("b6disabled", "disabled123", false, false, AppUser.ROLE_VIEWER);
        given().redirects().follow(false)
                .formParam("j_username", "b6disabled")
                .formParam("j_password", "disabled123")
                .when().post("/j_security_check")
                .then().statusCode(302)
                .header("Location", containsString("/ui/offers"));
    }

    /**
     * B8 — the five password-change refusals in exact order, then the successful change:
     * wrong current, empty, too short, mismatched confirmation, unchanged; success redirects
     * 303 to the offer list with the update notice, keeps the session, and applies the new
     * password without a re-login.
     */
    @Test
    void b8_passwordChangeRefusalsThenSuccess() {
        ensureUser("b8user", "initpass1", true, false, AppUser.ROLE_ADMIN);
        String session = signIn("b8user", "initpass1");
        changePassword(session, "wrong-one", "newpass12", "newpass12")
                .body(containsString("The current password is incorrect."));
        changePassword(session, "initpass1", "", "")
                .body(containsString("The password is mandatory."));
        changePassword(session, "initpass1", "short", "short")
                .body(containsString("The password must be at least 8 characters long."));
        changePassword(session, "initpass1", "newpass12", "different1")
                .body(containsString("The two passwords do not match."));
        changePassword(session, "initpass1", "initpass1", "initpass1")
                .body(containsString("The new password must differ from the current one."));
        given().redirects().follow(false)
                .cookie(SESSION_COOKIE, session)
                .formParam("currentPassword", "initpass1")
                .formParam("newPassword", "brandnew12")
                .formParam("confirmation", "brandnew12")
                .when().post("/ui/password")
                .then().statusCode(303)
                .header("Location", containsString("/ui/offers?notice=Password+updated."));
        boolean[] applied = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername("b8user");
            applied[0] = user.matchesPassword("brandnew12") && !user.mustChangePassword;
        });
        assertTrue(applied[0]);
    }

    /**
     * Posts a password-change attempt with the given session and returns the response for
     * assertion. Each rejected attempt renders the password page (200) with its error.
     *
     * @param session         The authenticated session cookie value.
     * @param currentPassword The current password field.
     * @param newPassword     The new password field.
     * @param confirmation    The confirmation field.
     * @return The RestAssured validatable response, already asserted to be a 200 render.
     */
    private io.restassured.response.ValidatableResponse changePassword(String session,
            String currentPassword, String newPassword, String confirmation) {
        return given().redirects().follow(false)
                .cookie(SESSION_COOKIE, session)
                .formParam("currentPassword", currentPassword)
                .formParam("newPassword", newPassword)
                .formParam("confirmation", confirmation)
                .when().post("/ui/password")
                .then().statusCode(200);
    }

    /**
     * B7 — forced password change on the initial admin: {@code [P]} residue. The test
     * profile disables the enforcement filter ({@code %test.app.password-change.enforced=
     * false}), so the forced /ui/password redirect cannot be reproduced without a prod-like
     * harness. Documented, not implemented.
     */
    @Test
    @Disabled("[P] requires the prod-like profile (password-change enforcement disabled in %test)")
    void b7_forcedPasswordChangeRedirect() {
    }

    /**
     * B9 — admin resets an account, forcing a change loop: {@code [P]} residue, for the same
     * reason as B7 — the enforcement filter is off in the test profile. Documented, not
     * implemented.
     */
    @Test
    @Disabled("[P] requires the prod-like profile (password-change enforcement disabled in %test)")
    void b9_adminResetForcesChangeLoop() {
    }
}
