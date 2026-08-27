package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Shared building block for threshold-based (tiered) mechanics.
 * <p>
 * A tier table holds an ascending list of thresholds, each carrying an arbitrary award
 * payload, and offers the three resolution semantics used by the engine:
 * <ul>
 *   <li>{@link #resolveHighest(BigDecimal)} — the highest tier whose threshold is reached
 *       applies once ("5% from 50€, 10% from 100€");</li>
 *   <li>{@link #slices(BigDecimal)} — progressive brackets: each tier's threshold is the
 *       floor of a slice and its award applies to that slice only, the last slice being
 *       open-ended ("5% on the first 50€, 10% on the next 50€");</li>
 *   <li>{@link #multiples(BigDecimal, BigDecimal)} — a single repeating step ("1€ for
 *       every 50€ spent").</li>
 * </ul>
 * This class is a plain value object with no CDI lifecycle: offer factories compose it,
 * they never inherit from it. Duplicate thresholds are tolerated for compatibility with
 * legacy specifications; on ties, {@link #resolveHighest(BigDecimal)} deterministically
 * returns the last tier of the tie in ascending order.
 *
 * @param <A> the type of the award payload carried by each tier.
 */
public final class TierTable<A> {

    /**
     * One tier of the table: a threshold and the award granted when it applies.
     *
     * @param threshold the tier's threshold (a monetary amount or a quantity, zero or
     *                  positive); in progressive mode it is the floor of the tier's slice.
     * @param award     the award payload attached to this tier.
     * @param <A>       the type of the award payload.
     */
    public record Tier<A>(BigDecimal threshold, A award) {
    }

    /**
     * One slice of a progressive resolution: the portion of the base falling into a
     * tier's bracket, together with that tier's award.
     *
     * @param portion the part of the base belonging to this slice (same unit as the base).
     * @param award   the award payload of the tier owning the slice.
     * @param <A>     the type of the award payload.
     */
    public record Slice<A>(BigDecimal portion, A award) {
    }

    /**
     * The tiers, sorted by ascending threshold.
     */
    private final List<Tier<A>> tiers;

    /**
     * Builds a table from an already sorted, immutable tier list.
     *
     * @param sorted the tiers, sorted by ascending threshold.
     */
    private TierTable(List<Tier<A>> sorted) {
        this.tiers = sorted;
    }

    /**
     * Creates a tier table from an arbitrary collection of tiers.
     * <p>
     * The tiers are sorted by ascending threshold; input order is irrelevant. An empty
     * collection yields a table that never resolves anything — offer schemas requiring at
     * least one tier enforce that constraint themselves.
     *
     * @param tiers the tiers to include, possibly empty.
     * @param <A>   the type of the award payload.
     * @return the immutable tier table.
     * @throws IllegalArgumentException if the collection is null, or if any tier or
     *                                  threshold is null or negative.
     */
    public static <A> TierTable<A> of(Collection<Tier<A>> tiers) {
        if (tiers == null) {
            throw new IllegalArgumentException("A tier table requires a tier collection.");
        }
        List<Tier<A>> sorted = new ArrayList<>(tiers);
        for (Tier<A> tier : sorted) {
            if (tier == null || tier.threshold() == null || tier.threshold().signum() < 0) {
                throw new IllegalArgumentException("Tier thresholds must be zero or positive.");
            }
        }
        sorted.sort(Comparator.comparing(Tier::threshold));
        return new TierTable<>(List.copyOf(sorted));
    }

    /**
     * Returns the tiers of this table, sorted by ascending threshold.
     *
     * @return an immutable ascending view of the tiers.
     */
    public List<Tier<A>> tiers() {
        return tiers;
    }

    /**
     * Resolves the highest tier reached by the given base ("highest reached" semantics).
     * <p>
     * The result is the tier with the greatest threshold lower than or equal to the base;
     * its award applies once, on whatever assiette the caller chooses. A base below the
     * first threshold reaches no tier.
     *
     * @param base the compared value (amount or quantity).
     * @return the highest reached tier, or empty when the base is null or below every
     *         threshold.
     */
    public Optional<Tier<A>> resolveHighest(BigDecimal base) {
        if (base == null) {
            return Optional.empty();
        }
        Tier<A> best = null;
        for (Tier<A> tier : tiers) {
            if (base.compareTo(tier.threshold()) >= 0) {
                best = tier;
            } else {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Splits the base into progressive slices ("marginal brackets" semantics).
     * <p>
     * Each tier's threshold is the floor of its bracket; the bracket extends to the next
     * tier's threshold, and the last bracket is open-ended. The part of the base below the
     * first threshold belongs to no slice and earns nothing — a first tier at threshold
     * zero makes the whole base eligible.
     *
     * @param base the value to split (amount or quantity).
     * @return the non-empty slices in ascending bracket order; empty when the base is
     *         null, zero or below the first threshold.
     */
    public List<Slice<A>> slices(BigDecimal base) {
        List<Slice<A>> slices = new ArrayList<>();
        if (base == null || base.signum() <= 0) {
            return slices;
        }
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal floor = tiers.get(i).threshold();
            if (base.compareTo(floor) <= 0) {
                break;
            }
            BigDecimal ceiling = (i + 1 < tiers.size()) ? tiers.get(i + 1).threshold() : null;
            BigDecimal upper = (ceiling == null || base.compareTo(ceiling) < 0) ? base : ceiling;
            BigDecimal portion = upper.subtract(floor);
            if (portion.signum() > 0) {
                slices.add(new Slice<>(portion, tiers.get(i).award()));
            }
        }
        return slices;
    }

    /**
     * Counts how many complete steps fit in the base ("per multiple" semantics).
     *
     * @param base the compared value (amount or quantity).
     * @param step the size of one step; must be strictly positive.
     * @return the number of complete steps, zero when the base is null or not positive.
     * @throws IllegalArgumentException if the step is null, zero or negative.
     */
    public static int multiples(BigDecimal base, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            throw new IllegalArgumentException("The step of a per-multiple tier must be strictly positive.");
        }
        if (base == null || base.signum() <= 0) {
            return 0;
        }
        return base.divideToIntegralValue(step).intValue();
    }
}
