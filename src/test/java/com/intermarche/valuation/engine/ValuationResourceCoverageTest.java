package com.intermarche.valuation.engine;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code @QuarkusTest} coverage of {@link ValuationResource#calculate(Basket)}.
 * <p>
 * The engine and the trace recorder are replaced by Quarkus {@code @InjectMock} beans so the
 * resource can be driven directly, without a seeded catalog and without writing traces. This
 * lets the unprocessed-items branch (HTTP 422) be reached deterministically: with the real
 * factory set every basket line is consumed, so that branch is otherwise defensive. The
 * existing {@code ValuationResourceTest} covers the same logic as a plain unit test, which
 * quarkus-jacoco does not attribute.
 */
@QuarkusTest
public class ValuationResourceCoverageTest {

    /**
     * The valuation engine, mocked to return crafted evaluations.
     */
    @InjectMock
    ValuationEngine engine;

    /**
     * The trace recorder, mocked so recording is a no-op that never touches the database.
     */
    @InjectMock
    ValuationTraceService traceService;

    /**
     * The resource under test, with its collaborators mocked.
     */
    @Inject
    ValuationResource resource;

    /**
     * Builds a schema-valid basket: a store code and a single EAN line with a quantity.
     *
     * @return A basket that passes the request schema validation.
     */
    private Basket validBasket() {
        Basket basket = new Basket();
        basket.storeCode = "0101";
        basket.customerCode = "C1";
        Basket.Item item = new Basket.Item();
        item.produceEan = "3300000000001";
        item.quantity = 1.0;
        basket.items = new ArrayList<>(List.of(item));
        return basket;
    }

    /**
     * Covers the unprocessed-items branch: when the engine leaves entries in the working map
     * the resource answers HTTP 422.
     */
    @Test
    void calculateReturns422WhenItemsRemain() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        Map<String, List<Basket.Item>> remaining = new HashMap<>();
        Basket.Item leftover = new Basket.Item();
        leftover.produceEan = "3300000000001";
        leftover.quantity = 1.0;
        remaining.put("3300000000001", new ArrayList<>(List.of(leftover)));
        when(evaluation.getToEvaluate()).thenReturn(remaining);
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.calculate(validBasket()));
        assertEquals(422, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("Valuation failed"));
    }

    /**
     * Covers the success path: an empty working map yields a 200 response carrying the
     * evaluation.
     */
    @Test
    void calculateReturns200WhenAllItemsProcessed() {
        BasketEvaluation evaluation = mock(BasketEvaluation.class);
        when(evaluation.getToEvaluate()).thenReturn(Collections.emptyMap());
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
        Response response = resource.calculate(validBasket());
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(evaluation, response.getEntity());
    }
}
