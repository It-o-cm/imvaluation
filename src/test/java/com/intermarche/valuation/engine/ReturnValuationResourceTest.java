package com.intermarche.valuation.engine;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ReturnValuationResource}: the body is read with exact decimals and
 * handed to a mocked {@link ReturnValuationService}; refusals and unreadable bodies map to
 * {@code {error}} with their status. The valuation cases themselves (one per price source, full
 * and partial on a closing snapshot) are asserted in {@code ReturnValuationServiceTest} and over
 * HTTP in {@code com.intermarche.e2e.GroupZIT}.
 */
class ReturnValuationResourceTest {

    /**
     * Builds the resource around a mocked service.
     *
     * @return the resource.
     */
    private static ReturnValuationResource resource() {
        ReturnValuationResource resource = new ReturnValuationResource();
        resource.service = mock(ReturnValuationService.class);
        return resource;
    }

    /**
     * A valuation answers 200 with the service's view; the order snapshot reaches the service with
     * its decimals exact.
     */
    @Test
    void valuate_readsExactlyAndDelegates() {
        ReturnValuationResource resource = resource();
        ReturnValuationService.ReturnView view = new ReturnValuationService.ReturnView();
        when(resource.service.valuate(any())).thenReturn(view);
        Response response = resource.valuate("{\"mode\":\"FULL\",\"snapshot\":{\"evaluation\":{\"totalPrice\":"
                + "{\"amountIncludingTax\":20.20}}},\"lines\":[{\"quantity\":1.50}]}");
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        ArgumentCaptor<ReturnValuationService.ReturnRequest> request =
                ArgumentCaptor.forClass(ReturnValuationService.ReturnRequest.class);
        verify(resource.service).valuate(request.capture());
        assertEquals("FULL", request.getValue().mode);
        assertEquals("20.20", request.getValue().snapshot.evaluation.path("totalPrice").path("amountIncludingTax").decimalValue().toPlainString());
        assertEquals(new BigDecimal("1.50"), request.getValue().lines.get(0).quantity);
    }

    /**
     * A missing or blank body reaches the service as no request, which refuses it.
     */
    @Test
    void valuate_withoutBody_passesNull() {
        ReturnValuationResource resource = resource();
        when(resource.service.valuate(null)).thenThrow(new ReturnValuationService.Refusal(400, "A return body is required."));
        assertEquals(400, resource.valuate(null).getStatus());
        Response blank = resource.valuate("  ");
        assertEquals(400, blank.getStatus());
        assertEquals(Map.of("error", "A return body is required."), blank.getEntity());
    }

    /**
     * A refusal of the service is answered with its status and an {@code {error}} body.
     */
    @Test
    void valuate_refusal_answersError() {
        ReturnValuationResource resource = resource();
        when(resource.service.valuate(any())).thenThrow(new ReturnValuationService.Refusal(400, "lineId 9 is not a line of the snapshot."));
        Response response = resource.valuate("{\"mode\":\"PARTIAL\"}");
        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", "lineId 9 is not a line of the snapshot."), response.getEntity());
    }

    /**
     * An unreadable body is a 400 naming the parse error; the service is not called.
     */
    @Test
    void valuate_unreadableBody_is400() {
        ReturnValuationResource resource = resource();
        Response response = resource.valuate("{\"lines\":\"x\"");
        assertEquals(400, response.getStatus());
        @SuppressWarnings("unchecked")
        String error = ((Map<String, Object>) response.getEntity()).get("error").toString();
        assertTrue(error.startsWith("Malformed return request: "), error);
        verify(resource.service, never()).valuate(any());
    }
}
