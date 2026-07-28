/*
 * Refreshes the valuation list rows every second, in place, without reloading the page.
 * Only the table body is replaced; filters, sorting, pagination and the config form are
 * left untouched. The current query string is forwarded so the refresh honours the active
 * filters and page.
 */
(function () {
    "use strict";

    var INTERVAL_MS = 1000;
    var tbody = document.getElementById("valuation-rows");
    if (!tbody) {
        return;
    }
    var baseUrl = tbody.getAttribute("data-refresh-url");
    var inFlight = false;

    /**
     * Fetches the latest rows for the current filters and swaps them in.
     */
    function refresh() {
        if (inFlight || document.hidden) {
            return;
        }
        inFlight = true;
        var url = baseUrl + window.location.search;
        fetch(url, { headers: { "Accept": "text/html" }, credentials: "same-origin" })
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (html != null) {
                    tbody.innerHTML = html;
                }
            })
            .catch(function () { /* transient error: keep the current rows, try again next tick */ })
            .then(function () { inFlight = false; });
    }

    setInterval(refresh, INTERVAL_MS);
})();
