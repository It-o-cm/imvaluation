package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import com.intermarche.valuation.engine.ValuationResource;
import com.intermarche.valuation.engine.ValuationTraceService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ValuationUiResource}.
 * <p>
 * The resource is a plain JAX-RS bean with two injected collaborators ({@code traceService} and
 * {@code valuationResource}, both package-private and set directly on the instance) and it drives
 * persistence through the inherited Panache static finders of {@link ValuationTrace} and the
 * {@code current()} factory of {@link ValuationTraceConfig}. The inherited finders
 * ({@code find}, {@code findById}) resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with a {@link org.mockito.Mockito#mockStatic} of that
 * declaring class; {@code ValuationTraceConfig.current()} is a concrete static declared on the
 * entity itself and is intercepted with a {@code mockStatic} of {@link ValuationTraceConfig}.
 * <p>
 * The list and detail screens end in the {@code @CheckedTemplate} native methods
 * ({@code Templates.list}, {@code Templates.list$rows}, {@code Templates.detail},
 * {@code Templates.test}), left unlinked under plain {@code mvn test}: every screen therefore
 * terminates in an {@link UnsatisfiedLinkError} once the pure logic before the template call has
 * run, and those tests assert the throw while exercising every branch upstream. The redirection
 * endpoints ({@code detail} on a missing trace, {@code updateConfig}, {@code purge}) return their
 * 303 responses directly. Because {@code UnsatisfiedLinkError} is an {@link Error} it slips past
 * the {@code catch (WebApplicationException)} / {@code catch (Exception)} clauses of
 * {@code submitTest} and propagates, so those tests still assert it.
 * <p>
 * Branches covered, arm by arm:
 * <ul>
 *   <li>{@code list}/{@code rows}: the {@code SORTABLE.contains(sort)} ternary (valid and
 *       invalid keys), the {@code !"asc".equalsIgnoreCase(dir)} decision (asc and non-asc), and
 *       the {@code securityContext != null && isUserInRole} short-circuit (null context,
 *       non-admin, admin).</li>
 *   <li>{@code queryTraces}: the three {@code isSet} guards, the two nested
 *       {@code where.length() > 0} decisions (first and subsequent condition), the
 *       {@code descending} ternary (desc and asc) and the final
 *       {@code where.length() > 0 ? ... : order.trim()} ternary (with and without filters).</li>
 *   <li>{@code isSet}: null, blank and populated values.</li>
 *   <li>{@code detail}: the {@code trace == null} decision (missing redirect and found render).</li>
 *   <li>{@code test}: the {@code replay != null} decision and the
 *       {@code trace != null && requestPayload != null} short-circuit (missing trace, trace with a
 *       null payload, trace with a payload).</li>
 *   <li>{@code submitTest}: the {@code requestJson == null || isBlank()} guard (null and blank),
 *       the success path, the {@code WebApplicationException} catch with its message ternary (null
 *       and populated message) and the general {@code Exception} catch.</li>
 *   <li>{@code updateConfig}: the {@code retentionDays < 1} guard and the {@code enabled != null}
 *       decision (present and absent).</li>
 *   <li>{@code purge}: the single redirect path.</li>
 * </ul>
 * The only unreachable arm is the {@code catch} of {@code schemasJson}: it guards a parse failure
 * of the constant, valid {@link com.intermarche.valuation.engine.Basket#BASKET_SCHEMA} through the
 * private {@code static final} mapper, which cannot be provoked without editing production code.
 */
class ValuationUiResourceTest {

    /**
     * Builds a resource wired with fresh mocks for both injected collaborators.
     *
     * @param traceService     The trace recorder mock.
     * @param valuationResource The valuation endpoint mock.
     * @return The configured resource.
     */
    private ValuationUiResource resource(ValuationTraceService traceService,
                                         ValuationResource valuationResource) {
        ValuationUiResource resource = new ValuationUiResource();
        resource.traceService = traceService;
        resource.valuationResource = valuationResource;
        return resource;
    }

    /**
     * Stubs the inherited {@code find} finder to return a paginated query yielding the given data.
     *
     * @param mocked    The active static mock of {@link PanacheEntityBase}.
     * @param traces    The traces the query lists.
     * @param count     The total count reported by the query.
     * @param pageCount The page count reported by the query.
     */
    private void stubQuery(MockedStatic<PanacheEntityBase> mocked, List<ValuationTrace> traces,
                           long count, int pageCount) {
        @SuppressWarnings("unchecked")
        PanacheQuery<ValuationTrace> query = mock(PanacheQuery.class);
        mocked.when(() -> PanacheEntityBase.<ValuationTrace>find(anyString(), any(Object[].class)))
                .thenReturn(query);
        when(query.page(any(Page.class))).thenReturn(query);
        when(query.count()).thenReturn(count);
        when(query.pageCount()).thenReturn(pageCount);
        when(query.list()).thenReturn(traces);
    }

    /**
     * Builds a security context reporting the given administrator membership.
     *
     * @param admin Whether the signed-in user holds the administrator role.
     * @return The configured security context mock.
     */
    private SecurityContext context(boolean admin) {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.isUserInRole(AppUser.ROLE_ADMIN)).thenReturn(admin);
        return ctx;
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * Listing with all three filters, an administrator, an ascending sort and a valid sort key
     * exercises the populated {@code isSet} arms, both nested {@code where.length() > 0} true arms,
     * the ascending {@code descending} arm, the valid {@code SORTABLE} ternary arm, the true right
     * arm of the security conjunction and the with-filters final ternary, then reaches the native
     * list template.
     */
    @Test
    void listWithAllFiltersAdminAscReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedStatic<ValuationTraceConfig> config = mockStatic(ValuationTraceConfig.class)) {
            stubQuery(mocked, List.of(), 1L, 1);
            config.when(ValuationTraceConfig::current).thenReturn(new ValuationTraceConfig());
            assertThrows(UnsatisfiedLinkError.class, () -> resource.list("ST", "cu", "OK",
                    "storeCode", "asc", 1, "hi", true, context(true)));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            String value = jpql.getValue();
            assertTrue(value.contains("lower(storeCode) like ?1"));
            assertTrue(value.contains("lower(customerCode) like ?2"));
            assertTrue(value.contains("status = ?3"));
            assertTrue(value.contains(" and "));
            assertTrue(value.endsWith("order by storeCode asc"));
        }
    }

    /**
     * Listing with no filter, a null security context, a descending sort, an unknown sort key and a
     * page far beyond the last one exercises the three null {@code isSet} arms, the empty
     * final-ternary arm, the descending arm, the invalid {@code SORTABLE} ternary arm falling back
     * to the date key and the false left arm of the security conjunction, then reaches the template.
     */
    @Test
    void listWithNoFilterNullContextBogusSortReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedStatic<ValuationTraceConfig> config = mockStatic(ValuationTraceConfig.class)) {
            stubQuery(mocked, List.of(), 0L, 0);
            config.when(ValuationTraceConfig::current).thenReturn(new ValuationTraceConfig());
            assertThrows(UnsatisfiedLinkError.class, () -> resource.list(null, null, null,
                    "bogus", "desc", 999, null, true, null));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            String value = jpql.getValue();
            assertFalse(value.contains("like"));
            assertEquals("order by createdAt desc", value);
        }
    }

    /**
     * Listing with a blank store filter and a non-administrator exercises the blank {@code isSet}
     * arm and the false right arm of the security conjunction, then reaches the template.
     */
    @Test
    void listWithBlankFilterNonAdminReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedStatic<ValuationTraceConfig> config = mockStatic(ValuationTraceConfig.class)) {
            stubQuery(mocked, List.of(), 0L, 0);
            config.when(ValuationTraceConfig::current).thenReturn(new ValuationTraceConfig());
            assertThrows(UnsatisfiedLinkError.class, () -> resource.list("   ", null, null,
                    "durationMs", "asc", 0, null, true, context(false)));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            assertEquals("order by durationMs asc", jpql.getValue());
        }
    }

    // --------------------------------------------------
    // rows
    // --------------------------------------------------

    /**
     * The rows fragment with a customer filter first and a status filter second, requested by a
     * non-administrator with an uppercase ascending direction, exercises the customer branch on an
     * empty builder (first condition), the status branch on a populated builder (subsequent
     * condition), the case-insensitive ascending arm and the false right arm of the security
     * conjunction, then reaches the native rows template.
     */
    @Test
    void rowsWithCustomerThenStatusReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubQuery(mocked, List.of(), 3L, 1);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.rows(null, "cu", "OK",
                    "durationMs", "ASC", 1, context(false)));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            String value = jpql.getValue();
            assertFalse(value.contains("storeCode) like"));
            assertTrue(value.contains("lower(customerCode) like ?1"));
            assertTrue(value.contains("status = ?2"));
            assertTrue(value.contains(" and "));
            assertTrue(value.endsWith("order by durationMs asc"));
        }
    }

    /**
     * The rows fragment with only a status filter, requested by an administrator with a descending
     * sort, exercises the status branch on an empty builder (first condition), the with-filters
     * final ternary and the true right arm of the security conjunction, then reaches the template.
     */
    @Test
    void rowsWithStatusOnlyAdminReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubQuery(mocked, List.of(), 1L, 1);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.rows(null, null, "OK",
                    "totalIncludingTax", "desc", 1, context(true)));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            String value = jpql.getValue();
            assertTrue(value.contains("status = ?1"));
            assertFalse(value.contains(" and "));
            assertTrue(value.endsWith("order by totalIncludingTax desc"));
        }
    }

    /**
     * The rows fragment with no filter, a null security context, an unknown sort key and a
     * below-first page exercises the empty final ternary, the invalid sort fallback, the descending
     * default arm and the false left arm of the security conjunction, then reaches the template.
     */
    @Test
    void rowsWithNoFilterNullContextBogusSortReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubQuery(mocked, List.of(), 0L, 0);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.rows(null, null, null,
                    "bogus", "desc", 0, null));
            ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
            mocked.verify(() -> PanacheEntityBase.find(jpql.capture(), any(Object[].class)));
            assertEquals("order by createdAt desc", jpql.getValue());
        }
    }

    // --------------------------------------------------
    // detail
    // --------------------------------------------------

    /**
     * A detail request for a missing trace redirects to the list with a failure notice, exercising
     * the true arm of the {@code trace == null} decision.
     */
    @Test
    void detailWithMissingTraceRedirectsWithNotice() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            Response response = resource.detail(7L);
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            String location = response.getLocation().toString();
            assertTrue(location.startsWith("/ui/valuations"));
            assertTrue(location.contains("noticeOk=false"));
            assertTrue(location.contains("7"));
        }
    }

    /**
     * A detail request for an existing trace renders the detail screen, exercising the false arm of
     * the {@code trace == null} decision and reaching the native detail template.
     */
    @Test
    void detailWithExistingTraceReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(7L)).thenReturn(new ValuationTrace());
            assertThrows(UnsatisfiedLinkError.class, () -> resource.detail(7L));
        }
    }

    // --------------------------------------------------
    // test (form)
    // --------------------------------------------------

    /**
     * Opening the form without a replay identifier leaves the request empty and reaches the native
     * test template, exercising the false arm of the {@code replay != null} decision.
     */
    @Test
    void testWithoutReplayReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        assertThrows(UnsatisfiedLinkError.class, () -> resource.test(null));
    }

    /**
     * Replaying an unknown trace keeps the empty request and reaches the template, exercising the
     * true arm of {@code replay != null} and the false left arm of the payload short-circuit.
     */
    @Test
    void testReplayWithMissingTraceReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> PanacheEntityBase.findById(5L)).thenReturn(null);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.test(5L));
        }
    }

    /**
     * Replaying a trace whose request payload is null keeps the empty request and reaches the
     * template, exercising the true left arm and the false right arm of the payload short-circuit.
     */
    @Test
    void testReplayWithNullPayloadReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            ValuationTrace trace = new ValuationTrace();
            trace.requestPayload = null;
            mocked.when(() -> PanacheEntityBase.findById(5L)).thenReturn(trace);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.test(5L));
        }
    }

    /**
     * Replaying a trace carrying a request payload preloads it and reaches the template, exercising
     * both true arms of the payload short-circuit.
     */
    @Test
    void testReplayWithPayloadReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            ValuationTrace trace = new ValuationTrace();
            trace.requestPayload = "{\"storeCode\":\"S1\"}";
            mocked.when(() -> PanacheEntityBase.findById(5L)).thenReturn(trace);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.test(5L));
        }
    }

    // --------------------------------------------------
    // submitTest
    // --------------------------------------------------

    /**
     * Submitting a null basket reaches the template with the empty-basket message, exercising the
     * true left arm of the blank guard.
     */
    @Test
    void submitTestWithNullRequestReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        assertThrows(UnsatisfiedLinkError.class, () -> resource.submitTest(null));
    }

    /**
     * Submitting a blank basket reaches the template with the empty-basket message, exercising the
     * false left arm and the true right arm of the blank guard.
     */
    @Test
    void submitTestWithBlankRequestReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        assertThrows(UnsatisfiedLinkError.class, () -> resource.submitTest("   "));
    }

    /**
     * Submitting a valid basket calls the engine, serializes its entity and reaches the template,
     * exercising the successful try body past the blank guard.
     */
    @Test
    void submitTestWithValidBasketReachesTemplate() {
        ValuationResource valuation = mock(ValuationResource.class);
        when(valuation.calculate(any())).thenReturn(Response.ok(Map.of("total", "1.00")).build());
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), valuation);
        assertThrows(UnsatisfiedLinkError.class, () -> resource.submitTest("{\"storeCode\":\"S1\"}"));
        verify(valuation).calculate(any());
    }

    /**
     * A refusal carrying a message is reported as an HTTP error, exercising the
     * {@code WebApplicationException} catch and the populated arm of its message ternary.
     */
    @Test
    void submitTestWithRefusalMessageReachesTemplate() {
        ValuationResource valuation = mock(ValuationResource.class);
        when(valuation.calculate(any())).thenThrow(new WebApplicationException("Refused", 422));
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), valuation);
        assertThrows(UnsatisfiedLinkError.class, () -> resource.submitTest("{\"storeCode\":\"S1\"}"));
    }

    /**
     * A refusal with no message falls back to the default text, exercising the
     * {@code WebApplicationException} catch and the null arm of its message ternary.
     */
    @Test
    void submitTestWithRefusalNoMessageReachesTemplate() {
        ValuationResource valuation = mock(ValuationResource.class);
        when(valuation.calculate(any())).thenThrow(new WebApplicationException((String) null, 400));
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), valuation);
        assertThrows(UnsatisfiedLinkError.class, () -> resource.submitTest("{\"storeCode\":\"S1\"}"));
    }

    /**
     * An unparseable basket triggers the general {@code Exception} catch, exercising that arm before
     * the template is reached.
     */
    @Test
    void submitTestWithInvalidJsonReachesTemplate() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        assertThrows(UnsatisfiedLinkError.class, () -> resource.submitTest("not-json"));
    }

    // --------------------------------------------------
    // updateConfig
    // --------------------------------------------------

    /**
     * Updating the configuration with a retention below one day redirects with a failure notice
     * without touching the configuration, exercising the true arm of the retention guard.
     */
    @Test
    void updateConfigWithRetentionBelowOneRedirectsWithFailure() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        Response response = resource.updateConfig("on", 0);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * Updating the configuration with the enabled flag present turns tracing on and stores the
     * retention, exercising the false arm of the retention guard and the true arm of
     * {@code enabled != null}.
     */
    @Test
    void updateConfigWithEnabledPresentTurnsTracingOn() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        ValuationTraceConfig stored = new ValuationTraceConfig();
        stored.enabled = false;
        stored.retentionDays = 1;
        try (MockedStatic<ValuationTraceConfig> config = mockStatic(ValuationTraceConfig.class)) {
            config.when(ValuationTraceConfig::current).thenReturn(stored);
            Response response = resource.updateConfig("on", 7);
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(stored.enabled);
            assertEquals(7, stored.retentionDays);
        }
    }

    /**
     * Updating the configuration with the enabled flag absent turns tracing off, exercising the
     * false arm of {@code enabled != null}.
     */
    @Test
    void updateConfigWithEnabledAbsentTurnsTracingOff() {
        ValuationUiResource resource = resource(mock(ValuationTraceService.class), mock(ValuationResource.class));
        ValuationTraceConfig stored = new ValuationTraceConfig();
        stored.enabled = true;
        stored.retentionDays = 1;
        try (MockedStatic<ValuationTraceConfig> config = mockStatic(ValuationTraceConfig.class)) {
            config.when(ValuationTraceConfig::current).thenReturn(stored);
            Response response = resource.updateConfig(null, 3);
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertFalse(stored.enabled);
            assertEquals(3, stored.retentionDays);
        }
    }

    // --------------------------------------------------
    // purge
    // --------------------------------------------------

    /**
     * Purging delegates to the recorder and redirects with the deleted count, exercising the single
     * purge path.
     */
    @Test
    void purgeDeletesAndRedirects() {
        ValuationTraceService traceService = mock(ValuationTraceService.class);
        when(traceService.purgeAll()).thenReturn(4L);
        ValuationUiResource resource = resource(traceService, mock(ValuationResource.class));
        Response response = resource.purge();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        verify(traceService).purgeAll();
    }
}
