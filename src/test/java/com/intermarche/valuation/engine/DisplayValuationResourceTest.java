package com.intermarche.valuation.engine;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link DisplayValuationResource}: the EAN-cap guard, the null-body guard, the
 * delegation to {@link DisplayValuationService}, and the mapping of a {@link DisplayValuationException}
 * to its HTTP status. The service is a Mockito mock; no HTTP server, no CDI, no database.
 */
class DisplayValuationResourceTest {

    /**
     * The resource under test, its service replaced by a mock and its cap set explicitly.
     */
    private DisplayValuationResource resource;

    /**
     * The mocked service.
     */
    private DisplayValuationService service;

    /**
     * Builds the resource with a mocked service and a cap of 200.
     */
    @BeforeEach
    void setUp() {
        resource = new DisplayValuationResource();
        service = mock(DisplayValuationService.class);
        resource.service = service;
        resource.maxEans = 200;
    }

    /**
     * Builds a request with the given number of blank EANs.
     *
     * @param count the number of EANs.
     * @return the request.
     */
    private static DisplayValuationRequest requestWith(int count) {
        DisplayValuationRequest request = new DisplayValuationRequest();
        request.storeCode = "0101";
        request.valuationDate = "2026-06-01";
        request.eans = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            request.eans.add("E" + i);
        }
        return request;
    }

    /**
     * A null body is refused 400 with a named error, the service never consulted.
     */
    @Test
    void nullBodyIsRefused400() {
        Response response = resource.valuate(null);
        assertEquals(400, response.getStatus());
        assertEquals("A request body is required.", ((Map<?, ?>) response.getEntity()).get("error"));
        verifyNoInteractions(service);
    }

    /**
     * Exactly the cap is accepted (boundary): the service is consulted and its entries returned 200.
     */
    @Test
    void atTheCapIsAccepted() {
        DisplayValuationRequest request = requestWith(200);
        List<DisplayValuationEntry> entries = List.of(new DisplayValuationEntry());
        when(service.evaluate(request)).thenReturn(entries);
        Response response = resource.valuate(request);
        assertEquals(200, response.getStatus());
        assertSame(entries, response.getEntity());
        verify(service).evaluate(request);
    }

    /**
     * One EAN over the cap is refused 400, the message naming the limit and the count received, and
     * the service is never consulted.
     */
    @Test
    void overTheCapIsRefused400() {
        DisplayValuationRequest request = requestWith(201);
        Response response = resource.valuate(request);
        assertEquals(400, response.getStatus());
        String error = (String) ((Map<?, ?>) response.getEntity()).get("error");
        assertTrue(error.contains("201"), "names the count received: " + error);
        assertTrue(error.contains("200"), "names the limit: " + error);
        verifyNoInteractions(service);
    }

    /**
     * A request with a null EAN list counts as zero and is accepted, delegating to the service.
     */
    @Test
    void nullEanListCountsAsZero() {
        DisplayValuationRequest request = new DisplayValuationRequest();
        request.eans = null;
        List<DisplayValuationEntry> entries = new ArrayList<>();
        when(service.evaluate(request)).thenReturn(entries);
        Response response = resource.valuate(request);
        assertEquals(200, response.getStatus());
        assertSame(entries, response.getEntity());
    }

    /**
     * A {@link DisplayValuationException} thrown by the service is mapped to its carried status with
     * a named error body.
     */
    @Test
    void serviceRefusalIsMappedToItsStatus() {
        DisplayValuationRequest request = requestWith(1);
        when(service.evaluate(any())).thenThrow(new DisplayValuationException(422, "Store not found for code '9999'."));
        Response response = resource.valuate(request);
        assertEquals(422, response.getStatus());
        assertEquals("Store not found for code '9999'.", ((Map<?, ?>) response.getEntity()).get("error"));
        assertFalse(response.getEntity() instanceof List, "a refusal is an error map, not the entries list");
    }
}
