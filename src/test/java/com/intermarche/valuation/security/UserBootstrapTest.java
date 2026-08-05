package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link UserBootstrap}.
 * <p>
 * The class exposes a single branch in {@code onStart}: the {@code AppUser.count() > 0}
 * guard. Both arms are exercised — an already populated table (the bootstrap is skipped and
 * no account is constructed) and an empty table (the administrator is built, fully
 * configured and persisted). The inherited static counter {@code AppUser.count()} resolves
 * to {@link PanacheEntityBase#count()} under plain unit tests, so it is stubbed with
 * {@link org.mockito.Mockito#mockStatic} on that class, and the entity's {@code persist()}
 * is neutralised with {@link org.mockito.Mockito#mockConstruction}, as required for Panache
 * entities.
 */
public class UserBootstrapTest {

    /**
     * Verifies that when at least one account already exists the bootstrap returns without
     * constructing or persisting any {@link AppUser}.
     */
    @Test
    void testExistingUsersSkipBootstrap() {
        UserBootstrap bootstrap = new UserBootstrap();
        bootstrap.bootstrapUsername = "admin";
        bootstrap.bootstrapPassword = "secret-password";
        try (MockedStatic<PanacheEntityBase> mockedStatic = mockStatic(PanacheEntityBase.class);
             MockedConstruction<AppUser> mockedConstruction = mockConstruction(AppUser.class)) {
            mockedStatic.when(PanacheEntityBase::count).thenReturn(1L);
            bootstrap.onStart(mock(StartupEvent.class));
            assertTrue(mockedConstruction.constructed().isEmpty());
        }
    }

    /**
     * Verifies that on an empty table the administrator is created with the configured
     * credentials, the full set of roles, the display name, the active flag and the pending
     * password change flag, and is persisted exactly once.
     */
    @Test
    void testEmptyDatabaseCreatesAdmin() {
        UserBootstrap bootstrap = new UserBootstrap();
        bootstrap.bootstrapUsername = "root";
        bootstrap.bootstrapPassword = "top-secret";
        try (MockedStatic<PanacheEntityBase> mockedStatic = mockStatic(PanacheEntityBase.class);
             MockedConstruction<AppUser> mockedConstruction = mockConstruction(AppUser.class)) {
            mockedStatic.when(PanacheEntityBase::count).thenReturn(0L);
            bootstrap.onStart(mock(StartupEvent.class));
            assertEquals(1, mockedConstruction.constructed().size());
            AppUser admin = mockedConstruction.constructed().get(0);
            assertEquals("root", admin.username);
            assertEquals("Bootstrap administrator", admin.displayName);
            assertTrue(admin.active);
            assertTrue(admin.mustChangePassword);
            verify(admin).setPassword("top-secret");
            verify(admin).setRoleSet(Set.of(AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN));
            verify(admin).persist();
        }
    }
}
