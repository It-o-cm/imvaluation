package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration screens managing store groups and their hierarchy.
 * <p>
 * A group gathers stores and other groups. Both relationships are many-to-many, so a
 * store or a sub-group may belong to several parents at once; the screens are built
 * around that, showing every parent of a group rather than a single one.
 * <p>
 * Cycles are refused when a link is created: a group cannot contain itself, directly or
 * through any chain of descendants.
 */
@Path("/ui/store-groups")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class StoreGroupUiResource {

    private static final Logger LOGGER = Logger.getLogger(StoreGroupUiResource.class);

    /**
     * Number of groups displayed per page.
     */
    private static final int PAGE_SIZE = 25;

    /**
     * Base path of the screen, used to build its links.
     */
    private static final String BASE_PATH = "/ui/store-groups";

    /**
     * Sort key ordering groups by their business code.
     */
    private static final String SORT_CODE = "code";

    /**
     * Sort key ordering groups by their display name.
     */
    private static final String SORT_NAME = "name";

    /**
     * Sort key ordering groups by the number of stores they hold directly.
     */
    private static final String SORT_STORES = "stores";

    /**
     * Every sort key accepted by this screen.
     */
    private static final Set<String> SORTABLE = Set.of(SORT_CODE, SORT_NAME, SORT_STORES);

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the group list screen.
         *
         * @param view The view model carrying the page, the filters and the sort state.
         * @return The template instance to render.
         */
        public static native TemplateInstance list(ListView<StoreGroup> view);

        /**
         * Renders the group creation or edition form.
         *
         * @param group    The group being edited, or null when creating.
         * @param parents  The groups declaring this one as a child.
         * @param canWrite Whether the signed-in user may modify the group.
         * @param error    An error message to display, may be null.
         * @return The template instance to render.
         */
        public static native TemplateInstance form(StoreGroup group, List<StoreGroup> parents,
                                                   boolean canWrite, String error);

        /**
         * Renders the hierarchy overview.
         *
         * @param roots    The groups that are not a child of any other.
         * @param orphans  The stores belonging to no group at all.
         * @param canWrite Whether the signed-in user may modify the hierarchy.
         * @return The template instance to render.
         */
        public static native TemplateInstance tree(List<StoreGroup> roots, List<Store> orphans,
                                                   boolean canWrite);
    }

    // --------------------------------------------------
    // Screens
    // --------------------------------------------------

    /**
     * Displays a page of store groups.
     *
     * @param search A fragment matched against the code and the name, may be null or blank.
     * @param store  A store code the group must contain, may be null or blank.
     * @param sort   The column driving the sort, defaulting to the code.
     * @param dir    The sort direction, either "asc" or "desc".
     * @param page   The requested page number, defaulting to the first one.
     * @param notice A message to display, typically the outcome of a previous action.
     * @param noticeOk Whether the message reports a success.
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered list screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String search,
                                 @QueryParam("store") String store,
                                 @QueryParam("sort") @DefaultValue(SORT_CODE) String sort,
                                 @QueryParam("dir") @DefaultValue("asc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method list");
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_CODE;
        boolean descending = "desc".equalsIgnoreCase(dir);
        PanacheQuery<StoreGroup> query = queryGroups(search, store, sortKey, descending)
                .page(Page.ofSize(PAGE_SIZE));
        long totalCount = query.count();
        int pageCount = Math.max(1, query.pageCount());
        int currentPage = Math.min(Math.max(page, 1), pageCount);
        List<StoreGroup> groups = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("q", search);
        filters.put("store", store);
        ListView<StoreGroup> view = new ListView<>(groups, BASE_PATH, filters, sortKey, descending,
                currentPage, pageCount, totalCount, "store group", notice, noticeOk,
                canWrite(securityContext));
        return Templates.list(view);
    }

    /**
     * Displays the hierarchy as a tree, starting from the groups without a parent.
     * <p>
     * Stores belonging to no group are listed apart: they are usually a configuration
     * oversight, and the overview is the natural place to notice them.
     *
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered hierarchy screen.
     */
    @GET
    @Path("/tree")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance tree(@Context SecurityContext securityContext) {
        LOGGER.debug("Entering method tree");
        List<Store> orphans = Store.find(
                "select s from Store s where s.id not in"
                        + " (select st.id from StoreGroup g join g.stores st) order by s.code").list();
        return Templates.tree(StoreGroup.findRoots(), orphans, canWrite(securityContext));
    }

    /**
     * Displays an empty form for creating a group.
     *
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered creation form.
     */
    @GET
    @Path("/new")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance create(@Context SecurityContext securityContext) {
        LOGGER.debug("Entering method create");
        return Templates.form(new StoreGroup(), List.of(), true, null);
    }

    /**
     * Displays the edition form of an existing group.
     *
     * @param id The identifier of the group to edit.
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered edition form, or a 404 response when the group does not exist.
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response edit(@PathParam("id") Long id, @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method edit with id: " + id);
        StoreGroup group = StoreGroup.findById(id);
        if (group == null) {
            LOGGER.error("StoreGroup with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity("Store group " + id + " not found").build();
        }
        return Response.ok(Templates.form(group, StoreGroup.findParentsOf(group),
                canWrite(securityContext), null)).build();
    }

    // --------------------------------------------------
    // Mutations
    // --------------------------------------------------

    /**
     * Creates a group from the submitted form.
     *
     * @param code            The unique business code.
     * @param name            The display name.
     * @param storeCodes      The codes of the stores to attach, comma separated.
     * @param storeGroupCodes The codes of the sub-groups to attach, comma separated.
     * @return A redirection to the list screen, or the form again when validation fails.
     */
    @POST
    @Path("/new")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response save(@FormParam("code") String code,
                         @FormParam("name") String name,
                         @FormParam("storeCodes") String storeCodes,
                         @FormParam("storeGroupCodes") String storeGroupCodes) {
        LOGGER.debug("Entering method save for code: " + code);
        StoreGroup group = new StoreGroup();
        group.code = code == null ? null : code.trim();
        group.name = name;

        if (group.code == null || group.code.isBlank()) {
            return renderError(group, "The code is mandatory.");
        }
        if (group.name == null || group.name.isBlank()) {
            return renderError(group, "The name is mandatory.");
        }
        if (StoreGroup.count("code", group.code) > 0) {
            return renderError(group, "A group with code '" + group.code + "' already exists.");
        }
        String error = applyLinks(group, storeCodes, storeGroupCodes);
        if (error != null) {
            return renderError(group, error);
        }
        group.persist();
        LOGGER.debug("Exiting method save. Created ID: " + group.id);
        return redirectWithNotice("Group '" + group.code + "' created.", true);
    }

    /**
     * Updates a group from the submitted form.
     *
     * @param id              The identifier of the group to update.
     * @param name            The display name.
     * @param storeCodes      The codes of the stores to attach, comma separated.
     * @param storeGroupCodes The codes of the sub-groups to attach, comma separated.
     * @return A redirection to the list screen, or the form again when validation fails.
     */
    @POST
    @Path("/{id}")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response update(@PathParam("id") Long id,
                           @FormParam("name") String name,
                           @FormParam("storeCodes") String storeCodes,
                           @FormParam("storeGroupCodes") String storeGroupCodes) {
        LOGGER.debug("Entering method update for id: " + id);
        StoreGroup group = StoreGroup.findById(id);
        if (group == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Store group " + id + " not found").build();
        }
        if (name == null || name.isBlank()) {
            return renderError(group, "The name is mandatory.");
        }
        group.name = name;
        String error = applyLinks(group, storeCodes, storeGroupCodes);
        if (error != null) {
            return renderError(group, error);
        }
        LOGGER.debug("Exiting method update");
        return redirectWithNotice("Group '" + group.code + "' updated.", true);
    }

    /**
     * Deletes a group.
     * <p>
     * The group is first detached from every parent referencing it, otherwise the join
     * table would keep a row pointing at a deleted row.
     *
     * @param id The identifier of the group to delete.
     * @return A redirection to the list screen carrying the outcome.
     */
    @POST
    @Path("/{id}/delete")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response delete(@PathParam("id") Long id) {
        LOGGER.debug("Entering method delete for id: " + id);
        StoreGroup group = StoreGroup.findById(id);
        if (group == null) {
            return redirectWithNotice("Group not found.", false);
        }
        String code = group.code;
        for (StoreGroup parent : StoreGroup.findParentsOf(group)) {
            parent.storeGroups.remove(group);
        }
        group.stores.clear();
        group.storeGroups.clear();
        group.delete();
        LOGGER.debug("Exiting method delete");
        return redirectWithNotice("Group '" + code + "' deleted.", true);
    }

    /**
     * Detaches a single store from a group.
     * <p>
     * Offered on the edition screen so a store can be removed without resubmitting the
     * whole list of members.
     *
     * @param id        The identifier of the group.
     * @param storeCode The code of the store to detach.
     * @return A redirection to the edition screen.
     */
    @POST
    @Path("/{id}/detach-store")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response detachStore(@PathParam("id") Long id, @FormParam("storeCode") String storeCode) {
        StoreGroup group = StoreGroup.findById(id);
        if (group == null) {
            return redirectWithNotice("Group not found.", false);
        }
        group.stores.removeIf(store -> store.code.equals(storeCode));
        return Response.seeOther(UriBuilder.fromPath(BASE_PATH + "/" + id).build()).build();
    }

    /**
     * Detaches a single sub-group from a group.
     *
     * @param id        The identifier of the parent group.
     * @param childCode The code of the sub-group to detach.
     * @return A redirection to the edition screen.
     */
    @POST
    @Path("/{id}/detach-group")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response detachGroup(@PathParam("id") Long id, @FormParam("childCode") String childCode) {
        StoreGroup group = StoreGroup.findById(id);
        if (group == null) {
            return redirectWithNotice("Group not found.", false);
        }
        group.storeGroups.removeIf(child -> child.code.equals(childCode));
        return Response.seeOther(UriBuilder.fromPath(BASE_PATH + "/" + id).build()).build();
    }

    // --------------------------------------------------
    // Linking
    // --------------------------------------------------

    /**
     * Replaces the stores and sub-groups attached to a group.
     * <p>
     * Every referenced code must exist, and no sub-group may introduce a cycle. The
     * group's own collections are left untouched when validation fails, so a rejected
     * submission never leaves a half-applied hierarchy.
     *
     * @param group           The group being populated.
     * @param storeCodes      The codes of the stores to attach, comma separated.
     * @param storeGroupCodes The codes of the sub-groups to attach, comma separated.
     * @return An error message when validation fails, {@code null} on success.
     */
    private String applyLinks(StoreGroup group, String storeCodes, String storeGroupCodes) {
        List<String> requestedStores = splitCsv(storeCodes);
        List<String> requestedGroups = splitCsv(storeGroupCodes);

        List<Store> stores = new ArrayList<>();
        for (String code : requestedStores) {
            Store store = Store.findByCode(code);
            if (store == null) {
                return "Unknown store code: " + code;
            }
            stores.add(store);
        }

        List<StoreGroup> children = new ArrayList<>();
        for (String code : requestedGroups) {
            StoreGroup child = StoreGroup.findByCode(code);
            if (child == null) {
                return "Unknown group code: " + code;
            }
            if (StoreGroup.wouldCreateCycle(group, child)) {
                return "Adding '" + code + "' would create a cycle in the hierarchy.";
            }
            children.add(child);
        }

        group.stores.clear();
        group.stores.addAll(stores);
        group.storeGroups.clear();
        group.storeGroups.addAll(children);
        return null;
    }

    // --------------------------------------------------
    // Query
    // --------------------------------------------------

    /**
     * Builds the query backing the list screen.
     *
     * @param search     A fragment matched against the code and the name, may be null or blank.
     * @param store      A store code the group must contain, may be null or blank.
     * @param sort       The validated sort key.
     * @param descending Whether the sort is descending.
     * @return The query, not yet paginated.
     */
    private PanacheQuery<StoreGroup> queryGroups(String search, String store,
                                                 String sort, boolean descending) {
        StringBuilder jpql = new StringBuilder("from StoreGroup g");
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (isSet(search)) {
            int index = params.size() + 1;
            where.append("(lower(g.code) like ?").append(index)
                    .append(" or lower(g.name) like ?").append(index).append(")");
            params.add("%" + search.trim().toLowerCase() + "%");
        }
        if (isSet(store)) {
            if (where.length() > 0) {
                where.append(" and ");
            }
            // Membership is tested with EXISTS: a join would duplicate rows and corrupt
            // the page count.
            where.append("exists (select 1 from g.stores s where lower(s.code) like ?")
                    .append(params.size() + 1).append(")");
            params.add("%" + store.trim().toLowerCase() + "%");
        }
        if (where.length() > 0) {
            jpql.append(" where ").append(where);
        }
        String direction = descending ? " desc" : " asc";
        String expression = SORT_STORES.equals(sort) ? "size(g.stores)" : "g." + sort;
        jpql.append(" order by ").append(expression).append(direction);
        if (!SORT_CODE.equals(sort)) {
            jpql.append(", g.code asc");
        }
        return StoreGroup.find(jpql.toString(), params.toArray());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Re-renders the form carrying an error message back to the user.
     *
     * @param group The group holding the submitted values.
     * @param error The error message to display.
     * @return A 200 response rendering the form.
     */
    private Response renderError(StoreGroup group, String error) {
        List<StoreGroup> parents = group.id == null ? List.of() : StoreGroup.findParentsOf(group);
        return Response.ok(Templates.form(group, parents, true, error)).build();
    }

    /**
     * Builds a redirection to the list screen carrying a message to display.
     *
     * @param notice  The message shown once on the list screen.
     * @param success Whether the message reports a success.
     * @return A 303 See Other response.
     */
    private Response redirectWithNotice(String notice, boolean success) {
        URI target = UriBuilder.fromPath(BASE_PATH)
                .queryParam("notice", notice)
                .queryParam("noticeOk", success)
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Indicates whether the signed-in user may modify groups.
     *
     * @param securityContext The JAX-RS security context.
     * @return {@code true} when the administrator role is granted.
     */
    private boolean canWrite(SecurityContext securityContext) {
        return securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN);
    }

    /**
     * Indicates whether a filter value carries an actual criterion.
     *
     * @param value The candidate value, may be null.
     * @return {@code true} when the value is neither null nor blank.
     */
    private boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Splits a comma separated form value into a list of trimmed, unique codes.
     *
     * @param raw The raw form value, may be null.
     * @return The parsed codes, never null.
     */
    private List<String> splitCsv(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !values.contains(trimmed)) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
