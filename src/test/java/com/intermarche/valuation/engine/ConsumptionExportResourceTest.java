package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.BudgetGroup;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Endpoint tests for {@link ConsumptionExportResource} (spec §6), driven over the real HTTP stack.
 * <p>
 * Records are seeded through {@link ConsumptionService#record} (which commits), so the exported
 * data is real ingested consumption; offers are seeded in their own committed transaction so the
 * export can resolve their type and budget group — and one case leaves the offer absent to prove the
 * deleted-offer columns come out empty. Authentication uses {@code @TestSecurity}.
 */
@QuarkusTest
public class ConsumptionExportResourceTest {

    /**
     * The ingestion service used to seed real records.
     */
    @Inject
    ConsumptionService service;

    /**
     * A finalization instant inside the export window used by the tests.
     */
    private static final String INSIDE = "2026-01-15T10:30:45";

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
     * Deletes the consumption records, counters and the offers/groups this test seeds.
     */
    private static void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            ConsumptionRecord.deleteAll();
            ConsumptionCounter.deleteAll();
            Offer.delete("code like ?1", "CE_%");
            BudgetGroup.delete("code like ?1", "CEG_%");
        });
    }

    /**
     * Seeds an offer with a type and an optional budget group, in its own committed transaction.
     *
     * @param code   the offer code.
     * @param type   the offer type.
     * @param bgCode the budget group code, or null for none.
     */
    private void seedOffer(String code, String type, String bgCode) {
        QuarkusTransaction.requiringNew().run(() -> {
            BudgetGroup group = null;
            if (bgCode != null) {
                group = new BudgetGroup();
                group.code = bgCode;
                group.persist();
            }
            Offer offer = new Offer();
            offer.code = code;
            offer.type = type;
            offer.specification = "{}";
            offer.budgetGroup = group;
            offer.persist();
        });
    }

    /**
     * Ingests one declaration through the service (committing it).
     *
     * @param ticket      the ticket id.
     * @param store       the store code.
     * @param finalizedAt the finalization instant (ISO local date-time).
     * @param direction   {@code SALE} or {@code CANCELLATION}.
     * @param offerCode   the offer code.
     * @param amount      the advantage amount.
     * @param lines       the optional per-article breakdown.
     */
    private void declare(String ticket, String store, String finalizedAt, String direction,
                         String offerCode, String amount, ConsumptionService.Line... lines) {
        ConsumptionService.Declaration declaration = new ConsumptionService.Declaration();
        declaration.ticketId = ticket;
        declaration.storeCode = store;
        declaration.finalizedAt = finalizedAt;
        declaration.direction = direction;
        ConsumptionService.Advantage advantage = new ConsumptionService.Advantage();
        advantage.offerCode = offerCode;
        advantage.amount = new BigDecimal(amount);
        if (lines.length > 0) {
            advantage.lines = new ArrayList<>(List.of(lines));
        }
        declaration.advantages = new ArrayList<>(List.of(advantage));
        service.record(declaration);
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
     * §6.1: the detail CSV honours the bounds and matches the header and a data row column by column;
     * amounts are plain, scale 2. A record outside the window is excluded.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailCsv_columnsAndBounds() {
        seedOffer("CE_OFF", "CARDPROMO", null);
        declare("T1", "S1", INSIDE, "SALE", "CE_OFF", "5.00");
        declare("TOUT", "S1", "2025-12-01T09:00:00", "SALE", "CE_OFF", "9.99");
        String body = export("from=2026-01-01&to=2026-01-31&format=csv");
        assertTrue(body.startsWith(
                "TICKET_ID|STORE_CODE|FINALIZED_AT|DIRECTION|OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|EAN|AMOUNT"),
                "header must match column by column: " + body);
        assertTrue(body.contains("T1|S1|2026-01-15T10:30:45|SALE|CE_OFF|CARDPROMO|||5.00"),
                "the detail row must match column by column: " + body);
        assertFalse(body.contains("TOUT"), "a record outside the window must be excluded: " + body);
    }

    /**
     * §6.2: {@code groupBy=offer} sums the signed amounts (a cancellation nets) per offer.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testAggregateByOffer_cancellationNetted() {
        seedOffer("CE_A", "CARDPROMO", null);
        declare("T1", "S1", INSIDE, "SALE", "CE_A", "10.00");
        declare("T1", "S1", INSIDE, "CANCELLATION", "CE_A", "4.00");
        String body = export("from=2026-01-01&to=2026-01-31&format=csv&groupBy=offer");
        assertTrue(body.startsWith("OFFER_CODE|OFFER_TYPE|BUDGET_GROUP_CODE|AMOUNT|TICKET_COUNT"),
                "aggregate header: " + body);
        assertTrue(body.contains("CE_A|CARDPROMO||6.00|1"), "10.00 sale minus 4.00 cancel = 6.00: " + body);
    }

    /**
     * §6.2 (detail cancellations): a CANCELLATION comes out as a negative detail line, never netted
     * in the detail.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailCsv_cancellationNegativeLine() {
        seedOffer("CE_A", "CARDPROMO", null);
        declare("T1", "S1", INSIDE, "CANCELLATION", "CE_A", "4.00");
        String body = export("from=2026-01-01&to=2026-01-31&format=csv");
        assertTrue(body.contains("T1|S1|2026-01-15T10:30:45|CANCELLATION|CE_A|CARDPROMO|||-4.00"),
                "a cancellation is a negative detail line: " + body);
    }

    /**
     * §6.3: an offer deleted since the consumption exports with empty type and budget group, the line
     * still present.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailCsv_deletedOfferEmptyColumns() {
        declare("T1", "S1", INSIDE, "SALE", "CE_GONE", "5.00");
        String body = export("from=2026-01-01&to=2026-01-31&format=csv");
        assertTrue(body.contains("T1|S1|2026-01-15T10:30:45|SALE|CE_GONE||||5.00"),
                "a deleted offer keeps the line with empty type/group: " + body);
    }

    /**
     * §6.4: a declaration carrying {@code lines[]} exports one detail row per EAN; without it, a
     * single blank-EAN row carries the whole amount, and the totals match.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailCsv_perArticleLines() {
        seedOffer("CE_LIN", "CARDPROMO", null);
        seedOffer("CE_NOLIN", "CARDPROMO", null);
        declare("T1", "S1", INSIDE, "SALE", "CE_LIN", "5.00", lineOf("111", "2.00"), lineOf("222", "3.00"));
        declare("T2", "S1", INSIDE, "SALE", "CE_NOLIN", "5.00");
        String body = export("from=2026-01-01&to=2026-01-31&format=csv");
        assertTrue(body.contains("|CE_LIN|CARDPROMO||111|2.00"), "the first article line: " + body);
        assertTrue(body.contains("|CE_LIN|CARDPROMO||222|3.00"), "the second article line: " + body);
        assertTrue(body.contains("T2|S1|2026-01-15T10:30:45|SALE|CE_NOLIN|CARDPROMO|||5.00"),
                "without lines the EAN is empty and the total is the whole amount: " + body);
    }

    /**
     * §2: the JSON export carries the same content for third-party systems.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testDetailJson_sameContent() {
        seedOffer("CE_OFF", "CARDPROMO", "CEG_1");
        declare("T1", "S1", INSIDE, "SALE", "CE_OFF", "5.00");
        String body = export("from=2026-01-01&to=2026-01-31&format=json");
        assertTrue(body.contains("\"offerCode\":\"CE_OFF\""), "json carries the offer code: " + body);
        assertTrue(body.contains("\"budgetGroupCode\":\"CEG_1\""), "json resolves the budget group: " + body);
        assertTrue(body.contains("\"amount\":5.00"), "json carries the plain scale-2 amount: " + body);
    }

    /**
     * §2: the budget group filter keeps only the offers of that group.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testBudgetGroupFilter() {
        seedOffer("CE_IN", "CARDPROMO", "CEG_KEEP");
        seedOffer("CE_OUT", "CARDPROMO", "CEG_OTHER");
        declare("T1", "S1", INSIDE, "SALE", "CE_IN", "5.00");
        declare("T2", "S1", INSIDE, "SALE", "CE_OUT", "7.00");
        String body = export("from=2026-01-01&to=2026-01-31&format=csv&budgetGroupCode=CEG_KEEP");
        assertTrue(body.contains("CE_IN"), "the group member is kept: " + body);
        assertFalse(body.contains("CE_OUT"), "a non-member is excluded: " + body);
    }

    /**
     * §6.1: the mandatory bounds are enforced — a missing {@code from}/{@code to} is a 400.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testMissingBoundsRejected() {
        given().when().get("/consumptions/export?to=2026-01-31").then().statusCode(400);
        given().when().get("/consumptions/export?from=2026-01-01").then().statusCode(400);
    }

    /**
     * §6.5: a VIEWER is refused the endpoint, a MANAGER is allowed.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testRbac_viewerForbidden() {
        given().when().get("/consumptions/export?from=2026-01-01&to=2026-01-31").then().statusCode(403);
    }
}
