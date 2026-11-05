package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.ConsumptionRecord;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AnomalyService} (spec-exploitation §2, §5.2): the atypical-ticket detection over an
 * absolute and a relative threshold, and the atypical point-of-sale detection beyond N standard
 * deviations, both computed from {@link ConsumptionRecord} only. A {@code @TestTransaction} seeds the
 * records; the service reads them in the same unit of work.
 */
@QuarkusTest
@TestTransaction
public class AnomalyServiceTest {

    /**
     * The service under test.
     */
    @Inject
    AnomalyService service;

    /**
     * The window lower bound used by the tests.
     */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 3, 1, 0, 0);

    /**
     * The window upper bound used by the tests.
     */
    private static final LocalDateTime TO = LocalDateTime.of(2026, 3, 31, 23, 59, 59);

    /**
     * The day every seeded record is finalized on (inside the window).
     */
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 3, 10, 10, 0, 0);

    /**
     * Persists one SALE consumption record.
     *
     * @param ticket    the ticket id.
     * @param store     the store code.
     * @param offer     the offer code.
     * @param amount    the generosity amount.
     */
    private void record(String ticket, String store, String offer, String amount) {
        ConsumptionRecord record = new ConsumptionRecord();
        record.ticketId = ticket;
        record.storeCode = store;
        record.direction = ConsumptionRecord.DIRECTION_SALE;
        record.offerCode = offer;
        record.amount = new BigDecimal(amount);
        record.finalizedAt = DAY;
        record.persist();
    }

    /**
     * §5.2: a ticket whose generosity crosses the absolute threshold is returned, at the head, and the
     * ordinary tickets are not.
     */
    @Test
    void testAtypicalTickets_absoluteThreshold() {
        record("T1", "S1", "O", "5.00");
        record("T2", "S1", "O", "5.00");
        record("T3", "S1", "O", "50.00");
        List<AnomalyService.TicketAnomaly> anomalies =
                service.atypicalTickets(FROM, TO, new BigDecimal("20"), null);
        assertEquals(1, anomalies.size());
        assertEquals("T3", anomalies.get(0).ticketId);
        assertEquals(0, new BigDecimal("50.00").compareTo(anomalies.get(0).generosity));
        assertTrue(anomalies.get(0).overAbsolute);
    }

    /**
     * §5.2: a ticket whose generosity crosses the relative threshold (a multiple of the average) is
     * detected — average of 5/5/50 is 20, so a ×2 threshold flags the 50 ticket.
     */
    @Test
    void testAtypicalTickets_relativeThreshold() {
        record("T1", "S1", "O", "5.00");
        record("T2", "S1", "O", "5.00");
        record("T3", "S1", "O", "50.00");
        List<AnomalyService.TicketAnomaly> anomalies =
                service.atypicalTickets(FROM, TO, null, new BigDecimal("2"));
        assertEquals(1, anomalies.size());
        assertEquals("T3", anomalies.get(0).ticketId);
        assertTrue(anomalies.get(0).overRelative);
    }

    /**
     * §5.2: changing the thresholds recomputes the result — a high absolute threshold flags nothing, a
     * low one flags every ticket.
     */
    @Test
    void testThresholdsRecomputed() {
        record("T1", "S1", "O", "5.00");
        record("T2", "S1", "O", "5.00");
        record("T3", "S1", "O", "50.00");
        assertTrue(service.atypicalTickets(FROM, TO, new BigDecimal("100"), null).isEmpty());
        assertEquals(3, service.atypicalTickets(FROM, TO, new BigDecimal("4"), null).size());
    }

    /**
     * §5.2: a point of sale deviating from its peers on the same offer and day beyond N standard
     * deviations is flagged and lands at the head of the table; the conforming stores are not flagged.
     */
    @Test
    void testAtypicalPdvOffers_deviantStoreFlaggedFirst() {
        record("T1", "S1", "O", "10.00");
        record("T2", "S2", "O", "10.00");
        record("T3", "S3", "O", "10.00");
        record("T4", "S4", "O", "10.00");
        record("T5", "S5", "O", "10.00");
        record("T6", "S6", "O", "100.00");
        List<AnomalyService.PdvOfferAnomaly> cells = service.atypicalPdvOffers(FROM, TO, new BigDecimal("2"));
        assertEquals("S6", cells.get(0).storeCode);
        assertTrue(cells.get(0).flagged);
        for (int i = 1; i < cells.size(); i++) {
            assertFalse(cells.get(i).flagged, "store " + cells.get(i).storeCode + " must not be flagged");
        }
    }

    /**
     * §5.2: raising the standard-deviation multiplier flags nobody — the same deviant store is no
     * longer atypical, proving the sigma parameter recomputes the detection.
     */
    @Test
    void testAtypicalPdvOffers_sigmaRecomputed() {
        record("T1", "S1", "O", "10.00");
        record("T2", "S2", "O", "10.00");
        record("T3", "S3", "O", "10.00");
        record("T4", "S4", "O", "10.00");
        record("T5", "S5", "O", "10.00");
        record("T6", "S6", "O", "100.00");
        List<AnomalyService.PdvOfferAnomaly> cells = service.atypicalPdvOffers(FROM, TO, new BigDecimal("100"));
        for (AnomalyService.PdvOfferAnomaly cell : cells) {
            assertFalse(cell.flagged, "no cell may be flagged at sigma 100: " + cell.storeCode);
        }
    }
}
