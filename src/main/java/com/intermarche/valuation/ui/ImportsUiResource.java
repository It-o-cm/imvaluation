package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.imports.OfferCsvResource;
import com.intermarche.valuation.imports.PriceCsvResource;
import com.intermarche.valuation.imports.ProductCategoryStorageCsvResource;
import com.intermarche.valuation.imports.ProductCsvResource;
import com.intermarche.valuation.imports.ProductFamilyCsvResource;
import com.intermarche.valuation.imports.StoreCsvResource;
import com.intermarche.valuation.imports.StoreGroupCsvResource;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.annotations.providers.multipart.PartType;

import java.io.InputStream;
import java.net.URI;

/**
 * Qute-backed administration screen gathering every CSV bulk import behind a single tab.
 * <p>
 * The catalog is loaded through seven importers that must be replayed in a mandated order
 * (Stores → Store groups → Products → Product families → Categories → Prices → Offers),
 * because each domain references the ones declared before it. This screen exposes them all
 * in that order: the operator picks a domain, drops a pipe-delimited file, and the upload is
 * dispatched to the matching {@code *CsvResource} — the very endpoints reachable over HTTP,
 * so the screen never duplicates the import machinery, it only drives it.
 * <p>
 * The importer's JSON report is collapsed into a one-shot notice carried by a POST → 303
 * redirect, so a page reload never replays the import. Per-line failures isolated by the
 * staged fallback are counted and flagged; their detail stays in the server logs.
 */
@Path("/ui/imports")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
public class ImportsUiResource {

    private static final Logger LOGGER = Logger.getLogger(ImportsUiResource.class);

    /**
     * Base path of the screen, used to build its redirects.
     */
    private static final String BASE_PATH = "/ui/imports";

    /**
     * Importer for stores, the first domain of the mandated order.
     */
    @Inject
    StoreCsvResource stores;

    /**
     * Importer for the store-group hierarchy.
     */
    @Inject
    StoreGroupCsvResource storeGroups;

    /**
     * Importer for products.
     */
    @Inject
    ProductCsvResource products;

    /**
     * Importer for product families.
     */
    @Inject
    ProductFamilyCsvResource productFamilies;

    /**
     * Importer for product category storages (the merchandising categories).
     */
    @Inject
    ProductCategoryStorageCsvResource categories;

    /**
     * Importer for prices.
     */
    @Inject
    PriceCsvResource prices;

    /**
     * Importer for offers, the last domain of the mandated order.
     */
    @Inject
    OfferCsvResource offers;

    /**
     * Type-safe declarations of the Qute templates used by this resource.
     * <p>
     * Templates live under {@code src/main/resources/templates/ImportsUiResource}.
     */
    @CheckedTemplate
    public static class Templates {

        /**
         * Renders the imports screen.
         *
         * @param notice   A one-shot message to display, typically an import outcome, may be blank.
         * @param noticeOk Whether the message reports a success.
         * @param canWrite Whether the signed-in user may run an import.
         * @return The template instance to render.
         */
        public static native TemplateInstance imports(String notice, boolean noticeOk, boolean canWrite);
    }

    /**
     * Displays the imports screen.
     *
     * @param notice          A one-shot message to display, may be null.
     * @param noticeOk        Whether the message reports a success.
     * @param securityContext The context identifying the signed-in user.
     * @return The rendered imports screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance imports(@QueryParam("notice") String notice,
                                    @QueryParam("noticeOk") boolean noticeOk,
                                    @Context SecurityContext securityContext) {
        LOGGER.debug("Entering method imports");
        return Templates.imports(notice == null ? "" : notice, noticeOk, canWrite(securityContext));
    }

    /**
     * Runs an import, dispatching the uploaded file to the importer of the chosen domain.
     * <p>
     * The parsing, chunking and staged transaction handling live in the shared importers,
     * so this screen and the raw {@code /.../import} endpoints can never diverge. The user is
     * redirected back to the screen with a short outcome message rather than the raw JSON.
     *
     * @param upload The multipart payload carrying the domain and the CSV file.
     * @return A redirection to the imports screen carrying the outcome.
     */
    @RolesAllowed(AppUser.ROLE_ADMIN)
    @POST
    @jakarta.ws.rs.Path("/run")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public Response run(@MultipartForm ImportUpload upload) {
        LOGGER.debug("Entering method run");
        if (upload == null || upload.file == null || upload.domain == null || upload.domain.isBlank()) {
            return redirect("No file or domain was selected.", false);
        }
        String domain = upload.domain.trim();
        try {
            Response report = dispatch(domain, upload.file);
            String body = report.getEntity() == null ? "" : report.getEntity().toString();
            boolean ok = report.getStatus() < 400;
            LOGGER.debug("Exiting method run for domain: " + domain);
            return redirect(domain + " — " + summarize(body), ok);
        } catch (Exception e) {
            LOGGER.error("CSV import failed for domain " + domain, e);
            return redirect("Import failed: " + e.getMessage(), false);
        }
    }

    /**
     * Dispatches a file stream to the importer matching a domain.
     *
     * @param domain The domain name submitted by the form.
     * @param file   The uploaded CSV stream.
     * @return The importer's JSON report response, or a 400 response for an unknown domain.
     */
    private Response dispatch(String domain, InputStream file) {
        return switch (domain) {
            case "STORES" -> stores.importStores(file);
            case "STORE_GROUPS" -> storeGroups.importHierarchy(file);
            case "PRODUCTS" -> products.importProducts(file);
            case "PRODUCT_FAMILIES" -> productFamilies.importProductFamilies(file);
            case "CATEGORIES" -> categories.importCategoryStorages(file);
            case "PRICES" -> prices.importPrices(file);
            case "OFFERS" -> offers.importOffers(file);
            default -> Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Unknown domain '" + domain + "'\"}").build();
        };
    }

    /**
     * Summarizes an importer JSON report into a short human sentence.
     * <p>
     * The full per-line error list stays in the server logs; the screen only surfaces the
     * created and updated counts and a flag telling whether isolated failures occurred.
     *
     * @param body The JSON report body, may be blank.
     * @return A short summary.
     */
    private String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "no report";
        }
        String created = extract(body, "createdCount");
        String updated = extract(body, "updatedCount");
        boolean hasErrors = body.contains("\"errors\"");
        String summary = created + " created, " + updated + " updated";
        return hasErrors ? summary + " (with errors — see logs)" : summary;
    }

    /**
     * Extracts a numeric field value from a small JSON body.
     *
     * @param body  The JSON body.
     * @param field The numeric field name.
     * @return The value as a string, or "?" when the field is absent.
     */
    private String extract(String body, String field) {
        int idx = body.indexOf("\"" + field + "\"");
        if (idx < 0) {
            return "?";
        }
        int colon = body.indexOf(':', idx);
        int end = colon + 1;
        while (end < body.length()
                && (Character.isDigit(body.charAt(end)) || body.charAt(end) == ' ' || body.charAt(end) == '-')) {
            end++;
        }
        return body.substring(colon + 1, end).trim();
    }

    /**
     * Indicates whether the signed-in user may run an import.
     * <p>
     * The server enforces access through {@code @RolesAllowed} on the run endpoint; this only
     * hides the upload form from users whose submission would be rejected.
     *
     * @param securityContext The request security context, may be null.
     * @return {@code true} when the user holds the admin role.
     */
    private boolean canWrite(SecurityContext securityContext) {
        return securityContext != null && securityContext.isUserInRole(AppUser.ROLE_ADMIN);
    }

    /**
     * Builds a 303 redirect to the imports screen carrying a one-shot notice.
     *
     * @param message The message shown once on the screen.
     * @param success Whether the message reports a success.
     * @return A 303 See Other response.
     */
    private Response redirect(String message, boolean success) {
        URI target = UriBuilder.fromPath(BASE_PATH)
                .queryParam("notice", message)
                .queryParam("noticeOk", success)
                .build();
        return Response.seeOther(target).build();
    }

    /**
     * Multipart payload of the import form.
     * <p>
     * The fields are public because RESTEasy populates them directly.
     */
    public static class ImportUpload {

        /**
         * The chosen import domain, matching a {@code case} of {@link #dispatch(String, InputStream)}.
         */
        @FormParam("domain")
        @PartType(MediaType.TEXT_PLAIN)
        public String domain;

        /**
         * The uploaded CSV content.
         */
        @FormParam("file")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public InputStream file;

        /**
         * Default constructor required by RESTEasy.
         */
        public ImportUpload() {
        }
    }
}
