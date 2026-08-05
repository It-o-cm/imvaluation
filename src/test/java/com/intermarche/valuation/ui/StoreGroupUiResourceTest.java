package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreGroupUiResource}.
 * <p>
 * The resource is a plain JAX-RS bean with no injected collaborators; every persistence access
 * goes through the custom and inherited static finders of {@link StoreGroup} and {@link Store}.
 * The custom finders declared on the entities ({@code StoreGroup.findByCode},
 * {@code StoreGroup.findParentsOf}, {@code Store.findByCode}) are neutralised with a
 * {@link org.mockito.Mockito#mockStatic} of the entity class, while the inherited finders
 * ({@code list}, {@code listAll}) resolve to {@link PanacheEntityBase} and are intercepted with a
 * {@link org.mockito.Mockito#mockStatic} of that declaring class. Because both list calls in
 * {@code buildModelJson} share the same {@code PanacheEntityBase.list("order by code")} signature,
 * the group list is returned first and the store list second through consecutive stubbing. The
 * {@code apply} path may create a group with {@code new StoreGroup()} followed by
 * {@code persist()}: that construction is intercepted with
 * {@link org.mockito.Mockito#mockConstruction} and its initializer re-creates the
 * {@code stores}/{@code storeGroups} sets that the field initializers do not run on an
 * Objenesis-instantiated mock.
 * <p>
 * The {@code workbench} screen ends in the {@code @CheckedTemplate} native method
 * {@code Templates.workbench}, left unlinked under plain {@code mvn test}; every screen therefore
 * terminates in an {@link UnsatisfiedLinkError} once {@code buildModelJson} and {@code canWrite}
 * have run, and those tests assert the throw while every branch before the template call is
 * exercised. The persistence endpoints ({@code save}) return their responses directly, or throw
 * {@link StoreGroupUiResource.RejectedHierarchyException} on a refused hierarchy.
 * <p>
 * Branches covered, arm by arm:
 * <ul>
 *   <li>{@code save}: the {@code payload == null || payload.groups == null} guard (both forms and
 *       the accepted form) and the {@code error != null} decision (refused and accepted).</li>
 *   <li>{@code apply}: the {@code code == null || code.isBlank()} guard (null, blank, valid), the
 *       {@code group == null} create/reuse decision, the {@code name} fallback ternary (null,
 *       blank, populated), the {@code !byCode.containsKey} survival decision (kept and removed),
 *       the {@code findParentsOf} loop (with and without parents), the {@code storeCodes != null}
 *       and {@code childCodes != null} guards (present and absent), the {@code store == null} and
 *       {@code child == null} rejections (found and missing) and the {@code reaches} cycle
 *       decision (clean and circular).</li>
 *   <li>{@code reaches}: the {@code child.code.equals(target.code)} test (hit and miss), the
 *       {@code visited.add} guard (fresh and already visited) and the recursive result (true and
 *       false, plus the loop-exhausted return).</li>
 *   <li>{@code buildModelJson}: the group and store render loops (populated and empty) and the
 *       {@code store.address == null} ternary (both arms).</li>
 *   <li>{@code canWrite}: the {@code securityContext != null && isUserInRole} short-circuit
 *       (null context, non-admin, admin).</li>
 * </ul>
 */
class StoreGroupUiResourceTest {

    /**
     * Builds a store group carrying the given code and a default name, with empty relation sets.
     *
     * @param code The group code.
     * @return The populated store group.
     */
    private StoreGroup group(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = code;
        return group;
    }

    /**
     * Builds a store carrying the given code with no address.
     *
     * @param code The store code.
     * @return The populated store.
     */
    private Store store(String code) {
        Store store = new Store();
        store.code = code;
        store.name = code;
        return store;
    }

    /**
     * Builds a store carrying the given code and an address with the given city.
     *
     * @param code The store code.
     * @param city The address city.
     * @return The populated store carrying an address.
     */
    private Store storeWithCity(String code, String city) {
        Store store = store(code);
        Adresse address = new Adresse();
        address.city = city;
        store.address = address;
        return store;
    }

    /**
     * Builds a group payload with the given fields.
     *
     * @param code       The declared group code.
     * @param name       The declared group name.
     * @param storeCodes The declared store codes, may be null.
     * @param childCodes The declared child group codes, may be null.
     * @return The populated payload.
     */
    private StoreGroupUiResource.GroupPayload payload(String code, String name,
                                                      List<String> storeCodes, List<String> childCodes) {
        StoreGroupUiResource.GroupPayload payload = new StoreGroupUiResource.GroupPayload();
        payload.code = code;
        payload.name = name;
        payload.storeCodes = storeCodes;
        payload.childCodes = childCodes;
        return payload;
    }

    /**
     * Wraps the given group payloads into a hierarchy payload.
     *
     * @param groups The declared groups.
     * @return The populated hierarchy payload.
     */
    private StoreGroupUiResource.HierarchyPayload hierarchy(StoreGroupUiResource.GroupPayload... groups) {
        StoreGroupUiResource.HierarchyPayload payload = new StoreGroupUiResource.HierarchyPayload();
        payload.groups = new ArrayList<>(Arrays.asList(groups));
        return payload;
    }

    // --------------------------------------------------
    // workbench
    // --------------------------------------------------

    /**
     * The workbench for an administrator serializes a populated hierarchy and reaches the template,
     * exercising the group and store render loops, both arms of the {@code store.address == null}
     * ternary and the true right arm of the {@code canWrite} conjunction.
     */
    @Test
    void workbenchAsAdminSerializesModelAndReachesTemplate() {
        StoreGroup g1 = group("G1");
        g1.stores = new HashSet<>(Set.of(store("S2"), store("S1")));
        g1.storeGroups = new HashSet<>(Set.of(group("C1")));
        StoreGroup g2 = group("G2");
        SecurityContext context = mock(SecurityContext.class);
        when(context.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(true);
        StoreGroupUiResource resource = new StoreGroupUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code"))
                    .thenReturn(List.of(g1, g2), List.of(storeWithCity("ST1", "Lyon"), store("ST2")));
            assertThrows(UnsatisfiedLinkError.class, () -> resource.workbench(context));
        }
    }

    /**
     * The workbench for a non-administrator serializes an empty hierarchy and reaches the template,
     * exercising the empty render loops and the false right arm of the {@code canWrite} conjunction.
     */
    @Test
    void workbenchAsNonAdminReachesTemplate() {
        SecurityContext context = mock(SecurityContext.class);
        when(context.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(false);
        StoreGroupUiResource resource = new StoreGroupUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertThrows(UnsatisfiedLinkError.class, () -> resource.workbench(context));
        }
    }

    /**
     * The workbench with a null security context reaches the template, exercising the false left
     * arm of the {@code canWrite} conjunction.
     */
    @Test
    void workbenchWithNullContextReachesTemplate() {
        StoreGroupUiResource resource = new StoreGroupUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertThrows(UnsatisfiedLinkError.class, () -> resource.workbench(null));
        }
    }

    // --------------------------------------------------
    // save — guards
    // --------------------------------------------------

    /**
     * Saving a null payload answers a 400 without touching persistence, exercising the true left
     * arm of the payload guard.
     */
    @Test
    void saveWithNullPayloadReturnsBadRequest() {
        Response response = new StoreGroupUiResource().save(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(Map.of("error", "Nothing to save."), response.getEntity());
    }

    /**
     * Saving a payload without a group list answers a 400, exercising the true right arm of the
     * payload guard.
     */
    @Test
    void saveWithNullGroupsReturnsBadRequest() {
        StoreGroupUiResource.HierarchyPayload payload = new StoreGroupUiResource.HierarchyPayload();
        Response response = new StoreGroupUiResource().save(payload);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(Map.of("error", "Nothing to save."), response.getEntity());
    }

    /**
     * Saving a group with a null code is refused, exercising the true left arm of the code guard.
     */
    @Test
    void saveWithNullCodeIsRejected() {
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(payload(null, "N", null, null));
        StoreGroupUiResource.RejectedHierarchyException error = assertThrows(
                StoreGroupUiResource.RejectedHierarchyException.class, () -> resource.save(payload));
        assertEquals("A group is missing its code.", error.getMessage());
    }

    /**
     * Saving a group with a blank code is refused, exercising the false left arm and true right arm
     * of the code guard.
     */
    @Test
    void saveWithBlankCodeIsRejected() {
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(payload("   ", "N", null, null));
        StoreGroupUiResource.RejectedHierarchyException error = assertThrows(
                StoreGroupUiResource.RejectedHierarchyException.class, () -> resource.save(payload));
        assertEquals("A group is missing its code.", error.getMessage());
    }

    // --------------------------------------------------
    // save — acceptance
    // --------------------------------------------------

    /**
     * A valid submission creates a missing group, reuses two existing ones and links a store and a
     * child, exercising the create arm of {@code group == null}, the reuse arm, all three arms of
     * the {@code name} fallback ternary, the present and absent forms of the store and child
     * guards, the found arms of {@code store == null} and {@code child == null}, the survival arm of
     * the removal loop and the clean arm of the cycle check.
     */
    @Test
    void saveValidHierarchyCreatesReusesAndLinks() {
        StoreGroup existingB = group("B");
        StoreGroup existingC = group("C");
        Store s1 = store("S1");
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(
                payload("A", null, List.of("S1"), List.of("B")),
                payload(" B ", "  ", null, null),
                payload("C", "Real Name", List.of(), List.of()));
        try (MockedStatic<StoreGroup> groups = mockStatic(StoreGroup.class);
             MockedStatic<Store> stores = mockStatic(Store.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoreGroup> construction = mockConstruction(StoreGroup.class, (created, ctx) -> {
                 created.stores = new HashSet<>();
                 created.storeGroups = new HashSet<>();
             })) {
            groups.when(() -> StoreGroup.findByCode("A")).thenReturn(null);
            groups.when(() -> StoreGroup.findByCode("B")).thenReturn(existingB);
            groups.when(() -> StoreGroup.findByCode("C")).thenReturn(existingC);
            panache.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of(existingB, existingC));
            stores.when(() -> Store.findByCode("S1")).thenReturn(s1);
            Response response = resource.save(payload);
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertEquals(Map.of("saved", true), response.getEntity());
            StoreGroup created = construction.constructed().get(0);
            assertEquals("A", created.code);
            assertEquals("A", created.name);
            verify(created).persist();
            assertEquals(Set.of("S1"), created.stores.stream().map(s -> s.code).collect(Collectors.toSet()));
            assertEquals(Set.of("B"), created.storeGroups.stream().map(g -> g.code).collect(Collectors.toSet()));
            assertEquals("B", existingB.name);
            assertEquals("Real Name", existingC.name);
        }
    }

    /**
     * A submission that no longer declares a group removes it, unlinking it from its parents and
     * deleting it. A second undeclared group without a parent covers the empty {@code findParentsOf}
     * loop, so both arms of the parent loop and the true arm of the survival decision are exercised.
     */
    @Test
    void saveRemovesUndeclaredGroups() {
        StoreGroup existingA = group("A");
        StoreGroup stray1 = mock(StoreGroup.class);
        stray1.code = "OLD1";
        stray1.stores = new HashSet<>();
        stray1.storeGroups = new HashSet<>();
        StoreGroup stray2 = mock(StoreGroup.class);
        stray2.code = "OLD2";
        stray2.stores = new HashSet<>();
        stray2.storeGroups = new HashSet<>();
        StoreGroup parent = mock(StoreGroup.class);
        parent.storeGroups = new HashSet<>(Set.of(stray1));
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(payload("A", "A", null, null));
        try (MockedStatic<StoreGroup> groups = mockStatic(StoreGroup.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            groups.when(() -> StoreGroup.findByCode("A")).thenReturn(existingA);
            panache.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of(existingA, stray1, stray2));
            groups.when(() -> StoreGroup.findParentsOf(stray1)).thenReturn(List.of(parent));
            groups.when(() -> StoreGroup.findParentsOf(stray2)).thenReturn(List.of());
            Response response = resource.save(payload);
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertFalse(parent.storeGroups.contains(stray1));
            verify(stray1).delete();
            verify(stray2).delete();
        }
    }

    // --------------------------------------------------
    // save — rejections
    // --------------------------------------------------

    /**
     * A submission naming an unknown store is refused, exercising the present arm of the store guard
     * and the missing arm of {@code store == null}.
     */
    @Test
    void saveWithUnknownStoreIsRejected() {
        StoreGroup existingA = group("A");
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(payload("A", "A", List.of("SX"), null));
        try (MockedStatic<StoreGroup> groups = mockStatic(StoreGroup.class);
             MockedStatic<Store> stores = mockStatic(Store.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            groups.when(() -> StoreGroup.findByCode("A")).thenReturn(existingA);
            stores.when(() -> Store.findByCode("SX")).thenReturn(null);
            StoreGroupUiResource.RejectedHierarchyException error = assertThrows(
                    StoreGroupUiResource.RejectedHierarchyException.class, () -> resource.save(payload));
            assertEquals("Unknown store code: SX", error.getMessage());
        }
    }

    /**
     * A submission naming an unknown child group is refused, exercising the present arm of the child
     * guard and the missing arm of {@code child == null}.
     */
    @Test
    void saveWithUnknownChildIsRejected() {
        StoreGroup existingA = group("A");
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(payload("A", "A", null, List.of("ZZ")));
        try (MockedStatic<StoreGroup> groups = mockStatic(StoreGroup.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            groups.when(() -> StoreGroup.findByCode("A")).thenReturn(existingA);
            StoreGroupUiResource.RejectedHierarchyException error = assertThrows(
                    StoreGroupUiResource.RejectedHierarchyException.class, () -> resource.save(payload));
            assertEquals("Unknown group code: ZZ", error.getMessage());
        }
    }

    /**
     * A submission wiring two groups into each other is refused, exercising the circular arm of the
     * cycle check, the hit arm of {@code child.code.equals(target.code)}, the fresh arm of
     * {@code visited.add} and the true recursive result of {@code reaches}.
     */
    @Test
    void saveWithCycleIsRejected() {
        StoreGroup existingA = group("A");
        StoreGroup existingB = group("B");
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(
                payload("A", "A", null, List.of("B")),
                payload("B", "B", null, List.of("A")));
        try (MockedStatic<StoreGroup> groups = mockStatic(StoreGroup.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            groups.when(() -> StoreGroup.findByCode("A")).thenReturn(existingA);
            groups.when(() -> StoreGroup.findByCode("B")).thenReturn(existingB);
            StoreGroupUiResource.RejectedHierarchyException error = assertThrows(
                    StoreGroupUiResource.RejectedHierarchyException.class, () -> resource.save(payload));
            assertEquals("Group 'A' ends up containing itself.", error.getMessage());
        }
    }

    /**
     * A diamond submission where two branches converge on the same descendant is accepted, exercising
     * the already-visited arm of {@code visited.add} and the loop-exhausted false return of
     * {@code reaches} without ever reaching a cycle.
     */
    @Test
    void saveWithDiamondIsAccepted() {
        StoreGroup existingA = group("A");
        StoreGroup existingB = group("B");
        StoreGroup existingC = group("C");
        StoreGroup existingD = group("D");
        StoreGroupUiResource resource = new StoreGroupUiResource();
        StoreGroupUiResource.HierarchyPayload payload = hierarchy(
                payload("A", "A", null, List.of("B", "C")),
                payload("B", "B", null, List.of("D")),
                payload("C", "C", null, List.of("D")),
                payload("D", "D", null, null));
        try (MockedStatic<StoreGroup> groups = mockStatic(StoreGroup.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            groups.when(() -> StoreGroup.findByCode("A")).thenReturn(existingA);
            groups.when(() -> StoreGroup.findByCode("B")).thenReturn(existingB);
            groups.when(() -> StoreGroup.findByCode("C")).thenReturn(existingC);
            groups.when(() -> StoreGroup.findByCode("D")).thenReturn(existingD);
            Response response = resource.save(payload);
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertEquals(Map.of("saved", true), response.getEntity());
            assertTrue(existingA.storeGroups.stream().map(g -> g.code).collect(Collectors.toSet())
                    .containsAll(Set.of("B", "C")));
        }
    }
}
