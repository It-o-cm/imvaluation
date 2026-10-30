package com.intermarche.valuation.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.engine.EngineTrait;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Qute-backed administration screens for {@link Offer} entities.
 * <p>
 * The editor renders a form driven by the JSON Schema declared by the factory handling the
 * offer type. Schemas come from {@link OfferSchemaRegistry}, so the very same definition
 * validates the specification here and inside the valuation engine.
 * <p>
 * Server-side validation is authoritative: every submitted specification is passed through
 * {@link EngineTrait#processSpecification} before being persisted.
 */
@Path("/ui/offers")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class OfferUiResource implements EngineTrait {

    private static final Logger LOGGER = Logger.getLogger(OfferUiResource.class);

    /**
     * Number of offers displayed per page on the list screen.
     */
    private static final int PAGE_SIZE = 25;

    /**
     * Base path of the screen, used to build its links.
     */
    private static final String BASE_PATH = "/ui/offers";

    /**
     * Sort key ordering offers by their business code.
     */
    private static final String SORT_CODE = "code";

    /**
     * Sort key ordering offers by their type discriminator.
     */
    private static final String SORT_TYPE = "type";

    /**
     * Sort key ordering offers by the number of EANs extracted from their specification.
     */
    private static final String SORT_EANS = "eans";

    /**
     * Every sort key accepted by this screen.
     * <p>
     * The targets column is deliberately absent: an offer points at several stores and
     * groups at once, so there is no single value to order rows by.
     */
    private static final Set<String> SORTABLE = Set.of(SORT_CODE, SORT_TYPE, SORT_EANS);

    /**
     * Registry exposing the JSON Schema of every configurable offer type.
     */
    @Inject
    OfferSchemaRegistry schemaRegistry;

    /**
     * The signed-in user's perimeter (store scope, allowed offer types), applied to filter the
     * lists, to restrict the form's type choices and to refuse out-of-scope writes (spec §2).
     */
    @Inject
    com.intermarche.valuation.security.CurrentUser currentUser;

    /**
     * The localised interface labels, used for the form validation messages shown back to the user
     * (spec-exploitation §1). Engine/schema validation messages stay in their original English.
     */
    @Inject
    com.intermarche.valuation.i18n.Messages messages;

    /**
     * The natural-language drafting assistant, powering the AI panel of the form.
     */
    @Inject
    com.intermarche.valuation.ai.OfferDraftService draftService;

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     * <p>
     * Templates live under {@code src/main/resources/templates/OfferUiResource}.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the offer list screen.
         *
         * @param view  The view model carrying the page, the filters and the sort state.
         * @param types The offer types available in the type filter.
         * @return The template instance to render.
         */
        public static native TemplateInstance list(ListView<Offer> view, Set<String> types);

        /**
         * Renders the offer creation or edition form.
         *
         * @param offer       The offer being edited, or null when creating.
         * @param types       Every offer type having a registered schema.
         * @param schemasJson The type-to-schema map serialized as a JSON object.
         * @param storeCodes      The store codes currently linked, comma separated.
         * @param storeGroupCodes The store group codes currently linked, comma separated.
         * @param error       An error message to display, may be null.
         * @param aiAvailable Whether the natural-language assistant is configured.
         * @return The template instance to render.
         */
        public static native TemplateInstance form(Offer offer, Set<String> types, String schemasJson,
                                                   String specificationJson, String storeCodes,
                                                   String storeGroupCodes, String error, boolean aiAvailable);
    }

    // --------------------------------------------------
    // Screens
    // --------------------------------------------------

    /**
     * Displays a page of offers, filtered and sorted according to the request.
     * <p>
     * Every filter is optional and combines with the others. Page numbers are one-based
     * and clamped to the available range, so a stale or hand-edited link never yields an
     * empty screen when results exist.
     *
     * @param search A fragment matched against the offer code, may be null or blank.
     * @param type   The offer type to filter on, may be null or blank.
     * @param target A store or store group code the offer must target, may be null or blank.
     * @param ean    An EAN the offer must reference, may be null or blank.
     * @param article An article EAN the offer's resolved targeting must cover, may be null or blank.
     * @param sort   The column driving the sort, defaulting to the offer code.
     * @param dir    The sort direction, either "asc" or "desc".
     * @param page   The requested page number, defaulting to the first one.
     * @param notice   A one-shot message to display, typically an import outcome.
     * @param noticeOk Whether the message reports a success.
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered list screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String search,
                                 @QueryParam("type") String type,
                                 @QueryParam("target") String target,
                                 @QueryParam("ean") String ean,
                                 @QueryParam("article") String article,
                                 @QueryParam("sort") @DefaultValue(SORT_CODE) String sort,
                                 @QueryParam("dir") @DefaultValue("asc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method list");
        // An unknown sort key would break the query, so fall back to the default column.
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_CODE;
        boolean descending = "desc".equalsIgnoreCase(dir);
        List<Offer> offers;
        long totalCount;
        int pageCount;
        int currentPage;
        if (isSet(article)) {
            // The article filter (spec-exploitation §4) resolves the offer targeting exactly as the
            // engine does — targetEans ∪ resolved families − excludedEans — which cannot be expressed
            // in JPQL, so the matching offers are filtered in memory and paginated here.
            List<Offer> matching = filterByArticle(
                    queryOffers(search, type, target, ean, sortKey, descending).list(), article.trim());
            totalCount = matching.size();
            pageCount = Math.max(1, (int) Math.ceil(totalCount / (double) PAGE_SIZE));
            currentPage = Math.min(Math.max(page, 1), pageCount);
            int start = (currentPage - 1) * PAGE_SIZE;
            offers = start >= matching.size() ? new ArrayList<>()
                    : new ArrayList<>(matching.subList(start, Math.min(start + PAGE_SIZE, matching.size())));
        } else {
            PanacheQuery<Offer> query = queryOffers(search, type, target, ean, sortKey, descending)
                    .page(Page.ofSize(PAGE_SIZE));
            totalCount = query.count();
            pageCount = Math.max(1, query.pageCount());
            currentPage = Math.min(Math.max(page, 1), pageCount);
            offers = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        }
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("q", search);
        filters.put("type", type);
        filters.put("target", target);
        filters.put("ean", ean);
        filters.put("article", article);
        ListView<Offer> view = new ListView<>(offers, BASE_PATH, filters, sortKey, descending,
                currentPage, pageCount, totalCount, PAGE_SIZE, "offer", notice, noticeOk,
                securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN));
        return Templates.list(view, schemaRegistry.getKnownTypes());
    }

    /**
     * Exports the offers matching the current filters as a CSV stream.
     * <p>
     * The produced file uses the exact format consumed by {@code /offers/import}: five
     * pipe-separated columns, with comma-separated code lists, so an export can be edited
     * and fed straight back into the importer.
     * <p>
     * The export ignores pagination and covers every matching offer, since exporting a
     * single screen of results is rarely what the user wants.
     *
     * @param search A fragment matched against the offer code, may be null or blank.
     * @param type   The offer type to filter on, may be null or blank.
     * @param target A store or store group code the offer must target, may be null or blank.
     * @param ean    An EAN the offer must reference, may be null or blank.
     * @param article An article EAN the offer's resolved targeting must cover, may be null or blank.
     * @param sort   The column driving the sort, defaulting to the offer code.
     * @param dir    The sort direction, either "asc" or "desc".
     * @return A CSV attachment holding every matching offer.
     */
    @GET
    @jakarta.ws.rs.Path("/export")
    @Produces("text/csv; charset=UTF-8")
    public Response export(@QueryParam("q") String search,
                           @QueryParam("type") String type,
                           @QueryParam("target") String target,
                           @QueryParam("ean") String ean,
                           @QueryParam("article") String article,
                           @QueryParam("sort") @DefaultValue(SORT_CODE) String sort,
                           @QueryParam("dir") @DefaultValue("asc") String dir) {
        LOGGER.debug("Entering method export");
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_CODE;
        boolean descending = "desc".equalsIgnoreCase(dir);
        List<Offer> offers = queryOffers(search, type, target, ean, sortKey, descending).list();
        if (isSet(article)) {
            // Same resolved-targeting filter as the screen (spec-exploitation §4), so the export is
            // consistent with what the list shows for that article.
            offers = filterByArticle(offers, article.trim());
        }
        StringBuilder csv = new StringBuilder("CODE|TYPE|SPECIFICATION|STORE_CODES|STORE_GROUP_CODES\n");
        for (Offer offer : offers) {
            csv.append(sanitize(offer.code)).append('|')
                    .append(sanitize(offer.type)).append('|')
                    .append(sanitize(offer.specification)).append('|')
                    .append(joinStoreCodes(offer)).append('|')
                    .append(joinStoreGroupCodes(offer)).append('\n');
        }
        LOGGER.debug("Exiting method export with " + offers.size() + " offers");
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"offers.csv\"")
                .build();
    }

    /**
     * Strips the characters that would break the pipe-separated line format and neutralises
     * spreadsheet formula injection.
     * <p>
     * Specifications are JSON documents written by the editor and never contain pipes or
     * newlines, but a value imported from elsewhere might; replacing them keeps the export
     * parseable rather than silently producing a corrupt file. In addition, a cell whose first
     * character is one a spreadsheet reads as a formula ({@code = + - @}, or a leading tab/CR) is
     * prefixed with a single quote so opening the export in Excel/Sheets cannot execute an offer
     * code that came from an untrusted feed (report §3).
     *
     * @param value The raw value, may be null.
     * @return The value with separators neutralised and formulas defused, never null.
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", " ").replace("\n", " ").replace("|", "/");
        if (!cleaned.isEmpty() && "=+-@\t".indexOf(cleaned.charAt(0)) >= 0) {
            cleaned = "'" + cleaned;
        }
        return cleaned;
    }

    /**
     * Displays an empty form for creating a new offer.
     *
     * @param type The offer type to preselect, may be null.
     * @return The rendered creation form.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @GET
    @jakarta.ws.rs.Path("/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance create(@QueryParam("type") String type) {
        LOGGER.debug("Entering method create");
        Offer offer = new Offer();
        offer.type = type;
        return Templates.form(offer, allowedTypesForForm(),
                HtmlSafeJson.forScript(buildSchemasJson()),
                HtmlSafeJson.forScript(offer.specification), "", "", null, draftService.isAvailable());
    }

    /**
     * Displays the edition form of an existing offer.
     *
     * @param id The identifier of the offer to edit.
     * @return The rendered edition form, or a 404 response when the offer does not exist.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @GET
    @jakarta.ws.rs.Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response edit(@PathParam("id") Long id) {
        LOGGER.debug("Entering method edit with id: " + id);
        Offer offer = Offer.findById(id);
        if (offer == null) {
            LOGGER.error("Offer with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity(messages.format("offers.error.notFound", id)).build();
        }
        TemplateInstance template = Templates.form(
                offer,
                allowedTypesForForm(),
                HtmlSafeJson.forScript(buildSchemasJson()),
                HtmlSafeJson.forScript(offer.specification),
                joinStoreCodes(offer),
                joinStoreGroupCodes(offer),
                null,
                draftService.isAvailable());
        return Response.ok(template).build();
    }

    // --------------------------------------------------
    // Mutations
    // --------------------------------------------------

    /**
     * Creates a new offer from the submitted form.
     *
     * @param code            The unique offer code.
     * @param type            The offer type discriminator.
     * @param specification   The JSON specification produced by the schema-driven form.
     * @param storeCodes      The linked store codes, comma separated.
     * @param storeGroupCodes The linked store group codes, comma separated.
     * @param active          The active flag; an unchecked checkbox posts nothing, meaning inactive.
     * @param validFrom       The validity-window start (ISO local date-time), blank for open.
     * @param validTo         The validity-window end (ISO local date-time), blank for open.
     * @return A redirection to the list screen, or the form again when validation fails.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @POST
    @jakarta.ws.rs.Path("/new")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response save(@FormParam("code") String code,
                         @FormParam("type") String type,
                         @FormParam("specification") String specification,
                         @FormParam("storeCodes") String storeCodes,
                         @FormParam("storeGroupCodes") String storeGroupCodes,
                         @FormParam("active") String active,
                         @FormParam("validFrom") String validFrom,
                         @FormParam("validTo") String validTo) {
        LOGGER.debug("Entering method save for code: " + code);
        Offer offer = new Offer();
        offer.code = code;
        String error = applyForm(offer, type, specification, storeCodes, storeGroupCodes, true);
        if (error == null) {
            error = applyActivation(offer, active, validFrom, validTo);
        }
        if (error != null) {
            return renderFormWithError(offer, storeCodes, storeGroupCodes, error);
        }
        offer.persist();
        LOGGER.debug("Exiting method save. Created ID: " + offer.id);
        return redirectToList();
    }

    /**
     * Updates an existing offer from the submitted form.
     *
     * @param id              The identifier of the offer to update.
     * @param type            The offer type discriminator.
     * @param specification   The JSON specification produced by the schema-driven form.
     * @param storeCodes      The linked store codes, comma separated.
     * @param storeGroupCodes The linked store group codes, comma separated.
     * @param active          The active flag; an unchecked checkbox posts nothing, meaning inactive.
     * @param validFrom       The validity-window start (ISO local date-time), blank for open.
     * @param validTo         The validity-window end (ISO local date-time), blank for open.
     * @return A redirection to the list screen, or the form again when validation fails.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @POST
    @jakarta.ws.rs.Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response update(@PathParam("id") Long id,
                           @FormParam("type") String type,
                           @FormParam("specification") String specification,
                           @FormParam("storeCodes") String storeCodes,
                           @FormParam("storeGroupCodes") String storeGroupCodes,
                           @FormParam("active") String active,
                           @FormParam("validFrom") String validFrom,
                           @FormParam("validTo") String validTo) {
        LOGGER.debug("Entering method update for id: " + id);
        Offer offer = Offer.findById(id);
        if (offer == null) {
            LOGGER.error("Offer with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity(messages.format("offers.error.notFound", id)).build();
        }
        String error = applyForm(offer, type, specification, storeCodes, storeGroupCodes, false);
        if (error == null) {
            error = applyActivation(offer, active, validFrom, validTo);
        }
        if (error != null) {
            return renderFormWithError(offer, storeCodes, storeGroupCodes, error);
        }
        LOGGER.debug("Exiting method update");
        return redirectToList();
    }

    /**
     * Deletes an offer.
     *
     * @param id The identifier of the offer to delete.
     * @return A redirection to the list screen.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @POST
    @jakarta.ws.rs.Path("/{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional(rollbackOn = Exception.class)
    public Response delete(@PathParam("id") Long id) {
        LOGGER.debug("Entering method delete for id: " + id);
        boolean deleted = Offer.deleteById(id);
        if (!deleted) {
            // Align with edit/update: a missing offer is a 404, never a silent redirect.
            LOGGER.error("Offer with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity(messages.format("offers.error.notFound", id)).build();
        }
        LOGGER.debug("Exiting method delete. Result: " + deleted);
        return redirectToList();
    }

    // --------------------------------------------------
    // Natural-language drafting
    // --------------------------------------------------

    /**
     * Request body of {@link #aiDraft(AiDraftRequest)}: the plain-language description of
     * the offer or advantage to draft.
     * <p>
     * Fields are read by Jackson; the record stays public for the resource method signature.
     *
     * @param prompt The natural-language description, one to a few sentences.
     */
    public record AiDraftRequest(String prompt) {
    }

    /**
     * Turns a plain-language description into a draft offer specification, or a declaration
     * of impossibility with the reason and possibly a close alternative.
     * <p>
     * The answer is always a 200 carrying one of the three statuses of
     * {@link com.intermarche.valuation.ai.OfferDraftResult}: the panel is a UI call, and
     * the browser renders the outcome rather than branching on a status code. A draft is
     * never persisted here — it pre-fills the form and the administrator reviews it before
     * saving, exactly like a hand-written specification.
     *
     * @param request The description to draft from, may be null.
     * @return The draft outcome, never null.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @POST
    @jakarta.ws.rs.Path("/ai-draft")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public com.intermarche.valuation.ai.OfferDraftResult aiDraft(AiDraftRequest request) {
        LOGGER.debug("Entering method aiDraft");
        try {
            return draftService.draft(request == null ? null : request.prompt());
        } catch (Exception e) {
            // The service already narrows its failures; this catch is the last-resort net
            // so the panel always gets a renderable JSON answer.
            LOGGER.error("The AI draft endpoint failed unexpectedly", e);
            return com.intermarche.valuation.ai.OfferDraftResult.error(
                    messages.get("offers.ai.error.unexpected"));
        }
    }

    /**
     * The coverage preview of a free-form targeting condition (spec-ciblage-where §5): the
     * exact number of products the fragment covers and a capped sample of them, so the
     * administrator sees what an empty "Eligible products" field really targets — and a
     * condition that matches nothing says so before the offer is saved. Read-only, hence a
     * GET; a fragment that fails the grammar comes back as an error payload, never a 500.
     *
     * @param where The condition text, may be null or blank.
     * @return The count, the sample and the outcome, never null.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @GET
    @jakarta.ws.rs.Path("/where-preview")
    @Produces(MediaType.APPLICATION_JSON)
    public WherePreview wherePreview(@QueryParam("where") String where) {
        LOGGER.debug("Entering method wherePreview");
        if (where == null || where.isBlank()) {
            return WherePreview.error(messages.get("offers.ai.error.empty"));
        }
        try {
            long count = com.intermarche.valuation.engine.TargetWhere.countOf(where);
            java.util.List<java.util.Map<String, String>> products = new java.util.ArrayList<>();
            for (com.intermarche.valuation.domain.Product product
                    : com.intermarche.valuation.engine.TargetWhere.sampleOf(where, 20)) {
                java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
                entry.put("ean", product.ean);
                entry.put("name", product.name == null ? "" : product.name);
                products.add(entry);
            }
            return new WherePreview(count, products, null);
        } catch (IllegalArgumentException e) {
            return WherePreview.error(e.getMessage());
        }
    }

    /**
     * The coverage preview payload: the exact count, a capped product sample and an error
     * message when the fragment failed the grammar. A record, serialized by Jackson.
     *
     * @param count    The number of products the condition covers, 0 when error.
     * @param products  The sample of covered products, empty when error.
     * @param error     The grammar refusal message, null on success.
     */
    public record WherePreview(long count, java.util.List<java.util.Map<String, String>> products,
                               String error) {

        /**
         * Builds a refusal payload.
         *
         * @param message Why the fragment was refused.
         * @return The preview carrying the error.
         */
        static WherePreview error(String message) {
            return new WherePreview(0, java.util.List.of(), message);
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds the query selecting the offers matching the requested filters.
     * <p>
     * Filters are combined with AND and only contribute a clause when they carry a value.
     * The query is returned unpaginated so the caller can both count the total number of
     * matches and extract a single page from it.
     *
     * @param search     A fragment matched against the offer code, may be null or blank.
     * @param type       The offer type filter, may be null or blank.
     * @param target     A store or store group code the offer must target, may be null or blank.
     * @param ean        An EAN the offer must reference, may be null or blank.
     * @param sort       The validated sort key.
     * @param descending Whether the sort is descending.
     * @return The Panache query, ordered as requested.
     */
    private PanacheQuery<Offer> queryOffers(String search, String type, String target, String ean,
                                            String sort, boolean descending) {
        // A full query is used rather than a Panache shorthand, so the root carries an
        // alias the correlated subqueries below can refer to.
        StringBuilder jpql = new StringBuilder("from Offer o");
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (isSet(search)) {
            append(where, "lower(o.code) like ?" + (params.size() + 1));
            params.add("%" + search.trim().toLowerCase() + "%");
        }
        if (isSet(type)) {
            append(where, "o.type = ?" + (params.size() + 1));
            params.add(type.trim());
        }
        if (isSet(target)) {
            // Matching a collection with a join would duplicate rows and corrupt the page
            // count, so membership is tested with an EXISTS subquery instead.
            int index = params.size() + 1;
            append(where, "(exists (select 1 from o.stores s where lower(s.code) like ?" + index + ")"
                    + " or exists (select 1 from o.storeGroups g where lower(g.code) like ?" + index + "))");
            params.add("%" + target.trim().toLowerCase() + "%");
        }
        if (isSet(ean)) {
            append(where, "exists (select 1 from o.eans e where e like ?" + (params.size() + 1) + ")");
            params.add(ean.trim() + "%");
        }
        // Perimeter (spec §2): a store-scoped user only sees offers whose targeting intersects the
        // scope. The same EXISTS shape as the target filter, with an IN over the scope codes. This
        // is the server-side truth; the IHM filter is only a convenience.
        Set<String> scope = currentUser == null ? Set.of() : currentUser.getStoreScopeSet();
        if (!scope.isEmpty()) {
            int index = params.size() + 1;
            append(where, "(exists (select 1 from o.stores s where s.code in ?" + index + ")"
                    + " or exists (select 1 from o.storeGroups g where g.code in ?" + index + "))");
            params.add(scope);
        }
        if (where.length() > 0) {
            jpql.append(" where ").append(where);
        }
        jpql.append(buildOrderBy(sort, descending));
        return Offer.find(jpql.toString(), params.toArray());
    }

    /**
     * Builds the ORDER BY clause matching a validated sort key.
     *
     * @param sort       The sort key, one of {@link #SORTABLE}.
     * @param descending Whether the sort is descending.
     * @return The clause to append to the query.
     */
    private String buildOrderBy(String sort, boolean descending) {
        String direction = descending ? " desc" : " asc";
        // The EAN column displays a count, so it is ordered by collection size, not value.
        String expression = SORT_EANS.equals(sort) ? "size(o.eans)" : "o." + sort;
        String clause = " order by " + expression + direction;
        // Keep a deterministic order between rows sharing the same sort value.
        if (!SORT_CODE.equals(sort)) {
            clause += ", o.code asc";
        }
        return clause;
    }

    /**
     * Appends a condition to the WHERE clause, inserting the AND keyword when needed.
     *
     * @param where     The clause being assembled.
     * @param condition The condition to add.
     */
    private void append(StringBuilder where, String condition) {
        if (where.length() > 0) {
            where.append(" and ");
        }
        where.append(condition);
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

    /**
     * The JSON mapper used to parse offer specifications for the article filter.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Keeps the offers whose targeting covers a given article (spec-exploitation §4).
     *
     * @param offers  the candidate offers.
     * @param article the article EAN to match.
     * @return the offers whose resolved targeting contains the article, in the input order.
     */
    private List<Offer> filterByArticle(List<Offer> offers, String article) {
        List<Offer> matching = new ArrayList<>();
        for (Offer offer : offers) {
            if (offerCoversArticle(offer, article)) {
                matching.add(offer);
            }
        }
        return matching;
    }

    /**
     * Tells whether an offer's targeting covers an article, resolving it exactly as the engine does
     * through the shared {@link EngineTrait#resolveTargetEans(JsonNode, String)} helper: the union of
     * {@code targetEans} and the resolved {@code targetFamilyCodes} sub-trees, minus
     * {@code excludedEans}. An offer that targets no EAN (no {@code targetEans}/{@code targetFamilyCodes})
     * covers nothing, and an offer whose specification cannot be parsed or whose family is unknown does
     * not match rather than breaking the whole listing.
     *
     * @param offer   the offer to test.
     * @param article the article EAN.
     * @return {@code true} when the offer's resolved targeting contains the article.
     */
    private boolean offerCoversArticle(Offer offer, String article) {
        if (offer.specification == null || offer.specification.isBlank()) {
            return false;
        }
        try {
            JsonNode spec = MAPPER.readTree(offer.specification);
            return resolveTargetEans(spec, offer.code).contains(article);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Applies the submitted form values to an offer after validating them.
     * <p>
     * Validation covers the mandatory fields, the uniqueness of the code on creation,
     * the presence of at least one target, the existence of every referenced store and
     * group, and finally the conformance of the specification to the registered schema.
     *
     * @param offer           The offer to populate.
     * @param type            The submitted offer type.
     * @param specification   The submitted JSON specification.
     * @param storeCodes      The submitted store codes, comma separated.
     * @param storeGroupCodes The submitted store group codes, comma separated.
     * @param isNew           Whether the offer is being created.
     * @return An error message when validation fails, {@code null} on success.
     */
    /**
     * Applies the activation fields submitted by the form: the active checkbox (an
     * unchecked box posts no value, meaning inactive) and the optional validity window,
     * parsed from the datetime-local inputs; a blank bound is open.
     *
     * @param offer     The offer being created or updated.
     * @param active    The posted checkbox value, null when unchecked.
     * @param validFrom The posted window start, blank or null for open.
     * @param validTo   The posted window end, blank or null for open.
     * @return An error message when a date is unparseable or the window is inverted, null otherwise.
     */
    private String applyActivation(Offer offer, String active, String validFrom, String validTo) {
        offer.active = active != null;
        try {
            offer.validFrom = parseFormDateTime(validFrom);
            offer.validTo = parseFormDateTime(validTo);
        } catch (java.time.format.DateTimeParseException e) {
            return messages.format("offers.error.invalidDate", e.getParsedString());
        }
        if (offer.validFrom != null && offer.validTo != null && !offer.validFrom.isBefore(offer.validTo)) {
            return messages.get("offers.error.windowInverted");
        }
        return null;
    }

    /**
     * Parses a datetime-local form value into a LocalDateTime.
     *
     * @param value The posted value, possibly blank or null.
     * @return The parsed date-time, or null when the value is blank or null.
     * @throws java.time.format.DateTimeParseException when the value is not ISO local date-time.
     */
    private java.time.LocalDateTime parseFormDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return java.time.LocalDateTime.parse(value.trim());
    }

    /**
     * Applies the submitted form values to an offer after validating them.
     * <p>
     * Validation covers the mandatory fields, the uniqueness of the code on creation,
     * the presence of at least one target, the existence of every referenced store and
     * group, and finally the conformance of the specification to the registered schema.
     *
     * @param offer           The offer to populate.
     * @param type            The submitted offer type.
     * @param specification   The submitted JSON specification.
     * @param storeCodes      The submitted store codes, comma separated.
     * @param storeGroupCodes The submitted store group codes, comma separated.
     * @param isNew           Whether the offer is being created.
     * @return An error message when validation fails, {@code null} on success.
     */
    private String applyForm(Offer offer, String type, String specification,
                             String storeCodes, String storeGroupCodes, boolean isNew) {
        if (offer.code == null || offer.code.isBlank()) {
            return messages.get("offers.error.codeMandatory");
        }
        if (type == null || type.isBlank()) {
            return messages.get("offers.error.typeMandatory");
        }
        if (isNew && Offer.count("code", offer.code) > 0) {
            return messages.format("offers.error.codeExists", offer.code);
        }
        List<String> requestedStores = splitCsv(storeCodes);
        List<String> requestedGroups = splitCsv(storeGroupCodes);
        if (requestedStores.isEmpty() && requestedGroups.isEmpty()) {
            return messages.get("offers.error.targetRequired");
        }
        String schema = schemaRegistry.getSchema(type);
        if (schema != null) {
            String schemaError = validateSpecification(schema, specification);
            if (schemaError != null) {
                return schemaError;
            }
        }
        Set<Store> stores;
        Set<StoreGroup> groups;
        try {
            stores = resolveStores(requestedStores);
            groups = resolveStoreGroups(requestedGroups);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        // Perimeter (spec §2): the type must be allowed and the whole resulting targeting within
        // the user's store scope. Server-side truth, mirroring the GraphQL enforcement. A null
        // currentUser only occurs off-server (plain-unit): treated as unrestricted.
        if (currentUser != null && !currentUser.isOfferTypeAllowed(type)) {
            return messages.format("offers.error.typeNotAllowed", type);
        }
        Set<String> targetCodes = new HashSet<>(requestedStores);
        targetCodes.addAll(requestedGroups);
        if (currentUser != null && !currentUser.isStoreTargetingWithinScope(targetCodes)) {
            return messages.get("offers.error.targetingOutsideScope");
        }
        offer.type = type;
        offer.specification = specification;
        offer.stores.clear();
        offer.stores.addAll(stores);
        offer.storeGroups.clear();
        offer.storeGroups.addAll(groups);
        return null;
    }

    /**
     * Returns the offer types the current user may pick in the form: every registered type, narrowed
     * to the user's {@code allowedOfferTypes} when set (spec §2). An unrestricted user sees them all.
     *
     * @return the selectable offer types, in a stable order.
     */
    private Set<String> allowedTypesForForm() {
        Set<String> known = schemaRegistry.getKnownTypes();
        com.intermarche.valuation.domain.AppUser user = currentUser == null ? null : currentUser.currentAppUser();
        if (user == null) {
            return known;
        }
        Set<String> allowed = user.getAllowedOfferTypeSet();
        if (allowed.isEmpty()) {
            return known;
        }
        return known.stream().filter(allowed::contains)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Validates a specification against a JSON Schema using the engine validation path.
     *
     * @param schema        The JSON Schema to validate against.
     * @param specification The specification to validate.
     * @return An error message when validation fails, {@code null} on success.
     */
    private String validateSpecification(String schema, String specification) {
        if (specification == null || specification.isBlank()) {
            return messages.get("offers.error.specMandatory");
        }
        try {
            this.processSpecification(schema, specification, (node) -> {
                // Validation only: the parsed node is not needed here.
            });
            return null;
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Specification rejected: " + e.getMessage());
            return e.getMessage();
        }
    }

    /**
     * Resolves store codes to managed entities.
     *
     * @param codes The store codes to resolve.
     * @return The resolved stores.
     * @throws IllegalArgumentException if any code does not exist.
     */
    private Set<Store> resolveStores(List<String> codes) {
        if (codes.isEmpty()) {
            return new HashSet<>();
        }
        List<Store> stores = Store.list("code in ?1", codes);
        if (stores.size() != codes.size()) {
            Set<String> found = stores.stream().map(s -> s.code).collect(Collectors.toSet());
            List<String> missing = codes.stream().filter(c -> !found.contains(c)).toList();
            throw new IllegalArgumentException(messages.format("offers.error.unknownStores", String.join(", ", missing)));
        }
        return new HashSet<>(stores);
    }

    /**
     * Resolves store group codes to managed entities.
     *
     * @param codes The store group codes to resolve.
     * @return The resolved store groups.
     * @throws IllegalArgumentException if any code does not exist.
     */
    private Set<StoreGroup> resolveStoreGroups(List<String> codes) {
        if (codes.isEmpty()) {
            return new HashSet<>();
        }
        List<StoreGroup> groups = StoreGroup.list("code in ?1", codes);
        if (groups.size() != codes.size()) {
            Set<String> found = groups.stream().map(g -> g.code).collect(Collectors.toSet());
            List<String> missing = codes.stream().filter(c -> !found.contains(c)).toList();
            throw new IllegalArgumentException(messages.format("offers.error.unknownStoreGroups", String.join(", ", missing)));
        }
        return new HashSet<>(groups);
    }

    /**
     * Re-renders the form carrying an error message back to the user.
     *
     * @param offer           The offer holding the submitted values.
     * @param storeCodes      The submitted store codes.
     * @param storeGroupCodes The submitted store group codes.
     * @param error           The error message to display.
     * @return A 200 response rendering the form.
     */
    private Response renderFormWithError(Offer offer, String storeCodes, String storeGroupCodes, String error) {
        TemplateInstance template = Templates.form(
                offer,
                allowedTypesForForm(),
                HtmlSafeJson.forScript(buildSchemasJson()),
                HtmlSafeJson.forScript(offer.specification),
                storeCodes == null ? "" : storeCodes,
                storeGroupCodes == null ? "" : storeGroupCodes,
                error,
                draftService.isAvailable());
        return Response.ok(template).build();
    }

    /**
     * Builds a redirection response pointing at the offer list.
     *
     * @return A 303 See Other response.
     */
    private Response redirectToList() {
        URI target = UriBuilder.fromPath("/ui/offers").build();
        return Response.seeOther(target).build();
    }

    /**
     * Serializes the whole schema registry as a JSON object literal.
     * <p>
     * Each schema is already a JSON document, so the values are embedded verbatim rather
     * than being escaped as strings. The result is injected into the page and consumed by
     * the schema-driven form generator.
     *
     * @return A JSON object mapping offer types to their schema.
     */
    private String buildSchemasJson() {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : schemaRegistry.getAllSchemas().entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            first = false;
        }
        return builder.append('}').toString();
    }

    /**
     * Escapes the characters that would break a JSON string literal.
     *
     * @param value The raw value.
     * @return The escaped value.
     */
    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Joins the store codes linked to an offer into a comma separated string.
     *
     * @param offer The offer to read.
     * @return The sorted, comma separated store codes.
     */
    private String joinStoreCodes(Offer offer) {
        return offer.stores.stream().map(s -> s.code).sorted().collect(Collectors.joining(","));
    }

    /**
     * Joins the store group codes linked to an offer into a comma separated string.
     *
     * @param offer The offer to read.
     * @return The sorted, comma separated store group codes.
     */
    private String joinStoreGroupCodes(Offer offer) {
        return offer.storeGroups.stream().map(g -> g.code).sorted().collect(Collectors.joining(","));
    }

    /**
     * Splits a comma separated form value into a list of trimmed, non empty codes.
     *
     * @param raw The raw form value, may be null.
     * @return The parsed codes, never null.
     */
    private List<String> splitCsv(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !values.contains(trimmed)) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
