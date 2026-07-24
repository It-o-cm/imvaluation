package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.ValuationTrace;
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
 * Receives a JSON representation of a basket, validates it against
 * {@link Basket#BASKET_SCHEMA}, uses the {@link ValuationEngine} to calculate applicable
 * offers, and returns the resulting {@link BasketEvaluation}.
 * <p>
 * Validation happens before the engine runs, so a malformed request is answered with a
 * 400 naming the offending fields rather than a 500 raised from inside a factory.
 * <p>
 * If some items cannot be matched or processed by any offer, the service returns
 * a HTTP 422 (Unprocessable Entity) status using {@link WebApplicationException}.
 * <p>
 * Every exchange is handed to {@link ValuationTraceService}, which records it when
 * tracing is enabled.
 */
@Path("/valuation")
@ApplicationScoped
@RunOnVirtualThread
public class ValuationResource implements EngineTrait {

    private static final Logger LOGGER = Logger.getLogger(ValuationResource.class);

    /**
     * Mapper used to echo the incoming basket into the trace.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The valuation engine instance.
     * <p>
     * Injected by CDI to handle business logic.
     */
    @Inject
    ValuationEngine engine;

    /**
     * Recorder keeping the request and its outcome for later inspection.
     */
    @Inject
    ValuationTraceService traceService;

    /**
     * Endpoint to receive a basket for valuation.
     * <p>
     * Parses the incoming JSON into a {@link Basket} object, validates it against the
     * schema, delegates processing to the {@link ValuationEngine}, validates the result,
     * and returns the computed {@link BasketEvaluation}.
     *
     * @param basket The basket object parsed from JSON body.
     * @return A 200 OK response containing the {@link BasketEvaluation} with applied offers.
     * @throws WebApplicationException with status 400 if the basket does not satisfy the schema,
     *                                  or 422 if the valuation failed to process all items.
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

        long startedAt = System.currentTimeMillis();
        // The basket is re-serialized rather than captured as a raw body: the resource
        // receives an already parsed object, and this keeps the trace self-contained.
        String requestPayload = serializeRequest(basket);

        // --------------------------------------------------
        // Schema Validation
        // --------------------------------------------------
        try {
            this.processSpecification(Basket.BASKET_SCHEMA, requestPayload, (node) -> {
                // Validation only: the parsed node is not needed here.
            });
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rejected valuation request: " + e.getMessage());
            traceService.record(requestPayload, basket, null, 400,
                    ValuationTrace.STATUS_REJECTED, e.getMessage(),
                    System.currentTimeMillis() - startedAt);
            throw new WebApplicationException(e.getMessage(), 400);
        }

        // --------------------------------------------------
        // Valuation Logic
        // --------------------------------------------------
        BasketEvaluation evaluation;
        try {
            evaluation = engine.evaluate(basket);
        } catch (RuntimeException e) {
            LOGGER.error("Valuation failed", e);
            traceService.record(requestPayload, basket, null, 500,
                    ValuationTrace.STATUS_FAILED, e.getMessage(),
                    System.currentTimeMillis() - startedAt);
            throw e;
        }

        // Check if all items were successfully processed
        // If the internal "toEvaluate" map still has items, it means some were not matched
        // by any offer logic.
        if (evaluation.getToEvaluate() != null && !evaluation.getToEvaluate().isEmpty()) {
            String message = "Valuation failed: Some items could not be processed by any offer.";
            LOGGER.error("Valuation failed: " + evaluation.getToEvaluate().size() + " items could not be processed.");
            traceService.record(requestPayload, basket, evaluation, 422,
                    ValuationTrace.STATUS_FAILED, message,
                    System.currentTimeMillis() - startedAt);
            // WebApplicationException is the standard JAX-RS way to throw arbitrary HTTP status codes.
            // 422 = Unprocessable Entity
            throw new WebApplicationException(message, 422);
        }

        traceService.record(requestPayload, basket, evaluation, 200,
                ValuationTrace.STATUS_SUCCESS, null,
                System.currentTimeMillis() - startedAt);
        return Response.ok(evaluation).build();
    }

    /**
     * Serializes the received basket back to JSON.
     *
     * @param basket The basket to serialize.
     * @return The JSON representation, or an empty object when serialization fails.
     */
    private String serializeRequest(Basket basket) {
        try {
            return MAPPER.writeValueAsString(basket);
        } catch (Exception e) {
            LOGGER.error("Could not serialize the incoming basket", e);
            return "{}";
        }
    }
}
