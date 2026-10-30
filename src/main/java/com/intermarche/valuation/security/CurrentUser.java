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

    /**
     * Indicates whether the signed-in user may reach the management screens (the consumption
     * exports and their BO screen): the manager or the administrator role.
     *
     * @return {@code true} when the manager or the administrator role is granted.
     */
    public boolean isManager() {
        return hasRole(AppUser.ROLE_MANAGER) || hasRole(AppUser.ROLE_ADMIN);
    }

    /**
     * Whether the current user's row has been looked up yet this request.
     */
    private boolean loaded;

    /**
     * The current user's persisted row, loaded lazily once per request; null when anonymous or the
     * account no longer exists.
     */
    private AppUser cachedUser;

    /**
     * Loads (once per request) and returns the signed-in user's persisted row, which carries the
     * perimeter fields (store scope, allowed offer types, hidden menus).
     *
     * @return the persisted user, or null when anonymous or unknown.
     */
    public AppUser currentAppUser() {
        if (!loaded) {
            loaded = true;
            String name = getName();
            cachedUser = name.isEmpty() ? null : AppUser.findByUsername(name);
        }
        return cachedUser;
    }

    /**
     * Indicates whether a navigation menu is hidden from the signed-in user (spec §2). Used by the
     * layout to drop the link and by the access filter to refuse the screen.
     *
     * @param key the menu key (a value of {@link AppUser#MENU_KEYS}).
     * @return {@code true} when the menu is hidden for this user.
     */
    public boolean isMenuHidden(String key) {
        AppUser user = currentAppUser();
        return user != null && user.isMenuHidden(key);
    }

    /**
     * Indicates whether the signed-in user is confined to a store perimeter.
     *
     * @return {@code true} when a store scope is set on the account.
     */
    public boolean isStoreScoped() {
        AppUser user = currentAppUser();
        return user != null && user.isStoreScoped();
    }

    /**
     * Returns the signed-in user's store perimeter codes.
     *
     * @return the scope codes, empty when unrestricted or anonymous.
     */
    public java.util.Set<String> getStoreScopeSet() {
        AppUser user = currentAppUser();
        return user == null ? new java.util.LinkedHashSet<>() : user.getStoreScopeSet();
    }

    /**
     * Tells whether the signed-in user may target an offer at the given store/group codes (write
     * rule, spec §2). An anonymous or unrestricted user always passes.
     *
     * @param targetCodes the offer's targeted store and store-group codes.
     * @return {@code true} when the targeting is within the user's scope.
     */
    public boolean isStoreTargetingWithinScope(java.util.Collection<String> targetCodes) {
        AppUser user = currentAppUser();
        return user == null || user.isStoreTargetingWithinScope(targetCodes);
    }

    /**
     * Tells whether an offer with the given targeting is visible to the signed-in user (read rule,
     * spec §2). An anonymous or unrestricted user sees everything.
     *
     * @param targetCodes the offer's targeted store and store-group codes.
     * @return {@code true} when the offer is visible.
     */
    public boolean isOfferVisibleForScope(java.util.Collection<String> targetCodes) {
        AppUser user = currentAppUser();
        return user == null || user.isOfferVisibleForScope(targetCodes);
    }

    /**
     * Tells whether the signed-in user may create or modify an offer of a given type (spec §2). An
     * anonymous or unrestricted user may use every type.
     *
     * @param type the offer type.
     * @return {@code true} when the type is allowed.
     */
    public boolean isOfferTypeAllowed(String type) {
        AppUser user = currentAppUser();
        return user == null || user.isOfferTypeAllowed(type);
    }
}
