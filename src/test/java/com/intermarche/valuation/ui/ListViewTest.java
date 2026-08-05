package com.intermarche.valuation.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ListView}.
 * <p>
 * {@link ListView} is a pure view model with no collaborator, so every test builds a fully
 * specified instance and asserts the exact value produced. Both arms of each ternary and each
 * guard are exercised: the {@code filter} null coalescing, the {@code getDirection} and
 * {@code getNotice} ternaries, the {@code isFiltered} null/blank/non-blank lambda, the numeric
 * comparisons of {@code isPaged}, {@code isHasPrevious} and {@code isHasNext}, the three
 * conditionals of {@code getSummary}, the short-circuit of {@code getRangeLabel}, the
 * {@code sortUrl} reverse decision, the {@code sortIndicator} three-way outcome, the
 * {@code isSortedOn} equality and the {@code baseUrl} filter loop reached through the URL
 * builders.
 */
class ListViewTest {

    /**
     * Builds a {@link ListView} with the supplied fields and otherwise inert defaults.
     *
     * @param rows        The rows of the current page.
     * @param filters     The active filters.
     * @param sort        The active sort key.
     * @param descending  Whether the sort is descending.
     * @param currentPage The one-based current page number.
     * @param pageCount   The total number of pages.
     * @param totalCount  The total number of matching rows.
     * @param pageSize    The number of rows a full page holds.
     * @param itemLabel   The singular noun naming a row.
     * @param notice      The one-shot message, may be null.
     * @param noticeOk    Whether the message reports a success.
     * @param canWrite    Whether the user may modify the entities.
     * @return The constructed view model.
     */
    private ListView<String> view(List<String> rows, Map<String, String> filters,
                                  String sort, boolean descending,
                                  int currentPage, int pageCount, long totalCount, int pageSize,
                                  String itemLabel, String notice, boolean noticeOk, boolean canWrite) {
        return new ListView<>(rows, "/ui/offers", filters, sort, descending,
                currentPage, pageCount, totalCount, pageSize, itemLabel, notice, noticeOk, canWrite);
    }

    /**
     * A single active filter map keyed by name.
     *
     * @param name  The filter query parameter name.
     * @param value The filter value.
     * @return A mutable ordered map holding the single entry.
     */
    private Map<String, String> filterMap(String name, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(name, value);
        return map;
    }

    /**
     * The constructor copies the supplied filters instead of aliasing them.
     */
    @Test
    void constructorCopiesFilters() {
        Map<String, String> source = filterMap("q", "milk");
        ListView<String> v = view(List.of(), source, "name", false, 1, 1, 0, 20, "offer", null, false, false);
        source.put("q", "changed");
        assertEquals("milk", v.filter("q"));
    }

    /**
     * The rows getter returns the exact list handed to the constructor.
     */
    @Test
    void getRowsReturnsRows() {
        List<String> rows = List.of("a", "b");
        ListView<String> v = view(rows, Map.of(), "name", false, 1, 1, 2, 20, "offer", null, false, false);
        assertEquals(rows, v.getRows());
    }

    /**
     * An absent filter yields an empty string rather than null.
     */
    @Test
    void filterReturnsEmptyWhenAbsent() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("", v.filter("q"));
    }

    /**
     * A present filter yields its stored value.
     */
    @Test
    void filterReturnsValueWhenPresent() {
        ListView<String> v = view(List.of(), filterMap("q", "milk"), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("milk", v.filter("q"));
    }

    /**
     * The sort getter returns the active key.
     */
    @Test
    void getSortReturnsSort() {
        ListView<String> v = view(List.of(), Map.of(), "price", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("price", v.getSort());
    }

    /**
     * The descending flag is reported as stored when set.
     */
    @Test
    void isDescendingTrue() {
        ListView<String> v = view(List.of(), Map.of(), "name", true, 1, 1, 0, 20, "offer", null, false, false);
        assertTrue(v.isDescending());
    }

    /**
     * The descending flag is reported as stored when clear.
     */
    @Test
    void isDescendingFalse() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isDescending());
    }

    /**
     * A descending sort maps to the "desc" direction token.
     */
    @Test
    void getDirectionDescending() {
        ListView<String> v = view(List.of(), Map.of(), "name", true, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("desc", v.getDirection());
    }

    /**
     * An ascending sort maps to the "asc" direction token.
     */
    @Test
    void getDirectionAscending() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("asc", v.getDirection());
    }

    /**
     * The current page number is returned as stored.
     */
    @Test
    void getCurrentPageReturnsValue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 3, 5, 100, 20, "offer", null, false, false);
        assertEquals(3, v.getCurrentPage());
    }

    /**
     * The page count is returned as stored.
     */
    @Test
    void getPageCountReturnsValue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 3, 5, 100, 20, "offer", null, false, false);
        assertEquals(5, v.getPageCount());
    }

    /**
     * The total count is returned as stored.
     */
    @Test
    void getTotalCountReturnsValue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 3, 5, 100, 20, "offer", null, false, false);
        assertEquals(100L, v.getTotalCount());
    }

    /**
     * A null notice is exposed as an empty string.
     */
    @Test
    void getNoticeNullYieldsEmpty() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("", v.getNotice());
    }

    /**
     * A present notice is exposed verbatim.
     */
    @Test
    void getNoticePresentYieldsValue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", "Saved", true, false);
        assertEquals("Saved", v.getNotice());
    }

    /**
     * The notice-ok flag is reported as stored when set.
     */
    @Test
    void isNoticeOkTrue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", "Saved", true, false);
        assertTrue(v.isNoticeOk());
    }

    /**
     * The notice-ok flag is reported as stored when clear.
     */
    @Test
    void isNoticeOkFalse() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", "Failed", false, false);
        assertFalse(v.isNoticeOk());
    }

    /**
     * A present notice makes {@code isHasNotice} true.
     */
    @Test
    void isHasNoticeTrueWhenPresent() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", "Saved", true, false);
        assertTrue(v.isHasNotice());
    }

    /**
     * An absent notice makes {@code isHasNotice} false.
     */
    @Test
    void isHasNoticeFalseWhenAbsent() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isHasNotice());
    }

    /**
     * The write flag is reported as stored when set.
     */
    @Test
    void isCanWriteTrue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, true);
        assertTrue(v.isCanWrite());
    }

    /**
     * The write flag is reported as stored when clear.
     */
    @Test
    void isCanWriteFalse() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isCanWrite());
    }

    /**
     * A non-blank filter value makes {@code isFiltered} true.
     */
    @Test
    void isFilteredTrueOnNonBlank() {
        ListView<String> v = view(List.of(), filterMap("q", "milk"), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertTrue(v.isFiltered());
    }

    /**
     * A null filter value keeps {@code isFiltered} false, exercising the null arm of the lambda.
     */
    @Test
    void isFilteredFalseOnNull() {
        ListView<String> v = view(List.of(), filterMap("q", null), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isFiltered());
    }

    /**
     * A blank filter value keeps {@code isFiltered} false, exercising the blank arm of the lambda.
     */
    @Test
    void isFilteredFalseOnBlank() {
        ListView<String> v = view(List.of(), filterMap("q", "   "), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isFiltered());
    }

    /**
     * An empty filter map keeps {@code isFiltered} false.
     */
    @Test
    void isFilteredFalseWhenEmpty() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isFiltered());
    }

    /**
     * A positive total makes the pager appear.
     */
    @Test
    void isPagedTrueWhenRows() {
        ListView<String> v = view(List.of("a"), Map.of(), "name", false, 1, 1, 1, 20, "offer", null, false, false);
        assertTrue(v.isPaged());
    }

    /**
     * A zero total hides the pager.
     */
    @Test
    void isPagedFalseWhenEmpty() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isPaged());
    }

    /**
     * A page beyond the first has a previous page.
     */
    @Test
    void isHasPreviousTrue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 2, 3, 60, 20, "offer", null, false, false);
        assertTrue(v.isHasPrevious());
    }

    /**
     * The first page has no previous page.
     */
    @Test
    void isHasPreviousFalse() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 3, 60, 20, "offer", null, false, false);
        assertFalse(v.isHasPrevious());
    }

    /**
     * A page before the last has a next page.
     */
    @Test
    void isHasNextTrue() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 3, 60, 20, "offer", null, false, false);
        assertTrue(v.isHasNext());
    }

    /**
     * The last page has no next page.
     */
    @Test
    void isHasNextFalse() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 3, 3, 60, 20, "offer", null, false, false);
        assertFalse(v.isHasNext());
    }

    /**
     * An empty result summarises as "No <label>".
     */
    @Test
    void getSummaryNoResult() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("No offer", v.getSummary());
    }

    /**
     * A single result uses the singular noun and omits the page fragment.
     */
    @Test
    void getSummarySingleResult() {
        ListView<String> v = view(List.of("a"), Map.of(), "name", false, 1, 1, 1, 20, "offer", null, false, false);
        assertEquals("1 offer", v.getSummary());
    }

    /**
     * Several results on a single page pluralise and omit the page fragment.
     */
    @Test
    void getSummaryPluralSinglePage() {
        ListView<String> v = view(List.of("a", "b"), Map.of(), "name", false, 1, 1, 2, 20, "offer", null, false, false);
        assertEquals("2 offers", v.getSummary());
    }

    /**
     * Several results across pages append the page position.
     */
    @Test
    void getSummaryPluralMultiPage() {
        ListView<String> v = view(List.of("a"), Map.of(), "name", false, 2, 3, 60, 20, "offer", null, false, false);
        assertEquals("60 offers — page 2 of 3", v.getSummary());
    }

    /**
     * A zero total produces no range label.
     */
    @Test
    void getRangeLabelEmptyOnZeroTotal() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("", v.getRangeLabel());
    }

    /**
     * A positive total with an empty page produces no range label, exercising the second arm
     * of the short-circuit.
     */
    @Test
    void getRangeLabelEmptyOnEmptyRows() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 5, 20, "offer", null, false, false);
        assertEquals("", v.getRangeLabel());
    }

    /**
     * A populated page states its absolute range, honouring the page offset.
     */
    @Test
    void getRangeLabelStatesRange() {
        ListView<String> v = view(List.of("a", "b", "c"), Map.of(), "name", false, 2, 3, 45, 20, "offer", null, false, false);
        assertEquals("Showing 21–23 of 45", v.getRangeLabel());
    }

    /**
     * A page link carries the filters, the sort and the requested page number.
     */
    @Test
    void pageUrlPreservesStateAndPage() {
        ListView<String> v = view(List.of(), filterMap("q", "milk"), "name", true, 1, 3, 60, 20, "offer", null, false, false);
        assertEquals("/ui/offers?q=milk&sort=name&dir=desc&page=2", v.pageUrl(2));
    }

    /**
     * Clicking the active ascending column reverses it to descending.
     */
    @Test
    void sortUrlReversesActiveAscendingColumn() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("/ui/offers?sort=name&dir=desc", v.sortUrl("name"));
    }

    /**
     * Clicking the active descending column sorts it ascending again.
     */
    @Test
    void sortUrlResetsActiveDescendingColumn() {
        ListView<String> v = view(List.of(), Map.of(), "name", true, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("/ui/offers?sort=name&dir=asc", v.sortUrl("name"));
    }

    /**
     * Clicking another column sorts it ascending regardless of the current direction.
     */
    @Test
    void sortUrlSortsOtherColumnAscending() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("/ui/offers?sort=price&dir=asc", v.sortUrl("price"));
    }

    /**
     * An action link retargets the path while keeping the filters and the sort.
     */
    @Test
    void actionUrlRetargetsPath() {
        ListView<String> v = view(List.of(), filterMap("q", "milk"), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("/ui/offers/export?q=milk&sort=name&dir=asc", v.actionUrl("export"));
    }

    /**
     * A non-active column has no sort indicator.
     */
    @Test
    void sortIndicatorEmptyOnOtherColumn() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("", v.sortIndicator("price"));
    }

    /**
     * The active descending column shows the down arrow.
     */
    @Test
    void sortIndicatorDownOnDescending() {
        ListView<String> v = view(List.of(), Map.of(), "name", true, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("▾", v.sortIndicator("name"));
    }

    /**
     * The active ascending column shows the up arrow.
     */
    @Test
    void sortIndicatorUpOnAscending() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("▴", v.sortIndicator("name"));
    }

    /**
     * The active column is reported as sorted.
     */
    @Test
    void isSortedOnTrueForActive() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertTrue(v.isSortedOn("name"));
    }

    /**
     * A non-active column is reported as not sorted.
     */
    @Test
    void isSortedOnFalseForOther() {
        ListView<String> v = view(List.of(), Map.of(), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertFalse(v.isSortedOn("price"));
    }

    /**
     * A blank filter is dropped from generated URLs, exercising the blank arm of the
     * {@code baseUrl} loop; a non-blank sibling filter is kept.
     */
    @Test
    void baseUrlDropsBlankFilterKeepsValued() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("blank", "  ");
        filters.put("q", "milk");
        ListView<String> v = view(List.of(), filters, "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("/ui/offers?q=milk&sort=name&dir=asc&page=1", v.pageUrl(1));
    }

    /**
     * A null filter value is dropped from generated URLs, exercising the null arm of the
     * {@code baseUrl} loop.
     */
    @Test
    void baseUrlDropsNullFilter() {
        ListView<String> v = view(List.of(), filterMap("q", null), "name", false, 1, 1, 0, 20, "offer", null, false, false);
        assertEquals("/ui/offers?sort=name&dir=asc&page=1", v.pageUrl(1));
    }
}
