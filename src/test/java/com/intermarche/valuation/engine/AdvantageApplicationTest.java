package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AdvantageApplication}.
 * <p>
 * Uses simple concrete implementations to test the default methods
 * defined in the interface.
 */
public class AdvantageApplicationTest {

    /**
     * Tests the default {@link AdvantageApplication#getOffer()} method.
     * <p>
     * Verifies that it correctly retrieves the type from the associated offer.
     */
    @Test
    void testGetOffer() {
        // Arrange
        TestOfferApplication offer = new TestOfferApplication("PROMO_50");
        TestAdvantageApplication advantage = new TestAdvantageApplication(offer);

        // Act
        String result = advantage.getOffer();

        // Assert
        assertEquals("PROMO_50", result);
    }

    /**
     * Tests the default {@link AdvantageApplication#getOffer()} method with a null offer.
     * <p>
     * Verifies that a {@link NullPointerException} is thrown when
     * no offer application is associated.
     */
    @Test
    void testGetOffer_WhenOfferApplicationIsNull() {
        // Arrange
        TestAdvantageApplication advantage = new TestAdvantageApplication(null);

        // Act & Assert
        assertThrows(NullPointerException.class, advantage::getOffer);
    }

    // --------------------------------------------------
    // Simple Test Implementations
    // --------------------------------------------------

    /**
     * Simple implementation of {@link OfferApplication} for testing.
     */
    private static class TestOfferApplication implements OfferApplication {
        private final String type;

        public TestOfferApplication(String type) {
            this.type = type;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public AmountEvaluation getAmount() {
            return new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        @Override
        public Collection<Basket.Item> getItems() {
            return Collections.emptyList();
        }
    }

    /**
     * Simple implementation of {@link AdvantageApplication} for testing.
     */
    private static class TestAdvantageApplication implements AdvantageApplication {
        private final OfferApplication offerApplication;

        public TestAdvantageApplication(OfferApplication offerApplication) {
            this.offerApplication = offerApplication;
        }

        @Override
        public OfferApplication getOfferApplication() {
            return offerApplication;
        }
    }
}