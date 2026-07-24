package com.intermarche.valuation.domain;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.security.jpa.Password;
import io.quarkus.security.jpa.Roles;
import io.quarkus.security.jpa.UserDefinition;
import io.quarkus.security.jpa.Username;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.util.ModularCrypt;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entity representing an application user allowed to sign in.
 * <p>
 * The {@link UserDefinition} annotation lets {@code quarkus-security-jpa} authenticate
 * against this table directly: no custom identity provider is needed, and the very same
 * {@code @RolesAllowed} annotations already guarding the GraphQL and UI resources keep
 * working unchanged.
 * <p>
 * Passwords are never stored in clear text. {@link #setPassword(String)} hashes them with
 * bcrypt, which is the format the extension expects when verifying a login.
 * <p>
 * Roles are held as a single comma separated string, as required by the extension. The
 * helper methods below expose them as a set for application code.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "app_users",
        indexes = @Index(name = "idx_app_user_username", columnList = "username")
)
@UserDefinition
public class AppUser extends BaseEntity {

    /**
     * Role granting read-only access: browsing and exporting, nothing else.
     */
    public static final String ROLE_VIEWER = "VIEWER";

    /**
     * Role granting read access across the administration screens.
     */
    public static final String ROLE_MANAGER = "MANAGER";

    /**
     * Role granting full access, including creation, deletion and bulk import.
     */
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * Every role the application recognises, ordered from least to most privileged.
     */
    public static final List<String> ALL_ROLES = List.of(ROLE_VIEWER, ROLE_MANAGER, ROLE_ADMIN);

    // --------------------------------------------------
    // Credentials
    // --------------------------------------------------

    /**
     * The login name, unique across the application.
     */
    @Username
    @Column(name = "username", unique = true, nullable = false, length = 60)
    @NotBlank(message = "Username is mandatory")
    public String username;

    /**
     * The bcrypt hash of the password.
     * <p>
     * Never assign this field directly: use {@link #setPassword(String)} so the value is
     * always hashed before it reaches the database.
     */
    @Password
    @Column(name = "password", nullable = false, length = 100)
    @NotBlank(message = "Password is mandatory")
    public String password;

    /**
     * The roles granted to the user, comma separated (e.g. "MANAGER,ADMIN").
     */
    @Roles
    @Column(name = "roles", nullable = false, length = 200)
    @NotBlank(message = "At least one role is mandatory")
    public String roles;

    // --------------------------------------------------
    // Profile
    // --------------------------------------------------

    /**
     * The name displayed in the interface, falling back to the username when absent.
     */
    @Column(name = "display_name", length = 100)
    public String displayName;

    /**
     * Whether the account may sign in.
     * <p>
     * Disabling an account is preferred over deleting it, so the audit trail left on the
     * entities this user created stays meaningful.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    /**
     * Whether the user must change their password before reaching any other screen.
     * <p>
     * Set on the bootstrap account, whose password is known from the configuration, and
     * whenever an administrator resets someone's credentials.
     */
    @Column(name = "must_change_password", nullable = false)
    public boolean mustChangePassword = false;

    // --------------------------------------------------
    // Credentials handling
    // --------------------------------------------------

    /**
     * Minimum number of characters a password must contain.
     */
    public static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Hashes a clear text password and stores the result.
     * <p>
     * This is the only supported way to set a password: assigning {@link #password}
     * directly would store it in clear text and break authentication.
     *
     * @param clearText The password as typed by the user.
     */
    public void setPassword(String clearText) {
        this.password = BcryptUtil.bcryptHash(clearText);
    }

    /**
     * Verifies a clear text password against the stored hash.
     * <p>
     * The stored value is a Modular Crypt Format string carrying its own salt, so the
     * candidate cannot simply be re-hashed and compared: the salt is read back from the
     * stored hash and applied to the candidate.
     *
     * @param clearText The password to verify.
     * @return {@code true} when the password matches.
     */
    public boolean matchesPassword(String clearText) {
        if (clearText == null || password == null) {
            return false;
        }
        try {
            PasswordFactory factory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT);
            // Fully qualified: the simple name Password is taken by the field annotation.
            org.wildfly.security.password.Password stored = factory.translate(
                    ModularCrypt.decode(password.toCharArray()));
            return factory.verify(stored, clearText.toCharArray());
        } catch (GeneralSecurityException e) {
            // A hash that cannot be decoded is a hash that cannot match.
            return false;
        }
    }

    /**
     * Validates a candidate password against the application policy.
     *
     * @param clearText The password to validate, may be null.
     * @return An error message when the password is unacceptable, {@code null} otherwise.
     */
    public static String validatePassword(String clearText) {
        if (clearText == null || clearText.isBlank()) {
            return "The password is mandatory.";
        }
        if (clearText.length() < MIN_PASSWORD_LENGTH) {
            return "The password must be at least " + MIN_PASSWORD_LENGTH + " characters long.";
        }
        return null;
    }

    // --------------------------------------------------
    // Roles handling
    // --------------------------------------------------

    /**
     * Returns the granted roles as a set.
     *
     * @return The roles, never null.
     */
    public Set<String> getRoleSet() {
        if (roles == null || roles.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Replaces the granted roles.
     * <p>
     * Roles are stored in the canonical order defined by {@link #ALL_ROLES} so two users
     * holding the same roles always yield the same stored value, which keeps the checksum
     * stable across updates.
     *
     * @param newRoles The roles to grant.
     */
    public void setRoleSet(Set<String> newRoles) {
        this.roles = ALL_ROLES.stream()
                .filter(newRoles::contains)
                .collect(Collectors.joining(","));
    }

    /**
     * Indicates whether the user holds a given role.
     *
     * @param role The role to test.
     * @return {@code true} when the role is granted.
     */
    public boolean hasRole(String role) {
        return getRoleSet().contains(role);
    }

    // --------------------------------------------------
    // Display
    // --------------------------------------------------

    /**
     * Returns the label identifying the user in the interface.
     *
     * @return The display name, or the username when none is set.
     */
    public String getLabel() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a user by login name.
     *
     * @param username The login name to search for.
     * @return The user, or null when not found.
     */
    public static AppUser findByUsername(String username) {
        return find("username", username).firstResult();
    }

    /**
     * Counts the active users holding the administrator role.
     * <p>
     * Used to refuse the removal of the last administrator, which would otherwise lock
     * everyone out of the application.
     *
     * @return The number of active administrators.
     */
    public static long countActiveAdmins() {
        return count("active = true and roles like ?1", "%" + ROLE_ADMIN + "%");
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the user's key attributes.
     * <p>
     * The password hash is deliberately included, so a credential change is detected as a
     * modification like any other.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(username, password, roles, displayName, active, mustChangePassword);
    }
}
