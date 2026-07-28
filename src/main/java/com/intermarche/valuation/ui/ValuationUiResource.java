package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.ValuationTrace;
import com.intermarche.valuation.domain.ValuationTraceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.engine.Basket;
import com.intermarche.valuation.engine.ValuationResource;
import com.intermarche.valuation.engine.ValuationTraceService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screens browsing recorded valuations and submitting new ones for testing.
 * <p>
 * The test form is generated from {@link Basket#BASKET_SCHEMA}, the same schema the
 * valuation endpoint enforces, so anything the form produces is accepted by the engine
 * and anything the engine rejects can be reproduced here.
 * <p>
 * A recorded trace can be replayed: its request is loaded back into the form, which is
 * the quickest way to check whether a disputed basket still behaves the same after a
 * configuration change.
 */
@Path("/ui/valuations")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class ValuationUiResource {

    private static final Logger LOGGER = Logger.getLogger(ValuationUiResource.class);

    /**
     * Number of traces displayed per page.
     */
    private static final int PAGE_SIZE = 25;

    /**
     * Base path of the screen, used to build its links.
     */
    private static final String BASE_PATH = "/ui/valuations";

    /**
     * Sort key ordering traces by recording time.
     */
    private static final String SORT_DATE = "createdAt";

    /**
     * Sort key ordering traces by store code.
     */
    private static final String SORT_STORE = "storeCode";

    /**
     * Sort key ordering traces by valuation duration.
     */
    private static final String SORT_DURATION = "durationMs";

    /**
     * Sort key ordering traces by total amount.
     */
    private static final String SORT_TOTAL = "totalIncludingTax";

    /**
     * Every sort key accepted by this screen.
     */
    private static final Set<String> SORTABLE =
            Set.of(SORT_DATE, SORT_STORE, SORT_DURATION, SORT_TOTAL);

    /**
     * Recorder used to purge traces on demand.
     */
    @Inject
    ValuationTraceService traceService;

    /**
     * The valuation endpoint, invoked in-process by the test form.
     */
    @Inject
    ValuationResource valuationResource;

    /**
     * Mapper used to parse the submitted basket and pretty-print the evaluation.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the trace list screen.
         *
         * @param view   The view model carrying the page, the filters and the sort state.
         * @param config The current tracing configuration.
         * @return The template instance to render.
         */
        public static native TemplateInstance list(ListView<ValuationTrace> view,
                                                   ValuationTraceConfig config);

        /**
         * Renders only the table rows, for the periodic refresh of the list.
         *
         * @param view The same view model as {@link #list}, carrying the current rows.
         * @return The template instance to render.
         */
        @io.quarkus.qute.Location("ValuationUiResource/rows.html")
        public static native TemplateInstance rows(ListView<ValuationTrace> view);

        /**
         * Renders a single trace, request and response side by side.
         *
         * @param trace The trace to display.
         * @return The template instance to render.
         */
        public static native TemplateInstance detail(ValuationTrace trace);

        /**
         * Renders the test form.
         *
         * @param schemasJson The generator's schema map, keyed by the single type "BASKET".
         * @param requestJson The basket to preload, an empty object when starting fresh.
         * @param responseJson The evaluation returned by the last submission, may be null.
         * @param error       An error message to display, may be null.
         * @return The template instance to render.
         */
        public static native TemplateInstance test(String schemasJson, String requestJson,
                                                   String responseJson, String error);
    }

    // --------------------------------------------------
    // Browsing
    // --------------------------------------------------

    /**
     * Displays a page of recorded valuations.
     *
     * @param store    A store code the trace must carry, may be null or blank.
     * @param customer A customer code the trace must carry, may be null or blank.
     * @param status   A status to filter on, may be null or blank.
     * @param sort     The column driving the sort, defaulting to the recording time.
     * @param dir      The sort direction, defaulting to newest first.
     * @param page     The requested page number, defaulting to the first one.
     * @param notice   A message to display, typically the outcome of a previous action.
     * @param noticeOk Whether the message reports a success.
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered list screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("store") String store,
                                 @QueryParam("customer") String customer,
                                 @QueryParam("status") String status,
                                 @QueryParam("sort") @DefaultValue(SORT_DATE) String sort,
                                 @QueryParam("dir") @DefaultValue("desc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method list");
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_DATE;
        boolean descending = !"asc".equalsIgnoreCase(dir);
        PanacheQuery<ValuationTrace> query = queryTraces(store, customer, status, sortKey, descending)
                .page(Page.ofSize(PAGE_SIZE));
        long totalCount = query.count();
        int pageCount = Math.max(1, query.pageCount());
        int currentPage = Math.min(Math.max(page, 1), pageCount);
        List<ValuationTrace> traces = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("store", store);
        filters.put("customer", customer);
        filters.put("status", status);
        ListView<ValuationTrace> view = new ListView<>(traces, BASE_PATH, filters,
                sortKey, descending, currentPage, pageCount, totalCount, PAGE_SIZE, "valuation",
                notice, noticeOk,
                securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN));
        return Templates.list(view, ValuationTraceConfig.current());
    }

    /**
     * Returns only the table rows for the current filters, for the periodic in-place
     * refresh of the list. Same query parameters as {@link #list}, minus the notice.
     *
     * @param store    Filter on the store code, may be null.
     * @param customer Filter on a fragment of the customer code, may be null.
     * @param status   Filter on the status, may be null.
     * @param sort     The sort key.
     * @param dir      The sort direction.
     * @param page     The requested page.
     * @param securityContext The security context, for the write flag.
     * @return The rendered rows fragment.
     */
    @GET
    @jakarta.ws.rs.Path("/rows")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance rows(@QueryParam("store") String store,
                                 @QueryParam("customer") String customer,
                                 @QueryParam("status") String status,
                                 @QueryParam("sort") @DefaultValue(SORT_DATE) String sort,
                                 @QueryParam("dir") @DefaultValue("desc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method rows");
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_DATE;
        boolean descending = !"asc".equalsIgnoreCase(dir);
        PanacheQuery<ValuationTrace> query = queryTraces(store, customer, status, sortKey, descending)
                .page(Page.ofSize(PAGE_SIZE));
        long totalCount = query.count();
        int pageCount = Math.max(1, query.pageCount());
        int currentPage = Math.min(Math.max(page, 1), pageCount);
        List<ValuationTrace> traces = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("store", store);
        filters.put("customer", customer);
        filters.put("status", status);
        ListView<ValuationTrace> view = new ListView<>(traces, BASE_PATH, filters,
                sortKey, descending, currentPage, pageCount, totalCount, PAGE_SIZE, "valuation",
                null, true,
                securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN));
        return Templates.rows(view);
    }

    /**
     * Displays a single recorded valuation.
     *
     * @param id The identifier of the trace to display.
     * @return The rendered detail screen, or a 404 response when the trace does not exist.
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response detail(@PathParam("id") Long id) {
        LOGGER.debug("Entering method detail with id: " + id);
        ValuationTrace trace = ValuationTrace.findById(id);
        if (trace == null) {
            // The trace is gone (e.g. a stale bookmark, or replayed after a restart with a
            // recreated database). Send the user back to the list with a plain explanation
            // rather than a dead-end error page.
            java.net.URI target = jakarta.ws.rs.core.UriBuilder.fromPath(BASE_PATH)
                    .queryParam("notice", "Valuation " + id + " no longer exists.")
                    .queryParam("noticeOk", false)
                    .build();
            return Response.seeOther(target).build();
        }
        return Response.ok(Templates.detail(trace)).build();
    }

    // --------------------------------------------------
    // Testing
    // --------------------------------------------------

    /**
     * Displays the test form.
     *
     * @param replay The identifier of a trace whose request should be preloaded, may be null.
     * @return The rendered test form.
     */
    @GET
    @Path("/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance test(@QueryParam("replay") Long replay) {
        LOGGER.debug("Entering method test");
        String request = "{}";
        if (replay != null) {
            ValuationTrace trace = ValuationTrace.findById(replay);
            if (trace != null && trace.requestPayload != null) {
                request = trace.requestPayload;
            }
        }
        return Templates.test(schemasJson(), request, null, null);
    }

    /**
     * Submits a basket to the valuation endpoint and displays the outcome.
     * <p>
     * The engine is called in-process rather than over HTTP: the caller is already
     * authenticated here, and a loopback request would only add a failure mode.
     *
     * @param requestJson The basket to value, as produced by the form.
     * @return The rendered test form carrying the response or the error.
     */
    @POST
    @Path("/new")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response submitTest(@FormParam("request") String requestJson) {
        LOGGER.debug("Entering method submitTest");
        if (requestJson == null || requestJson.isBlank()) {
            return Response.ok(Templates.test(schemasJson(), "{}", null,
                    "The basket is empty.")).build();
        }
        try {
            Basket basket = MAPPER.readValue(requestJson, Basket.class);
            Response result = valuationResource.calculate(basket);
            String response = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(result.getEntity());
            return Response.ok(Templates.test(schemasJson(), requestJson, response, null)).build();
        } catch (WebApplicationException e) {
            String message = e.getMessage() == null ? "The valuation was refused." : e.getMessage();
            return Response.ok(Templates.test(schemasJson(), requestJson, null,
                    "HTTP " + e.getResponse().getStatus() + " \u2014 " + message)).build();
        } catch (Exception e) {
            LOGGER.error("Test valuation failed", e);
            return Response.ok(Templates.test(schemasJson(), requestJson, null,
                    e.getMessage())).build();
        }
    }

    // --------------------------------------------------
    // Configuration and maintenance
    // --------------------------------------------------

    /**
     * Updates the tracing configuration.
     *
     * @param enabled       Present when tracing must stay on.
     * @param retentionDays Number of days a trace is kept.
     * @return A redirection to the list screen carrying the outcome.
     */
    @POST
    @Path("/config")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response updateConfig(@FormParam("enabled") String enabled,
                                 @FormParam("retentionDays") int retentionDays) {
        LOGGER.debug("Entering method updateConfig");
        if (retentionDays < 1) {
            return redirectWithNotice("The retention must be at least one day.", false);
        }
        ValuationTraceConfig config = ValuationTraceConfig.current();
        config.enabled = enabled != null;
        config.retentionDays = retentionDays;
        return redirectWithNotice("Tracing configuration updated.", true);
    }

    /**
     * Deletes every recorded trace.
     *
     * @return A redirection to the list screen carrying the outcome.
     */
    @POST
    @Path("/purge")
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @Produces(MediaType.TEXT_HTML)
    public Response purge() {
        long deleted = traceService.purgeAll();
        return redirectWithNotice(deleted + " trace(s) deleted.", true);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds the query backing the list screen.
     *
     * @param store      A store code filter, may be null or blank.
     * @param customer   A customer code filter, may be null or blank.
     * @param status     A status filter, may be null or blank.
     * @param sort       The validated sort key.
     * @param descending Whether the sort is descending.
     * @return The query, not yet paginated.
     */
    private PanacheQuery<ValuationTrace> queryTraces(String store, String customer, String status,
                                                     String sort, boolean descending) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (isSet(store)) {
            where.append("lower(storeCode) like ?").append(params.size() + 1);
            params.add("%" + store.trim().toLowerCase() + "%");
        }
        if (isSet(customer)) {
            if (where.length() > 0) {
                where.append(" and ");
            }
            where.append("lower(customerCode) like ?").append(params.size() + 1);
            params.add("%" + customer.trim().toLowerCase() + "%");
        }
        if (isSet(status)) {
            if (where.length() > 0) {
                where.append(" and ");
            }
            where.append("status = ?").append(params.size() + 1);
            params.add(status.trim());
        }
        String order = " order by " + sort + (descending ? " desc" : " asc");
        String query = where.length() > 0 ? where + order : order.trim();
        return ValuationTrace.find(query, params.toArray());
    }

    /**
     * Builds a redirection to the list screen carrying a message to display.
     *
     * @param notice  The message shown once on the list screen.
     * @param success Whether the message reports a success.
     * @return A 303 See Other response.
     */
    private Response redirectWithNotice(String notice, boolean success) {
        URI target = UriBuilder.fromPath(BASE_PATH)
                .queryParam("notice", notice)
                .queryParam("noticeOk", success)
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Wraps the basket schema in the object the form generator expects.
     * <p>
     * The generator keys its schemas by type and looks the selected one up by name; the
     * test form has a single type, "BASKET", so the map holds exactly one entry.
     * <p>
     * The schema is minified first. It is declared as an indented text block, and a JSON
     * template escapes the control characters U+0000..U+001F, so its literal newlines
     * would reach the browser as escaped sequences and break {@code JSON.parse}. Parsing
     * and re-serializing collapses it to a single line, which embeds cleanly.
     *
     * @return A compact JSON object of the form {@code {"BASKET": <schema>}}.
     */
    private String schemasJson() {
        try {
            Object schema = MAPPER.readValue(Basket.BASKET_SCHEMA, Object.class);
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("BASKET", schema);
            return MAPPER.writeValueAsString(wrapper);
        } catch (Exception e) {
            LOGGER.error("Could not prepare the basket schema", e);
            return "{}";
        }
    }

    /**
     * Indicates whether a filter value carries an actual criterion.
     *
     * @param value The candidate value, may be null.
     * @return {@code true} when the value is neither null nor blank.
     */
    private boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
