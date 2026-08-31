package com.intermarche.valuation.ui;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coverage test for {@link RejectedHierarchyMapper}, run inside a {@code @QuarkusTest} so the
 * mapper class is loaded and instrumented by quarkus-jacoco while its single mapping method is
 * exercised directly.
 * <p>
 * The mapper is branch-free: it always builds a 409 JSON body from the exception message, so a
 * single call carrying a non-blank message covers the whole method. A null message is not
 * exercised because {@code Map.of} rejects null values, which is a property of the collection,
 * not a branch of this class.
 */
@QuarkusTest
public class RejectedHierarchyMapperCoverageTest {

    /**
     * Tests that a refused hierarchy is mapped to a 409 JSON response carrying the reason under
     * the {@code error} key.
     */
    @Test
    void testToResponse_mapsToConflictWithReason() {
        RejectedHierarchyMapper mapper = new RejectedHierarchyMapper();
        StoreGroupUiResource.RejectedHierarchyException exception =
                new StoreGroupUiResource.RejectedHierarchyException("cycle detected");
        Response response = mapper.toResponse(exception);
        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        assertEquals(Map.of("error", "cycle detected"), response.getEntity());
    }
}
