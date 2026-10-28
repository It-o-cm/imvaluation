

package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * Test class for {@link ValuationResource}.
 */
@ExtendWith(MockitoExtension.class)
public class ValuationResourceTest {

    @Mock
    private ValuationEngine engine;

    /**
     * The resource records every valuation, successful or not, so the collaborator has to be
     * supplied here too: {@code @InjectMocks} only injects what the test declares, and an
     * undeclared one stays null and fails on the first call.
     */
    @Mock
    private ValuationTraceService traceService;

    /**
     * The application mapper the answer is serialized with, mocked so a crafted evaluation
     * serializes to a known document.
     */
    @Mock
    private ObjectMapper mapper;

    @InjectMocks
    private ValuationResource resource;

    /**
     * The document the mocked mapper answers for a successful evaluation.
     */
    private static final String ANSWER = "{\"offers\":[],\"totalPrice\":{\"amountIncludingTax\":1.20}}";

    private Basket basket;

    @BeforeEach
    void setUp() {
        basket = new Basket();
        basket.customerCode = "CUST_001";
        basket.storeCode = "STORE_001";
        basket.items = new ArrayList<>();

        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        item.quantity = BigDecimal.valueOf(1.0);
        basket.items.add(item);
    }

    /**
     * A processed basket answers 200 with the evaluation as the application mapper serializes
     * it, read back with its decimals exact.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void testCalculate_Success() throws Exception {
        BasketEvaluation evaluation = mockEvaluationWithEmptyMap();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
        when(mapper.writeValueAsString(evaluation)).thenReturn(ANSWER);
        Response response = resource.calculate(basket);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(EvaluationReader.MAPPER.readTree(ANSWER), response.getEntity());
        assertEquals("1.20", ((JsonNode) response.getEntity()).path("totalPrice").path("amountIncludingTax").decimalValue().toPlainString());
    }

    /**
     * A null working map is read as fully processed and answers 200.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void testCalculate_Success_NullToEvaluate() throws Exception {
        BasketEvaluation evaluation = mockEvaluationWithNullMap();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
        when(mapper.writeValueAsString(evaluation)).thenReturn(ANSWER);
        Response response = resource.calculate(basket);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(EvaluationReader.MAPPER.readTree(ANSWER), response.getEntity());
    }

    /**
     * An evaluation the application mapper cannot serialize answers 500.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void testCalculate_UnserializableEvaluation_is500() throws Exception {
        BasketEvaluation evaluation = mockEvaluationWithEmptyMap();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
        when(mapper.writeValueAsString(evaluation)).thenThrow(new IllegalStateException("boom"));
        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.calculate(basket));
        assertEquals(500, ex.getResponse().getStatus());
        assertEquals("Cannot serialize the evaluation.", ex.getMessage());
    }

    /**
     * Skipped configurations still answer 200 and are recorded in the trace.
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    void testCalculate_SkippedConfigurations_areTraced() throws Exception {
        BasketEvaluation evaluation = mockEvaluationWithEmptyMap();
        when(evaluation.getSkippedConfigurations()).thenReturn(java.util.List.of("A", "B"));
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);
        when(mapper.writeValueAsString(evaluation)).thenReturn(ANSWER);
        assertEquals(200, resource.calculate(basket).getStatus());
        verify(traceService).record(any(), any(), any(), org.mockito.ArgumentMatchers.eq(200), any(),
                org.mockito.ArgumentMatchers.eq("Skipped configurations: A | B"), org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * A generic engine failure (an unexpected bug, not a configuration gap) is traced as a 500
     * and rethrown unchanged, so it surfaces as a server error.
     */
    @Test
    void testCalculate_EngineFailure_isRethrown() {
        when(engine.evaluate(any(Basket.class))).thenThrow(new IllegalStateException("unexpected"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resource.calculate(basket));
        assertEquals("unexpected", ex.getMessage());
        verify(traceService).record(any(), any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(500),
                any(), org.mockito.ArgumentMatchers.eq("unexpected"), org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * An unknown store raised by the engine as a {@link ReferentialException} answers 422
     * (not 500), the body naming the offending store, and the exchange is traced as a 422.
     */
    @Test
    void testCalculate_UnknownStore_is422NamingStore() {
        when(engine.evaluate(any(Basket.class))).thenThrow(new ReferentialException(
                "Configuration Error: Store not found for code 'STORE_001'"));
        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.calculate(basket));
        assertEquals(422, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("STORE_001"));
        assertEquals(java.util.Map.of("error", "Configuration Error: Store not found for code 'STORE_001'"),
                ex.getResponse().getEntity());
        verify(traceService).record(any(), any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(422),
                any(), org.mockito.ArgumentMatchers.eq("Configuration Error: Store not found for code 'STORE_001'"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * An unknown article without a pushed price raised as a {@link ReferentialException}
     * answers 422, the body naming the offending EAN.
     */
    @Test
    void testCalculate_UnknownArticle_is422NamingEan() {
        when(engine.evaluate(any(Basket.class))).thenThrow(new ReferentialException(
                "Configuration Error: Product not found for EAN '1234567890123'"));
        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.calculate(basket));
        assertEquals(422, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("1234567890123"));
        assertEquals(java.util.Map.of("error", "Configuration Error: Product not found for EAN '1234567890123'"),
                ex.getResponse().getEntity());
    }

    /**
     * A basket that cannot be echoed to JSON is traced as an empty object, which the schema then
     * rejects with a 400; the engine is never reached.
     */
    @Test
    void testCalculate_UnserializableBasket_is400() {
        Basket broken = new Basket() {
            /**
             * A getter that always fails.
             *
             * @return never.
             */
            public String getBoom() {
                throw new IllegalStateException("no");
            }
        };
        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.calculate(broken));
        assertEquals(400, ex.getResponse().getStatus());
        verify(engine, never()).evaluate(any());
    }

    @Test
    void testCalculate_Failure_UnprocessedItems() {
        // Arrange
        BasketEvaluation evaluation = mockEvaluationWithItems();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);

        // Act & Assert
        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> {
            resource.calculate(basket);
        });

        assertEquals(422, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("Valuation failed"));
    }

    /**
     * A basket without any line is rejected before valuation.
     * <p>
     * The request is validated against the basket schema, which requires {@code items}, so
     * the engine is never reached: pricing nothing and answering success would hide a
     * malformed request from the caller.
     */
    @Test
    void testCalculate_ItemsListIsNull() {
        // Arrange
        basket.items = null;

        // Act & Assert
        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> {
            resource.calculate(basket);
        });

        assertEquals(400, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("items"));
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    private BasketEvaluation mockEvaluationWithEmptyMap() {
        BasketEvaluation evaluation = org.mockito.Mockito.mock(BasketEvaluation.class);
        when(evaluation.getToEvaluate()).thenReturn(Collections.emptyMap());
        return evaluation;
    }

    private BasketEvaluation mockEvaluationWithNullMap() {
        BasketEvaluation evaluation = org.mockito.Mockito.mock(BasketEvaluation.class);
        when(evaluation.getToEvaluate()).thenReturn(null);
        return evaluation;
    }

    private BasketEvaluation mockEvaluationWithItems() {
        BasketEvaluation evaluation = org.mockito.Mockito.mock(BasketEvaluation.class);
        Map<String, java.util.List<Basket.Item>> items = new HashMap<>();
        Basket.Item item = new Basket.Item();
        item.produceEan = "9999999999999";
        item.quantity = BigDecimal.valueOf(1.0);
        items.put(item.produceEan, new ArrayList<>(java.util.List.of(item)));
        when(evaluation.getToEvaluate()).thenReturn(items);
        return evaluation;
    }
}
