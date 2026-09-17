package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Product;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Trait providing common utilities for offer and advantage processing.
 * <p>
 * This interface acts as a mixin for various factories and appliers,
 * providing shared validation, database retrieval, and JSON parsing logic.
 */
public interface EngineTrait {

    /**
     * JSON Schema fragment for the optional {@code trigger} block (spec §3.1), injected into
     * every offer/advantage schema by {@link #processSpecification} — the single funnel, so
     * no factory is edited one by one.
     */
    String TRIGGER_PROPERTY_SCHEMA = """
        { "type": "object", "additionalProperties": false,
          "required": ["conditions"],
          "properties": {
            "conditions": { "type": "array", "minItems": 1,
              "items": { "$ref": "#/definitions/triggerCondition" } } } }""";

    /**
     * JSON Schema definition of a single trigger condition — the three kinds of spec §3.1.
     * The scope/eans cross-rules a schema cannot express are enforced in code by
     * {@link Trigger#of}.
     */
    String TRIGGER_CONDITION_DEFINITION = """
        { "oneOf": [
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "scope", "threshold"],
              "properties": {
                "kind": { "const": "MINIMUM_AMOUNT" },
                "scope": { "enum": ["TICKET", "ITEMS"] },
                "eans": { "type": "array", "minItems": 1,
                          "items": { "type": "string", "minLength": 1 } },
                "threshold": { "type": "number", "exclusiveMinimum": 0 } } },
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "eans", "threshold"],
              "properties": {
                "kind": { "const": "MINIMUM_QUANTITY" },
                "eans": { "type": "array", "minItems": 1,
                          "items": { "type": "string", "minLength": 1 } },
                "threshold": { "type": "number", "exclusiveMinimum": 0 } } },
            { "type": "object", "additionalProperties": false,
              "required": ["kind", "code"],
              "properties": {
                "kind": { "const": "COUPON_CODE" },
                "code": { "type": "string", "minLength": 1 } } } ] }""";

    /**
     * JSON Schema fragment for the optional {@code applicationMoment} (spec §3.6): declared
     * in C1, its effect delivered in C2.
     */
    String APPLICATION_MOMENT_SCHEMA = """
        { "enum": ["AT_TRIGGER", "AT_TOTAL"], "default": "AT_TOTAL" }""";

    /**
     * JSON Schema fragment for the optional {@code arbitration} block (spec §4.1): all
     * defaults reproduce the current behaviour — activation by data, never by code.
     */
    String ARBITRATION_SCHEMA = """
        { "type": "object", "additionalProperties": false,
          "properties": {
            "priority": { "type": "integer", "minimum": 0, "maximum": 1000, "default": 500 },
            "cumulable": { "type": "boolean", "default": true },
            "exclusionGroups": { "type": "array", "items": { "type": "string", "minLength": 1 } },
            "maxApplicationsPerTicket": { "type": "integer", "minimum": 1 },
            "maxApplicationsPerLine": { "type": "integer", "minimum": 1 },
            "consumesContributors": { "type": "boolean", "default": false } } }""";

    /**
     * Retrieves the {@link Basket} from the evaluation context.
     *
     * @param basketEvaluation the evaluation context containing the basket.
     * @param errorMessage     the error message to throw if the basket is null.
     * @return the validated Basket.
     * @throws IllegalStateException if the basket is null.
     */
    default Basket getBasket(BasketEvaluation basketEvaluation, String errorMessage) {
        Basket basket = basketEvaluation.getBasket();
        if (basket == null) {
            throw new IllegalStateException(errorMessage);
        }
        return basket;
    }

    /**
     * Retrieves a {@link Product} by its EAN code.
     *
     * @param produceEan    the EAN code of the product to retrieve.
     * @param errorMessage    the error message to throw if the product is not found.
     * @return the validated Product.
     * @throws IllegalStateException if the product is not found.
     */
    default Product getProduct(String produceEan, String errorMessage) {
        Product product = Product.findByEan(produceEan);
        if (product == null) {
            throw new IllegalStateException(String.format(
                    errorMessage, produceEan
            ));
        }
        return product;
    }

    /**
     * Retrieves offers by type for the given basket evaluation context.
     * <p>
     * This method fetches offers that match the provided type,
     * considering both direct store offers and offers associated with the store's groups.
     *
     * @param basketEvaluation the basket evaluation context containing store and group information.
     * @param type             the type of offers to retrieve.
     * @return a collection of matching offers.
     */
    default Collection<Offer> getOffers(BasketEvaluation basketEvaluation, String type) {
        java.time.LocalDateTime at = DateTimeProvider.now();
        Set<Offer> offers = new HashSet<>(Offer.findInForceByStoreAndType(basketEvaluation.getStore(), type, at));
        offers.addAll(Offer.findInForceByStoreGroupsAndType(basketEvaluation.getStoreGroups(), type, at));
        return offers;
    }

    /**
     * Retrieves offers by EANs and type for the given basket evaluation context.
     * <p>
     * This method fetches offers that match the provided EANs and type,
     * considering both direct store offers and offers associated with the store's groups.
     *
     * @param basketEvaluation the basket evaluation context containing store and group information.
     * @param eans             the collection of product EANs to search offers for.
     * @param type             the type of offers to retrieve.
     * @return a collection of matching offers.
     */
    default Collection<Offer> getOffers(BasketEvaluation basketEvaluation, Collection<String> eans, String type) {
        java.time.LocalDateTime at = DateTimeProvider.now();
        Set<Offer> offers = new HashSet<>(Offer.findInForceByEansAndStoreAndType(eans, basketEvaluation.getStore(), type, at));
        offers.addAll(Offer.findInForceByEansAndStoreGroupsAndType(eans, basketEvaluation.getStoreGroups(), type, at));
        return offers;
    }

    /**
     * Validates the offer specification JSON against a provided JSON schema and processes it if the validation is successful.
     * <p>
     * This method parses the offer specification, validates it against the provided schema.
     * If validation succeeds, the parsed {@link JsonNode} is passed to the provided
     * {@link Consumer} for further processing. If validation fails, an {@link IllegalArgumentException}
     * is thrown containing the validation error details.
     *
     * @param schemaSpecification the JSON schema definition (as a string or URI).
     * @param offerSpecification the offer specification JSON string to validate and process.
     * @param process            the consumer logic to execute on the parsed JSON node if validation passes.
     * @throws IllegalArgumentException if the JSON is invalid, validation fails, or a parsing error occurs.
     */
    /**
     * Offer-aware variant of {@link #processSpecification(String, String, Consumer)}: validates
     * the offer's specification and, on rejection, carries the offer code in the message —
     * {@code Error validating offer: [CODE] ...} — so a failing configuration is identifiable
     * among the store's offers. The canonical prefixes are preserved verbatim.
     *
     * @param schemaSpecification The factory's JSON schema.
     * @param offer               The configuration whose specification is validated.
     * @param process             The consumer invoked with the validated specification node.
     */
    default void processSpecification(String schemaSpecification, Offer offer, Consumer<JsonNode> process) {
        try {
            processSpecification(schemaSpecification, offer.specification, process);
        } catch (IllegalArgumentException e) {
            String prefix = "Error validating offer: ";
            String message = e.getMessage();
            if (message != null && message.startsWith(prefix)) {
                throw new IllegalArgumentException(prefix + "[" + offer.code + "] " + message.substring(prefix.length()), e);
            }
            throw new IllegalArgumentException("[" + offer.code + "] " + message, e);
        }
    }

    default void processSpecification(String schemaSpecification, String offerSpecification, Consumer<JsonNode> process) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            // 0. Inject the shared trigger / applicationMoment / arbitration fragments once,
            // centrally, into every offer/advantage schema (spec §3.5). The basket schema is
            // left untouched.
            String effectiveSchema = injectSharedFragments(mapper, schemaSpecification);
            // 1. Configure the factory for the desired schema version (V7, V2019-09, V2020-12, etc.)
            // Here we use Draft 7 (widely used).
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            // 2. Load the schema
            JsonSchema schema = factory.getSchema(effectiveSchema);
            // 3. Parse JSON content into JsonNode (Jackson)
            JsonNode jsonNode = mapper.readTree(offerSpecification);
            // 4. Perform validation
            Set<ValidationMessage> errors = schema.validate(jsonNode);
            // 5. Analyze results
            if (errors.isEmpty()) {
                // Cross-field trigger rules a schema cannot express (spec §3.8), rejected at
                // creation through the very same channel.
                validateTriggerCrossRules(jsonNode);
                process.accept(jsonNode);
            } else {
                // Print errors for debugging
                String message = errors.stream().map(err -> err.getMessage()).collect(Collectors.joining(", "));
                throw new IllegalArgumentException("Error validating offer: "+message);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Error parsing offer.", e);
        }
    }

    /**
     * Injects the shared {@code trigger}, {@code applicationMoment} and {@code arbitration}
     * fragments (spec §3.5, §4.1) into an offer/advantage schema, centrally and once.
     * <p>
     * This is the single funnel the spec requires: no factory schema is edited on its own,
     * and a future type inherits the blocks for free. The basket schema is deliberately left
     * untouched — it is validated through the same method but must not carry these offer-only
     * blocks. A schema without a top-level {@code properties} object is returned unchanged.
     *
     * @param mapper              the shared object mapper.
     * @param schemaSpecification the raw factory schema.
     * @return the schema augmented with the three optional blocks, or the input unchanged
     *         when it is the basket schema, is malformed, or carries no {@code properties}.
     */
    private String injectSharedFragments(ObjectMapper mapper, String schemaSpecification) {
        if (Basket.BASKET_SCHEMA.equals(schemaSpecification)) {
            return schemaSpecification;
        }
        try {
            JsonNode root = mapper.readTree(schemaSpecification);
            if (!(root instanceof ObjectNode rootObject)
                    || !(root.get("properties") instanceof ObjectNode properties)) {
                return schemaSpecification;
            }
            properties.set("trigger", mapper.readTree(TRIGGER_PROPERTY_SCHEMA));
            properties.set("applicationMoment", mapper.readTree(APPLICATION_MOMENT_SCHEMA));
            properties.set("arbitration", mapper.readTree(ARBITRATION_SCHEMA));
            ObjectNode definitions = root.get("definitions") instanceof ObjectNode existing
                    ? existing : mapper.createObjectNode();
            definitions.set("triggerCondition", mapper.readTree(TRIGGER_CONDITION_DEFINITION));
            rootObject.set("definitions", definitions);
            return mapper.writeValueAsString(rootObject);
        } catch (JsonProcessingException e) {
            // A malformed factory schema is a coding error, not a user input error: keep the
            // original so the downstream validation surfaces it exactly as it did before.
            return schemaSpecification;
        }
    }

    /**
     * Applies the trigger cross-field rules of spec §3.8 that a JSON Schema cannot express —
     * scope {@code ITEMS} requires {@code eans}, scope {@code TICKET} forbids them — by
     * parsing the trigger block through {@link Trigger#of}.
     *
     * @param specification the validated specification node.
     * @throws IllegalArgumentException through the existing "Error validating offer: "
     *                                  channel when a cross-field rule is violated.
     */
    private void validateTriggerCrossRules(JsonNode specification) {
        if (specification != null && specification.has("trigger")) {
            try {
                Trigger.of(specification.get("trigger"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Error validating offer: " + e.getMessage());
            }
        }
    }
}