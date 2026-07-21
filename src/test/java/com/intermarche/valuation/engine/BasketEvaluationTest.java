package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * Test class for {@link BasketEvaluation}.
 * <p>
 * Focuses on state management, item aggregation, consumption logic (pick),
 * and resolution of store context via mocked static repositories.
 */
@ExtendWith(MockitoExtension.class)
public class BasketEvaluationTest {

    private MockedStatic<Store> mockedStore;
    private MockedStatic<StoreGroup> mockedStoreGroup;

    @BeforeEach
    void setUp() {
        mockedStore = mockStatic(Store.class);
        mockedStoreGroup = mockStatic(StoreGroup.class);
    }

    @AfterEach
    void tearDown() {
        mockedStore.close();
        mockedStoreGroup.close();
    }

    // --------------------------------------------------
    // Constructor Tests
    // --------------------------------------------------

    /**
     * Tests the constructor with a valid basket containing a store code.
     * Verifies that Store and StoreGroups are resolved via static methods.
     */
    @Test
    void testConstructor_ResolvesStoreAndGroups() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";

        Store mockStore = new Store();
        mockStore.id = 1L;
        mockedStore.when(() -> Store.findByCode("STORE_01")).thenReturn(mockStore);

        Set<StoreGroup> mockGroups = new HashSet<>();
        StoreGroup group = new StoreGroup();
        group.id = 10L;
        mockGroups.add(group);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(mockStore)).thenReturn(mockGroups);

        // Act
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Assert
        assertNotNull(evaluation.getStore());
        assertEquals(mockStore, evaluation.getStore());
        assertNotNull(evaluation.getStoreGroups());
        assertEquals(1, evaluation.getStoreGroups().size());
        assertTrue(evaluation.getStoreGroups().contains(group));

        mockedStore.verify(() -> Store.findByCode("STORE_01"));
        mockedStoreGroup.verify(() -> StoreGroup.findAllStoreGroups(mockStore));
    }

    /**
     * Tests the constructor when the basket is null or store code is missing.
     * Verifies that Store remains null and StoreGroups is an empty set.
     */
    @Test
    void testConstructor_NullBasket_InitializesEmptyState() {
        // Act
        BasketEvaluation evaluation = new BasketEvaluation(null);

        // Assert
        assertNull(evaluation.getStore());
        assertNotNull(evaluation.getStoreGroups());
        assertTrue(evaluation.getStoreGroups().isEmpty());

        // Verify no DB interaction
        mockedStore.verify(() -> Store.findByCode(any()), never());
        mockedStoreGroup.verify(() -> StoreGroup.findAllStoreGroups(any()), never());
    }

    // --------------------------------------------------
    // feedFrom Tests
    // --------------------------------------------------

    /**
     * Tests {@link BasketEvaluation#feedFrom(Basket)} aggregation logic.
     * If the basket contains multiple lines with the same EAN, their quantities should be summed.
     */
    @Test
    void testFeedFrom_AggregatesDuplicateEans() {
        // Arrange
        Basket basket = new Basket();

        Basket.Item item1 = new Basket.Item();
        item1.lineId = 1;
        item1.produceEan = "111";
        item1.quantity = 2.0;

        Basket.Item item2 = new Basket.Item();
        item2.lineId = 2;
        item2.produceEan = "111"; // Same EAN
        item2.quantity = 3.0;

        Basket.Item item3 = new Basket.Item();
        item3.lineId = 3;
        item3.produceEan = "222"; // Different EAN
        item3.quantity = 1.0;

        basket.items = List.of(item1, item2, item3);

        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        evaluation.feedFrom(basket);

        // Assert
        assertEquals(2, evaluation.getToEvaluate().size()); // 2 distinct EANs

        Basket.Item aggregatedItem = evaluation.getToEvaluate().get("111");
        assertNotNull(aggregatedItem);
        assertEquals(5.0, aggregatedItem.quantity, 0.001); // 2.0 + 3.0
        // Note: lineId might be from the last processed item or first depending on implementation,
        // here we check quantity as it's the business critical aggregation.

        Basket.Item distinctItem = evaluation.getToEvaluate().get("222");
        assertNotNull(distinctItem);
        assertEquals(1.0, distinctItem.quantity, 0.001);
    }

    // --------------------------------------------------
    // pick Tests
    // --------------------------------------------------

    /**
     * Tests picking the exact available quantity.
     * The item should be removed from the map after the pick.
     */
    @Test
    void testPick_FullConsumption() {
        // Arrange
        Basket basket = new Basket();
        Basket.Item item = new Basket.Item();
        item.produceEan = "123";
        item.quantity = 5.0;
        basket.items = List.of(item);

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Basket.Item picked = evaluation.pick(5.0, "123");

        // Assert
        assertNotNull(picked);
        assertEquals("123", picked.produceEan);
        assertEquals(5.0, picked.quantity, 0.001);

        // Verify it's removed from working set
        assertNull(evaluation.getToEvaluate().get("123"));
    }

    /**
     * Tests picking a quantity smaller than available.
     * The item should remain in the map with the reduced quantity.
     */
    @Test
    void testPick_PartialConsumption() {
        // Arrange
        Basket basket = new Basket();
        Basket.Item item = new Basket.Item();
        item.produceEan = "123";
        item.quantity = 10.0;
        basket.items = List.of(item);

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Basket.Item picked = evaluation.pick(4.0, "123");

        // Assert
        assertNotNull(picked);
        assertEquals(4.0, picked.quantity, 0.001);

        // Verify remaining
        Basket.Item remaining = evaluation.getToEvaluate().get("123");
        assertNotNull(remaining);
        assertEquals(6.0, remaining.quantity, 0.001); // 10 - 4
    }

    /**
     * Tests picking a quantity larger than available.
     * Should clamp to the available quantity and consume the item entirely.
     */
    @Test
    void testPick_OverPick_ClampsToAvailable() {
        // Arrange
        Basket basket = new Basket();
        Basket.Item item = new Basket.Item();
        item.produceEan = "123";
        item.quantity = 2.0;
        basket.items = List.of(item);

        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Basket.Item picked = evaluation.pick(10.0, "123"); // Ask for 10

        // Assert
        assertNotNull(picked);
        assertEquals(2.0, picked.quantity, 0.001); // Should only get 2

        // Verify item is gone (fully consumed)
        assertNull(evaluation.getToEvaluate().get("123"));
    }

    /**
     * Tests picking an item that does not exist.
     */
    @Test
    void testPick_NotFound() {
        // Arrange
        Basket basket = new Basket();
        basket.items = List.of();
        BasketEvaluation evaluation = new BasketEvaluation(basket);
        evaluation.feedFrom(basket);

        // Act
        Basket.Item picked = evaluation.pick(1.0, "999");

        // Assert
        assertNull(picked);
    }

    /**
     * Tests pick with null inputs.
     */
    @Test
    void testPick_NullInputs() {
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());

        assertNull(evaluation.pick(null, "123"));
        assertNull(evaluation.pick(1.0, null));
    }

    // --------------------------------------------------
    // addAvailableToUpcell Tests
    // --------------------------------------------------

    /**
     * Tests adding items to the upcell map.
     * Verifies that duplicate EANs are aggregated.
     */
    @Test
    void testAddAvailableToUpcell_AggregatesItems() {
        // Arrange
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());

        Basket.Item item1 = new Basket.Item();
        item1.produceEan = "UP1";
        item1.quantity = 1.0;

        Basket.Item item2 = new Basket.Item();
        item2.produceEan = "UP1";
        item2.quantity = 2.0;

        // Act
        evaluation.addAvailableToUpcell(item1);
        evaluation.addAvailableToUpcell(item2);

        // Assert
        assertEquals(1, evaluation.getAvailableToUpcell().size());
        Basket.Item upcellItem = evaluation.getAvailableToUpcell().get("UP1");
        assertNotNull(upcellItem);
        assertEquals(3.0, upcellItem.quantity, 0.001); // 1 + 2
    }

    // --------------------------------------------------
    // Other Getters/Setters Tests
    // --------------------------------------------------

    /**
     * Tests setting and getting the total price.
     */
    @Test
    void testSetTotalPrice() {
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());
        AmountEvaluation total = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"));

        evaluation.setTotalPrice(total);

        assertEquals(total, evaluation.getTotalPrice());
    }

    // --------------------------------------------------
    // Tests spécifiques demandés
    // --------------------------------------------------

    /**
     * Tests {@link BasketEvaluation#feedFrom(Basket)} when basket.items is null.
     * <p>
     * Verifies that no exception is thrown and the toEvaluate map remains empty.
     */
    @Test
    void testFeedFrom_NullItems() {
        // Arrange
        Basket basket = new Basket();
        basket.items = null; // Explicitly null to test the branch
        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        evaluation.feedFrom(basket);

        // Assert
        assertNotNull(evaluation.getToEvaluate());
        assertTrue(evaluation.getToEvaluate().isEmpty());
    }

    /**
     * Tests {@link BasketEvaluation#getBasket()}.
     * Verifies that the method returns the basket instance provided during construction.
     */
    @Test
    void testGetBasket() {
        // Arrange
        Basket basket = new Basket();
        basket.customerCode = "CUST_001";
        basket.storeCode = "STORE_A";

        BasketEvaluation evaluation = new BasketEvaluation(basket);

        // Act
        Basket result = evaluation.getBasket();

        // Assert
        assertNotNull(result);
        assertEquals(basket, result);
        assertEquals("CUST_001", result.customerCode);
    }

    /**
     * Tests {@link BasketEvaluation#getOffers()}.
     * Verifies the collection is initialized and accessible.
     */
    @Test
    void testGetOffers() {
        // Arrange
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());

        // Act
        var offers = evaluation.getOffers();

        // Assert
        assertNotNull(offers);
        assertTrue(offers.isEmpty()); // Should be initialized as empty HashSet
        // Verify we can modify the returned collection
        OfferApplication mockOffer = new OfferApplication() {
            @Override
            public AmountEvaluation getAmount() {return null;}
            @Override
            public Collection<Basket.Item> getItems() {return List.of();}
            @Override
            public String getType() {return "";}
        };
        offers.add(mockOffer);
        assertEquals(1, offers.size());
    }

    /**
     * Tests {@link BasketEvaluation#getAdvantages()}.
     * Verifies the collection is initialized and accessible.
     */
    @Test
    void testGetAdvantages() {
        // Arrange
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());

        // Act
        var advantages = evaluation.getAdvantages();

        // Assert
        assertNotNull(advantages);
        assertTrue(advantages.isEmpty()); // Should be initialized as empty HashSet

        // Verify we can modify the returned collection
        AdvantageApplication mockAdvantage = new AdvantageApplication() {
            @Override
            public OfferApplication getOfferApplication() {
                return null;
            }
        };
        advantages.add(mockAdvantage);
        assertEquals(1, advantages.size());
    }

    /**
     * Tests {@link BasketEvaluation#addAvailableToUpcell(Basket.Item)} with a null item.
     * <p>
     * Verifies that the method handles null input gracefully without exceptions
     * and without modifying the availableToUpcell map.
     */
    @Test
    void testAddAvailableToUpcell_NullInput() {
        // Arrange
        BasketEvaluation evaluation = new BasketEvaluation(new Basket());

        // Ensure the map is initially empty
        assertTrue(evaluation.getAvailableToUpcell().isEmpty());

        // Act
        evaluation.addAvailableToUpcell(null);

        // Assert
        // Verify that the map remains empty and no exception was thrown
        assertTrue(evaluation.getAvailableToUpcell().isEmpty());
    }
}