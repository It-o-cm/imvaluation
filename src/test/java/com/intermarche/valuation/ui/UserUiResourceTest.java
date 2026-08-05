package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserUiResource}.
 * <p>
 * The resource is a plain JAX-RS bean with no injected collaborators; every persistence access goes
 * through the static members of {@link AppUser}. Two families of statics coexist and are mocked
 * differently: the inherited finders ({@code find}, {@code findById}, {@code count}) resolve to
 * {@link PanacheEntityBase} under plain unit tests and are intercepted with a
 * {@link org.mockito.Mockito#mockStatic} of that class, while the declared statics
 * ({@code countActiveAdmins}) are intercepted with a {@link org.mockito.Mockito#mockStatic} of
 * {@link AppUser}. The pure {@code validatePassword} static carries no persistence, so it runs for
 * real natively and is left unmocked. The constant {@code AppUser.ALL_ROLES} is a field, untouched
 * by either static mock.
 * <p>
 * The {@code save} success path constructs a fresh {@link AppUser} and calls {@code persist()},
 * which is neutralised with {@link org.mockito.Mockito#mockConstruction}; its {@code getRoleSet}
 * is stubbed so the mandatory-role check sees a non-empty set. Every other {@code save} path and
 * both {@code update} error paths return before {@code persist()}, so they operate on a real
 * {@code new AppUser()} whose instance methods run genuinely. {@code update} never persists (it
 * relies on transactional dirty checking), so its account is a plain real {@link AppUser}, while
 * {@code delete} needs its {@code delete()} neutralised and therefore uses a mocked account.
 * <p>
 * The Qute {@code Templates} methods are {@code @CheckedTemplate} native methods left unlinked
 * under plain {@code mvn test}; every screen therefore terminates in an {@link UnsatisfiedLinkError}
 * once its logic has run, and those tests assert the throw while all the branches before the
 * template call are exercised. Redirect and 404 responses carry no template and are asserted
 * directly.
 * <p>
 * Branches covered, arm by arm:
 * <ul>
 *   <li>{@code list}: the {@code sortKey} validity ternary, the {@code descending} decision, the
 *       {@code securityContext != null && isUserInRole} short-circuit (null, non-admin, admin).</li>
 *   <li>{@code queryUsers}: the {@code search} guard (null, blank, populated), the {@code role}
 *       guard (null, blank, populated), the {@code where.length() > 0} join (with and without a
 *       preceding search), the {@code descending} order ternary and the {@code !SORT_USERNAME}
 *       tie-breaker (both arms), and the final {@code where.length() > 0} query ternary.</li>
 *   <li>{@code edit}: the {@code user == null} 404 guard (both arms).</li>
 *   <li>{@code save}: the {@code username == null} trim ternary, the {@code active != null} flag,
 *       the {@code error != null} decision (render and persist).</li>
 *   <li>{@code validateNew}: the {@code username} null/blank guard, the duplicate {@code count}
 *       check, the {@code validatePassword} error and the {@code getRoleSet().isEmpty()} check.</li>
 *   <li>{@code update}: the {@code user == null} 404 guard, the {@code active != null} flag, the
 *       {@code error != null} decision, and the body {@code password} guard (null, blank,
 *       populated).</li>
 *   <li>{@code validateUpdate}: the empty-roles guard, the {@code password} guard and its policy
 *       error, {@code losesAdmin} (both operands, both arms), {@code losesAccess} (both operands,
 *       both arms) and the {@code (losesAdmin || losesAccess) && isLastActiveAdmin} decision (all
 *       three outcomes).</li>
 *   <li>{@code isLastActiveAdmin}: the {@code active}, {@code hasRole} and
 *       {@code countActiveAdmins() <= 1} conjunction (every short-circuit position).</li>
 *   <li>{@code delete}: the {@code user == null} guard, the self-deletion guard and the
 *       last-administrator guard (both arms each).</li>
 *   <li>{@code sanitizeRoles}: the {@code submitted == null} guard and the per-role
 *       {@code role != null && ALL_ROLES.contains} filter (null, unknown, valid).</li>
 *   <li>{@code currentUsername}: the {@code securityContext == null} and null-principal
 *       short-circuit (null context, missing principal, present principal).</li>
 * </ul>
 */
class UserUiResourceTest {

    /**
     * Builds a real account carrying the given identity, roles and activation flag.
     *
     * @param username The login name.
     * @param roles    The comma separated roles stored on the entity.
     * @param active   Whether the account may sign in.
     * @return The populated real account.
     */
    private AppUser realUser(String username, String roles, boolean active) {
        AppUser user = new AppUser();
        user.username = username;
        user.roles = roles;
        user.active = active;
        return user;
    }

    /**
     * Builds a mocked account carrying the given login name and activation flag.
     *
     * @param username The login name exposed on the entity.
     * @param active   Whether the account is flagged active.
     * @return The configured account mock.
     */
    private AppUser mockUser(String username, boolean active) {
        AppUser user = mock(AppUser.class);
        user.username = username;
        user.active = active;
        return user;
    }

    /**
     * Builds a security context carrying a principal with the given name.
     *
     * @param principalName The login name exposed by the principal.
     * @param admin         Whether the context reports the administrator role.
     * @return The configured security context mock.
     */
    private SecurityContext context(String principalName, boolean admin) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(admin);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(principalName);
        when(ctx.getUserPrincipal()).thenReturn(principal);
        return ctx;
    }

    /**
     * Builds a security context that carries no principal.
     *
     * @param admin Whether the context reports the administrator role.
     * @return The configured security context mock.
     */
    private SecurityContext contextNoPrincipal(boolean admin) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(admin);
        when(ctx.getUserPrincipal()).thenReturn(null);
        return ctx;
    }

    /**
     * Stubs the paginated query returned by the list screen finder.
     *
     * @param panache   The active {@link PanacheEntityBase} static mock.
     * @param users     The rows the query returns.
     * @param count     The total row count reported.
     * @param pageCount The page count reported.
     */
    private void stubQuery(MockedStatic<PanacheEntityBase> panache, List<AppUser> users,
                           long count, int pageCount) {
        @SuppressWarnings("unchecked")
        PanacheQuery<AppUser> query = mock(PanacheQuery.class);
        panache.when(() -> PanacheEntityBase.<AppUser>find(anyString(), any(Object[].class)))
                .thenReturn(query);
        when(query.page(any(Page.class))).thenReturn(query);
        when(query.count()).thenReturn(count);
        when(query.pageCount()).thenReturn(pageCount);
        when(query.list()).thenReturn(users);
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * The list screen with a valid sort, an ascending direction, both filters populated and an admin
     * principal reaches the template, exercising the valid {@code sortKey} arm, the ascending order,
     * the populated {@code search} and {@code role} guards, the {@code where.length() > 0} join, the
     * non-username tie-breaker, the populated query ternary, the true arm of the security conjunction
     * and the present-principal arm of {@code currentUsername}.
     */
    @Test
    void listWithAdminValidSortAscendingReachesTemplate() {
        UserUiResource resource = new UserUiResource();
        SecurityContext ctx = context("admin", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubQuery(panache, List.of(realUser("bob", "ADMIN", true)), 2L, 1);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(ctx, "milk", "ADMIN", "displayName", "asc", 1, null, true));
        }
    }

    /**
     * The list screen with an unknown sort, a descending direction, no filters and a non-admin
     * principal that carries no principal object reaches the template, exercising the fallback
     * {@code sortKey} arm, the descending order, the null {@code search} and {@code role} guards, the
     * username tie-breaker, the empty query ternary, the false right arm of the security conjunction
     * and the missing-principal arm of {@code currentUsername}.
     */
    @Test
    void listWithInvalidSortDescendingNonAdminReachesTemplate() {
        UserUiResource resource = new UserUiResource();
        SecurityContext ctx = contextNoPrincipal(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubQuery(panache, List.of(), 0L, 0);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(ctx, null, null, "bogus", "DESC", 100, "hi", false));
        }
    }

    /**
     * The list screen with a null security context and blank filters reaches the template, exercising
     * the blank arms of the {@code search} and {@code role} guards, the false left arm of the security
     * conjunction and the null-context arm of {@code currentUsername}; a page below one is clamped up.
     */
    @Test
    void listWithNullContextBlankFiltersReachesTemplate() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubQuery(panache, List.of(), 0L, 0);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, "   ", "  ", "username", "asc", 0, null, true));
        }
    }

    /**
     * The list screen filtered on a role alone reaches the template, exercising the false arm of the
     * {@code where.length() > 0} join (no preceding search) while still producing a populated query.
     */
    @Test
    void listWithRoleFilterOnlyReachesTemplate() {
        UserUiResource resource = new UserUiResource();
        SecurityContext ctx = context("admin", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubQuery(panache, List.of(realUser("bob", "MANAGER", true)), 1L, 1);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(ctx, null, "MANAGER", "username", "asc", 1, null, true));
        }
    }

    // --------------------------------------------------
    // create
    // --------------------------------------------------

    /**
     * The creation form reaches the template, defaulting the new account to active before the native
     * call fails to link.
     */
    @Test
    void createReachesTemplate() {
        UserUiResource resource = new UserUiResource();
        assertThrows(UnsatisfiedLinkError.class, resource::create);
    }

    // --------------------------------------------------
    // edit
    // --------------------------------------------------

    /**
     * Editing a missing account answers a 404 without touching the template, exercising the true arm
     * of the account guard.
     */
    @Test
    void editMissingAccountReturnsNotFound() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            Response response = resource.edit(7L);
            assertEquals(404, response.getStatus());
            assertEquals("User 7 not found", response.getEntity());
        }
    }

    /**
     * Editing an existing account reaches the template, exercising the false arm of the account guard.
     */
    @Test
    void editExistingAccountReachesTemplate() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(realUser("bob", "MANAGER", true));
            assertThrows(UnsatisfiedLinkError.class, () -> resource.edit(3L));
        }
    }

    // --------------------------------------------------
    // save
    // --------------------------------------------------

    /**
     * Saving without a username re-renders the form, exercising the null arm of the trim ternary, the
     * false arm of the {@code active != null} flag, the null-username validation arm, the true arm of
     * the {@code error != null} decision and the null arm of {@code sanitizeRoles}.
     */
    @Test
    void saveWithNullUsernameRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save(null, "goodpass1", "Bob", null, null));
        }
    }

    /**
     * Saving with a blank username re-renders the form, exercising the non-null arm of the trim
     * ternary, the true arm of the {@code active != null} flag, the blank-username validation arm and
     * the valid-role arm of {@code sanitizeRoles}.
     */
    @Test
    void saveWithBlankUsernameRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("   ", "goodpass1", "Bob", List.of("ADMIN"), "on"));
        }
    }

    /**
     * Saving a duplicate username re-renders the form, exercising the true arm of the uniqueness
     * check.
     */
    @Test
    void saveWithDuplicateUsernameRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class))).thenReturn(1L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("bob", "goodpass1", "Bob", List.of("ADMIN"), "on"));
        }
    }

    /**
     * Saving with a weak password re-renders the form, exercising the false arm of the uniqueness
     * check and the error arm of {@code validatePassword}.
     */
    @Test
    void saveWithWeakPasswordRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class))).thenReturn(0L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("bob", "short", "Bob", List.of("ADMIN"), "on"));
        }
    }

    /**
     * Saving with no recognised role re-renders the form, exercising the success arm of
     * {@code validatePassword}, the true arm of {@code getRoleSet().isEmpty()} and both the null-role
     * and unknown-role rejections of {@code sanitizeRoles}.
     */
    @Test
    void saveWithNoRolesRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class))).thenReturn(0L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("bob", "goodpass1", "Bob", Arrays.asList(null, "SUPERUSER"), "on"));
        }
    }

    /**
     * A fully valid submission persists the account and redirects, exercising the false arm of the
     * {@code error != null} decision, the false arm of {@code getRoleSet().isEmpty()}, the forced
     * password change and the success redirect.
     */
    @Test
    void saveValidAccountPersistsAndRedirects() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<AppUser> construction = mockConstruction(AppUser.class,
                     (user, ctx) -> when(user.getRoleSet()).thenReturn(Set.of("ADMIN")))) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class))).thenReturn(0L);
            Response response = resource.save("bob", "goodpass1", "Bob", List.of("ADMIN"), "on");
            assertEquals(303, response.getStatus());
            assertEquals("/ui/users", response.getLocation().getPath());
            assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
            AppUser created = construction.constructed().get(0);
            assertEquals("bob", created.username);
            assertTrue(created.active);
            assertTrue(created.mustChangePassword);
            verify(created).setPassword("goodpass1");
            verify(created).persist();
        }
    }

    // --------------------------------------------------
    // update
    // --------------------------------------------------

    /**
     * Updating a missing account answers a 404 without touching the template, exercising the true arm
     * of the account guard.
     */
    @Test
    void updateMissingAccountReturnsNotFound() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(null);
            Response response = resource.update(9L, "goodpass1", "Bob", List.of("ADMIN"), "on", null);
            assertEquals(404, response.getStatus());
            assertEquals("User 9 not found", response.getEntity());
        }
    }

    /**
     * Updating an account with no recognised role re-renders the form, exercising the false arm of the
     * account guard, the true arm of the {@code active != null} flag and the empty-roles arm of
     * {@code validateUpdate}.
     */
    @Test
    void updateWithNoRolesRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(realUser("bob", "MANAGER", true));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.update(1L, null, "Bob", null, "on", null));
        }
    }

    /**
     * Updating an account with a weak password re-renders the form, exercising the non-empty-roles
     * arm, the populated arm of the {@code password} guard and the policy-error arm of
     * {@code validateUpdate}.
     */
    @Test
    void updateWithWeakPasswordRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(realUser("bob", "ADMIN", true));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.update(1L, "short", "Bob", List.of("ADMIN"), "on", null));
        }
    }

    /**
     * Demoting the last administrator re-renders the form, exercising the null arm of the
     * {@code password} guard, the true form of {@code losesAdmin}, the {@code nowActive} arm of
     * {@code losesAccess} and the true outcome of {@code isLastActiveAdmin}.
     */
    @Test
    void updateDemotingLastAdminRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(realUser("admin", "ADMIN", true));
            users.when(AppUser::countActiveAdmins).thenReturn(1L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.update(1L, null, "Admin", List.of("MANAGER"), "on", null));
        }
    }

    /**
     * Disabling the last administrator re-renders the form, exercising the false right arm of
     * {@code losesAdmin} (the role is kept), the true form of {@code losesAccess}, the false arm of
     * the {@code active != null} flag and the true outcome of {@code isLastActiveAdmin}.
     */
    @Test
    void updateDisablingLastAdminRendersError() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(realUser("admin", "ADMIN", true));
            users.when(AppUser::countActiveAdmins).thenReturn(1L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.update(1L, null, "Admin", List.of("ADMIN"), null, null));
        }
    }

    /**
     * Demoting an administrator that is not the last one succeeds, exercising the populated arm of the
     * body {@code password} guard, the success arm of {@code validatePassword}, the false outcome of
     * {@code isLastActiveAdmin} through {@code countActiveAdmins() > 1} and the password reset.
     */
    @Test
    void updateDemotingNonLastAdminSucceeds() {
        UserUiResource resource = new UserUiResource();
        AppUser user = realUser("admin", "ADMIN", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(user);
            users.when(AppUser::countActiveAdmins).thenReturn(2L);
            Response response = resource.update(1L, "newgoodpass1", "Admin", List.of("MANAGER"), "on", null);
            assertEquals(303, response.getStatus());
            assertEquals("/ui/users", response.getLocation().getPath());
            assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
            assertEquals(Set.of("MANAGER"), user.getRoleSet());
            assertTrue(user.mustChangePassword);
            assertNotNull(user.password);
        }
    }

    /**
     * Demoting an inactive administrator succeeds, exercising the false left arm of
     * {@code losesAccess} (the account is already inactive), the {@code active == false} short-circuit
     * of {@code isLastActiveAdmin} and the null arm of the body {@code password} guard.
     */
    @Test
    void updateDemotingInactiveAdminSucceeds() {
        UserUiResource resource = new UserUiResource();
        AppUser user = realUser("admin", "ADMIN", false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(user);
            Response response = resource.update(1L, null, "Admin", List.of("MANAGER"), null, null);
            assertEquals(303, response.getStatus());
            assertEquals(Set.of("MANAGER"), user.getRoleSet());
            assertFalse(user.mustChangePassword);
            assertNull(user.password);
        }
    }

    /**
     * Disabling a non-administrator succeeds, exercising the false left arm of {@code losesAdmin} (the
     * account holds no admin role), the {@code hasRole == false} short-circuit of
     * {@code isLastActiveAdmin} and the blank arm of the {@code password} guards.
     */
    @Test
    void updateDisablingNonAdminSucceeds() {
        UserUiResource resource = new UserUiResource();
        AppUser user = realUser("bob", "MANAGER", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(user);
            Response response = resource.update(1L, "  ", "Bob", List.of("MANAGER"), null, null);
            assertEquals(303, response.getStatus());
            assertFalse(user.active);
            assertNull(user.password);
        }
    }

    /**
     * Updating an administrator without touching its role or activation succeeds, exercising the false
     * arm of the {@code (losesAdmin || losesAccess)} decision so {@code isLastActiveAdmin} is never
     * consulted.
     */
    @Test
    void updateKeepingAdminActiveSucceeds() {
        UserUiResource resource = new UserUiResource();
        AppUser user = realUser("admin", "ADMIN", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(user);
            Response response = resource.update(1L, null, "Admin", List.of("ADMIN", "MANAGER"), "on", null);
            assertEquals(303, response.getStatus());
            assertTrue(user.getRoleSet().containsAll(Set.of("ADMIN", "MANAGER")));
        }
    }

    // --------------------------------------------------
    // delete
    // --------------------------------------------------

    /**
     * Deleting a missing account redirects with a failure notice, exercising the true arm of the
     * account guard.
     */
    @Test
    void deleteMissingAccountRedirectsWithFailure() {
        UserUiResource resource = new UserUiResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(4L)).thenReturn(null);
            Response response = resource.delete(4L, context("admin", true));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
        }
    }

    /**
     * Deleting one's own account is refused, exercising the false arm of the account guard, the true
     * arm of the self-deletion guard and the present-principal arm of {@code currentUsername}.
     */
    @Test
    void deleteOwnAccountRedirectsWithFailure() {
        UserUiResource resource = new UserUiResource();
        AppUser user = mockUser("alice", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(4L)).thenReturn(user);
            Response response = resource.delete(4L, context("alice", true));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
            verify(user, org.mockito.Mockito.never()).delete();
        }
    }

    /**
     * Deleting the last administrator is refused, exercising the false arm of the self-deletion guard
     * (resolved against a null context), the true arm of the last-administrator guard and the
     * populated conjunction of {@code isLastActiveAdmin}.
     */
    @Test
    void deleteLastAdminRedirectsWithFailure() {
        UserUiResource resource = new UserUiResource();
        AppUser user = mockUser("admin", true);
        when(user.hasRole(AppUser.ROLE_ADMIN)).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(4L)).thenReturn(user);
            users.when(AppUser::countActiveAdmins).thenReturn(1L);
            Response response = resource.delete(4L, null);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
            verify(user, org.mockito.Mockito.never()).delete();
        }
    }

    /**
     * Deleting a regular account succeeds, exercising the false arm of the last-administrator guard
     * (through the inactive short-circuit of {@code isLastActiveAdmin}), the deletion and the success
     * redirect.
     */
    @Test
    void deleteRegularAccountSucceeds() {
        UserUiResource resource = new UserUiResource();
        AppUser user = mockUser("bob", false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(4L)).thenReturn(user);
            Response response = resource.delete(4L, context("admin", true));
            assertEquals(303, response.getStatus());
            assertEquals("/ui/users", response.getLocation().getPath());
            assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
            verify(user).delete();
        }
    }
}
