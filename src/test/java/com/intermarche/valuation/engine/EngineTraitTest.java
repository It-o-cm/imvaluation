package com.intermarche.valuation.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test class for {@link EngineTrait}.
 * <p>
 * Tests the default validation and utility methods defined in the engine trait.
 */
@ExtendWith(MockitoExtension.class)
public class EngineTraitTest {

    // MockedStatic for Panache calls (Repository)
    private MockedStatic<Product> mockedProduct;
    private MockedStatic<Offer> mockedOffer;

    @BeforeEach
    void setUp() {
        mockedProduct = mockStatic(Product.class);
        mockedOffer = mockStatic(Offer.class);
    }

    @AfterEach
    void tearDown() {
        mockedProduct.close();
        mockedOffer.close();
    }

    // Helper to instantiate the interface (since it is an interface)
    private EngineTrait createTrait() {
        return new EngineTrait() {};
    }

    // --------------------------------------------------
    // Tests for getBasket
    // --------------------------------------------------

    /**
     * Tests {@link EngineTrait#getBasket(BasketEvaluation, String)} with a valid basket.
     */
    @Test
    void testGetBasket_Valid() {
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";
        basket.items = List.of(new Basket.Item());
        BasketEvaluation eval = mock(BasketEvaluation.class);
        when(eval.getBasket()).thenReturn(basket);
        EngineTrait trait = createTrait();
        Basket result = trait.getBasket(eval, "Error");
        assertNotNull(result);
        assertEquals("STORE_01", result.storeCode);
    }

    /**
     * Tests {@link EngineTrait#getBasket(BasketEvaluation, String)} when basket is null.
     */
    @Test
    void testGetBasket_NullBasket() {
        BasketEvaluation eval = mock(BasketEvaluation.class);
        when(eval.getBasket()).thenReturn(null);
        EngineTrait trait = createTrait();
        assertThrows(IllegalStateException.class, () -> {
            trait.getBasket(eval, "Basket is null");
        });
    }

    // --------------------------------------------------
    // Tests for getProduct
    // --------------------------------------------------

    /**
     * Tests {@link EngineTrait#getProduct(String, String)} when product is found.
     */
    @Test
    void testGetProduct_Found() {
        String ean = "1234567890123";
        Product product = new Product();
        product.ean = ean;
        mockedProduct.when(() -> Product.findByEan(ean)).thenReturn(product);
        EngineTrait trait = createTrait();
        Product result = trait.getProduct(ean, "Error: %s");
        assertNotNull(result);
        assertEquals(ean, result.ean);
    }

    /**
     * Tests {@link EngineTrait#getProduct(String, String)} when product is not found (null).
     */
    @Test
    void testGetProduct_NotFound() {
        String ean = "9999999999999";
        mockedProduct.when(() -> Product.findByEan(ean)).thenReturn(null);
        EngineTrait trait = createTrait();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            trait.getProduct(ean, "Product %s not found");
        });
        assertTrue(ex.getMessage().contains(ean));
        assertTrue(ex.getMessage().contains("not found"));
    }

    // --------------------------------------------------
    // Tests for getOffers(Context, Type)
    // --------------------------------------------------

    /**
     * Tests {@link EngineTrait#getOffers(BasketEvaluation, String)}.
     * Verifies that offers from both Store and StoreGroups are combined.
     */
    @Test
    void testGetOffers_ByStoreAndGroups() {
        // Setup Context
        Store store = new Store();
        store.id = 1L;
        StoreGroup group = new StoreGroup();
        group.id = 10L;
        BasketEvaluation eval = mock(BasketEvaluation.class);
        when(eval.getStore()).thenReturn(store);
        when(eval.getStoreGroups()).thenReturn(Set.of(group));
        // Setup Data
        Offer storeOffer = new Offer();
        storeOffer.id = 100L;
        storeOffer.type = "PROMO";
        Offer groupOffer = new Offer();
        groupOffer.id = 200L;
        groupOffer.type = "PROMO";
        // Mock Repository Calls
        mockedOffer.when(() -> Offer.findByStoreAndType(store, "PROMO"))
                .thenReturn(List.of(storeOffer));
        mockedOffer.when(() -> Offer.findByStoreGroupsAndType(Set.of(group), "PROMO"))
                .thenReturn(List.of(groupOffer));
        EngineTrait trait = createTrait();
        var result = trait.getOffers(eval, "PROMO");
        // Assert
        assertEquals(2, result.size());
        assertTrue(result.contains(storeOffer));
        assertTrue(result.contains(groupOffer));
        mockedOffer.verify(() -> Offer.findByStoreAndType(store, "PROMO"));
        mockedOffer.verify(() -> Offer.findByStoreGroupsAndType(Set.of(group), "PROMO"));
    }

    // --------------------------------------------------
    // Tests for getOffers(Context, Eans, Type)
    // --------------------------------------------------

    /**
     * Tests {@link EngineTrait#getOffers(BasketEvaluation, Collection, String)}.
     * Verifies that offers filtered by EANs for Store and Groups are combined.
     */
    @Test
    void testGetOffers_ByEansStoreAndGroups() {
        // Setup Context
        Store store = new Store();
        store.id = 1L;
        StoreGroup group = new StoreGroup();
        group.id = 10L;
        BasketEvaluation eval = mock(BasketEvaluation.class);
        when(eval.getStore()).thenReturn(store);
        when(eval.getStoreGroups()).thenReturn(Set.of(group));
        List<String> eans = List.of("111", "222");
        // Setup Data
        Offer storeOffer = new Offer();
        storeOffer.id = 100L;
        Offer groupOffer = new Offer();
        groupOffer.id = 200L;
        // Mock Repository Calls
        mockedOffer.when(() -> Offer.findByEansAndStoreAndType(eans, store, "DISCOUNT"))
                .thenReturn(List.of(storeOffer));
        mockedOffer.when(() -> Offer.findByEansAndStoreGroupsAndType(eans, Set.of(group), "DISCOUNT"))
                .thenReturn(List.of(groupOffer));
        EngineTrait trait = createTrait();
        var result = trait.getOffers(eval, eans, "DISCOUNT");
        // Assert
        assertEquals(2, result.size());
        assertTrue(result.contains(storeOffer));
        assertTrue(result.contains(groupOffer));
    }

    /**
     * Tests {@link EngineTrait#getOffers(BasketEvaluation, Collection, String)} with no results.
     */
    @Test
    void testGetOffers_NoResults() {
        Store store = new Store();
        store.id = 1L;
        BasketEvaluation eval = mock(BasketEvaluation.class);
        when(eval.getStore()).thenReturn(store);
        when(eval.getStoreGroups()).thenReturn(Collections.emptySet());
        mockedOffer.when(() -> Offer.findByStoreAndType(store, "NONE"))
                .thenReturn(Collections.emptyList());
        mockedOffer.when(() -> Offer.findByStoreGroupsAndType(anySet(), eq("NONE")))
                .thenReturn(Collections.emptyList());
        EngineTrait trait = createTrait();
        var result = trait.getOffers(eval, "NONE");
        assertTrue(result.isEmpty());
    }

    // --------------------------------------------------
    // Tests for processSpecification
    // --------------------------------------------------

    /**
     * Tests {@link EngineTrait#processSpecification} with a valid schema and valid JSON.
     * <p>
     * Expectation: The consumer is invoked with the parsed JsonNode.
     */
    @Test
    void testProcessSpecification_Success() {
        EngineTrait trait = createTrait();
        // Simple schema: Object with a string property "code"
        String schema = "{ \"type\": \"object\", \"properties\": { \"code\": { \"type\": \"string\" } } }";
        // Valid JSON matching the schema
        String json = "{ \"code\": \"TEST_123\" }";

        Consumer<JsonNode> consumer = mock(Consumer.class);

        // Act
        trait.processSpecification(schema, json, consumer);

        // Assert
        verify(consumer).accept(any(JsonNode.class));
    }

    /**
     * Tests {@link EngineTrait#processSpecification} when the JSON fails schema validation.
     * <p>
     * Expectation: Throws {@link IllegalArgumentException} with validation error details.
     */
    @Test
    void testProcessSpecification_ValidationError() {
        EngineTrait trait = createTrait();
        // Schema expects "code" to be a string
        String schema = "{ \"type\": \"object\", \"properties\": { \"code\": { \"type\": \"string\" } } }";
        // JSON provides "code" as an integer (invalid)
        String json = "{ \"code\": 123 }";

        Consumer<JsonNode> consumer = mock(Consumer.class);

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            trait.processSpecification(schema, json, consumer);
        });

        assertTrue(ex.getMessage().contains("Error validating offer"));
        // The consumer should not be called
        verify(consumer, never()).accept(any());
    }

    /**
     * Tests {@link EngineTrait#processSpecification} when the offer specification is malformed JSON.
     * <p>
     * Expectation: Throws {@link IllegalArgumentException} indicating a parsing error.
     */
    @Test
    void testProcessSpecification_JsonParseException() {
        EngineTrait trait = createTrait();
        String schema = "{ \"type\": \"object\" }";
        // Malformed JSON (missing closing brace)
        String json = "{ \"code\": 123 ";

        Consumer<JsonNode> consumer = mock(Consumer.class);

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            trait.processSpecification(schema, json, consumer);
        });

        assertTrue(ex.getMessage().contains("Error parsing offer"));
        verify(consumer, never()).accept(any());
    }

    /**
     * Tests {@link EngineTrait#processSpecification} when the JSON is valid but the consumer throws an exception.
     * <p>
     * Expectation: The exception from the consumer propagates.
     */
    @Test
    void testProcessSpecification_ConsumerThrowsException() {
        EngineTrait trait = createTrait();
        String schema = "{ \"type\": \"object\" }";
        String json = "{ \"key\": \"value\" }";

        Consumer<JsonNode> failingConsumer = node -> {
            throw new RuntimeException("Consumer logic failed");
        };

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            trait.processSpecification(schema, json, failingConsumer);
        });

        assertEquals("Consumer logic failed", ex.getMessage());
    }

}