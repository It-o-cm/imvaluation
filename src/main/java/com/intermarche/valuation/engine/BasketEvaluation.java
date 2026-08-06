package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;

import java.util.*;

/**
 * Represents the evaluation state of a {@link Basket}.
 * <p>
 * This class holds a reference to the original basket, a map of items
 * (keyed by EAN) that remain to be processed, a list of offers
 * successfully applied so far, and the store context.
 */
public class BasketEvaluation {

    /**
     * The original basket being evaluated.
     * <p>
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Basket basket;

    /**
     * The store context for this evaluation.
     * <p>
     * Used to resolve prices and determine applicable offer hierarchies.
     * Retrieved automatically using the {@code storeCode} from the basket.
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Store store;

    /**
     * The set of all store groups the store belongs to.
     * <p>
     * This includes direct parent groups and ancestors in the hierarchy.
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    private Set<StoreGroup> storeGroups;

    /**
     * The map of items remaining to be evaluated.
     * <p>
     * Key: The Product EAN (String).
     * Value: The {@link Basket.Item} with its remaining quantity.
     * <p>
     * This map acts as a working set. Items are copied deeply from the original basket.
     * If multiple lines in the original basket share the same EAN, their quantities are
     * aggregated here to provide a global availability view for the valuation engine.
     * <p>
     * Marked as ignored to hide it from the JSON response.
     */
    @JsonIgnore
    // Keyed by EAN, each EAN holds one entry per distinct price profile. Lines that share
    // an EAN and a price profile are aggregated; lines of the same EAN but different price
    // (manual override, or a different price date) stay separate, so valuation and the
    // per-line split downstream stay exact when a product carries two prices at once.
    private Map<String, List<Basket.Item>> toEvaluate;

    /**
     * The map of items available for upcell suggestions.
     */
    @JsonIgnore
    private Map<String, Basket.Item> availableToUpcell = new HashMap<>();;

    /**
     * The set of offer applications calculated during the evaluation.
     * <p>
     * Uses the concrete class {@link OfferApplication}
     */
    private Collection<OfferApplication> offers;

    /**
     * The set of discount applications calculated during the evaluation.
     * <p>
     * Uses the concrete class {@link AdvantageApplication}
     */
    private Collection<AdvantageApplication> advantages;

    /**
     * The total price evaluation of the basket after applying offers and discounts.
     */
    private AmountEvaluation totalPrice;

    /**
     * Constructs a new BasketEvaluation.
     * <p>
     * Initializes the evaluation with the provided basket and empty collections.
     * The store and its group hierarchy are resolved automatically using the
     * {@code storeCode} present in the basket.
     *
     * @param basket The basket to evaluate.
     */
    public BasketEvaluation(Basket basket) {
        this.basket = basket;
        this.toEvaluate = new HashMap<>();
        this.offers = new HashSet<>();
        this.advantages = new HashSet<>();
        // Resolve Store from Basket storeCode
        if (this.basket != null && this.basket.storeCode != null) {
            this.store = Store.findByCode(this.basket.storeCode);
            // Align with the unknown-product contract: a store code that resolves to
            // nothing is a configuration error, not a NullPointerException raised later
            // when the (null) store is dereferenced for pricing.
            if (this.store == null) {
                throw new IllegalStateException(
                        "Configuration Error: Store not found for code '" + this.basket.storeCode + "'");
            }
        } else {
            this.store = null;
        }
        // Calculate store groups hierarchy
        if (this.store != null) {
            this.storeGroups = StoreGroup.findAllStoreGroups(this.store);
        } else {
            this.storeGroups = new HashSet<>();
        }
    }

    /**
     * Populates the {@code toEvaluate} map by performing a deep copy of items from the basket.
     * <p>
     * This method ensures that the evaluation engine works on independent copies of items.
     * If the original basket contains multiple lines with the same EAN, their quantities
     * are aggregated in the map to represent the total available stock for that product.
     *
     * @param basket The basket source.
     */
    public void feedFrom(Basket basket) {
        this.basket = basket;
        this.toEvaluate.clear();
        if (basket.items != null) {
            for (Basket.Item originalItem : basket.items) {
                List<Basket.Item> bucket =
                        toEvaluate.computeIfAbsent(originalItem.produceEan, k -> new ArrayList<>());
                // Aggregate only with a line sharing the same price profile; a different
                // manual price or price date keeps the line on its own entry.
                Basket.Item existingItem = null;
                for (Basket.Item candidate : bucket) {
                    if (samePriceProfile(candidate, originalItem)) {
                        existingItem = candidate;
                        break;
                    }
                }
                if (existingItem == null) {
                    // First occurrence of this (EAN, price): deep copy into the bucket.
                    Basket.Item copy = new Basket.Item();
                    copy.lineId = originalItem.lineId;
                    copy.produceEan = originalItem.produceEan;
                    copy.quantity = originalItem.quantity;
                    copy.pricePerUnitExclTax = originalItem.pricePerUnitExclTax;
                    copy.pricePerUnitInclTax = originalItem.pricePerUnitInclTax;
                    copy.vatRate = originalItem.vatRate;
                    copy.priceDate = originalItem.priceDate;
                    // The manual gesture belongs to the line and takes part in the price
                    // profile: without it here, a line bearing a gesture would look identical
                    // to a plain line of the same product and the two would be merged.
                    copy.manualDiscountAmount = originalItem.manualDiscountAmount;
                    copy.manualDiscountPercent = originalItem.manualDiscountPercent;
                    copy.manualForcedPrice = originalItem.manualForcedPrice;
                    // Record this line's contribution so a later consumption can be split
                    // back across the exact source lines, in order. A line without a
                    // quantity contributes nothing and is recorded as zero rather than
                    // unboxed, which would fail on a case the engine otherwise tolerates.
                    copy.sourceLines.add(new Basket.Item.SourceLine(
                            originalItem.lineId, contributionOf(originalItem)));
                    bucket.add(copy);
                } else {
                    // Same EAN and same price: aggregate quantity, keep each contributing
                    // line on record for an exact per-line split downstream.
                    if (originalItem.quantity != null) {
                        existingItem.quantity = (existingItem.quantity == null ? 0.0 : existingItem.quantity)
                                + originalItem.quantity;
                    }
                    existingItem.sourceLines.add(new Basket.Item.SourceLine(
                            originalItem.lineId, contributionOf(originalItem)));
                }
            }
        }
    }

    // --------------------------------------------------
    // Logic Methods
    // --------------------------------------------------

    /**
     * Picks a specified quantity of a product identified by its EAN from the items to evaluate.
     * <p>
     * Uses the Map structure for O(1) access time.
     * This method searches the {@code toEvaluate} map for an item matching the provided EAN.
     * It reduces the quantity of the found item by the requested amount.
     * If the resulting quantity reaches zero, the item is removed from the map.
     * <p>
     * If the requested quantity exceeds the available quantity, the available quantity
     * is taken entirely (partial consumption).
     *
     * @param quantityToPick The quantity to remove (consume).
     * @param ean            The EAN code of the product to pick.
     * @return A new {@link Basket.Item} describing what was actually taken (with the taken quantity),
     *         or {@code null} if no matching item was found.
     */
    public List<Basket.Item> pick(Double quantityToPick, String ean) {
        List<Basket.Item> picked = new ArrayList<>();
        if (quantityToPick == null || ean == null) {
            return picked;
        }
        List<Basket.Item> bucket = toEvaluate.get(ean);
        if (bucket == null || bucket.isEmpty()) {
            return picked;
        }
        double remaining = quantityToPick;
        // Consume price entries in order until the quantity is satisfied or the EAN runs
        // out. Each entry yields its own picked item, mono-price, so a downstream split
        // sees the exact price of every consumed slice — the whole point of separating a
        // product's distinct prices.
        while (remaining > 1e-9 && !bucket.isEmpty()) {
            Basket.Item item = bucket.get(0);
            double available = contributionOf(item);
            double take = Math.min(remaining, available);

            Basket.Item slice = new Basket.Item();
            slice.lineId = item.lineId;
            slice.produceEan = ean;
            slice.quantity = take;
            slice.pricePerUnitExclTax = item.pricePerUnitExclTax;
            slice.pricePerUnitInclTax = item.pricePerUnitInclTax;
            slice.vatRate = item.vatRate;
            slice.priceDate = item.priceDate;
            slice.manualDiscountAmount = item.manualDiscountAmount;
            slice.manualDiscountPercent = item.manualDiscountPercent;
            slice.manualForcedPrice = item.manualForcedPrice;
            slice.sourceLines = consumeSourceLines(item, take);
            picked.add(slice);

            remaining -= take;
            if (take < available) {
                item.quantity = roundQuantity(available - take);
            } else {
                bucket.remove(0);
            }
        }
        if (bucket.isEmpty()) {
            toEvaluate.remove(ean);
        }
        return picked;
    }

    /**
     * Consumes a quantity from the entry matching a given line's price profile.
     * <p>
     * {@link #pick(Double, String)} draws on the first entry of an EAN, which is wrong for a
     * caller that owns one particular line: a manual gesture belongs to the line that
     * carries it, and consuming a neighbouring line of the same product would apply the
     * gesture to the wrong quantity and report the wrong line identifier.
     *
     * @param quantityToPick The quantity to consume.
     * @param source         The line whose price profile identifies the entry to draw on.
     * @return The consumed slices, empty when no matching entry remains.
     */
    public List<Basket.Item> pickMatching(Double quantityToPick, Basket.Item source) {
        List<Basket.Item> picked = new ArrayList<>();
        if (quantityToPick == null || source == null || source.produceEan == null) {
            return picked;
        }
        List<Basket.Item> bucket = toEvaluate.get(source.produceEan);
        if (bucket == null) {
            return picked;
        }
        int index = -1;
        for (int i = 0; i < bucket.size(); i++) {
            if (samePriceProfile(bucket.get(i), source)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return picked;
        }
        Basket.Item item = bucket.get(index);
        double available = contributionOf(item);
        double take = Math.min(quantityToPick, available);

        Basket.Item slice = new Basket.Item();
        slice.lineId = item.lineId;
        slice.produceEan = item.produceEan;
        slice.quantity = take;
        slice.pricePerUnitExclTax = item.pricePerUnitExclTax;
        slice.pricePerUnitInclTax = item.pricePerUnitInclTax;
        slice.vatRate = item.vatRate;
        slice.priceDate = item.priceDate;
        slice.manualDiscountAmount = item.manualDiscountAmount;
        slice.manualDiscountPercent = item.manualDiscountPercent;
        slice.manualForcedPrice = item.manualForcedPrice;
        slice.sourceLines = consumeSourceLines(item, take);
        picked.add(slice);

        if (take < available) {
            item.quantity = roundQuantity(available - take);
        } else {
            bucket.remove(index);
            if (bucket.isEmpty()) {
                toEvaluate.remove(source.produceEan);
            }
        }
        return picked;
    }

    /**
     * Consumes a quantity of an EAN and returns the total taken, ignoring the per-price
     * breakdown.
     * <p>
     * A convenience for callers that only need the aggregate consumed quantity and the
     * combined source lines, not one item per price. It draws on {@link #pick(Double,
     * String)} and merges the slices into a single item carrying the price profile of the
     * first slice; use {@link #pick(Double, String)} directly when the per-price split
     * must be preserved.
     *
     * @param quantityToPick The quantity to consume.
     * @param ean            The product EAN.
     * @return A single merged item, or {@code null} when nothing was taken.
     */
    public Basket.Item pickMerged(Double quantityToPick, String ean) {
        List<Basket.Item> slices = pick(quantityToPick, ean);
        if (slices.isEmpty()) {
            return null;
        }
        Basket.Item first = slices.get(0);
        if (slices.size() == 1) {
            return first;
        }
        Basket.Item merged = new Basket.Item();
        merged.lineId = first.lineId;
        merged.produceEan = ean;
        merged.pricePerUnitExclTax = first.pricePerUnitExclTax;
        merged.pricePerUnitInclTax = first.pricePerUnitInclTax;
        merged.vatRate = first.vatRate;
        merged.priceDate = first.priceDate;
        double total = 0.0;
        for (Basket.Item slice : slices) {
            total += contributionOf(slice);
            merged.sourceLines.addAll(slice.sourceLines);
        }
        merged.quantity = total;
        return merged;
    }

    /**
     * Consumes a quantity from an item's source lines, in order, returning the slices taken.
     * <p>
     * Source lines record how each original basket line contributed to an aggregated item.
     * Consuming FIFO, a pick of 3 against lines [line1:2, line5:3] yields [line1:2, line5:1]
     * and leaves [line5:2] on the working item. This is what lets the response carry an
     * exact amount per original line rather than one merged figure.
     *
     * @param item     The working item whose source lines are drawn down (mutated).
     * @param quantity The quantity being consumed.
     * @return The source-line slices making up the consumed quantity.
     */
    private List<Basket.Item.SourceLine> consumeSourceLines(Basket.Item item, double quantity) {
        List<Basket.Item.SourceLine> taken = new ArrayList<>();
        double remaining = quantity;
        java.util.Iterator<Basket.Item.SourceLine> it = item.sourceLines.iterator();
        while (it.hasNext() && remaining > 1e-9) {
            Basket.Item.SourceLine line = it.next();
            double slice = Math.min(line.quantity, remaining);
            taken.add(new Basket.Item.SourceLine(line.lineId, slice));
            remaining -= slice;
            if (slice >= line.quantity - 1e-9) {
                it.remove();
            } else {
                line.quantity = roundQuantity(line.quantity - slice);
            }
        }
        return taken;
    }

    /**
     * Rounds a quantity to a sane precision.
     * <p>
     * Quantities are doubles, so splitting one (3.639 minus 3.0) leaves artefacts like
     * 0.6389999999999998 that then surface in offer labels and in the response. Rounding
     * the remainder keeps the value the caller would expect without changing the total
     * consumed.
     *
     * @param quantity The quantity to round.
     * @return The quantity rounded to six decimals.
     */
    private static double roundQuantity(double quantity) {
        return java.math.BigDecimal.valueOf(quantity)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Returns the quantity a line contributes, treating an absent quantity as none.
     *
     * @param item The original basket line.
     * @return The quantity, or zero when the line carries none.
     */
    private static double contributionOf(Basket.Item item) {
        return item.quantity == null ? 0.0 : item.quantity;
    }

    /**
     * Indicates whether two lines of the same EAN carry the same price profile.
     * <p>
     * Lines aggregate only when they would resolve to the same price: identical manual
     * price fields (or both absent) and the same price date. Comparing the inputs rather
     * than the resolved price avoids resolving a price early, while still keeping genuinely
     * different prices apart.
     *
     * @param a One line.
     * @param b The other line.
     * @return {@code true} when the two may be aggregated.
     */
    private boolean samePriceProfile(Basket.Item a, Basket.Item b) {
        return java.util.Objects.equals(a.pricePerUnitExclTax, b.pricePerUnitExclTax)
                && java.util.Objects.equals(a.pricePerUnitInclTax, b.pricePerUnitInclTax)
                && java.util.Objects.equals(a.vatRate, b.vatRate)
                && java.util.Objects.equals(a.priceDate, b.priceDate)
                // Manual gestures are per line: two lines of the same EAN must not merge
                // when they carry different gestures, or a gesture would spread to the wrong
                // quantity. Any difference here keeps them on separate entries.
                && java.util.Objects.equals(a.manualDiscountAmount, b.manualDiscountAmount)
                && java.util.Objects.equals(a.manualDiscountPercent, b.manualDiscountPercent)
                && java.util.Objects.equals(a.manualForcedPrice, b.manualForcedPrice);
    }

    /**
     * Gets the original basket.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     *
     * @return The basket.
     */
    @JsonIgnore
    public Basket getBasket() {
        return basket;
    }

    /**
     * Gets the store context.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     *
     * @return The store entity.
     */
    @JsonIgnore
    public Store getStore() {
        return store;
    }

    /**
     * Gets the set of store groups the store belongs to.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     *
     * @return The set of store groups.
     */
    @JsonIgnore
    public Set<StoreGroup> getStoreGroups() {
        return storeGroups;
    }

    /**
     * Gets the map of items remaining to be evaluated.
     * <p>
     * Marked as ignored to prevent it from appearing in the JSON response.
     * Note: The resource checks this map to signal an error if it is not empty.
     *
     * @return The map of items (EAN -> Item).
     */
    @JsonIgnore
    public Map<String, List<Basket.Item>> getToEvaluate() {
        return toEvaluate;
    }

    /**
     * Returns the total remaining quantity of an EAN across all its price entries.
     * <p>
     * Callers that only need to know how much of a product is left, regardless of price,
     * use this instead of reaching into the per-price entries.
     *
     * @param ean The product EAN.
     * @return The summed remaining quantity; zero when the EAN is absent.
     */
    public double remainingQuantity(String ean) {
        List<Basket.Item> bucket = toEvaluate.get(ean);
        if (bucket == null) {
            return 0.0;
        }
        double total = 0.0;
        for (Basket.Item item : bucket) {
            total += contributionOf(item);
        }
        return total;
    }

    /**
     * Gets the set of applied offers.
     * <p>
     * This is visible in the JSON response.
     *
     * @return The set of offer applications.
     */
    public Collection<OfferApplication> getOffers() {
        return offers;
    }

    /**
     * Gets the set of applied discounts.
     * <p>
     * This is visible in the JSON response.
     *
     * @return The set of discount applications.
     */
    public Collection<AdvantageApplication> getAdvantages() {
        return advantages;
    }

    /**
     * Sets the total price evaluation of the basket after applying offers and discounts.
     *
     * @param totalPrice The total price evaluation to set.
     */
    public void setTotalPrice(AmountEvaluation totalPrice) {
        this.totalPrice = totalPrice;
    }

    /**
     * Gets the total price evaluation of the basket after applying offers and discounts.
     *
     * @return The total price evaluation.
     */
    public AmountEvaluation getTotalPrice() {
        return totalPrice;
    }

    /**
     * Returns the amounts due per real VAT rate.
     * <p>
     * Derived from the applied offers and discounts on every call rather than stored, so it
     * cannot drift from them. Where {@link #getTotalPrice()} shows a rate of zero because
     * several rates are mixed, this gives the figures a tax return needs: one taxable base
     * and one tax amount per legal rate, summing to the total.
     *
     * @return The breakdown lines, ordered by increasing rate.
     */
    public java.util.List<VatLine> getVatBreakdown() {
        return VatBreakdown.compute(this);
    }

    /**
     * Gets the map of items available for upcell suggestions.
     *
     * @return The map of items (EAN -> Item).
     */
    public Map<String, Basket.Item> getAvailableToUpcell() {
        return availableToUpcell;
    }

    /**
     * Adds a picked item to the availableToUpcell map.
     * <p>
     * If an item with the same EAN already exists in the map,
     * its quantity is aggregated with the new item's quantity.
     *
     * @param pickedItem The picked item to add.
     */
    public void addAvailableToUpcell(Basket.Item pickedItem) {
        if (pickedItem != null) {
            Basket.Item existingItem = availableToUpcell.get(pickedItem.produceEan);
            if (existingItem == null) {
                // First occurrence: Add to map
                Basket.Item copy = new Basket.Item();
                copy.lineId = pickedItem.lineId;
                copy.produceEan = pickedItem.produceEan;
                copy.quantity = pickedItem.quantity;
                availableToUpcell.put(pickedItem.produceEan, copy);
            } else {
                // Duplicate EAN: Aggregate quantity to the existing item
                existingItem.quantity = contributionOf(existingItem) + contributionOf(pickedItem);
            }
        }
    }

    /**
     * One line of the VAT breakdown: what is due at a single legal rate.
     * <p>
     * The engine prices each item at its product's own rate, so these lines carry real
     * rates only — never the blended figure an offer or the total may show. Their amounts
     * sum to the basket total.
     * <p>
     * Fields are public for JSON serialization.
     */
    public static class VatLine {

        /**
         * The VAT rate this line accounts for.
         */
        public java.math.BigDecimal vatRate;

        /**
         * Taxable base: the amount excluding tax at this rate.
         */
        public java.math.BigDecimal amountExcludingTax;

        /**
         * The tax due at this rate.
         */
        public java.math.BigDecimal vatAmount;

        /**
         * The amount including tax at this rate.
         */
        public java.math.BigDecimal amountIncludingTax;

        /**
         * Default constructor for JSON serialization.
         */
        public VatLine() {
        }

        /**
         * Builds a breakdown line.
         *
         * @param vatRate            The rate.
         * @param amountExcludingTax The taxable base.
         * @param vatAmount          The tax due.
         * @param amountIncludingTax The amount including tax.
         */
        public VatLine(java.math.BigDecimal vatRate, java.math.BigDecimal amountExcludingTax,
                       java.math.BigDecimal vatAmount, java.math.BigDecimal amountIncludingTax) {
            this.vatRate = vatRate;
            this.amountExcludingTax = amountExcludingTax;
            this.vatAmount = vatAmount;
            this.amountIncludingTax = amountIncludingTax;
        }
    }

    /**
     * An item as it appears in a valuation result — a line's contribution to one offer,
     * already priced.
     * <p>
     * This is deliberately a distinct type from {@link Basket.Item}, the line submitted in
     * the request. A request line may give rise to several result items: split across the
     * paid and free parts of a bundle, spread over more than one offer, or separated by
     * price when a product carries two. Each result item is mono-price and traces back to
     * exactly one source line through {@link #lineId}, so a caller rebuilds a line-by-line
     * valuation by grouping result items on that identifier.
     * <p>
     * The {@link #amount} is the offer's own attribution to this item — the figure the
     * offer computed for it, not a re-derivation — so summing the items of an offer returns
     * that offer's total.
     * <p>
     * Fields are public for JSON serialization.
     */
    public static class Item {

        /**
         * Identifier of the request line this result item came from.
         */
        public String lineId;

        /**
         * EAN of the product.
         */
        public String produceEan;

        /**
         * Quantity of this result item.
         */
        public double quantity;

        /**
         * The offer's attributed amount for this item: excl. tax, incl. tax, and the real
         * VAT rate of the product — never a blended rate.
         */
        public AmountEvaluation amount;

        /**
         * Default constructor for JSON serialization.
         */
        public Item() {
        }

        /**
         * Builds a result item from a consumed slice and its attributed amount.
         *
         * @param source The consumed slice, carrying its line id, EAN and quantity.
         * @param amount The amount the offer attributes to this item.
         */
        public Item(Basket.Item source, AmountEvaluation amount) {
            this.lineId = source.lineId;
            this.produceEan = source.produceEan;
            this.quantity = source.quantity == null ? 0.0 : source.quantity;
            this.amount = amount;
        }
    }

}