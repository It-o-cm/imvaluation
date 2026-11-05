package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.valuation.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Values e-commerce returns, statelessly (digital sheet DG-01-03, spec §5).
 * <p>
 * The engine keeps no basket and no order: the selling front keeps its basket and the closing
 * snapshot — the basket it sent to {@code /valuation} at close and the answer it received — and
 * hands the snapshot back with the returned lines. The answer is the refund valuation, positive
 * amounts to refund, one line per requested line in request order, with the advantages the
 * return takes back so the caller can declare the {@code CANCELLATION} on {@code /consumptions}.
 * Nothing is persisted and no consumption is declared here.
 * <p>
 * Every line names its price source: {@code ORDER} (the price paid, read from the snapshot,
 * net of the line's share of the order's discounts, DG-01-03-06), {@code CURRENT} (the store's
 * price now, DG-01-03-04), {@code DATE} (the price in force at a date, DG-01-03-07) or
 * {@code PUSHED} (the caller's price, DG-01-03-08). The mode frames the return:
 * <ul>
 *   <li>{@code FULL} (DG-01-03-01): the whole snapshot is refunded line for line, exactly what it
 *       cashed, and every advantage of the order is taken back;</li>
 *   <li>{@code PARTIAL} (DG-01-03-03): the {@code ORDER} lines are refunded at their paid price;
 *       the basket the customer keeps is re-valued at the order's instant, and the generosity it
 *       no longer deserves (a global offer whose condition it no longer meets) is taken back from
 *       the refund of the returned order lines, pro-rata, the cent residue on the last line;</li>
 *   <li>{@code LINES}: every line valued on its own source, no basket recomputed.</li>
 * </ul>
 * No promotion of the day applies to a return: it is a reference price, not a sale.
 */
@ApplicationScoped
public class ReturnValuationService implements EngineTrait {

    /**
     * Mode refunding the whole order.
     */
    static final String MODE_FULL = "FULL";

    /**
     * Mode refunding part of the order.
     */
    static final String MODE_PARTIAL = "PARTIAL";

    /**
     * Mode valuing lines one by one.
     */
    static final String MODE_LINES = "LINES";

    /**
     * The message answered when the engine leaves articles of the kept basket unvalued.
     */
    static final String UNPROCESSED_MESSAGE = "Valuation failed: Some items could not be processed by any offer.";

    /**
     * The stateless valuation engine, which re-values the kept basket.
     */
    @Inject
    ValuationEngine engine;

    /**
     * The application mapper, the one {@code /valuation} serializes its answers with.
     */
    @Inject
    ObjectMapper mapper;

    /**
     * Values a return.
     *
     * @param request the return request.
     * @return the refund valuation.
     * @throws Refusal 400 on a malformed request (spec §5.3), 422 naming the EAN when the
     *                 referential cannot price an article or the engine cannot value the kept
     *                 basket, 500 when the kept basket's valuation cannot be serialized.
     */
    public ReturnView valuate(ReturnRequest request) {
        if (request == null) {
            throw new Refusal(400, "A return body is required.");
        }
        String mode = request.mode == null ? "" : request.mode.trim().toUpperCase();
        if (!MODE_FULL.equals(mode) && !MODE_PARTIAL.equals(mode) && !MODE_LINES.equals(mode)) {
            throw new Refusal(400, "mode must be FULL, PARTIAL or LINES.");
        }
        Snapshot snapshot = request.snapshot;
        if (snapshot == null && !MODE_LINES.equals(mode)) {
            throw new Refusal(400, "snapshot is required in FULL and PARTIAL mode: the request and the evaluation kept at close.");
        }
        if (snapshot != null && (snapshot.evaluation == null || !snapshot.evaluation.isObject())) {
            throw new Refusal(400, "snapshot.evaluation is required: the /valuation answer kept at close.");
        }
        Map<String, EvaluationReader.PaidLine> paid = snapshot == null ? null : EvaluationReader.paidLines(snapshot.evaluation);
        Previous previous = request.previousReturns == null ? new Previous() : request.previousReturns;
        Map<String, BigDecimal> before = previous.returned == null ? new LinkedHashMap<>() : previous.returned;
        if (MODE_FULL.equals(mode)) {
            return full(snapshot, paid, before);
        }
        if (request.lines == null || request.lines.isEmpty()) {
            throw new Refusal(400, "lines are required in PARTIAL and LINES mode.");
        }
        ReturnView view = new ReturnView();
        Map<String, BigDecimal> now = new LinkedHashMap<>();
        List<OrderEntry> orderEntries = new ArrayList<>();
        Store store = null;
        for (ReturnLine line : request.lines) {
            positive(line.quantity);
            String source = line.priceSource == null ? (MODE_PARTIAL.equals(mode) ? "ORDER" : "")
                    : line.priceSource.trim().toUpperCase();
            ReturnLineView row;
            switch (source) {
                case "ORDER":
                    OrderEntry entry = orderEntry(paid, before, now, line);
                    orderEntries.add(entry);
                    row = entry.row;
                    break;
                case "PUSHED":
                    row = priced(line, "PUSHED", ReturnPricing.pushed(line.quantity, line.pricePerUnitExclTax,
                            line.pricePerUnitInclTax, line.vatRate));
                    break;
                case "DATE":
                    if (line.priceDate == null || line.priceDate.isBlank()) {
                        throw new Refusal(400, "priceDate is required for a DATE price.");
                    }
                    store = store == null ? store(request) : store;
                    row = priced(line, "DATE", ReturnPricing.referential(line.produceEan, line.quantity, line.priceDate, store));
                    break;
                case "CURRENT":
                    store = store == null ? store(request) : store;
                    row = priced(line, "CURRENT", ReturnPricing.referential(line.produceEan, line.quantity, null, store));
                    break;
                default:
                    throw new Refusal(400, "priceSource must be ORDER, CURRENT, DATE or PUSHED.");
            }
            view.lines.add(row);
        }
        if (MODE_PARTIAL.equals(mode) && !orderEntries.isEmpty()) {
            takeBack(snapshot, paid, before, now, previous.withdrawnGenerosity, orderEntries, view);
        }
        for (OrderEntry entry : orderEntries) {
            entry.settle();
        }
        return totals(view);
    }

    /**
     * Refunds the whole snapshot line for line, exactly what it cashed, and takes back every
     * advantage of the order.
     *
     * @param snapshot the closing snapshot.
     * @param paid     the paid lines of the snapshot.
     * @param before   the quantities earlier returns took.
     * @return the refund valuation.
     */
    private ReturnView full(Snapshot snapshot, Map<String, EvaluationReader.PaidLine> paid, Map<String, BigDecimal> before) {
        if (!before.isEmpty()) {
            throw new Refusal(400, "The order already has returns: return the remaining lines with PARTIAL.");
        }
        if (paid.isEmpty()) {
            throw new Refusal(400, "snapshot.evaluation holds no valued line.");
        }
        ReturnView view = new ReturnView();
        BigDecimal refunded = BigDecimal.ZERO;
        ReturnLineView last = null;
        for (EvaluationReader.PaidLine line : paid.values()) {
            last = orderRow(line, line.quantity);
            last.amount = line.paidTtc();
            last.discountTakenBack = line.grossTtc.setScale(2, RoundingMode.HALF_UP).subtract(last.amount);
            refunded = refunded.add(last.amount);
            view.lines.add(last);
        }
        BigDecimal residue = EvaluationReader.totalIncludingTax(snapshot.evaluation).subtract(refunded);
        last.amount = last.amount.add(residue);
        last.discountTakenBack = last.discountTakenBack.subtract(residue);
        for (ConsumptionService.Advantage advantage : EvaluationReader.perOffer(EvaluationReader.advantagesOf(snapshot.evaluation))) {
            view.advantagesTakenBack.add(new TakenBack(advantage.offerCode, advantage.amount));
        }
        return totals(view);
    }

    /**
     * Checks an {@code ORDER} line against the snapshot and computes what was paid for it.
     *
     * @param paid   the paid lines of the snapshot, or null when the request carries none.
     * @param before the quantities earlier returns took.
     * @param now    the quantities this return took so far, updated.
     * @param line   the returned line.
     * @return the order entry, its amount not yet settled.
     */
    private static OrderEntry orderEntry(Map<String, EvaluationReader.PaidLine> paid, Map<String, BigDecimal> before,
                                         Map<String, BigDecimal> now, ReturnLine line) {
        if (paid == null) {
            throw new Refusal(400, "An ORDER price requires the snapshot.");
        }
        EvaluationReader.PaidLine origin = line.lineId == null ? null : paid.get(line.lineId);
        if (origin == null || origin.charge) {
            throw new Refusal(400, "lineId " + line.lineId + " is not a line of the snapshot.");
        }
        BigDecimal already = before.getOrDefault(line.lineId, BigDecimal.ZERO).add(now.getOrDefault(line.lineId, BigDecimal.ZERO));
        BigDecimal left = origin.quantity.subtract(already);
        if (line.quantity.compareTo(left) > 0) {
            throw new Refusal(400, "Line " + line.lineId + ": quantity " + line.quantity.stripTrailingZeros().toPlainString()
                    + " exceeds the " + left.stripTrailingZeros().toPlainString() + " left in the snapshot.");
        }
        now.merge(line.lineId, line.quantity, BigDecimal::add);
        OrderEntry entry = new OrderEntry();
        entry.row = orderRow(origin, line.quantity);
        entry.paidShare = origin.paidFor(already.add(line.quantity)).subtract(origin.paidFor(already));
        entry.takenBack = BigDecimal.ZERO;
        return entry;
    }

    /**
     * Takes back, from the refund of the returned order lines, the generosity the kept basket no
     * longer deserves, and lists the offers it no longer earns.
     *
     * @param snapshot        the closing snapshot.
     * @param paid            the paid lines of the snapshot.
     * @param before          the quantities earlier returns took.
     * @param now             the quantities this return takes.
     * @param withdrawnBefore the generosity earlier returns already took back, or null.
     * @param entries         the returned order lines, in request order.
     * @param view            the refund valuation, completed.
     */
    private void takeBack(Snapshot snapshot, Map<String, EvaluationReader.PaidLine> paid, Map<String, BigDecimal> before,
                          Map<String, BigDecimal> now, BigDecimal withdrawnBefore, List<OrderEntry> entries, ReturnView view) {
        if (snapshot.request == null || !snapshot.request.isObject()) {
            throw new Refusal(400, "snapshot.request is required to return part of the order: the basket valued at close.");
        }
        Map<String, BigDecimal> earnedBefore = before.isEmpty() ? EvaluationReader.earnedByOffer(snapshot.evaluation)
                : EvaluationReader.earnedByOffer(evaluate(keptBasket(snapshot, before, Map.of())));
        JsonNode keptEvaluation = evaluate(keptBasket(snapshot, before, now));
        BigDecimal worth = EvaluationReader.totalIncludingTax(keptEvaluation);
        BigDecimal paidKept = BigDecimal.ZERO;
        for (EvaluationReader.PaidLine line : paid.values()) {
            BigDecimal gone = line.charge ? BigDecimal.ZERO : before.getOrDefault(line.lineId, BigDecimal.ZERO)
                    .add(now.getOrDefault(line.lineId, BigDecimal.ZERO));
            paidKept = paidKept.add(line.paidTtc().subtract(line.paidFor(gone)));
        }
        BigDecimal withdrawn = worth.subtract(paidKept).max(BigDecimal.ZERO);
        BigDecimal paidReturned = BigDecimal.ZERO;
        List<BigDecimal> weights = new ArrayList<>();
        for (OrderEntry entry : entries) {
            weights.add(entry.paidShare);
            paidReturned = paidReturned.add(entry.paidShare);
        }
        BigDecimal effective = withdrawn.subtract(withdrawnBefore == null ? BigDecimal.ZERO : withdrawnBefore)
                .min(paidReturned).setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> shares = spread(effective, weights);
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).takenBack = shares.get(i);
        }
        view.withdrawnGenerosity = effective;
        Map<String, BigDecimal> earnedAfter = EvaluationReader.earnedByOffer(keptEvaluation);
        for (Map.Entry<String, BigDecimal> earned : earnedBefore.entrySet()) {
            if (!earnedAfter.containsKey(earned.getKey())) {
                view.advantagesTakenBack.add(new TakenBack(earned.getKey(), earned.getValue()));
            }
        }
    }

    /**
     * Rebuilds the basket the customer keeps from the snapshot's request: every line at its kept
     * quantity, its pushed price or its price date kept, the order's instant ({@code createdAt})
     * as the price date otherwise, with the order's coupons, card and card promotions.
     *
     * @param snapshot the closing snapshot.
     * @param before   the quantities earlier returns took.
     * @param now      the quantities this return takes.
     * @return the kept basket, closed.
     */
    private static Basket keptBasket(Snapshot snapshot, Map<String, BigDecimal> before, Map<String, BigDecimal> now) {
        Basket kept = EvaluationReader.convert(snapshot.request, Basket.class);
        String instant = kept.createdAt == null || kept.createdAt.isBlank() ? null : kept.createdAt;
        List<Basket.Item> items = new ArrayList<>();
        for (Basket.Item item : kept.items == null ? List.<Basket.Item>of() : kept.items) {
            BigDecimal quantity = item.quantity.subtract(before.getOrDefault(item.lineId, BigDecimal.ZERO))
                    .subtract(now.getOrDefault(item.lineId, BigDecimal.ZERO));
            if (quantity.signum() <= 0) {
                continue;
            }
            item.quantity = quantity;
            // The basket schema makes the pushed trio all-or-nothing, so one field tells a pushed
            // line; a partial trio is refused when the kept basket is validated.
            boolean pushed = item.pricePerUnitInclTax != null;
            if (!pushed && instant != null && (item.priceDate == null || item.priceDate.isBlank())) {
                item.priceDate = instant;
            }
            items.add(item);
        }
        kept.items = items;
        kept.closed = Boolean.TRUE;
        return kept;
    }

    /**
     * Values a basket through the stateless engine: an empty basket is worth zero without the
     * engine; any other is validated against {@link Basket#BASKET_SCHEMA}, valued, and serialized
     * exactly as {@code /valuation} answers.
     *
     * @param basket the basket.
     * @return the serialized evaluation.
     */
    JsonNode evaluate(Basket basket) {
        if (basket.items.isEmpty()) {
            ObjectNode empty = EvaluationReader.MAPPER.createObjectNode();
            empty.putObject("totalPrice").put("amountIncludingTax", BigDecimal.ZERO.setScale(2));
            return empty;
        }
        String payload = EvaluationReader.write(basket);
        try {
            processSpecification(Basket.BASKET_SCHEMA, payload, node -> {
            });
        } catch (IllegalArgumentException e) {
            throw new Refusal(400, "The kept basket breaks the basket schema: " + e.getMessage());
        }
        BasketEvaluation evaluation;
        try {
            evaluation = engine.evaluate(basket);
        } catch (RuntimeException e) {
            throw new Refusal(422, e.getMessage());
        }
        if (!evaluation.getToEvaluate().isEmpty()) {
            throw new Refusal(422, UNPROCESSED_MESSAGE);
        }
        try {
            return EvaluationReader.MAPPER.readTree(mapper.writeValueAsString(evaluation));
        } catch (Exception e) {
            throw new Refusal(500, "Cannot serialize the evaluation: " + e.getMessage());
        }
    }

    /**
     * Builds the row of an order line: its unit reference price is what the line cost before
     * the order's discounts.
     *
     * @param line     the paid line of the snapshot.
     * @param quantity the quantity returned.
     * @return the row, its amount not yet set.
     */
    private static ReturnLineView orderRow(EvaluationReader.PaidLine line, BigDecimal quantity) {
        ReturnLineView row = new ReturnLineView();
        row.lineId = line.lineId;
        row.produceEan = line.ean;
        row.quantity = quantity;
        row.priceSource = "ORDER";
        row.referencePrice = line.quantity.signum() == 0 ? line.grossTtc.setScale(2, RoundingMode.HALF_UP)
                : line.grossTtc.divide(line.quantity, 2, RoundingMode.HALF_UP);
        row.vatRate = line.vatRate;
        return row;
    }

    /**
     * Builds the row of a line priced on the referential or on a pushed price.
     *
     * @param line   the returned line.
     * @param source the price source.
     * @param priced the price found.
     * @return the row, nothing taken back.
     */
    private static ReturnLineView priced(ReturnLine line, String source, ReturnPricing.Priced priced) {
        ReturnLineView row = new ReturnLineView();
        row.lineId = line.lineId;
        row.produceEan = line.produceEan;
        row.quantity = line.quantity;
        row.priceSource = source;
        row.referencePrice = priced.unit.amountIncludingTax;
        row.discountTakenBack = BigDecimal.ZERO.setScale(2);
        row.amount = priced.amount.amountIncludingTax;
        row.vatRate = priced.unit.vatRate;
        return row;
    }

    /**
     * Resolves the store of a CURRENT or DATE price: the request's, else the snapshot's.
     *
     * @param request the return request.
     * @return the store.
     */
    private static Store store(ReturnRequest request) {
        String storeCode = request.storeCode != null ? request.storeCode
                : request.snapshot == null || request.snapshot.request == null ? null
                : EvaluationReader.text(request.snapshot.request, "storeCode");
        if (storeCode == null) {
            throw new Refusal(400, "storeCode is mandatory for a CURRENT or DATE price.");
        }
        Store store = Store.findByCode(storeCode);
        if (store == null) {
            throw new Refusal(400, "Unknown store: " + storeCode);
        }
        return store;
    }

    /**
     * Refuses a missing or non-positive quantity.
     *
     * @param quantity the quantity.
     */
    private static void positive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new Refusal(400, "quantity must be strictly positive.");
        }
    }

    /**
     * Spreads an amount over weights pro-rata, the cent residue on the last share; weights that
     * sum to zero share nothing.
     *
     * @param amount  the amount, negative when generosity is given back.
     * @param weights the weights, at least one.
     * @return one share per weight.
     */
    static List<BigDecimal> spread(BigDecimal amount, List<BigDecimal> weights) {
        BigDecimal total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            List<BigDecimal> zeros = new ArrayList<>();
            for (int i = 0; i < weights.size(); i++) {
                zeros.add(BigDecimal.ZERO.setScale(2));
            }
            return zeros;
        }
        return EvaluationReader.prorate(amount, weights);
    }

    /**
     * Completes a refund valuation with its total and its VAT breakdown.
     *
     * @param view the refund valuation, its lines valued.
     * @return the same valuation.
     */
    private static ReturnView totals(ReturnView view) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        Map<BigDecimal, BigDecimal> byRate = new TreeMap<>();
        for (ReturnLineView line : view.lines) {
            total = total.add(line.amount);
            byRate.merge(line.vatRate, line.amount, BigDecimal::add);
        }
        view.totalRefund = total;
        for (Map.Entry<BigDecimal, BigDecimal> rate : byRate.entrySet()) {
            VatLine vat = new VatLine();
            vat.vatRate = rate.getKey();
            vat.amountIncludingTax = rate.getValue().setScale(2, RoundingMode.HALF_UP);
            vat.amountExcludingTax = rate.getValue().divide(BigDecimal.ONE.add(rate.getKey()), 2, RoundingMode.HALF_UP);
            view.vatBreakdown.add(vat);
        }
        return view;
    }

    /**
     * A returned order line being valued: its row and the amounts it carries.
     */
    private static final class OrderEntry {

        /**
         * The row answered.
         */
        ReturnLineView row;

        /**
         * What was paid for the returned quantity, discounts of the order included.
         */
        BigDecimal paidShare;

        /**
         * The generosity taken back on this line.
         */
        BigDecimal takenBack;

        /**
         * Sets the row's amount (paid share minus generosity taken back) and the discount it takes
         * back, so that reference price times quantity equals amount plus discount.
         */
        void settle() {
            row.amount = paidShare.subtract(takenBack).setScale(2, RoundingMode.HALF_UP);
            row.discountTakenBack = row.referencePrice.multiply(row.quantity).setScale(2, RoundingMode.HALF_UP)
                    .subtract(row.amount);
        }
    }

    /**
     * A refusal of a return valuation, carrying its HTTP status.
     */
    public static class Refusal extends RuntimeException {

        /**
         * The HTTP status to answer.
         */
        public final int status;

        /**
         * Builds a refusal.
         *
         * @param status  the HTTP status to answer.
         * @param message the reason.
         */
        public Refusal(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * A return request (spec §5.1): everything the valuation needs, since the engine keeps
     * nothing.
     */
    public static class ReturnRequest {

        /**
         * The store of the CURRENT and DATE prices; defaults to the snapshot request's.
         */
        public String storeCode;

        /**
         * The order reference, informative.
         */
        public String orderReference;

        /**
         * {@code FULL}, {@code PARTIAL} or {@code LINES}.
         */
        public String mode;

        /**
         * The closing snapshot kept by the caller; mandatory in FULL and PARTIAL.
         */
        public Snapshot snapshot;

        /**
         * Extension: what earlier returns of the order took, so a second partial return measures
         * the basket really kept. Absent on a first return.
         */
        public Previous previousReturns;

        /**
         * The returned lines (PARTIAL, LINES).
         */
        public List<ReturnLine> lines;

        /**
         * Default constructor for JSON binding.
         */
        public ReturnRequest() {
        }
    }

    /**
     * The closing snapshot the caller kept.
     */
    public static class Snapshot {

        /**
         * The basket sent to {@code /valuation} at close, with its line identifiers.
         */
        public JsonNode request;

        /**
         * The {@code /valuation} answer received at close.
         */
        public JsonNode evaluation;

        /**
         * Default constructor for JSON binding.
         */
        public Snapshot() {
        }
    }

    /**
     * Extension: what earlier returns of an order took.
     */
    public static class Previous {

        /**
         * The quantity already returned per line identifier.
         */
        public Map<String, BigDecimal> returned = new LinkedHashMap<>();

        /**
         * The generosity already taken back by the earlier returns (their
         * {@code withdrawnGenerosity}, summed).
         */
        public BigDecimal withdrawnGenerosity = BigDecimal.ZERO.setScale(2);

        /**
         * Default constructor for JSON binding.
         */
        public Previous() {
        }
    }

    /**
     * A returned line.
     */
    public static class ReturnLine {

        /**
         * The snapshot line identifier (ORDER).
         */
        public String lineId;

        /**
         * The product EAN.
         */
        public String produceEan;

        /**
         * The returned quantity, strictly positive.
         */
        public BigDecimal quantity;

        /**
         * {@code ORDER}, {@code CURRENT}, {@code DATE} or {@code PUSHED}; {@code ORDER} by default
         * in PARTIAL mode.
         */
        public String priceSource;

        /**
         * The ISO-8601 date (or date-time) of the price (DATE).
         */
        public String priceDate;

        /**
         * The pushed unit price tax excluded (PUSHED).
         */
        public BigDecimal pricePerUnitExclTax;

        /**
         * The pushed unit price tax included (PUSHED).
         */
        public BigDecimal pricePerUnitInclTax;

        /**
         * The pushed VAT rate (PUSHED).
         */
        public BigDecimal vatRate;

        /**
         * Default constructor for JSON binding.
         */
        public ReturnLine() {
        }
    }

    /**
     * The refund valuation (spec §5.2): positive amounts to refund.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReturnView {

        /**
         * One line per requested line, in request order (every snapshot line in FULL mode).
         */
        public List<ReturnLineView> lines = new ArrayList<>();

        /**
         * The advantages the return takes back, per offer, to declare as a {@code CANCELLATION}.
         */
        public List<TakenBack> advantagesTakenBack = new ArrayList<>();

        /**
         * The total to refund.
         */
        public BigDecimal totalRefund;

        /**
         * The refund per VAT rate.
         */
        public List<VatLine> vatBreakdown = new ArrayList<>();

        /**
         * Extension (PARTIAL): the generosity this return takes back because the kept basket no
         * longer deserves it, negative when an earlier return's is given back.
         */
        public BigDecimal withdrawnGenerosity;

        /**
         * Default constructor for JSON serialization.
         */
        public ReturnView() {
        }
    }

    /**
     * A valued return line.
     */
    public static class ReturnLineView {

        /**
         * The snapshot line identifier, or null.
         */
        public String lineId;

        /**
         * The product EAN, or null for a basket-level charge.
         */
        public String produceEan;

        /**
         * The returned quantity.
         */
        public BigDecimal quantity;

        /**
         * The price source.
         */
        public String priceSource;

        /**
         * The unit reference price, tax included.
         */
        public BigDecimal referencePrice;

        /**
         * The discount taken back on the line: reference price times quantity minus amount.
         */
        public BigDecimal discountTakenBack;

        /**
         * The amount refunded for the line, tax included.
         */
        public BigDecimal amount;

        /**
         * The VAT rate of the line.
         */
        public BigDecimal vatRate;

        /**
         * Default constructor for JSON serialization.
         */
        public ReturnLineView() {
        }
    }

    /**
     * An advantage taken back by a return.
     */
    public static class TakenBack {

        /**
         * The offer code.
         */
        public String offerCode;

        /**
         * The amount taken back, tax included.
         */
        public BigDecimal amount;

        /**
         * Builds an advantage taken back.
         *
         * @param offerCode the offer code.
         * @param amount    the amount.
         */
        public TakenBack(String offerCode, BigDecimal amount) {
            this.offerCode = offerCode;
            this.amount = amount;
        }
    }

    /**
     * The refund of one VAT rate.
     */
    public static class VatLine {

        /**
         * The VAT rate.
         */
        public BigDecimal vatRate;

        /**
         * The refund at that rate, tax included.
         */
        public BigDecimal amountIncludingTax;

        /**
         * The refund at that rate, tax excluded.
         */
        public BigDecimal amountExcludingTax;

        /**
         * Default constructor for JSON serialization.
         */
        public VatLine() {
        }
    }
}
