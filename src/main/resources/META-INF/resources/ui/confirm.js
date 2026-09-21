// Shared confirmation handler (report C3).
//
// Delete forms declare their confirmation message in a `data-confirm` attribute rather than in
// an inline `onsubmit` handler. An inline handler interpolates a value (an offer code, a user
// name) straight into a JavaScript string, where HTML escaping does not protect the JS context;
// a `data-confirm` attribute is plain text the browser never executes. This one script attaches a
// submit listener to every form carrying the attribute and blocks the submit if the user cancels.
(function () {
    "use strict";
    document.addEventListener("submit", function (event) {
        var form = event.target;
        if (form && form.hasAttribute && form.hasAttribute("data-confirm")) {
            if (!window.confirm(form.getAttribute("data-confirm"))) {
                event.preventDefault();
            }
        }
    });
})();
