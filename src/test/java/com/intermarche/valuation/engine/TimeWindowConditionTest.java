package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeWindowCondition}: the half-open {@code [from, to)} window, the
 * midnight-spanning case, the optional weekday filter, the empty-window case, and the
 * empty-contributors contract. Time is driven through {@link DateTimeProvider}, never the wall
 * clock, so the assertions are deterministic.
 * <p>
 * The reference dates are real weekdays: 2024-01-01 is a Monday, 2024-01-02 a Tuesday and
 * 2024-01-05 a Friday.
 */
public class TimeWindowConditionTest {

    /**
     * A Monday, used for the weekday-filter and in-window assertions.
     */
    private static final java.time.LocalDate MONDAY = java.time.LocalDate.of(2024, 1, 1);

    /**
     * A Tuesday, used for the weekday-filter negative assertion.
     */
    private static final java.time.LocalDate TUESDAY = java.time.LocalDate.of(2024, 1, 2);

    /**
     * A Friday, used for the second listed weekday.
     */
    private static final java.time.LocalDate FRIDAY = java.time.LocalDate.of(2024, 1, 5);

    /**
     * Clears the fixed time after each test so nothing leaks between them.
     */
    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    /**
     * Evaluates a condition at a fixed moment.
     *
     * @param moment    the moment to freeze the clock at.
     * @param condition the condition to evaluate.
     * @return whether the condition is satisfied at that moment.
     */
    private boolean satisfiedAt(LocalDateTime moment, TimeWindowCondition condition) {
        DateTimeProvider.setFixedDateTime(moment);
        return condition.evaluate(new BasketEvaluation(new Basket())).satisfied();
    }

    /**
     * Tests that a moment inside a normal window is satisfied and one outside is not, with the
     * lower bound included and the upper bound excluded.
     */
    @Test
    void testNormalWindowBounds() {
        TimeWindowCondition window = new TimeWindowCondition(Set.of(),
                LocalTime.of(17, 0), LocalTime.of(19, 0));
        assertTrue(satisfiedAt(MONDAY.atTime(18, 0), window), "18:00 is inside");
        assertTrue(satisfiedAt(MONDAY.atTime(17, 0), window), "17:00 is the included lower bound");
        assertFalse(satisfiedAt(MONDAY.atTime(19, 0), window), "19:00 is the excluded upper bound");
        assertFalse(satisfiedAt(MONDAY.atTime(16, 59), window), "16:59 is before the window");
        assertFalse(satisfiedAt(MONDAY.atTime(19, 1), window), "19:01 is after the window");
    }

    /**
     * Tests the midnight-spanning window 22:00 -> 02:00: 23:50 and 00:10 are inside, 12:00 is
     * outside (spec-divers-offre §5.1).
     */
    @Test
    void testMidnightSpanningWindow() {
        TimeWindowCondition window = new TimeWindowCondition(Set.of(),
                LocalTime.of(22, 0), LocalTime.of(2, 0));
        assertTrue(satisfiedAt(MONDAY.atTime(23, 50), window), "23:50 is inside the wrap");
        assertTrue(satisfiedAt(MONDAY.atTime(0, 10), window), "00:10 is inside the wrap");
        assertTrue(satisfiedAt(MONDAY.atTime(22, 0), window), "22:00 is the included lower bound");
        assertFalse(satisfiedAt(MONDAY.atTime(2, 0), window), "02:00 is the excluded upper bound");
        assertFalse(satisfiedAt(MONDAY.atTime(12, 0), window), "12:00 is outside the wrap");
    }

    /**
     * Tests that the weekday filter is honoured: the window is active on the listed days and
     * silent on the others, independent of the time being inside.
     */
    @Test
    void testWeekdayFilter() {
        TimeWindowCondition window = new TimeWindowCondition(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                LocalTime.of(17, 0), LocalTime.of(19, 0));
        assertTrue(satisfiedAt(MONDAY.atTime(18, 0), window), "Monday 18:00 is inside and allowed");
        assertTrue(satisfiedAt(FRIDAY.atTime(18, 0), window), "Friday 18:00 is inside and allowed");
        assertFalse(satisfiedAt(TUESDAY.atTime(18, 0), window), "Tuesday is not a listed day");
    }

    /**
     * Tests that an empty weekday set means every day.
     */
    @Test
    void testEmptyDaysMeansEveryDay() {
        TimeWindowCondition window = new TimeWindowCondition(Set.of(),
                LocalTime.of(17, 0), LocalTime.of(19, 0));
        assertTrue(satisfiedAt(MONDAY.atTime(18, 0), window), "Monday inside");
        assertTrue(satisfiedAt(TUESDAY.atTime(18, 0), window), "Tuesday inside");
        assertTrue(satisfiedAt(FRIDAY.atTime(18, 0), window), "Friday inside");
    }

    /**
     * Tests that a window whose bounds are equal is empty (never satisfied), consistent with the
     * empty interval {@code [t, t)}.
     */
    @Test
    void testEqualBoundsIsEmptyWindow() {
        TimeWindowCondition window = new TimeWindowCondition(Set.of(),
                LocalTime.of(12, 0), LocalTime.of(12, 0));
        assertFalse(satisfiedAt(MONDAY.atTime(12, 0), window), "an equal-bounds window is empty");
        assertFalse(satisfiedAt(MONDAY.atTime(9, 0), window), "still empty before");
    }

    /**
     * Tests that the condition never carries contributors, whether satisfied or not.
     */
    @Test
    void testNoContributors() {
        TimeWindowCondition window = new TimeWindowCondition(Set.of(),
                LocalTime.of(17, 0), LocalTime.of(19, 0));
        DateTimeProvider.setFixedDateTime(MONDAY.atTime(18, 0));
        TriggerResult inside = window.evaluate(new BasketEvaluation(new Basket()));
        assertTrue(inside.satisfied());
        assertTrue(inside.contributors().isEmpty(), "satisfied window carries no contributors");
        DateTimeProvider.setFixedDateTime(MONDAY.atTime(9, 0));
        TriggerResult outside = window.evaluate(new BasketEvaluation(new Basket()));
        assertFalse(outside.satisfied());
        assertTrue(outside.contributors().isEmpty(), "unsatisfied window carries no contributors");
    }

    /**
     * Tests that the accessors return the stored window, and that a null day set becomes empty.
     */
    @Test
    void testAccessors() {
        TimeWindowCondition window = new TimeWindowCondition(null,
                LocalTime.of(8, 30), LocalTime.of(10, 45));
        assertTrue(window.getDays().isEmpty(), "a null day set becomes empty");
        assertEquals(LocalTime.of(8, 30), window.getFrom());
        assertEquals(LocalTime.of(10, 45), window.getTo());
    }
}
