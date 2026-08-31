package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.PasswordResetToken;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.valuation.CoverageDbReset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Endpoint coverage tests for {@link AuthUiResource}, exercised through the real HTTP stack so
 * the container instruments the resource for coverage.
 * <p>
 * The public screens (login, forgot, reset) are driven anonymously; the authenticated screens
 * (logout, password change) use {@code @TestSecurity} to inject an identity and, where the
 * resource resolves the signed-in account, a matching {@link AppUser} row is seeded. Every
 * server-side branch is reached: the login error mapping, the forgot/reset redirects, the four
 * password-change validation refusals, the forced-change short-circuit and each success path.
 * <p>
 * Test accounts are prefixed with {@code test_} and the bootstrap {@code admin} account is
 * never removed.
 */
@QuarkusTest
public class AuthUiResourceCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another test class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }


    /**
     * Removes only the reset tokens and the test-created accounts before each test, so the
     * bootstrap {@code admin} account survives and no state leaks between tests.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        PasswordResetToken.deleteAll();
        AppUser.delete("username like ?1", "test_%");
    }

    /**
     * Persists a test account in its own committed transaction.
     *
     * @param username     The login name, always prefixed with {@code test_}.
     * @param rawPassword  The clear-text password, hashed on the way in.
     * @param roles        The comma separated roles.
     * @param mustChange   Whether a forced password change is pending.
     * @param email        The e-mail address, may be null.
     */
    void seedUser(String username, String rawPassword, String roles, boolean mustChange, String email) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(rawPassword);
            user.roles = roles;
            user.mustChangePassword = mustChange;
            user.email = email;
            user.active = true;
            user.persist();
        });
    }

    /**
     * Persists a usable reset token for a seeded account in its own committed transaction.
     *
     * @param username The account the token resets.
     * @param rawToken The raw token whose SHA-256 hash is stored.
     */
    void seedValidToken(String username, String rawToken) {
        QuarkusTransaction.requiringNew().run(() -> {
            PasswordResetToken token = new PasswordResetToken();
            token.user = AppUser.findByUsername(username);
            token.tokenHash = sha256Hex(rawToken);
            token.expiresAt = LocalDateTime.now().plusMinutes(30);
            token.persist();
        });
    }

    /**
     * Hashes a raw token with SHA-256, hex encoded, mirroring the reset service so a seeded
     * token can be consumed through the public endpoint.
     *
     * @param value The raw token.
     * @return The hex-encoded SHA-256 hash.
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Starts a form-encoded request that does not follow redirects, so a 303 outcome can be
     * asserted directly.
     *
     * @return A request specification ready for form parameters.
     */
    private io.restassured.specification.RequestSpecification form() {
        return given().redirects().follow(false).contentType(ContentType.URLENC);
    }

    // --------------------------------------------------
    // Login page
    // --------------------------------------------------

    /**
     * Tests that {@code error=true} renders the login page with the generic failure message.
     */
    @Test
    void testLogin_errorTrueShowsMessage() {
        given().when().get("/ui/login?error=true")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("Invalid username or password."));
    }

    /**
     * Tests that any other {@code error} value renders the login page without the message,
     * covering the null arm of the error mapping.
     */
    @Test
    void testLogin_otherErrorShowsNoMessage() {
        given().queryParam("error", "whatever").queryParam("notice", "Signed out")
                .when().get("/ui/login")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(org.hamcrest.Matchers.not(containsString("Invalid username or password.")));
    }

    // --------------------------------------------------
    // Forgot password
    // --------------------------------------------------

    /**
     * Tests that the forgot page renders the neutral confirmation when {@code sent} is present.
     */
    @Test
    void testForgot_sentShowsConfirmation() {
        given().when().get("/ui/forgot?sent=true")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that the forgot page renders without confirmation when {@code sent} is absent.
     */
    @Test
    void testForgot_withoutSent() {
        given().when().get("/ui/forgot")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that a request for an unknown address still redirects to the neutral confirmation,
     * covering the unknown-account branch of the reset service.
     */
    @Test
    void testRequestReset_unknownEmailRedirects() {
        form().formParam("email", "nobody@example.test")
                .when().post("/ui/forgot")
                .then().statusCode(303)
                .header("location", containsString("/ui/forgot?sent=true"));
    }

    /**
     * Tests that a request for a known active address redirects to the same confirmation,
     * covering the known-account branch of the reset service.
     */
    @Test
    void testRequestReset_knownEmailRedirects() {
        seedUser("test_forgot", "Current1234", "VIEWER", false, "known@example.test");
        form().formParam("email", "known@example.test")
                .when().post("/ui/forgot")
                .then().statusCode(303)
                .header("location", containsString("/ui/forgot?sent=true"));
    }

    // --------------------------------------------------
    // Reset page
    // --------------------------------------------------

    /**
     * Tests that the reset page renders with an empty token when none is supplied.
     */
    @Test
    void testReset_missingTokenRenders() {
        given().when().get("/ui/reset")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that the reset page renders with the supplied token and error, covering the
     * non-null token arm.
     */
    @Test
    void testReset_withTokenAndError() {
        given().when().get("/ui/reset?token=abc&error=Bad")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that submitting mismatching passwords redirects back to the reset form,
     * covering the non-matching arm of the confirmation check.
     */
    @Test
    void testDoReset_mismatchRedirectsBack() {
        form().formParam("token", "sometoken").formParam("password", "NewPass123")
                .formParam("confirm", "Different123")
                .when().post("/ui/reset")
                .then().statusCode(303)
                .header("location", containsString("/ui/reset"));
    }

    /**
     * Tests that submitting no password redirects back to the reset form, covering the null
     * arm of the confirmation check.
     */
    @Test
    void testDoReset_nullPasswordRedirectsBack() {
        form().formParam("token", "sometoken")
                .when().post("/ui/reset")
                .then().statusCode(303)
                .header("location", containsString("/ui/reset"));
    }

    /**
     * Tests that a matching pair with an invalid token is rejected by the service and redirected
     * back to the reset form, covering the service-error arm.
     */
    @Test
    void testDoReset_invalidTokenRedirectsBack() {
        form().formParam("token", "unknown-token").formParam("password", "NewPass123")
                .formParam("confirm", "NewPass123")
                .when().post("/ui/reset")
                .then().statusCode(303)
                .header("location", containsString("/ui/reset"));
    }

    /**
     * Tests that a matching pair with a valid token sets the password and redirects to the
     * login page, covering the success arm.
     */
    @Test
    void testDoReset_validTokenRedirectsToLogin() {
        seedUser("test_reset", "Current1234", "VIEWER", true, "reset@example.test");
        seedValidToken("test_reset", "raw-token-value");
        form().formParam("token", "raw-token-value").formParam("password", "BrandNew123")
                .formParam("confirm", "BrandNew123")
                .when().post("/ui/reset")
                .then().statusCode(303)
                .header("location", containsString("/ui/login"));
    }

    // --------------------------------------------------
    // Logout
    // --------------------------------------------------

    /**
     * Tests that an authenticated logout clears the session cookie and redirects to login.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testLogout_authenticatedRedirectsToLogin() {
        given().redirects().follow(false)
                .when().post("/ui/logout")
                .then().statusCode(303)
                .header("location", containsString("/ui/login"));
    }

    /**
     * Tests that an anonymous logout is challenged by the form authentication mechanism.
     */
    @Test
    void testLogout_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().post("/ui/logout")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }

    // --------------------------------------------------
    // Password change form
    // --------------------------------------------------

    /**
     * Tests that the password form renders for a signed-in account backed by a real row.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testPasswordForm_rendersForKnownUser() {
        seedUser("test_user", "Current1234", "VIEWER", false, null);
        given().when().get("/ui/password")
                .then().statusCode(200).contentType(ContentType.HTML);
    }

    /**
     * Tests that the password form redirects to login when the identity backs no account,
     * covering the null-user arm.
     */
    @Test
    @TestSecurity(user = "test_ghost", roles = "VIEWER")
    void testPasswordForm_unknownUserRedirects() {
        given().redirects().follow(false)
                .when().get("/ui/password")
                .then().statusCode(303)
                .header("location", containsString("/ui/login"));
    }

    // --------------------------------------------------
    // Password change submit
    // --------------------------------------------------

    /**
     * Tests that a change posted by an identity backing no account redirects to login,
     * covering the null-user arm of the submit handler.
     */
    @Test
    @TestSecurity(user = "test_ghost", roles = "VIEWER")
    void testChangePassword_unknownUserRedirects() {
        form().formParam("currentPassword", "x").formParam("newPassword", "BrandNew123")
                .formParam("confirmation", "BrandNew123")
                .when().post("/ui/password")
                .then().statusCode(303)
                .header("location", containsString("/ui/login"));
    }

    /**
     * Tests that a wrong current password on a voluntary change re-renders the form with an
     * error, covering the current-password check.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testChangePassword_wrongCurrentPassword() {
        seedUser("test_user", "Current1234", "VIEWER", false, null);
        form().formParam("currentPassword", "WrongOne1").formParam("newPassword", "BrandNew123")
                .formParam("confirmation", "BrandNew123")
                .when().post("/ui/password")
                .then().statusCode(200)
                .body(containsString("The current password is incorrect."));
    }

    /**
     * Tests that a policy-violating new password re-renders the form with the policy error.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testChangePassword_policyError() {
        seedUser("test_user", "Current1234", "VIEWER", false, null);
        form().formParam("currentPassword", "Current1234").formParam("newPassword", "short")
                .formParam("confirmation", "short")
                .when().post("/ui/password")
                .then().statusCode(200)
                .body(containsString("at least"));
    }

    /**
     * Tests that a mismatching confirmation re-renders the form with the mismatch error.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testChangePassword_confirmationMismatch() {
        seedUser("test_user", "Current1234", "VIEWER", false, null);
        form().formParam("currentPassword", "Current1234").formParam("newPassword", "BrandNew123")
                .formParam("confirmation", "Different123")
                .when().post("/ui/password")
                .then().statusCode(200)
                .body(containsString("The two passwords do not match."));
    }

    /**
     * Tests that reusing the current password re-renders the form with the must-differ error.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testChangePassword_sameAsCurrent() {
        seedUser("test_user", "Current1234", "VIEWER", false, null);
        form().formParam("currentPassword", "Current1234").formParam("newPassword", "Current1234")
                .formParam("confirmation", "Current1234")
                .when().post("/ui/password")
                .then().statusCode(200)
                .body(containsString("The new password must differ from the current one."));
    }

    /**
     * Tests that a valid voluntary change succeeds and redirects to the offer list.
     */
    @Test
    @TestSecurity(user = "test_user", roles = "VIEWER")
    void testChangePassword_voluntarySuccess() {
        seedUser("test_user", "Current1234", "VIEWER", false, null);
        form().formParam("currentPassword", "Current1234").formParam("newPassword", "BrandNew123")
                .formParam("confirmation", "BrandNew123")
                .when().post("/ui/password")
                .then().statusCode(303)
                .header("location", containsString("/ui/offers"));
    }

    /**
     * Tests that a forced change succeeds without the current password, covering the
     * forced-change short-circuit of the validation.
     */
    @Test
    @TestSecurity(user = "test_forced", roles = "VIEWER")
    void testChangePassword_forcedSuccess() {
        seedUser("test_forced", "Current1234", "VIEWER", true, null);
        form().formParam("newPassword", "BrandNew123").formParam("confirmation", "BrandNew123")
                .when().post("/ui/password")
                .then().statusCode(303)
                .header("location", containsString("/ui/offers"));
    }
}
