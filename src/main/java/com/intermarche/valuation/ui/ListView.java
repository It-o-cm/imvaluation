package com.intermarche.valuation.ui;

import jakarta.ws.rs.core.UriBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * View model backing any paginated administration list.
 * <p>
 * It carries the displayed page together with the whole interaction state: the active
 * filters, the current sort, and the pagination bounds. Nothing here is tied to a
 * particular entity: the screen supplies its base path, its sortable columns and its
 * filter values, so the same class serves the offer list, and any list added later.
 * <p>
 * The template builds its own links through {@link #sortUrl(String)} and
 * {@link #pageUrl(int)}, which keeps every URL consistent without the resource having to
 * pre-compute one per control.
 * <p>
 * Getters are public because Qute resolves template expressions against them.
 *
 * @param <T> The type of the listed rows.
 */
public class ListView<T> {

    /**
     * The rows of the current page.
     */
    private final List<T> rows;

    /**
     * The base path of the screen, used to build every link (e.g. "/ui/offers").
     */
    private final String basePath;

    /**
     * The active filters, keyed by query parameter name.
     * <p>
     * Insertion order is preserved so generated URLs stay stable and comparable.
     */
    private final Map<String, String> filters;

    /**
     * The active sort key, always one the screen declared as sortable.
     */
    private final String sort;

    /**
     * Whether the sort is descending.
     */
    private final boolean descending;

    /**
     * The one-based number of the displayed page.
     */
    private final int currentPage;

    /**
     * The total number of pages, at least one.
     */
    private final int pageCount;

    /**
     * The total number of rows matching the filters.
     */
    private final long totalCount;

    /**
     * The singular noun naming a row, used to build the summary sentence.
     */
    private final String itemLabel;

    /**
     * A one-shot message to display, typically the outcome of an action, may be null.
     */
    private final String notice;

    /**
     * Whether {@link #notice} reports a success rather than a failure.
     */
    private final boolean noticeOk;

    /**
     * Whether the signed-in user may modify the listed entities.
     * <p>
     * The server enforces access through {@code @RolesAllowed}; this flag only prevents
     * the screen from displaying controls that would lead to a rejected request.
     */
    private final boolean canWrite;

    /**
     * Constructs the view model of a list screen rendering.
     *
     * @param rows        The rows of the current page.
     * @param basePath    The base path of the screen, used to build links.
     * @param filters     The active filters, keyed by query parameter name.
     * @param sort        The active sort key.
     * @param descending  Whether the sort is descending.
     * @param currentPage The one-based number of the displayed page.
     * @param pageCount   The total number of pages.
     * @param totalCount  The total number of matching rows.
     * @param itemLabel   The singular noun naming a row (e.g. "offer").
     * @param notice      A one-shot message to display, may be null.
     * @param noticeOk    Whether the message reports a success.
     * @param canWrite    Whether the signed-in user may modify the listed entities.
     */
    public ListView(List<T> rows, String basePath, Map<String, String> filters,
                    String sort, boolean descending,
                    int currentPage, int pageCount, long totalCount, String itemLabel,
                    String notice, boolean noticeOk, boolean canWrite) {
        this.rows = rows;
        this.basePath = basePath;
        this.filters = new LinkedHashMap<>(filters);
        this.sort = sort;
        this.descending = descending;
        this.currentPage = currentPage;
        this.pageCount = pageCount;
        this.totalCount = totalCount;
        this.itemLabel = itemLabel;
        this.notice = notice;
        this.noticeOk = noticeOk;
        this.canWrite = canWrite;
    }

    /**
     * Returns the rows of the current page.
     *
     * @return The displayed rows.
     */
    public List<T> getRows() {
        return rows;
    }

    /**
     * Returns the value of a filter.
     * <p>
     * Templates call this to populate their inputs, so an absent filter yields an empty
     * string rather than null.
     *
     * @param name The query parameter name of the filter.
     * @return The active value, or an empty string when the filter is not set.
     */
    public String filter(String name) {
        String value = filters.get(name);
        return value == null ? "" : value;
    }

    /**
     * Returns the active sort key.
     *
     * @return The sort key.
     */
    public String getSort() {
        return sort;
    }

    /**
     * Indicates whether the current sort is descending.
     *
     * @return {@code true} when descending.
     */
    public boolean isDescending() {
        return descending;
    }

    /**
     * Returns the sort direction as the value expected in a query string.
     *
     * @return Either "desc" or "asc".
     */
    public String getDirection() {
        return descending ? "desc" : "asc";
    }

    /**
     * Returns the one-based number of the displayed page.
     *
     * @return The current page number.
     */
    public int getCurrentPage() {
        return currentPage;
    }

    /**
     * Returns the total number of pages.
     *
     * @return The page count, at least one.
     */
    public int getPageCount() {
        return pageCount;
    }

    /**
     * Returns the total number of rows matching the filters.
     *
     * @return The total count across every page.
     */
    public long getTotalCount() {
        return totalCount;
    }

    /**
     * Returns the one-shot message to display.
     *
     * @return The message, or an empty string when there is none.
     */
    public String getNotice() {
        return notice == null ? "" : notice;
    }

    /**
     * Indicates whether the message reports a success.
     *
     * @return {@code true} on success.
     */
    public boolean isNoticeOk() {
        return noticeOk;
    }

    /**
     * Indicates whether a message must be displayed.
     *
     * @return {@code true} when a message is present.
     */
    public boolean isHasNotice() {
        return !getNotice().isEmpty();
    }

    /**
     * Indicates whether the signed-in user may modify the listed entities.
     *
     * @return {@code true} when write controls must be displayed.
     */
    public boolean isCanWrite() {
        return canWrite;
    }

    /**
     * Indicates whether at least one filter is active.
     *
     * @return {@code true} when any filter carries a value.
     */
    public boolean isFiltered() {
        return filters.values().stream().anyMatch(value -> value != null && !value.isBlank());
    }

    /**
     * Indicates whether more than one page of results exists.
     *
     * @return {@code true} when the pager must be displayed.
     */
    public boolean isPaged() {
        return pageCount > 1;
    }

    /**
     * Indicates whether a previous page exists.
     *
     * @return {@code true} when the current page is not the first one.
     */
    public boolean isHasPrevious() {
        return currentPage > 1;
    }

    /**
     * Indicates whether a next page exists.
     *
     * @return {@code true} when the current page is not the last one.
     */
    public boolean isHasNext() {
        return currentPage < pageCount;
    }

    /**
     * Builds the sentence describing the result count and the current position.
     *
     * @return The summary displayed under the screen title.
     */
    public String getSummary() {
        if (totalCount == 0) {
            return "No " + itemLabel;
        }
        String summary = totalCount + " " + itemLabel + (totalCount == 1 ? "" : "s");
        if (pageCount > 1) {
            summary += " \u2014 page " + currentPage + " of " + pageCount;
        }
        return summary;
    }

    /**
     * Builds a link to a given page, preserving the filters and the sort.
     *
     * @param page The target page number.
     * @return The encoded URL of the requested page.
     */
    public String pageUrl(int page) {
        return baseUrl().queryParam("page", page).build().toString();
    }

    /**
     * Builds the link toggling the sort on a column.
     * <p>
     * Clicking the active column reverses its direction; clicking another column sorts it
     * ascending. The page is reset to the first one, since the previous offset is
     * meaningless under a new ordering.
     *
     * @param column The sort key of the clicked column.
     * @return The encoded URL applying the requested sort.
     */
    public String sortUrl(String column) {
        boolean reverse = column.equals(sort) && !descending;
        return baseUrl()
                .replaceQueryParam("sort", column)
                .replaceQueryParam("dir", reverse ? "desc" : "asc")
                .build().toString();
    }

    /**
     * Builds a link to a sibling path of the screen, preserving the filters and the sort.
     * <p>
     * Used for actions operating on the current selection, such as a CSV export.
     *
     * @param suffix The path segment to append to the base path (e.g. "export").
     * @return The encoded URL of the action.
     */
    public String actionUrl(String suffix) {
        return baseUrl().replacePath(basePath + "/" + suffix).build().toString();
    }

    /**
     * Returns the sort indicator to display next to a column header.
     *
     * @param column The sort key of the column.
     * @return An arrow when the column drives the sort, an empty string otherwise.
     */
    public String sortIndicator(String column) {
        if (!column.equals(sort)) {
            return "";
        }
        return descending ? "\u25be" : "\u25b4";
    }

    /**
     * Indicates whether a column is the one currently driving the sort.
     *
     * @param column The sort key of the column.
     * @return {@code true} when the column is active.
     */
    public boolean isSortedOn(String column) {
        return column.equals(sort);
    }

    /**
     * Builds a URI carrying every active filter and the current sort.
     * <p>
     * The page number is deliberately omitted so callers decide whether to keep the
     * current offset or reset it.
     *
     * @return A builder pre-populated with the list state.
     */
    private UriBuilder baseUrl() {
        UriBuilder builder = UriBuilder.fromPath(basePath);
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isBlank()) {
                builder.queryParam(entry.getKey(), value);
            }
        }
        builder.queryParam("sort", sort);
        builder.queryParam("dir", getDirection());
        return builder;
    }
}
