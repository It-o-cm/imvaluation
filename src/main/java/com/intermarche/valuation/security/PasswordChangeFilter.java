package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Redirects users who must change their password to the password screen.
 * <p>
 * The bootstrap account is created with a password taken from the configuration, and an
 * administrator resetting someone's credentials chooses a temporary one. In both cases
 * the password is known by someone other than its owner, so the account is confined to
 * the password screen until it has been changed.
 * <p>
 * The filter deliberately lets a few paths through: the password screen itself, the sign
 * out action, the login page, and static assets, which would otherwise leave the user
 * facing an unstyled page with no way out.
 * <p>
 * It also applies to browser navigation only. API clients authenticate with a Basic
 * header and expect a payload, not a redirect to an HTML screen, so a pending password
 * change must not break the CSV importers or the valuation endpoint.
 */
@Provider
public class PasswordChangeFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = Logger.getLogger(PasswordChangeFilter.class);

    /**
     * Paths reachable while a password change is pending.
     */
    private static final String[] ALLOWED_PREFIXES = {
            "/ui/password",
            "/ui/logout",
            "/ui/login",
            "/j_security_check"
    };

    /**
     * The identity of the current request.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * Whether a pending password change is enforced.
     * <p>
     * Defaults to {@code true} so existing environments keep forcing the change. Set
     * {@code app.password-change.enforced=false} to disable the redirect without removing
     * the mechanism: the account flag is still honoured everywhere else, only this filter
     * stops acting on it.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "app.password-change.enforced", defaultValue = "true")
    boolean enforced;

    /**
     * Redirects to the password screen when the signed-in account has a pending change.
     *
     * @param requestContext The request being filtered.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Disabled by configuration: leave the redirect off but keep the mechanism intact.
        if (!enforced) {
            return;
        }
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return;
        }
        if (!isBrowserNavigation(requestContext)) {
            return;
        }
        // getPath() is documented as relative to the base URI, but implementations differ
        // on whether it carries a leading slash. Normalising here rather than assuming a
        // shape is what keeps the exempt paths actually exempt: a mismatch would redirect
        // the password screen to itself, forever.
        String path = requestContext.getUriInfo().getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        for (String allowed : ALLOWED_PREFIXES) {
            if (path.startsWith(allowed)) {
                return;
            }
        }
        AppUser user = AppUser.findByUsername(identity.getPrincipal().getName());
        if (user == null || !user.mustChangePassword) {
            return;
        }
        URI target = UriBuilder.fromPath("/ui/password").build();
        LOGGER.debug("Password change pending, redirecting " + path + " to " + target);
        requestContext.abortWith(Response.seeOther(target).build());
    }

    /**
     * Indicates whether the request comes from a browser navigating the interface.
     * <p>
     * Only such requests can act on a redirect to an HTML screen. Anything asking for
     * JSON, or authenticating with a Basic header, is treated as an API call and left
     * alone.
     *
     * @param requestContext The request being filtered.
     * @return {@code true} when the request expects an HTML page.
     */
    private boolean isBrowserNavigation(ContainerRequestContext requestContext) {
        String authorization = requestContext.getHeaderString("Authorization");
        if (authorization != null && authorization.startsWith("Basic ")) {
            return false;
        }
        String accept = requestContext.getHeaderString("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML);
    }
}
