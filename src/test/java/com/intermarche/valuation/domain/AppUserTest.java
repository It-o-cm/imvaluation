package com.intermarche.valuation.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.wildfly.security.password.WildFlyElytronPasswordProvider;

import java.security.Security;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link AppUser}.
 * <p>
 * No {@code @QuarkusTest}, no database: collaborators are mocked and the inherited Panache
 * static finders (resolving to {@link PanacheEntityBase} under plain {@code mvn test}) are
 * mocked with {@link org.mockito.Mockito#mockStatic}. Password hashing and verification use
 * the real bcrypt library, which is pure computation and needs no application context.
 */
class AppUserTest {

    /**
     * Registers the WildFly Elytron password provider that Quarkus installs at boot but which
     * is absent from a plain JVM, so bcrypt verification in {@link AppUser#matchesPassword}
     * resolves an algorithm instead of throwing. Registration is idempotent and JVM global.
     */
    @BeforeAll
    static void registerPasswordProvider() {
        Security.addProvider(new WildFlyElytronPasswordProvider());
    }

    // --------------------------------------------------
    // setPassword
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#setPassword(String)} stores a bcrypt hash rather than the
     * clear text, and that the stored hash verifies against the original password.
     */
    @Test
    void setPassword_shouldStoreVerifiableBcryptHash() {
        AppUser user = new AppUser();
        user.setPassword("secret123");
        assertNotNull(user.password);
        assertNotEquals("secret123", user.password);
        assertTrue(user.matchesPassword("secret123"));
    }

    // --------------------------------------------------
    // matchesPassword
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#matchesPassword(String)} returns {@code false} when the
     * candidate password is null (first arm of the null guard).
     */
    @Test
    void matchesPassword_shouldReturnFalse_whenClearTextIsNull() {
        AppUser user = new AppUser();
        user.setPassword("secret123");
        assertFalse(user.matchesPassword(null));
    }

    /**
     * Tests that {@link AppUser#matchesPassword(String)} returns {@code false} when no hash
     * is stored (second arm of the null guard, candidate non-null).
     */
    @Test
    void matchesPassword_shouldReturnFalse_whenStoredPasswordIsNull() {
        AppUser user = new AppUser();
        user.password = null;
        assertFalse(user.matchesPassword("secret123"));
    }

    /**
     * Tests that {@link AppUser#matchesPassword(String)} returns {@code true} when the
     * candidate matches the stored hash (verify branch true).
     */
    @Test
    void matchesPassword_shouldReturnTrue_whenPasswordMatches() {
        AppUser user = new AppUser();
        user.setPassword("secret123");
        assertTrue(user.matchesPassword("secret123"));
    }

    /**
     * Tests that {@link AppUser#matchesPassword(String)} returns {@code false} when the
     * candidate does not match the stored hash (verify branch false).
     */
    @Test
    void matchesPassword_shouldReturnFalse_whenPasswordDiffers() {
        AppUser user = new AppUser();
        user.setPassword("secret123");
        assertFalse(user.matchesPassword("wrongPassword"));
    }

    /**
     * Tests that {@link AppUser#matchesPassword(String)} returns {@code false} when the
     * stored hash cannot be decoded, exercising the {@code GeneralSecurityException} catch.
     */
    @Test
    void matchesPassword_shouldReturnFalse_whenStoredHashIsUndecodable() {
        AppUser user = new AppUser();
        user.password = "not-a-valid-modular-crypt-hash";
        assertFalse(user.matchesPassword("secret123"));
    }

    // --------------------------------------------------
    // validatePassword
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#validatePassword(String)} rejects a null password (first arm
     * of the guard).
     */
    @Test
    void validatePassword_shouldRejectNull() {
        assertEquals("The password is mandatory.", AppUser.validatePassword(null));
    }

    /**
     * Tests that {@link AppUser#validatePassword(String)} rejects a blank password (second
     * arm of the guard, non-null value).
     */
    @Test
    void validatePassword_shouldRejectBlank() {
        assertEquals("The password is mandatory.", AppUser.validatePassword("   "));
    }

    /**
     * Tests that {@link AppUser#validatePassword(String)} rejects a password shorter than the
     * minimum length (length branch true).
     */
    @Test
    void validatePassword_shouldRejectTooShort() {
        assertEquals("The password must be at least 8 characters long.",
                AppUser.validatePassword("short"));
    }

    /**
     * Tests that {@link AppUser#validatePassword(String)} accepts a password meeting the
     * policy (both guards false), returning {@code null}.
     */
    @Test
    void validatePassword_shouldAcceptValid() {
        assertNull(AppUser.validatePassword("longEnough"));
    }

    // --------------------------------------------------
    // getRoleSet
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#getRoleSet()} returns an empty set when roles are null (first
     * arm of the guard).
     */
    @Test
    void getRoleSet_shouldReturnEmpty_whenRolesNull() {
        AppUser user = new AppUser();
        user.roles = null;
        assertTrue(user.getRoleSet().isEmpty());
    }

    /**
     * Tests that {@link AppUser#getRoleSet()} returns an empty set when roles are blank
     * (second arm of the guard, non-null value).
     */
    @Test
    void getRoleSet_shouldReturnEmpty_whenRolesBlank() {
        AppUser user = new AppUser();
        user.roles = "   ";
        assertTrue(user.getRoleSet().isEmpty());
    }

    /**
     * Tests that {@link AppUser#getRoleSet()} parses, trims and drops empty tokens, covering
     * both arms of the {@code !role.isEmpty()} filter.
     */
    @Test
    void getRoleSet_shouldParseTrimAndDropEmptyTokens() {
        AppUser user = new AppUser();
        user.roles = "VIEWER, ,ADMIN";
        Set<String> expected = new LinkedHashSet<>();
        expected.add("VIEWER");
        expected.add("ADMIN");
        assertEquals(expected, user.getRoleSet());
    }

    // --------------------------------------------------
    // setRoleSet
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#setRoleSet(Set)} stores roles in the canonical order and
     * drops unknown roles, exercising both arms of the {@code contains} filter.
     */
    @Test
    void setRoleSet_shouldStoreInCanonicalOrderAndDropUnknown() {
        AppUser user = new AppUser();
        Set<String> input = new LinkedHashSet<>();
        input.add("ADMIN");
        input.add("VIEWER");
        input.add("UNKNOWN");
        user.setRoleSet(input);
        assertEquals("VIEWER,ADMIN", user.roles);
    }

    /**
     * Tests that {@link AppUser#setRoleSet(Set)} produces an empty string when no known role
     * is granted (filter always false).
     */
    @Test
    void setRoleSet_shouldStoreEmptyString_whenNoKnownRole() {
        AppUser user = new AppUser();
        user.setRoleSet(new LinkedHashSet<>());
        assertEquals("", user.roles);
    }

    // --------------------------------------------------
    // hasRole
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#hasRole(String)} returns {@code true} for a granted role.
     */
    @Test
    void hasRole_shouldReturnTrue_whenGranted() {
        AppUser user = new AppUser();
        user.roles = "VIEWER,ADMIN";
        assertTrue(user.hasRole("ADMIN"));
    }

    /**
     * Tests that {@link AppUser#hasRole(String)} returns {@code false} for a role that is not
     * granted.
     */
    @Test
    void hasRole_shouldReturnFalse_whenNotGranted() {
        AppUser user = new AppUser();
        user.roles = "VIEWER";
        assertFalse(user.hasRole("ADMIN"));
    }

    // --------------------------------------------------
    // getLabel
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#getLabel()} falls back to the username when the display name
     * is null (first arm of the ternary condition).
     */
    @Test
    void getLabel_shouldReturnUsername_whenDisplayNameNull() {
        AppUser user = new AppUser();
        user.username = "alice";
        user.displayName = null;
        assertEquals("alice", user.getLabel());
    }

    /**
     * Tests that {@link AppUser#getLabel()} falls back to the username when the display name
     * is blank (second arm of the ternary condition, non-null value).
     */
    @Test
    void getLabel_shouldReturnUsername_whenDisplayNameBlank() {
        AppUser user = new AppUser();
        user.username = "alice";
        user.displayName = "   ";
        assertEquals("alice", user.getLabel());
    }

    /**
     * Tests that {@link AppUser#getLabel()} returns the display name when it is set (both arms
     * of the ternary condition false).
     */
    @Test
    void getLabel_shouldReturnDisplayName_whenSet() {
        AppUser user = new AppUser();
        user.username = "alice";
        user.displayName = "Alice Doe";
        assertEquals("Alice Doe", user.getLabel());
    }

    // --------------------------------------------------
    // findByUsername
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#findByUsername(String)} delegates to the Panache finder and
     * returns its first result.
     */
    @Test
    void findByUsername_shouldReturnFirstResult() {
        AppUser expected = new AppUser();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<AppUser> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            mocked.when(() -> PanacheEntityBase.find("username", "alice")).thenReturn(query);
            assertSame(expected, AppUser.findByUsername("alice"));
        }
    }

    // --------------------------------------------------
    // countActiveAdmins
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#countActiveAdmins()} delegates to the Panache count query.
     */
    @Test
    void countActiveAdmins_shouldReturnCount() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.count(
                    "active = true and roles like ?1", "%ADMIN%")).thenReturn(3L);
            assertEquals(3L, AppUser.countActiveAdmins());
        }
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    /**
     * Tests that {@link AppUser#getChecksum()} hashes exactly the declared business fields.
     */
    @Test
    void getChecksum_shouldHashBusinessFields() {
        AppUser user = new AppUser();
        user.username = "alice";
        user.password = "hash";
        user.roles = "ADMIN";
        user.displayName = "Alice";
        user.active = true;
        user.mustChangePassword = false;
        int expected = Objects.hash("alice", "hash", "ADMIN", "Alice", true, false);
        assertEquals(expected, user.getChecksum());
    }
}
