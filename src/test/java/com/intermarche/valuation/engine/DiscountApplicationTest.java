package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link DiscountApplication}.
 * <p>
 * {@link DiscountApplication} is a pure SPI interface: it declares the single
 * abstract method {@link DiscountApplication#getDiscountAmount()} and inherits
 * from {@link AdvantageApplication}. It carries no executable code and no
 * branches of its own, so these tests exercise the interface contract through
 * a simple concrete implementation.
 */
public class DiscountApplicationTest {

    /**
     * Tests {@link DiscountApplication#getDiscountAmount()}.
     * <p>
     * Verifies that the discount evaluation carried by the application is
     * returned unchanged.
     */
    @Test
    void testGetDiscountAmount() {
        // Arrange
        AmountEvaluation discount = new AmountEvaluation(new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("0.2000"));
        TestDiscountApplication application = new TestDiscountApplication(new TestOfferApplication("PROMO_50"), discount);
        // Act
        AmountEvaluation result = application.getDiscountAmount();
        // Assert
        assertSame(discount, result);
    }

    /**
     * Tests {@link DiscountApplication#getDiscountAmount()} when no discount is set.
     * <p>
     * Verifies that a null discount evaluation is returned as-is.
     */
    @Test
    void testGetDiscountAmount_WhenNull() {
        // Arrange
        TestDiscountApplication application = new TestDiscountApplication(new TestOfferApplication("PROMO_50"), null);
        // Act
        AmountEvaluation result = application.getDiscountAmount();
        // Assert
        assertNull(result);
    }

    /**
     * Tests {@link DiscountApplication#getOfferApplication()}.
     * <p>
     * Verifies that the associated offer application is returned unchanged.
     */
    @Test
    void testGetOfferApplication() {
        // Arrange
        TestOfferApplication offer = new TestOfferApplication("PROMO_50");
        TestDiscountApplication application = new TestDiscountApplication(offer, new AmountEvaluation());
        // Act
        OfferApplication result = application.getOfferApplication();
        // Assert
        assertSame(offer, result);
    }

    /**
     * Tests the inherited default {@link AdvantageApplication#getOffer()} through a
     * {@link DiscountApplication} instance.
     * <p>
     * Verifies that the offer type is retrieved from the associated offer application.
     */
    @Test
    void testGetOffer() {
        // Arrange
        TestDiscountApplication application = new TestDiscountApplication(new TestOfferApplication("PROMO_50"), new AmountEvaluation());
        // Act
        String result = application.getOffer();
        // Assert
        assertEquals("PROMO_50", result);
    }

    /**
     * Tests the inherited default {@link AdvantageApplication#getOffer()} when the
     * offer application is null.
     * <p>
     * Verifies that a {@link NullPointerException} is thrown when no offer
     * application is associated.
     */
    @Test
    void testGetOffer_WhenOfferApplicationIsNull() {
        // Arrange
        TestDiscountApplication application = new TestDiscountApplication(null, new AmountEvaluation());
        // Act & Assert
        assertThrows(NullPointerException.class, application::getOffer);
    }

    // --------------------------------------------------
    // Simple Test Implementations
    // --------------------------------------------------

    /**
     * Simple implementation of {@link OfferApplication} for testing.
     */
    private static class TestOfferApplication implements OfferApplication {
        private final String type;

        /**
         * Creates a test offer application.
         *
         * @param type The offer type to report.
         */
        public TestOfferApplication(String type) {
            this.type = type;
        }

        /**
         * Returns the configured offer type.
         *
         * @return The offer type.
         */
        @Override
        public String getType() {
            return type;
        }

        /**
         * Returns a zeroed amount evaluation.
         *
         * @return An empty {@link AmountEvaluation}.
         */
        @Override
        public AmountEvaluation getAmount() {
            return new AmountEvaluation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /**
         * Returns an empty item collection.
         *
         * @return An empty collection.
         */
        @Override
        public Collection<Basket.Item> getItems() {
            return Collections.emptyList();
        }
    }

    /**
     * Simple implementation of {@link DiscountApplication} for testing.
     */
    private static class TestDiscountApplication implements DiscountApplication {
        private final OfferApplication offerApplication;
        private final AmountEvaluation discountAmount;

        /**
         * Creates a test discount application.
         *
         * @param offerApplication The associated offer application (may be null).
         * @param discountAmount   The discount evaluation to report (may be null).
         */
        public TestDiscountApplication(OfferApplication offerApplication, AmountEvaluation discountAmount) {
            this.offerApplication = offerApplication;
            this.discountAmount = discountAmount;
        }

        /**
         * Returns the configured offer application.
         *
         * @return The offer application (may be null).
         */
        @Override
        public OfferApplication getOfferApplication() {
            return offerApplication;
        }

        /**
         * Returns the configured discount evaluation.
         *
         * @return The discount evaluation (may be null).
         */
        @Override
        public AmountEvaluation getDiscountAmount() {
            return discountAmount;
        }
    }
}
