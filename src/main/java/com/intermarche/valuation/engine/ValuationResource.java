package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 400 whose {@code {error}} body names the offending fields and lines, rather than a 500 raised
 * from inside a factory.
 * <p>
 * If some items cannot be matched or processed by any offer, the service returns
 * a HTTP 422 (Unprocessable Entity) status using {@link WebApplicationException}. A
 * {@link ReferentialException} raised by the engine — an unknown EAN without a pushed
 * price, an unknown store, or a known article without an active price — is likewise
 * answered 422, the body naming the offending EAN or store, rather than a bare 500.
 * <p>
 * Every exchange is handed to {@link ValuationTraceService}, which records it when
 * tracing is enabled.
 * <p>
 * The answer is the evaluation as the application mapper serializes it, each valued line
 * enriched with the family of its product by {@link LineFamilies} (digital sheet
 * DG-01-01-01). The resource stays stateless: nothing of the basket is kept.
 */
@Path("/valuation")
@ApplicationScoped
@RunOnVirtualThread
public class ValuationResource implements EngineTrait {

    private static final Logger LOGGER = Logger.getLogger(ValuationResource.class);

    /**
     * Mapper used to echo the incoming basket into the trace.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

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
     * The application mapper, the one the answer has always been serialized with.
     */
    @Inject
    ObjectMapper mapper;

    /**
     * Endpoint to receive a basket for valuation.
     * <p>
     * Parses the incoming JSON into a {@link Basket} object, validates it against the
     * schema, delegates processing to the {@link ValuationEngine}, validates the result,
     * and returns the computed {@link BasketEvaluation}.
     *
     * @param basket The basket object parsed from JSON body.
     * @return A 200 OK response containing the serialized {@link BasketEvaluation} with applied
     *         offers, its valued lines carrying their family.
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
            // The 400 carries the message (spec §4): a partial pushed price names each missing
            // field and its line, so the caller knows what to correct without reading the trace.
            throw new WebApplicationException(e.getMessage(), Response.status(400)
                    .entity(java.util.Map.of("error", e.getMessage())).type(MediaType.APPLICATION_JSON).build());
        }

        // --------------------------------------------------
        // Valuation Logic
        // --------------------------------------------------
        BasketEvaluation evaluation;
        try {
            evaluation = engine.evaluate(basket);
        } catch (ReferentialException e) {
            // A referential gap (unknown EAN without a pushed price, unknown store, or a known
            // article without an active price) is the caller's data, not a server bug: answer
            // 422 with the message, which names the offending EAN or store, rather than a bare
            // 500.
            LOGGER.warn("Valuation rejected: " + e.getMessage());
            traceService.record(requestPayload, basket, null, 422,
                    ValuationTrace.STATUS_FAILED, e.getMessage(),
                    System.currentTimeMillis() - startedAt);
            throw new WebApplicationException(e.getMessage(), Response.status(422)
                    .entity(java.util.Map.of("error", e.getMessage())).type(MediaType.APPLICATION_JSON).build());
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

        // Fail-closed configurations (A2, report H1) still let the evaluation succeed; record the
        // skips in the trace so they are auditable even on a 200.
        String skips = evaluation.getSkippedConfigurations().isEmpty() ? null
                : "Skipped configurations: " + String.join(" | ", evaluation.getSkippedConfigurations());
        if (skips != null) {
            LOGGER.warn(skips);
        }
        traceService.record(requestPayload, basket, evaluation, 200,
                ValuationTrace.STATUS_SUCCESS, skips,
                System.currentTimeMillis() - startedAt);
        return Response.ok(describe(evaluation)).build();
    }

    /**
     * Serializes the evaluation with the application mapper, read back with exact decimals so the
     * figures on the wire are unchanged, and adds the family of every valued line.
     *
     * @param evaluation The evaluation to answer.
     * @return The serialized, enriched evaluation.
     * @throws WebApplicationException with status 500 when the evaluation cannot be serialized.
     */
    JsonNode describe(BasketEvaluation evaluation) {
        JsonNode node;
        try {
            node = EvaluationReader.MAPPER.readTree(mapper.writeValueAsString(evaluation));
        } catch (Exception e) {
            LOGGER.error("Could not serialize the evaluation", e);
            throw new WebApplicationException("Cannot serialize the evaluation.", 500);
        }
        LineFamilies.describe(node);
        return node;
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
