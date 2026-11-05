package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.ConsumptionCounter;
import com.intermarche.valuation.domain.ConsumptionRecord;
import com.intermarche.valuation.domain.Offer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code funding} passthrough on the consumption path and the financial exports
 * (spec-exploitation §3, §5.3): the declared funding is persisted verbatim on the
 * {@link ConsumptionRecord}, surfaces in the {@code FUNDING} column of the detail export, drives the
 * {@code groupBy=funding} aggregate, and leaves an empty column when absent.
 */
@QuarkusTest
public class ConsumptionFundingTest {

    /**
     * The ingestion service used to seed real records.
     */
    @Inject
    ConsumptionService service;

    /**
     * A finalization instant inside the export window used by the tests.
     */
    private static final String INSIDE = "2026-02-10T10:00:45";

    /**
     * Clears the consumption rows and this test's offers before each test.
     */
    @BeforeEach
    void clean() {
        wipe();
    }

    /**
     * Clears the consumption rows and this test's offers after the class.
     */
    @AfterAll
    static void afterAll() {
        wipe();
    }

    /**
     * Deletes the consumption records, counters and the offers this test seeds.
     */
    private static void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            ConsumptionRecord.deleteAll();
            ConsumptionCounter.deleteAll();
            Offer.delete("code like ?1", "CF_%");
        });
    }

    /**
     * Ingests one declaration through the service (committing it), with an optional funding.
     *
     * @param ticket    the ticket id.
     * @param store     the store code.
     * @param offerCode the offer code.
     * @param amount    the advantage amount.
     * @param funding   the promotion funding, or null for none.
     */
    private void declare(String ticket, String store, String offerCode, String amount, String funding) {
        ConsumptionService.Declaration declaration = new ConsumptionService.Declaration();
        declaration.ticketId = ticket;
        declaration.storeCode = store;
        declaration.finalizedAt = INSIDE;
        declaration.direction = "SALE";
        ConsumptionService.Advantage advantage = new ConsumptionService.Advantage();
        advantage.offerCode = offerCode;
        advantage.amount = new BigDecimal(amount);
        advantage.funding = funding;
        declaration.advantages = new ArrayList<>(List.of(advantage));
        service.record(declaration);
    }

    /**
     * GETs the export body for a query string.
     *
     * @param query the query string (without leading '?').
     * @return the response body as a string.
     */
    private String export(String query) {
        return given().when().get("/consumptions/export?" + query)
                .then().statusCode(200).extract().asString();
    }

    /**
     * §5.3: the declared funding is persisted verbatim on the record.
     */
    @Test
    void testFundingPersistedOnRecord() {
        declare("CF_T1", "S1", "CF_OFF", "5.00", "BANNER");
        String[] funding = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ConsumptionRecord record = ConsumptionRecord
                    .find("ticketId = ?1 and offerCode = ?2", "CF_T1", "CF_OFF").firstResult();
            funding[0] = record == null ? null : record.funding;
        });
        assertEquals("BANNER", funding[0]);
    }

    /**
     * §5.3: the detail CSV carries a {@code FUNDING} column populated with the declared value.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailCsvCarriesFundingColumn() {
        declare("CF_T1", "S1", "CF_OFF", "5.00", "STORE");
        String body = export("from=2026-02-01&to=2026-02-28&format=csv");
        assertTrue(body.startsWith(
                "TICKET_ID|STORE_CODE|FINALIZED_AT|DIRECTION|OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|EAN|AMOUNT|FUNDING"),
                "header must carry the FUNDING column: " + body);
        assertTrue(body.contains("CF_T1|S1|2026-02-10T10:00:45|SALE|CF_OFF||||5.00|STORE"),
                "the detail row must end with the funding: " + body);
    }

    /**
     * §4 case 3: a declaration without funding leaves the FUNDING column empty (bit-for-bit but for
     * the trailing empty cell).
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailCsvEmptyFundingWhenAbsent() {
        declare("CF_T2", "S1", "CF_OFF", "7.00", null);
        String body = export("from=2026-02-01&to=2026-02-28&format=csv");
        assertTrue(body.contains("CF_T2|S1|2026-02-10T10:00:45|SALE|CF_OFF||||7.00|\n")
                        || body.trim().endsWith("CF_T2|S1|2026-02-10T10:00:45|SALE|CF_OFF||||7.00|"),
                "the funding cell must be empty when none was declared: " + body);
    }

    /**
     * §5.3 / §4 case 3: {@code groupBy=funding} sums the amounts per funding value.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testAggregateByFundingSumsCorrectly() {
        declare("CF_T1", "S1", "CF_OFF", "5.00", "BANNER");
        declare("CF_T2", "S1", "CF_OFF", "3.00", "BANNER");
        declare("CF_T3", "S1", "CF_OFF", "8.00", "STORE");
        String body = export("from=2026-02-01&to=2026-02-28&format=csv&groupBy=funding");
        assertTrue(body.startsWith("FUNDING|AMOUNT|TICKET_COUNT"), "aggregate header must be by funding: " + body);
        assertTrue(body.contains("BANNER|8.00|2"), "BANNER must sum 5.00 + 3.00 over 2 tickets: " + body);
        assertTrue(body.contains("STORE|8.00|1"), "STORE must sum 8.00 over 1 ticket: " + body);
    }

    /**
     * §5.3: the funding also surfaces in the JSON detail export.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailJsonCarriesFunding() {
        declare("CF_T1", "S1", "CF_OFF", "5.00", "SPLIT_50_50");
        String body = given().accept("application/json")
                .when().get("/consumptions/export?from=2026-02-01&to=2026-02-28&format=json")
                .then().statusCode(200).extract().asString();
        assertTrue(body.contains("\"funding\":\"SPLIT_50_50\""), "the JSON detail must carry funding: " + body);
    }

    /**
     * Confirms a null declared funding stays null on the record (no coercion to empty string).
     */
    @Test
    void testAbsentFundingStaysNull() {
        declare("CF_T2", "S1", "CF_OFF", "7.00", null);
        String[] funding = new String[]{"sentinel"};
        QuarkusTransaction.requiringNew().run(() -> {
            ConsumptionRecord record = ConsumptionRecord
                    .find("ticketId = ?1 and offerCode = ?2", "CF_T2", "CF_OFF").firstResult();
            funding[0] = record == null ? "no-record" : record.funding;
        });
        assertNull(funding[0]);
    }
}
