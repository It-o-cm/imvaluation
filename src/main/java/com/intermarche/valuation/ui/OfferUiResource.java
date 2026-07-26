package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.imports.OfferCsvResource;
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
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.annotations.providers.multipart.PartType;
import org.jboss.logging.Logger;

import java.io.InputStream;
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
     * Bulk CSV importer reused by the upload form of the list screen.
     */
    @Inject
    OfferCsvResource csvResource;

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
         * @return The template instance to render.
         */
        public static native TemplateInstance form(Offer offer, Set<String> types, String schemasJson,
                                                   String storeCodes, String storeGroupCodes, String error);
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
        PanacheQuery<Offer> query = queryOffers(search, type, target, ean, sortKey, descending)
                .page(Page.ofSize(PAGE_SIZE));
        long totalCount = query.count();
        int pageCount = Math.max(1, query.pageCount());
        int currentPage = Math.min(Math.max(page, 1), pageCount);
        List<Offer> offers = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("q", search);
        filters.put("type", type);
        filters.put("target", target);
        filters.put("ean", ean);
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
                           @QueryParam("sort") @DefaultValue(SORT_CODE) String sort,
                           @QueryParam("dir") @DefaultValue("asc") String dir) {
        LOGGER.debug("Entering method export");
        String sortKey = SORTABLE.contains(sort) ? sort : SORT_CODE;
        boolean descending = "desc".equalsIgnoreCase(dir);
        List<Offer> offers = queryOffers(search, type, target, ean, sortKey, descending).list();
        StringBuilder csv = new StringBuilder("offer_code|offer_type|specification|store_code|store_group_code\n");
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
     * Strips the characters that would break the pipe-separated line format.
     * <p>
     * Specifications are JSON documents written by the editor and never contain pipes or
     * newlines, but a value imported from elsewhere might; replacing them keeps the export
     * parseable rather than silently producing a corrupt file.
     *
     * @param value The raw value, may be null.
     * @return The value with separators neutralised, never null.
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("|", "/");
    }

    /**
     * Imports offers from a CSV file uploaded through the list screen.
     * <p>
     * The parsing, chunking and staged transaction handling are delegated to
     * {@link OfferCsvResource}, so the screen and the {@code /offers/import} endpoint
     * share one implementation and can never diverge.
     * <p>
     * The user is redirected back to the list with a short outcome message rather than
     * being shown the raw JSON summary returned by the importer.
     *
     * @param upload The uploaded CSV content.
     * @return A redirection to the list screen carrying the import outcome.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @POST
    @jakarta.ws.rs.Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public Response importCsv(@MultipartForm OfferCsvUpload upload) {
        LOGGER.debug("Entering method importCsv");
        if (upload == null || upload.file == null) {
            return redirectWithNotice("No file was selected.", false);
        }
        try {
            Response result = csvResource.importOffers(upload.file);
            String notice = result.getEntity() == null
                    ? "Import completed."
                    : "Import completed: " + result.getEntity();
            LOGGER.debug("Exiting method importCsv");
            return redirectWithNotice(notice, true);
        } catch (Exception e) {
            LOGGER.error("CSV import failed", e);
            return redirectWithNotice("Import failed: " + e.getMessage(), false);
        }
    }

    /**
     * Redirects to the list screen carrying a message to display.
     *
     * @param notice  The message shown once on the list screen.
     * @param success Whether the message reports a success.
     * @return A 303 See Other response.
     */
    private Response redirectWithNotice(String notice, boolean success) {
        URI target = UriBuilder.fromPath("/ui/offers")
                .queryParam("notice", notice)
                .queryParam("noticeOk", success)
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Multipart payload of the CSV import form.
     * <p>
     * The field is public because RESTEasy populates it directly.
     */
    public static class OfferCsvUpload {

        /**
         * The uploaded CSV content.
         */
        @FormParam("file")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public InputStream file;

        /**
         * Default constructor required by RESTEasy.
         */
        public OfferCsvUpload() {
        }
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
        return Templates.form(offer, schemaRegistry.getKnownTypes(), buildSchemasJson(), "", "", null);
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
            return Response.status(Response.Status.NOT_FOUND).entity("Offer " + id + " not found").build();
        }
        TemplateInstance template = Templates.form(
                offer,
                schemaRegistry.getKnownTypes(),
                buildSchemasJson(),
                joinStoreCodes(offer),
                joinStoreGroupCodes(offer),
                null);
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
                         @FormParam("storeGroupCodes") String storeGroupCodes) {
        LOGGER.debug("Entering method save for code: " + code);
        Offer offer = new Offer();
        offer.code = code;
        String error = applyForm(offer, type, specification, storeCodes, storeGroupCodes, true);
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
                           @FormParam("storeGroupCodes") String storeGroupCodes) {
        LOGGER.debug("Entering method update for id: " + id);
        Offer offer = Offer.findById(id);
        if (offer == null) {
            LOGGER.error("Offer with id " + id + " not found");
            return Response.status(Response.Status.NOT_FOUND).entity("Offer " + id + " not found").build();
        }
        String error = applyForm(offer, type, specification, storeCodes, storeGroupCodes, false);
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
        LOGGER.debug("Exiting method delete. Result: " + deleted);
        return redirectToList();
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
            return "The offer code is mandatory.";
        }
        if (type == null || type.isBlank()) {
            return "The offer type is mandatory.";
        }
        if (isNew && Offer.count("code", offer.code) > 0) {
            return "An offer with code '" + offer.code + "' already exists.";
        }
        List<String> requestedStores = splitCsv(storeCodes);
        List<String> requestedGroups = splitCsv(storeGroupCodes);
        if (requestedStores.isEmpty() && requestedGroups.isEmpty()) {
            return "The offer must target at least one store or one store group.";
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
        offer.type = type;
        offer.specification = specification;
        offer.stores.clear();
        offer.stores.addAll(stores);
        offer.storeGroups.clear();
        offer.storeGroups.addAll(groups);
        return null;
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
            return "The offer specification is mandatory.";
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
            throw new IllegalArgumentException("Unknown store codes: " + String.join(", ", missing));
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
            throw new IllegalArgumentException("Unknown store group codes: " + String.join(", ", missing));
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
                schemaRegistry.getKnownTypes(),
                buildSchemasJson(),
                storeCodes == null ? "" : storeCodes,
                storeGroupCodes == null ? "" : storeGroupCodes,
                error);
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
