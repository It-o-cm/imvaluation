package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.security.PasswordResetService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Objects;

/**
 * Screens handling sign-in, sign-out and password management.
 * <p>
 * Authentication itself is performed by Quarkus form authentication: the login form posts
 * to {@code /j_security_check}, which validates the credentials against the user table and
 * issues the session cookie. This resource only renders the surrounding pages.
 * <p>
 * The login page is deliberately open to anonymous requests; every other screen requires
 * an authenticated identity.
 */
@Path("/ui")
@ApplicationScoped
@RunOnVirtualThread
public class AuthUiResource {

    private static final Logger LOGGER = Logger.getLogger(AuthUiResource.class);

    /**
     * Name of the cookie holding the form authentication session.
     * <p>
     * Clearing it is what actually signs the user out.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The identity of the current request, used to resolve the signed-in account.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * The self-service password reset flow: token issuance, mailing and consumption.
     */
    @Inject
    PasswordResetService passwordReset;

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the login page.
         *
         * @param error   An error message to display, may be null.
         * @param notice  An informational message to display, may be null.
         * @return The template instance to render.
         */
        public static native TemplateInstance login(String error, String notice);

        /**
         * Renders the password change page.
         *
         * @param user   The signed-in account.
         * @param forced Whether the change is mandatory before reaching other screens.
         * @param error  An error message to display, may be null.
         * @return The template instance to render.
         */
        public static native TemplateInstance password(AppUser user, boolean forced, String error);

        /**
         * Renders the "forgot password" request page.
         *
         * @param sent Whether to show the neutral "link sent" confirmation.
         * @return The template instance to render.
         */
        public static native TemplateInstance forgot(boolean sent);

        /**
         * Renders the "new password" page reached through the mailed link.
         *
         * @param token The raw reset token carried by the link.
         * @param error An error message to display, may be null.
         * @return The template instance to render.
         */
        public static native TemplateInstance reset(String token, String error);
    }

    // --------------------------------------------------
    // Sign in and out
    // --------------------------------------------------

    /**
     * Displays the login page.
     * <p>
     * Quarkus redirects here with {@code error=true} when credentials are rejected, and
     * the sign-out action redirects here with a confirmation message.
     *
     * @param error  Present when the previous attempt failed.
     * @param notice An informational message to display, may be null.
     * @return The rendered login page.
     */
    @GET
    @Path("/login")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login(@QueryParam("error") String error,
                                  @QueryParam("notice") String notice) {
        return Templates.login(loginErrorMessage(error), notice);
    }

    /**
     * Resolves the failure message shown on the login page for the {@code error} parameter.
     * <p>
     * Quarkus form authentication redirects here with exactly {@code error=true} when it
     * rejects credentials. Only that value shows the generic failure message; any other
     * value, an empty one included, shows nothing rather than reacting to a stray or
     * hand-typed {@code error} query parameter.
     *
     * @param error The raw {@code error} query parameter, may be null.
     * @return The message to display, or {@code null} to display none.
     */
    static String loginErrorMessage(String error) {
        return "true".equals(error) ? "Invalid username or password." : null;
    }

    // --------------------------------------------------
    // Forgot password
    // --------------------------------------------------

    /**
     * Displays the "forgot password" request page.
     *
     * @param sent Present after a request was submitted, to show the neutral confirmation.
     * @return The rendered request page.
     */
    @GET
    @Path("/forgot")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgot(@QueryParam("sent") String sent) {
        return Templates.forgot(sent != null);
    }

    /**
     * Handles a "forgot password" request.
     * <p>
     * The outcome is deliberately not revealed: whether or not the address matches an
     * account, the request always redirects to the same confirmation, so the page cannot
     * be used to tell which addresses have accounts.
     *
     * @param email   The e-mail address typed on the form.
     * @param uriInfo The request URI, used to build the link with the host that served it.
     * @return A redirection back to the request page with the confirmation shown.
     */
    @POST
    @Path("/forgot")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response requestReset(@FormParam("email") String email, @Context UriInfo uriInfo) {
        String baseUrl = Objects.toString(uriInfo.getBaseUri(), "");
        passwordReset.requestReset(email, baseUrl);
        return Response.seeOther(URI.create("/ui/forgot?sent=true")).build();
    }

    /**
     * Displays the "new password" page reached through the mailed link.
     *
     * @param token The raw reset token carried by the link, may be null.
     * @param error An error message to display, may be null.
     * @return The rendered reset page.
     */
    @GET
    @Path("/reset")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance reset(@QueryParam("token") String token, @QueryParam("error") String error) {
        return Templates.reset(token == null ? "" : token, error);
    }

    /**
     * Consumes a reset link and sets the new password.
     * <p>
     * The confirmation match is checked here; every other rule (token validity, password
     * policy) is delegated to the reset service. On success the user is sent back to the
     * login page with a confirmation, so they sign in with the new password.
     *
     * @param token    The raw token carried by the link.
     * @param password The new password.
     * @param confirm  The new password, typed a second time.
     * @return A redirection to the login page on success, back to the reset form otherwise.
     */
    @POST
    @Path("/reset")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response doReset(@FormParam("token") String token,
                            @FormParam("password") String password,
                            @FormParam("confirm") String confirm) {
        if (password == null || !password.equals(confirm)) {
            return redirectToReset(token, "The two passwords do not match.");
        }
        String error = passwordReset.resetPassword(token, password);
        if (error != null) {
            return redirectToReset(token, error);
        }
        URI target = UriBuilder.fromPath("/ui/login")
                .queryParam("notice", "Your password has been changed. You can sign in.")
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Redirects back to the reset form, carrying the token and an error to display.
     *
     * @param token The raw token to keep in the form, may be null.
     * @param error The error message to display.
     * @return A 303 See Other response to the reset form.
     */
    private Response redirectToReset(String token, String error) {
        URI target = UriBuilder.fromPath("/ui/reset")
                .queryParam("token", token == null ? "" : token)
                .queryParam("error", error)
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Signs the user out by clearing the session cookie.
     * <p>
     * Form authentication keeps no server-side session, so expiring the cookie is enough
     * to end the session.
     *
     * @return A redirection to the login page.
     */
    @POST
    @Path("/logout")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response logout() {
        LOGGER.debug("Signing out " + currentUsername());
        NewCookie cleared = new NewCookie.Builder(SESSION_COOKIE)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        URI target = UriBuilder.fromPath("/ui/login")
                .queryParam("notice", "You have been signed out.")
                .build();
        return Response.seeOther(target).cookie(cleared).build();
    }

    // --------------------------------------------------
    // Password management
    // --------------------------------------------------

    /**
     * Displays the password change page for the signed-in user.
     *
     * @return The rendered page, or a redirection to the login page when the account
     *         backing the identity no longer exists.
     */
    @GET
    @Path("/password")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response passwordForm() {
        AppUser user = currentUser();
        if (user == null) {
            return Response.seeOther(URI.create("/ui/login")).build();
        }
        return Response.ok(Templates.password(user, user.mustChangePassword, null)).build();
    }

    /**
     * Changes the password of the signed-in user.
     * <p>
     * The current password is required for a voluntary change, where an unattended
     * session could otherwise be used to lock the real owner out. It is not required
     * when the change is forced: the user has just typed that password to get here, and
     * asking for it again serves no purpose.
     *
     * @param currentPassword The password currently in use.
     * @param newPassword     The requested password.
     * @param confirmation    The requested password, typed a second time.
     * @return A redirection to the offer list on success, the form again otherwise.
     */
    @POST
    @Path("/password")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response changePassword(@FormParam("currentPassword") String currentPassword,
                                   @FormParam("newPassword") String newPassword,
                                   @FormParam("confirmation") String confirmation) {
        AppUser user = currentUser();
        if (user == null) {
            return Response.seeOther(URI.create("/ui/login")).build();
        }
        String error = validateChange(user, currentPassword, newPassword, confirmation);
        if (error != null) {
            return Response.ok(Templates.password(user, user.mustChangePassword, error)).build();
        }
        user.setPassword(newPassword);
        user.mustChangePassword = false;
        LOGGER.info("Password changed for " + user.username);
        // The session cookie carries a signed identity rather than the credentials, so it
        // stays valid: there is no reason to sign the user out of a session they just used.
        URI target = UriBuilder.fromPath("/ui/offers")
                .queryParam("notice", "Password updated.")
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Validates a password change request.
     *
     * @param user            The account being updated.
     * @param currentPassword The password currently in use, as typed.
     * @param newPassword     The requested password.
     * @param confirmation    The requested password, typed a second time.
     * @return An error message when the change must be refused, {@code null} otherwise.
     */
    private String validateChange(AppUser user, String currentPassword,
                                  String newPassword, String confirmation) {
        // On a forced change the user authenticated moments ago, so the current password
        // is not asked for and must not be checked.
        if (!user.mustChangePassword && !user.matchesPassword(currentPassword)) {
            return "The current password is incorrect.";
        }
        String policyError = AppUser.validatePassword(newPassword);
        if (policyError != null) {
            return policyError;
        }
        if (!newPassword.equals(confirmation)) {
            return "The two passwords do not match.";
        }
        if (user.matchesPassword(newPassword)) {
            return "The new password must differ from the current one.";
        }
        return null;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Returns the account backing the current identity.
     *
     * @return The signed-in account, or null when it cannot be resolved.
     */
    private AppUser currentUser() {
        String username = currentUsername();
        return username.isEmpty() ? null : AppUser.findByUsername(username);
    }

    /**
     * Returns the login name of the signed-in user.
     *
     * @return The login name, or an empty string when unauthenticated.
     */
    private String currentUsername() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "";
        }
        return identity.getPrincipal().getName();
    }
}
