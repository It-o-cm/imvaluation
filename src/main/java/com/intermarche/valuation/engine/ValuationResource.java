package com.intermarche.valuation.engine;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST Endpoint for basket valuation.
 * <p>
 * Receives a JSON representation of a basket, parses it into {@link Basket},
 * uses the {@link ValuationEngine} to calculate applicable offers, and returns
 * the resulting {@link BasketEvaluation}.
 * <p>
 * If some items cannot be matched or processed by any offer, the service returns
 * a HTTP 422 (Unprocessable Entity) status using {@link WebApplicationException}.
 */
@Path("/valuation")
@ApplicationScoped
@RunOnVirtualThread
public class ValuationResource {

    private static final Logger LOGGER = Logger.getLogger(ValuationResource.class);

    /**
     * The valuation engine instance.
     * <p>
     * Injected by CDI to handle business logic.
     */
    @Inject
    ValuationEngine engine;

    /**
     * Endpoint to receive a basket for valuation.
     * <p>
     * Parses the incoming JSON into a {@link Basket} object,
     * delegates processing to the {@link ValuationEngine}, validates
     * the result, and returns the computed {@link BasketEvaluation}.
     *
     * @param basket The basket object parsed from JSON body.
     * @return A 200 OK response containing the {@link BasketEvaluation} with applied offers.
     * @throws WebApplicationException with status 422 if the valuation failed to process all items
     *                                  (toEvaluate map is not empty).
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response calculate(Basket basket) {
        LOGGER.info("Received valuation request for customer: " + basket.customerCode);
        LOGGER.info("Store: " + basket.storeCode + ", Mode: " + basket.deliveryMode);

        if (basket.items != null) {
            LOGGER.info("Number of items in basket: " + basket.items.size());
        }

        // --------------------------------------------------
        // Valuation Logic
        // --------------------------------------------------
        BasketEvaluation evaluation = engine.evaluate(basket);

        // Check if all items were successfully processed
        // If the internal "toEvaluate" map still has items, it means some were not matched
        // by any offer logic.
        if (evaluation.getToEvaluate() != null && !evaluation.getToEvaluate().isEmpty()) {
            LOGGER.error("Valuation failed: " + evaluation.getToEvaluate().size() + " items could not be processed.");
            // WebApplicationException is the standard JAX-RS way to throw arbitrary HTTP status codes.
            // 422 = Unprocessable Entity
            throw new WebApplicationException("Valuation failed: Some items could not be processed by any offer.", 422);
        }

        return Response.ok(evaluation).build();
    }
}