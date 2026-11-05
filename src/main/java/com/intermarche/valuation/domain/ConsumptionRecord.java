package com.intermarche.valuation.domain;

import jakarta.persistence.Cacheable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The raw, per-advantage trace of a cashed ticket's declared consumption, kept
 * for audit and resynchronisation (spec §4).
 * <p>
 * One row is written per {@code (ticket, offer)} advantage line. Idempotence is
 * a property of the whole ticket declaration: the triplet
 * {@code (storeCode, ticketId, direction)} is unique per declaration, so a
 * replay of the same triplet is accepted and ignored — the property that makes
 * a declaration relayable and rejouable by any link of the delivery chain
 * (spec §4). The row-level unique constraint additionally pins the fourth
 * dimension, the offer, so two links racing on the same ticket cannot double it.
 * <p>
 * {@link #direction} is {@code SALE} or {@code CANCELLATION}: the amount stays
 * positive, the sign of the counter movement comes from the direction. An
 * {@code offerCode} unknown to the referential is stored all the same — the
 * counter is a comptable truth and the offer may have been deleted since.
 * <p>
 * A record is written once and never updated.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "consumption_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_record_ticket_offer",
                columnNames = {"store_code", "ticket_id", "direction", "offer_code"}),
        indexes = {
                @Index(name = "idx_record_ticket", columnList = "store_code, ticket_id, direction"),
                @Index(name = "idx_record_offer", columnList = "offer_code"),
                @Index(name = "idx_record_finalized", columnList = "finalized_at")
        })
@Cacheable
public class ConsumptionRecord extends BaseEntity {

    /**
     * Direction of a declaration that adds to the counters.
     */
    public static final String DIRECTION_SALE = "SALE";

    /**
     * Direction of a declaration that subtracts from the counters.
     */
    public static final String DIRECTION_CANCELLATION = "CANCELLATION";

    /**
     * The cashed ticket identifier, part of the idempotence triplet.
     */
    @Column(name = "ticket_id", nullable = false, length = 80)
    @NotBlank(message = "Ticket id is mandatory")
    public String ticketId;

    /**
     * The point-of-sale code the ticket was cashed at, part of the idempotence triplet.
     */
    @Column(name = "store_code", nullable = false, length = 20)
    @NotBlank(message = "Store code is mandatory")
    public String storeCode;

    /**
     * {@code SALE} or {@code CANCELLATION}, part of the idempotence triplet.
     */
    @Column(name = "direction", nullable = false, length = 20)
    @NotBlank(message = "Direction is mandatory")
    public String direction;

    /**
     * The instant the ticket was finalized at the till, copied out of the declaration.
     */
    @Column(name = "finalized_at")
    public LocalDateTime finalizedAt;

    /**
     * The offer code the advantage was granted under.
     */
    @Column(name = "offer_code", nullable = false, length = 50)
    @NotBlank(message = "Offer code is mandatory")
    public String offerCode;

    /**
     * The absolute value of the advantage granted on this line, in euros, scale 2.
     */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    public BigDecimal amount = BigDecimal.ZERO;

    /**
     * Who funds the promotion (spec-exploitation §3): {@code STORE}, {@code BANNER} or
     * {@code SPLIT_50_50}, copied verbatim from the declaration and never interpreted. Null when the
     * declaration carried no funding — the export then leaves the {@code FUNDING} column empty.
     */
    @Column(name = "funding", length = 20)
    public String funding;

    /**
     * The optional per-article breakdown of this advantage (spec §4): the amount financed on each
     * EAN, as the till already knows it. Empty when the declaration carried no detail — the export
     * then emits a single line with a blank EAN and the whole {@link #amount}.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "consumption_record_lines",
            joinColumns = @JoinColumn(name = "record_id"))
    public List<ConsumptionLine> lines = new ArrayList<>();

    /**
     * Default constructor for JPA.
     */
    public ConsumptionRecord() {
    }

    /**
     * Lists the records to export, bounded on {@link #finalizedAt} and optionally narrowed by store,
     * offer and a set of offer codes (the members of a budget group), ordered deterministically.
     * <p>
     * Records with a null {@code finalizedAt} are excluded — the export bounds are on the
     * finalization instant, which such records do not carry.
     *
     * @param from             the inclusive lower bound on {@code finalizedAt}.
     * @param to               the inclusive upper bound on {@code finalizedAt}.
     * @param storeCode        a store code to filter on, or null for every store.
     * @param offerCode        an offer code to filter on, or null for every offer.
     * @param offerCodesFilter the offer codes to keep (a budget group's members), or null to skip
     *                         this filter; an empty collection yields no rows.
     * @return the matching records, ordered by finalization instant then ticket then offer.
     */
    public static List<ConsumptionRecord> listForExport(LocalDateTime from, LocalDateTime to,
                                                        String storeCode, String offerCode,
                                                        Collection<String> offerCodesFilter) {
        StringBuilder query = new StringBuilder("finalizedAt >= ?1 and finalizedAt <= ?2");
        List<Object> params = new ArrayList<>();
        params.add(from);
        params.add(to);
        if (storeCode != null) {
            query.append(" and storeCode = ?").append(params.size() + 1);
            params.add(storeCode);
        }
        if (offerCode != null) {
            query.append(" and offerCode = ?").append(params.size() + 1);
            params.add(offerCode);
        }
        if (offerCodesFilter != null) {
            if (offerCodesFilter.isEmpty()) {
                return List.of();
            }
            query.append(" and offerCode in ?").append(params.size() + 1);
            params.add(offerCodesFilter);
        }
        query.append(" order by finalizedAt, ticketId, offerCode");
        return list(query.toString(), params.toArray());
    }

    /**
     * Tells whether a declaration triplet was already recorded — the idempotence witness.
     *
     * @param storeCode the point-of-sale code
     * @param ticketId  the ticket identifier
     * @param direction {@code SALE} or {@code CANCELLATION}
     * @return true when at least one row already carries that triplet
     */
    public static boolean existsForTicket(String storeCode, String ticketId, String direction) {
        return count("storeCode = ?1 and ticketId = ?2 and direction = ?3",
                storeCode, ticketId, direction) > 0;
    }

    /**
     * Calculates a checksum based on the record's identity and value.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ticketId, storeCode, direction, offerCode, amount, finalizedAt, funding);
    }

    /**
     * One article's share of an advantage (spec §4): the amount financed on a given EAN.
     * <p>
     * Stored as a positive absolute value like the record's own amount; the export applies the
     * direction's sign. Fields are public to comply with Quarkus/Panache conventions.
     */
    @Embeddable
    public static class ConsumptionLine {

        /**
         * The EAN the amount was financed on.
         */
        @Column(name = "ean", length = 13)
        public String ean;

        /**
         * The absolute amount financed on that EAN, in euros, scale 2.
         */
        @Column(name = "line_amount", precision = 19, scale = 2)
        public BigDecimal amount;

        /**
         * Default constructor for JPA.
         */
        public ConsumptionLine() {
        }

        /**
         * Builds a per-article line.
         *
         * @param ean    the EAN.
         * @param amount the absolute amount financed on that EAN, scale 2.
         */
        public ConsumptionLine(String ean, BigDecimal amount) {
            this.ean = ean;
            this.amount = amount;
        }
    }
}
