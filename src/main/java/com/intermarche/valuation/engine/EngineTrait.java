package com.intermarche.valuation.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Set<Offer> offers = new HashSet<>(Offer.findByStoreAndType(basketEvaluation.getStore(), type));
        offers.addAll(Offer.findByStoreGroupsAndType(basketEvaluation.getStoreGroups(), type));
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
        Set<Offer> offers = new HashSet<>(Offer.findByEansAndStoreAndType(eans, basketEvaluation.getStore(), type));
        offers.addAll(Offer.findByEansAndStoreGroupsAndType(eans, basketEvaluation.getStoreGroups(), type));
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
    default void processSpecification(String schemaSpecification, String offerSpecification, Consumer<JsonNode> process) {
        try {
            // 1. Configure the factory for the desired schema version (V7, V2019-09, V2020-12, etc.)
            // Here we use Draft 7 (widely used).
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            // 2. Load the schema
            JsonSchema schema = factory.getSchema(schemaSpecification);
            // 3. Parse JSON content into JsonNode (Jackson)
            JsonNode jsonNode = new ObjectMapper().readTree(offerSpecification);
            // 4. Perform validation
            Set<ValidationMessage> errors = schema.validate(jsonNode);
            // 5. Analyze results
            if (errors.isEmpty()) {
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
}