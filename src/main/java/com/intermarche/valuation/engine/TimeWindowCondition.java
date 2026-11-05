package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.util.DateTimeProvider;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Leaf condition of kind {@code TIME_WINDOW}: satisfied when the moment of valuation falls
 * inside a recurring local-time window, optionally restricted to a set of weekdays
 * (spec-divers-offre §1).
 * <p>
 * The window is a half-open local-time interval {@code [from, to)} — {@code from} included,
 * {@code to} excluded — so two adjacent windows never overlap on their shared bound. A window
 * whose {@code from} is after {@code to} spans midnight (e.g. {@code 22:00 -> 02:00}): it is
 * satisfied from {@code from} to the end of the day and from the start of the next day up to
 * {@code to}. A window whose bounds are equal is empty (never satisfied), consistent with the
 * empty interval {@code [t, t)}.
 * <p>
 * The weekday filter, when present, is matched against the weekday of the current moment. For
 * a midnight-spanning window this means the after-midnight portion is attributed to the next
 * day: a {@code MONDAY} window {@code 22:00 -> 02:00} covers Monday 22:00–24:00 and, only if
 * {@code TUESDAY} is also listed, Tuesday 00:00–02:00. An absent (empty) day set means every
 * day.
 * <p>
 * The moment is read through {@link DateTimeProvider#now()} — never the wall clock directly —
 * so replays and tests are deterministic. Like a coupon, a time window has no line-level
 * attribution, so this condition never carries contributors (spec §4.4).
 */
public final class TimeWindowCondition implements TriggerCondition {

    /**
     * The weekdays the window is active on; empty means every day.
     */
    private final Set<DayOfWeek> days;

    /**
     * The inclusive start of the window, a local time-of-day.
     */
    private final LocalTime from;

    /**
     * The exclusive end of the window, a local time-of-day.
     */
    private final LocalTime to;

    /**
     * Builds a time-window condition.
     *
     * @param days the active weekdays; copied defensively, {@code null} treated as every day.
     * @param from the inclusive start of the window, never {@code null}.
     * @param to   the exclusive end of the window, never {@code null}.
     */
    public TimeWindowCondition(Set<DayOfWeek> days, LocalTime from, LocalTime to) {
        this.days = days == null ? Set.of() : Set.copyOf(days);
        this.from = from;
        this.to = to;
    }

    /**
     * Returns the active weekdays.
     *
     * @return an unmodifiable set of weekdays, empty when the window applies every day.
     */
    public Set<DayOfWeek> getDays() {
        return days;
    }

    /**
     * Returns the inclusive start of the window.
     *
     * @return the start time-of-day.
     */
    public LocalTime getFrom() {
        return from;
    }

    /**
     * Returns the exclusive end of the window.
     *
     * @return the end time-of-day.
     */
    public LocalTime getTo() {
        return to;
    }

    /**
     * Evaluates the condition against the current moment obtained from {@link DateTimeProvider}.
     *
     * @param evaluation the evaluation context (unused: the window depends on time, not lines).
     * @return satisfied when the current weekday is allowed and the current time-of-day is
     *         inside the half-open window; contributors are always empty.
     */
    @Override
    public TriggerResult evaluate(BasketEvaluation evaluation) {
        LocalDateTime now = DateTimeProvider.now();
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek())) {
            return new TriggerResult(false, List.of());
        }
        return new TriggerResult(contains(now.toLocalTime()), List.of());
    }

    /**
     * Tells whether a time-of-day falls inside the half-open window, honouring midnight spans.
     *
     * @param time the time-of-day to test.
     * @return {@code true} when {@code time} is inside {@code [from, to)}, or inside the
     *         wrap-around window when {@code from} is after {@code to}.
     */
    private boolean contains(LocalTime time) {
        if (from.equals(to)) {
            return false;
        }
        if (from.isBefore(to)) {
            return !time.isBefore(from) && time.isBefore(to);
        }
        return !time.isBefore(from) || time.isBefore(to);
    }
}
