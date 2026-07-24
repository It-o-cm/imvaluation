package com.intermarche.valuation.security;

import com.intermarche.valuation.domain.AppUser;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Creates the initial administrator account when the user table is empty.
 * <p>
 * Authentication now reads from the database, so a freshly created schema would otherwise
 * leave nobody able to sign in. This is especially true in development, where the H2
 * database is dropped and recreated at every start.
 * <p>
 * The bootstrap only ever runs when <b>no</b> user exists at all: once an account has been
 * created, this class never touches the table again, and in particular never resets a
 * password that an administrator has changed.
 */
@Singleton
public class UserBootstrap {

    private static final Logger LOGGER = Logger.getLogger(UserBootstrap.class);

    /**
     * Login name of the account created on an empty database.
     */
    @ConfigProperty(name = "valuation.bootstrap.admin.username", defaultValue = "admin")
    String bootstrapUsername;

    /**
     * Clear text password of the account created on an empty database.
     * <p>
     * Override it in every environment that is not a local workstation.
     */
    @ConfigProperty(name = "valuation.bootstrap.admin.password", defaultValue = "admin")
    String bootstrapPassword;

    /**
     * Creates the initial administrator if the user table holds no account.
     *
     * @param event The application startup event.
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (AppUser.count() > 0) {
            return;
        }
        AppUser admin = new AppUser();
        admin.username = bootstrapUsername;
        admin.setPassword(bootstrapPassword);
        admin.setRoleSet(Set.of(AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN));
        admin.displayName = "Bootstrap administrator";
        admin.active = true;
        // The password comes from the configuration, so it is known outside the account:
        // the first sign-in is confined to the password screen until it is replaced.
        admin.mustChangePassword = true;
        admin.persist();
        LOGGER.warn("No user found: created bootstrap administrator '" + bootstrapUsername
                + "'. Change its password before exposing this instance.");
    }
}
