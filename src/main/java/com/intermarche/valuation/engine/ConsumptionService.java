package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.ConsumptionCounter;
import com.intermarche.valuation.domain.ConsumptionRecord;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests declared ticket consumption and keeps the {@link ConsumptionCounter}
 * aggregates up to date (spec §4).
 * <p>
 * The consumption of a cashed ticket is declared by the delivery chain, not by
 * a business emitter, and never by a valuation: only the cashing counts. Each
 * declaration writes the raw {@link ConsumptionRecord} lines (for audit and
 * resynchronisation) and updates the {@code (offerCode, storeCode)} counters
 * transactionally, in the same unit of work.
 * <p>
 * The declaration is idempotent on the triplet
 * {@code (storeCode, ticketId, direction)}: a replay is accepted and ignored, so
 * any link of the delivery chain may relay and replay it. A {@code CANCELLATION}
 * without a prior {@code SALE} is accepted (out-of-order relay) and simply
 * decrements. An {@code offerCode} unknown to the referential is counted all the
 * same — the counter is a comptable truth.
 */
@ApplicationScoped
public class ConsumptionService {

    private static final Logger LOGGER = Logger.getLogger(ConsumptionService.class);

    /**
     * The instant format the till declares (ISO local date-time, seconds precision).
     */
    private static final DateTimeFormatter FINALIZED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Records one ticket consumption declaration, transactionally.
     * <p>
     * Validates the mandatory identity and direction, applies idempotence on the declaration
     * triplet, aggregates the advantages per offer (a ticket counts as ONE application per offer,
     * whatever the number of lines — spec §3), writes the raw records, and moves the counters by
     * the direction's sign.
     *
     * @param declaration the declaration received from the delivery chain.
     * @return the outcome carrying the {@code duplicate} flag and the number of offers moved.
     * @throws IllegalArgumentException when the identity is incomplete or the direction is unknown.
     */
    @Transactional
    public Outcome record(Declaration declaration) {
        String ticketId = trimToNull(declaration.ticketId);
        String storeCode = trimToNull(declaration.storeCode);
        String direction = normalizeDirection(declaration.direction);
        if (ticketId == null || storeCode == null) {
            throw new IllegalArgumentException("ticketId and storeCode are mandatory.");
        }
        if (direction == null) {
            throw new IllegalArgumentException(
                    "direction must be SALE or CANCELLATION, was: " + declaration.direction);
        }
        if (ConsumptionRecord.existsForTicket(storeCode, ticketId, direction)) {
            // Idempotence (spec §4): the triplet was already recorded, so the replay changes
            // nothing. This is what makes a declaration relayable by any link of the chain.
            LOGGER.debugf("Duplicate consumption ignored: %s %s %s", storeCode, ticketId, direction);
            return new Outcome(true, 0);
        }
        LocalDateTime finalizedAt = parseFinalizedAt(declaration.finalizedAt);
        Map<String, OfferAggregate> byOffer = aggregatePerOffer(declaration.advantages);
        int sign = ConsumptionRecord.DIRECTION_CANCELLATION.equals(direction) ? -1 : 1;
        for (Map.Entry<String, OfferAggregate> entry : byOffer.entrySet()) {
            String offerCode = entry.getKey();
            OfferAggregate aggregate = entry.getValue();
            writeRecord(ticketId, storeCode, direction, finalizedAt, offerCode, aggregate);
            moveCounter(offerCode, storeCode, aggregate.total, sign);
        }
        return new Outcome(false, byOffer.size());
    }

    /**
     * Aggregates the declared advantages per offer: sums the absolute amounts and merges the
     * optional per-article breakdown, keeping one entry per offer, so a ticket that carries the same
     * offer on several lines still counts as a single application (spec §3). A blank offer code or a
     * null amount line is ignored; a blank EAN in the breakdown is dropped from the detail.
     *
     * @param advantages the raw advantage lines of the declaration, possibly null.
     * @return an insertion-ordered map of offer code to its aggregate (total and per-EAN detail).
     */
    private Map<String, OfferAggregate> aggregatePerOffer(List<Advantage> advantages) {
        Map<String, OfferAggregate> byOffer = new LinkedHashMap<>();
        if (advantages == null) {
            return byOffer;
        }
        for (Advantage advantage : advantages) {
            if (advantage == null) {
                continue;
            }
            String offerCode = trimToNull(advantage.offerCode);
            if (offerCode == null) {
                continue;
            }
            BigDecimal amount = advantage.amount == null ? BigDecimal.ZERO : advantage.amount.abs();
            OfferAggregate aggregate = byOffer.computeIfAbsent(offerCode, k -> new OfferAggregate());
            aggregate.total = aggregate.total.add(amount);
            // Funding is a property of the offer, not of a single line: keep the first non-null value
            // declared for the offer, copied verbatim (spec-exploitation §3).
            if (aggregate.funding == null) {
                aggregate.funding = trimToNull(advantage.funding);
            }
            if (advantage.lines != null) {
                for (Line line : advantage.lines) {
                    if (line == null) {
                        continue;
                    }
                    String ean = trimToNull(line.ean);
                    if (ean == null) {
                        continue;
                    }
                    BigDecimal lineAmount = line.amount == null ? BigDecimal.ZERO : line.amount.abs();
                    aggregate.amountByEan.merge(ean, lineAmount, BigDecimal::add);
                }
            }
        }
        return byOffer;
    }

    /**
     * Writes one raw consumption record line, with its optional per-article breakdown.
     *
     * @param ticketId    the ticket identifier.
     * @param storeCode   the point-of-sale code.
     * @param direction   {@code SALE} or {@code CANCELLATION}.
     * @param finalizedAt the finalization instant, or null.
     * @param offerCode   the offer code.
     * @param aggregate   the offer's aggregate: summed absolute amount and per-EAN detail.
     */
    private void writeRecord(String ticketId, String storeCode, String direction,
                             LocalDateTime finalizedAt, String offerCode, OfferAggregate aggregate) {
        ConsumptionRecord record = new ConsumptionRecord();
        record.ticketId = ticketId;
        record.storeCode = storeCode;
        record.direction = direction;
        record.finalizedAt = finalizedAt;
        record.offerCode = offerCode;
        record.amount = aggregate.total.setScale(2, RoundingMode.HALF_UP);
        record.funding = aggregate.funding;
        for (Map.Entry<String, BigDecimal> line : aggregate.amountByEan.entrySet()) {
            record.lines.add(new ConsumptionRecord.ConsumptionLine(
                    line.getKey(), line.getValue().setScale(2, RoundingMode.HALF_UP)));
        }
        record.persist();
    }

    /**
     * Moves the {@code (offerCode, storeCode)} counter by the direction's sign, creating it when the
     * pair consumed nothing yet.
     *
     * @param offerCode the offer code.
     * @param storeCode the point-of-sale code.
     * @param amount    the positive amount to move.
     * @param sign      {@code +1} for a SALE, {@code -1} for a CANCELLATION.
     */
    private void moveCounter(String offerCode, String storeCode, BigDecimal amount, int sign) {
        ConsumptionCounter counter = ConsumptionCounter.findCounter(offerCode, storeCode);
        if (counter == null) {
            counter = new ConsumptionCounter(offerCode, storeCode);
            Panache.getEntityManager().persist(counter);
        }
        BigDecimal move = amount.multiply(BigDecimal.valueOf(sign)).setScale(2, RoundingMode.HALF_UP);
        counter.consumedAmount = counter.consumedAmount.add(move).setScale(2, RoundingMode.HALF_UP);
        counter.consumedApplications += sign;
    }

    /**
     * Parses the declared finalization instant, tolerating a null/blank/unparseable value by
     * leaving it unset — the finalization instant is audit context, never a counter input.
     *
     * @param raw the raw instant string, possibly null.
     * @return the parsed instant, or null when absent or unparseable.
     */
    private LocalDateTime parseFinalizedAt(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, FINALIZED_AT_FORMAT);
        } catch (Exception e) {
            LOGGER.debugf("Ignoring unparseable finalizedAt: %s", raw);
            return null;
        }
    }

    /**
     * Normalizes a direction token to its canonical constant.
     *
     * @param raw the raw direction, possibly null.
     * @return {@code SALE}, {@code CANCELLATION}, or null when the token is unknown.
     */
    private String normalizeDirection(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase();
        if (ConsumptionRecord.DIRECTION_SALE.equals(upper)
                || ConsumptionRecord.DIRECTION_CANCELLATION.equals(upper)) {
            return upper;
        }
        return null;
    }

    /**
     * Trims a string to null when it is null or blank.
     *
     * @param value the value, possibly null.
     * @return the trimmed value, or null when null or blank.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The per-offer aggregate of a declaration: the summed absolute amount and the merged per-EAN
     * detail, both used to write a single {@link ConsumptionRecord} for the offer.
     */
    private static class OfferAggregate {

        /**
         * The summed absolute amount across the offer's advantage lines.
         */
        private BigDecimal total = BigDecimal.ZERO;

        /**
         * The per-EAN absolute amounts, insertion-ordered; empty when no detail was declared.
         */
        private final Map<String, BigDecimal> amountByEan = new LinkedHashMap<>();

        /**
         * The promotion funding declared for the offer (spec-exploitation §3), or null when none.
         */
        private String funding;
    }

    /**
     * The outcome of a consumption ingestion.
     */
    public static class Outcome {

        /**
         * Whether the declaration was a replay of an already-recorded triplet.
         */
        public final boolean duplicate;

        /**
         * The number of distinct offers whose counter was moved (zero on a duplicate).
         */
        public final int offersAffected;

        /**
         * Creates an outcome.
         *
         * @param duplicate      whether the declaration was a replay.
         * @param offersAffected the number of offers moved.
         */
        public Outcome(boolean duplicate, int offersAffected) {
            this.duplicate = duplicate;
            this.offersAffected = offersAffected;
        }
    }

    /**
     * One declared advantage line: the offer and the absolute amount granted.
     */
    public static class Advantage {

        /**
         * The offer code the advantage was granted under.
         */
        public String offerCode;

        /**
         * The absolute value of the advantage granted on the ticket for that offer.
         */
        public BigDecimal amount;

        /**
         * The optional per-article breakdown (spec §4): the amount financed on each EAN, as the
         * till already knows it. Absent (empty/null) on legacy declarations — purely additive and
         * backward-compatible; the export then emits a single line with a blank EAN.
         */
        public List<Line> lines;

        /**
         * Who funds the promotion (spec-exploitation §3): {@code STORE}, {@code BANNER} or
         * {@code SPLIT_50_50}, as the till read it from the valuation output. Copied verbatim onto the
         * persisted record, never interpreted; absent (null) on declarations that carry no funding.
         */
        public String funding;

        /**
         * Default constructor for JSON binding.
         */
        public Advantage() {
        }
    }

    /**
     * One article's share of a declared advantage (spec §4): an EAN and the amount financed on it.
     */
    public static class Line {

        /**
         * The EAN the amount was financed on.
         */
        public String ean;

        /**
         * The amount financed on that EAN.
         */
        public BigDecimal amount;

        /**
         * Default constructor for JSON binding.
         */
        public Line() {
        }
    }

    /**
     * A ticket consumption declaration as received from the delivery chain (spec §4).
     */
    public static class Declaration {

        /**
         * The cashed ticket identifier.
         */
        public String ticketId;

        /**
         * The point-of-sale code the ticket was cashed at.
         */
        public String storeCode;

        /**
         * The finalization instant at the till (ISO local date-time), audit context only.
         */
        public String finalizedAt;

        /**
         * {@code SALE} or {@code CANCELLATION}.
         */
        public String direction;

        /**
         * The advantages granted on the ticket.
         */
        public List<Advantage> advantages = new ArrayList<>();

        /**
         * Default constructor for JSON binding.
         */
        public Declaration() {
        }
    }
}
