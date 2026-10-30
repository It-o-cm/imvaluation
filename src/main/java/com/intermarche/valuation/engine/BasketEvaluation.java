package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.engine.offers.InstrumentGrantFactory;

import java.math.BigDecimal;
import java.util.*;

/**
 * Represents the evaluation state of a {@link Basket}.
 * <p>
 * This class holds a reference to the original basket, a map of items
 * (keyed by EAN) that remain to be processed, a list of offers
 * successfully applied so far, and the store context.
 */
@JsonPropertyOrder({"offers", "advantages", "messages", "grantTotals", "totalPrice", "egalim", "srp",
        "vatBreakdown", "availableToUpcell"})
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
     * The EGAlim conformity record, one entry per initial basket line (EGALIM_GUARD spec §6).
     * <p>
     * Populated by the EGAlim guard advantage when an {@code EGALIM_GUARD} configuration is in
     * force and the guard actually runs (a closed basket). Left {@code null} — and omitted from
     * the JSON response, see {@link #getEgalim()} — otherwise, so a basket valued without a
     * guard configuration serialises exactly as before (regression-free).
     */
    private List<EgalimLine> egalim;

    /**
     * The SRP (resale-below-cost) floor record, one entry per controlled initial basket line
     * (spec-srp-guard §3).
     * <p>
     * Populated by the SRP guard advantage when an {@code SRP_GUARD} configuration is in force and
     * the guard actually runs (a closed basket). Left {@code null} — and omitted from the JSON
     * response, see {@link #getSrp()} — otherwise, so a basket valued without a guard configuration
     * serialises exactly as before (regression-free).
     */
    private List<SrpLine> srp;

    /**
     * The configurations skipped during this evaluation, fail-closed (A2, report H1).
     * <p>
     * A configuration whose specification, trigger or arbitration block cannot be honoured is
     * skipped rather than failing the whole valuation: each such skip is logged as an error and
     * appended here so the resource can record it in the {@link com.intermarche.valuation.domain.ValuationTrace}.
     * Ignored in the JSON response: it is audit context, not part of the till contract.
     */
    @JsonIgnore
    private final List<String> skippedConfigurations = new ArrayList<>();

    /**
     * Memoized trigger evaluation, one entry per configuration (spec §3.3).
     * <p>
     * The trigger of a configuration is evaluated once per arbitration, against the current
     * amounts, and reused until the cache is invalidated (spec §4.3). Keyed by the
     * {@link Offer} row. Ignored in the JSON response: internal arbitration state.
     */
    @JsonIgnore
    private final Map<Offer, TriggerResult> triggerCache = new HashMap<>();

    /**
     * The offer applications consumed as carriers (spec §4.4).
     * <p>
     * When an applied advantage carries {@code consumesContributors}, the contributors of its
     * trigger become consumed carriers: they no longer count toward the measures of the
     * triggers evaluated afterwards. Identity-based so two distinct applications of the same
     * amount stay distinct. Ignored in the JSON response.
     */
    @JsonIgnore
    private final Set<OfferApplication> consumedContributors =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The shared display-valuation context, set only for a {@code POST /display-valuation}
     * mini-basket (spec-valorisation-affichage-ecommerce §2bis); {@code null} for a normal
     * {@code /valuation}, which therefore stays byte-for-byte unchanged.
     * <p>
     * When present, {@link EngineTrait#getOffers} serves the offers the context resolved ONCE for
     * the whole call instead of re-querying the database per product, and it filters out the
     * basket-dependent and ticket-level offers that must not shape a one-unit displayed price.
     * Ignored in the JSON response: it is engine wiring, not part of the till contract.
     */
    @JsonIgnore
    private transient DisplayContext displayContext;

    /**
     * Constructs a new BasketEvaluation.
     * <p>
     * Initializes the evaluation with the provided basket and empty collections.
     * The store and its group hierarchy are resolved automatically using the
     * {@code storeCode} present in the basket.
     *
     * @param basket The basket to evaluate.
     * @throws IllegalStateException if the basket carries a store code that resolves to no
     *                               store — a configuration error, mirroring the
     *                               unknown-product contract.
     */
    public BasketEvaluation(Basket basket) {
        this.basket = basket;
        this.toEvaluate = new HashMap<>();
        // Insertion-ordered so the evaluation is deterministic (report C2): every downstream
        // consumer that iterates the applied offers/advantages (VAT breakdown, residue-on-last
        // distributions, the available-offers view feeding bases and triggers) sees them in
        // the stable order in which the engine applied them, never in identity-hash order.
        this.offers = new LinkedHashSet<>();
        this.advantages = new LinkedHashSet<>();
        // Resolve Store from Basket storeCode
        if (this.basket != null && this.basket.storeCode != null) {
            this.store = Store.findByCode(this.basket.storeCode);
            // Align with the unknown-product contract: a store code that resolves to
            // nothing is a configuration error, not a NullPointerException raised later
            // when the (null) store is dereferenced for pricing.
            if (this.store == null) {
                throw new ReferentialException(
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
                        existingItem.quantity = (existingItem.quantity == null ? BigDecimal.ZERO : existingItem.quantity)
                                .add(originalItem.quantity);
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
    public List<Basket.Item> pick(BigDecimal quantityToPick, String ean) {
        List<Basket.Item> picked = new ArrayList<>();
        if (quantityToPick == null || ean == null) {
            return picked;
        }
        List<Basket.Item> bucket = toEvaluate.get(ean);
        if (bucket == null || bucket.isEmpty()) {
            return picked;
        }
        BigDecimal remaining = quantityToPick;
        // Consume price entries in order until the quantity is satisfied or the EAN runs
        // out. Each entry yields its own picked item, mono-price, so a downstream split
        // sees the exact price of every consumed slice — the whole point of separating a
        // product's distinct prices. Subtraction is exact in BigDecimal, so the taken
        // slices sum to the demanded quantity to the last unit — no epsilon, no residue.
        while (remaining.signum() > 0 && !bucket.isEmpty()) {
            Basket.Item item = bucket.get(0);
            BigDecimal available = contributionOf(item);
            BigDecimal take = remaining.min(available);

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

            remaining = remaining.subtract(take);
            if (take.compareTo(available) < 0) {
                item.quantity = available.subtract(take);
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
     * <p>
     * A {@code null} EAN is a valid key here: generic lines (no EAN, price carried by the
     * line) are pooled under the {@code null} bucket by {@link #feedFrom(Basket)} and are
     * consumed through this method by their price profile.
     *
     * @param quantityToPick The quantity to consume.
     * @param source         The line whose price profile identifies the entry to draw on.
     * @return The consumed slices, empty when no matching entry remains.
     */
    public List<Basket.Item> pickMatching(BigDecimal quantityToPick, Basket.Item source) {
        List<Basket.Item> picked = new ArrayList<>();
        if (quantityToPick == null || source == null) {
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
        BigDecimal available = contributionOf(item);
        BigDecimal take = quantityToPick.min(available);

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

        if (take.compareTo(available) < 0) {
            item.quantity = available.subtract(take);
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
    public Basket.Item pickMerged(BigDecimal quantityToPick, String ean) {
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
        BigDecimal total = BigDecimal.ZERO;
        for (Basket.Item slice : slices) {
            total = total.add(contributionOf(slice));
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
    private List<Basket.Item.SourceLine> consumeSourceLines(Basket.Item item, BigDecimal quantity) {
        List<Basket.Item.SourceLine> taken = new ArrayList<>();
        BigDecimal remaining = quantity;
        java.util.Iterator<Basket.Item.SourceLine> it = item.sourceLines.iterator();
        while (it.hasNext() && remaining.signum() > 0) {
            Basket.Item.SourceLine line = it.next();
            BigDecimal slice = line.quantity.min(remaining);
            taken.add(new Basket.Item.SourceLine(line.lineId, slice));
            remaining = remaining.subtract(slice);
            if (slice.compareTo(line.quantity) >= 0) {
                it.remove();
            } else {
                line.quantity = line.quantity.subtract(slice);
            }
        }
        return taken;
    }

    /**
     * Returns the quantity a line contributes, treating an absent quantity as none.
     *
     * @param item The original basket line.
     * @return The quantity, or zero when the line carries none.
     */
    private static BigDecimal contributionOf(Basket.Item item) {
        return item.quantity == null ? BigDecimal.ZERO : item.quantity;
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
     * Returns the shared display-valuation context, or {@code null} for a normal {@code /valuation}
     * (spec-valorisation-affichage-ecommerce §2bis).
     *
     * @return the display context, or {@code null}.
     */
    @JsonIgnore
    public DisplayContext getDisplayContext() {
        return displayContext;
    }

    /**
     * Attaches the shared display-valuation context to this mini-basket evaluation. Called by the
     * engine only on the display path; left {@code null} for a normal basket.
     *
     * @param displayContext the shared context resolved once for the whole call.
     */
    public void setDisplayContext(DisplayContext displayContext) {
        this.displayContext = displayContext;
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
    public BigDecimal remainingQuantity(String ean) {
        List<Basket.Item> bucket = toEvaluate.get(ean);
        if (bucket == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Basket.Item item : bucket) {
            total = total.add(contributionOf(item));
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
     * Gets the full set of applied advantages, message advantages included.
     * <p>
     * Internal accessor used across the engine; NOT the serialized view — the JSON {@code advantages}
     * block is produced by {@link #getVisibleAdvantages()}, which omits the message advantages so the
     * monetary block stays clean (they surface in {@code messages[]} instead).
     *
     * @return The set of advantage applications.
     */
    @JsonIgnore
    public Collection<AdvantageApplication> getAdvantages() {
        return advantages;
    }

    /**
     * The serialized {@code advantages} block: every applied advantage except the message advantages
     * (spec-messages-editique §1). With no message applied this is exactly the full set, so the
     * response stays byte-for-byte identical to before.
     *
     * @return the monetary advantages, in application order.
     */
    @JsonProperty("advantages")
    public List<AdvantageApplication> getVisibleAdvantages() {
        List<AdvantageApplication> visible = new ArrayList<>();
        for (AdvantageApplication advantage : advantages) {
            if (!(advantage instanceof MessageApplication)) {
                visible.add(advantage);
            }
        }
        return visible;
    }

    /**
     * The additive {@code messages} response block (spec-messages-editique §1): one entry per applied
     * message advantage. {@code null} — and thus omitted — when no message applies, so a valuation
     * without messages serialises exactly as before.
     *
     * @return the messages, or {@code null} when none applied.
     */
    @JsonProperty("messages")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<MessageLine> getMessages() {
        List<MessageLine> messages = new ArrayList<>();
        for (AdvantageApplication advantage : advantages) {
            if (advantage instanceof MessageApplication message) {
                messages.add(new MessageLine(message.getCode(), message.getText(), message.getPrintMode()));
            }
        }
        return messages.isEmpty() ? null : messages;
    }

    /**
     * The additive {@code grantTotals} response block (spec-plafonds-attribution §3): the total
     * number of points and vignettes attributed across every attribution mechanic of the ticket
     * (GM-06-04-12 / GM-09-05-12 — "return the total number to give when several mechanics apply").
     * <p>
     * Summed from the {@link InstrumentGrantFactory.InstrumentGrantApplication} grants whose unit is
     * {@code POINTS} or {@code VIGNETTES}; monetary ({@code EUR}) vouchers and coupons keep their own
     * line in {@code advantages[]} but are not points or vignettes, so they do not feed this block.
     * The attributed vignettes counted here are the ones the ticket EARNS — never the vignettes the
     * basket brought in for a {@code VIGNETTE_DISCOUNT} (spec §5): the two never mix, they live in
     * unrelated structures.
     * <p>
     * Returned {@code null} — and thus omitted — when no points or vignettes were attributed, so a
     * valuation without an attribution serialises exactly as before (bit-for-bit regression).
     *
     * @return the summed points and vignettes, or {@code null} when none was attributed.
     */
    @JsonProperty("grantTotals")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public GrantTotals getGrantTotals() {
        BigDecimal points = BigDecimal.ZERO;
        BigDecimal vignettes = BigDecimal.ZERO;
        boolean any = false;
        for (AdvantageApplication advantage : advantages) {
            if (!(advantage instanceof InstrumentGrantFactory.InstrumentGrantApplication grant)) {
                continue;
            }
            InstrumentGrantFactory.Grant instrument = grant.getGrant();
            if (instrument == null || instrument.amount() == null) {
                continue;
            }
            if ("POINTS".equals(instrument.unit())) {
                points = points.add(instrument.amount());
                any = true;
            } else if ("VIGNETTES".equals(instrument.unit())) {
                vignettes = vignettes.add(instrument.amount());
                any = true;
            }
        }
        return any ? new GrantTotals(points, vignettes) : null;
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
     * Returns the EGAlim conformity record, one line per initial basket line (spec §6).
     * <p>
     * Omitted from the JSON response when {@code null} (no in-force guard, or an open basket),
     * so a valuation without an EGAlim guard is byte-for-byte identical to before.
     *
     * @return the record lines, or {@code null} when no guard produced a record.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<EgalimLine> getEgalim() {
        return egalim;
    }

    /**
     * Records the EGAlim conformity record produced by the guard (spec §6).
     *
     * @param egalim the record lines, one per initial basket line.
     */
    public void setEgalimRecord(List<EgalimLine> egalim) {
        this.egalim = egalim;
    }

    /**
     * Returns the SRP floor record, one entry per controlled initial basket line (spec-srp-guard §3).
     * <p>
     * Omitted from the JSON response when {@code null} (no in-force guard, or an open basket), so a
     * valuation without an SRP guard is byte-for-byte identical to before.
     *
     * @return the record lines, or {@code null} when no guard produced a record.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<SrpLine> getSrp() {
        return srp;
    }

    /**
     * Records the SRP floor record produced by the guard (spec-srp-guard §3).
     *
     * @param srp the record lines, one per controlled initial basket line.
     */
    public void setSrpRecord(List<SrpLine> srp) {
        this.srp = srp;
    }

    /**
     * Records a configuration skipped fail-closed during this evaluation (A2, report H1).
     *
     * @param message the human-readable reason the configuration was skipped; ignored when null.
     */
    public void recordSkippedConfiguration(String message) {
        if (message != null) {
            skippedConfigurations.add(message);
        }
    }

    /**
     * Returns the configurations skipped fail-closed during this evaluation (A2, report H1).
     * <p>
     * Empty when every configuration was honoured. The resource records these in the valuation
     * trace so a skipped offer is auditable even though the evaluation itself succeeded.
     *
     * @return the skip messages, in the order they occurred; never null.
     */
    @JsonIgnore
    public List<String> getSkippedConfigurations() {
        return skippedConfigurations;
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
     * Evaluates a configuration's trigger once, memoizing the result (spec §3.3).
     * <p>
     * The result is cached per {@link Offer} and reused until {@link #invalidateTriggerCache()}
     * is called (spec §4.3). A configuration without a row (a {@code null} offer, for an
     * applier not born from a configuration) is never cached and always evaluates its
     * trigger, which for such appliers is {@link Trigger#ALWAYS}.
     *
     * @param configuration the configuration row, or {@code null}.
     * @param trigger       the configuration's trigger.
     * @return the memoized outcome.
     */
    public TriggerResult triggerResult(Offer configuration, Trigger trigger) {
        if (configuration == null) {
            return trigger.evaluate(this);
        }
        TriggerResult cached = triggerCache.get(configuration);
        if (cached != null) {
            return cached;
        }
        TriggerResult result = trigger.evaluate(this);
        triggerCache.put(configuration, result);
        return result;
    }

    /**
     * Invalidates the trigger cache, so the next evaluation reflects the current amounts
     * (spec §4.3): called when a discount really applies or when carriers are consumed.
     */
    public void invalidateTriggerCache() {
        triggerCache.clear();
    }

    /**
     * Marks offer applications as consumed carriers (spec §4.4).
     * <p>
     * Consumed carriers leave the measures of the triggers evaluated afterwards: a consumed
     * amount no longer counts toward anyone's threshold.
     *
     * @param contributors the contributors to consume.
     */
    public void markConsumed(Collection<OfferApplication> contributors) {
        if (contributors != null) {
            consumedContributors.addAll(contributors);
        }
    }

    /**
     * Tells whether an offer application has been consumed as a carrier (spec §4.4).
     *
     * @param application the offer application to test.
     * @return {@code true} when the application is a consumed carrier.
     */
    public boolean isConsumed(OfferApplication application) {
        return consumedContributors.contains(application);
    }

    /**
     * Returns the offer applications available to the arbitration: every applied offer minus
     * the carriers consumed so far (spec §4.4).
     * <p>
     * This is the view an advantage builds its base from and a trigger takes its measures
     * on, so a consumed carrier leaves both. It is deliberately distinct from
     * {@link #getOffers()}, which is the response payload and must keep showing the consumed
     * lines — the customer still bought them; they are only withdrawn from the calculations of
     * the configurations arbitrated afterwards.
     *
     * @return the applied offers that are not consumed carriers; a live filtered view, never
     *         null.
     */
    @JsonIgnore
    public Collection<OfferApplication> getAvailableOffers() {
        if (consumedContributors.isEmpty()) {
            return offers;
        }
        List<OfferApplication> available = new ArrayList<>();
        for (OfferApplication application : offers) {
            if (!consumedContributors.contains(application)) {
                available.add(application);
            }
        }
        return available;
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
                existingItem.quantity = contributionOf(existingItem).add(contributionOf(pickedItem));
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
         * Quantity of this result item, normalized to plain notation on output (spec A5 §1.4).
         */
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = QuantitySerializer.class)
        public BigDecimal quantity;

        /**
         * The offer's attributed amount for this item: excl. tax, incl. tax, and the real
         * VAT rate of the product — never a blended rate.
         */
        public AmountEvaluation amount;

        /**
         * The catalog name of the product (digital sheet DG-01-01-01), read from the product the
         * engine already resolved to price the slice; omitted when the slice carries no resolved
         * product (an unknown article valued on its pushed price alone).
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String label;

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
            this.quantity = source.quantity == null ? BigDecimal.ZERO : source.quantity;
            this.amount = amount;
            this.label = source.product == null ? null : source.product.name;
        }
    }

    /**
     * One line of the EGAlim conformity record (EGALIM_GUARD spec §6).
     * <p>
     * A per-initial-line document of the generosity applied to the line: its nominal and final
     * prices, the counted generosity rate, the part excluded from the ceiling (anti-waste and
     * manual gestures), the margin still available before the ceiling, and the correction the
     * guard applied when the ceiling was exceeded. {@code remainingBeforeCap} is absent for an
     * {@link com.intermarche.valuation.domain.EgalimRegime#EXEMPT} line; {@code correction} is
     * present only when a correction was made. Amounts are at scale 2, the rate at scale 4.
     * <p>
     * Fields are public for JSON serialization; null fields are omitted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EgalimLine {

        /**
         * The EAN of the initial line; null for a line carrying no EAN.
         */
        public String ean;

        /**
         * The product label (its catalog name); null when the product is unknown.
         */
        public String label;

        /**
         * The EGAlim regime that governed the line ({@code FOOD_34}, {@code DPH_40},
         * {@code EXEMPT}).
         */
        public String regime;

        /**
         * The nominal price of the line: reference unit price times quantity, tax included.
         */
        public BigDecimal nominalPrice;

        /**
         * The final price of the line after every advantage, tax included, before the guard's
         * own correction.
         */
        public BigDecimal finalPrice;

        /**
         * The counted generosity rate: counted generosity divided by nominal, scale 4.
         */
        public BigDecimal generosityRate;

        /**
         * The counted generosity in euros: nominal minus final, less the excluded part.
         */
        public BigDecimal countedGenerosity;

        /**
         * The excluded generosity in euros: the anti-waste and manual-gesture share, outside the
         * ceiling.
         */
        public BigDecimal excludedGenerosity;

        /**
         * The generosity still available before the ceiling, in euros; {@code null} for an
         * exempt line, {@code 0.00} when the ceiling is reached or exceeded.
         */
        public BigDecimal remainingBeforeCap;

        /**
         * The correction applied by the guard, in euros; {@code null} unless a correction was
         * made (counted generosity above the ceiling).
         */
        public BigDecimal correction;

        /**
         * Default constructor for JSON serialization.
         */
        public EgalimLine() {
        }
    }

    /**
     * One line of the SRP floor record (spec-srp-guard §3): the floor, the final price after every
     * advantage, the margin above the floor, and the correction when one was applied. Emitted only
     * for controlled lines (those whose article carries an {@code SRP_FLOOR} price).
     * <p>
     * Fields are public for JSON serialization.
     */
    public static class SrpLine {

        /**
         * The id of the controlled initial line.
         */
        public String lineId;

        /**
         * The EAN of the controlled line.
         */
        public String ean;

        /**
         * The floor price of the line: floor unit price times quantity, tax included.
         */
        public BigDecimal floorPrice;

        /**
         * The final price of the line after every advantage (the EGAlim reprise included), tax
         * included, before the guard's own correction.
         */
        public BigDecimal finalPrice;

        /**
         * The margin above the floor in euros: final minus floor; {@code 0.00} on a corrected line.
         */
        public BigDecimal marginAboveFloor;

        /**
         * The correction applied by the guard, in euros; {@code null} unless the line was below its
         * floor (and not exempt nor overridden).
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public BigDecimal correction;

        /**
         * Stable machine reason code the till maps to a screen message (spec-srp-guard §3.A/B/C), never
         * a French text from the engine and distinct from the EGAlim record. One of
         * {@code SRP_FLOOR_CLAMPED} (the line was lifted to its floor), {@code SRP_MARGIN_WARNING} (the
         * discount reached a configured warning palier without being clamped) or {@code SRP_OVERRIDE}
         * (the floor was bypassed by a per-line derogation). {@code null} on a line granted without
         * reserve, so an unaffected line is unchanged.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String reasonCode;

        /**
         * The descent toward the floor, as a fraction of the room between the sale price and the floor
         * (spec-srp-guard §3.B): {@code (nominal − final) / (nominal − floor)}, scale 4. Present only
         * when the guard carries warning paliers and the line has a floor room to measure against;
         * {@code null} otherwise.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public BigDecimal descentFraction;

        /**
         * The highest configured warning palier the line's descent reached (spec-srp-guard §3.B), e.g.
         * {@code 0.80}; present only on a {@code SRP_MARGIN_WARNING} line, {@code null} otherwise.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public BigDecimal warningThreshold;

        /**
         * The discount that had been requested on the line, tax included (spec-srp-guard §3.A):
         * {@code nominal − final(before the floor lift)}. Present only on a clamped line that carries a
         * manual gesture, so the till can say "3.00 asked, 1.20 granted".
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public BigDecimal requestedDiscount;

        /**
         * The discount actually granted on the line after the floor lift, tax included
         * (spec-srp-guard §3.A): {@code nominal − floor = requestedDiscount − correction}. Present only
         * on a clamped line that carries a manual gesture.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public BigDecimal grantedDiscount;

        /**
         * The operator's reason for a per-line SRP override, echoed verbatim (spec-srp-guard §3.C);
         * present only on a {@code SRP_OVERRIDE} line whose declaration carried a reason.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String overrideReason;

        /**
         * Default constructor for JSON serialization.
         */
        public SrpLine() {
        }
    }

    /**
     * The {@code grantTotals} response block (spec-plafonds-attribution §3): the summed points and
     * vignettes attributed by the ticket. Both keys are always present — a zero says "this ticket
     * attributed none of that kind". Fields are public for JSON serialization.
     */
    public static class GrantTotals {

        /**
         * The total number of points attributed across every mechanic of the ticket.
         */
        public BigDecimal points;

        /**
         * The total number of vignettes attributed across every mechanic of the ticket (outgoing
         * only — never the vignettes the basket brought in).
         */
        public BigDecimal vignettes;

        /**
         * Default constructor for JSON serialization.
         */
        public GrantTotals() {
        }

        /**
         * Builds the totals.
         *
         * @param points    the summed attributed points.
         * @param vignettes the summed attributed vignettes.
         */
        public GrantTotals(BigDecimal points, BigDecimal vignettes) {
            this.points = points;
            this.vignettes = vignettes;
        }
    }

    /**
     * One entry of the {@code messages} response block (spec-messages-editique §1): a resolved till
     * message. Fields are public for JSON serialization.
     */
    public static class MessageLine {

        /**
         * The configuration code the message was declared under.
         */
        public String code;

        /**
         * The resolved message text, variables substituted.
         */
        public String text;

        /**
         * The print mode ({@code LINE}/{@code FOOTER}), omitted when unset.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String printMode;

        /**
         * Default constructor for JSON serialization.
         */
        public MessageLine() {
        }

        /**
         * Builds a message line.
         *
         * @param code      the configuration code.
         * @param text      the resolved text.
         * @param printMode the print mode, or null.
         */
        public MessageLine(String code, String text, String printMode) {
            this.code = code;
            this.text = text;
            this.printMode = printMode;
        }
    }

}