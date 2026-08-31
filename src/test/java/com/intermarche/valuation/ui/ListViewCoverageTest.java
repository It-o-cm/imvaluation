package com.intermarche.valuation.ui;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-oriented @QuarkusTest for {@link ListView}, exercising every derived getter and
 * URL builder across single-page, multi-page, empty, filtered and notice states.
 * <p>
 * The class is a pure view model, so no database is involved; the QuarkusTest annotation is
 * required only so quarkus-jacoco attributes the executed lines.
 */
@QuarkusTest
class ListViewCoverageTest {

    /**
     * Builds a filter map with a single entry.
     *
     * @param key   The parameter name.
     * @param value The filter value.
     * @return The single-entry ordered map.
     */
    private Map<String, String> filter(String key, String value) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put(key, value);
        return filters;
    }

    /**
     * A multi-page, descending, filtered view exposes its state and builds stable URLs.
     */
    @Test
    void multiPageDescendingFilteredView() {
        ListView<String> view = new ListView<>(List.of("r1", "r2", "r3", "r4", "r5"), "/ui/offers",
                filter("q", "milk"), "name", true, 2, 3, 25, 10, "offer", "Saved", true, true);
        assertTrue(view.isDescending());
        assertEquals("desc", view.getDirection());
        assertEquals(25, view.getTotalCount());
        assertEquals(2, view.getCurrentPage());
        assertEquals(3, view.getPageCount());
        assertTrue(view.isPaged());
        assertTrue(view.isHasPrevious());
        assertTrue(view.isHasNext());
        assertEquals("25 offers — page 2 of 3", view.getSummary());
        assertEquals("Showing 11–15 of 25", view.getRangeLabel());
        assertEquals("milk", view.filter("q"));
        assertEquals("", view.filter("missing"));
        assertTrue(view.isFiltered());
        assertTrue(view.isHasNotice());
        assertEquals("Saved", view.getNotice());
        assertTrue(view.isNoticeOk());
        assertTrue(view.isCanWrite());
        assertEquals("/ui/offers?q=milk&sort=name&dir=desc&page=1", view.pageUrl(1));
        assertEquals("/ui/offers?q=milk&sort=name&dir=asc", view.sortUrl("name"));
        assertEquals("/ui/offers?q=milk&sort=code&dir=asc", view.sortUrl("code"));
        assertEquals("/ui/offers/export?q=milk&sort=name&dir=desc", view.actionUrl("export"));
        assertEquals("▾", view.sortIndicator("name"));
        assertEquals("", view.sortIndicator("code"));
        assertTrue(view.isSortedOn("name"));
        assertFalse(view.isSortedOn("code"));
        assertEquals(5, view.getRows().size());
        assertEquals("name", view.getSort());
    }

    /**
     * A single-page, ascending, empty view reports no rows and toggles the active sort to
     * descending.
     */
    @Test
    void singlePageAscendingEmptyView() {
        ListView<String> view = new ListView<>(List.of(), "/ui/offers",
                new LinkedHashMap<>(), "name", false, 1, 1, 0, 10, "offer", null, false, false);
        assertFalse(view.isDescending());
        assertEquals("asc", view.getDirection());
        assertEquals("No offer", view.getSummary());
        assertEquals("", view.getRangeLabel());
        assertFalse(view.isPaged());
        assertFalse(view.isHasPrevious());
        assertFalse(view.isHasNext());
        assertEquals("▴", view.sortIndicator("name"));
        assertFalse(view.isHasNotice());
        assertEquals("", view.getNotice());
        assertFalse(view.isNoticeOk());
        assertFalse(view.isCanWrite());
        assertFalse(view.isFiltered());
        assertEquals("/ui/offers?sort=name&dir=desc", view.sortUrl("name"));
    }

    /**
     * A single-row view uses the singular noun and a one-row range.
     */
    @Test
    void singularRowView() {
        ListView<String> view = new ListView<>(List.of("only"), "/ui/offers",
                filter("q", "a"), "name", false, 1, 1, 1, 10, "offer", null, false, true);
        assertEquals("1 offer", view.getSummary());
        assertEquals("Showing 1–1 of 1", view.getRangeLabel());
        assertTrue(view.isFiltered());
        assertTrue(view.isPaged());
    }

    /**
     * A view with a positive total but no rows on the page yields an empty range label.
     */
    @Test
    void positiveTotalWithEmptyPageHasEmptyRange() {
        ListView<String> view = new ListView<>(List.of(), "/ui/offers",
                new LinkedHashMap<>(), "name", false, 1, 1, 5, 10, "offer", null, false, false);
        assertEquals("", view.getRangeLabel());
        assertTrue(view.isPaged());
    }
}
