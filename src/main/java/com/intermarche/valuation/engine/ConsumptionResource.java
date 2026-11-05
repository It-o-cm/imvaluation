package com.intermarche.valuation.engine;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoint that receives declared ticket consumption (spec §4).
 * <p>
 * The endpoint is deliberately agnostic of the emitter: it is declared by the
 * delivery chain from cashed tickets, which is what makes it relayable — a store
 * node sees every ticket of its counters and relays it, and in the demo stack the
 * delivery component runs inside the till. It carries {@code @RolesAllowed("ADMIN")}
 * like the imports.
 * <p>
 * The declaration is idempotent on {@code (storeCode, ticketId, direction)}: a
 * replay answers {@code 200} with {@code duplicate: true} and changes no counter.
 * A malformed declaration answers {@code 400}.
 */
@Path("/consumptions")
@ApplicationScoped
@RunOnVirtualThread
public class ConsumptionResource {

    private static final Logger LOGGER = Logger.getLogger(ConsumptionResource.class);

    /**
     * The consumption ingestion service keeping the counters up to date.
     */
    @Inject
    ConsumptionService consumptionService;

    /**
     * Receives a ticket consumption declaration and updates the counters.
     *
     * @param declaration the declaration parsed from the JSON body.
     * @return {@code 200} with {@code {duplicate, offersAffected}} on success, {@code 400} when the
     *         declaration is malformed.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response declare(ConsumptionService.Declaration declaration) {
        if (declaration == null) {
            return badRequest("A consumption declaration body is required.");
        }
        LOGGER.infof("Received consumption declaration: store=%s ticket=%s direction=%s",
                declaration.storeCode, declaration.ticketId, declaration.direction);
        try {
            ConsumptionService.Outcome outcome = consumptionService.record(declaration);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("duplicate", outcome.duplicate);
            body.put("offersAffected", outcome.offersAffected);
            return Response.ok(body).build();
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rejected consumption declaration: " + e.getMessage());
            return badRequest(e.getMessage());
        }
    }

    /**
     * Builds a {@code 400} response carrying the rejection message.
     *
     * @param message the rejection reason.
     * @return the built response.
     */
    private static Response badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return Response.status(Response.Status.BAD_REQUEST).entity(body).build();
    }
}
