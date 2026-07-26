package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.HashSet;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration screens managing the accounts allowed to sign in.
 * <p>
 * Only administrators may reach these screens: granting roles is itself a privileged
 * operation, and any weaker guard would let a manager escalate their own permissions.
 * <p>
 * Two safety rules are enforced throughout:
 * <ul>
 *   <li>the last active administrator can neither be demoted, disabled nor deleted,
 *       so the application can never become unreachable;</li>
 *   <li>a user cannot delete or disable their own account by accident.</li>
 * </ul>
 */
@Path("/ui/users")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_ADMIN)
public class UserUiResource {

    private static final Logger LOGGER = Logger.getLogger(UserUiResource.class);

    /**
     * Number of accounts displayed per page.
     */
    private static final int PAGE_SIZE = 25;

    /**
     * Base path of the screen, used to build its links.
     */
    private static final String BASE_PATH = "/ui/users";

    /**
     * Sort key ordering accounts by login name.
     */
    private static final String SORT_USERNAME = "username";

    /**
     * Sort key ordering accounts by display name.
     */
    private static final String SORT_DISPLAY = "displayName";

    /**
     * Every sort key accepted by this screen.
     */
    private static final java.util.Set<String> SORTABLE =
            java.util.Set.of(SORT_USERNAME, SORT_DISPLAY);

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the user list screen.
         *
         * @param view      The view model carrying the page, the filter and the sort state.
         * @param allRoles  Every role the application recognises.
         * @param currentUser The login name of the signed-in user.
         * @return The template instance to render.
         */
        public static native TemplateInstance list(ListView<AppUser> view, List<String> allRoles,
                                                   String currentUser);

        /**
         * Renders the user creation or edition form.
         *
         * @param user     The account being edited, or null when creating.
         * @param allRoles Every role the application recognises.
         * @param error    An error message to display, may be null.
         * @return The template instance to render.
         */
        public static native TemplateInstance form(AppUser user, List<String> allRoles, String error);
    }

    // --------------------------------------------------
    // Screens
    // --------------------------------------------------

    /**
     * Displays every account, ordered by login name.
     *
     * @param securityContext The context identifying the signed-in user.
     * @param notice   A message to display, typically the outcome of a previous action.
     * @param noticeOk Whether the message reports a success.
     * @return The rendered list screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@Context SecurityContext securityContext,
                                 @QueryParam("q") String search,
                                 @QueryParam("role") String role,
                                 @QueryParam("sort") @jakarta.ws.rs.DefaultValue(SORT_USERNAME) String sort,
                                 @QueryParam("dir") @jakarta.ws.rs.DefaultValue("asc") String dir,
                                 @QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @jakarta.ws.rs.DefaultValue("true") boolean noticeOk) {
        LOGGER.debug("Entering method list");
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_USERNAME;
        boolean descending = "desc".equalsIgnoreCase(dir);
        PanacheQuery<AppUser> query = queryUsers(search, role, sortKey, descending).page(Page.ofSize(PAGE_SIZE));
        long totalCount = query.count();
        int pageCount = Math.max(1, query.pageCount());
        int currentPage = Math.min(Math.max(page, 1), pageCount);
        List<AppUser> users = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("q", search);
        filters.put("role", role);
        ListView<AppUser> view = new ListView<>(users, BASE_PATH, filters, sortKey, descending,
                currentPage, pageCount, totalCount, PAGE_SIZE, "user", notice, noticeOk,
                securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN));
        return Templates.list(view, AppUser.ALL_ROLES, currentUsername(securityContext));
    }

    /**
     * Builds the query backing the list screen.
     *
     * @param search     A fragment matched against the username and display name, may be null.
     * @param role       A role the account must hold, may be null or blank.
     * @param sort       The validated sort key.
     * @param descending Whether the sort is descending.
     * @return The query, not yet paginated.
     */
    private PanacheQuery<AppUser> queryUsers(String search, String role, String sort, boolean descending) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            int index = params.size() + 1;
            where.append("(lower(username) like ?").append(index)
                    .append(" or lower(displayName) like ?").append(index).append(")");
            params.add("%" + search.trim().toLowerCase() + "%");
        }
        if (role != null && !role.isBlank()) {
            if (where.length() > 0) {
                where.append(" and ");
            }
            where.append("roles like ?").append(params.size() + 1);
            params.add("%" + role.trim() + "%");
        }
        String order = " order by " + sort + (descending ? " desc" : " asc");
        if (!SORT_USERNAME.equals(sort)) {
            order += ", username asc";
        }
        String query = where.length() > 0 ? where + order : order.trim();
        return AppUser.find(query, params.toArray());
    }

    /**
     * Displays an empty form for creating an account.
     *
     * @return The rendered creation form.
     */
    @GET
    @Path("/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance create() {
        LOGGER.debug("Entering method create");
        AppUser user = new AppUser();
        user.active = true;
        return Templates.form(user, AppUser.ALL_ROLES, null);
    }

    /**
     * Displays the edition form of an existing account.
     *
     * @param id The identifier of the account to edit.
     * @return The rendered edition form, or a 404 response when the account does not exist.
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response edit(@PathParam("id") Long id) {
        LOGGER.debug("Entering method edit with id: " + id);
        AppUser user = AppUser.findById(id);
        if (user == null) {
            LOGGER.error("User with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity("User " + id + " not found").build();
        }
        return Response.ok(Templates.form(user, AppUser.ALL_ROLES, null)).build();
    }

    // --------------------------------------------------
    // Mutations
    // --------------------------------------------------

    /**
     * Creates an account from the submitted form.
     *
     * @param username    The login name.
     * @param password    The clear text password, hashed before storage.
     * @param displayName The name shown in the interface, may be blank.
     * @param roles       The granted roles.
     * @param active      Whether the account may sign in.
     * @return A redirection to the list screen, or the form again when validation fails.
     */
    @POST
    @Path("/new")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response save(@FormParam("username") String username,
                         @FormParam("password") String password,
                         @FormParam("displayName") String displayName,
                         @FormParam("roles") List<String> roles,
                         @FormParam("active") String active) {
        LOGGER.debug("Entering method save for username: " + username);
        AppUser user = new AppUser();
        user.username = username == null ? null : username.trim();
        user.displayName = displayName;
        user.active = active != null;
        user.setRoleSet(sanitizeRoles(roles));

        String error = validateNew(user, password);
        if (error != null) {
            return Response.ok(Templates.form(user, AppUser.ALL_ROLES, error)).build();
        }
        user.setPassword(password);
        // The creator knows this password, so its owner must replace it on first sign-in.
        user.mustChangePassword = true;
        user.persist();
        LOGGER.debug("Exiting method save. Created ID: " + user.id);
        return redirectWithNotice("Account '" + user.username + "' created.", true);
    }

    /**
     * Updates an account from the submitted form.
     * <p>
     * The password is only changed when a new one is supplied, so editing a profile does
     * not require retyping the credentials.
     *
     * @param id              The identifier of the account to update.
     * @param password        The new clear text password, blank to keep the current one.
     * @param displayName     The name shown in the interface, may be blank.
     * @param roles           The granted roles.
     * @param active          Whether the account may sign in.
     * @param securityContext The context identifying the signed-in user.
     * @return A redirection to the list screen, or the form again when validation fails.
     */
    @POST
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response update(@PathParam("id") Long id,
                           @FormParam("password") String password,
                           @FormParam("displayName") String displayName,
                           @FormParam("roles") List<String> roles,
                           @FormParam("active") String active,
                           @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method update for id: " + id);
        AppUser user = AppUser.findById(id);
        if (user == null) {
            LOGGER.error("User with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity("User " + id + " not found").build();
        }
        Set<String> newRoles = sanitizeRoles(roles);
        boolean nowActive = active != null;

        String error = validateUpdate(user, newRoles, nowActive, password);
        if (error != null) {
            return Response.ok(Templates.form(user, AppUser.ALL_ROLES, error)).build();
        }
        user.displayName = displayName;
        user.active = nowActive;
        user.setRoleSet(newRoles);
        if (password != null && !password.isBlank()) {
            user.setPassword(password);
            user.mustChangePassword = true;
        }
        LOGGER.debug("Exiting method update");
        return redirectWithNotice("Account '" + user.username + "' updated.", true);
    }

    /**
     * Deletes an account.
     *
     * @param id              The identifier of the account to delete.
     * @param securityContext The context identifying the signed-in user.
     * @return A redirection to the list screen carrying the outcome.
     */
    @POST
    @Path("/{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response delete(@PathParam("id") Long id, @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method delete for id: " + id);
        AppUser user = AppUser.findById(id);
        if (user == null) {
            return redirectWithNotice("Account not found.", false);
        }
        if (user.username.equals(currentUsername(securityContext))) {
            return redirectWithNotice("You cannot delete your own account.", false);
        }
        if (isLastActiveAdmin(user)) {
            return redirectWithNotice("The last administrator cannot be deleted.", false);
        }
        String name = user.username;
        user.delete();
        LOGGER.debug("Exiting method delete");
        return redirectWithNotice("Account '" + name + "' deleted.", true);
    }

    // --------------------------------------------------
    // Validation
    // --------------------------------------------------

    /**
     * Validates the creation of an account.
     *
     * @param user     The populated account.
     * @param password The submitted clear text password.
     * @return An error message when validation fails, {@code null} on success.
     */
    private String validateNew(AppUser user, String password) {
        if (user.username == null || user.username.isBlank()) {
            return "The username is mandatory.";
        }
        if (AppUser.count("username", user.username) > 0) {
            return "An account named '" + user.username + "' already exists.";
        }
        String policyError = AppUser.validatePassword(password);
        if (policyError != null) {
            return policyError;
        }
        if (user.getRoleSet().isEmpty()) {
            return "At least one role must be granted.";
        }
        return null;
    }

    /**
     * Validates the update of an account.
     * <p>
     * Beyond the field level checks, this refuses any change that would remove the last
     * administrator able to sign in.
     *
     * @param user      The account being updated, still holding its persisted state.
     * @param newRoles  The roles about to be granted.
     * @param nowActive Whether the account is about to stay enabled.
     * @param password  The submitted password, blank to keep the current one.
     * @return An error message when validation fails, {@code null} on success.
     */
    private String validateUpdate(AppUser user, Set<String> newRoles, boolean nowActive, String password) {
        if (newRoles.isEmpty()) {
            return "At least one role must be granted.";
        }
        if (password != null && !password.isBlank()) {
            String policyError = AppUser.validatePassword(password);
            if (policyError != null) {
                return policyError;
            }
        }
        boolean losesAdmin = user.hasRole(AppUser.ROLE_ADMIN) && !newRoles.contains(AppUser.ROLE_ADMIN);
        boolean losesAccess = user.active && !nowActive;
        if ((losesAdmin || losesAccess) && isLastActiveAdmin(user)) {
            return "This is the last administrator: keep the role and the account enabled.";
        }
        return null;
    }

    /**
     * Indicates whether an account is the only active administrator left.
     *
     * @param user The account to test.
     * @return {@code true} when disabling or demoting it would lock everyone out.
     */
    private boolean isLastActiveAdmin(AppUser user) {
        return user.active && user.hasRole(AppUser.ROLE_ADMIN) && AppUser.countActiveAdmins() <= 1;
    }

    /**
     * Keeps only the submitted values that are actual application roles.
     * <p>
     * Form parameters are user controlled, so an unknown value must never reach the
     * database and become a grantable role.
     *
     * @param submitted The roles submitted by the form, may be null.
     * @return The recognised roles, never null.
     */
    private Set<String> sanitizeRoles(List<String> submitted) {
        Set<String> sanitized = new HashSet<>();
        if (submitted == null) {
            return sanitized;
        }
        for (String role : submitted) {
            if (role != null && AppUser.ALL_ROLES.contains(role.trim())) {
                sanitized.add(role.trim());
            }
        }
        return sanitized;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Returns the login name of the signed-in user.
     *
     * @param securityContext The JAX-RS security context.
     * @return The login name, or an empty string when unauthenticated.
     */
    private String currentUsername(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return "";
        }
        return securityContext.getUserPrincipal().getName();
    }

    /**
     * Redirects to the list screen carrying a message to display.
     *
     * @param notice  The message shown once on the list screen.
     * @param success Whether the message reports a success.
     * @return A 303 See Other response.
     */
    private Response redirectWithNotice(String notice, boolean success) {
        URI target = UriBuilder.fromPath("/ui/users")
                .queryParam("notice", notice)
                .queryParam("noticeOk", success)
                .build();
        return Response.seeOther(target).build();
    }
}
