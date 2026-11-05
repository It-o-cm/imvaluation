package com.intermarche.valuation.security;

import io.quarkus.qute.TemplateData;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Per-session CSRF token, exposed to Qute as {@code inject:csrf} and verified by {@link CsrfFilter}.
 * <p>
 * The scheme is the stateless double-submit cookie: a random token is stored in a cookie
 * ({@link #COOKIE_NAME}) readable by the same origin, and echoed in every state-changing form (a
 * hidden field) or fetch (a header). A forged cross-site request cannot read the cookie to replicate
 * it, so the mismatch is caught. It complements — not replaces — the {@code SameSite=Lax} session
 * cookie: two layers, one against cross-site cookie replay, one against request forgery.
 * <p>
 * {@link #getToken()} reuses the token already carried by the request cookie, or mints a fresh one
 * and sets the cookie on the response the first time a page needs it (typically while a form renders).
 */
@Named("csrf")
@RequestScoped
@TemplateData
public class CsrfTokenManager {

    /**
     * The cookie carrying the token (readable by the page so a fetch can echo it in a header).
     */
    public static final String COOKIE_NAME = "csrf-token";

    /**
     * The hidden form field name a POST form echoes the token in.
     */
    public static final String FIELD_NAME = "csrf-token";

    /**
     * The request header a fetch echoes the token in.
     */
    public static final String HEADER_NAME = "X-CSRF-TOKEN";

    /**
     * The randomness source for the token.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * The current Vert.x request, used to read the request cookie and set the response cookie.
     */
    @Inject
    CurrentVertxRequest request;

    /**
     * The token resolved for this request, memoized once.
     */
    private String token;

    /**
     * Returns the CSRF token for this request, minting and setting the cookie when none exists yet.
     *
     * @return the token; never null nor blank.
     */
    public String getToken() {
        if (token != null) {
            return token;
        }
        RoutingContext context = request == null ? null : request.getCurrent();
        if (context == null) {
            token = generate();
            return token;
        }
        Cookie existing = context.request().getCookie(COOKIE_NAME);
        if (existing != null && existing.getValue() != null && !existing.getValue().isBlank()) {
            token = existing.getValue();
            return token;
        }
        token = generate();
        context.response().addCookie(Cookie.cookie(COOKIE_NAME, token)
                .setPath("/")
                .setHttpOnly(false)
                .setSameSite(CookieSameSite.LAX));
        return token;
    }

    /**
     * Generates a fresh URL-safe random token.
     *
     * @return the token.
     */
    private static String generate() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
