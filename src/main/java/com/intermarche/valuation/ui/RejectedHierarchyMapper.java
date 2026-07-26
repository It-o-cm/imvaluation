package com.intermarche.valuation.ui;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Turns a refused hierarchy into a response the workbench can display.
 * <p>
 * The workbench submits the whole reorganisation at once, so a refusal must come back as
 * a readable reason rather than a stack trace: the editor keeps the pending changes on
 * screen and shows the message next to the save button.
 */
@Provider
public class RejectedHierarchyMapper
        implements ExceptionMapper<StoreGroupUiResource.RejectedHierarchyException> {

    /**
     * Maps the exception to a 409 carrying the reason.
     *
     * @param exception The refusal raised while applying the hierarchy.
     * @return A conflict response holding the message.
     */
    @Override
    public Response toResponse(StoreGroupUiResource.RejectedHierarchyException exception) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", exception.getMessage()))
                .build();
    }
}
