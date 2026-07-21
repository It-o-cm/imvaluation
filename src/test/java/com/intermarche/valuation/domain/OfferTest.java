package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration test class for {@link Offer}.
 * <p>
 * Uses {@code @QuarkusTest} for application context.
 * Splits testing into:
 * <ul>
 *   <li><b>Business Logic:</b> In-memory tests for JSON parsing, EAN extraction, and Checksum calculation.</li>
 *   <li><b>Repository Logic:</b> Transactional tests for database queries and persistence.</li>
 * </ul>
 */
@QuarkusTest
class OfferTest {

    @Inject
    EntityManager em;

    // --------------------------------------------------
    // Business Logic Tests (No Database needed)
    // --------------------------------------------------

    /**
     * Tests {@link Offer#onCreate()} extracts a single EAN.
     * <p>
     * Verifies that when the specification contains a key ending with "ean" (singular)
     * with a text value, that value is added to the {@code eans} list.
     */
    @Test
    void onCreate_shouldExtractSingleEan_fromSingularKey() {
        Offer offer = new Offer();
        offer.specification = "{\"productEan\": \"1234567890123\"}";
        offer.onCreate();
        assertEquals(1, offer.eans.size());
        assertTrue(offer.eans.contains("1234567890123"));
    }

    /**
     * Tests {@link Offer#onCreate()} extracts multiple EANs.
     * <p>
     * Verifies that when the specification contains a key ending with "eans" (plural)
     * with an array of values, all values are added to the {@code eans} list.
     */
    @Test
    void onCreate_shouldExtractMultipleEans_fromPluralKey() {
        Offer offer = new Offer();
        offer.specification = "{\"productEans\": [\"1111111111111\", \"2222222222222\"]}";
        offer.onCreate();
        assertEquals(2, offer.eans.size());
        assertTrue(offer.eans.contains("1111111111111"));
        assertTrue(offer.eans.contains("2222222222222"));
    }

    /**
     * Tests {@link Offer#onCreate()} handles null specification.
     * <p>
     * Verifies that no exception is thrown and the EAN list remains empty.
     */
    @Test
    void onCreate_shouldHandleNullSpecification() {
        Offer offer = new Offer();
        offer.specification = null;
        offer.onCreate();
        assertEquals(0, offer.eans.size());
    }

    /**
     * Tests {@link Offer#onCreate()} handles blank specification.
     * <p>
     * Verifies that blank JSON strings do not trigger extraction.
     */
    @Test
    void onCreate_shouldHandleBlankSpecification() {
        Offer offer = new Offer();
        offer.specification = "   ";
        offer.onCreate();
        assertEquals(0, offer.eans.size());
    }

    /**
     * Tests {@link Offer#onCreate()} throws a RuntimeException for invalid JSON.
     * <p>
     * Ensures that malformed JSON is caught and wrapped in a RuntimeException.
     */
    @Test
    void onCreate_shouldThrowRuntimeException_forInvalidJson() {
        Offer offer = new Offer();
        offer.code = "BAD";
        offer.specification = "{ invalid }";
        assertThrows(RuntimeException.class, offer::onCreate);
    }

    /**
     * Tests {@link Offer#onCreate()} ignores numeric EAN values.
     * <p>
     * Verifies that keys ending with "ean" with numeric values are ignored
     * (branch where {@code valueNode.isTextual()} is false).
     */
    @Test
    void onCreate_shouldIgnoreNumericEanValue() {
        Offer offer = new Offer();
        offer.specification = "{\"productEan\": 123456}";
        offer.onCreate();
        assertEquals(0, offer.eans.size());
    }

    /**
     * Tests {@link Offer#onCreate()} filters non-textual items in EAN arrays.
     * <p>
     * Verifies that if an "eans" array contains non-string items, they are ignored.
     */
    @Test
    void onCreate_shouldFilterNonTextualItemsInArray() {
        Offer offer = new Offer();
        offer.specification = "{\"productEans\": [123456, \"valid-ean\", 789]}";
        offer.onCreate();
        assertEquals(1, offer.eans.size());
        assertTrue(offer.eans.contains("valid-ean"));
    }

    /**
     * Tests {@link Offer#onCreate()} handles deeply nested objects.
     * <p>
     * Verifies recursive extraction works correctly for nested structures.
     */
    @Test
    void onCreate_shouldHandleDeeplyNestedObjects() {
        Offer offer = new Offer();
        offer.specification = "{\"level1\": { \"level2\": { \"nestedEan\": \"7777777777777\" } } }";
        offer.onCreate();
        assertEquals(1, offer.eans.size());
        assertTrue(offer.eans.contains("7777777777777"));
    }

    /**
     * Tests {@link Offer#onUpdate()} refreshes the EAN list.
     * <p>
     * Ensures that updating an offer recalculates the EANs from the specification.
     */
    @Test
    void onUpdate_shouldExtractEans() {
        Offer offer = new Offer();
        offer.eans.add("999");
        offer.specification = "{\"newEan\": \"1111111111111\"}";
        offer.onUpdate();
        assertEquals(1, offer.eans.size());
        assertFalse(offer.eans.contains("999"));
        assertTrue(offer.eans.contains("1111111111111"));
    }

    /**
     * Tests {@link Offer#onCreate()} handles case-insensitive keys.
     */
    @Test
    void onCreate_shouldExtractEans_caseInsensitiveKey() {
        Offer offer = new Offer();
        offer.specification = "{\"PRODUCT_EAN\": \"9999999999999\"}";
        offer.onCreate();
        assertTrue(offer.eans.contains("9999999999999"));
    }

    /**
     * Tests {@link Offer#onCreate()} clears existing EANs before updating.
     * <p>
     * Ensures that stale EANs are removed when the specification changes.
     */
    @Test
    void onCreate_shouldClearOldEans_beforeUpdating() {
        Offer offer = new Offer();
        offer.eans.add("0000000000000");
        offer.specification = "{\"newEan\": \"1111111111111\"}";
        offer.onCreate();
        assertEquals(1, offer.eans.size());
        assertTrue(offer.eans.contains("1111111111111"));
    }

    /**
     * Tests {@link Offer#getChecksum()} changes when basic fields change.
     */
    @Test
    void getChecksum_shouldChange_withBasicFields() {
        Offer offer = new Offer();
        offer.code = "CODE_1";
        offer.type = "TYPE_A";
        offer.specification = "{}";
        int checksum1 = offer.getChecksum();
        offer.code = "CODE_2";
        int checksum2 = offer.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    /**
     * Tests {@link Offer#getChecksum()} includes linked stores.
     */
    @Test
    void getChecksum_shouldIncludeStores() {
        Offer offer = new Offer();
        offer.code = "CODE";
        offer.type = "TYPE";
        offer.stores = new HashSet<>();
        int checksum1 = offer.getChecksum();
        Store testStore = new Store();
        testStore.code = "STORE_1";
        offer.stores.add(testStore);
        int checksum2 = offer.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    /**
     * Tests {@link Offer#getChecksum()} includes linked store groups.
     */
    @Test
    void getChecksum_shouldIncludeStoreGroups() {
        Offer offer = new Offer();
        offer.code = "CODE";
        offer.type = "TYPE";
        offer.storeGroups = new HashSet<>();
        int checksum1 = offer.getChecksum();
        StoreGroup testGroup = new StoreGroup();
        testGroup.code = "GROUP_A";
        offer.storeGroups.add(testGroup);
        int checksum2 = offer.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Database / Repository Query Tests (DB required)
    // --------------------------------------------------

    /**
     * Tests {@link Offer#findByCode(String)}.
     * <p>
     * Verifies that an offer can be retrieved by its unique code.
     */
    @Test
    @TestTransaction
    void findByCode_shouldReturnCorrectOffer() {
        Store testStore = new Store();
        testStore.code = "S_TEST";
        testStore.name = "Test Store Name";
        testStore.address = new Adresse();
        testStore.persist();
        Offer offer = new Offer();
        offer.code = "PROMO_123";
        offer.type = "PROMO";
        offer.specification = "{}";
        offer.persist();
        em.flush();
        Offer result = Offer.findByCode("PROMO_123");
        assertNotNull(result);
        assertEquals("PROMO_123", result.code);
    }

    /**
     * Tests {@link Offer#findByStoreAndType(Store, String)}.
     * <p>
     * Verifies filtering by store and offer type.
     */
    @Test
    @TestTransaction
    void findByStoreAndType_shouldFilterCorrectly() {
        Store testStore = new Store();
        testStore.code = "S_TEST";
        testStore.name = "Test Store Name";
        testStore.address = new Adresse();
        testStore.persist();
        Offer targetOffer = new Offer();
        targetOffer.code = "TARGET";
        targetOffer.type = "DISCOUNT";
        targetOffer.specification = "{}";
        targetOffer.stores.add(testStore);
        targetOffer.persist();
        Offer otherOffer = new Offer();
        otherOffer.code = "OTHER";
        otherOffer.type = "OTHER_TYPE";
        otherOffer.specification = "{}";
        otherOffer.stores.add(testStore);
        otherOffer.persist();
        em.flush();
        List<Offer> results = Offer.findByStoreAndType(testStore, "DISCOUNT");
        assertEquals(1, results.size());
        assertEquals("TARGET", results.get(0).code);
    }

    /**
     * Tests {@link Offer#findByStoreGroupsAndType(Collection, String)}.
     * <p>
     * Verifies filtering by store groups and offer type.
     */
    @Test
    @TestTransaction
    void findByStoreGroupsAndType_shouldFilterCorrectly() {
        StoreGroup testGroup = new StoreGroup();
        testGroup.code = "G_TEST";
        testGroup.name = "Test Group Name";
        testGroup.persist();
        Offer offer = new Offer();
        offer.code = "GROUP_OFFER";
        offer.type = "PROMO";
        offer.specification = "{}";
        offer.storeGroups.add(testGroup);
        offer.persist();
        em.flush();
        List<Offer> results = Offer.findByStoreGroupsAndType(Set.of(testGroup), "PROMO");
        assertEquals(1, results.size());
        assertEquals("GROUP_OFFER", results.get(0).code);
    }

    /**
     * Tests {@link Offer#findByEansAndStoreAndType(Collection, Store, String)}.
     * <p>
     * Verifies complex filtering by EAN list, store, and type.
     */
    @Test
    @TestTransaction
    void findByEansAndStoreAndType_shouldFilterCorrectly() {
        Store testStore = new Store();
        testStore.code = "S_TEST";
        testStore.name = "Test Store Name";
        testStore.address = new Adresse();
        testStore.persist();
        Offer offer = new Offer();
        offer.code = "EAN_OFFER";
        offer.type = "FLASH";
        offer.specification = "{\"productEan\": \"1234567890123\"}";
        offer.stores.add(testStore);
        offer.persist();
        em.flush();
        List<Offer> results = Offer.findByEansAndStoreAndType(
                List.of("1234567890123"), testStore, "FLASH"
        );
        assertEquals(1, results.size());
        assertEquals("EAN_OFFER", results.get(0).code);
    }

    /**
     * Tests {@link Offer#findByEansAndStoreGroupsAndType(Collection, Collection, String)}.
     * <p>
     * Verifies complex filtering by EAN list, store groups, and type.
     */
    @Test
    @TestTransaction
    void findByEansAndStoreGroupsAndType_shouldFilterCorrectly() {
        StoreGroup testGroup = new StoreGroup();
        testGroup.code = "G_TEST";
        testGroup.name = "Test Group Name";
        testGroup.persist();

        Offer offer = new Offer();
        offer.code = "COMPLEX_OFFER";
        offer.type = "SALE";
        offer.specification = "{\"productEan\": \"9999999999999\"}";
        offer.storeGroups.add(testGroup);
        offer.persist();
        em.flush();
        List<Offer> results = Offer.findByEansAndStoreGroupsAndType(
                List.of("9999999999999"), Set.of(testGroup), "SALE"
        );
        assertEquals(1, results.size());
        assertEquals("COMPLEX_OFFER", results.get(0).code);
    }
}