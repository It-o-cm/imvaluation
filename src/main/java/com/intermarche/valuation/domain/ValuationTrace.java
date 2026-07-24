package com.intermarche.valuation.domain;

import io.quarkus.panache.common.Page;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Record of a valuation request and its outcome.
 * <p>
 * The engine answers and forgets; this entity keeps the exchange so a disputed basket can
 * be inspected afterwards, and replayed against the current configuration to see whether
 * the result still differs.
 * <p>
 * Both payloads are stored verbatim rather than parsed into columns: the point is to keep
 * exactly what crossed the wire, including anything a later version would interpret
 * differently.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "valuation_traces",
        indexes = {
                @Index(name = "idx_trace_created", columnList = "created_at"),
                @Index(name = "idx_trace_store", columnList = "store_code")
        }
)
public class ValuationTrace extends BaseEntity {

    /**
     * Outcome of a valuation that ran to completion.
     */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * Outcome of a request rejected before the engine ran, typically by schema validation.
     */
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * Outcome of a valuation interrupted by an error.
     */
    public static final String STATUS_FAILED = "FAILED";

    // --------------------------------------------------
    // Context
    // --------------------------------------------------

    /**
     * Code of the store the basket was valued for, copied out of the request for filtering.
     */
    @Column(name = "store_code", length = 20)
    public String storeCode;

    /**
     * Code of the customer owning the basket, copied out of the request for filtering.
     */
    @Column(name = "customer_code", length = 60)
    public String customerCode;

    /**
     * Number of lines in the submitted basket.
     */
    @Column(name = "item_count")
    public Integer itemCount;

    // --------------------------------------------------
    // Outcome
    // --------------------------------------------------

    /**
     * Whether the valuation succeeded, was rejected, or failed.
     */
    @Column(name = "status", nullable = false, length = 20)
    public String status;

    /**
     * HTTP status code returned to the caller.
     */
    @Column(name = "http_status")
    public Integer httpStatus;

    /**
     * Wall-clock duration of the valuation, in milliseconds.
     */
    @Column(name = "duration_ms")
    public Long durationMs;

    /**
     * Total price including tax, when the valuation produced one.
     */
    @Column(name = "total_incl_tax", precision = 19, scale = 2)
    public java.math.BigDecimal totalIncludingTax;

    /**
     * Error message when the valuation did not succeed.
     */
    @Column(name = "error_message", length = 2000)
    public String errorMessage;

    // --------------------------------------------------
    // Payloads
    // --------------------------------------------------

    /**
     * The submitted basket, exactly as received.
     */
    @Lob
    @Column(name = "request_payload")
    public String requestPayload;

    /**
     * The evaluation returned to the caller, exactly as serialized.
     */
    @Lob
    @Column(name = "response_payload")
    public String responsePayload;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Deletes every trace created before the given instant.
     *
     * @param threshold The cut-off date; traces older than this are removed.
     * @return The number of deleted traces.
     */
    public static long deleteOlderThan(LocalDateTime threshold) {
        return delete("createdAt < ?1", threshold);
    }

    /**
     * Returns the most recent traces, newest first.
     *
     * @param limit The maximum number of traces to return.
     * @return The latest traces.
     */
    public static List<ValuationTrace> findLatest(int limit) {
        return find("order by createdAt desc").page(Page.ofSize(limit)).list();
    }

    // --------------------------------------------------
    // Display
    // --------------------------------------------------

    /**
     * Indicates whether the valuation succeeded.
     *
     * @return {@code true} when the status is {@link #STATUS_SUCCESS}.
     */
    public boolean isSuccess() {
        return STATUS_SUCCESS.equals(status);
    }

    /**
     * Returns a short description of the basket for the list screen.
     *
     * @return The store code and the number of lines.
     */
    public String getSummary() {
        String store = storeCode == null ? "unknown store" : storeCode;
        int count = itemCount == null ? 0 : itemCount;
        return store + " \u00b7 " + count + (count == 1 ? " line" : " lines");
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the traced exchange.
     * <p>
     * A trace is never updated after creation, so this only ever runs once.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(storeCode, customerCode, status, httpStatus, requestPayload);
    }
}
