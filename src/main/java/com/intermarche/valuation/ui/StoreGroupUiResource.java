package com.intermarche.valuation.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workbench for organising stores into groups.
 * <p>
 * The screen shows the whole hierarchy on one side and every store on the other.
 * Membership is changed by dragging or by selecting several rows at once; nothing is
 * written until the pending changes are saved, so a reorganisation can be laid out in
 * full and reviewed before it takes effect.
 * <p>
 * There is no per-row button and no separate edition form: the tree is the editor.
 * Creating and renaming a group happens in place, and the whole state is submitted as a
 * single document.
 */
@Path("/ui/store-groups")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class StoreGroupUiResource {

    private static final Logger LOGGER = Logger.getLogger(StoreGroupUiResource.class);

    /**
     * Mapper used to exchange the hierarchy with the browser.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the workbench.
         *
         * @param modelJson The hierarchy and the stores, serialized for the editor.
         * @param canWrite  Whether the signed-in user may reorganise the hierarchy.
         * @return The template instance to render.
         */
        public static native TemplateInstance workbench(String modelJson, boolean canWrite);
    }

    // --------------------------------------------------
    // Screen
    // --------------------------------------------------

    /**
     * Displays the workbench.
     *
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered workbench.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance workbench(@Context SecurityContext securityContext) {
        LOGGER.debug("Entering method workbench");
        return Templates.workbench(buildModelJson(), canWrite(securityContext));
    }

    // --------------------------------------------------
    // Persistence
    // --------------------------------------------------

    /**
     * Applies a whole reorganisation submitted by the editor.
     * <p>
     * The payload describes the intended end state rather than a list of operations: the
     * groups that must exist, their names, and the members of each. Replacing the state
     * wholesale keeps the browser and the database in step even when several changes were
     * accumulated before saving.
     *
     * @param payload The intended hierarchy, as produced by the editor.
     * @return A confirmation, or a conflict describing why the hierarchy was refused.
     */
    @POST
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional(rollbackOn = Exception.class)
    public Response save(HierarchyPayload payload) {
        LOGGER.debug("Entering method save");
        if (payload == null || payload.groups == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Nothing to save.")).build();
        }
        String error = apply(payload);
        if (error != null) {
            LOGGER.warn("Rejected hierarchy: " + error);
            // Rolling back leaves the browser holding its pending state, so the user can
            // correct the offending link without losing the rest of the reorganisation.
            throw new RejectedHierarchyException(error);
        }
        LOGGER.debug("Exiting method save");
        return Response.ok(Map.of("saved", true)).build();
    }

    /**
     * Applies the submitted hierarchy to the database.
     *
     * @param payload The intended end state.
     * @return An error message when the hierarchy must be refused, {@code null} on success.
     */
    private String apply(HierarchyPayload payload) {
        Map<String, StoreGroup> byCode = new LinkedHashMap<>();

        // 1. Create or update every declared group before linking, so a group can
        //    reference another one created in the same submission.
        for (GroupPayload declared : payload.groups) {
            if (declared.code == null || declared.code.isBlank()) {
                return "A group is missing its code.";
            }
            String code = declared.code.trim();
            StoreGroup group = StoreGroup.findByCode(code);
            if (group == null) {
                group = new StoreGroup();
                group.code = code;
                group.persist();
            }
            group.name = declared.name == null || declared.name.isBlank() ? code : declared.name.trim();
            byCode.put(code, group);
        }

        // 2. Remove the groups the editor no longer declares.
        for (StoreGroup group : StoreGroup.<StoreGroup>listAll()) {
            if (!byCode.containsKey(group.code)) {
                for (StoreGroup parent : StoreGroup.findParentsOf(group)) {
                    parent.storeGroups.remove(group);
                }
                group.stores.clear();
                group.storeGroups.clear();
                group.delete();
            }
        }

        // 3. Replace the membership of every surviving group.
        for (GroupPayload declared : payload.groups) {
            StoreGroup group = byCode.get(declared.code.trim());
            Set<Store> stores = new HashSet<>();
            if (declared.storeCodes != null) {
                for (String code : declared.storeCodes) {
                    Store store = Store.findByCode(code);
                    if (store == null) {
                        return "Unknown store code: " + code;
                    }
                    stores.add(store);
                }
            }
            Set<StoreGroup> children = new HashSet<>();
            if (declared.childCodes != null) {
                for (String code : declared.childCodes) {
                    StoreGroup child = byCode.get(code);
                    if (child == null) {
                        return "Unknown group code: " + code;
                    }
                    children.add(child);
                }
            }
            group.stores.clear();
            group.stores.addAll(stores);
            group.storeGroups.clear();
            group.storeGroups.addAll(children);
        }

        // 4. Cycles are checked once the whole graph is in place: a submission may move
        //    several groups at once, and an intermediate state can look circular while
        //    the end state is not.
        for (StoreGroup group : byCode.values()) {
            if (reaches(group, group, new HashSet<>())) {
                return "Group '" + group.code + "' ends up containing itself.";
            }
        }
        return null;
    }

    /**
     * Indicates whether a group reaches a target by walking down its descendants.
     *
     * @param current The group being explored.
     * @param target  The group looked for.
     * @param visited The codes already explored.
     * @return {@code true} when the target is reachable.
     */
    private boolean reaches(StoreGroup current, StoreGroup target, Set<String> visited) {
        for (StoreGroup child : current.storeGroups) {
            if (child.code.equals(target.code)) {
                return true;
            }
            if (visited.add(child.code) && reaches(child, target, visited)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------
    // Model
    // --------------------------------------------------

    /**
     * Serializes the current hierarchy for the editor.
     * <p>
     * Every store is sent, not only the unassigned ones: a store may belong to several
     * groups, so the editor needs the full catalog to offer it again.
     *
     * @return A JSON document holding every group and every store.
     */
    private String buildModelJson() {
        Map<String, Object> model = new LinkedHashMap<>();

        List<Map<String, Object>> groups = new ArrayList<>();
        for (StoreGroup group : StoreGroup.<StoreGroup>list("order by code")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", group.code);
            entry.put("name", group.name);
            entry.put("storeCodes", group.stores.stream().map(s -> s.code).sorted().toList());
            entry.put("childCodes", group.storeGroups.stream().map(g -> g.code).sorted().toList());
            groups.add(entry);
        }
        model.put("groups", groups);

        List<Map<String, Object>> stores = new ArrayList<>();
        for (Store store : Store.<Store>list("order by code")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", store.code);
            entry.put("name", store.name);
            entry.put("city", store.address == null ? null : store.address.city);
            stores.add(entry);
        }
        model.put("stores", stores);

        try {
            return MAPPER.writeValueAsString(model);
        } catch (Exception e) {
            LOGGER.error("Could not serialize the hierarchy", e);
            return "{\"groups\":[],\"stores\":[]}";
        }
    }

    /**
     * Indicates whether the signed-in user may reorganise the hierarchy.
     *
     * @param securityContext The JAX-RS security context.
     * @return {@code true} when the administrator role is granted.
     */
    private boolean canWrite(SecurityContext securityContext) {
        return securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN);
    }

    // --------------------------------------------------
    // Payloads
    // --------------------------------------------------

    /**
     * Raised when the submitted hierarchy cannot be applied.
     * <p>
     * Extending {@link RuntimeException} is what rolls the transaction back, so a refused
     * submission leaves the database exactly as it was.
     */
    public static class RejectedHierarchyException extends RuntimeException {

        /**
         * Constructs the exception with the reason shown to the user.
         *
         * @param message The reason the hierarchy was refused.
         */
        public RejectedHierarchyException(String message) {
            super(message);
        }
    }

    /**
     * The hierarchy submitted by the editor.
     * <p>
     * Fields are public because Jackson populates them directly.
     */
    public static class HierarchyPayload {

        /**
         * Every group that must exist once the submission is applied.
         */
        public List<GroupPayload> groups;

        /**
         * Default constructor required for JSON deserialization.
         */
        public HierarchyPayload() {
        }
    }

    /**
     * One group of the submitted hierarchy.
     * <p>
     * Fields are public because Jackson populates them directly.
     */
    public static class GroupPayload {

        /**
         * The unique business code of the group.
         */
        public String code;

        /**
         * The display name of the group.
         */
        public String name;

        /**
         * The codes of the stores attached directly to this group.
         */
        public List<String> storeCodes;

        /**
         * The codes of the groups contained in this one.
         */
        public List<String> childCodes;

        /**
         * Default constructor required for JSON deserialization.
         */
        public GroupPayload() {
        }
    }
}
