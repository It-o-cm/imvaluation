package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link CurrentUser} template helper.
 * Covers both arms of every null guard and boolean short-circuit in getName,
 * isAuthenticated, hasRole and isAdmin.
 */
public class CurrentUserTest {

    /**
     * Verifies getName returns an empty string when no identity has been injected.
     */
    @Test
    void testGetNameReturnsEmptyWhenIdentityNull() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.identity = null;
        assertEquals("", currentUser.getName());
    }

    /**
     * Verifies getName returns an empty string when the identity is anonymous.
     */
    @Test
    void testGetNameReturnsEmptyWhenAnonymous() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        currentUser.identity = identity;
        assertEquals("", currentUser.getName());
    }

    /**
     * Verifies getName returns an empty string when the identity carries no principal.
     */
    @Test
    void testGetNameReturnsEmptyWhenPrincipalNull() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);
        currentUser.identity = identity;
        assertEquals("", currentUser.getName());
    }

    /**
     * Verifies getName returns the principal login name for an authenticated identity.
     */
    @Test
    void testGetNameReturnsPrincipalName() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("alice");
        currentUser.identity = identity;
        assertEquals("alice", currentUser.getName());
    }

    /**
     * Verifies isAuthenticated is false when getName resolves to an empty string.
     */
    @Test
    void testIsAuthenticatedFalseWhenNameEmpty() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.identity = null;
        assertFalse(currentUser.isAuthenticated());
    }

    /**
     * Verifies isAuthenticated is true when getName resolves to a non-empty name.
     */
    @Test
    void testIsAuthenticatedTrueWhenNamePresent() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("bob");
        currentUser.identity = identity;
        assertTrue(currentUser.isAuthenticated());
    }

    /**
     * Verifies hasRole is false when no identity has been injected.
     */
    @Test
    void testHasRoleFalseWhenIdentityNull() {
        CurrentUser currentUser = new CurrentUser();
        currentUser.identity = null;
        assertFalse(currentUser.hasRole("ANY"));
    }

    /**
     * Verifies hasRole is false when the identity does not grant the role.
     */
    @Test
    void testHasRoleFalseWhenRoleAbsent() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole("MANAGER")).thenReturn(false);
        currentUser.identity = identity;
        assertFalse(currentUser.hasRole("MANAGER"));
    }

    /**
     * Verifies hasRole is true when the identity grants the role.
     */
    @Test
    void testHasRoleTrueWhenRoleGranted() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole("MANAGER")).thenReturn(true);
        currentUser.identity = identity;
        assertTrue(currentUser.hasRole("MANAGER"));
    }

    /**
     * Verifies isAdmin is true when the administrator role is granted.
     */
    @Test
    void testIsAdminTrueWhenAdminRoleGranted() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole(AppUser.ROLE_ADMIN)).thenReturn(true);
        currentUser.identity = identity;
        assertTrue(currentUser.isAdmin());
    }

    /**
     * Verifies isAdmin is false when the administrator role is not granted.
     */
    @Test
    void testIsAdminFalseWhenAdminRoleAbsent() {
        CurrentUser currentUser = new CurrentUser();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole(AppUser.ROLE_ADMIN)).thenReturn(false);
        currentUser.identity = identity;
        assertFalse(currentUser.isAdmin());
    }
}
