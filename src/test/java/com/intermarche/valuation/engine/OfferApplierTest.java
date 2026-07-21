package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.PriceUsage;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class for {@link OfferApplier}.
 * <p>
 * Tests the calculation logic for efficiency score, discount applier registration,
 * and the integration with basket evaluation context.
 */
@ExtendWith(MockitoExtension.class)
public class OfferApplierTest {

    // MockedStatic handle for AmountEvaluation utility calls
    private MockedStatic<AmountEvaluation> mockedAmountEvaluation;
    private MockedStatic<Store> mockedStore;
    private MockedStatic<StoreGroup> mockedStoreGroup;

    @Mock
    private AdvantageApplier discountApplier;

    @Mock
    private DiscountApplication discountApplication;

    @Mock
    private OfferApplication offerApplication;

    /**
     * Sets up the static mocks for {@link AmountEvaluation}, {@link Store}, and {@link StoreGroup}
     * before each test execution.
     */
    @BeforeEach
    void setUp() {
        mockedAmountEvaluation = mockStatic(AmountEvaluation.class);
        mockedStore = mockStatic(Store.class);
        mockedStoreGroup = mockStatic(StoreGroup.class);
    }

    /**
     * Closes the static mocks after each test execution to prevent memory leaks
     * and ensure test isolation.
     */
    @AfterEach
    void tearDown() {
        mockedAmountEvaluation.close();
        mockedStore.close();
        mockedStoreGroup.close();
    }

    // --------------------------------------------------
    // Helper to create concrete instance
    // --------------------------------------------------

    /**
     * Helper method to create a concrete implementation of {@link OfferApplier} for testing.
     * <p>
     * The abstract {@code apply} method is stubbed to return a controlled collection of
     * {@link OfferApplication} objects provided as a parameter.
     *
     * @param appsToReturn The collection of offer applications the stubbed apply method should return.
     * @return A concrete instance of OfferApplier.
     */
    private OfferApplier createConcreteApplier(Collection<OfferApplication> appsToReturn) {
        return new OfferApplier() {
            @Override
            public Collection<OfferApplication> apply(BasketEvaluation basketEvaluation) {
                return appsToReturn;
            }
        };
    }

    // --------------------------------------------------
    // Tests for Efficiency Score Logic
    // --------------------------------------------------

    /**
     * Tests {@link OfferApplier#computeEfficiencyScore(Basket)} when no discounts are applied.
     * <p>
     * Scenario:
     * <ul>
     *   <li>Reference Amount (Ref) = 120.00</li>
     *   <li>Total Amount (Total) = 120.00</li>
     *   <li>Total Discount = 0.00</li>
     * </ul>
     * Expected Score: (120 - 120 + 0) / 120 = 0.0
     */
    @Test
    void testComputeEfficiencyScore_NoDiscount() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_01";

        Store store = new Store();
        store.id = 1L;
        mockedStore.when(() -> Store.findByCode("STORE_01")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        Basket.Item item = new Basket.Item();
        item.produceEan = "123";

        // Setup Mocks for Amount calculations
        AmountEvaluation totalEval = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"));
        AmountEvaluation refEval = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"));

        when(offerApplication.getItems()).thenReturn(List.of(item));
        when(offerApplication.getAmount()).thenReturn(totalEval);

        // Mock the static call for reference amount
        mockedAmountEvaluation.when(() -> AmountEvaluation.getAmount(anyCollection(), eq(store), eq(PriceUsage.DEFAULT)))
                .thenReturn(refEval);

        OfferApplier applier = createConcreteApplier(List.of(offerApplication));

        // Act
        double score = applier.computeEfficiencyScore(basket);

        // Assert
        // (120 - 120 + 0) / 120 = 0
        assertEquals(0.0, score, 0.0001);
    }

    /**
     * Tests {@link OfferApplier#computeEfficiencyScore(Basket)} when active discounts are present.
     * <p>
     * Scenario:
     * <ul>
     *   <li>Reference Amount (Ref) = 120.00</li>
     *   <li>Total Amount (Total) = 100.00</li>
     *   <li>Total Discount = 20.00</li>
     * </ul>
     * Expected Score: (120 - 100 + 20) / 100 = 0.4
     */
    @Test
    void testComputeEfficiencyScore_WithDiscounts() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_02";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_02")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        Basket.Item item = new Basket.Item();

        // Setup Offer Application (Total Price = 100.00)
        AmountEvaluation totalEval = new AmountEvaluation(new BigDecimal("83.33"), new BigDecimal("100.00"), new BigDecimal("0.20"));
        when(offerApplication.getItems()).thenReturn(List.of(item));
        when(offerApplication.getAmount()).thenReturn(totalEval);

        // Setup Reference Price (Ref = 120.00)
        AmountEvaluation refEval = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"));
        mockedAmountEvaluation.when(() -> AmountEvaluation.getAmount(anyCollection(), eq(store), eq(PriceUsage.DEFAULT)))
                .thenReturn(refEval);

        // Setup Discount Application (Discount = 20.00)
        AmountEvaluation discountEval = new AmountEvaluation(new BigDecimal("16.66"), new BigDecimal("20.00"), new BigDecimal("0.20"));
        when(discountApplication.getDiscountAmount()).thenReturn(discountEval);

        // Configure the mock applier to return our discount application
        when(discountApplier.apply(any())).thenReturn(List.of(discountApplication));

        OfferApplier applier = createConcreteApplier(List.of(offerApplication));
        applier.registerDiscountApplier(discountApplier);

        // Act
        double score = applier.computeEfficiencyScore(basket);

        // Assert
        // (120 - 100 + 20) / 100 = 0.4
        assertEquals(0.4, score, 0.0001);

        // Verify the discount applier was actually invoked
        verify(discountApplier).apply(any(BasketEvaluation.class));
    }

    /**
     * Tests {@link OfferApplier#computeEfficiencyScore(Basket)} when an AdvantageApplication
     * is present in the list but is not an instance of {@link DiscountApplication}.
     * <p>
     * Verifies that the calculation loop correctly skips items that are not discounts,
     * resulting in a totalDiscountEvaluation of zero, even though advantages exist in the list.
     */
    @Test
    void testComputeEfficiencyScore_WithNonDiscountAdvantage() {
        // Arrange
        Basket basket = new Basket();
        basket.storeCode = "STORE_99";

        Store store = new Store();
        mockedStore.when(() -> Store.findByCode("STORE_99")).thenReturn(store);
        mockedStoreGroup.when(() -> StoreGroup.findAllStoreGroups(any())).thenReturn(Set.of());

        Basket.Item item = new Basket.Item();

        // Setup Offer Application (Total Price = 100.00)
        AmountEvaluation totalEval = new AmountEvaluation(new BigDecimal("83.33"), new BigDecimal("100.00"), new BigDecimal("0.20"));
        when(offerApplication.getItems()).thenReturn(List.of(item));
        when(offerApplication.getAmount()).thenReturn(totalEval);

        // Setup Reference Price (Ref = 120.00)
        AmountEvaluation refEval = new AmountEvaluation(new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("0.20"));
        mockedAmountEvaluation.when(() -> AmountEvaluation.getAmount(anyCollection(), eq(store), eq(PriceUsage.DEFAULT)))
                .thenReturn(refEval);

        // Setup AdvantageApplier that returns a generic AdvantageApplication (NOT a DiscountApplication)
        AdvantageApplication nonDiscountAdvantage = mock(AdvantageApplication.class);
        when(discountApplier.apply(any())).thenReturn(List.of(nonDiscountAdvantage));

        OfferApplier applier = createConcreteApplier(List.of(offerApplication));
        applier.registerDiscountApplier(discountApplier);

        // Act
        double score = applier.computeEfficiencyScore(basket);

        // Assert
        // Since the advantage is not a DiscountApplication, it is ignored.
        // Formula: (Ref - Total + 0) / Total
        // (120 - 100) / 100 = 0.2
        assertEquals(0.2, score, 0.0001);
    }

    // --------------------------------------------------
    // Tests for Getters/Setters & Registration
    // --------------------------------------------------

    /**
     * Tests the setter and getter for the efficiency score.
     * <p>
     * Verifies that the value set via {@link OfferApplier#setEfficiencyScore(double)}
     * is correctly returned by {@link OfferApplier#getEfficiencyScore()}.
     */
    @Test
    void testSetGetEfficiencyScore() {
        OfferApplier applier = createConcreteApplier(List.of());
        applier.setEfficiencyScore(0.85);
        assertEquals(0.85, applier.getEfficiencyScore(), 0.001);
    }

    /**
     * Tests the registration and retrieval of a single discount applier.
     * <p>
     * Verifies that the internal collection is initially empty,
     * and that a registered applier is correctly stored and retrieved.
     */
    @Test
    void testRegisterAndGetDiscountAppliers() {
        OfferApplier applier = createConcreteApplier(List.of());

        assertTrue(applier.getDiscountAppliers().isEmpty());

        applier.registerDiscountApplier(discountApplier);

        assertEquals(1, applier.getDiscountAppliers().size());
        assertTrue(applier.getDiscountAppliers().contains(discountApplier));
    }

    /**
     * Tests registering multiple discount appliers.
     * <p>
     * Verifies that the collection grows correctly and contains all registered appliers.
     */
    @Test
    void testRegisterMultipleDiscountAppliers() {
        AdvantageApplier applier2 = mock(AdvantageApplier.class);
        OfferApplier applier = createConcreteApplier(List.of());

        applier.registerDiscountApplier(discountApplier);
        applier.registerDiscountApplier(applier2);

        assertEquals(2, applier.getDiscountAppliers().size());
    }
}