package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.imports.OfferCsvResource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OfferUiResource}.
 * <p>
 * The resource is a plain JAX-RS bean; its two collaborators ({@link OfferSchemaRegistry} and
 * {@link OfferCsvResource}) are Mockito mocks injected on the package-private fields, and every
 * Panache access resolves to a static of {@link PanacheEntityBase} under plain unit tests, so it
 * is intercepted with {@link org.mockito.Mockito#mockStatic}. The {@code save} path constructs a
 * fresh {@link Offer} and calls {@code persist()}, so it is neutralised with
 * {@link org.mockito.Mockito#mockConstruction}; the mandatory {@code stores}/{@code storeGroups}
 * collections are re-created by the construction initializer because the real field initializers
 * do not run on an Objenesis-instantiated mock.
 * <p>
 * The Qute {@code Templates} methods are {@code @CheckedTemplate} native methods left unlinked
 * under plain {@code mvn test}; every screen therefore terminates in an {@link UnsatisfiedLinkError}
 * once its logic has run, and those tests assert the throw while all the branches before the
 * template call are exercised. Redirect and 404 responses carry no template and are asserted
 * directly.
 * <p>
 * Branches covered, arm by arm:
 * <ul>
 *   <li>{@code list}: the {@code sortKey} validity ternary, the {@code descending} decision, the
 *       {@code securityContext != null && isUserInRole} short-circuit (null, non-admin, admin).</li>
 *   <li>{@code export}: the same sort/direction ternaries plus the render loop (empty and populated)
 *       and {@code sanitize} (null and populated value).</li>
 *   <li>{@code queryOffers}: every {@code isSet} filter (present, blank, null), the {@code append}
 *       first/subsequent condition, the {@code where.length() > 0} guard, and every
 *       {@code buildOrderBy} arm (asc/desc, eans/other, code/non-code tie-breaker).</li>
 *   <li>{@code importCsv}: the {@code upload == null || file == null} guard (all three forms), the
 *       {@code getEntity() == null} ternary and the exception catch.</li>
 *   <li>{@code edit}/{@code update}: the {@code offer == null} 404 guard, both arms.</li>
 *   <li>{@code applyForm}: the code guard, the type guard, the {@code isNew && count > 0} check, the
 *       target-presence conjunction, the {@code schema != null} branch, and the resolve
 *       {@link IllegalArgumentException} catch.</li>
 *   <li>{@code validateSpecification}: the blank/null specification guard and the validation
 *       success/failure arms.</li>
 *   <li>{@code resolveStores}/{@code resolveStoreGroups}: the empty-codes short-circuit and the
 *       size-mismatch throw.</li>
 *   <li>{@code renderFormWithError}: both null-coalescing ternaries.</li>
 *   <li>{@code buildSchemasJson}: the empty registry and the comma separator, plus {@code splitCsv}
 *       null/blank/populated and its empty-part and duplicate filters.</li>
 * </ul>
 */
class OfferUiResourceTest {

    /**
     * A JSON Schema accepting any object, used to drive the successful validation arm.
     */
    private static final String SCHEMA_ANY = "{\"type\":\"object\"}";

    /**
     * A JSON Schema requiring an {@code ean} field, used to drive the failing validation arm.
     */
    private static final String SCHEMA_REQUIRED = "{\"type\":\"object\",\"required\":[\"ean\"]}";

    /**
     * Builds a store carrying the given business code.
     *
     * @param code The store code.
     * @return The populated store.
     */
    private Store store(String code) {
        Store store = new Store();
        store.code = code;
        return store;
    }

    /**
     * Builds a store group carrying the given business code.
     *
     * @param code The group code.
     * @return The populated store group.
     */
    private StoreGroup group(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        return group;
    }

    /**
     * Builds a real offer with empty target collections.
     *
     * @param code          The offer code.
     * @param type          The offer type.
     * @param specification The JSON specification.
     * @return The populated offer.
     */
    private Offer offer(String code, String type, String specification) {
        Offer offer = new Offer();
        offer.code = code;
        offer.type = type;
        offer.specification = specification;
        return offer;
    }

    /**
     * Builds a real offer carrying the given stores and groups.
     *
     * @param code          The offer code.
     * @param type          The offer type.
     * @param specification The JSON specification.
     * @param stores        The linked stores.
     * @param groups        The linked store groups.
     * @return The populated offer.
     */
    private Offer offer(String code, String type, String specification, Set<Store> stores, Set<StoreGroup> groups) {
        Offer offer = offer(code, type, specification);
        offer.stores = stores;
        offer.storeGroups = groups;
        return offer;
    }

    /**
     * Builds a schema registry mock ready to feed the form template without a null map.
     *
     * @return A registry exposing no type and an empty schema map.
     */
    private OfferSchemaRegistry emptyRegistry() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getKnownTypes()).thenReturn(Collections.emptySet());
        when(registry.getAllSchemas()).thenReturn(new LinkedHashMap<>());
        return registry;
    }

    /**
     * Wires a resource instance with the supplied collaborators.
     *
     * @param registry The schema registry collaborator.
     * @param csv      The CSV importer collaborator.
     * @return The wired resource.
     */
    private OfferUiResource resource(OfferSchemaRegistry registry, OfferCsvResource csv) {
        OfferUiResource resource = new OfferUiResource();
        resource.schemaRegistry = registry;
        resource.csvResource = csv;
        return resource;
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * The list screen with a valid sort, an ascending direction and an admin principal reaches the
     * template after clamping the page, exercising the true arm of the security conjunction.
     */
    @Test
    void listWithAdminReachesTemplate() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getKnownTypes()).thenReturn(Set.of("PROMO"));
        SecurityContext context = mock(SecurityContext.class);
        when(context.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(true);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.page(any(Page.class))).thenReturn(query);
            when(query.count()).thenReturn(1L);
            when(query.pageCount()).thenReturn(1);
            when(query.list()).thenReturn(List.of(offer("C", "PROMO", "{}")));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, null, null, null, "code", "asc", 1, null, true, context));
        }
    }

    /**
     * An unknown sort key falls back to the code column, a descending direction is honoured and a
     * non-admin principal exercises the false right arm of the security conjunction; a page beyond
     * the range is clamped down.
     */
    @Test
    void listWithInvalidSortAndNonAdminReachesTemplate() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getKnownTypes()).thenReturn(Set.of("PROMO"));
        SecurityContext context = mock(SecurityContext.class);
        when(context.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(false);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.page(any(Page.class))).thenReturn(query);
            when(query.count()).thenReturn(60L);
            when(query.pageCount()).thenReturn(3);
            when(query.list()).thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, null, null, null, "bogus", "desc", 100, "hi", false, context));
        }
    }

    /**
     * A null security context exercises the false left arm of the security conjunction and a page
     * below one is clamped up, still reaching the template.
     */
    @Test
    void listWithNullSecurityContextReachesTemplate() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getKnownTypes()).thenReturn(Set.of("PROMO"));
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.page(any(Page.class))).thenReturn(query);
            when(query.count()).thenReturn(0L);
            when(query.pageCount()).thenReturn(0);
            when(query.list()).thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, null, null, null, "eans", "ASC", 0, null, true, null));
        }
    }

    // --------------------------------------------------
    // export
    // --------------------------------------------------

    /**
     * An export with no filter and the default sort produces the header alone, exercising the empty
     * render loop, the absent WHERE clause and the code-column ORDER BY.
     */
    @Test
    void exportWithoutFilterYieldsHeaderOnly() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.list()).thenReturn(List.of());
            Response response = resource.export(null, null, null, null, "code", "asc");
            assertEquals(200, response.getStatus());
            assertEquals("offer_code|offer_type|specification|store_code|store_group_code\n", response.getEntity());
            assertEquals("attachment; filename=\"offers.csv\"", response.getHeaderString("Content-Disposition"));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            assertFalse(jpql.getValue().contains("where"));
            assertTrue(jpql.getValue().endsWith("order by o.code asc"));
        }
    }

    /**
     * An export filtered on the code alone renders a full row, exercising the single-filter WHERE
     * clause, the {@code append} first-condition arm and the {@code sanitize} populated arm; the
     * joined store and group codes are sorted.
     */
    @Test
    void exportWithSearchRendersRow() {
        Offer offer = offer("PROMO1", "DISCOUNT", "{\"ean\":\"111\"}",
                new HashSet<>(Set.of(store("S2"), store("S1"))), new HashSet<>(Set.of(group("G1"))));
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.list()).thenReturn(List.of(offer));
            Response response = resource.export("milk", null, null, null, "code", "asc");
            String body = (String) response.getEntity();
            assertTrue(body.contains("PROMO1|DISCOUNT|{\"ean\":\"111\"}|S1,S2|G1\n"));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            assertTrue(jpql.getValue().contains("where lower(o.code) like ?1"));
        }
    }

    /**
     * An export combining a blank type, a target and an EAN with a descending EAN sort exercises the
     * blank {@code isSet} arm, the {@code append} subsequent-condition arm, the EAN order expression
     * and the {@code sanitize} null arm through a null specification.
     */
    @Test
    void exportWithMultipleFiltersAndEanSort() {
        Offer offer = offer("A|B\nC", "T", null);
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.list()).thenReturn(List.of(offer));
            Response response = resource.export(null, "  ", "lyon", "333", "eans", "DESC");
            String body = (String) response.getEntity();
            assertTrue(body.contains("A/B C|T|||\n"));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            String value = jpql.getValue();
            assertFalse(value.contains("o.type ="));
            assertTrue(value.contains("o.stores"));
            assertTrue(value.contains(" and "));
            assertTrue(value.contains("order by size(o.eans) desc, o.code asc"));
        }
    }

    /**
     * An export sorted on the type column appends the deterministic code tie-breaker, exercising the
     * non-code arm of {@code buildOrderBy}.
     */
    @Test
    void exportSortedOnTypeAppendsTieBreaker() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Offer> query = mock(PanacheQuery.class);
            mocked.when(() -> PanacheEntityBase.<Offer>find(anyString(), any(Object[].class))).thenReturn(query);
            when(query.list()).thenReturn(List.of());
            resource.export(null, null, null, null, "type", "asc");
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            assertTrue(jpql.getValue().endsWith("order by o.type asc, o.code asc"));
        }
    }

    // --------------------------------------------------
    // importCsv
    // --------------------------------------------------

    /**
     * A null upload redirects with a failure notice, exercising the true left arm of the file guard.
     */
    @Test
    void importCsvWithNullUploadRedirectsWithFailure() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        Response response = resource.importCsv(null);
        assertEquals(303, response.getStatus());
        assertEquals("/ui/offers", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    /**
     * An upload without a file redirects with a failure notice, exercising the true right arm of the
     * file guard.
     */
    @Test
    void importCsvWithoutFileRedirectsWithFailure() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        OfferUiResource.OfferCsvUpload upload = new OfferUiResource.OfferCsvUpload();
        Response response = resource.importCsv(upload);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    /**
     * A successful import with an empty importer entity redirects with the plain success notice,
     * exercising the false file guard and the null-entity ternary arm.
     */
    @Test
    void importCsvWithEmptyEntityRedirectsWithSuccess() {
        OfferCsvResource csv = mock(OfferCsvResource.class);
        when(csv.importOffers(any())).thenReturn(Response.ok().build());
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), csv);
        OfferUiResource.OfferCsvUpload upload = new OfferUiResource.OfferCsvUpload();
        upload.file = new ByteArrayInputStream(new byte[0]);
        Response response = resource.importCsv(upload);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * A successful import carrying a summary entity redirects with the detailed success notice,
     * exercising the non-null entity ternary arm.
     */
    @Test
    void importCsvWithEntityRedirectsWithSuccess() {
        OfferCsvResource csv = mock(OfferCsvResource.class);
        when(csv.importOffers(any())).thenReturn(Response.ok("Rows: 5").build());
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), csv);
        OfferUiResource.OfferCsvUpload upload = new OfferUiResource.OfferCsvUpload();
        upload.file = new ByteArrayInputStream(new byte[0]);
        Response response = resource.importCsv(upload);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * An importer failure is caught and reported as a failure notice, exercising the catch branch.
     */
    @Test
    void importCsvOnExceptionRedirectsWithFailure() {
        OfferCsvResource csv = mock(OfferCsvResource.class);
        when(csv.importOffers(any())).thenThrow(new RuntimeException("boom"));
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), csv);
        OfferUiResource.OfferCsvUpload upload = new OfferUiResource.OfferCsvUpload();
        upload.file = new ByteArrayInputStream(new byte[0]);
        Response response = resource.importCsv(upload);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // create
    // --------------------------------------------------

    /**
     * The creation form with an empty registry reaches the template with an empty schema map,
     * exercising the never-entered branch of {@code buildSchemasJson}.
     */
    @Test
    void createWithEmptyRegistryReachesTemplate() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        assertThrows(UnsatisfiedLinkError.class, () -> resource.create(null));
    }

    /**
     * The creation form with two schemas reaches the template after inserting the comma separator
     * and escaping the keys, exercising the populated branch of {@code buildSchemasJson}.
     */
    @Test
    void createWithSchemasReachesTemplate() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getKnownTypes()).thenReturn(Set.of("A", "B"));
        Map<String, String> schemas = new LinkedHashMap<>();
        schemas.put("A\"x", "{\"k\":1}");
        schemas.put("B\\y", "{}");
        when(registry.getAllSchemas()).thenReturn(schemas);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        assertThrows(UnsatisfiedLinkError.class, () -> resource.create("PROMO"));
    }

    // --------------------------------------------------
    // edit
    // --------------------------------------------------

    /**
     * Editing a missing offer answers a 404 without touching the template.
     */
    @Test
    void editMissingOfferReturnsNotFound() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            Response response = resource.edit(7L);
            assertEquals(404, response.getStatus());
            assertEquals("Offer 7 not found", response.getEntity());
        }
    }

    /**
     * Editing an existing offer reaches the template after joining its store and group codes,
     * exercising the found arm of the guard.
     */
    @Test
    void editExistingOfferReachesTemplate() {
        Offer existing = offer("C", "PROMO", "{}",
                new HashSet<>(Set.of(store("S1"))), new HashSet<>(Set.of(group("G1"))));
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(3L)).thenReturn(existing);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.edit(3L));
        }
    }

    // --------------------------------------------------
    // save
    // --------------------------------------------------

    /**
     * Saving without a code re-renders the form with an error, exercising the null-code guard and
     * both null-coalescing arms of {@code renderFormWithError}.
     */
    @Test
    void saveWithNullCodeRendersError() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save(null, "PROMO", "{}", null, null));
        }
    }

    /**
     * Saving with a blank code re-renders the form, exercising the blank-code guard and the
     * populated arms of {@code renderFormWithError}.
     */
    @Test
    void saveWithBlankCodeRendersError() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("   ", "PROMO", "{}", "S1", "G1"));
        }
    }

    /**
     * Saving without a type re-renders the form, exercising the null-type guard.
     */
    @Test
    void saveWithNullTypeRendersError() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", null, "{}", "S1", "G1"));
        }
    }

    /**
     * Saving with a blank type re-renders the form, exercising the blank-type guard.
     */
    @Test
    void saveWithBlankTypeRendersError() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "  ", "{}", "S1", "G1"));
        }
    }

    /**
     * Saving a duplicate code re-renders the form, exercising the true arm of the uniqueness check.
     */
    @Test
    void saveWithDuplicateCodeRendersError() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            mocked.when(() -> PanacheEntityBase.count(eq("code"), any(Object[].class))).thenReturn(1L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", "{}", "S1", "G1"));
        }
    }

    /**
     * Saving without any target re-renders the form, exercising the true arm of the target
     * conjunction and both the blank and null arms of {@code splitCsv}.
     */
    @Test
    void saveWithoutTargetsRendersError() {
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", "{}", "   ", null));
        }
    }

    /**
     * Saving with a schema but a null specification re-renders the form, exercising the
     * {@code schema != null} arm and the null-specification guard.
     */
    @Test
    void saveWithNullSpecificationRendersError() {
        OfferSchemaRegistry registry = emptyRegistry();
        when(registry.getSchema("PROMO")).thenReturn(SCHEMA_ANY);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", null, "S1", null));
        }
    }

    /**
     * Saving with a schema but a blank specification re-renders the form, exercising the
     * blank-specification guard.
     */
    @Test
    void saveWithBlankSpecificationRendersError() {
        OfferSchemaRegistry registry = emptyRegistry();
        when(registry.getSchema("PROMO")).thenReturn(SCHEMA_ANY);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", "   ", "S1", null));
        }
    }

    /**
     * Saving a specification breaching its schema re-renders the form, exercising the validation
     * failure arm of {@code validateSpecification}.
     */
    @Test
    void saveWithInvalidSpecificationRendersError() {
        OfferSchemaRegistry registry = emptyRegistry();
        when(registry.getSchema("PROMO")).thenReturn(SCHEMA_REQUIRED);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", "{}", "S1", null));
        }
    }

    /**
     * Saving with an unknown store code re-renders the form, exercising the size-mismatch throw of
     * {@code resolveStores} and its catch in {@code applyForm}.
     */
    @Test
    void saveWithUnknownStoreRendersError() {
        OfferSchemaRegistry registry = emptyRegistry();
        when(registry.getSchema("PROMO")).thenReturn(null);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            mocked.when(() -> PanacheEntityBase.list(eq("code in ?1"), eq(List.of("S1", "S2"))))
                    .thenReturn(List.of(store("S1")));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", "{}", "S1,S2", null));
        }
    }

    /**
     * Saving with only unknown group codes re-renders the form, exercising the empty-codes arm of
     * {@code resolveStores}, the size-mismatch throw of {@code resolveStoreGroups} and the
     * left-true right-false form of the target conjunction.
     */
    @Test
    void saveWithUnknownGroupRendersError() {
        OfferSchemaRegistry registry = emptyRegistry();
        when(registry.getSchema("PROMO")).thenReturn(null);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class)) {
            mocked.when(() -> PanacheEntityBase.list(eq("code in ?1"), eq(List.of("G1"))))
                    .thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.save("C", "PROMO", "{}", null, "G1"));
        }
    }

    /**
     * A fully valid creation without a schema persists the offer and redirects, exercising the
     * success arms and the empty-part and duplicate filters of {@code splitCsv}.
     */
    @Test
    void saveValidOfferPersistsAndRedirects() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class, (offer, ctx) -> {
                 offer.stores = new HashSet<>();
                 offer.storeGroups = new HashSet<>();
             })) {
            mocked.when(() -> PanacheEntityBase.count(eq("code"), any(Object[].class))).thenReturn(0L);
            mocked.when(() -> PanacheEntityBase.list(eq("code in ?1"), eq(List.of("S1", "S2"))))
                    .thenReturn(List.of(store("S1"), store("S2")));
            mocked.when(() -> PanacheEntityBase.list(eq("code in ?1"), eq(List.of("G1"))))
                    .thenReturn(List.of(group("G1")));
            Response response = resource.save("PROMO_CODE", "PROMO", "{}", " S1 , , S1 , S2 ", "G1");
            assertEquals(303, response.getStatus());
            assertEquals("/ui/offers", response.getLocation().getPath());
            assertNull(response.getLocation().getQuery());
            Offer created = construction.constructed().get(0);
            assertEquals("PROMO", created.type);
            assertEquals("{}", created.specification);
            verify(created).persist();
            assertEquals(Set.of("S1", "S2"),
                    created.stores.stream().map(s -> s.code).collect(Collectors.toSet()));
            assertEquals(Set.of("G1"),
                    created.storeGroups.stream().map(g -> g.code).collect(Collectors.toSet()));
        }
    }

    /**
     * A valid creation whose specification passes its schema persists and redirects, exercising the
     * validation success arm and the empty-codes arm of {@code resolveStoreGroups}.
     */
    @Test
    void saveValidOfferWithSchemaPersistsAndRedirects() {
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getSchema("PROMO")).thenReturn(SCHEMA_ANY);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Offer> construction = mockConstruction(Offer.class, (offer, ctx) -> {
                 offer.stores = new HashSet<>();
                 offer.storeGroups = new HashSet<>();
             })) {
            mocked.when(() -> PanacheEntityBase.count(eq("code"), any(Object[].class))).thenReturn(0L);
            mocked.when(() -> PanacheEntityBase.list(eq("code in ?1"), eq(List.of("S1"))))
                    .thenReturn(List.of(store("S1")));
            Response response = resource.save("C", "PROMO", "{}", "S1", null);
            assertEquals(303, response.getStatus());
            Offer created = construction.constructed().get(0);
            verify(created).persist();
            assertTrue(created.storeGroups.isEmpty());
        }
    }

    // --------------------------------------------------
    // update
    // --------------------------------------------------

    /**
     * Updating a missing offer answers a 404 without touching the template.
     */
    @Test
    void updateMissingOfferReturnsNotFound() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(9L)).thenReturn(null);
            Response response = resource.update(9L, "PROMO", "{}", "S1", null);
            assertEquals(404, response.getStatus());
            assertEquals("Offer 9 not found", response.getEntity());
        }
    }

    /**
     * Updating an existing offer applies the form and redirects without a uniqueness check,
     * exercising the false left arm of the {@code isNew && count} conjunction and the update success
     * path.
     */
    @Test
    void updateExistingOfferRedirects() {
        Offer existing = offer("EXIST", "OLD", "{}", new HashSet<>(), new HashSet<>());
        OfferSchemaRegistry registry = mock(OfferSchemaRegistry.class);
        when(registry.getSchema("PROMO")).thenReturn(null);
        OfferUiResource resource = resource(registry, mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(5L)).thenReturn(existing);
            mocked.when(() -> PanacheEntityBase.list(eq("code in ?1"), eq(List.of("S1"))))
                    .thenReturn(List.of(store("S1")));
            Response response = resource.update(5L, "PROMO", "{}", "S1", null);
            assertEquals(303, response.getStatus());
            assertEquals("/ui/offers", response.getLocation().getPath());
            assertEquals("PROMO", existing.type);
            assertEquals(Set.of("S1"), existing.stores.stream().map(s -> s.code).collect(Collectors.toSet()));
        }
    }

    /**
     * Updating an existing offer with an invalid form re-renders the form, exercising the update
     * error path.
     */
    @Test
    void updateExistingOfferWithErrorRendersForm() {
        Offer existing = offer("EXIST", "OLD", "{}", new HashSet<>(), new HashSet<>());
        OfferUiResource resource = resource(emptyRegistry(), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(5L)).thenReturn(existing);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.update(5L, null, "{}", "S1", "G1"));
        }
    }

    // --------------------------------------------------
    // delete
    // --------------------------------------------------

    /**
     * Deleting an existing offer redirects to the list (the successful-deletion arm).
     */
    @Test
    void deleteExistingRedirectsToList() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.deleteById(2L)).thenReturn(true);
            Response response = resource.delete(2L);
            assertEquals(303, response.getStatus());
            assertEquals("/ui/offers", response.getLocation().getPath());
            assertNull(response.getLocation().getQuery());
        }
    }

    /**
     * Deleting a missing offer answers a 404 carrying the same message as edit/update,
     * never a silent redirect (the deletion-failed arm).
     */
    @Test
    void deleteMissingAnswers404() {
        OfferUiResource resource = resource(mock(OfferSchemaRegistry.class), mock(OfferCsvResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.deleteById(7L)).thenReturn(false);
            Response response = resource.delete(7L);
            assertEquals(404, response.getStatus());
            assertEquals("Offer 7 not found", response.getEntity());
        }
    }
}
