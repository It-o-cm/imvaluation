/*
 * Autocomplete inputs for the offer list filters.
 *
 * The list filters accept a single value each, unlike the editor which manages sets of
 * values. The widget is therefore a plain text input backed by a suggestion dropdown:
 * the typed text is always submitted as is, so a value that matches nothing known stays
 * a valid filter rather than being silently discarded.
 */
(function () {
    'use strict';

    /**
     * Lookup endpoints, keyed by the data-lookup attribute of the host element.
     */
    var ENDPOINTS = {
        'store-any': '/ui/lookup/targets',
        'store': '/ui/lookup/stores',
        'ean': '/ui/lookup/products'
    };

    /**
     * Placeholder text, keyed by the data-lookup attribute of the host element.
     */
    var PLACEHOLDERS = {
        'store-any': 'Store or group code',
        'store': 'Store code or name',
        'ean': 'EAN or product name'
    };

    /**
     * Creates an element with an optional class name and text content.
     *
     * @param {string} tag The tag name.
     * @param {string} [className] The class attribute.
     * @param {string} [text] The text content.
     * @returns {HTMLElement} The created element.
     */
    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text !== undefined && text !== null) { node.textContent = text; }
        return node;
    }

    /**
     * Debounces a function call.
     *
     * @param {Function} fn The function to debounce.
     * @param {number} delay The delay in milliseconds.
     * @returns {Function} The debounced function.
     */
    function debounce(fn, delay) {
        var timer = null;
        return function () {
            var args = arguments, self = this;
            clearTimeout(timer);
            timer = setTimeout(function () { fn.apply(self, args); }, delay);
        };
    }

    /**
     * Fetches suggestions from a lookup endpoint.
     *
     * @param {string} endpoint The lookup URL.
     * @param {string} query The search term.
     * @returns {Promise<Array>} The suggestions, empty on failure.
     */
    function lookup(endpoint, query) {
        return fetch(endpoint + '?q=' + encodeURIComponent(query || ''), {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        }).then(function (response) {
            return response.ok ? response.json() : [];
        }).catch(function () {
            return [];
        });
    }

    /**
     * Turns a host element into a single-value autocomplete filter input.
     *
     * @param {HTMLElement} host The element carrying the data-lookup attributes.
     */
    function buildFilterInput(host) {
        var kind = host.getAttribute('data-lookup');
        var endpoint = ENDPOINTS[kind];
        if (!endpoint) { return; }

        var wrapper = el('div', 'ac ac-single');
        var inputRow = el('div', 'ac-input-row');
        var input = el('input', 'ac-input');
        input.type = 'text';
        input.name = host.getAttribute('data-name');
        input.autocomplete = 'off';
        input.placeholder = PLACEHOLDERS[kind] || 'Search\u2026';
        input.value = host.getAttribute('data-value') || '';

        var dropdown = el('div', 'ac-dropdown is-hidden');
        inputRow.appendChild(input);
        inputRow.appendChild(dropdown);
        wrapper.appendChild(inputRow);
        host.appendChild(wrapper);

        /**
         * Hides the suggestion dropdown.
         */
        function hide() {
            dropdown.classList.add('is-hidden');
            dropdown.innerHTML = '';
        }

        /**
         * Renders the suggestion dropdown.
         *
         * @param {Array} suggestions The suggestions to display.
         */
        function show(suggestions) {
            dropdown.innerHTML = '';
            if (!suggestions.length) {
                hide();
                return;
            }
            suggestions.forEach(function (suggestion) {
                var item = el('button', 'ac-item');
                item.type = 'button';
                item.appendChild(el('span', 'ac-item-value', suggestion.value));
                item.appendChild(el('span', 'ac-item-label', suggestion.label));
                if (suggestion.detail) {
                    item.appendChild(el('span', 'ac-item-detail', suggestion.detail));
                }
                item.addEventListener('mousedown', function (event) {
                    event.preventDefault();
                    input.value = suggestion.value;
                    hide();
                    // Picking a suggestion is an explicit choice, so apply it immediately.
                    input.form.submit();
                });
                dropdown.appendChild(item);
            });
            dropdown.classList.remove('is-hidden');
        }

        var search = debounce(function () {
            lookup(endpoint, input.value).then(show);
        }, 180);

        input.addEventListener('input', search);
        input.addEventListener('focus', search);
        input.addEventListener('blur', function () { setTimeout(hide, 150); });
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                hide();
            }
        });
    }

    /**
     * Wires every filter input declared in the markup.
     */
    function init() {
        document.querySelectorAll('[data-lookup]').forEach(buildFilterInput);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();

/*
 * CSV import trigger.
 *
 * The file input is kept hidden so the toolbar shows a button consistent with the other
 * actions; picking a file submits the form straight away, since a separate confirmation
 * step would add nothing.
 */
(function () {
    'use strict';

    /**
     * Wires the import button to the hidden file input.
     */
    function initImport() {
        var trigger = document.getElementById('import-trigger');
        var input = document.getElementById('import-file');
        var form = document.getElementById('import-form');
        if (!trigger || !input || !form) { return; }

        trigger.addEventListener('click', function () {
            input.click();
        });
        input.addEventListener('change', function () {
            if (input.files && input.files.length) {
                form.submit();
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initImport);
    } else {
        initImport();
    }
})();
