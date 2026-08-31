package com.intermarche.valuation.security;

import com.intermarche.valuation.CoverageDbReset;
import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.PasswordResetToken;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Coverage tests for {@link PasswordResetService}, exercised through {@code @QuarkusTest} so
 * the container attributes the executed lines. Reset tokens are created directly with a hash
 * computed the same way the service does, then consumed through the public methods; the
 * base-URL trimming arms are reached by driving the request flow against the mocked mailer.
 */
@QuarkusTest
public class PasswordResetServiceCoverageTest {

    /**
     * The e-mail address of the account seeded for the reset flow.
     */
    private static final String EMAIL = "reset@test.com";

    /**
     * The service under test.
     */
    @Inject
    PasswordResetService service;

    /**
     * Clears the tokens and {@code test_} accounts after this class.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }

    /**
     * Removes every reset token and {@code test_} account before each test, in reverse
     * dependency order, preserving the bootstrap admin.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        PasswordResetToken.deleteAll();
        AppUser.delete("username like ?1", "test_%");
    }

    /**
     * Computes the hex-encoded SHA-256 of a value, mirroring the service's private hashing so
     * a directly created token matches what {@code resetPassword} looks up.
     *
     * @param value The raw token value.
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
     * Commits an active account carrying {@link #EMAIL}.
     */
    private void seedUser() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = "test_reset";
            user.email = EMAIL;
            user.roles = AppUser.ROLE_VIEWER;
            user.active = true;
            user.setPassword("oldsecret1");
            user.persist();
        });
    }

    /**
     * Commits a usable reset token, valid for thirty minutes, hashing the given raw value.
     *
     * @param rawToken The raw token whose hash is stored.
     */
    private void seedToken(String rawToken) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.find("username", "test_reset").firstResult();
            PasswordResetToken token = new PasswordResetToken();
            token.user = user;
            token.tokenHash = sha256Hex(rawToken);
            token.expiresAt = LocalDateTime.now().plusMinutes(30);
            token.persist();
        });
    }

    /**
     * Tests that {@link PasswordResetService#resetPassword(String, String)} rejects a null,
     * blank or unknown token with the expected message.
     */
    @Test
    void testResetPasswordInvalidToken() {
        assertEquals("This reset link is invalid.", service.resetPassword(null, "whatever8"));
        assertEquals("This reset link is invalid.", service.resetPassword("   ", "whatever8"));
        assertEquals("This reset link is invalid, already used or expired. Please request a new one.",
                service.resetPassword("no-such-token", "whatever8"));
    }

    /**
     * Tests that a usable token yields the policy error for a too-short password, then
     * completes the reset for a compliant one.
     */
    @Test
    void testResetPasswordPolicyErrorThenSuccess() {
        seedUser();
        seedToken("raw-token-A");
        assertEquals("The password must be at least 8 characters long.",
                service.resetPassword("raw-token-A", "short"));
        assertNull(service.resetPassword("raw-token-A", "ValidPassw0rd"));
    }

    /**
     * Tests that {@link PasswordResetService#requestReset(String, String)} silently does
     * nothing for an address that no active account carries.
     */
    @Test
    void testRequestResetUnknownEmailDoesNothing() {
        service.requestReset("nobody@nowhere.test", "http://host");
        long tokens = QuarkusTransaction.requiringNew().call(() -> PasswordResetToken.count());
        assertEquals(0L, tokens);
    }

    /**
     * Tests the base-URL trimming arms of {@link PasswordResetService#requestReset}: a URL
     * ending with a slash, one without, and a blank one all issue a fresh single-use token.
     */
    @Test
    void testRequestResetTrimsBaseUrl() {
        seedUser();
        service.requestReset(EMAIL, "http://host:8080/");
        service.requestReset(EMAIL, "http://host:8080");
        service.requestReset(EMAIL, "");
        long tokens = QuarkusTransaction.requiringNew().call(() -> PasswordResetToken.count());
        assertEquals(1L, tokens);
    }
}
