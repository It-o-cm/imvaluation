package com.intermarche.valuation.engine;

import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Price;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Plain unit tests for {@link DisplayContext}: the once-per-call classification of a store's offers
 * (PRICING / BADGE / IGNORED), the served-offer views consulted by the engine, the per-product
 * badges, and the opaque price+offer fingerprint. The Panache finders are mocked statically — no
 * database.
 */
class DisplayContextTest {

    /**
     * The effective instant every context is built at.
     */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 6, 1, 0, 0);

    /**
     * Builds an offer with a type, code, specification and targeted EANs.
     *
     * @param code    the offer code.
     * @param type    the offer type.
     * @param spec    the specification JSON.
     * @param eans    the targeted EANs.
     * @param updated the last-modification instant, or null.
     * @return the offer.
     */
    private static Offer offer(String code, String type, String spec, List<String> eans, LocalDateTime updated) {
        Offer offer = new Offer();
        offer.code = code;
        offer.type = type;
        offer.specification = spec;
        offer.eans = new ArrayList<>(eans);
        offer.updatedAt = updated;
        return offer;
    }

    /**
     * Builds a store with a code.
     *
     * @param code the store code.
     * @return the store.
     */
    private static Store store(String code) {
        Store store = new Store();
        store.code = code;
        return store;
    }

    /**
     * Builds a context over the given store-wide and EAN-targeted offers, with the given prices for
     * the fingerprint, mocking every Panache finder.
     *
     * @param storeWide   the offers reachable store-wide.
     * @param eanTargeted the offers targeting the requested EANs.
     * @param prices      the in-force prices for the fingerprint.
     * @param store       the store.
     * @param eans        the requested EANs.
     * @return the built context.
     */
    private static DisplayContext build(List<Offer> storeWide, List<Offer> eanTargeted, List<Price> prices,
                                        Store store, List<String> eans) {
        try (MockedStatic<Offer> mockedOffer = mockStatic(Offer.class);
             MockedStatic<PanacheEntityBase> mockedBase = mockStatic(PanacheEntityBase.class)) {
            mockedOffer.when(() -> Offer.findInForceByStore(any(), any())).thenReturn(storeWide);
            mockedOffer.when(() -> Offer.findInForceByStoreGroups(any(), any())).thenReturn(new ArrayList<>());
            mockedOffer.when(() -> Offer.findInForceByEansAndStore(any(), any(), any())).thenReturn(eanTargeted);
            mockedOffer.when(() -> Offer.findInForceByEansAndStoreGroups(any(), any(), any())).thenReturn(new ArrayList<>());
            mockedBase.when(() -> PanacheEntityBase.list(anyString(), any(Store.class), any(LocalDateTime.class))).thenReturn(prices);
            return new DisplayContext(store, Set.<StoreGroup>of(), AT, eans);
        }
    }

    /**
     * A product markdown and a guard are PRICING (served to the engine, not badged); a ticket
     * discount is IGNORED (withheld from the engine, not badged).
     */
    @Test
    void classifiesPricingAndIgnored() {
        Offer guard = offer("G", "EGALIM_GUARD", "{}", List.of(), AT);
        Offer ticket = offer("T", "TICKET_DISCOUNT", "{\"discountType\":\"PERCENTAGE\",\"value\":10.0}", List.of(), AT);
        Offer markdown = offer("M", "NEW_PRICE_DISCOUNT", "{\"targets\":[{\"eans\":[\"E1\"],\"newPrice\":2.00}]}", List.of("E1"), AT);
        DisplayContext context = build(List.of(guard, ticket), List.of(markdown), new ArrayList<>(), store("0101"), List.of("E1"));
        assertEquals(List.of(guard), context.servedOffers("EGALIM_GUARD"), "the guard is served store-wide");
        assertTrue(context.servedOffers("TICKET_DISCOUNT").isEmpty(), "the ticket discount is withheld");
        assertEquals(List.of(markdown), context.servedOffers(List.of("E1"), "NEW_PRICE_DISCOUNT"), "the markdown is served for its EAN");
        assertTrue(context.badgesFor("E1").isEmpty(), "neither a guard nor a markdown is a badge");
    }

    /**
     * A basket-dependent offer by type (N+M) and a markdown carrying a MINIMUM_QUANTITY trigger are
     * both BADGE: served to the engine (so its potential-promotion machinery runs) AND surfaced as
     * badges.
     */
    @Test
    void classifiesBadgesByTypeAndByTrigger() {
        Offer nplusm = offer("N", "N+M", "{\"targetEans\":[\"E1\"],\"label\":\"2 for 1\",\"quantityToPay\":2}", List.of("E1"), AT);
        Offer minQty = offer("Q", "NEW_PRICE_DISCOUNT",
                "{\"targets\":[{\"eans\":[\"E2\"],\"newPrice\":1.00}],\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_QUANTITY\",\"eans\":[\"E2\"],\"threshold\":3}]}}",
                List.of("E2"), AT);
        DisplayContext context = build(new ArrayList<>(), List.of(nplusm, minQty), new ArrayList<>(), store("0101"), List.of("E1", "E2"));
        assertEquals(List.of(nplusm), context.servedOffers(List.of("E1"), "N+M"), "N+M is served so its upsell runs");
        assertEquals(List.of(minQty), context.servedOffers(List.of("E2"), "NEW_PRICE_DISCOUNT"), "the min-qty markdown is served");
        List<DisplayContext.OfferBadge> badgesE1 = context.badgesFor("E1");
        assertEquals(1, badgesE1.size());
        assertEquals("N+M", badgesE1.get(0).type);
        assertEquals("2 for 1", badgesE1.get(0).label, "the label comes from the specification");
        assertEquals("N", badgesE1.get(0).code);
        List<DisplayContext.OfferBadge> badgesE2 = context.badgesFor("E2");
        assertEquals(1, badgesE2.size());
        assertEquals("Q", badgesE2.get(0).label, "a specification without a label falls back to the code");
    }

    /**
     * A MINIMUM_AMOUNT trigger also classifies a markdown as a badge, and a malformed specification
     * reads as carrying no trigger (so it classifies by type) and as carrying no label.
     */
    @Test
    void minimumAmountTriggerBadgesAndMalformedSpecFallsBack() {
        Offer minAmount = offer("A", "AMOUNT_PER_ITEM_DISCOUNT",
                "{\"targets\":[{\"eans\":[\"E1\"],\"amountPerItem\":0.50}],\"trigger\":{\"conditions\":[{\"kind\":\"MINIMUM_AMOUNT\",\"scope\":\"TICKET\",\"threshold\":10}]}}",
                List.of("E1"), AT);
        Offer malformed = offer("B", "NEW_PRICE_DISCOUNT", "{not json", List.of("E2"), AT);
        DisplayContext context = build(new ArrayList<>(), List.of(minAmount, malformed), new ArrayList<>(), store("0101"), List.of("E1", "E2"));
        assertEquals("AMOUNT_PER_ITEM_DISCOUNT", context.badgesFor("E1").get(0).type, "a MINIMUM_AMOUNT trigger makes it a badge");
        assertTrue(context.badgesFor("E2").isEmpty(), "a malformed spec reads as no trigger, so the markdown stays PRICING");
        assertEquals(List.of(malformed), context.servedOffers(List.of("E2"), "NEW_PRICE_DISCOUNT"), "the malformed markdown is still served");
    }

    /**
     * An offer targeting none of the queried EANs is not served for them, and badges are ordered by
     * offer code with a null-safe comparator.
     */
    @Test
    void filtersByEanAndOrdersBadgesByCode() {
        Offer badgeZ = offer("Z", "N+M", "{\"label\":\"z\"}", List.of("E1"), AT);
        Offer badgeA = offer("A", "MIXED_BUNDLE", "{\"label\":\"a\"}", List.of("E1"), AT);
        Offer other = offer("O", "N+M", "{\"label\":\"o\"}", List.of("E9"), AT);
        DisplayContext context = build(new ArrayList<>(), List.of(badgeZ, badgeA, other), new ArrayList<>(), store("0101"), List.of("E1"));
        List<DisplayContext.OfferBadge> badges = context.badgesFor("E1");
        assertEquals(2, badges.size(), "only the offers targeting E1");
        assertEquals("A", badges.get(0).code, "badges are ordered by code");
        assertEquals("Z", badges.get(1).code);
        assertTrue(context.servedOffers(List.of("E1"), "N+M").contains(badgeZ), "E1 is served the N+M targeting it");
        assertFalse(context.servedOffers(List.of("E1"), "N+M").contains(other), "an offer targeting only E9 is not served for E1");
    }

    /**
     * Defensive classification branches: an offer with a null type is IGNORED (never served); a
     * trigger block without conditions, a condition of a non-minimum kind, and a condition with a
     * null kind all read as carrying no minimum trigger (so the markdown stays PRICING); a blank
     * specification label falls back to the offer code.
     */
    @Test
    void coversDefensiveClassificationBranches() {
        Offer typeNull = new Offer();
        typeNull.code = "TN";
        typeNull.type = null;
        typeNull.specification = "{}";
        typeNull.eans = new ArrayList<>();
        typeNull.updatedAt = AT;
        Offer noConditions = offer("TC", "NEW_PRICE_DISCOUNT", "{\"trigger\":{}}", List.of("E1"), AT);
        Offer otherKind = offer("OK", "NEW_PRICE_DISCOUNT",
                "{\"trigger\":{\"conditions\":[{\"kind\":\"COUPON_CODE\",\"code\":\"X\"}]}}", List.of("E1"), AT);
        Offer kindNull = offer("KN", "NEW_PRICE_DISCOUNT",
                "{\"trigger\":{\"conditions\":[{\"foo\":1}]}}", List.of("E1"), AT);
        Offer blankLabel = offer("BL", "N+M", "{\"label\":\"  \"}", List.of("E1"), AT);
        Offer nullSpecMarkdown = offer("NS", "NEW_PRICE_DISCOUNT", null, List.of("E1"), AT);
        Offer voucher = offer("IV", "IMMEDIATE_VOUCHER", "{\"value\":1.0}", List.of("E1"), AT);
        DisplayContext context = build(List.of(typeNull),
                List.of(noConditions, otherKind, kindNull, blankLabel, nullSpecMarkdown, voucher),
                new ArrayList<>(), store("0101"), List.of("E1"));
        assertTrue(context.servedOffers("EGALIM_GUARD").isEmpty(), "a null-type offer is never served");
        assertEquals(4, context.servedOffers(List.of("E1"), "NEW_PRICE_DISCOUNT").size(),
                "the four non-minimum-triggered markdowns (null spec included) stay PRICING and are served");
        assertTrue(context.servedOffers(List.of("E1"), "IMMEDIATE_VOUCHER").isEmpty(),
                "an EAN-targeted instrument grant is IGNORED: neither served nor badged");
        List<DisplayContext.OfferBadge> badges = context.badgesFor("E1");
        assertEquals(1, badges.size(), "only the N+M is a badge");
        assertEquals("BL", badges.get(0).code);
        assertEquals("BL", badges.get(0).label, "a blank label falls back to the code");
    }

    /**
     * A badge offer with a null specification labels by its code (no spec to read), and an offer
     * carrying no targeted EANs ({@code eans == null}) is never attached to a product.
     */
    @Test
    void nullSpecificationLabelsByCodeAndNullEansNeverTargets() {
        Offer nullSpec = offer("N1", "N+M", null, List.of("E1"), AT);
        Offer nullEans = new Offer();
        nullEans.code = "N2";
        nullEans.type = "N+M";
        nullEans.specification = "{}";
        nullEans.eans = null;
        nullEans.updatedAt = AT;
        DisplayContext context = build(new ArrayList<>(), List.of(nullSpec, nullEans), new ArrayList<>(), store("0101"), List.of("E1"));
        List<DisplayContext.OfferBadge> badges = context.badgesFor("E1");
        assertEquals(1, badges.size(), "only the badge that targets E1");
        assertEquals("N1", badges.get(0).code);
        assertEquals("N1", badges.get(0).label, "a null specification falls back to the code");
    }

    /**
     * The fingerprint is a non-empty opaque string, stable for identical inputs, and changes when an
     * in-force offer's or price's modification instant moves. Offers and prices with a null
     * modification instant are tolerated.
     */
    @Test
    void priceVersionIsStableAndMovesWithUpdates() {
        Offer o1 = offer("O1", "NEW_PRICE_DISCOUNT", "{\"targets\":[{\"eans\":[\"E1\"],\"newPrice\":2.00}]}", List.of("E1"), AT);
        Offer oNullDate = offer("O2", "EGALIM_GUARD", "{}", List.of(), null);
        Price priced = new Price();
        priced.updatedAt = LocalDateTime.of(2026, 5, 1, 0, 0);
        Price priceNullDate = new Price();
        priceNullDate.updatedAt = null;
        List<Price> prices = List.of(priced, priceNullDate);
        String first = build(List.of(oNullDate), List.of(o1), prices, store("0101"), List.of("E1")).priceVersion();
        String again = build(List.of(oNullDate), List.of(o1), prices, store("0101"), List.of("E1")).priceVersion();
        assertTrue(first.startsWith("pv-"), "opaque prefixed fingerprint: " + first);
        assertEquals(first, again, "stable for identical store/date/catalog");
        Offer o1Later = offer("O1", "NEW_PRICE_DISCOUNT", "{\"targets\":[{\"eans\":[\"E1\"],\"newPrice\":2.00}]}",
                List.of("E1"), LocalDateTime.of(2027, 1, 1, 0, 0));
        String moved = build(List.of(oNullDate), List.of(o1Later), prices, store("0101"), List.of("E1")).priceVersion();
        assertNotEquals(first, moved, "a moved offer modification instant changes the fingerprint");
    }
}
