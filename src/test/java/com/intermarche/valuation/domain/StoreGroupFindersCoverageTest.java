package com.intermarche.valuation.domain;

import com.intermarche.valuation.CoverageDbReset;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for the hierarchy finders and traversals of {@link StoreGroup}, exercised
 * through {@code @QuarkusTest} so the container attributes the executed lines to the class.
 * <p>
 * A small diamond-shaped graph is committed once per test (GA over GB and GC, both over GD,
 * plus an isolated GE), then every finder, the cycle guard and the downward collectors are
 * driven inside a session so their lazy sub-group collections resolve.
 */
@QuarkusTest
public class StoreGroupFindersCoverageTest {

    /**
     * Clears the shared database after this class so its seeded rows cannot collide with
     * another coverage class in the process-wide in-memory database.
     */
    @AfterAll
    static void clearDatabaseAfterClass() {
        CoverageDbReset.resetAll();
    }

    /**
     * Empties the store-group and store tables before each test, in reverse dependency order,
     * so no leaked row skews a lookup.
     */
    @BeforeEach
    @Transactional
    void cleanDatabase() {
        StoreGroup.deleteAll();
        Store.deleteAll();
    }

    /**
     * Persists a store carrying the given code, inside the ambient transaction.
     *
     * @param code The store code.
     * @return The persisted store.
     */
    private Store store(String code) {
        Store store = new Store();
        store.code = code;
        store.name = "Store " + code;
        store.persistAndFlush();
        return store;
    }

    /**
     * Persists a store group carrying the given code, inside the ambient transaction.
     *
     * @param code The store group code.
     * @return The persisted store group.
     */
    private StoreGroup group(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = "Group " + code;
        group.persistAndFlush();
        return group;
    }

    /**
     * Commits the diamond hierarchy used by every test: GA contains GB and GC, both contain
     * GD, GE stays isolated; stores S3, S1 and S2 hang off GA, GB and GD respectively.
     */
    private void seedHierarchy() {
        QuarkusTransaction.requiringNew().run(() -> {
            Store s1 = store("S1");
            Store s2 = store("S2");
            Store s3 = store("S3");
            StoreGroup gA = group("GA");
            StoreGroup gB = group("GB");
            StoreGroup gC = group("GC");
            StoreGroup gD = group("GD");
            group("GE");
            gA.stores.add(s3);
            gB.stores.add(s1);
            gD.stores.add(s2);
            gA.storeGroups.add(gB);
            gA.storeGroups.add(gC);
            gB.storeGroups.add(gD);
            gC.storeGroups.add(gD);
        });
    }

    /**
     * Tests that {@link StoreGroup#findParentsOf(StoreGroup)} returns an empty list for a null
     * group and the direct parents for a persisted child.
     */
    @Test
    void testFindParentsOf() {
        seedHierarchy();
        List<String> parentCodes = QuarkusTransaction.requiringNew().call(() -> {
            assertTrue(StoreGroup.findParentsOf(null).isEmpty());
            assertTrue(StoreGroup.findParentsOf(new StoreGroup()).isEmpty());
            StoreGroup gB = StoreGroup.findByCode("GB");
            return StoreGroup.findParentsOf(gB).stream().map(g -> g.code).collect(Collectors.toList());
        });
        assertEquals(1, parentCodes.size());
        assertTrue(parentCodes.contains("GA"));
    }

    /**
     * Tests that {@link StoreGroup#findGroupsContaining(Store)} returns an empty list for a
     * null store and the containing groups for a persisted one.
     */
    @Test
    void testFindGroupsContaining() {
        seedHierarchy();
        List<String> codes = QuarkusTransaction.requiringNew().call(() -> {
            assertTrue(StoreGroup.findGroupsContaining(null).isEmpty());
            assertTrue(StoreGroup.findGroupsContaining(new Store()).isEmpty());
            Store s1 = Store.find("code", "S1").firstResult();
            return StoreGroup.findGroupsContaining(s1).stream().map(g -> g.code).collect(Collectors.toList());
        });
        assertEquals(1, codes.size());
        assertTrue(codes.contains("GB"));
    }

    /**
     * Tests that {@link StoreGroup#findRoots()} returns exactly the groups that are no other
     * group's child, here GA and the isolated GE.
     */
    @Test
    void testFindRoots() {
        seedHierarchy();
        Set<String> roots = QuarkusTransaction.requiringNew().call(() ->
                StoreGroup.findRoots().stream().map(g -> g.code).collect(Collectors.toSet()));
        assertEquals(Set.of("GA", "GE"), roots);
    }

    /**
     * Tests every arm of {@link StoreGroup#wouldCreateCycle(StoreGroup, StoreGroup)}: null
     * operands, the self-link, a genuine back-edge, a transient child with a null id, and a
     * safe link whose downward walk crosses the diamond twice without ever reaching the
     * parent.
     */
    @Test
    void testWouldCreateCycle() {
        seedHierarchy();
        QuarkusTransaction.requiringNew().run(() -> {
            StoreGroup gA = StoreGroup.findByCode("GA");
            StoreGroup gB = StoreGroup.findByCode("GB");
            StoreGroup gE = StoreGroup.findByCode("GE");
            assertFalse(StoreGroup.wouldCreateCycle(null, gA));
            assertFalse(StoreGroup.wouldCreateCycle(gA, null));
            assertTrue(StoreGroup.wouldCreateCycle(gA, gA));
            assertTrue(StoreGroup.wouldCreateCycle(gB, gA));
            assertFalse(StoreGroup.wouldCreateCycle(gA, new StoreGroup()));
            assertFalse(StoreGroup.wouldCreateCycle(gE, gA));
        });
    }

    /**
     * Tests that {@link StoreGroup#collectDescendants()} gathers the group and all reachable
     * sub-groups exactly once despite the diamond, exercising the already-visited
     * short-circuit.
     */
    @Test
    void testCollectDescendants() {
        seedHierarchy();
        Set<String> codes = QuarkusTransaction.requiringNew().call(() ->
                StoreGroup.findByCode("GA").collectDescendants().stream()
                        .map(g -> g.code).collect(Collectors.toSet()));
        assertEquals(Set.of("GA", "GB", "GC", "GD"), codes);
    }

    /**
     * Tests that {@link StoreGroup#collectAllStores()} gathers every store reachable directly
     * or through the sub-groups of the diamond.
     */
    @Test
    void testCollectAllStores() {
        seedHierarchy();
        Set<String> codes = QuarkusTransaction.requiringNew().call(() ->
                StoreGroup.findByCode("GA").collectAllStores().stream()
                        .map(s -> s.code).collect(Collectors.toSet()));
        assertEquals(Set.of("S1", "S2", "S3"), codes);
    }

    /**
     * Tests {@link StoreGroup#search(String, int)} across its three arms: a null query and a
     * blank query both list every group by code, while a non-blank term filters on the code
     * prefix and the name fragment.
     */
    @Test
    void testSearch() {
        seedHierarchy();
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(5, StoreGroup.search(null, 10).size());
            assertEquals(5, StoreGroup.search("   ", 10).size());
            List<String> byCode = StoreGroup.search("ga", 10).stream()
                    .map(g -> g.code).collect(Collectors.toList());
            assertEquals(1, byCode.size());
            assertTrue(byCode.contains("GA"));
            assertEquals(5, StoreGroup.search("group", 10).size());
        });
    }
}
