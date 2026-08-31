package com.intermarche.valuation.domain;

import com.intermarche.valuation.CoverageDbReset;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link AppUser}, exercised through {@code @QuarkusTest} so the container
 * attributes the executed lines: the credential helpers run in memory while the e-mail and
 * count finders run against committed rows.
 * <p>
 * Created accounts are prefixed with {@code test_} and the bootstrap {@code admin} is never
 * touched, so the shared in-memory database is left as it was found.
 */
@QuarkusTest
public class AppUserFindersCoverageTest {

    /**
     * Clears the {@code test_} accounts and other coverage rows after this class.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }

    /**
     * Removes the {@code test_} accounts before each test, preserving the bootstrap admin.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        AppUser.delete("username like ?1", "test_%");
    }

    /**
     * Persists an active user carrying the given attributes in its own committed transaction.
     *
     * @param username The login name, expected to start with {@code test_}.
     * @param email    The e-mail address, or null.
     * @param roles    The comma-separated roles.
     */
    private void seedUser(String username, String email, String roles) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = username;
            user.email = email;
            user.roles = roles;
            user.active = true;
            user.setPassword("secret123");
            user.persist();
        });
    }

    /**
     * Tests {@link AppUser#matchesPassword(String)} across its null guards, the successful
     * verification, the mismatching verification and the undecodable-hash catch.
     */
    @Test
    void testMatchesPassword() throws Exception {
        AppUser withHash = new AppUser();
        withHash.setPassword("secret123");
        assertTrue(withHash.matchesPassword("secret123"));
        assertFalse(withHash.matchesPassword("wrong"));
        assertFalse(withHash.matchesPassword(null));
        // A fresh account has a null stored hash, so no candidate can match.
        assertFalse(new AppUser().matchesPassword("secret123"));
        // An undecodable stored hash matches nothing: the raw field is set by reflection
        // because the setter would hash the value instead of storing it verbatim.
        AppUser badHash = new AppUser();
        java.lang.reflect.Field field = AppUser.class.getDeclaredField("password");
        field.setAccessible(true);
        field.set(badHash, "not-a-valid-modular-crypt-hash");
        assertFalse(badHash.matchesPassword("secret123"));
    }

    /**
     * Tests that {@link AppUser#getRoleSet()} returns an empty set for null or blank roles and
     * the trimmed, non-empty tokens otherwise.
     */
    @Test
    void testGetRoleSet() {
        AppUser blank = new AppUser();
        blank.roles = "   ";
        assertTrue(blank.getRoleSet().isEmpty());
        AppUser nullRoles = new AppUser();
        nullRoles.roles = null;
        assertTrue(nullRoles.getRoleSet().isEmpty());
        AppUser user = new AppUser();
        user.roles = "VIEWER, ,ADMIN";
        assertEquals(Set.of("VIEWER", "ADMIN"), user.getRoleSet());
    }

    /**
     * Tests that {@link AppUser#getLabel()} returns the display name when set and falls back
     * to the username when it is null or blank.
     */
    @Test
    void testGetLabel() {
        AppUser named = new AppUser();
        named.username = "test_alice";
        named.displayName = "Alice Doe";
        assertEquals("Alice Doe", named.getLabel());
        AppUser nullName = new AppUser();
        nullName.username = "test_alice";
        nullName.displayName = null;
        assertEquals("test_alice", nullName.getLabel());
        AppUser blankName = new AppUser();
        blankName.username = "test_alice";
        blankName.displayName = "   ";
        assertEquals("test_alice", blankName.getLabel());
    }

    /**
     * Tests that {@link AppUser#findActiveByEmail(String)} returns null for a blank address
     * and the matching active account for a case-insensitive, padded address.
     */
    @Test
    void testFindActiveByEmail() {
        seedUser("test_cover", "cover@test.com", AppUser.ROLE_VIEWER);
        assertNull(AppUser.findActiveByEmail(null));
        assertNull(AppUser.findActiveByEmail("   "));
        AppUser found = AppUser.findActiveByEmail("  COVER@test.com  ");
        assertNotNull(found);
        assertEquals("test_cover", found.username);
        assertNull(AppUser.findActiveByEmail("missing@test.com"));
    }

    /**
     * Tests that {@link AppUser#findByUsername(String)} resolves a persisted account and
     * returns null for an unknown login name.
     */
    @Test
    void testFindByUsername() {
        seedUser("test_bob", "bob@test.com", AppUser.ROLE_MANAGER);
        assertNotNull(AppUser.findByUsername("test_bob"));
        assertNull(AppUser.findByUsername("test_nobody"));
    }

    /**
     * Tests that {@link AppUser#countActiveAdmins()} counts each additional active admin,
     * asserted relative to the baseline so the bootstrap admin is not assumed away.
     */
    @Test
    void testCountActiveAdmins() {
        long baseline = AppUser.countActiveAdmins();
        seedUser("test_admin", "admin2@test.com", AppUser.ROLE_ADMIN);
        assertEquals(baseline + 1, AppUser.countActiveAdmins());
    }
}
