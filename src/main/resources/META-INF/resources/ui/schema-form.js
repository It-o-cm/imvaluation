/*
 * Schema-driven offer specification editor.
 *
 * The form is generated from the JSON Schema declared by the factory handling the selected
 * offer type. Each property is rendered by resolving a widget in three steps:
 *
 *   1. the "x-widget" annotation carried by the schema property,
 *   2. a naming convention applied to the property name,
 *   3. the raw JSON type of the property.
 *
 * Step 1 gives a hand-tuned rendering per offer type, step 2 keeps new offer types
 * ergonomic without touching this file, step 3 guarantees that anything still renders.
 */
(function () {
    'use strict';

    // --------------------------------------------------
    // Naming conventions (fallback when x-widget is absent)
    // --------------------------------------------------

    /**
     * Ordered list of conventions. The first matching entry wins, so the most specific
     * suffixes must be declared before the broader ones.
     */
    var CONVENTIONS = [
        { test: function (n) { return /eans$/.test(n); }, widget: 'ean-list' },
        { test: function (n) { return /ean$/.test(n); }, widget: 'ean' },
        { test: function (n) { return /(storegroupcodes|storegroupcode)$/.test(n); }, widget: 'store-group-code' },
        { test: function (n) { return /(storecodes|storecode|idpdv|pdv)$/.test(n); }, widget: 'store-code' },
        { test: function (n) { return /flags?$/.test(n); }, widget: 'product-family-flag' },
        { test: function (n) { return /percent$/.test(n); }, widget: 'percent' },
        { test: function (n) { return /(vatrate|taxrate|rate)$/.test(n); }, widget: 'rate' },
        { test: function (n) { return /(price|amount|threshold|cap)$/.test(n); }, widget: 'money' },
        { test: function (n) { return /(distance|radius)$/.test(n); }, widget: 'distance' },
        { test: function (n) { return /volume$/.test(n); }, widget: 'volume' },
        { test: function (n) { return /weight$/.test(n); }, widget: 'weight' },
        { test: function (n) { return /quantity$/.test(n); }, widget: 'quantity' }
    ];

    /**
     * Lookup endpoints feeding the autocomplete widgets.
     */
    var LOOKUP = {
        'ean': '/ui/lookup/products',
        'ean-list': '/ui/lookup/products',
        'store-code': '/ui/lookup/stores',
        'store-group-code': '/ui/lookup/store-groups',
        'product-family-flag': '/ui/lookup/flags'
    };

    /**
     * Unit suffixes displayed next to numeric widgets.
     */
    var UNITS = {
        'money': '\u20ac',
        'rate': '%',
        'percent': '%',
        'distance': 'km',
        'volume': 'L',
        'weight': 'kg'
    };

    // --------------------------------------------------
    // Widget resolution
    // --------------------------------------------------

    /**
     * Resolves the widget to use for a schema property.
     *
     * @param {string} name The property name.
     * @param {object} schema The property schema.
     * @returns {string} The resolved widget identifier.
     */
    function resolveWidget(name, schema) {
        if (schema && schema['x-widget']) {
            return schema['x-widget'];
        }
        var normalized = String(name || '').toLowerCase();
        if (normalized === 'vignettes') {
            return 'ean-quantity-map';
        }
        for (var i = 0; i < CONVENTIONS.length; i++) {
            if (CONVENTIONS[i].test(normalized)) {
                return CONVENTIONS[i].widget;
            }
        }
        return resolveByType(schema);
    }

    /**
     * Resolves a widget from the raw JSON type when no annotation or convention applies.
     *
     * @param {object} schema The property schema.
     * @returns {string} The resolved widget identifier.
     */
    function resolveByType(schema) {
        if (!schema) {
            return 'text';
        }
        if (schema.enum) {
            return 'enum';
        }
        if (schema.oneOf) {
            return 'string-list';
        }
        switch (schema.type) {
            case 'integer': return 'integer';
            case 'number': return 'number';
            case 'boolean': return 'boolean';
            case 'array': return schema.items && schema.items.type === 'object' ? 'object-list' : 'string-list';
            case 'object': return 'object';
            default: return 'text';
        }
    }

    /**
     * Computes the label of a property, honouring the "x-label" annotation.
     *
     * @param {string} name The property name.
     * @param {object} schema The property schema.
     * @returns {string} The label to display.
     */
    function labelOf(name, schema) {
        if (schema && schema['x-label']) {
            return schema['x-label'];
        }
        var spaced = String(name).replace(/([A-Z])/g, ' $1').replace(/[_-]+/g, ' ');
        return spaced.charAt(0).toUpperCase() + spaced.slice(1).trim();
    }

    /**
     * Resolves every local {@code #/definitions/…} reference of a schema, in place: some
     * factory schemas (the tiered discount's award) declare their nested objects through a
     * {@code $ref}, and without this resolution the field builder sees a node without a type
     * and falls back to a plain text input — which renders an object value as the infamous
     * "[object Object]". Unresolvable references stay as they are, so the builder keeps
     * its text fallback rather than crashing.
     *
     * @param {*} node The schema node being resolved.
     * @param {object} root The whole schema, owning the definitions.
     * @param {string[]} seen The definition names on the current path, against cycles.
     * @returns {*} The resolved node.
     */
    function dereference(node, root, seen) {
        if (!node || typeof node !== 'object') {
            return node;
        }
        if (Array.isArray(node)) {
            for (var i = 0; i < node.length; i += 1) {
                node[i] = dereference(node[i], root, seen);
            }
            return node;
        }
        if (typeof node.$ref === 'string' && node.$ref.indexOf('#/definitions/') === 0) {
            var name = node.$ref.substring('#/definitions/'.length);
            var target = (root.definitions || {})[name];
            if (target && seen.indexOf(name) === -1) {
                seen.push(name);
                // A deep copy keeps the definition intact for the next reference.
                var copy = dereference(JSON.parse(JSON.stringify(target)), root, seen);
                seen.pop();
                return copy;
            }
            return node;
        }
        Object.keys(node).forEach(function (key) {
            node[key] = dereference(node[key], root, seen);
        });
        return node;
    }

    // --------------------------------------------------
    // DOM helpers
    // --------------------------------------------------

    /**
     * Creates an element with optional class name and text content.
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

    // --------------------------------------------------
    // Autocomplete field (shared by every lookup widget)
    // --------------------------------------------------

    /**
     * Parses the text of a one-column EAN file into a de-duplicated, order-stable list of EANs
     * (spec-ciblage-saisie §4). A leading UTF-8 BOM is stripped, blank lines are ignored, a single
     * comma/semicolon/tab-delimited column is tolerated (only the first field is kept), and
     * duplicates are dropped. A file containing a NUL byte is treated as binary and rejected by
     * returning {@code null}. Parsing is entirely client-side; the file is never sent to the server.
     *
     * @param {string} text The raw file text.
     * @returns {?string[]} The parsed EANs, or {@code null} when the file looks binary.
     */
    function parseEanList(text) {
        if (text == null) { return null; }
        var content = text.charCodeAt(0) === 0xFEFF ? text.slice(1) : text;
        if (content.indexOf('\u0000') !== -1) { return null; }
        var seen = {};
        var result = [];
        content.split(/\r\n|\r|\n/).forEach(function (line) {
            var value = String(line).split(/[,;\t]/)[0].trim();
            if (!value) { return; }
            if (Object.prototype.hasOwnProperty.call(seen, value)) { return; }
            seen[value] = true;
            result.push(value);
        });
        return result;
    }

    /**
     * Builds an autocomplete input bound to a lookup endpoint.
     *
     * Values that do not resolve to a known entity remain usable: they are flagged with a
     * warning rather than rejected, because an offer may legitimately target a product or
     * a store that has not been imported yet.
     *
     * @param {object} options Configuration of the field.
     * @returns {object} A controller exposing the root node and the value accessors.
     */
    function buildAutocomplete(options) {
        var endpoint = options.endpoint;
        var multiple = !!options.multiple;
        var values = options.values ? options.values.slice() : [];

        var root = el('div', 'ac' + (multiple ? ' ac-multi' : ''));
        var chips = el('div', 'ac-chips');
        var inputRow = el('div', 'ac-input-row');
        var input = el('input', 'ac-input');
        input.type = 'text';
        input.autocomplete = 'off';
        input.placeholder = options.placeholder || 'Type to search\u2026';
        var dropdown = el('div', 'ac-dropdown is-hidden');

        inputRow.appendChild(input);
        inputRow.appendChild(dropdown);
        if (multiple) { root.appendChild(chips); }
        root.appendChild(inputRow);

        var labelCache = {};

        /**
         * Adds the "Import a file" control to an EAN-list widget (spec-ciblage-saisie §4): a text or
         * one-column CSV file is read and parsed entirely in the browser through {@link parseEanList}
         * and its EANs fill the field; a binary file is refused with a message. The file is never
         * sent to the server.
         */
        function attachFileImport() {
            if (!multiple || endpoint !== LOOKUP['ean-list']) { return; }
            var importRow = el('div', 'ac-file-import');
            var importButton = el('button', 'btn btn-ghost ac-file-button', 'Import a file');
            importButton.type = 'button';
            var fileInput = el('input', 'ac-file-input');
            fileInput.type = 'file';
            fileInput.accept = '.txt,.csv,text/plain,text/csv';
            fileInput.style.display = 'none';
            var importError = el('div', 'ac-file-error');
            importButton.addEventListener('click', function () { fileInput.click(); });
            fileInput.addEventListener('change', function () {
                importError.textContent = '';
                var file = fileInput.files && fileInput.files[0];
                if (!file) { return; }
                var reader = new FileReader();
                reader.onload = function () {
                    var eans = parseEanList(String(reader.result));
                    if (eans === null) {
                        importError.textContent = 'This file is not a readable EAN list.';
                    } else {
                        eans.forEach(addValue);
                    }
                    fileInput.value = '';
                };
                reader.onerror = function () {
                    importError.textContent = 'This file could not be read.';
                    fileInput.value = '';
                };
                reader.readAsText(file);
            });
            importRow.appendChild(importButton);
            importRow.appendChild(fileInput);
            importRow.appendChild(importError);
            root.appendChild(importRow);
        }

        /**
         * Renders the chips of the currently selected values.
         */
        function renderChips() {
            if (!multiple) { return; }
            chips.innerHTML = '';
            values.forEach(function (value, index) {
                var known = labelCache[value];
                var chip = el('span', 'ac-chip' + (known === undefined ? ' ac-chip-unknown' : ''));
                chip.appendChild(el('span', 'ac-chip-value', value));
                if (known) {
                    chip.appendChild(el('span', 'ac-chip-label', known));
                }
                var remove = el('button', 'ac-chip-remove', '\u00d7');
                remove.type = 'button';
                remove.setAttribute('aria-label', 'Remove ' + value);
                remove.addEventListener('click', function () {
                    values.splice(index, 1);
                    renderChips();
                    options.onChange();
                });
                chip.appendChild(remove);
                chips.appendChild(chip);
            });
        }

        /**
         * Adds a value to the selection, ignoring blanks and duplicates.
         *
         * @param {string} value The value to add.
         */
        function addValue(value) {
            var trimmed = String(value || '').trim();
            if (!trimmed) { return; }
            if (multiple) {
                if (values.indexOf(trimmed) === -1) {
                    values.push(trimmed);
                }
                input.value = '';
                renderChips();
            } else {
                values = [trimmed];
                input.value = trimmed;
            }
            hideDropdown();
            options.onChange();
        }

        /**
         * Hides the suggestion dropdown.
         */
        function hideDropdown() {
            dropdown.classList.add('is-hidden');
            dropdown.innerHTML = '';
        }

        /**
         * Renders the suggestion dropdown.
         *
         * @param {Array} suggestions The suggestions to display.
         */
        function showDropdown(suggestions) {
            dropdown.innerHTML = '';
            if (!suggestions.length) {
                dropdown.appendChild(el('div', 'ac-empty', 'No match \u2014 the value can still be used as is.'));
            }
            suggestions.forEach(function (suggestion) {
                labelCache[suggestion.value] = suggestion.label;
                var item = el('button', 'ac-item');
                item.type = 'button';
                item.appendChild(el('span', 'ac-item-value', suggestion.value));
                item.appendChild(el('span', 'ac-item-label', suggestion.label));
                if (suggestion.detail) {
                    item.appendChild(el('span', 'ac-item-detail', suggestion.detail));
                }
                item.addEventListener('mousedown', function (event) {
                    event.preventDefault();
                    addValue(suggestion.value);
                });
                dropdown.appendChild(item);
            });
            dropdown.classList.remove('is-hidden');
        }

        var search = debounce(function () {
            lookup(endpoint, input.value).then(showDropdown);
        }, 180);

        input.addEventListener('input', function () {
            if (!multiple) {
                values = input.value.trim() ? [input.value.trim()] : [];
                options.onChange();
            }
            search();
        });

        input.addEventListener('focus', search);
        input.addEventListener('blur', function () { setTimeout(hideDropdown, 150); });

        input.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                // A complete barcode typed or scanned is added straight away.
                addValue(input.value);
            } else if (event.key === 'Backspace' && multiple && !input.value && values.length) {
                values.pop();
                renderChips();
                options.onChange();
            } else if (event.key === 'Escape') {
                hideDropdown();
            }
        });

        if (!multiple && values.length) {
            input.value = values[0];
        }

        // Resolve the labels of the pre-existing values so the chips are readable.
        if (values.length && endpoint === LOOKUP['ean-list']) {
            fetch('/ui/lookup/products/resolve?eans=' + encodeURIComponent(values.join(',')), {
                headers: { 'Accept': 'application/json' },
                credentials: 'same-origin'
            }).then(function (response) {
                return response.ok ? response.json() : [];
            }).then(function (resolved) {
                resolved.forEach(function (item) { labelCache[item.value] = item.label; });
                renderChips();
            }).catch(function () { /* labels stay unresolved, values remain editable */ });
        }

        renderChips();
        attachFileImport();

        return {
            node: root,
            getValues: function () { return values.slice(); },
            getValue: function () { return values.length ? values[0] : ''; }
        };
    }

    // --------------------------------------------------
    // Field builders
    // --------------------------------------------------

    /**
     * Builds the editor of a single schema property.
     *
     * @param {string} name The property name.
     * @param {object} schema The property schema.
     * @param {*} value The current value.
     * @param {boolean} required Whether the property is required.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildField(name, schema, value, required, onChange) {
        var widget = resolveWidget(name, schema);
        var wrapper = el('div', 'field field-' + widget);
        // Repeaters and nested objects need the whole row; simple fields sit in the grid.
        var WIDE = ['object-list', 'object', 'ean-quantity-map', 'string-list', 'ean-list'];
        if (WIDE.indexOf(widget) !== -1) {
            wrapper.classList.add('field-wide');
        }
        var label = el('label', 'field-label');
        label.appendChild(document.createTextNode(labelOf(name, schema)));
        if (required) {
            label.appendChild(el('em', null, '*'));
        }
        wrapper.appendChild(label);

        var controller = buildControl(widget, name, schema, value, onChange);
        wrapper.appendChild(controller.node);

        if (schema && schema.description) {
            wrapper.appendChild(el('span', 'field-hint', schema.description));
        }
        return { node: wrapper, read: controller.read };
    }

    /**
     * Builds the input control matching a resolved widget.
     *
     * @param {string} widget The widget identifier.
     * @param {string} name The property name.
     * @param {object} schema The property schema.
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildControl(widget, name, schema, value, onChange) {
        switch (widget) {
            case 'ean':
            case 'store-code':
            case 'store-group-code':
            case 'product-family-flag':
                return buildLookupControl(widget, value, false, onChange);

            case 'ean-list':
                return buildLookupControl(widget, value, true, onChange);

            case 'enum':
                return buildEnumControl(schema, value, onChange);

            case 'boolean':
                return buildBooleanControl(value, onChange);

            case 'object-list':
                return buildObjectListControl(schema, value, onChange);

            case 'object':
                return buildObjectControl(schema, value, onChange);

            case 'ean-quantity-map':
                return buildEanQuantityMapControl(value, onChange);

            case 'string-list':
                return buildStringListControl(value, onChange);

            case 'money':
            case 'rate':
            case 'percent':
            case 'distance':
            case 'volume':
            case 'weight':
            case 'discount-value':
            case 'quantity':
            case 'integer':
            case 'number':
                return buildNumberControl(widget, schema, value, onChange);

            default:
                return buildTextControl(schema, value, onChange);
        }
    }

    /**
     * Builds an autocomplete-backed control.
     *
     * @param {string} widget The widget identifier.
     * @param {*} value The current value.
     * @param {boolean} multiple Whether several values can be selected.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildLookupControl(widget, value, multiple, onChange) {
        var initial = [];
        if (Array.isArray(value)) {
            initial = value.slice();
        } else if (value !== undefined && value !== null && value !== '') {
            initial = [String(value)];
        }
        var ac = buildAutocomplete({
            endpoint: LOOKUP[widget],
            multiple: multiple,
            values: initial,
            placeholder: multiple ? 'Scan or search a product\u2026' : 'Type to search\u2026',
            onChange: onChange
        });
        return {
            node: ac.node,
            read: function () {
                if (multiple) {
                    var values = ac.getValues();
                    return values.length ? values : undefined;
                }
                var single = ac.getValue();
                return single ? single : undefined;
            }
        };
    }

    /**
     * Builds a select control for an enumerated property.
     *
     * @param {object} schema The property schema.
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildEnumControl(schema, value, onChange) {
        var select = el('select', 'input-control');
        select.appendChild(el('option', null, '\u2014'));
        select.firstChild.value = '';
        (schema.enum || []).forEach(function (option) {
            var node = el('option', null, option);
            node.value = option;
            if (option === value) { node.selected = true; }
            select.appendChild(node);
        });
        select.addEventListener('change', onChange);
        return {
            node: select,
            read: function () { return select.value || undefined; }
        };
    }

    /**
     * Builds a checkbox control.
     *
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildBooleanControl(value, onChange) {
        var wrapper = el('div', 'toggle');
        var input = el('input');
        input.type = 'checkbox';
        input.checked = value === true;
        input.addEventListener('change', onChange);
        wrapper.appendChild(input);
        return {
            node: wrapper,
            read: function () { return input.checked; }
        };
    }

    /**
     * Builds a numeric control carrying its unit and the schema bounds.
     *
     * @param {string} widget The widget identifier.
     * @param {object} schema The property schema.
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildNumberControl(widget, schema, value, onChange) {
        var isInteger = widget === 'integer' || widget === 'quantity' || (schema && schema.type === 'integer');
        // A "rate" is stored as a fraction (0.2) but shown as a percentage (20), so the
        // value and the schema bounds are scaled for display and unscaled on read. A
        // "percent" is already expressed in percent and needs no scaling.
        var scale = widget === 'rate' ? 100 : 1;
        var wrapper = el('div', 'input-unit');
        var input = el('input', 'input-control');
        input.type = 'number';
        input.step = isInteger ? '1' : (scale === 100 || widget === 'percent' ? '0.01' : '0.01');
        if (schema) {
            if (schema.minimum !== undefined) { input.min = schema.minimum * scale; }
            if (schema.maximum !== undefined) { input.max = schema.maximum * scale; }
            if (schema.exclusiveMinimum !== undefined) {
                input.min = isInteger ? schema.exclusiveMinimum + 1 : schema.exclusiveMinimum * scale;
            }
        }
        if (value !== undefined && value !== null && value !== '') {
            // Scaling can surface float artefacts (0.2 * 100 = 20.000000000000004), so the
            // displayed figure is trimmed.
            input.value = scale === 1 ? value : parseFloat((value * scale).toFixed(6));
        }
        input.addEventListener('input', onChange);
        wrapper.appendChild(input);

        var unit = UNITS[widget];
        if (unit) {
            wrapper.appendChild(el('span', 'input-suffix', unit));
        }
        return {
            node: wrapper,
            read: function () {
                if (input.value === '') { return undefined; }
                var parsed = isInteger ? parseInt(input.value, 10) : parseFloat(input.value);
                if (isNaN(parsed)) { return undefined; }
                if (scale === 1) { return parsed; }
                // Back to a fraction, trimmed so 20 / 100 stays 0.2 and not 0.2000000001.
                return parseFloat((parsed / scale).toFixed(8));
            }
        };
    }

    /**
     * Builds a plain text control.
     *
     * @param {object} schema The property schema.
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildTextControl(schema, value, onChange) {
        var input = el('input', 'input-control');
        input.type = 'text';
        if (schema && schema.minLength) { input.minLength = schema.minLength; }
        if (value !== undefined && value !== null) { input.value = value; }
        input.addEventListener('input', onChange);
        return {
            node: input,
            read: function () { return input.value ? input.value : undefined; }
        };
    }

    /**
     * Builds a repeater for a list of free strings.
     *
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildStringListControl(value, onChange) {
        var items = Array.isArray(value) ? value.slice() : (value ? [String(value)] : []);
        var wrapper = el('div', 'repeater');
        var list = el('div', 'repeater-rows');
        wrapper.appendChild(list);

        /**
         * Redraws every row of the repeater.
         */
        function render() {
            list.innerHTML = '';
            items.forEach(function (item, index) {
                var row = el('div', 'repeater-row');
                var input = el('input', 'input-control');
                input.type = 'text';
                input.value = item;
                input.addEventListener('input', function () {
                    items[index] = input.value;
                    onChange();
                });
                row.appendChild(input);
                var remove = el('button', 'btn btn-small btn-danger', 'Remove');
                remove.type = 'button';
                remove.addEventListener('click', function () {
                    items.splice(index, 1);
                    render();
                    onChange();
                });
                row.appendChild(remove);
                list.appendChild(row);
            });
        }

        var add = el('button', 'btn btn-small', 'Add value');
        add.type = 'button';
        add.addEventListener('click', function () {
            items.push('');
            render();
            onChange();
        });
        wrapper.appendChild(add);
        render();

        return {
            node: wrapper,
            read: function () {
                var cleaned = items.map(function (i) { return String(i).trim(); })
                                   .filter(function (i) { return i.length > 0; });
                return cleaned.length ? cleaned : undefined;
            }
        };
    }

    /**
     * Builds an editor for a map of product EAN to quantity, such as the vignettes a
     * customer presents at the register.
     *
     * Keys are products, so each row pairs a product autocomplete with a count rather
     * than exposing the raw JSON object.
     *
     * @param {*} value The current map.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildEanQuantityMapControl(value, onChange) {
        var rows = [];
        if (value && typeof value === 'object' && !Array.isArray(value)) {
            Object.keys(value).forEach(function (ean) {
                rows.push({ ean: ean, quantity: value[ean] });
            });
        }
        var wrapper = el('div', 'repeater repeater-object');
        var list = el('div', 'repeater-rows');
        wrapper.appendChild(list);

        /**
         * Redraws every row of the map editor.
         */
        function render() {
            list.innerHTML = '';
            rows.forEach(function (row, index) {
                var rowNode = el('div', 'repeater-object-row');
                var head = el('div', 'repeater-object-head');
                head.appendChild(el('span', 'repeater-index', 'entry ' + (index + 1)));
                var remove = el('button', 'btn btn-small btn-danger', 'Remove');
                remove.type = 'button';
                remove.addEventListener('click', function () {
                    rows.splice(index, 1);
                    render();
                    onChange();
                });
                head.appendChild(remove);
                rowNode.appendChild(head);

                var grid = el('div', 'field-grid');

                var eanField = buildField('ean', { 'x-widget': 'ean', 'x-label': 'Product' },
                    row.ean, true, function () {
                        row.ean = eanField.read();
                        onChange();
                    });
                grid.appendChild(eanField.node);

                var qtyField = buildField('quantity',
                    { type: 'integer', minimum: 0, 'x-widget': 'quantity', 'x-label': 'Vignettes' },
                    row.quantity, true, function () {
                        row.quantity = qtyField.read();
                        onChange();
                    });
                grid.appendChild(qtyField.node);

                rowNode.appendChild(grid);
                list.appendChild(rowNode);
            });
        }

        var add = el('button', 'btn btn-small', 'Add entry');
        add.type = 'button';
        add.addEventListener('click', function () {
            rows.push({});
            render();
            onChange();
        });
        wrapper.appendChild(add);
        render();

        return {
            node: wrapper,
            read: function () {
                var result = {};
                var filled = false;
                rows.forEach(function (row) {
                    if (row.ean && row.quantity !== undefined) {
                        result[row.ean] = row.quantity;
                        filled = true;
                    }
                });
                return filled ? result : undefined;
            }
        };
    }

    /**
     * Builds an editor for a nested object property, such as the discount block of a
     * vignette catalog entry. The nested properties are rendered inside a bordered group
     * so the hierarchy stays readable.
     *
     * @param {object} schema The object schema.
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildObjectControl(schema, value, onChange) {
        var current = (value && typeof value === 'object' && !Array.isArray(value)) ? value : {};
        var wrapper = el('div', 'nested-object');
        var grid = el('div', 'field-grid');
        var required = (schema && schema.required) || [];
        var children = [];

        Object.keys((schema && schema.properties) || {}).forEach(function (propName) {
            var propSchema = schema.properties[propName];
            var field = buildField(propName, propSchema, current[propName],
                required.indexOf(propName) !== -1, function () {
                    onChange();
                });
            field.name = propName;
            children.push(field);
            grid.appendChild(field.node);
        });

        wrapper.appendChild(grid);
        return {
            node: wrapper,
            read: function () {
                var result = {};
                var filled = false;
                children.forEach(function (child) {
                    var read = child.read();
                    if (read !== undefined) {
                        result[child.name] = read;
                        filled = true;
                    }
                });
                return filled ? result : undefined;
            }
        };
    }

    /**
     * Builds a repeater for a list of objects, such as the delivery pricing tiers.
     *
     * @param {object} schema The array schema.
     * @param {*} value The current value.
     * @param {Function} onChange Callback invoked on every edit.
     * @returns {object} A controller exposing the node and a read function.
     */
    function buildObjectListControl(schema, value, onChange) {
        var itemSchema = (schema && schema.items) || { type: 'object', properties: {} };
        var itemLabel = (schema && schema['x-item-label']) || 'entry';
        var rows = Array.isArray(value) ? value.slice() : [];
        var wrapper = el('div', 'repeater repeater-object');
        var list = el('div', 'repeater-rows');
        wrapper.appendChild(list);

        /**
         * Redraws every row, each row being a nested set of fields.
         */
        function render() {
            list.innerHTML = '';
            rows.forEach(function (row, index) {
                var rowNode = el('div', 'repeater-object-row');
                var head = el('div', 'repeater-object-head');
                head.appendChild(el('span', 'repeater-index', itemLabel + ' ' + (index + 1)));
                var remove = el('button', 'btn btn-small btn-danger', 'Remove');
                remove.type = 'button';
                remove.addEventListener('click', function () {
                    rows.splice(index, 1);
                    render();
                    onChange();
                });
                head.appendChild(remove);
                rowNode.appendChild(head);

                var grid = el('div', 'field-grid');
                var required = itemSchema.required || [];
                Object.keys(itemSchema.properties || {}).forEach(function (propName) {
                    var propSchema = itemSchema.properties[propName];
                    var field = buildField(propName, propSchema, row[propName],
                        required.indexOf(propName) !== -1, function () {
                            var read = field.read();
                            if (read === undefined) {
                                delete row[propName];
                            } else {
                                row[propName] = read;
                            }
                            onChange();
                        });
                    grid.appendChild(field.node);
                });
                rowNode.appendChild(grid);
                list.appendChild(rowNode);
            });
        }

        var add = el('button', 'btn btn-small', 'Add ' + itemLabel);
        add.type = 'button';
        add.addEventListener('click', function () {
            rows.push({});
            render();
            onChange();
        });
        wrapper.appendChild(add);
        render();

        return {
            node: wrapper,
            read: function () { return rows.length ? rows : undefined; }
        };
    }

    // --------------------------------------------------
    // Editor wiring
    // --------------------------------------------------

    var schemas = {};
    var specification = {};
    var fields = [];
    var mode = 'form';

    var typeSelect = document.getElementById('offer-type');
    var formHost = document.getElementById('schema-form');
    var jsonHost = document.getElementById('json-editor');
    var rawTextarea = document.getElementById('specification-raw');
    var hiddenInput = document.getElementById('specification');
    var errorHost = document.getElementById('spec-errors');

    /**
     * Reads the specification currently described by the generated form.
     *
     * @returns {object} The rebuilt specification.
     */
    function readForm() {
        var result = {};
        fields.forEach(function (field) {
            var value = field.read();
            if (value !== undefined) {
                result[field.name] = value;
            }
        });
        return result;
    }

    /**
     * Pushes the current state into the hidden input submitted with the form.
     */
    function syncHidden() {
        var payload = mode === 'json' ? rawTextarea.value : JSON.stringify(readForm(), null, 2);
        hiddenInput.value = payload;
    }

    /**
     * Validates the current values against the selected schema and displays the findings.
     *
     * Only the constraints cheap to evaluate client side are checked here; the server
     * re-validates the whole document against the schema before persisting.
     */
    function validate() {
        var schema = schemas[typeSelect.value];
        errorHost.innerHTML = '';
        if (!schema || mode === 'json') {
            errorHost.classList.add('is-hidden');
            return;
        }
        var current = readForm();
        var missing = (schema.required || []).filter(function (name) {
            var value = current[name];
            return value === undefined || value === '' || (Array.isArray(value) && !value.length);
        });
        if (!missing.length) {
            errorHost.classList.add('is-hidden');
            return;
        }
        var list = el('ul', 'spec-error-list');
        missing.forEach(function (name) {
            var propSchema = (schema.properties || {})[name];
            list.appendChild(el('li', null, labelOf(name, propSchema) + ' is required.'));
        });
        errorHost.appendChild(list);
        errorHost.classList.remove('is-hidden');
    }

    /**
     * Called whenever any generated field changes.
     */
    function onFieldChange() {
        syncHidden();
        validate();
    }

    /**
     * Rebuilds the whole form for the selected offer type.
     */
    function renderForm() {
        fields = [];
        formHost.innerHTML = '';
        var schema = schemas[typeSelect.value];

        if (!typeSelect.value) {
            formHost.appendChild(el('p', 'placeholder-note', 'Select an offer type to configure its specification.'));
            syncHidden();
            return;
        }
        if (!schema) {
            formHost.appendChild(el('p', 'placeholder-note',
                'No schema is registered for type "' + typeSelect.value + '". Use the JSON tab to edit the specification.'));
            syncHidden();
            return;
        }

        var grid = el('div', 'field-grid');
        var required = schema.required || [];
        Object.keys(schema.properties || {}).forEach(function (name) {
            if (name === 'targetEans' && schema.properties.targetWhere) {
                // The single targeting field: EANs or a condition, never both — see
                // buildTargetingField.
                var targeting = buildTargetingField();
                fields.push(targeting.eans);
                fields.push(targeting.where);
                grid.appendChild(targeting.node);
                grid.appendChild(targeting.note);
                return;
            }
            if (name === 'targetWhere' && schema.properties.targetEans) {
                return;
            }
            var propSchema = schema.properties[name];
            var field = buildField(name, propSchema, specification[name],
                required.indexOf(name) !== -1, onFieldChange);
            field.name = name;
            fields.push(field);
            grid.appendChild(field.node);
        });
        // Group the family and exclusion fields with the targeting field, whatever their
        // rank in the injected schema.
        var targetingNode = grid.querySelector('.field-targeting');
        ['targetFamilyCodes', 'excludedEans'].forEach(function (name) {
            var moved = fields.find(function (field) { return field.name === name; });
            if (targetingNode && moved) {
                grid.insertBefore(moved.node, targetingNode.nextSibling);
                targetingNode = moved.node;
            }
        });
        formHost.appendChild(grid);
        syncHidden();
        validate();
    }

    /**
     * Builds the single product-targeting field: its content is either a list of EANs —
     * digits, commas and spaces — or a free-form condition, and only the matching
     * specification key is carried: the exclusivity between {@code targetEans} and
     * {@code targetWhere} holds by construction, one input can only read as one of them.
     * <p>
     * The coverage preview under the field shows, live, what the content covers — the
     * count and a sample of matching products, for an EAN list (which reveals EANs that
     * do not exist in the catalog) exactly as for a condition (which reveals an
     * impossible condition), through the very same grammar endpoint.
     *
     * @returns {{node: HTMLElement, note: HTMLElement, eans: object, where: object}}
     *          The field node, its coverage note and the two read entries.
     */
    function buildTargetingField() {
        var node = el('div', 'field field-text field-wide field-targeting');
        var label = el('label', 'field-label', 'Eligible products');
        node.appendChild(label);
        var wrap = el('div', 'targeting-wrap');
        var input = el('input');
        input.type = 'text';
        input.className = 'targeting-input';
        input.value = targetingText();
        input.setAttribute('placeholder', "EANs, or a condition: name like 'pomme%'");
        // The suggestion dropdown of the list reading: while the content is an EAN list,
        // the trailing digits being typed drive the product lookup, and picking a
        // suggestion completes the list with that EAN.
        var dropdown = el('div', 'ac-dropdown is-hidden');
        wrap.appendChild(input);
        wrap.appendChild(dropdown);
        node.appendChild(wrap);
        node.appendChild(el('span', 'field-hint',
            "Either EANs (comma separated) or a condition on brand, ean, egalimRegime, "
            + "name, productType, referenceVolume, referenceWeight."));
        var note = el('p', 'placeholder-note field-wide', '');
        note.classList.add('is-hidden');

        /**
         * Seeds the field from whichever targeting key the specification carries.
         */
        function targetingText() {
            if (typeof specification.targetWhere === 'string' && specification.targetWhere.trim() !== '') {
                return specification.targetWhere;
            }
            if (Array.isArray(specification.targetEans)) {
                return specification.targetEans.join(', ');
            }
            return '';
        }

        /**
         * Splits the field content into its two possible readings.
         *
         * @returns {{eans: ?string[], where: ?string}} The parsed targeting.
         */
        function parse() {
            var text = input.value.trim();
            if (!text) {
                return { eans: null, where: null };
            }
            if (/^[0-9,\s;]+$/.test(text)) {
                return { eans: text.split(/[,;\s]+/).filter(Boolean), where: null };
            }
            return { eans: null, where: text };
        }

        /**
         * Builds the grammar expression the coverage preview resolves: an EAN list
         * becomes an {@code in} condition, so both readings preview identically.
         *
         * @returns {string} The expression, empty when the field is empty.
         */
        function expression() {
            var parsed = parse();
            if (parsed.eans) {
                return 'ean in (' + parsed.eans.map(function (ean) { return "'" + ean + "'"; }).join(', ') + ')';
            }
            return parsed.where || '';
        }

        // The grammar verdict of the last preview: null while pending, true when the
        // content parsed, false when the grammar refused it — the third case of the
        // field's contract is an error, not a condition.
        var verdict = null;

        /**
         * Applies the grammar verdict: a refused content is flagged and dropped from the
         * carried specification; a stale response, arriving after further typing, is
         * ignored.
         */
        function applyVerdict(sent, ok) {
            if (expression() !== sent) {
                return;
            }
            verdict = ok;
            input.classList.toggle('is-invalid', !ok);
            if (!ok) {
                delete specification.targetWhere;
                delete specification.targetEans;
            }
            onFieldChange();
        }

        var refreshPreview = debounce(function () {
            var sent = expression();
            renderCoveragePreview(sent, note, function (ok) {
                applyVerdict(sent, ok);
            });
        }, 300);

        /**
         * The last EAN being typed, when the content reads as a list.
         *
         * @returns {string} The trailing digits, empty in any other case.
         */
        function lastToken() {
            var parsed = parse();
            if (!parsed.eans) {
                return '';
            }
            var match = input.value.match(/[0-9]+$/);
            return match ? match[0] : '';
        }

        /**
         * Hides the suggestion dropdown.
         */
        function hideSuggestions() {
            dropdown.classList.add('is-hidden');
            dropdown.innerHTML = '';
        }

        /**
         * Renders the suggestions of the trailing EAN.
         *
         * @param {Array} suggestions The products matching the typed digits.
         */
        function showSuggestions(suggestions) {
            dropdown.innerHTML = '';
            if (!suggestions.length) {
                dropdown.appendChild(el('div', 'ac-empty', 'No match.'));
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
                    // Completing the last EAN with the picked suggestion, then the usual
                    // input pipeline re-reads the list and refreshes the coverage.
                    input.value = input.value.replace(/[0-9]+$/, suggestion.value);
                    hideSuggestions();
                    input.dispatchEvent(new window.Event('input'));
                });
                dropdown.appendChild(item);
            });
            dropdown.classList.remove('is-hidden');
        }

        var searchSuggestions = debounce(function () {
            var token = lastToken();
            if (!token) {
                hideSuggestions();
                return;
            }
            lookup(LOOKUP['ean-list'], token).then(function (suggestions) {
                showSuggestions(Array.isArray(suggestions) ? suggestions : []);
            });
        }, 180);

        input.addEventListener('input', function () {
            searchSuggestions();
        });
        input.addEventListener('focus', function () {
            searchSuggestions();
        });
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                hideSuggestions();
            }
        });
        input.addEventListener('blur', function () {
            setTimeout(hideSuggestions, 150);
        });

        input.addEventListener('input', function () {
            var parsed = parse();
            verdict = null;
            input.classList.remove('is-invalid');
            if (parsed.eans) {
                specification.targetEans = parsed.eans;
                delete specification.targetWhere;
            } else if (parsed.where) {
                specification.targetWhere = parsed.where;
                delete specification.targetEans;
            } else {
                delete specification.targetEans;
                delete specification.targetWhere;
            }
            refreshPreview();
            onFieldChange();
        });

        if (expression()) {
            var sent = expression();
            renderCoveragePreview(sent, note, function (ok) {
                applyVerdict(sent, ok);
            });
        }
        return {
            node: node,
            note: note,
            eans: {
                name: 'targetEans',
                node: node,
                read: function () {
                    var parsed = parse();
                    return parsed.eans && parsed.eans.length ? parsed.eans : undefined;
                }
            },
            where: {
                name: 'targetWhere',
                node: node,
                read: function () {
                    // A refused content is the error case, not a condition: it does not
                    // travel to the submitted specification.
                    return verdict === false ? undefined : (parse().where || undefined);
                }
            }
        };
    }

    /**
     * Fetches the coverage preview of a conditional targeting and appends it to the note:
     * the exact number of covered products and a capped sample as chips, or the grammar
     * refusal in place. A preview that cannot be fetched degrades to a silent note.
     *
     * @param {string} where The condition text.
     * @param {HTMLElement} note The note element to append the preview to.
     */
    function renderCoveragePreview(where, note, onVerdict) {
        note.innerHTML = '';
        if (!where) {
            note.classList.add('is-hidden');
            return;
        }
        note.classList.remove('is-hidden');
        fetch('/ui/offers/where-preview?where=' + encodeURIComponent(where))
            .then(function (response) { return response.json(); })
            .then(function (preview) {
                if (onVerdict) {
                    onVerdict(!preview.error);
                }
                if (preview.error) {
                    note.appendChild(el('span', null,
                        ' The condition was refused: ' + preview.error));
                    return;
                }
                var line = el('span', null,
                    ' Coverage: ' + preview.count + ' product' + (preview.count === 1 ? '' : 's')
                    + (preview.count > 0 ? ' — ' : ' — the catalog matches nothing.'));
                note.appendChild(line);
                (preview.products || []).forEach(function (product) {
                    note.appendChild(el('span', 'ac-chip ac-chip-unknown',
                        (product.name ? product.name + ' ' : '') + '(' + product.ean + ')'));
                });
            })
            .catch(function () {
                // The note already explains the empty list; a failed preview adds nothing.
            });
    }

    /**
     * Switches between the generated form and the raw JSON editor, carrying the state over.
     *
     * @param {string} target The mode to activate, either "form" or "json".
     */
    function switchMode(target) {
        if (target === mode) { return; }
        if (target === 'json') {
            rawTextarea.value = JSON.stringify(readForm(), null, 2);
            formHost.classList.add('is-hidden');
            jsonHost.classList.remove('is-hidden');
        } else {
            try {
                specification = JSON.parse(rawTextarea.value || '{}');
            } catch (e) {
                errorHost.innerHTML = '';
                errorHost.appendChild(el('p', null, 'The JSON is malformed: ' + e.message));
                errorHost.classList.remove('is-hidden');
                return;
            }
            jsonHost.classList.add('is-hidden');
            formHost.classList.remove('is-hidden');
        }
        mode = target;
        document.querySelectorAll('.mode-switch [data-mode]').forEach(function (button) {
            button.classList.toggle('is-active', button.getAttribute('data-mode') === mode);
        });
        if (mode === 'form') {
            renderForm();
        }
        syncHidden();
    }

    /**
     * Instantiates the store and store group pickers declared in the markup, one by one.
     */
    function initTargetWidgets() {
        document.querySelectorAll('[data-widget]').forEach(buildTargetWidget);
    }

    /**
     * Builds one store or store group picker onto its host, from the host's attributes.
     *
     * @param {HTMLElement} host The declaring element, carrying data-widget, data-name and
     *                          the current comma-separated data-value.
     */
    function buildTargetWidget(host) {
        var widget = host.getAttribute('data-widget');
        var name = host.getAttribute('data-name');
        var raw = host.getAttribute('data-value') || '';
        var values = raw.split(',').map(function (v) { return v.trim(); })
                        .filter(function (v) { return v.length > 0; });

        var hidden = el('input');
        hidden.type = 'hidden';
        hidden.name = name;

        var ac = buildAutocomplete({
            endpoint: LOOKUP[widget],
            multiple: true,
            values: values,
            placeholder: 'Type a code or a name\u2026',
            onChange: function () { hidden.value = ac.getValues().join(','); }
        });
        hidden.value = values.join(',');
        host.appendChild(ac.node);
        host.appendChild(hidden);
    }

    /**
     * Applies a draft's store targeting to the pickers: the suggested codes merge into the
     * hosts' current values — the administrator's own picks survive — and the pickers
     * rebuild, chips included. Dispatched by the AI drafting panel through the
     * 'offer-targets:apply' event; a host or a list that is absent is simply skipped.
     */
    function applyDraftTargets(detail) {
        ['storeCodes', 'storeGroupCodes'].forEach(function (name) {
            var incoming = (detail && detail[name]) || [];
            if (!incoming.length) {
                return;
            }
            var host = document.querySelector("[data-name='" + name + "']");
            if (!host) {
                return;
            }
            var existing = (host.getAttribute('data-value') || '').split(',')
                .map(function (v) { return v.trim(); })
                .filter(function (v) { return v.length > 0; });
            incoming.forEach(function (code) {
                if (existing.indexOf(code) === -1) {
                    existing.push(code);
                }
            });
            host.setAttribute('data-value', existing.join(','));
            host.innerHTML = '';
            buildTargetWidget(host);
        });
    }

    /**
     * Boots the editor: reads the embedded schemas and specification, then renders.
     */
    function init() {
        try {
            schemas = JSON.parse(document.getElementById('offer-schemas').textContent || '{}');
        } catch (e) {
            schemas = {};
        }
        // Resolve the local #/definitions references once, so every field builder — this
        // one and the sub-builders — never sees a $ref node.
        Object.keys(schemas).forEach(function (type) {
            schemas[type] = dereference(schemas[type], schemas[type], []);
        });
        try {
            specification = JSON.parse(document.getElementById('offer-specification').textContent || '{}');
        } catch (e) {
            specification = {};
        }

        initTargetWidgets();

        // A draft's store targeting lands in the pickers through its own event; the
        // specification's own apply event is declared just below.
        document.addEventListener('offer-targets:apply', function (event) {
            applyDraftTargets(event.detail);
        });

        typeSelect.addEventListener('change', function () {
            // Keep the values already captured so switching type by mistake is not destructive.
            specification = mode === 'json' ? specification : readForm();
            renderForm();
        });

        // Applied by an external producer (the AI drafting assistant): a validated type and
        // specification land in the editor without a page reload. An unknown type is ignored,
        // so a caller can never force the form onto a schema-less value.
        document.addEventListener('offer-spec:apply', function (event) {
            var detail = event.detail || {};
            if (!detail.type || !schemas[detail.type]) {
                return;
            }
            typeSelect.value = detail.type;
            specification = detail.specification || {};
            if (mode === 'json') {
                rawTextarea.value = JSON.stringify(specification, null, 2);
            } else {
                renderForm();
            }
            syncHidden();
        });

        document.querySelectorAll('.mode-switch [data-mode]').forEach(function (button) {
            button.addEventListener('click', function () {
                switchMode(button.getAttribute('data-mode'));
            });
        });

        rawTextarea.addEventListener('input', syncHidden);
        document.getElementById('offer-form').addEventListener('submit', syncHidden);

        renderForm();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
