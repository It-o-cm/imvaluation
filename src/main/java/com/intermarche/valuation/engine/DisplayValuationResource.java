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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the display valuation of a batch of products
 * (spec-valorisation-affichage-ecommerce §2).
 * <p>
 * {@code POST /display-valuation} is a read-only, idempotent, side-effect-free batch in the
 * {@code /valuation} family: it returns, per EAN, the announced price a storefront shows outside the
 * basket — the crossed-out reference price, the displayed price after immediate product markdowns,
 * the unit price of measure, and the labels of the basket-dependent offers. The real basket total
 * stays the job of {@link ValuationResource}.
 * <p>
 * Conceptually the request is a very special multiple basket — n one-unit mini-baskets valued
 * against a context built once (§2bis) — so the computation is delegated to
 * {@link DisplayValuationService}, which runs the same {@link ValuationEngine} that prices tickets.
 * Unlike {@code /valuation}, nothing is persisted: no trace, no consumption, no write, whatever the
 * call volume (§2 rule 5, §7 criterion 3).
 * <p>
 * The EAN count is capped (default 200, {@code valuation.display.max-eans}); beyond the cap the
 * call is refused 400 naming the limit and the count received — immcomm paginates its own pages, so
 * there is no server-side pagination. A missing/unreadable date or an unknown store is a named
 * refusal; an unknown EAN is reported on its own entry while the rest of the batch is valued.
 */
@Path("/display-valuation")
@ApplicationScoped
@RunOnVirtualThread
public class DisplayValuationResource {

    /**
     * Logger for the request-level refusals.
     */
    private static final Logger LOGGER = Logger.getLogger(DisplayValuationResource.class);

    /**
     * The maximum number of EANs accepted per call (spec §2, §6.1): 200 by default, overridable by
     * {@code valuation.display.max-eans} in {@code application.properties}.
     */
    @ConfigProperty(name = "valuation.display.max-eans", defaultValue = "200")
    int maxEans;

    /**
     * The service that builds the shared context once and values each EAN against it.
     */
    @Inject
    DisplayValuationService service;

    /**
     * Values a batch of EANs for display.
     * <p>
     * Enforces the EAN cap, then delegates to {@link DisplayValuationService}. A
     * {@link DisplayValuationException} becomes a response carrying its status and a
     * {@code {error}} body naming the cause, mirroring {@code /valuation}'s 400/422 contract.
     *
     * @param request the display valuation request.
     * @return 200 with one entry per EAN; 400 when the EAN cap is exceeded or the request is
     *         malformed; 422 when the store is unknown.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response valuate(DisplayValuationRequest request) {
        if (request == null) {
            return refusal(400, "A request body is required.");
        }
        int count = request.eans == null ? 0 : request.eans.size();
        if (count > maxEans) {
            // Spec §6.1: the cap is named along with the count received, and there is no pagination.
            return refusal(400, "Too many EANs: " + count + " received, the limit is " + maxEans
                    + " per call (valuation.display.max-eans). immcomm paginates its pages; this"
                    + " endpoint does not.");
        }
        try {
            List<DisplayValuationEntry> entries = service.evaluate(request);
            return Response.ok(entries).build();
        } catch (DisplayValuationException e) {
            LOGGER.warn("Display valuation refused: " + e.getMessage());
            return refusal(e.getStatus(), e.getMessage());
        }
    }

    /**
     * Builds a named refusal response, the {@code {error}} body carrying the cause.
     *
     * @param status  the HTTP status.
     * @param message the message naming the cause.
     * @return the response, never thrown — returned directly so the batch contract stays a plain
     *         JSON body at every status.
     */
    private static Response refusal(int status, String message) {
        return Response.status(status)
                .entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
