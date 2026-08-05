package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.Adresse;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link LookupResource}.
 * <p>
 * Every branch of the resource is exercised. The domain entities are treated as mocked
 * collaborators: their custom static finders ({@code Product.search},
 * {@code Product.findByEans}, {@code Store.search}, {@code StoreGroup.search} and
 * {@code ProductFamily.searchFlags}) are stubbed with {@link org.mockito.Mockito#mockStatic}
 * so the resource is tested in isolation from the database.
 * <p>
 * The presentation helpers carry all the conditional logic and are covered through the
 * public endpoints that call them:
 * <ul>
 *   <li>{@code toProductSuggestions}: the {@code brand == null || brand.isBlank()} ternary
 *       in its three forms (null brand, blank brand, populated brand).</li>
 *   <li>{@code toStoreSuggestions}: the {@code address == null} ternary and the
 *       {@code city == null || city.isBlank()} ternary in all their forms.</li>
 *   <li>{@code toStoreGroupSuggestions}: the plain rendering loop.</li>
 *   <li>{@code splitCsv}: the {@code raw == null || raw.isBlank()} guard (null, blank,
 *       populated) and the {@code !trimmed.isEmpty()} filter (empty part skipped, non empty
 *       part kept).</li>
 *   <li>{@code searchTargets}: the store {@code detail == null} ternary, the store loop
 *       early return on {@code MAX_RESULTS}, and the group loop break on
 *       {@code MAX_RESULTS}.</li>
 * </ul>
 */
class LookupResourceTest {

    /**
     * The maximum number of suggestions the resource returns, mirroring the production
     * constant so the boundary tests stay in sync.
     */
    private static final int MAX_RESULTS = 20;

    /**
     * Builds a product with the given fields, leaving the rest at their defaults.
     *
     * @param ean   The product EAN.
     * @param name  The product name.
     * @param brand The product brand, may be null or blank.
     * @return The populated product.
     */
    private Product product(String ean, String name, String brand) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.brand = brand;
        return product;
    }

    /**
     * Builds a store with the given fields and an optional address city.
     *
     * @param code The store code.
     * @param name The store name.
     * @param city The address city, or null to leave the store without an address.
     * @return The populated store.
     */
    private Store store(String code, String name, String city) {
        Store store = new Store();
        store.code = code;
        store.name = name;
        if (city != null) {
            Adresse address = new Adresse();
            address.city = city;
            store.address = address;
        }
        return store;
    }

    /**
     * Builds a store whose address is present but whose city is exactly the given value.
     *
     * @param code The store code.
     * @param name The store name.
     * @param city The address city, which may be null or blank.
     * @return The populated store carrying an address.
     */
    private Store storeWithAddress(String code, String name, String city) {
        Store store = new Store();
        store.code = code;
        store.name = name;
        Adresse address = new Adresse();
        address.city = city;
        store.address = address;
        return store;
    }

    /**
     * Builds a store group with the given fields.
     *
     * @param code The group code.
     * @param name The group name.
     * @return The populated store group.
     */
    private StoreGroup group(String code, String name) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = name;
        return group;
    }

    /**
     * Verifies that {@code searchProducts} renders each product and covers the three brand
     * arms of {@code toProductSuggestions}: null brand and blank brand both fall back to the
     * EAN as detail, while a populated brand is prefixed before the EAN.
     */
    @Test
    void searchProductsRendersBrandArms() {
        List<Product> products = Arrays.asList(
                product("111", "Milk", null),
                product("222", "Bread", "   "),
                product("333", "Butter", "ACME"));
        try (MockedStatic<Product> mocked = mockStatic(Product.class)) {
            mocked.when(() -> Product.search("q", MAX_RESULTS)).thenReturn(products);
            List<LookupResource.Suggestion> result = new LookupResource().searchProducts("q");
            assertEquals(3, result.size());
            assertEquals("111", result.get(0).value);
            assertEquals("Milk", result.get(0).label);
            assertEquals("111", result.get(0).detail);
            assertEquals("222", result.get(1).value);
            assertEquals("222", result.get(1).detail);
            assertEquals("333", result.get(2).value);
            assertEquals("ACME · 333", result.get(2).detail);
        }
    }

    /**
     * Verifies that {@code resolveProducts} passes an empty EAN list to the finder when the
     * raw parameter is null, covering the {@code raw == null} arm of {@code splitCsv}.
     */
    @Test
    void resolveProductsWithNullEansQueriesEmptyList() {
        try (MockedStatic<Product> mocked = mockStatic(Product.class)) {
            mocked.when(() -> Product.findByEans(eq(new ArrayList<String>())))
                    .thenReturn(new ArrayList<Product>());
            List<LookupResource.Suggestion> result = new LookupResource().resolveProducts(null);
            assertTrue(result.isEmpty());
            mocked.verify(() -> Product.findByEans(eq(new ArrayList<String>())));
        }
    }

    /**
     * Verifies that {@code resolveProducts} passes an empty EAN list to the finder when the
     * raw parameter is blank, covering the {@code raw.isBlank()} arm of {@code splitCsv}.
     */
    @Test
    void resolveProductsWithBlankEansQueriesEmptyList() {
        try (MockedStatic<Product> mocked = mockStatic(Product.class)) {
            mocked.when(() -> Product.findByEans(eq(new ArrayList<String>())))
                    .thenReturn(new ArrayList<Product>());
            List<LookupResource.Suggestion> result = new LookupResource().resolveProducts("   ");
            assertTrue(result.isEmpty());
            mocked.verify(() -> Product.findByEans(eq(new ArrayList<String>())));
        }
    }

    /**
     * Verifies that {@code resolveProducts} trims each CSV part, drops empty parts and keeps
     * the non empty ones, covering the {@code !trimmed.isEmpty()} filter of {@code splitCsv}
     * in both arms as well as its populated guard arm.
     */
    @Test
    void resolveProductsSplitsTrimsAndFiltersCsv() {
        List<Product> found = Arrays.asList(product("123", "Milk", "ACME"));
        try (MockedStatic<Product> mocked = mockStatic(Product.class)) {
            mocked.when(() -> Product.findByEans(eq(Arrays.asList("123", "456"))))
                    .thenReturn(found);
            List<LookupResource.Suggestion> result =
                    new LookupResource().resolveProducts(" 123 , ,456 ");
            assertEquals(1, result.size());
            assertEquals("123", result.get(0).value);
            assertEquals("ACME · 123", result.get(0).detail);
            mocked.verify(() -> Product.findByEans(eq(Arrays.asList("123", "456"))));
        }
    }

    /**
     * Verifies that {@code searchStores} renders stores and covers all forms of the two
     * ternaries in {@code toStoreSuggestions}: a store without an address, a store with an
     * address but a null city, a store with a blank city, and a store with a populated city.
     */
    @Test
    void searchStoresRendersCityArms() {
        List<Store> stores = Arrays.asList(
                store("S1", "Store One", null),
                storeWithAddress("S2", "Store Two", null),
                storeWithAddress("S3", "Store Three", "  "),
                storeWithAddress("S4", "Store Four", "Lyon"));
        try (MockedStatic<Store> mocked = mockStatic(Store.class)) {
            mocked.when(() -> Store.search("q", MAX_RESULTS)).thenReturn(stores);
            List<LookupResource.Suggestion> result = new LookupResource().searchStores("q");
            assertEquals(4, result.size());
            assertEquals("S1", result.get(0).value);
            assertEquals("Store One", result.get(0).label);
            assertNull(result.get(0).detail);
            assertNull(result.get(1).detail);
            assertNull(result.get(2).detail);
            assertEquals("Lyon", result.get(3).detail);
        }
    }

    /**
     * Verifies that {@code searchStoreGroups} renders each group into a suggestion with a
     * null detail line.
     */
    @Test
    void searchStoreGroupsRendersGroups() {
        List<StoreGroup> groups = Arrays.asList(group("G1", "Group One"), group("G2", "Group Two"));
        try (MockedStatic<StoreGroup> mocked = mockStatic(StoreGroup.class)) {
            mocked.when(() -> StoreGroup.search("q", MAX_RESULTS)).thenReturn(groups);
            List<LookupResource.Suggestion> result = new LookupResource().searchStoreGroups("q");
            assertEquals(2, result.size());
            assertEquals("G1", result.get(0).value);
            assertEquals("Group One", result.get(0).label);
            assertNull(result.get(0).detail);
            assertEquals("G2", result.get(1).value);
            assertNull(result.get(1).detail);
        }
    }

    /**
     * Verifies that {@code searchTargets} merges stores and groups without hitting the limit:
     * a store with a populated detail is prefixed with {@code "Store · "} while a store
     * with a null detail becomes plain {@code "Store"}, and every group detail becomes
     * {@code "Store group"}. Covers both arms of the store {@code detail == null} ternary and
     * the false arm of both {@code MAX_RESULTS} guards.
     */
    @Test
    void searchTargetsMergesStoresThenGroups() {
        List<Store> stores = Arrays.asList(
                storeWithAddress("S1", "Store One", "Lyon"),
                store("S2", "Store Two", null));
        List<StoreGroup> groups = Arrays.asList(group("G1", "Group One"));
        try (MockedStatic<Store> mockedStore = mockStatic(Store.class);
             MockedStatic<StoreGroup> mockedGroup = mockStatic(StoreGroup.class)) {
            mockedStore.when(() -> Store.search("q", MAX_RESULTS)).thenReturn(stores);
            mockedGroup.when(() -> StoreGroup.search("q", MAX_RESULTS)).thenReturn(groups);
            List<LookupResource.Suggestion> result = new LookupResource().searchTargets("q");
            assertEquals(3, result.size());
            assertEquals("S1", result.get(0).value);
            assertEquals("Store · Lyon", result.get(0).detail);
            assertEquals("S2", result.get(1).value);
            assertEquals("Store", result.get(1).detail);
            assertEquals("G1", result.get(2).value);
            assertEquals("Store group", result.get(2).detail);
        }
    }

    /**
     * Verifies that {@code searchTargets} returns as soon as the store loop reaches
     * {@code MAX_RESULTS}, never consulting the store groups. Covers the true arm of the
     * store loop {@code MAX_RESULTS} guard.
     */
    @Test
    void searchTargetsStopsInsideStoreLoopAtLimit() {
        List<Store> stores = new ArrayList<>();
        for (int i = 0; i < MAX_RESULTS + 5; i++) {
            stores.add(store("S" + i, "Store " + i, null));
        }
        try (MockedStatic<Store> mockedStore = mockStatic(Store.class);
             MockedStatic<StoreGroup> mockedGroup = mockStatic(StoreGroup.class)) {
            mockedStore.when(() -> Store.search("q", MAX_RESULTS)).thenReturn(stores);
            List<LookupResource.Suggestion> result = new LookupResource().searchTargets("q");
            assertEquals(MAX_RESULTS, result.size());
            assertEquals("S0", result.get(0).value);
            assertEquals("S19", result.get(MAX_RESULTS - 1).value);
            mockedGroup.verifyNoInteractions();
        }
    }

    /**
     * Verifies that {@code searchTargets} breaks out of the group loop once the combined
     * result reaches {@code MAX_RESULTS}, so the returned list is capped. Covers the true arm
     * of the group loop {@code MAX_RESULTS} guard.
     */
    @Test
    void searchTargetsStopsInsideGroupLoopAtLimit() {
        List<Store> stores = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stores.add(store("S" + i, "Store " + i, null));
        }
        List<StoreGroup> groups = new ArrayList<>();
        for (int i = 0; i < MAX_RESULTS; i++) {
            groups.add(group("G" + i, "Group " + i));
        }
        try (MockedStatic<Store> mockedStore = mockStatic(Store.class);
             MockedStatic<StoreGroup> mockedGroup = mockStatic(StoreGroup.class)) {
            mockedStore.when(() -> Store.search("q", MAX_RESULTS)).thenReturn(stores);
            mockedGroup.when(() -> StoreGroup.search("q", MAX_RESULTS)).thenReturn(groups);
            List<LookupResource.Suggestion> result = new LookupResource().searchTargets("q");
            assertEquals(MAX_RESULTS, result.size());
            assertEquals("S0", result.get(0).value);
            assertEquals("Store group", result.get(MAX_RESULTS - 1).detail);
        }
    }

    /**
     * Verifies that {@code searchFlags} wraps each flag string into a self labelled
     * suggestion with a null detail line.
     */
    @Test
    void searchFlagsWrapsEachFlag() {
        List<String> flags = Arrays.asList("BIO", "PROMO");
        try (MockedStatic<ProductFamily> mocked = mockStatic(ProductFamily.class)) {
            mocked.when(() -> ProductFamily.searchFlags("q", MAX_RESULTS)).thenReturn(flags);
            List<LookupResource.Suggestion> result = new LookupResource().searchFlags("q");
            assertEquals(2, result.size());
            assertEquals("BIO", result.get(0).value);
            assertEquals("BIO", result.get(0).label);
            assertNull(result.get(0).detail);
            assertEquals("PROMO", result.get(1).value);
        }
    }

    /**
     * Verifies that {@code searchFlags} returns an empty list when the catalog yields no
     * flags, covering the empty arm of its rendering loop.
     */
    @Test
    void searchFlagsReturnsEmptyWhenNoFlags() {
        try (MockedStatic<ProductFamily> mocked = mockStatic(ProductFamily.class)) {
            mocked.when(() -> ProductFamily.searchFlags(null, MAX_RESULTS))
                    .thenReturn(new ArrayList<String>());
            List<LookupResource.Suggestion> result = new LookupResource().searchFlags(null);
            assertTrue(result.isEmpty());
        }
    }
}
