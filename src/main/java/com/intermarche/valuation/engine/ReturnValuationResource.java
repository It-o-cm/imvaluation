package com.intermarche.valuation.engine;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST resource of the stateless e-commerce return valuation (digital sheet DG-01-03).
 * <p>
 * Thin: delegates to {@link ReturnValuationService}. Authenticated with HTTP Basic like
 * {@code /valuation}. The body is read with exact decimals, so the order snapshot the caller
 * hands back is restated to the cent. A refusal is {@code {error}} with its status (400, 404,
 * 409, 422, 500). Nothing is persisted.
 */
@Path("/returns")
@ApplicationScoped
@RunOnVirtualThread
public class ReturnValuationResource {

    /**
     * The return valuation service.
     */
    @Inject
    ReturnValuationService service;

    /**
     * Values a return: FULL or PARTIAL against the order snapshot the caller kept, or LINES on
     * forced price sources.
     *
     * @param body the return request, JSON.
     * @return 200 with the return valuation, amounts negative.
     */
    @POST
    @Path("/valuation")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response valuate(String body) {
        try {
            ReturnValuationService.ReturnRequest request = body == null || body.isBlank() ? null
                    : read(body);
            return Response.ok(service.valuate(request)).type(MediaType.APPLICATION_JSON).build();
        } catch (ReturnValuationService.Refusal e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return Response.status(e.status).entity(error).type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * Reads the return request with exact decimals.
     *
     * @param body the JSON body.
     * @return the request.
     * @throws ReturnValuationService.Refusal 400 when the body is not a readable return request.
     */
    private static ReturnValuationService.ReturnRequest read(String body) {
        try {
            return EvaluationReader.read(body, ReturnValuationService.ReturnRequest.class);
        } catch (IllegalStateException e) {
            throw new ReturnValuationService.Refusal(400, "Malformed return request: " + e.getCause().getMessage());
        }
    }
}
