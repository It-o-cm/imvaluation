package com.intermarche.valuation.domain.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DateTimeProvider}.
 * <p>
 * Verifies the behavior of static time management, ensuring that
 * the provider correctly switches between system time and fixed time.
 */
public class DateTimeProviderTest {

    /**
     * Cleans up the static state after each test.
     * <p>
     * This is crucial because {@code fixedTime} is a static field shared across tests.
     * Without cleanup, a fixed time set in one test would leak into subsequent tests.
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    /**
     * Tests that {@link DateTimeProvider#now()} returns the current system time
     * when no fixed time has been set.
     */
    @Test
    void now_shouldReturnSystemTime_whenNoFixedTimeIsSet() {
        // Capture system time before and after to account for execution latency
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime result = DateTimeProvider.now();
        LocalDateTime after = LocalDateTime.now();
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isBefore(before), "Result should not be before the start time");
        assertFalse(result.isAfter(after), "Result should not be after the end time");
    }

    /**
     * Tests that {@link DateTimeProvider#now()} returns the exact fixed time
     * previously set via {@link DateTimeProvider#setFixedDateTime(LocalDateTime)}.
     */
    @Test
    void now_shouldReturnFixedTime_whenTimeHasBeenSet() {
        LocalDateTime fixedTime = LocalDateTime.of(2023, 5, 10, 14, 30, 0);
        DateTimeProvider.setFixedDateTime(fixedTime);
        LocalDateTime result = DateTimeProvider.now();
        assertEquals(fixedTime, result, "now() must return exactly the fixed time");
    }

    /**
     * Tests that {@link DateTimeProvider#setFixedDateTime(LocalDateTime)}
     * throws a {@link NullPointerException} when the provided time is null.
     */
    @Test
    void setFixedDateTime_shouldThrowException_whenInputIsNull() {
        // Objects.requireNonNull throws NullPointerException if argument is null
        assertThrows(NullPointerException.class, () -> DateTimeProvider.setFixedDateTime(null));
    }

    /**
     * Tests that calling {@link DateTimeProvider#clear()} resets the provider
     * to return the actual system time.
     */
    @Test
    void clear_shouldRevertToSystemTime() {
        // 1. Set a fixed time
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), DateTimeProvider.now());
        // 2. Clear the fixed time
        DateTimeProvider.clear();
        // 3. Verify that system time is returned (using interval check)
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime result = DateTimeProvider.now();
        LocalDateTime after = LocalDateTime.now();
        assertFalse(result.isBefore(before));
        assertFalse(result.isAfter(after));
    }

    /**
     * Ensures that the private constructor throws an {@link UnsupportedOperationException}
     * to prevent instantiation of this utility class.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void constructor_shouldPreventInstantiation() throws Exception {
        // Use reflection to access the private constructor
        Constructor<DateTimeProvider> constructor = DateTimeProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Exception exception = assertThrows(InvocationTargetException.class, constructor::newInstance);
        // The exception thrown in the constructor is wrapped in an InvocationTargetException
        assertTrue(exception.getCause() instanceof UnsupportedOperationException);
        assertEquals("This is a utility class and cannot be instantiated", exception.getCause().getMessage());
    }
}