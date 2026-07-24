package com.intermarche.valuation.ui;

import com.intermarche.valuation.domain.AppUser;
import com.intermarche.valuation.domain.Product;
import com.intermarche.valuation.domain.ProductFamily;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.domain.StoreGroup;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * REST endpoints backing the autocomplete widgets of the administration screens.
 * <p>
 * Searching is delegated to the entities themselves, which own their query logic just
 * like the other Panache finders in the domain. This resource only turns the results into
 * the display shape the widgets expect: deciding what makes a good label or a useful
 * detail line is a presentation concern and stays here.
 * <p>
 * One endpoint per entity, so each stays explicit and typed.
 */
@Path("/ui/lookup")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed({AppUser.ROLE_VIEWER, AppUser.ROLE_MANAGER, AppUser.ROLE_ADMIN})
@Produces(MediaType.APPLICATION_JSON)
public class LookupResource {

    /**
     * Maximum number of suggestions returned by a lookup call.
     */
    private static final int MAX_RESULTS = 20;

    /**
     * Uniform suggestion payload consumed by the autocomplete widgets.
     * <p>
     * Fields are public to keep Jackson serialization straightforward.
     */
    public static class Suggestion {

        /**
         * The value stored in the form field (EAN, store code, group code, flag).
         */
        public String value;

        /**
         * The human readable label displayed in the dropdown.
         */
        public String label;

        /**
         * An optional secondary line giving extra context (brand, city, etc.).
         */
        public String detail;

        /**
         * Default constructor required for JSON serialization.
         */
        public Suggestion() {
        }

        /**
         * Constructs a fully populated suggestion.
         *
         * @param value  The value to store in the form field.
         * @param label  The primary display label.
         * @param detail The secondary display line, may be null.
         */
        public Suggestion(String value, String label, String detail) {
            this.value = value;
            this.label = label;
            this.detail = detail;
        }
    }

    // --------------------------------------------------
    // Endpoints
    // --------------------------------------------------

    /**
     * Searches products by EAN or by name.
     *
     * @param query The raw user input, may be null or blank.
     * @return The matching product suggestions.
     */
    @GET
    @Path("/products")
    public List<Suggestion> searchProducts(@QueryParam("q") String query) {
        return toProductSuggestions(Product.search(query, MAX_RESULTS));
    }

    /**
     * Resolves a batch of EANs to their product labels.
     * <p>
     * Used by the editor to display the values of a saved offer without issuing one
     * request per EAN. Unknown EANs are simply absent from the response, which lets the
     * widget flag them as unresolved while keeping them editable.
     *
     * @param eans A comma separated list of EANs.
     * @return The suggestions matching the known EANs.
     */
    @GET
    @Path("/products/resolve")
    public List<Suggestion> resolveProducts(@QueryParam("eans") String eans) {
        return toProductSuggestions(Product.findByEans(splitCsv(eans)));
    }

    /**
     * Searches stores by code or by name.
     *
     * @param query The raw user input, may be null or blank.
     * @return The matching store suggestions.
     */
    @GET
    @Path("/stores")
    public List<Suggestion> searchStores(@QueryParam("q") String query) {
        return toStoreSuggestions(Store.search(query, MAX_RESULTS));
    }

    /**
     * Searches store groups by code or by name.
     *
     * @param query The raw user input, may be null or blank.
     * @return The matching store group suggestions.
     */
    @GET
    @Path("/store-groups")
    public List<Suggestion> searchStoreGroups(@QueryParam("q") String query) {
        return toStoreGroupSuggestions(StoreGroup.search(query, MAX_RESULTS));
    }

    /**
     * Searches stores and store groups at once.
     * <p>
     * Backs the target filter of the offer list, where an offer may be attached either to
     * a store or to a group and the user should not have to know which beforehand. Each
     * suggestion states its origin in the detail line.
     *
     * @param query The raw user input, may be null or blank.
     * @return The matching suggestions, stores first.
     */
    @GET
    @Path("/targets")
    public List<Suggestion> searchTargets(@QueryParam("q") String query) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (Suggestion store : toStoreSuggestions(Store.search(query, MAX_RESULTS))) {
            store.detail = store.detail == null ? "Store" : "Store \u00b7 " + store.detail;
            suggestions.add(store);
            if (suggestions.size() >= MAX_RESULTS) {
                return suggestions;
            }
        }
        for (Suggestion group : toStoreGroupSuggestions(StoreGroup.search(query, MAX_RESULTS))) {
            group.detail = "Store group";
            suggestions.add(group);
            if (suggestions.size() >= MAX_RESULTS) {
                break;
            }
        }
        return suggestions;
    }

    /**
     * Lists the product family flags currently defined in the catalog.
     *
     * @param query An optional fragment used to filter the flags.
     * @return The matching flag suggestions.
     */
    @GET
    @Path("/flags")
    public List<Suggestion> searchFlags(@QueryParam("q") String query) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (String flag : ProductFamily.searchFlags(query, MAX_RESULTS)) {
            suggestions.add(new Suggestion(flag, flag, null));
        }
        return suggestions;
    }

    // --------------------------------------------------
    // Presentation
    // --------------------------------------------------

    /**
     * Renders products as suggestions.
     *
     * @param products The products to render.
     * @return The suggestions, in the order of the source list.
     */
    private List<Suggestion> toProductSuggestions(List<Product> products) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (Product product : products) {
            String detail = product.brand == null || product.brand.isBlank()
                    ? product.ean
                    : product.brand + " \u00b7 " + product.ean;
            suggestions.add(new Suggestion(product.ean, product.name, detail));
        }
        return suggestions;
    }

    /**
     * Renders stores as suggestions.
     *
     * @param stores The stores to render.
     * @return The suggestions, in the order of the source list.
     */
    private List<Suggestion> toStoreSuggestions(List<Store> stores) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (Store store : stores) {
            String city = store.address == null ? null : store.address.city;
            String detail = city == null || city.isBlank() ? null : city;
            suggestions.add(new Suggestion(store.code, store.name, detail));
        }
        return suggestions;
    }

    /**
     * Renders store groups as suggestions.
     *
     * @param groups The store groups to render.
     * @return The suggestions, in the order of the source list.
     */
    private List<Suggestion> toStoreGroupSuggestions(List<StoreGroup> groups) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (StoreGroup group : groups) {
            suggestions.add(new Suggestion(group.code, group.name, null));
        }
        return suggestions;
    }

    /**
     * Splits a comma separated parameter into a list of trimmed, non empty values.
     *
     * @param raw The raw parameter, may be null.
     * @return The parsed values, never null.
     */
    private List<String> splitCsv(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
