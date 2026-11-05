package com.intermarche.valuation.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain unit tests for the display data-transfer objects: the request, the entry and its nested
 * {@code UnitPrice}/{@code FloorGuard}, the {@code OfferBadge}, and the request-level exception.
 * These carry no logic beyond field assignment and the error-entry factory, asserted here to the
 * value.
 */
class DisplayDtoTest {

    /**
     * The request holds the store, date, EANs and pushed card data verbatim.
     */
    @Test
    void requestHoldsItsFields() {
        DisplayValuationRequest request = new DisplayValuationRequest();
        request.storeCode = "0101";
        request.valuationDate = "2026-06-01";
        request.eans = List.of("E1", "E2");
        request.cardNumber = "CARD";
        request.cardPromotions = List.of(new Basket.CardPromotion());
        assertEquals("0101", request.storeCode);
        assertEquals("2026-06-01", request.valuationDate);
        assertEquals(2, request.eans.size());
        assertEquals("CARD", request.cardNumber);
        assertEquals(1, request.cardPromotions.size());
    }

    /**
     * The error-entry factory sets the EAN, the error and the price version, leaving every
     * valuation field null.
     */
    @Test
    void errorEntryLeavesValuationFieldsNull() {
        DisplayValuationEntry entry = DisplayValuationEntry.error("E1", "unknown", "pv-1");
        assertEquals("E1", entry.ean);
        assertEquals("unknown", entry.error);
        assertEquals("pv-1", entry.priceVersion);
        assertNull(entry.basePrice);
        assertNull(entry.displayedPrice);
        assertNull(entry.immediateDiscount);
        assertNull(entry.unitPriceMeasure);
        assertNull(entry.floorGuard);
        assertNull(entry.offerBadges);
    }

    /**
     * A populated entry carries its money, measure, badge, floor and version fields.
     */
    @Test
    void populatedEntryHoldsItsFields() {
        DisplayValuationEntry entry = new DisplayValuationEntry();
        entry.ean = "E1";
        entry.basePrice = new AmountEvaluation(new BigDecimal("2.00"), new BigDecimal("2.40"), new BigDecimal("0.20"));
        entry.displayedPrice = new AmountEvaluation(new BigDecimal("1.50"), new BigDecimal("1.80"), new BigDecimal("0.20"));
        entry.immediateDiscount = new AmountEvaluation(new BigDecimal("0.50"), new BigDecimal("0.60"), new BigDecimal("0.20"));
        entry.unitPriceMeasure = new DisplayValuationEntry.UnitPrice(new BigDecimal("9.60"), "kg");
        entry.floorGuard = new DisplayValuationEntry.FloorGuard("SRP", new BigDecimal("1.80"));
        entry.vatRate = new BigDecimal("0.2000");
        entry.offerBadges = List.of(new DisplayContext.OfferBadge("N+M", "2 for 1", "OFF1"));
        entry.priceVersion = "pv-1";
        assertEquals(0, new BigDecimal("2.40").compareTo(entry.basePrice.amountIncludingTax));
        assertEquals(0, new BigDecimal("9.60").compareTo(entry.unitPriceMeasure.value));
        assertEquals("kg", entry.unitPriceMeasure.unit);
        assertEquals("SRP", entry.floorGuard.type);
        assertEquals(0, new BigDecimal("1.80").compareTo(entry.floorGuard.floorPrice));
        assertEquals("N+M", entry.offerBadges.get(0).type);
        assertEquals("2 for 1", entry.offerBadges.get(0).label);
        assertEquals("OFF1", entry.offerBadges.get(0).code);
    }

    /**
     * The no-argument constructors used by the JSON deserializer leave their fields null.
     */
    @Test
    void noArgConstructorsLeaveFieldsNull() {
        DisplayValuationEntry.UnitPrice unit = new DisplayValuationEntry.UnitPrice();
        DisplayValuationEntry.FloorGuard floor = new DisplayValuationEntry.FloorGuard();
        DisplayContext.OfferBadge badge = new DisplayContext.OfferBadge();
        assertNull(unit.value);
        assertNull(unit.unit);
        assertNull(floor.type);
        assertNull(floor.floorPrice);
        assertNull(badge.type);
        assertNull(badge.label);
        assertNull(badge.code);
    }

    /**
     * The request-level exception carries its status and message.
     */
    @Test
    void exceptionCarriesStatusAndMessage() {
        DisplayValuationException exception = new DisplayValuationException(400, "bad");
        assertEquals(400, exception.getStatus());
        assertEquals("bad", exception.getMessage());
    }
}
