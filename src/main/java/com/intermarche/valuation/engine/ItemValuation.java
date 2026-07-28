package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Store;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Splits an offer's total across its items, and each item across its source lines.
 * <p>
 * The rule is option (b): the offer's total including tax is distributed over the items in
 * proportion to their catalog TTC weight; each resulting amount keeps the item's real VAT
 * rate (never a blended one) and its excluding-tax figure is rebuilt from that fixed rate.
 * The rounding residue — the cents lost when the per-item amounts are rounded — is added to
 * the item bearing the highest VAT rate, so the parts sum back to the offer total and the
 * collected tax is never understated.
 * <p>
 * Each item is then broken down by source line, so a request line that was split (a bundle's
 * paid and free portions) or spread over several prices is valued line by line.
 */
public final class ItemValuation {

    /**
     * Not instantiable.
     */
    private ItemValuation() {
    }

    /**
     * Distributes an offer total over its items and their source lines.
     *
     * @param offerTotal The offer's total amount to distribute.
     * @param items      The consumed slices making up the offer, each mono-price.
     * @param store      The store, used to resolve each item's catalog price and rate.
     * @return One valued result item per source line, summing to the offer total.
     */
    public static List<BasketEvaluation.Item> distribute(AmountEvaluation offerTotal,
                                                  Collection<Basket.Item> items, Store store) {
        List<Basket.Item> slices = new ArrayList<>(items);
        List<BasketEvaluation.Item> result = new ArrayList<>();
        if (slices.isEmpty() || offerTotal == null) {
            return result;
        }

        // 1. Catalog TTC weight of each slice, and their sum.
        BigDecimal[] weights = new BigDecimal[slices.size()];
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (int i = 0; i < slices.size(); i++) {
            AmountEvaluation catalog = AmountEvaluation.getAmount(slices.get(i), store,
                    PriceUsage.BASE_FOR_DISCOUNT);
            weights[i] = catalog.amountIncludingTax;
            totalWeight = totalWeight.add(weights[i]);
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            return result;
        }

        // 2. Pro-rata TTC share per slice, rounded; track the running sum and the slice
        //    holding the highest VAT rate to carry the residue.
        BigDecimal offerTtc = offerTotal.amountIncludingTax;
        BigDecimal[] shareTtc = new BigDecimal[slices.size()];
        BigDecimal[] rates = new BigDecimal[slices.size()];
        BigDecimal assigned = BigDecimal.ZERO;
        int highestRateIndex = 0;
        for (int i = 0; i < slices.size(); i++) {
            AmountEvaluation catalog = AmountEvaluation.getAmount(slices.get(i), store,
                    PriceUsage.BASE_FOR_DISCOUNT);
            rates[i] = catalog.vatRate;
            shareTtc[i] = offerTtc.multiply(weights[i])
                    .divide(totalWeight, 2, RoundingMode.HALF_UP);
            assigned = assigned.add(shareTtc[i]);
            if (rates[i].compareTo(rates[highestRateIndex]) > 0) {
                highestRateIndex = i;
            }
        }

        // 3. Residue to the highest-rate slice so the parts sum exactly to the offer total.
        BigDecimal residue = offerTtc.subtract(assigned);
        shareTtc[highestRateIndex] = shareTtc[highestRateIndex].add(residue);

        // 4. Per slice: rate fixed, HT rebuilt from the TTC share, then split by source line.
        for (int i = 0; i < slices.size(); i++) {
            Basket.Item slice = slices.get(i);
            BigDecimal rate = rates[i];
            BigDecimal ttc = shareTtc[i];
            BigDecimal ht = ttc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            AmountEvaluation sliceAmount = new AmountEvaluation(ht, ttc, rate);
            result.addAll(splitBySourceLine(slice, sliceAmount));
        }
        return result;
    }

    /**
     * Splits one priced slice across its source lines, proportionally to their quantities.
     * <p>
     * The residue from rounding the per-line amounts is added to the last line, so the
     * lines of a slice sum back to the slice amount.
     *
     * @param slice       The consumed slice, carrying its source-line quantities.
     * @param sliceAmount The amount attributed to the whole slice.
     * @return One valued item per source line.
     */
    private static List<BasketEvaluation.Item> splitBySourceLine(Basket.Item slice,
                                                                 AmountEvaluation sliceAmount) {
        List<BasketEvaluation.Item> lines = new ArrayList<>();
        List<Basket.Item.SourceLine> sources = slice.sourceLines;
        if (sources == null || sources.isEmpty()) {
            // No line breakdown recorded: the slice is its own line.
            Basket.Item copy = new Basket.Item();
            copy.lineId = slice.lineId;
            copy.produceEan = slice.produceEan;
            copy.quantity = slice.quantity;
            lines.add(new BasketEvaluation.Item(copy, sliceAmount));
            return lines;
        }

        double totalQty = 0.0;
        for (Basket.Item.SourceLine s : sources) {
            totalQty += s.quantity;
        }

        BigDecimal assignedHt = BigDecimal.ZERO;
        BigDecimal assignedTtc = BigDecimal.ZERO;
        for (int i = 0; i < sources.size(); i++) {
            Basket.Item.SourceLine s = sources.get(i);
            Basket.Item lineItem = new Basket.Item();
            lineItem.lineId = s.lineId;
            lineItem.produceEan = slice.produceEan;
            lineItem.quantity = s.quantity;

            AmountEvaluation lineAmount;
            if (i == sources.size() - 1) {
                // Last line takes the residue so the lines sum to the slice amount.
                lineAmount = new AmountEvaluation(
                        sliceAmount.amountExcludingTax.subtract(assignedHt),
                        sliceAmount.amountIncludingTax.subtract(assignedTtc),
                        sliceAmount.vatRate);
            } else {
                BigDecimal ratio = BigDecimal.valueOf(s.quantity / totalQty);
                BigDecimal ht = sliceAmount.amountExcludingTax.multiply(ratio)
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal ttc = sliceAmount.amountIncludingTax.multiply(ratio)
                        .setScale(2, RoundingMode.HALF_UP);
                lineAmount = new AmountEvaluation(ht, ttc, sliceAmount.vatRate);
                assignedHt = assignedHt.add(ht);
                assignedTtc = assignedTtc.add(ttc);
            }
            lines.add(new BasketEvaluation.Item(lineItem, lineAmount));
        }
        return lines;
    }
}
