package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * Records valuation exchanges and removes the ones that have expired.
 * <p>
 * Recording never interferes with the valuation itself: a failure to serialize or persist
 * a trace is logged and swallowed, because losing an audit record is preferable to
 * failing a request that otherwise succeeded.
 * <p>
 * Whether traces are written, and how long they survive, is read from
 * {@link ValuationTraceConfig} on each call, so a change made in the administration
 * screens takes effect immediately.
 */
@ApplicationScoped
public class ValuationTraceService {

    private static final Logger LOGGER = Logger.getLogger(ValuationTraceService.class);

    /**
     * Mapper used to serialize the evaluation returned to the caller.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Records a completed valuation.
     *
     * @param requestPayload The submitted basket, as received.
     * @param basket         The parsed basket, used to extract the context columns.
     * @param evaluation     The evaluation produced by the engine, may be null on failure.
     * @param httpStatus     The status code returned to the caller.
     * @param status         One of the {@code STATUS_*} constants of {@link ValuationTrace}.
     * @param errorMessage   The failure description, or null on success.
     * @param durationMs     Wall-clock duration of the valuation.
     */
    @Transactional
    public void record(String requestPayload, Basket basket, BasketEvaluation evaluation,
                       int httpStatus, String status, String errorMessage, long durationMs) {
        try {
            if (!ValuationTraceConfig.current().enabled) {
                return;
            }
            ValuationTrace trace = new ValuationTrace();
            trace.requestPayload = requestPayload;
            trace.httpStatus = httpStatus;
            trace.status = status;
            trace.errorMessage = truncate(errorMessage);
            trace.durationMs = durationMs;
            if (basket != null) {
                trace.storeCode = basket.storeCode;
                trace.customerCode = basket.customerCode;
                trace.itemCount = basket.items == null ? 0 : basket.items.size();
            }
            if (evaluation != null) {
                trace.responsePayload = MAPPER.writeValueAsString(evaluation);
                if (evaluation.getTotalPrice() != null) {
                    trace.totalIncludingTax = evaluation.getTotalPrice().amountIncludingTax;
                }
            }
            trace.persist();
        } catch (Exception e) {
            // A missing trace must never turn a successful valuation into a failure.
            LOGGER.error("Could not record the valuation trace", e);
        }
    }

    /**
     * Deletes the traces older than the configured retention.
     * <p>
     * Runs every hour rather than daily: with a retention counted in days, an hourly pass
     * keeps the table close to its target size without waiting for a fixed nightly slot.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void purgeExpired() {
        try {
            ValuationTraceConfig config = ValuationTraceConfig.current();
            LocalDateTime threshold = LocalDateTime.now().minusDays(config.retentionDays);
            long deleted = ValuationTrace.deleteOlderThan(threshold);
            if (deleted > 0) {
                LOGGER.info("Purged " + deleted + " valuation trace(s) older than " + threshold);
            }
        } catch (Exception e) {
            LOGGER.error("Valuation trace purge failed", e);
        }
    }

    /**
     * Deletes every recorded trace.
     * <p>
     * Offered from the administration screens, for when the table needs clearing without
     * waiting for the retention to elapse.
     *
     * @return The number of deleted traces.
     */
    @Transactional
    public long purgeAll() {
        long deleted = ValuationTrace.deleteAll();
        LOGGER.info("Purged every valuation trace (" + deleted + ")");
        return deleted;
    }

    /**
     * Shortens a message so it fits the column holding it.
     *
     * @param message The raw message, may be null.
     * @return The message, truncated when needed.
     */
    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 1997) + "...";
    }
}
