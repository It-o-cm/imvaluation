package com.intermarche.valuation.ui;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RejectedHierarchyMapper}.
 * <p>
 * The mapper carries no conditional logic: its single {@code toResponse} method is
 * straight-line code. The tests therefore pin the shape of the produced response — the
 * {@code 409 CONFLICT} status, the JSON content type and the {@code {"error": <message>}}
 * body — against absolute expected values so the contract with the workbench editor is
 * protected.
 */
class RejectedHierarchyMapperTest {

    /**
     * The mapper turns a refusal into a 409 response typed as JSON.
     */
    @Test
    void mapsRefusalToConflictJsonResponse() {
        RejectedHierarchyMapper mapper = new RejectedHierarchyMapper();
        StoreGroupUiResource.RejectedHierarchyException exception =
                new StoreGroupUiResource.RejectedHierarchyException("Store A already attached");
        Response response = mapper.toResponse(exception);
        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
    }

    /**
     * The response body carries the exception message under the {@code error} key.
     */
    @Test
    void bodyHoldsExceptionMessageUnderErrorKey() {
        RejectedHierarchyMapper mapper = new RejectedHierarchyMapper();
        StoreGroupUiResource.RejectedHierarchyException exception =
                new StoreGroupUiResource.RejectedHierarchyException("Cycle detected");
        Response response = mapper.toResponse(exception);
        assertEquals(Map.of("error", "Cycle detected"), response.getEntity());
    }

    /**
     * A different message is propagated verbatim, confirming the body is not hard-coded.
     */
    @Test
    void bodyPropagatesEachMessageVerbatim() {
        RejectedHierarchyMapper mapper = new RejectedHierarchyMapper();
        StoreGroupUiResource.RejectedHierarchyException exception =
                new StoreGroupUiResource.RejectedHierarchyException("Unknown store group 42");
        Response response = mapper.toResponse(exception);
        assertEquals(Map.of("error", "Unknown store group 42"), response.getEntity());
    }
}
