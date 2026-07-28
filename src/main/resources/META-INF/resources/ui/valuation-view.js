/*
 * Renders the valuation request and response in a readable form, with the raw JSON kept
 * one tab away. Everything runs client-side from the JSON already printed in the page, so
 * the stored payloads are untouched and the JSON view is always the exact source.
 */
(function () {
    "use strict";

    /**
     * Escapes text for safe insertion as HTML.
     * @param {*} value The value to escape.
     * @returns {string} The escaped string.
     */
    function esc(value) {
        return String(value == null ? "" : value)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    /**
     * Formats a number as a euro amount, or a dash when absent.
     * @param {*} value The numeric value.
     * @returns {string} The formatted amount.
     */
    function euro(value) {
        if (value == null || isNaN(value)) {
            return "&mdash;";
        }
        return Number(value).toFixed(2) + " &euro;";
    }

    /**
     * Formats a VAT rate (0.20) as a percentage (20.0%).
     * @param {*} rate The rate as a fraction.
     * @returns {string} The formatted percentage.
     */
    function rate(value) {
        if (value == null || isNaN(value)) {
            return "&mdash;";
        }
        return (Number(value) * 100).toFixed(1) + "%";
    }

    /**
     * Builds an amount cell trio (excl., incl., rate) as table cells.
     * @param {object} amount An {amountExcludingTax, amountIncludingTax, vatRate} object.
     * @returns {string} The HTML for three cells.
     */
    function amountCells(amount) {
        if (!amount) {
            return "<td>&mdash;</td><td>&mdash;</td><td>&mdash;</td>";
        }
        return "<td class=\"num\">" + euro(amount.amountExcludingTax) + "</td>"
            + "<td class=\"num\">" + euro(amount.amountIncludingTax) + "</td>"
            + "<td class=\"num rate\">" + rate(amount.vatRate) + "</td>";
    }

    /**
     * Renders the basket request into readable HTML.
     * @param {object} basket The parsed request.
     * @returns {string} The HTML.
     */
    function renderRequest(basket) {
        var html = "";
        var head = [];
        if (basket.storeCode) head.push("Store <strong>" + esc(basket.storeCode) + "</strong>");
        if (basket.customerCode) head.push("Customer <strong>" + esc(basket.customerCode) + "</strong>");
        if (basket.deliveryMode) head.push("<strong>" + esc(basket.deliveryMode) + "</strong>");
        if (head.length) {
            html += "<p class=\"friendly-summary\">" + head.join(" &middot; ") + "</p>";
        }

        var items = basket.items || [];
        if (!items.length) {
            html += "<p class=\"placeholder-note\">No items.</p>";
            return html;
        }
        html += "<table class=\"friendly-table\"><thead><tr>"
            + "<th>Line</th><th>Product</th><th class=\"num\">Qty</th><th>Gesture</th>"
            + "</tr></thead><tbody>";
        items.forEach(function (it) {
            var gesture = "&mdash;";
            if (it.manualForcedPrice != null) gesture = "Forced " + euro(it.manualForcedPrice);
            else if (it.manualDiscountAmount != null) gesture = "-" + euro(it.manualDiscountAmount);
            else if (it.manualDiscountPercent != null) gesture = "-" + esc(it.manualDiscountPercent) + "%";
            html += "<tr><td>" + esc(it.lineId || "&mdash;") + "</td>"
                + "<td class=\"mono\">" + esc(it.produceEan) + "</td>"
                + "<td class=\"num\">" + esc(it.quantity) + "</td>"
                + "<td>" + gesture + "</td></tr>";
        });
        html += "</tbody></table>";
        return html;
    }

    /**
     * Renders one advantage row, dispatching on its shape.
     * @param {object} adv The advantage.
     * @returns {string} The HTML row.
     */
    function renderAdvantage(adv) {
        var label = esc(adv.type || "Advantage");
        if (adv.discountAmount) {
            return "<tr><td>" + label + "</td>"
                + "<td>on " + esc(adv.offer || "") + "</td>"
                + "<td class=\"num\">-" + euro(adv.discountAmount.amountIncludingTax) + "</td></tr>";
        }
        if (adv.suggestion) {
            var s = adv.suggestion;
            return "<tr><td>" + label + "</td>"
                + "<td>Add " + esc(s.quantity) + " &times; " + esc(s.ean)
                + " for " + esc(s.offerCode) + "</td><td class=\"num\">&mdash;</td></tr>";
        }
        if (adv.totalEligibleAmount != null) {
            return "<tr><td>" + label + "</td>"
                + "<td>Eligible " + euro(adv.totalEligibleAmount)
                + " (threshold " + euro(adv.threshold) + ")</td><td class=\"num\">&mdash;</td></tr>";
        }
        return "<tr><td>" + label + "</td><td>&mdash;</td><td class=\"num\">&mdash;</td></tr>";
    }

    /**
     * Renders the valuation response into readable HTML.
     * @param {object} res The parsed response.
     * @returns {string} The HTML.
     */
    function renderResponse(res) {
        var html = "";

        if (res.totalPrice) {
            html += "<div class=\"friendly-total\">"
                + "<span class=\"friendly-total-label\">Total</span>"
                + "<span class=\"friendly-total-value\">" + euro(res.totalPrice.amountIncludingTax) + "</span>"
                + "<span class=\"friendly-total-ht\">" + euro(res.totalPrice.amountExcludingTax) + " excl. tax</span>"
                + "</div>";
        }

        var offers = res.offers || [];
        if (offers.length) {
            html += "<h3 class=\"friendly-head\">Offers</h3>";
            offers.forEach(function (offer) {
                html += "<div class=\"friendly-offer\">";
                html += "<div class=\"friendly-offer-head\"><span>" + esc(offer.type) + "</span>"
                    + "<span class=\"num\">" + euro(offer.amount && offer.amount.amountIncludingTax) + "</span></div>";
                var items = offer.items || [];
                if (items.length) {
                    html += "<table class=\"friendly-table sub\"><thead><tr>"
                        + "<th>Line</th><th>Product</th><th class=\"num\">Qty</th>"
                        + "<th class=\"num\">Excl.</th><th class=\"num\">Incl.</th><th class=\"num\">VAT</th>"
                        + "</tr></thead><tbody>";
                    items.forEach(function (it) {
                        html += "<tr><td>" + esc(it.lineId || "&mdash;") + "</td>"
                            + "<td class=\"mono\">" + esc(it.produceEan) + "</td>"
                            + "<td class=\"num\">" + esc(it.quantity) + "</td>"
                            + amountCells(it.amount) + "</tr>";
                    });
                    html += "</tbody></table>";
                }
                html += "</div>";
            });
        }

        var advantages = res.advantages || [];
        if (advantages.length) {
            html += "<h3 class=\"friendly-head\">Advantages</h3>";
            html += "<table class=\"friendly-table\"><thead><tr>"
                + "<th>Type</th><th>Detail</th><th class=\"num\">Amount</th>"
                + "</tr></thead><tbody>";
            advantages.forEach(function (adv) { html += renderAdvantage(adv); });
            html += "</tbody></table>";
        }

        if (!offers.length && !advantages.length && !res.totalPrice) {
            html += "<p class=\"placeholder-note\">Nothing to show.</p>";
        }
        return html;
    }

    /**
     * Fills a friendly panel from its source JSON, falling back to a note on parse errors.
     * @param {string} kind Either "request" or "response".
     */
    function build(kind) {
        var src = document.querySelector("[data-role='" + kind + "-src']");
        var panel = document.querySelector("[data-panel='" + kind + "-friendly']");
        if (!src || !panel) {
            return;
        }
        var parsed;
        try {
            parsed = JSON.parse(src.textContent);
        } catch (e) {
            panel.innerHTML = "<p class=\"placeholder-note\">Could not read this payload. "
                + "Use the JSON tab.</p>";
            return;
        }
        panel.innerHTML = kind === "request" ? renderRequest(parsed) : renderResponse(parsed);
    }

    /**
     * Wires the Readable / JSON toggle for a given side.
     */
    function wireToggle() {
        document.querySelectorAll(".view-tab").forEach(function (tab) {
            tab.addEventListener("click", function () {
                var target = tab.getAttribute("data-target");
                var view = tab.getAttribute("data-view");
                document.querySelectorAll(".view-tab[data-target='" + target + "']").forEach(function (t) {
                    t.classList.toggle("is-active", t === tab);
                });
                var friendly = document.querySelector("[data-panel='" + target + "-friendly']");
                var json = document.querySelector("[data-panel='" + target + "-json']");
                if (friendly) friendly.classList.toggle("is-hidden", view !== "friendly");
                if (json) json.classList.toggle("is-hidden", view !== "json");
            });
        });
    }

    build("request");
    build("response");
    wireToggle();
})();
