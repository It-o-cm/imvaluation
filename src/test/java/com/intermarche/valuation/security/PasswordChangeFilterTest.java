package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PasswordChangeFilter}.
 * <p>
 * Exercises both arms of every branch in {@code filter} (the enforcement flag, the
 * identity null/anonymous/principal chain, the browser-navigation gate, the path
 * normalisation, the allowed-prefix loop and the pending-change decision) and in
 * {@code isBrowserNavigation} (the Authorization and Accept header guards). The static
 * finder {@link AppUser#findByUsername(String)} is stubbed with
 * {@link org.mockito.Mockito#mockStatic}.
 */
public class PasswordChangeFilterTest {

    /**
     * Builds a fully authenticated identity whose principal carries the given name.
     *
     * @param name The login name exposed by the principal.
     * @return A mock identity that is neither null nor anonymous.
     */
    private SecurityIdentity authenticatedIdentity(String name) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(name);
        return identity;
    }

    /**
     * Builds a request context returning the given path and headers.
     *
     * @param path          The value returned by {@code getUriInfo().getPath()}.
     * @param authorization The Authorization header value, may be null.
     * @param accept        The Accept header value, may be null.
     * @return The configured request context mock.
     */
    private ContainerRequestContext requestContext(String path, String authorization, String accept) {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getHeaderString("Authorization")).thenReturn(authorization);
        when(requestContext.getHeaderString("Accept")).thenReturn(accept);
        return requestContext;
    }

    /**
     * Verifies the filter does nothing when enforcement is disabled by configuration.
     */
    @Test
    void testDisabledByConfigurationDoesNothing() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = false;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies the filter does nothing when no identity has been injected.
     */
    @Test
    void testNullIdentityPassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = null;
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies the filter does nothing when the identity is anonymous.
     */
    @Test
    void testAnonymousIdentityPassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        filter.identity = identity;
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies the filter does nothing when the identity carries no principal.
     */
    @Test
    void testNullPrincipalPassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);
        filter.identity = identity;
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies a Basic-authenticated request is treated as an API call and left alone.
     */
    @Test
    void testBasicAuthorizationIsNotBrowserNavigation() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/dashboard", "Basic dXNlcjpwYXNz", MediaType.TEXT_HTML);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies a request with no Accept header is not treated as browser navigation.
     */
    @Test
    void testMissingAcceptHeaderIsNotBrowserNavigation() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext = requestContext("/ui/dashboard", null, null);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies a request accepting only JSON is not treated as browser navigation.
     */
    @Test
    void testNonHtmlAcceptHeaderIsNotBrowserNavigation() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/dashboard", null, MediaType.APPLICATION_JSON);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies an allowed prefix is exempt: a browser hitting the password screen is not
     * redirected, and the finder is never consulted.
     */
    @Test
    void testAllowedPrefixPassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/password", null, MediaType.TEXT_HTML);
        try (MockedStatic<AppUser> mocked = mockStatic(AppUser.class)) {
            filter.filter(requestContext);
            mocked.verifyNoInteractions();
        }
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies a non-Basic Authorization header (e.g. Bearer) still counts as browser
     * navigation, and an allowed prefix reached via a later slot of the loop is exempt.
     */
    @Test
    void testBearerAuthorizationOnAllowedPrefixPassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/logout", "Bearer token", MediaType.TEXT_HTML);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies the filter does nothing when the signed-in account no longer exists.
     */
    @Test
    void testUnknownUserPassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/dashboard", null, MediaType.TEXT_HTML);
        try (MockedStatic<AppUser> mocked = mockStatic(AppUser.class)) {
            mocked.when(() -> AppUser.findByUsername("alice")).thenReturn(null);
            filter.filter(requestContext);
        }
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies the filter does nothing when the account has no pending password change.
     */
    @Test
    void testUserWithoutPendingChangePassesThrough() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/dashboard", null, MediaType.TEXT_HTML);
        AppUser user = new AppUser();
        user.mustChangePassword = false;
        try (MockedStatic<AppUser> mocked = mockStatic(AppUser.class)) {
            mocked.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            filter.filter(requestContext);
        }
        verify(requestContext, never()).abortWith(any());
    }

    /**
     * Verifies a browser navigation to a protected path by an account with a pending
     * password change is redirected to the password screen with a 303 status.
     */
    @Test
    void testPendingChangeRedirectsToPasswordScreen() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("/ui/dashboard", null, MediaType.TEXT_HTML);
        AppUser user = new AppUser();
        user.mustChangePassword = true;
        try (MockedStatic<AppUser> mocked = mockStatic(AppUser.class)) {
            mocked.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            filter.filter(requestContext);
        }
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/password", response.getLocation().toString());
    }

    /**
     * Verifies a path returned without a leading slash is normalised before the exempt
     * prefixes are checked: a relative protected path still redirects a pending account.
     */
    @Test
    void testRelativePathIsNormalisedAndRedirects() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("ui/dashboard", null, MediaType.TEXT_HTML);
        AppUser user = new AppUser();
        user.mustChangePassword = true;
        try (MockedStatic<AppUser> mocked = mockStatic(AppUser.class)) {
            mocked.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            filter.filter(requestContext);
        }
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals("/ui/password", captor.getValue().getLocation().toString());
    }

    /**
     * Verifies a relative allowed path is normalised and then recognised as exempt, so it
     * is not redirected.
     */
    @Test
    void testRelativeAllowedPathIsNormalisedAndExempt() {
        PasswordChangeFilter filter = new PasswordChangeFilter();
        filter.enforced = true;
        filter.identity = authenticatedIdentity("alice");
        ContainerRequestContext requestContext =
                requestContext("ui/password", null, MediaType.TEXT_HTML);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }
}
