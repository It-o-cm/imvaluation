package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.ConsumptionCounter;
import com.intermarche.valuation.domain.ConsumptionRecord;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.engine.ConsumptionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Endpoint tests for the {@link ConsumptionUiResource} BO screen (spec §3, §6.6): the aggregate
 * shown matches the export, and a VIEWER is refused.
 */
@QuarkusTest
public class ConsumptionUiResourceTest {

    /**
     * The ingestion service used to seed real records.
     */
    @Inject
    ConsumptionService service;

    /**
     * A finalization instant inside the screen window.
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
     * Deletes the consumption records, counters and this test's offers.
     */
    private static void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            ConsumptionRecord.deleteAll();
            ConsumptionCounter.deleteAll();
            Offer.delete("code like ?1", "CU_%");
        });
    }

    /**
     * Seeds an offer with a type, in its own committed transaction.
     *
     * @param code the offer code.
     * @param type the offer type.
     */
    private void seedOffer(String code, String type) {
        QuarkusTransaction.requiringNew().run(() -> {
            Offer offer = new Offer();
            offer.code = code;
            offer.type = type;
            offer.specification = "{}";
            offer.persist();
        });
    }

    /**
     * Ingests one declaration through the service (committing it).
     *
     * @param ticket    the ticket id.
     * @param direction {@code SALE} or {@code CANCELLATION}.
     * @param offerCode the offer code.
     * @param amount    the advantage amount.
     */
    private void declare(String ticket, String direction, String offerCode, String amount) {
        ConsumptionService.Declaration declaration = new ConsumptionService.Declaration();
        declaration.ticketId = ticket;
        declaration.storeCode = "S1";
        declaration.finalizedAt = INSIDE;
        declaration.direction = direction;
        ConsumptionService.Advantage advantage = new ConsumptionService.Advantage();
        advantage.offerCode = offerCode;
        advantage.amount = new BigDecimal(amount);
        declaration.advantages = new ArrayList<>(List.of(advantage));
        service.record(declaration);
    }

    /**
     * §3, §6.6: the screen shows, per offer, the financed amount (cancellation netted) and the
     * ticket count — coherent with the export.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testScreen_aggregatesByOffer() {
        seedOffer("CU_A", "CARDPROMO");
        declare("T1", "SALE", "CU_A", "10.00");
        declare("T1", "CANCELLATION", "CU_A", "4.00");
        given().when().get("/ui/consumptions?from=2026-01-01&to=2026-01-31")
                .then().statusCode(200).contentType(ContentType.HTML)
                .body(containsString("CU_A"))
                .body(containsString("6.00"));
    }

    /**
     * §3: with no period selected, the screen invites one and shows no table.
     */
    @Test
    @TestSecurity(user = "manager", roles = "MANAGER")
    void testScreen_noPeriodPrompt() {
        given().when().get("/ui/consumptions")
                .then().statusCode(200)
                .body(containsString("Sélectionnez une période"));
    }

    /**
     * §5: a VIEWER is refused the screen.
     */
    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void testScreen_viewerForbidden() {
        given().when().get("/ui/consumptions?from=2026-01-01&to=2026-01-31").then().statusCode(403);
    }

    /**
     * §5: an anonymous caller is redirected to the login screen.
     */
    @Test
    void testScreen_anonymousRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get("/ui/consumptions")
                .then().statusCode(302)
                .header("location", containsString("/ui/login"));
    }
}
