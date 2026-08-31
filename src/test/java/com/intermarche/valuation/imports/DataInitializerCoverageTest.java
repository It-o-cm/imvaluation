package com.intermarche.valuation.imports;

import com.intermarche.valuation.domain.Store;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage test for {@link DataInitializer}, run under a dedicated profile that enables the
 * startup seed so the whole seeding path executes for real.
 * <p>
 * The disabled-flag arm is exercised by every other {@code @QuarkusTest} boot (the seed
 * defaults to off), so this class focuses on the enabled path: the empty-database seed at
 * startup, and the already-seeded short-circuit when the observer is invoked a second time.
 */
@QuarkusTest
@TestProfile(DataInitializerCoverageTest.SeedEnabledProfile.class)
public class DataInitializerCoverageTest {

    /**
     * Test profile enabling the reference-data seed at startup.
     */
    public static class SeedEnabledProfile implements QuarkusTestProfile {

        /**
         * Turns the startup seed on for this test's Quarkus boot.
         *
         * @return The configuration overrides enabling the seed.
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("valuation.bootstrap.data.enabled", "true");
        }
    }

    /**
     * The initializer under test, injected as the real singleton.
     */
    @Inject
    DataInitializer dataInitializer;

    /**
     * Tests that enabling the seed populates the reference data at startup, and that invoking
     * the startup observer again is a no-op because the data is already present.
     */
    @Test
    void testSeed_populatesThenSkipsWhenAlreadySeeded() {
        long stores = QuarkusTransaction.requiringNew().call(() -> Store.count());
        assertTrue(stores > 0, "the startup seed should have loaded at least one store");
        // A second invocation must detect the already-seeded database and skip silently.
        dataInitializer.onStart(new StartupEvent());
        long after = QuarkusTransaction.requiringNew().call(() -> Store.count());
        assertTrue(after == stores, "the already-seeded guard must leave the data untouched");
    }
}
