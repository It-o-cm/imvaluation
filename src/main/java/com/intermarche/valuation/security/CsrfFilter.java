package com.intermarche.valuation.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Verifies the CSRF token on every state-changing browser request (spec-durcissement-web §1).
 * <p>
 * The double-submit check: a state-changing {@code /ui} request must echo the token — in the
 * {@value CsrfTokenManager#HEADER_NAME} header (fetch) or the {@value CsrfTokenManager#FIELD_NAME}
 * form field (HTML form) — and it must equal the {@value CsrfTokenManager#COOKIE_NAME} cookie. A
 * missing or mismatched token is a 403.
 * <p>
 * Exempt, by design and documented in {@code application.properties}: safe methods (GET/HEAD/OPTIONS),
 * the login post {@code /j_security_check} (pre-session, handled by the security layer), and the
 * machine-to-machine APIs authenticated by Basic — GraphQL, the CSV imports, {@code /consumptions} and the
 * stateless return valuation {@code /returns}.
 * CSRF protects cookie sessions, not API clients, which carry no ambient cookie to abuse.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 200)
public class CsrfFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = Logger.getLogger(CsrfFilter.class);

    /**
     * Methods that never change state, so never need a token.
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Whether the verification runs. On by default (production and dev); turned off in the {@code
     * test} profile so the pre-existing {@code /ui} POST tests need no rework — the token cookie and
     * the {@code {#csrf/}} rendering still run everywhere, and {@link CsrfFilterTest} re-enables the
     * verification through a dedicated test profile.
     */
    @ConfigProperty(name = "valuation.csrf.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * Rejects a state-changing browser request whose CSRF token is missing or does not match.
     *
     * @param requestContext the request being filtered.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!enabled) {
            return;
        }
        if (SAFE_METHODS.contains(requestContext.getMethod())) {
            return;
        }
        String path = normalize(requestContext.getUriInfo().getPath());
        if (isExempt(path)) {
            return;
        }
        String cookie = cookieToken(requestContext);
        String submitted = submittedToken(requestContext);
        if (cookie == null || submitted == null || !constantTimeEquals(cookie, submitted)) {
            LOGGER.warnf("CSRF token missing or invalid for %s %s", requestContext.getMethod(), path);
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("Invalid or missing CSRF token.").build());
        }
    }

    /**
     * Normalizes the request path to a leading-slash-free form.
     *
     * @param path the raw path, may be null.
     * @return the normalized path, never null.
     */
    private String normalize(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Tells whether a path is exempt from CSRF: the login post and the Basic-auth machine APIs.
     *
     * @param path the normalized path.
     * @return {@code true} when no CSRF token is required.
     */
    private boolean isExempt(String path) {
        return path.equals("j_security_check")
                || path.equals("graphql")
                || path.equals("valuation")
                || path.endsWith("/import")
                || path.equals("consumptions")
                || path.startsWith("consumptions/")
                || path.startsWith("returns/");
    }

    /**
     * Reads the token carried by the CSRF cookie.
     *
     * @param requestContext the request.
     * @return the cookie token, or null when absent.
     */
    private String cookieToken(ContainerRequestContext requestContext) {
        Cookie cookie = requestContext.getCookies().get(CsrfTokenManager.COOKIE_NAME);
        return cookie == null ? null : cookie.getValue();
    }

    /**
     * Reads the token submitted by the request: the header (fetch), then the query string (the
     * multipart import carries it there), then the {@code x-www-form-urlencoded} hidden field.
     * <p>
     * The url-encoded body is read and the entity stream is put back, so the downstream
     * {@code @FormParam} binding still sees it — classic RESTEasy does not expose the form field to a
     * filter otherwise.
     *
     * @param requestContext the request.
     * @return the submitted token, or null when absent.
     */
    private String submittedToken(ContainerRequestContext requestContext) {
        String header = requestContext.getHeaderString(CsrfTokenManager.HEADER_NAME);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String query = requestContext.getUriInfo().getQueryParameters().getFirst(CsrfTokenManager.FIELD_NAME);
        if (query != null && !query.isBlank()) {
            return query;
        }
        return formFieldToken(requestContext);
    }

    /**
     * Extracts the token from an {@code application/x-www-form-urlencoded} body, buffering and
     * restoring the entity stream.
     *
     * @param requestContext the request.
     * @return the token, or null when the body is not a form or carries no token.
     */
    private String formFieldToken(ContainerRequestContext requestContext) {
        MediaType mediaType = requestContext.getMediaType();
        if (mediaType == null || !mediaType.isCompatible(MediaType.APPLICATION_FORM_URLENCODED_TYPE)) {
            return null;
        }
        try {
            byte[] body = requestContext.getEntityStream().readAllBytes();
            requestContext.setEntityStream(new ByteArrayInputStream(body));
            String form = new String(body, StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (CsrfTokenManager.FIELD_NAME.equals(name)) {
                    String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    return value.isBlank() ? null : value;
                }
            }
            return null;
        } catch (IOException e) {
            LOGGER.warn("Could not read the form body for CSRF verification.", e);
            return null;
        }
    }

    /**
     * Compares two tokens in constant time, to avoid leaking their equality through timing.
     *
     * @param a the first token.
     * @param b the second token.
     * @return {@code true} when equal.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
