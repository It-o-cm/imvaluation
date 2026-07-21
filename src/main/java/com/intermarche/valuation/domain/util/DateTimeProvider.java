
package com.intermarche.valuation.domain.util;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Utility class to provide the current date and time.
 * <p>
 * Encapsulates {@link LocalDateTime#now()} to allow deterministic time
 * for testing purposes.
 */
public final class DateTimeProvider {

    // Private constructor to prevent instantiation of the utility class
    private DateTimeProvider() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * The fixed date time currently set.
     * If null, the provider returns the real system time.
     */
    private static LocalDateTime fixedTime;

    /**
     * Returns the current LocalDateTime.
     * <p>
     * If a fixed time has been set using {@link #setFixedDateTime(LocalDateTime)},
     * that time is returned. Otherwise, it returns the system current time.
     *
     * @return The current LocalDateTime (or the fixed one).
     */
    public static LocalDateTime now() {
        return fixedTime != null ? fixedTime : LocalDateTime.now();
    }

    /**
     * Sets a fixed date and time to be returned by {@link #now()}.
     * <p>
     * This is useful for testing scenarios where you need to verify
     * timestamps independently of the actual execution time.
     *
     * @param time The fixed LocalDateTime to use. Must not be null.
     */
    public static void setFixedDateTime(LocalDateTime time) {
        Objects.requireNonNull(time, "Fixed time cannot be null");
        DateTimeProvider.fixedTime = time;
    }

    /**
     * Clears the fixed time.
     * <p>
     * After calling this method, {@link #now()} will revert to returning
     * the actual system time.
     */
    public static void clear() {
        DateTimeProvider.fixedTime = null;
    }
}
