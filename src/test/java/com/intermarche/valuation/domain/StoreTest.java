package com.intermarche.valuation.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified integration test class for {@link Store}.
 * <p>
 * Uses {@code @QuarkusTest} for application context.
 * Splits testing into:
 * <ul>
 *   <li><b>Business Logic:</b> Tests for {@code getChecksum()}.</li>
 *   <li><b>Repository Logic:</b> Transactional tests for persistence and lookups.</li>
 * </ul>
 */
@QuarkusTest
public class StoreTest {

    @Inject
    EntityManager em;

    /**
     * Creates a valid {@link Adresse} entity for testing.
     *
     * @return A valid {@link Adresse} instance.
     */
    private Adresse createAdresse() {
        Adresse adresse = new Adresse();
        adresse.streetLine1 = "10 Avenue des Champs";
        adresse.city = "Paris";
        adresse.postalCode = "75008";
        adresse.country = "France";
        return adresse;
    }

    // --------------------------------------------------
    // Business Logic Tests
    // --------------------------------------------------

    /**
     * Tests {@link Store#getChecksum()} calculation.
     * <p>
     * Verifies that the checksum changes when the address checksum changes.
     */
    @Test
    void getChecksum_shouldIncludeAddressFields() {
        Store store = new Store();
        store.code = "S1";
        store.name = "Paris Centre";
        store.address = createAdresse();
        int checksum1 = store.getChecksum();
        // Modify address (which modifies address checksum)
        store.address.postalCode = "69001";
        int checksum2 = store.getChecksum();
        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Database / Repository Query Tests
    // --------------------------------------------------

    /**
     * Tests that {@link Store} can be persisted successfully with valid data.
     */
    @Test
    @TestTransaction
    void persist_shouldSucceed_withValidData() {
        Store store = new Store();
        store.code = "S001";
        store.name = "Intermarché Paris";
        store.address = createAdresse();
        store.persist();
        em.flush();
        assertNotNull(store.id);
    }

    /**
     * Tests persistence validation: Code is mandatory.
     * <p>
     * Note: We call {@code em.flush()} to trigger validation immediately.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifCodeIsNull() {
        Store store = new Store();
        store.code = null;
        store.name = "Test Store";
        store.address = createAdresse();
        assertThrows(ConstraintViolationException.class, () -> {
            store.persist();
            em.flush();
        });
    }

    /**
     * Tests persistence validation: Name is mandatory.
     * <p>
     * Note: We call {@code em.flush()} to trigger validation immediately.
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifNameIsNull() {
        Store store = new Store();
        store.code = "S002";
        store.name = null;
        store.address = createAdresse();
        assertThrows(ConstraintViolationException.class, () -> {
            store.persist();
            em.flush();
        });
    }

    /**
     * Tests persistence constraint: Code is unique.
     * <p>
     * Tries to insert two stores with the same code.
     * The second insert should fail (ConstraintViolationException or PersistenceException).
     */
    @Test
    @TestTransaction
    void persist_shouldThrowConstraintViolation_ifCodeIsDuplicate() {
        Store store1 = new Store();
        store1.code = "DUPLICATE";
        store1.name = "Store One";
        store1.address = createAdresse();
        store1.persist();
        em.flush();
        Store store2 = new Store();
        store2.code = "DUPLICATE"; // Same code
        store2.name = "Store Two";
        store2.address = createAdresse();
        // The exception might be PersistenceException or ConstraintViolationException
        // depending on DB version and configuration. We just assert failure.
        assertThrows(Exception.class, () -> {
            store2.persist();
            em.flush();
        });
    }

    /**
     * Tests {@link Store#findByCode(String)}.
     * <p>
     * Verifies that an existing store can be retrieved by code.
     */
    @Test
    @TestTransaction
    void findByCode_shouldReturnStore() {
        Store store = new Store();
        store.code = "SEARCH_ME";
        store.name = "Target Store";
        store.address = createAdresse();
        store.persist();
        em.flush();
        Store result = Store.findByCode("SEARCH_ME");
        assertNotNull(result);
        assertEquals("Target Store", result.name);
    }

    /**
     * Tests {@link Store#getChecksum()} calculation when address is null.
     * <p>
     * Verifies that the method handles null addresses gracefully without throwing
     * a NullPointerException and returns a consistent checksum.
     */
    @Test
    void getChecksum_shouldHandleNullAddress() {
        Store store = new Store();
        store.code = "S_NULL_ADDR";
        store.name = "Store without address";
        store.address = null; // Cas de test spécifique

        // Act & Assert
        // 1. Vérifie qu'aucune NullPointerException n'est levée
        assertDoesNotThrow(store::getChecksum, "getChecksum should not throw NPE when address is null");

        // 2. Vérifie la cohérence : le checksum pour un objet identique doit être identique
        int checksum1 = store.getChecksum();
        int checksum2 = store.getChecksum();
        assertEquals(checksum1, checksum2, "Checksum should be consistent for the same input");

        // 3. Vérification croisée (optionnel) :
        // S'assurer que ce checksum est différent d'un store avec une adresse
        Store storeWithAddr = new Store();
        storeWithAddr.code = "S_NULL_ADDR";
        storeWithAddr.name = "Store without address";
        storeWithAddr.address = createAdresse();

        assertNotEquals(checksum1, storeWithAddr.getChecksum(),
                "Checksum with null address should differ from checksum with an address");
    }

    /**
     * Tests {@link Store#findByCode(String)} returns null for unknown code.
     */
    @Test
    @TestTransaction
    void findByCode_shouldReturnNull_ifNotFound() {
        Store result = Store.findByCode("UNKNOWN_CODE");
        assertNull(result);
    }
}