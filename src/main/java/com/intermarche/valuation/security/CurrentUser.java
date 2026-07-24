package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.qute.TemplateData;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Exposes the signed-in identity to Qute templates.
 * <p>
 * Templates reach it through {@code inject:currentUser}, which lets the shared layout
 * adapt the navigation to the granted roles without every resource having to pass the
 * information down explicitly.
 * <p>
 * This only drives what the interface displays. Access itself is enforced server side by
 * the {@code @RolesAllowed} annotations on the resources, so hiding a link is a
 * convenience, never a security measure.
 */
@Named("currentUser")
@RequestScoped
@TemplateData
public class CurrentUser {

    /**
     * The identity resolved by Quarkus Security for the current request.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * Returns the login name of the signed-in user.
     *
     * @return The login name, or an empty string when unauthenticated.
     */
    public String getName() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "";
        }
        return identity.getPrincipal().getName();
    }

    /**
     * Indicates whether someone is signed in.
     *
     * @return {@code true} when the request carries an identity.
     */
    public boolean isAuthenticated() {
        return !getName().isEmpty();
    }

    /**
     * Indicates whether the signed-in user holds a given role.
     *
     * @param role The role to test.
     * @return {@code true} when the role is granted.
     */
    public boolean hasRole(String role) {
        return identity != null && identity.hasRole(role);
    }

    /**
     * Indicates whether the signed-in user may administer the application.
     *
     * @return {@code true} when the administrator role is granted.
     */
    public boolean isAdmin() {
        return hasRole(AppUser.ROLE_ADMIN);
    }
}
