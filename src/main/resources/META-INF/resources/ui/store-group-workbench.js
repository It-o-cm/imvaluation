/*
 * Store group workbench.
 *
 * The whole hierarchy is held in memory and edited locally; nothing reaches the server
 * until the pending changes are saved. That is what makes a reorganisation reviewable:
 * several moves can be laid out, checked against each other, and discarded as a whole.
 *
 * Two ways to assign, deliberately: dragging is the fastest for one item, selection is
 * the only workable option for twenty. Neither uses a per-row button.
 */
(function () {
    'use strict';

    /**
     * The hierarchy being edited: groups keyed by code, plus the store catalog.
     */
    var model = { groups: [], stores: [] };

    /**
     * The state as loaded, used to detect pending changes and to discard them.
     */
    var pristine = null;

    /**
     * Whether the signed-in user may reorganise the hierarchy.
     */
    var canWrite = false;

    /**
     * Codes of the currently selected stores, in selection order.
     */
    var selection = [];

    /**
     * Code of the last store clicked, anchoring a shift-click range.
     */
    var anchor = null;

    /**
     * What is currently being dragged: either stores or a group.
     */
    var dragging = null;

    /**
     * Codes of the groups whose children are currently hidden.
     */
    var collapsed = {};

    // --------------------------------------------------
    // Model helpers
    // --------------------------------------------------

    /**
     * Finds a group by its code.
     *
     * @param {string} code The group code.
     * @returns {object|null} The group, or null when unknown.
     */
    function group(code) {
        for (var i = 0; i < model.groups.length; i++) {
            if (model.groups[i].code === code) {
                return model.groups[i];
            }
        }
        return null;
    }

    /**
     * Returns the groups that are nobody's child.
     *
     * @returns {Array} The root groups, sorted by code.
     */
    function roots() {
        var children = {};
        model.groups.forEach(function (g) {
            (g.childCodes || []).forEach(function (c) { children[c] = true; });
        });
        return model.groups.filter(function (g) { return !children[g.code]; })
                           .sort(function (a, b) { return a.code.localeCompare(b.code); });
    }

    /**
     * Indicates whether making a group the child of another would close a cycle.
     *
     * @param {string} parentCode The prospective parent.
     * @param {string} childCode The prospective child.
     * @returns {boolean} True when the link must be refused.
     */
    function wouldCycle(parentCode, childCode) {
        if (parentCode === childCode) { return true; }
        var seen = {};
        var stack = [childCode];
        while (stack.length) {
            var current = stack.pop();
            if (current === parentCode) { return true; }
            if (seen[current]) { continue; }
            seen[current] = true;
            var g = group(current);
            if (g) { stack.push.apply(stack, g.childCodes || []); }
        }
        return false;
    }

    /**
     * Indicates whether the model differs from the state loaded from the server.
     *
     * @returns {boolean} True when there is something to save.
     */
    function isDirty() {
        return JSON.stringify(model.groups) !== pristine;
    }

    // --------------------------------------------------
    // Mutations
    // --------------------------------------------------

    /**
     * Attaches stores to a group.
     *
     * @param {Array} storeCodes The stores to attach.
     * @param {string} groupCode The receiving group.
     */
    function assignStores(storeCodes, groupCode) {
        var target = group(groupCode);
        if (!target) { return; }
        storeCodes.forEach(function (code) {
            if (target.storeCodes.indexOf(code) === -1) {
                target.storeCodes.push(code);
            }
        });
        target.storeCodes.sort();
        render();
    }

    /**
     * Detaches a store from a group.
     *
     * @param {string} storeCode The store to detach.
     * @param {string} groupCode The group losing it.
     */
    function detachStore(storeCode, groupCode) {
        var target = group(groupCode);
        if (!target) { return; }
        target.storeCodes = target.storeCodes.filter(function (c) { return c !== storeCode; });
        render();
    }

    /**
     * Makes a group the child of another, or a root when no parent is given.
     *
     * @param {string} childCode The group being moved.
     * @param {string|null} parentCode The receiving group, null to detach.
     */
    function moveGroup(childCode, parentCode) {
        if (parentCode && wouldCycle(parentCode, childCode)) {
            showError('"' + parentCode + '" is already inside "' + childCode + '".');
            return;
        }
        // A group has one place in the tree at a time: detach before re-attaching.
        model.groups.forEach(function (g) {
            g.childCodes = (g.childCodes || []).filter(function (c) { return c !== childCode; });
        });
        if (parentCode) {
            var parent = group(parentCode);
            if (parent && parent.childCodes.indexOf(childCode) === -1) {
                parent.childCodes.push(childCode);
                parent.childCodes.sort();
            }
        }
        clearError();
        render();
    }

    /**
     * Creates a group at the root of the hierarchy.
     *
     * @param {string} code The code of the new group.
     */
    function createGroup(code) {
        var trimmed = code.trim();
        if (!trimmed) { return; }
        if (group(trimmed)) {
            showError('A group named "' + trimmed + '" already exists.');
            return;
        }
        model.groups.push({ code: trimmed, name: trimmed, storeCodes: [], childCodes: [] });
        clearError();
        render();
    }

    /**
     * Removes a group, releasing its members to the level above.
     *
     * @param {string} code The group to remove.
     */
    function removeGroup(code) {
        var removed = group(code);
        if (!removed) { return; }
        model.groups = model.groups.filter(function (g) { return g.code !== code; });
        model.groups.forEach(function (g) {
            g.childCodes = (g.childCodes || []).filter(function (c) { return c !== code; });
        });
        render();
    }

    // --------------------------------------------------
    // Rendering
    // --------------------------------------------------

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
     * Redraws the hierarchy and the store catalog.
     */
    function render() {
        renderTree();
        renderStores();
        renderPending();
    }

    /**
     * Redraws the hierarchy panel.
     */
    function renderTree() {
        var host = document.getElementById('wb-tree');
        host.innerHTML = '';
        var list = el('ul', 'wb-list');
        roots().forEach(function (g) { list.appendChild(groupNode(g, 0, {})); });
        host.appendChild(list);

        // Dropping on the empty area of the panel promotes a group to the root.
        makeDropTarget(host, null);
    }

    /**
     * Builds the node of one group and, recursively, its children.
     *
     * @param {object} g The group to render.
     * @param {number} depth The nesting level.
     * @param {object} seen Codes already rendered on this branch.
     * @returns {HTMLElement} The list item.
     */
    function groupNode(g, depth, seen) {
        var item = el('li', 'wb-group');
        var row = el('div', 'wb-group-row');
        row.style.paddingLeft = (depth * 20) + 'px';

        var hasChildren = (g.childCodes || []).length > 0;
        var toggle = el('button', 'wb-toggle');
        toggle.type = 'button';
        if (hasChildren) {
            toggle.textContent = collapsed[g.code] ? '\u25b8' : '\u25be';
            toggle.setAttribute('aria-label', collapsed[g.code] ? 'Expand' : 'Collapse');
            toggle.addEventListener('click', function (event) {
                event.stopPropagation();
                if (collapsed[g.code]) {
                    delete collapsed[g.code];
                } else {
                    collapsed[g.code] = true;
                }
                render();
            });
        } else {
            // Keep the alignment of leaf rows with the ones that have a toggle.
            toggle.classList.add('is-leaf');
            toggle.disabled = true;
        }
        row.appendChild(toggle);

        var name = el('span', 'wb-group-name', g.name || g.code);
        name.title = 'Double-click to rename';
        if (canWrite) {
            name.addEventListener('dblclick', function () { startRename(g, name); });
        }
        row.appendChild(name);
        row.appendChild(el('span', 'wb-group-code', g.code));

        var count = (g.storeCodes || []).length;
        if (count) {
            row.appendChild(el('span', 'wb-count', count + (count === 1 ? ' store' : ' stores')));
        }

        if (canWrite) {
            row.draggable = true;
            row.addEventListener('dragstart', function (event) {
                dragging = { kind: 'group', code: g.code };
                event.dataTransfer.effectAllowed = 'move';
                row.classList.add('is-dragging');
            });
            row.addEventListener('dragend', function () {
                dragging = null;
                row.classList.remove('is-dragging');
            });
            // Removing a group is a drag onto the trash strip, not a button.
            row.addEventListener('keydown', function (event) {
                if (event.key === 'Delete') { removeGroup(g.code); }
            });
            row.tabIndex = 0;
        }
        makeDropTarget(row, g.code);
        item.appendChild(row);

        var members = el('div', 'wb-members');
        members.style.marginLeft = ((depth * 20) + 16) + 'px';
        (g.storeCodes || []).forEach(function (code) {
            var store = storeByCode(code);
            var chip = el('span', 'chip chip-store wb-chip');
            chip.appendChild(el('span', null, code));
            if (store) {
                chip.appendChild(el('span', 'wb-chip-name', store.name));
            }
            if (canWrite) {
                chip.draggable = true;
                chip.title = 'Drag out to detach';
                chip.addEventListener('dragstart', function (event) {
                    dragging = { kind: 'stores', codes: [code], from: g.code };
                    event.dataTransfer.effectAllowed = 'move';
                });
                chip.addEventListener('dblclick', function () { detachStore(code, g.code); });
            }
            members.appendChild(chip);
        });
        if ((g.storeCodes || []).length) {
            item.appendChild(members);
        }

        if (seen[g.code]) {
            return item;
        }
        var branch = {};
        Object.keys(seen).forEach(function (k) { branch[k] = true; });
        branch[g.code] = true;

        if (!collapsed[g.code]) {
            (g.childCodes || []).forEach(function (childCode) {
                var child = group(childCode);
                if (child) {
                    item.appendChild(groupNode(child, depth + 1, branch));
                }
            });
        }
        return item;
    }

    /**
     * Finds a store by its code.
     *
     * @param {string} code The store code.
     * @returns {object|null} The store, or null when unknown.
     */
    function storeByCode(code) {
        for (var i = 0; i < model.stores.length; i++) {
            if (model.stores[i].code === code) { return model.stores[i]; }
        }
        return null;
    }

    /**
     * Redraws the store catalog, honouring the current filter.
     */
    function renderStores() {
        var host = document.getElementById('wb-stores');
        var filter = (document.getElementById('wb-store-search').value || '').toLowerCase();
        host.innerHTML = '';

        var assigned = {};
        model.groups.forEach(function (g) {
            (g.storeCodes || []).forEach(function (c) { assigned[c] = true; });
        });

        model.stores.forEach(function (store) {
            var haystack = (store.code + ' ' + store.name + ' ' + (store.city || '')).toLowerCase();
            if (filter && haystack.indexOf(filter) === -1) { return; }

            var row = el('div', 'wb-store');
            if (selection.indexOf(store.code) !== -1) { row.classList.add('is-selected'); }
            if (assigned[store.code]) { row.classList.add('is-assigned'); }

            row.appendChild(el('span', 'wb-store-code', store.code));
            row.appendChild(el('span', 'wb-store-name', store.name));
            if (store.city) {
                row.appendChild(el('span', 'wb-store-city', store.city));
            }

            row.addEventListener('click', function (event) { toggleSelection(store.code, event); });

            if (canWrite) {
                row.draggable = true;
                row.addEventListener('dragstart', function (event) {
                    // Dragging an unselected row carries that row alone; dragging a
                    // selected one carries the whole selection.
                    var codes = selection.indexOf(store.code) === -1 ? [store.code] : selection.slice();
                    dragging = { kind: 'stores', codes: codes, from: null };
                    event.dataTransfer.effectAllowed = 'copy';
                });
            }
            host.appendChild(row);
        });
    }

    /**
     * Updates the pending-changes indicator and the save controls.
     */
    function renderPending() {
        var dirty = isDirty();
        var badge = document.getElementById('wb-pending');
        var save = document.getElementById('wb-save');
        var revert = document.getElementById('wb-revert');
        if (badge) {
            badge.textContent = dirty ? 'Unsaved changes' : '';
            badge.classList.toggle('is-hidden', !dirty);
        }
        if (save) { save.disabled = !dirty; }
        if (revert) { revert.disabled = !dirty; }
    }

    // --------------------------------------------------
    // Interaction
    // --------------------------------------------------

    /**
     * Adds or removes a store from the selection.
     *
     * @param {string} code The store clicked.
     * @param {MouseEvent} event The originating event.
     */
    function toggleSelection(code, event) {
        if (event.shiftKey && anchor) {
            var visible = Array.prototype.map.call(
                document.querySelectorAll('#wb-stores .wb-store .wb-store-code'),
                function (n) { return n.textContent; });
            var from = visible.indexOf(anchor);
            var to = visible.indexOf(code);
            if (from !== -1 && to !== -1) {
                var slice = visible.slice(Math.min(from, to), Math.max(from, to) + 1);
                slice.forEach(function (c) {
                    if (selection.indexOf(c) === -1) { selection.push(c); }
                });
                renderStores();
                return;
            }
        }
        var index = selection.indexOf(code);
        if (index === -1) {
            selection.push(code);
        } else {
            selection.splice(index, 1);
        }
        anchor = code;
        renderStores();
    }

    /**
     * Makes an element accept drops.
     *
     * @param {HTMLElement} node The element receiving drops.
     * @param {string|null} groupCode The target group, null for the root area.
     */
    function makeDropTarget(node, groupCode) {
        if (!canWrite) { return; }
        node.addEventListener('dragover', function (event) {
            if (!dragging) { return; }
            event.preventDefault();
            node.classList.add('is-drop-target');
        });
        node.addEventListener('dragleave', function () {
            node.classList.remove('is-drop-target');
        });
        node.addEventListener('drop', function (event) {
            event.preventDefault();
            event.stopPropagation();
            node.classList.remove('is-drop-target');
            if (!dragging) { return; }
            if (dragging.kind === 'stores') {
                if (groupCode) {
                    if (dragging.from && dragging.from !== groupCode) {
                        dragging.codes.forEach(function (c) {
                            var source = group(dragging.from);
                            if (source) {
                                source.storeCodes = source.storeCodes.filter(function (x) { return x !== c; });
                            }
                        });
                    }
                    assignStores(dragging.codes, groupCode);
                    selection = [];
                } else if (dragging.from) {
                    // Dropped outside any group: detach from where it came from.
                    dragging.codes.forEach(function (c) { detachStore(c, dragging.from); });
                }
            } else if (dragging.kind === 'group') {
                moveGroup(dragging.code, groupCode);
            }
            // Reveal the result: a drop onto a collapsed group would otherwise vanish.
            if (groupCode) {
                delete collapsed[groupCode];
            }
            dragging = null;
        });
    }

    /**
     * Turns a group name into an input for renaming.
     *
     * @param {object} g The group being renamed.
     * @param {HTMLElement} node The element displaying the name.
     */
    function startRename(g, node) {
        var input = el('input', 'wb-rename');
        input.type = 'text';
        input.value = g.name || g.code;
        input.maxLength = 100;
        node.replaceWith(input);
        input.focus();
        input.select();

        function commit() {
            g.name = input.value.trim() || g.code;
            render();
        }
        input.addEventListener('blur', commit);
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') { input.blur(); }
            if (event.key === 'Escape') { render(); }
        });
    }

    // --------------------------------------------------
    // Persistence
    // --------------------------------------------------

    /**
     * Displays an error next to the save controls.
     *
     * @param {string} message The message to display.
     */
    function showError(message) {
        var box = document.getElementById('wb-error');
        box.textContent = message;
        box.classList.remove('is-hidden');
    }

    /**
     * Hides the error message.
     */
    function clearError() {
        document.getElementById('wb-error').classList.add('is-hidden');
    }

    /**
     * Submits the whole hierarchy.
     */
    function save() {
        clearError();
        var button = document.getElementById('wb-save');
        button.disabled = true;
        fetch('/ui/store-groups', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({ groups: model.groups })
        }).then(function (response) {
            return response.json().then(function (body) {
                return { ok: response.ok, body: body };
            });
        }).then(function (result) {
            if (result.ok) {
                // The saved state becomes the new reference, so the indicator clears.
                pristine = JSON.stringify(model.groups);
                renderPending();
            } else {
                showError(result.body.error || 'The hierarchy could not be saved.');
                renderPending();
            }
        }).catch(function () {
            showError('The hierarchy could not be saved.');
            renderPending();
        });
    }

    /**
     * Restores the state as loaded, discarding every pending change.
     */
    function revert() {
        model.groups = JSON.parse(pristine);
        selection = [];
        clearError();
        render();
    }

    // --------------------------------------------------
    // Boot
    // --------------------------------------------------

    /**
     * Reads the embedded model and wires the screen.
     */
    function init() {
        try {
            model = JSON.parse(document.getElementById('wb-model').textContent);
        } catch (e) {
            model = { groups: [], stores: [] };
        }
        try {
            canWrite = JSON.parse(document.getElementById('wb-can-write').textContent) === true;
        } catch (e) {
            canWrite = false;
        }
        model.groups.forEach(function (g) {
            g.storeCodes = g.storeCodes || [];
            g.childCodes = g.childCodes || [];
        });
        pristine = JSON.stringify(model.groups);

        document.getElementById('wb-store-search').addEventListener('input', renderStores);

        var creator = document.getElementById('wb-new-group');
        if (creator) {
            creator.addEventListener('keydown', function (event) {
                if (event.key === 'Enter') {
                    event.preventDefault();
                    createGroup(creator.value);
                    creator.value = '';
                }
            });
        }

        var save_ = document.getElementById('wb-save');
        if (save_) { save_.addEventListener('click', save); }
        var revert_ = document.getElementById('wb-revert');
        if (revert_) { revert_.addEventListener('click', revert); }

        // Leaving with unsaved work is almost always a mistake here: a reorganisation
        // represents several deliberate moves.
        window.addEventListener('beforeunload', function (event) {
            if (isDirty()) {
                event.preventDefault();
                event.returnValue = '';
            }
        });

        render();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
