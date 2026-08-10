package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.PasswordResetToken;
import com.intermarche.valuation.domain.util.DateTimeProvider;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The self-service "forgot password" flow: request a reset by e-mail, then set a new
 * password through the single-use link.
 * <p>
 * The request path never reveals whether an address exists, so it cannot be used to
 * enumerate accounts: an unknown, blank or e-mail-less account silently sends nothing, and
 * the screen always answers the same neutral message. The raw token travels only in the
 * mailed link; the database stores its SHA-256 hash. Outside production the Quarkus mailer
 * is mocked, so the mail, and thus the link, is written to the log: the whole flow is
 * exercisable in development without an SMTP server.
 */
@ApplicationScoped
public class PasswordResetService {

    private static final Logger LOGGER = Logger.getLogger(PasswordResetService.class);

    /**
     * The number of random bytes making up a raw token (32 bytes yield 64 hex characters).
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * The random source of the raw tokens.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The mailer sending the reset links, mocked outside production.
     */
    @Inject
    Mailer mailer;

    /**
     * The validity of a reset link, in minutes.
     */
    @ConfigProperty(name = "valuation.reset.token-ttl-minutes", defaultValue = "30")
    int tokenTtlMinutes;

    /**
     * Handles a "forgot password" request for an e-mail address.
     * <p>
     * When an active account carries the address, its pending tokens are invalidated, a
     * fresh hashed token is stored and the reset link is mailed; otherwise nothing happens.
     * The method always returns silently, so the caller cannot distinguish the two cases.
     *
     * @param email   The e-mail address as typed on the public form.
     * @param baseUrl The application base URL (scheme://host[:port]) the link is built
     *                from, derived from the incoming request.
     */
    @Transactional
    public void requestReset(String email, String baseUrl) {
        AppUser user = AppUser.findActiveByEmail(email);
        if (user == null) {
            LOGGER.debug("Password reset requested for an unknown or e-mail-less account; nothing sent");
            return;
        }
        PasswordResetToken.deletePendingFor(user);
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        PasswordResetToken reset = new PasswordResetToken();
        reset.user = user;
        reset.tokenHash = sha256Hex(token);
        reset.expiresAt = DateTimeProvider.now().plusMinutes(tokenTtlMinutes);
        reset.persist();
        String link = trimTrailingSlash(baseUrl) + "/ui/reset?token=" + token;
        mailer.send(Mail.withText(user.email,
                "Valuation admin — reset your password",
                "Hello " + user.getLabel() + ",\n\n"
                        + "A password reset was requested for your account '" + user.username
                        + "'.\n\n"
                        + "To choose a new password, open this link (valid for "
                        + tokenTtlMinutes + " minutes, single use):\n\n"
                        + link + "\n\n"
                        + "If you did not request this, ignore this message: "
                        + "your password stays unchanged."));
        LOGGER.info("Password reset link sent to the address of account '" + user.username + "'");
    }

    /**
     * Consumes a reset link and sets the new password.
     * <p>
     * The token must exist, be unconsumed and unexpired, and the new password must satisfy
     * the policy of {@link AppUser#validatePassword(String)}.
     *
     * @param token       The raw token carried by the link.
     * @param newPassword The new password as typed by the user.
     * @return {@code null} on success, or the error message to display.
     */
    @Transactional
    public String resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            return "This reset link is invalid.";
        }
        PasswordResetToken reset = PasswordResetToken.findByHash(sha256Hex(token.trim()));
        if (reset == null || !reset.isUsableAt(DateTimeProvider.now())) {
            return "This reset link is invalid, already used or expired. Please request a new one.";
        }
        String policyError = AppUser.validatePassword(newPassword);
        if (policyError != null) {
            return policyError;
        }
        AppUser user = reset.user;
        user.setPassword(newPassword);
        // The link owner proved control of the account's inbox, so the password is now
        // theirs alone: no forced change is left pending.
        user.mustChangePassword = false;
        user.persist();
        reset.usedAt = DateTimeProvider.now();
        reset.persist();
        LOGGER.info("Password reset completed for account '" + user.username + "'");
        return null;
    }

    /**
     * Hashes a raw token with SHA-256, hex encoded — the only form ever stored.
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
     * Removes a trailing slash from the base URL so the link concatenation never doubles it.
     *
     * @param baseUrl The base URL, may end with a slash.
     * @return The base URL without a trailing slash.
     */
    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
