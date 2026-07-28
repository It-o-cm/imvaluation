

package com.intermarche.valuation.engine;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Test class for {@link ValuationResource}.
 */
@ExtendWith(MockitoExtension.class)
public class ValuationResourceTest {

    @Mock
    private ValuationEngine engine;

    @InjectMocks
    private ValuationResource resource;

    private Basket basket;

    @BeforeEach
    void setUp() {
        basket = new Basket();
        basket.customerCode = "CUST_001";
        basket.storeCode = "STORE_001";
        basket.items = new ArrayList<>();

        Basket.Item item = new Basket.Item();
        item.produceEan = "1234567890123";
        item.quantity = 1.0;
        basket.items.add(item);
    }

    @Test
    void testCalculate_Success() {
        // Arrange
        BasketEvaluation evaluation = mockEvaluationWithEmptyMap();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);

        // Act
        Response response = resource.calculate(basket);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(evaluation, response.getEntity());
    }

    @Test
    void testCalculate_Success_NullToEvaluate() {
        // Arrange
        BasketEvaluation evaluation = mockEvaluationWithNullMap();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);

        // Act
        Response response = resource.calculate(basket);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(evaluation, response.getEntity());
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

    @Test
    void testCalculate_ItemsListIsNull() {
        // Arrange
        basket.items = null;
        BasketEvaluation evaluation = mockEvaluationWithEmptyMap();
        when(engine.evaluate(any(Basket.class))).thenReturn(evaluation);

        // Act
        Response response = resource.calculate(basket);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
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
        item.quantity = 1.0;
        items.put(item.produceEan, new ArrayList<>(java.util.List.of(item)));
        when(evaluation.getToEvaluate()).thenReturn(items);
        return evaluation;
    }
}
