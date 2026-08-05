package com.intermarche.valuation.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates a valuation into one line per real VAT rate.
 * <p>
 * The engine already prices every item at its product's own rate, so this only groups and
 * sums: it recalculates nothing. Offers contribute through their valued items, or through
 * their own amount when they cover no product line (delivery, deposit basket), which
 * carries a single real rate anyway. Discounts are then deducted from the rate they belong
 * to.
 * <p>
 * A discount whose amount shows a blended rate — it targets an offer holding several rates —
 * cannot be charged to any single legal rate. It is spread over the rates of the offer it
 * targets, in proportion to their tax-included weight, the same rule the per-line split
 * uses.
 * <p>
 * The lines are finally reconciled with the basket total, the rounding residue landing on
 * the highest rate so the breakdown sums exactly to what the customer pays and the
 * collected tax is never understated.
 */
public final class VatBreakdown {

    /**
     * Scale used to key a rate, so 0.20 and 0.2000 land in the same bucket.
     */
    private static final int RATE_SCALE = 4;

    /**
     * Not instantiable.
     */
    private VatBreakdown() {
    }

    /**
     * Computes the per-rate breakdown of a valuation.
     *
     * @param evaluation The evaluation to summarise.
     * @return One line per rate, ordered by increasing rate; empty when nothing is priced.
     */
    public static List<BasketEvaluation.VatLine> compute(BasketEvaluation evaluation) {
        if (evaluation == null) {
            return List.of();
        }
        // Sorted by rate so the reduced rates come before the standard one.
        Map<BigDecimal, BigDecimal[]> buckets = new TreeMap<>();

        for (OfferApplication offer : evaluation.getOffers()) {
            addOffer(buckets, offer);
        }
        for (AdvantageApplication advantage : evaluation.getAdvantages()) {
            if (advantage instanceof DiscountApplication) {
                subtractDiscount(buckets, (DiscountApplication) advantage);
            }
        }
        return reconcile(buckets, evaluation.getTotalPrice());
    }

    /**
     * Adds an offer's amounts to the buckets of the rates it actually carries.
     *
     * @param buckets The buckets being filled.
     * @param offer   The offer to account for.
     */
    private static void addOffer(Map<BigDecimal, BigDecimal[]> buckets, OfferApplication offer) {
        List<BasketEvaluation.Item> valued = offer.getValuedItems();
        if (valued != null && !valued.isEmpty()) {
            for (BasketEvaluation.Item item : valued) {
                if (item.amount != null) {
                    add(buckets, item.amount.vatRate,
                            item.amount.amountExcludingTax, item.amount.amountIncludingTax);
                }
            }
            return;
        }
        // No priced product line: the offer's own amount already holds a single real rate.
        AmountEvaluation amount = offer.getAmount();
        if (amount != null) {
            add(buckets, amount.vatRate, amount.amountExcludingTax, amount.amountIncludingTax);
        }
    }

    /**
     * Deducts a discount from the rate it belongs to, spreading it when it is blended.
     *
     * @param buckets  The buckets being adjusted.
     * @param discount The discount to deduct.
     */
    private static void subtractDiscount(Map<BigDecimal, BigDecimal[]> buckets,
                                         DiscountApplication discount) {
        AmountEvaluation amount = discount.getDiscountAmount();
        if (amount == null) {
            return;
        }
        OfferApplication target = discount.getOfferApplication();
        Map<BigDecimal, BigDecimal> mix = rateMix(target);

        if (mix.size() <= 1) {
            // A single rate: either the target's only rate, or, with no target to read, the
            // rate the discount itself declares.
            BigDecimal rate = mix.isEmpty() ? amount.vatRate : mix.keySet().iterator().next();
            add(buckets, rate, amount.amountExcludingTax.negate(), amount.amountIncludingTax.negate());
            return;
        }

        // Blended: spread over the target's rates by their tax-included weight. The residue
        // goes to the highest rate, which keeps the deduction from exceeding the total there.
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (BigDecimal weight : mix.values()) {
            totalWeight = totalWeight.add(weight);
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal assignedTtc = BigDecimal.ZERO;
        BigDecimal highestRate = BigDecimal.ZERO;
        Map<BigDecimal, BigDecimal> shares = new LinkedHashMap<>();
        for (Map.Entry<BigDecimal, BigDecimal> entry : mix.entrySet()) {
            BigDecimal share = amount.amountIncludingTax.multiply(entry.getValue())
                    .divide(totalWeight, 2, RoundingMode.HALF_UP);
            shares.put(entry.getKey(), share);
            assignedTtc = assignedTtc.add(share);
            if (entry.getKey().compareTo(highestRate) > 0) {
                highestRate = entry.getKey();
            }
        }
        shares.put(highestRate, shares.get(highestRate)
                .add(amount.amountIncludingTax.subtract(assignedTtc)));

        for (Map.Entry<BigDecimal, BigDecimal> entry : shares.entrySet()) {
            BigDecimal rate = entry.getKey();
            BigDecimal ttc = entry.getValue();
            // The pre-tax figure is rebuilt at the fixed rate of the bucket it lands in.
            BigDecimal ht = ttc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            add(buckets, rate, ht.negate(), ttc.negate());
        }
    }

    /**
     * Returns the tax-included weight of each rate present in an offer.
     *
     * @param offer The offer to inspect; may be {@code null}.
     * @return Rate to tax-included weight; empty when the offer is unknown or unpriced.
     */
    private static Map<BigDecimal, BigDecimal> rateMix(OfferApplication offer) {
        Map<BigDecimal, BigDecimal> mix = new TreeMap<>();
        if (offer == null) {
            return mix;
        }
        List<BasketEvaluation.Item> valued = offer.getValuedItems();
        if (valued != null && !valued.isEmpty()) {
            for (BasketEvaluation.Item item : valued) {
                if (item.amount != null) {
                    BigDecimal key = key(item.amount.vatRate);
                    mix.merge(key, item.amount.amountIncludingTax, BigDecimal::add);
                }
            }
            return mix;
        }
        AmountEvaluation amount = offer.getAmount();
        if (amount != null) {
            mix.put(key(amount.vatRate), amount.amountIncludingTax);
        }
        return mix;
    }

    /**
     * Accumulates a pre-tax and tax-included pair into the bucket of a rate.
     *
     * @param buckets The buckets being filled.
     * @param vatRate The rate the amounts belong to.
     * @param ht      The pre-tax amount, negative to deduct.
     * @param ttc     The tax-included amount, negative to deduct.
     */
    private static void add(Map<BigDecimal, BigDecimal[]> buckets, BigDecimal vatRate,
                            BigDecimal ht, BigDecimal ttc) {
        if (ht == null || ttc == null) {
            return;
        }
        BigDecimal key = key(vatRate);
        BigDecimal[] slot = buckets.computeIfAbsent(key,
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        slot[0] = slot[0].add(ht);
        slot[1] = slot[1].add(ttc);
    }

    /**
     * Normalises a rate so equal rates written at different scales share one bucket.
     *
     * @param vatRate The rate to normalise; may be {@code null}.
     * @return The rate at a fixed scale, zero when absent.
     */
    private static BigDecimal key(BigDecimal vatRate) {
        BigDecimal rate = vatRate == null ? BigDecimal.ZERO : vatRate;
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Turns the buckets into response lines and makes them sum to the basket total.
     * <p>
     * Rounding at every step can leave the lines a cent away from the total; the difference
     * is carried by the highest rate.
     *
     * @param buckets The accumulated buckets.
     * @param total   The basket total to match; may be {@code null}.
     * @return The breakdown lines, ordered by increasing rate.
     */
    private static List<BasketEvaluation.VatLine> reconcile(Map<BigDecimal, BigDecimal[]> buckets,
                                                            AmountEvaluation total) {
        List<BasketEvaluation.VatLine> lines = new ArrayList<>();
        if (buckets.isEmpty()) {
            return lines;
        }
        BigDecimal sumHt = BigDecimal.ZERO;
        BigDecimal sumTtc = BigDecimal.ZERO;
        for (BigDecimal[] slot : buckets.values()) {
            sumHt = sumHt.add(slot[0]);
            sumTtc = sumTtc.add(slot[1]);
        }
        if (total != null && total.amountIncludingTax != null && total.amountExcludingTax != null) {
            BigDecimal highest = null;
            for (BigDecimal rate : buckets.keySet()) {
                highest = rate;
            }
            BigDecimal[] slot = buckets.get(highest);
            slot[0] = slot[0].add(total.amountExcludingTax.subtract(sumHt));
            slot[1] = slot[1].add(total.amountIncludingTax.subtract(sumTtc));
        }
        for (Map.Entry<BigDecimal, BigDecimal[]> entry : buckets.entrySet()) {
            BigDecimal ht = entry.getValue()[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal ttc = entry.getValue()[1].setScale(2, RoundingMode.HALF_UP);
            lines.add(new BasketEvaluation.VatLine(entry.getKey(), ht, ttc.subtract(ht), ttc));
        }
        return lines;
    }
}
