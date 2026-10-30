/*
 * Renders the valuation request and response in a readable form, as a till receipt, with
 * the raw JSON kept one tab away. Everything runs client-side from the JSON already
 * printed in the page, so the stored payloads are untouched and the JSON view is always
 * the exact source.
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
        html += "<table class=\"friendly-table\">"
            + "<colgroup><col class=\"col-line\"><col class=\"col-prod\">"
            + "<col class=\"col-qty\"><col class=\"col-gesture\"></colgroup>"
            + "<thead><tr>"
            + "<th>Line</th><th>Product</th><th class=\"num\">Qty</th><th>Gesture</th>"
            + "</tr></thead><tbody>";
        items.forEach(function (it) {
            var gesture = "&mdash;";
            if (it.manualForcedPrice != null) gesture = "Forced " + euro(it.manualForcedPrice);
            else if (it.manualDiscountAmount != null) gesture = "-" + euro(it.manualDiscountAmount);
            else if (it.manualDiscountPercent != null) gesture = "-" + esc(it.manualDiscountPercent) + "%";
            html += "<tr><td>" + (it.lineId ? esc(it.lineId) : "&mdash;") + "</td>"
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
                    html += "<table class=\"friendly-table sub\">"
                        + "<colgroup><col class=\"col-line\"><col class=\"col-prod\">"
                        + "<col class=\"col-qty\"><col class=\"col-amt\">"
                        + "<col class=\"col-amt\"><col class=\"col-vat\"></colgroup>"
                        + "<thead><tr>"
                        + "<th>Line</th><th>Product</th><th class=\"num\">Qty</th>"
                        + "<th class=\"num\">Excl.</th><th class=\"num\">Incl.</th><th class=\"num\">VAT</th>"
                        + "</tr></thead><tbody>";
                    items.forEach(function (it) {
                        html += "<tr><td>" + (it.lineId ? esc(it.lineId) : "&mdash;") + "</td>"
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
     * Renders the valuation response as a till receipt: the store and customer head the
     * paper when the request is readable, then the priced lines grouped by offer, the
     * discounts, the total and the VAT breakdown. The figures are exactly the ones the
     * engine returned; nothing is recomputed.
     *
     * @param {object} req The parsed request, may be null.
     * @param {object} res The parsed response, may be null.
     * @returns {string} The HTML.
     */
    function renderReceipt(req, res) {
        res = res || {};
        var hasLine = false;
        var html = "<div class=\"receipt-paper\">"
            + "<div class=\"receipt-head\">Ticket de caisse</div>";
        if (req) {
            var meta = [];
            if (req.storeCode) meta.push("Magasin <strong>" + esc(req.storeCode) + "</strong>");
            if (req.customerCode) meta.push("Client <strong>" + esc(req.customerCode) + "</strong>");
            if (meta.length) {
                html += "<div class=\"receipt-meta\">" + meta.join(" &middot; ") + "</div>";
            }
        }
        html += "<div class=\"receipt-rule\"></div>";
        (res.offers || []).forEach(function (offer) {
            var items = offer.items || [];
            if (items.length) {
                html += "<div class=\"receipt-offer-type\">" + esc(offer.type || "Offre") + "</div>";
            }
            items.forEach(function (it) {
                html += "<div class=\"receipt-line\"><span class=\"receipt-label\">"
                    + esc(it.produceEan || it.lineId || "") + " <span class=\"receipt-qty\">&times; "
                    + esc(it.quantity) + "</span></span>"
                    + "<span class=\"receipt-amount\">"
                    + euro(it.amount && it.amount.amountIncludingTax) + "</span></div>";
                hasLine = true;
            });
            if (!items.length && offer.amount) {
                // A priced offer without lines (e.g. delivery) still belongs on the paper.
                html += "<div class=\"receipt-line\"><span class=\"receipt-label\">"
                    + esc(offer.type || "Offre") + "</span>"
                    + "<span class=\"receipt-amount\">"
                    + euro(offer.amount.amountIncludingTax) + "</span></div>";
                hasLine = true;
            }
        });
        var discounts = (res.advantages || []).filter(function (adv) {
            return adv.discountAmount;
        });
        if (discounts.length) {
            html += "<div class=\"receipt-rule\"></div>";
            discounts.forEach(function (adv) {
                html += "<div class=\"receipt-line receipt-discount\"><span class=\"receipt-label\">Remise "
                    + esc(adv.type || "") + "</span><span class=\"receipt-amount\">-"
                    + euro(adv.discountAmount.amountIncludingTax) + "</span></div>";
            });
        }
        if (res.totalPrice) {
            html += "<div class=\"receipt-rule\"></div>"
                + "<div class=\"receipt-line receipt-total\"><span class=\"receipt-label\">Total TTC</span>"
                + "<span class=\"receipt-amount\">" + euro(res.totalPrice.amountIncludingTax)
                + "</span></div>"
                + "<div class=\"receipt-line receipt-sub\"><span class=\"receipt-label\">dont HT</span>"
                + "<span class=\"receipt-amount\">" + euro(res.totalPrice.amountExcludingTax)
                + "</span></div>";
        }
        (res.vatBreakdown || []).forEach(function (line) {
            html += "<div class=\"receipt-line receipt-vat\"><span class=\"receipt-label\">TVA "
                + rate(line.vatRate) + "</span><span class=\"receipt-amount\">base "
                + euro(line.amountExcludingTax) + " &middot; taxe " + euro(line.vatAmount)
                + " &middot; TTC " + euro(line.amountIncludingTax) + "</span></div>";
        });
        if (!hasLine && !res.totalPrice && !discounts.length) {
            return "<p class=\"placeholder-note\">Rien à afficher.</p>";
        }
        html += "<div class=\"receipt-rule\"></div>"
            + "<div class=\"receipt-foot\">Merci de votre visite</div></div>";
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
     * Fills the receipt panel from the request and response JSON printed in the page,
     * falling back to a note on parse errors. The receipt is a view of the response; the
     * request only lends its store and customer to the paper head.
     */
    function buildReceipt() {
        var panel = document.querySelector("[data-panel='response-receipt']");
        if (!panel) {
            return;
        }
        var request = null;
        var response = null;
        try {
            var requestSrc = document.querySelector("[data-role='request-src']");
            var responseSrc = document.querySelector("[data-role='response-src']");
            request = requestSrc ? JSON.parse(requestSrc.textContent) : null;
            response = responseSrc ? JSON.parse(responseSrc.textContent) : null;
        } catch (e) {
            panel.innerHTML = "<p class=\"placeholder-note\">Could not read this payload. "
                + "Use the JSON tab.</p>";
            return;
        }
        panel.innerHTML = renderReceipt(request, response);
    }

    /**
     * Wires the view tabs (Readable, Ticket, JSON) for a given side. Any panel whose name
     * starts with the target's hides unless it is the selected view's, so a side can carry
     * any number of views.
     */
    function wireToggle() {
        document.querySelectorAll(".view-tab").forEach(function (tab) {
            tab.addEventListener("click", function () {
                var target = tab.getAttribute("data-target");
                var view = tab.getAttribute("data-view");
                document.querySelectorAll(".view-tab[data-target='" + target + "']").forEach(function (t) {
                    t.classList.toggle("is-active", t === tab);
                });
                var prefix = target + "-";
                document.querySelectorAll("[data-panel]").forEach(function (panel) {
                    var name = panel.getAttribute("data-panel");
                    if (name.indexOf(prefix) === 0) {
                        panel.classList.toggle("is-hidden", name !== prefix + view);
                    }
                });
            });
        });
    }

    build("request");
    build("response");
    buildReceipt();
    wireToggle();
})();
