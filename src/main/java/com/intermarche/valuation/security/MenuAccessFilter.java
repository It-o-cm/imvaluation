package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Refuses a {@code /ui/...} screen whose menu the signed-in user has hidden (spec §2, §3.3): a
 * hidden menu is not mere decoration, so its resource is blocked server-side too, not only its link.
 * <p>
 * The filter maps a request path to a stable menu key ({@link AppUser#MENU_KEYS}) and aborts with a
 * 403 when that key is in the user's hidden set. It only ever matches the {@code /ui/<key>} screens;
 * login, password, logout, the API and every other path pass through untouched, and an unrestricted
 * user (no hidden menus) is never affected — regression-free.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 10)
public class MenuAccessFilter implements ContainerRequestFilter {

    /**
     * The signed-in user's perimeter, carrying the hidden-menu set.
     */
    @Inject
    CurrentUser currentUser;

    /**
     * Aborts the request with a 403 when it targets a screen whose menu is hidden for the user.
     *
     * @param requestContext the request being filtered.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null) {
            return;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        for (String key : AppUser.MENU_KEYS) {
            String base = "ui/" + key;
            if ((normalized.equals(base) || normalized.startsWith(base + "/"))
                    && currentUser.isMenuHidden(key)) {
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity("This screen is hidden from your profile.").build());
                return;
            }
        }
    }
}
