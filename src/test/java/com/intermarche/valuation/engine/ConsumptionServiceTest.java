package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.ConsumptionRecord;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConsumptionService} ingestion, focusing on the additive per-article
 * {@code lines[]} (spec §4), the per-offer aggregation, the direction and the idempotence.
 * <p>
 * Runs under {@code @TestTransaction}: the service's own transaction joins the test's, so the writes
 * are visible to the assertions and rolled back afterwards.
 */
@QuarkusTest
@TestTransaction
public class ConsumptionServiceTest {

    /**
     * The service under test.
     */
    @Inject
    ConsumptionService service;

    /**
     * Builds a declaration with a single advantage.
     *
     * @param ticket    the ticket id.
     * @param direction {@code SALE} or {@code CANCELLATION}.
     * @param offerCode the offer code.
     * @param amount    the advantage amount.
     * @param lines     the optional per-article breakdown.
     * @return the declaration.
     */
    private ConsumptionService.Declaration declaration(String ticket, String direction,
                                                       String offerCode, String amount,
                                                       ConsumptionService.Line... lines) {
        ConsumptionService.Declaration declaration = new ConsumptionService.Declaration();
        declaration.ticketId = ticket;
        declaration.storeCode = "CS_STORE";
        declaration.finalizedAt = "2026-02-01T12:00:00";
        declaration.direction = direction;
        ConsumptionService.Advantage advantage = new ConsumptionService.Advantage();
        advantage.offerCode = offerCode;
        advantage.amount = new BigDecimal(amount);
        if (lines.length > 0) {
            advantage.lines = new ArrayList<>(List.of(lines));
        }
        declaration.advantages = new ArrayList<>(List.of(advantage));
        return declaration;
    }

    /**
     * Builds a per-article line.
     *
     * @param ean    the EAN.
     * @param amount the amount.
     * @return the line.
     */
    private ConsumptionService.Line lineOf(String ean, String amount) {
        ConsumptionService.Line line = new ConsumptionService.Line();
        line.ean = ean;
        line.amount = new BigDecimal(amount);
        return line;
    }

    /**
     * Finds the single record of an offer code.
     *
     * @param offerCode the offer code.
     * @return the record.
     */
    private ConsumptionRecord recordOf(String offerCode) {
        return ConsumptionRecord.find("offerCode", offerCode).firstResult();
    }

    /**
     * Tests that the per-article breakdown is persisted on the record, and its total is the amount.
     */
    @Test
    void testRecordPersistsLines() {
        service.record(declaration("CS_T1", "SALE", "CS_LIN", "5.00",
                lineOf("111", "2.00"), lineOf("222", "3.00")));
        ConsumptionRecord record = recordOf("CS_LIN");
        assertEquals(new BigDecimal("5.00"), record.amount);
        assertEquals(2, record.lines.size());
        assertTrue(record.lines.stream().anyMatch(l -> "111".equals(l.ean)
                && new BigDecimal("2.00").compareTo(l.amount) == 0), "the 111 line is persisted");
        assertTrue(record.lines.stream().anyMatch(l -> "222".equals(l.ean)
                && new BigDecimal("3.00").compareTo(l.amount) == 0), "the 222 line is persisted");
    }

    /**
     * Tests that a declaration without lines persists an empty breakdown (backward-compatible).
     */
    @Test
    void testRecordWithoutLinesIsEmpty() {
        service.record(declaration("CS_T2", "SALE", "CS_NOLIN", "7.00"));
        ConsumptionRecord record = recordOf("CS_NOLIN");
        assertEquals(new BigDecimal("7.00"), record.amount);
        assertTrue(record.lines.isEmpty(), "no breakdown was declared");
    }

    /**
     * Tests that a CANCELLATION stores the direction and keeps the amount positive (absolute).
     */
    @Test
    void testCancellationStoresAbsoluteAmount() {
        service.record(declaration("CS_T3", "CANCELLATION", "CS_CANCEL", "4.00"));
        ConsumptionRecord record = recordOf("CS_CANCEL");
        assertEquals(ConsumptionRecord.DIRECTION_CANCELLATION, record.direction);
        assertEquals(new BigDecimal("4.00"), record.amount);
    }

    /**
     * Tests that a replayed declaration triplet is idempotent — the second call is a duplicate and
     * writes nothing new.
     */
    @Test
    void testIdempotentReplay() {
        ConsumptionService.Outcome first = service.record(declaration("CS_T4", "SALE", "CS_DUP", "5.00"));
        ConsumptionService.Outcome second = service.record(declaration("CS_T4", "SALE", "CS_DUP", "5.00"));
        assertTrue(!first.duplicate, "the first declaration is not a duplicate");
        assertTrue(second.duplicate, "the replay is a duplicate");
        assertEquals(1, ConsumptionRecord.count("offerCode", "CS_DUP"));
    }
}
