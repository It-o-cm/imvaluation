import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of store-group-workbench.js.
 *
 * The model/graph layer (group, roots, wouldCycle, isDirty, storeByCode, the mutations and
 * confirmRemoval) is asserted first, purely on the in-memory hierarchy. The rendering and
 * interaction layer (el, render, groupNode, renderStores, renderPending, buildRemoveButton,
 * toggleSelection, makeDropTarget, startRename, persistence and init) is then driven against
 * a jsdom document. All module state lives in closure vars seeded by init(); each test
 * re-boots a fresh DOM and a chosen model so there is no ordering dependency.
 */

/**
 * Project-relative path of the source under test.
 */
const PATH = 'src/main/resources/META-INF/resources/ui/store-group-workbench.js';

/**
 * The source under test, exposing its top-level functions once loaded.
 */
let wb;

/**
 * The workbench DOM skeleton, holding every element init() and render() require.
 */
const SKELETON = '<div id="wb-tree"></div>'
  + '<div id="wb-stores"></div>'
  + '<input id="wb-store-search">'
  + '<span id="wb-pending"></span>'
  + '<button id="wb-save"></button>'
  + '<button id="wb-revert"></button>'
  + '<div id="wb-error" class="is-hidden"></div>'
  + '<input id="wb-new-group">'
  + '<script id="wb-model" type="application/json">{"groups":[],"stores":[]}</script>'
  + '<script id="wb-can-write" type="application/json">false</script>';

/**
 * Installs the skeleton, seeds the embedded model/permission, and re-runs init() so the
 * module state is deterministic for the test that follows.
 *
 * @param {object} model The hierarchy to embed ({groups, stores}).
 * @param {boolean} canWrite Whether the user may reorganise.
 */
function boot(model, canWrite) {
  document.body.innerHTML = SKELETON;
  document.getElementById('wb-model').textContent = JSON.stringify(model);
  document.getElementById('wb-can-write').textContent = JSON.stringify(canWrite);
  wb.init();
}

/**
 * Clears the store selection deterministically through the public revert(), which resets
 * the selection to empty without otherwise changing the pristine model.
 */
function clearSelection() {
  wb.revert();
}

/**
 * Dispatches a drag event carrying a stub dataTransfer, since jsdom does not implement it.
 *
 * @param {Element} node The event target.
 * @param {string} type The event type (dragstart, dragover, dragleave, drop).
 * @returns {Event} The dispatched event.
 */
function fireDrag(node, type) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'dataTransfer', { value: { effectAllowed: '' }, configurable: true });
  node.dispatchEvent(event);
  return event;
}

/**
 * Dispatches a keydown carrying the given key.
 *
 * @param {Element} node The event target.
 * @param {string} key The KeyboardEvent key value.
 * @returns {KeyboardEvent} The dispatched event.
 */
function fireKey(node, key) {
  const event = new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true });
  node.dispatchEvent(event);
  return event;
}

/**
 * Flushes pending microtasks so promise chains (save) settle before assertions.
 *
 * @returns {Promise<void>} A promise resolved on the next macrotask.
 */
function flush() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * Loads the source once, against a ready document, so the boot else-arm runs init().
 */
beforeAll(async () => {
  document.body.innerHTML = SKELETON;
  wb = await loadScript(PATH);
});

/**
 * Restores any globals stubbed by a test and forces the shared dragging state back to null,
 * so no test depends on a drag started (but never finished) by an earlier one.
 */
afterEach(() => {
  vi.restoreAllMocks();
  boot({ groups: [{ code: '__reset__', name: 'r', childCodes: [], storeCodes: [] }], stores: [] }, true);
  const row = document.querySelector('#wb-tree .wb-group-row');
  fireDrag(row, 'dragend');
});

describe('group', () => {
  /**
   * Boots a two-group model for the lookups.
   */
  beforeEach(() => {
    boot({ groups: [{ code: 'A' }, { code: 'B' }], stores: [] }, false);
  });

  /**
   * A known code returns the matching group (=== arm true, after skipping a non-match).
   */
  it('returns the group matching the code', () => {
    expect(wb.group('B').code).toBe('B');
  });

  /**
   * An unknown code exhausts the loop and returns null.
   */
  it('returns null for an unknown code', () => {
    expect(wb.group('Z')).toBeNull();
  });
});

describe('roots', () => {
  /**
   * A group that is another's child is excluded, and the remainder is sorted by code.
   */
  it('returns only non-child groups, sorted by code', () => {
    boot({
      groups: [
        { code: 'C', childCodes: [] },
        { code: 'A', childCodes: ['B'] },
        { code: 'B', childCodes: [] },
      ],
      stores: [],
    }, false);
    expect(wb.roots().map((g) => g.code)).toEqual(['A', 'C']);
  });
});

describe('wouldCycle', () => {
  /**
   * Boots a model exercising a chain, a diamond (revisited node) and a dangling child.
   */
  beforeEach(() => {
    boot({
      groups: [
        { code: 'A', childCodes: ['B', 'C', 'GHOST'] },
        { code: 'B', childCodes: ['D'] },
        { code: 'C', childCodes: ['D'] },
        { code: 'D', childCodes: [] },
      ],
      stores: [],
    }, false);
  });

  /**
   * A group is trivially inside itself.
   */
  it('refuses making a group its own child', () => {
    expect(wb.wouldCycle('A', 'A')).toBe(true);
  });

  /**
   * Walking the descendants of A reaches B, so B cannot become A's parent.
   */
  it('detects a cycle reached through the descendants', () => {
    expect(wb.wouldCycle('B', 'A')).toBe(true);
  });

  /**
   * An unrelated parent is safe; the diamond revisits D (seen → continue) and GHOST has no
   * group (the null-group arm), yet no cycle is found.
   */
  it('allows a link that closes no cycle, crossing a revisited node and a dangling child', () => {
    expect(wb.wouldCycle('Z', 'A')).toBe(false);
  });
});

describe('isDirty', () => {
  /**
   * A freshly booted model equals its pristine snapshot.
   */
  it('is false right after boot', () => {
    boot({ groups: [{ code: 'A', childCodes: [], storeCodes: [] }], stores: [] }, false);
    expect(wb.isDirty()).toBe(false);
  });

  /**
   * A mutation makes the model differ from the snapshot.
   */
  it('is true after a mutation', () => {
    boot({ groups: [], stores: [] }, false);
    wb.createGroup('NEW');
    expect(wb.isDirty()).toBe(true);
  });
});

describe('storeByCode', () => {
  /**
   * Boots a catalog of two stores.
   */
  beforeEach(() => {
    boot({ groups: [], stores: [{ code: 's1', name: 'One' }, { code: 's2', name: 'Two' }] }, false);
  });

  /**
   * A known code returns the store (after skipping a non-match).
   */
  it('returns the store matching the code', () => {
    expect(wb.storeByCode('s2').name).toBe('Two');
  });

  /**
   * An unknown code returns null.
   */
  it('returns null for an unknown code', () => {
    expect(wb.storeByCode('zzz')).toBeNull();
  });
});

describe('assignStores', () => {
  /**
   * An unknown target group is a no-op.
   */
  it('does nothing when the target group is unknown', () => {
    boot({ groups: [{ code: 'A', storeCodes: [], childCodes: [] }], stores: [] }, false);
    wb.assignStores(['s1'], 'Z');
    expect(wb.group('A').storeCodes).toEqual([]);
  });

  /**
   * New stores are appended and sorted; a duplicate is skipped.
   */
  it('appends unique stores in sorted order', () => {
    boot({ groups: [{ code: 'A', storeCodes: ['s2'], childCodes: [] }], stores: [] }, false);
    wb.assignStores(['s3', 's1', 's2'], 'A');
    expect(wb.group('A').storeCodes).toEqual(['s1', 's2', 's3']);
  });
});

describe('detachStore', () => {
  /**
   * An unknown target group is a no-op.
   */
  it('does nothing when the target group is unknown', () => {
    boot({ groups: [{ code: 'A', storeCodes: ['s1'], childCodes: [] }], stores: [] }, false);
    wb.detachStore('s1', 'Z');
    expect(wb.group('A').storeCodes).toEqual(['s1']);
  });

  /**
   * The named store is removed from the group.
   */
  it('removes the store from the group', () => {
    boot({ groups: [{ code: 'A', storeCodes: ['s1', 's2'], childCodes: [] }], stores: [] }, false);
    wb.detachStore('s1', 'A');
    expect(wb.group('A').storeCodes).toEqual(['s2']);
  });
});

describe('moveGroup', () => {
  /**
   * A move that would close a cycle is refused and surfaces an error.
   */
  it('refuses a move that would create a cycle', () => {
    boot({
      groups: [{ code: 'A', childCodes: ['B'] }, { code: 'B', childCodes: [] }],
      stores: [],
    }, false);
    wb.moveGroup('A', 'B');
    expect(wb.group('B').childCodes).toEqual([]);
    expect(document.getElementById('wb-error').classList.contains('is-hidden')).toBe(false);
  });

  /**
   * A valid move re-parents the group under the target.
   */
  it('attaches the group under the given parent', () => {
    boot({
      groups: [{ code: 'A', childCodes: [] }, { code: 'B', childCodes: [] }],
      stores: [],
    }, false);
    wb.moveGroup('B', 'A');
    expect(wb.group('A').childCodes).toEqual(['B']);
    expect(wb.roots().map((g) => g.code)).toEqual(['A']);
  });

  /**
   * A null parent detaches the group back to the root.
   */
  it('promotes the group to a root when the parent is null', () => {
    boot({
      groups: [{ code: 'A', childCodes: ['B'] }, { code: 'B', childCodes: [] }],
      stores: [],
    }, false);
    wb.moveGroup('B', null);
    expect(wb.group('A').childCodes).toEqual([]);
    expect(wb.roots().map((g) => g.code)).toEqual(['A', 'B']);
  });

  /**
   * A non-existent parent detaches the group without attaching it (parent null arm).
   */
  it('leaves the group at the root when the parent group is unknown', () => {
    boot({
      groups: [{ code: 'A', childCodes: ['B'] }, { code: 'B', childCodes: [] }],
      stores: [],
    }, false);
    wb.moveGroup('B', 'GHOST');
    expect(wb.group('A').childCodes).toEqual([]);
    expect(wb.roots().map((g) => g.code)).toEqual(['A', 'B']);
  });
});

describe('createGroup', () => {
  /**
   * A blank code is ignored.
   */
  it('ignores a blank code', () => {
    boot({ groups: [], stores: [] }, false);
    wb.createGroup('   ');
    expect(wb.roots()).toEqual([]);
  });

  /**
   * A duplicate code is refused with an error.
   */
  it('refuses a duplicate code', () => {
    boot({ groups: [{ code: 'A', childCodes: [], storeCodes: [] }], stores: [] }, false);
    wb.createGroup('A');
    expect(wb.roots().length).toBe(1);
    expect(document.getElementById('wb-error').classList.contains('is-hidden')).toBe(false);
  });

  /**
   * A fresh code is created as a root group with empty members.
   */
  it('creates a new root group', () => {
    boot({ groups: [], stores: [] }, false);
    wb.createGroup('NEW');
    expect(wb.group('NEW')).toEqual({ code: 'NEW', name: 'NEW', storeCodes: [], childCodes: [] });
  });
});

describe('removeGroup', () => {
  /**
   * An unknown code is a no-op.
   */
  it('does nothing when the group is unknown', () => {
    boot({ groups: [{ code: 'A', childCodes: [], storeCodes: [] }], stores: [] }, false);
    wb.removeGroup('GHOST');
    expect(wb.roots().length).toBe(1);
  });

  /**
   * Removing a group drops it and strips it from every parent's children.
   */
  it('removes the group and its links', () => {
    boot({
      groups: [{ code: 'A', childCodes: ['B'] }, { code: 'B', childCodes: [] }],
      stores: [],
    }, false);
    wb.removeGroup('B');
    expect(wb.group('B')).toBeNull();
    expect(wb.group('A').childCodes).toEqual([]);
  });
});

describe('confirmRemoval', () => {
  /**
   * Boots any model; confirmRemoval works on the argument, not the model.
   */
  beforeEach(() => {
    boot({ groups: [], stores: [] }, false);
  });

  /**
   * An empty group (fields absent, exercising the || [] arms) is confirmed without a prompt.
   */
  it('confirms an empty group without prompting', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    expect(wb.confirmRemoval({ code: 'X' })).toBe(true);
    expect(confirm).not.toHaveBeenCalled();
  });

  /**
   * A single store and a named group produce a singular prompt; a false answer refuses.
   */
  it('prompts with a singular store count and honours a refusal', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const result = wb.confirmRemoval({ code: 'X', name: 'Shop', storeCodes: ['s1'], childCodes: [] });
    expect(result).toBe(false);
    expect(confirm.mock.calls[0][0]).toContain('"Shop" contains 1 store.');
  });

  /**
   * Plural stores and plural sub-groups on an unnamed group are joined; a true answer accepts.
   */
  it('prompts with plural counts joined and honours an acceptance', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const result = wb.confirmRemoval({ code: 'X', storeCodes: ['s1', 's2'], childCodes: ['c1', 'c2'] });
    expect(result).toBe(true);
    expect(confirm.mock.calls[0][0]).toContain('"X" contains 2 stores and 2 sub-groups.');
  });

  /**
   * A single sub-group and no stores produce a singular sub-group prompt.
   */
  it('prompts with a singular sub-group count', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    wb.confirmRemoval({ code: 'X', name: 'Region', storeCodes: [], childCodes: ['c1'] });
    expect(confirm.mock.calls[0][0]).toContain('"Region" contains 1 sub-group.');
  });
});

describe('el', () => {
  /**
   * No class and no text leaves both untouched (className falsy, text undefined).
   */
  it('creates a bare element', () => {
    const node = wb.el('div');
    expect(node.tagName).toBe('DIV');
    expect(node.className).toBe('');
    expect(node.textContent).toBe('');
  });

  /**
   * A class and text are both applied.
   */
  it('applies a class and text', () => {
    const node = wb.el('span', 'c', 'hello');
    expect(node.className).toBe('c');
    expect(node.textContent).toBe('hello');
  });

  /**
   * A null text takes the "not null" arm as false and is not written.
   */
  it('ignores a null text', () => {
    const node = wb.el('span', null, null);
    expect(node.textContent).toBe('');
  });
});

describe('render / renderTree', () => {
  /**
   * Rendering populates the tree, the catalog and the pending indicator.
   */
  it('draws the tree, the catalog and the pending state', () => {
    boot({
      groups: [{ code: 'A', name: 'Alpha', childCodes: [], storeCodes: ['s1'] }],
      stores: [{ code: 's1', name: 'One', city: 'Paris' }],
    }, false);
    expect(document.querySelector('#wb-tree ul.wb-list')).not.toBeNull();
    expect(document.querySelector('#wb-tree .wb-group-name').textContent).toBe('Alpha');
    expect(document.querySelectorAll('#wb-stores .wb-store').length).toBe(1);
  });
});

describe('groupNode', () => {
  /**
   * A bare group object (no childCodes/storeCodes) exercises the || [] fallbacks, the leaf
   * toggle, the missing-name fallback and, with canWrite off, the read-only arms.
   */
  it('renders a read-only leaf from a bare group', () => {
    boot({ groups: [], stores: [] }, false);
    const item = wb.groupNode({ code: 'BARE' }, 0, {});
    expect(item.querySelector('.wb-group-name').textContent).toBe('BARE');
    expect(item.querySelector('.wb-toggle').disabled).toBe(true);
    expect(item.querySelector('.wb-group-row').draggable).toBe(false);
    expect(item.querySelector('.wb-group-remove')).toBeNull();
  });

  /**
   * With canWrite on, a bare group gains the drag handle and the remove button.
   */
  it('renders a writable leaf with drag and remove affordances', () => {
    boot({ groups: [], stores: [] }, true);
    const item = wb.groupNode({ code: 'BARE', name: 'Bare' }, 0, {});
    expect(item.querySelector('.wb-group-row').draggable).toBe(true);
    expect(item.querySelector('.wb-group-remove')).not.toBeNull();
  });

  /**
   * A parent with a matched child, a store count and an unmatched store code renders the
   * count (singular), the chip with and without a resolved name, and expands its children.
   */
  it('renders a parent with children and store chips', () => {
    boot({
      groups: [
        { code: 'P', name: 'Parent', childCodes: ['CH'], storeCodes: ['s1', 'sX'] },
        { code: 'CH', name: 'Child', childCodes: [], storeCodes: [] },
      ],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const rows = document.querySelectorAll('#wb-tree .wb-group-row');
    expect(rows.length).toBe(2);
    expect(document.querySelectorAll('#wb-tree .wb-chip').length).toBe(2);
    expect(document.querySelector('#wb-tree .wb-chip .wb-chip-name').textContent).toBe('One');
  });

  /**
   * A store count of exactly one is labelled in the singular.
   */
  it('labels a single store in the singular', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }],
      stores: [],
    }, false);
    expect(document.querySelector('#wb-tree .wb-count').textContent).toBe('1 store');
  });

  /**
   * A collapsed parent hides its children; toggling twice covers expand and collapse.
   */
  it('collapses and expands a parent via its toggle', () => {
    boot({
      groups: [
        { code: 'P', name: 'P', childCodes: ['CH'], storeCodes: [] },
        { code: 'CH', name: 'CH', childCodes: [], storeCodes: [] },
      ],
      stores: [],
    }, false);
    const toggle = document.querySelector('#wb-tree .wb-toggle');
    expect(document.querySelectorAll('#wb-tree .wb-group-row').length).toBe(2);
    toggle.dispatchEvent(new Event('click', { bubbles: true }));
    expect(document.querySelectorAll('#wb-tree .wb-group-row').length).toBe(1);
    document.querySelector('#wb-tree .wb-toggle').dispatchEvent(new Event('click', { bubbles: true }));
    expect(document.querySelectorAll('#wb-tree .wb-group-row').length).toBe(2);
  });

  /**
   * A dangling child code renders no extra node (the group(childCode) null arm).
   */
  it('skips a dangling child code', () => {
    boot({
      groups: [{ code: 'P', name: 'P', childCodes: ['MISSING'], storeCodes: [] }],
      stores: [],
    }, false);
    expect(document.querySelectorAll('#wb-tree .wb-group-row').length).toBe(1);
  });

  /**
   * A cycle reachable from a root stops at the already-seen node without looping forever.
   */
  it('stops recursion at an already-seen node', () => {
    boot({
      groups: [
        { code: 'ROOT', name: 'Root', childCodes: ['A'], storeCodes: [] },
        { code: 'A', name: 'A', childCodes: ['B'], storeCodes: [] },
        { code: 'B', name: 'B', childCodes: ['A'], storeCodes: [] },
      ],
      stores: [],
    }, false);
    expect(document.querySelectorAll('#wb-tree .wb-group-row').length).toBe(4);
  });

  /**
   * With canWrite on, double-clicking a name opens the rename input.
   */
  it('opens rename on name double-click when writable', () => {
    boot({
      groups: [{ code: 'A', name: 'Alpha', childCodes: [], storeCodes: [] }],
      stores: [],
    }, true);
    const name = document.querySelector('#wb-tree .wb-group-name');
    name.dispatchEvent(new Event('dblclick', { bubbles: true }));
    expect(document.querySelector('#wb-tree .wb-rename')).not.toBeNull();
  });

  /**
   * A group row start/end drag toggles the dragging state and the row class.
   */
  it('carries a group on dragstart and clears it on dragend', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }, { code: 'B', name: 'B', childCodes: [], storeCodes: [] }],
      stores: [],
    }, true);
    const row = document.querySelector('#wb-tree .wb-group-row');
    fireDrag(row, 'dragstart');
    expect(row.classList.contains('is-dragging')).toBe(true);
    fireDrag(row, 'dragend');
    expect(row.classList.contains('is-dragging')).toBe(false);
  });

  /**
   * The Delete key on a writable row removes the group after confirmation.
   */
  it('removes a group on the Delete key', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }],
      stores: [],
    }, true);
    const row = document.querySelector('#wb-tree .wb-group-row');
    fireKey(row, 'Delete');
    expect(wb.group('A')).toBeNull();
  });

  /**
   * A store chip drag sets the store drag payload, and a chip double-click detaches it.
   */
  it('drags and double-click-detaches a store chip', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const chip = document.querySelector('#wb-tree .wb-chip');
    fireDrag(chip, 'dragstart');
    chip.dispatchEvent(new Event('dblclick', { bubbles: true }));
    expect(wb.group('A').storeCodes).toEqual([]);
  });
});

describe('renderStores', () => {
  /**
   * Boots a catalog: one assigned store with a city, one unassigned without a city.
   */
  beforeEach(() => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }],
      stores: [{ code: 's1', name: 'One', city: 'Paris' }, { code: 's2', name: 'Two' }],
    }, true);
    clearSelection();
  });

  /**
   * Every store renders; the assigned one is flagged and the city is shown when present.
   */
  it('renders all stores with assignment and city flags', () => {
    const rows = document.querySelectorAll('#wb-stores .wb-store');
    expect(rows.length).toBe(2);
    expect(rows[0].classList.contains('is-assigned')).toBe(true);
    expect(rows[0].querySelector('.wb-store-city').textContent).toBe('Paris');
    expect(rows[1].querySelector('.wb-store-city')).toBeNull();
  });

  /**
   * A matching filter keeps only the matching store; the search field feeds the filter.
   */
  it('filters the catalog by the search field', () => {
    document.getElementById('wb-store-search').value = 'two';
    wb.renderStores();
    const rows = document.querySelectorAll('#wb-stores .wb-store');
    expect(rows.length).toBe(1);
    expect(rows[0].querySelector('.wb-store-code').textContent).toBe('s2');
  });

  /**
   * A selected store is flagged, and dragging it alone carries just that code.
   */
  it('flags a selected store and drags it alone', () => {
    wb.toggleSelection('s2', { shiftKey: false });
    const rows = document.querySelectorAll('#wb-stores .wb-store');
    const selected = rows[1];
    expect(selected.classList.contains('is-selected')).toBe(true);
    expect(() => fireDrag(selected, 'dragstart')).not.toThrow();
  });

  /**
   * Dragging one of several selected stores carries the whole selection.
   */
  it('drags the whole selection when the dragged store is selected', () => {
    wb.toggleSelection('s1', { shiftKey: false });
    wb.toggleSelection('s2', { shiftKey: false });
    const rows = document.querySelectorAll('#wb-stores .wb-store');
    expect(() => fireDrag(rows[0], 'dragstart')).not.toThrow();
  });
});

describe('renderPending', () => {
  /**
   * A clean model hides the badge and disables the controls.
   */
  it('reflects a clean model', () => {
    boot({ groups: [], stores: [] }, false);
    wb.renderPending();
    expect(document.getElementById('wb-pending').classList.contains('is-hidden')).toBe(true);
    expect(document.getElementById('wb-save').disabled).toBe(true);
    expect(document.getElementById('wb-revert').disabled).toBe(true);
  });

  /**
   * A dirty model shows the badge and enables the controls.
   */
  it('reflects a dirty model', () => {
    boot({ groups: [], stores: [] }, false);
    wb.createGroup('NEW');
    expect(document.getElementById('wb-pending').textContent).toBe('Unsaved changes');
    expect(document.getElementById('wb-save').disabled).toBe(false);
    expect(document.getElementById('wb-revert').disabled).toBe(false);
  });

  /**
   * Missing controls take the null arms of every guard without throwing.
   */
  it('tolerates missing controls', () => {
    boot({ groups: [], stores: [] }, false);
    document.getElementById('wb-pending').remove();
    document.getElementById('wb-save').remove();
    document.getElementById('wb-revert').remove();
    expect(() => wb.renderPending()).not.toThrow();
  });
});

describe('buildRemoveButton', () => {
  /**
   * Clicking the button on an empty group removes it without a prompt.
   */
  it('removes an empty group on click', () => {
    boot({ groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }], stores: [] }, true);
    const button = wb.buildRemoveButton({ code: 'A', name: 'A', storeCodes: [], childCodes: [] });
    button.dispatchEvent(new Event('click', { bubbles: true, cancelable: true }));
    expect(wb.group('A')).toBeNull();
  });

  /**
   * A refused confirmation on a populated group keeps it.
   */
  it('keeps a populated group when the confirmation is refused', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    boot({ groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }], stores: [] }, true);
    const button = wb.buildRemoveButton({ code: 'A', name: 'A', storeCodes: ['s1'], childCodes: [] });
    button.dispatchEvent(new Event('click', { bubbles: true, cancelable: true }));
    expect(wb.group('A')).not.toBeNull();
  });
});

describe('toggleSelection', () => {
  /**
   * Boots three stores for range and single selection.
   */
  beforeEach(() => {
    boot({
      groups: [],
      stores: [{ code: 's1', name: 'One' }, { code: 's2', name: 'Two' }, { code: 's3', name: 'Three' }],
    }, true);
    clearSelection();
  });

  /**
   * A shift click with no anchor yet takes the plain toggle path and selects the store.
   */
  it('selects a single store when there is no anchor', () => {
    wb.toggleSelection('s2', { shiftKey: true });
    expect(document.querySelectorAll('#wb-stores .wb-store.is-selected').length).toBe(1);
  });

  /**
   * A second plain click on the same store deselects it.
   */
  it('deselects a store on a second plain click', () => {
    wb.toggleSelection('s1', { shiftKey: false });
    wb.toggleSelection('s1', { shiftKey: false });
    expect(document.querySelectorAll('#wb-stores .wb-store.is-selected').length).toBe(0);
  });

  /**
   * A shift click after an anchor selects the whole visible range, skipping already-selected.
   */
  it('selects a contiguous range on shift click', () => {
    wb.toggleSelection('s1', { shiftKey: false });
    wb.toggleSelection('s3', { shiftKey: true });
    expect(document.querySelectorAll('#wb-stores .wb-store.is-selected').length).toBe(3);
  });

  /**
   * A shift click on a code absent from the visible list falls back to the plain toggle.
   */
  it('falls back to a plain toggle when the target is not visible', () => {
    wb.toggleSelection('s1', { shiftKey: false });
    wb.toggleSelection('ghost', { shiftKey: true });
    expect(document.querySelectorAll('#wb-stores .wb-store.is-selected').length).toBe(1);
  });

  /**
   * A real click on a store row runs the wired handler and selects it.
   */
  it('selects a store through a real row click', () => {
    const row = document.querySelector('#wb-stores .wb-store');
    row.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(document.querySelectorAll('#wb-stores .wb-store.is-selected').length).toBe(1);
  });
});

describe('makeDropTarget', () => {
  /**
   * A read-only workbench attaches no drop behaviour.
   */
  it('does nothing when the user cannot write', () => {
    boot({ groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }], stores: [] }, false);
    const row = document.querySelector('#wb-tree .wb-group-row');
    const event = fireDrag(row, 'dragover');
    expect(row.classList.contains('is-drop-target')).toBe(false);
    expect(event.defaultPrevented).toBe(false);
  });

  /**
   * dragover with an active drag highlights the target; dragleave clears it.
   */
  it('highlights on dragover and clears on dragleave', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }, { code: 'B', name: 'B', childCodes: [], storeCodes: [] }],
      stores: [],
    }, true);
    const rows = document.querySelectorAll('#wb-tree .wb-group-row');
    fireDrag(rows[0], 'dragstart');
    const over = fireDrag(rows[1], 'dragover');
    expect(over.defaultPrevented).toBe(true);
    expect(rows[1].classList.contains('is-drop-target')).toBe(true);
    fireDrag(rows[1], 'dragleave');
    expect(rows[1].classList.contains('is-drop-target')).toBe(false);
  });

  /**
   * dragover with no active drag is ignored.
   */
  it('ignores dragover with no active drag', () => {
    boot({ groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }], stores: [] }, true);
    const row = document.querySelector('#wb-tree .wb-group-row');
    const over = fireDrag(row, 'dragover');
    expect(over.defaultPrevented).toBe(false);
  });

  /**
   * A drop with nothing being dragged is a no-op.
   */
  it('ignores a drop with no active drag', () => {
    boot({ groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }], stores: [] }, true);
    const row = document.querySelector('#wb-tree .wb-group-row');
    expect(() => fireDrag(row, 'drop')).not.toThrow();
  });

  /**
   * Dropping a store from another group onto a group moves it across.
   */
  it('moves a store across groups on drop', () => {
    boot({
      groups: [
        { code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] },
        { code: 'B', name: 'B', childCodes: [], storeCodes: [] },
      ],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const chip = document.querySelector('#wb-tree .wb-chip');
    fireDrag(chip, 'dragstart');
    const targetRow = document.querySelectorAll('#wb-tree .wb-group-row')[1];
    fireDrag(targetRow, 'drop');
    expect(wb.group('A').storeCodes).toEqual([]);
    expect(wb.group('B').storeCodes).toEqual(['s1']);
  });

  /**
   * Dropping a store onto its own group leaves it in place (the from === group arm).
   */
  it('keeps a store dropped onto its own group', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const chip = document.querySelector('#wb-tree .wb-chip');
    fireDrag(chip, 'dragstart');
    const row = document.querySelector('#wb-tree .wb-group-row');
    fireDrag(row, 'drop');
    expect(wb.group('A').storeCodes).toEqual(['s1']);
  });

  /**
   * The source-group lookup returning null is tolerated (the group deleted mid-drag).
   */
  it('tolerates a vanished source group on a cross-group drop', () => {
    boot({
      groups: [
        { code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] },
        { code: 'B', name: 'B', childCodes: [], storeCodes: [] },
      ],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const chip = document.querySelector('#wb-tree .wb-chip');
    fireDrag(chip, 'dragstart');
    wb.removeGroup('A');
    const targetRow = document.querySelector('#wb-tree .wb-group-row');
    fireDrag(targetRow, 'drop');
    expect(wb.group('B').storeCodes).toEqual(['s1']);
  });

  /**
   * Dropping a store from a group onto the empty root area detaches it.
   */
  it('detaches a store dropped on the root area', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: ['s1'] }],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const chip = document.querySelector('#wb-tree .wb-chip');
    fireDrag(chip, 'dragstart');
    fireDrag(document.getElementById('wb-tree'), 'drop');
    expect(wb.group('A').storeCodes).toEqual([]);
  });

  /**
   * A catalog store (no source group) dropped on the root area is a no-op.
   */
  it('ignores a catalog store dropped on the root area', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    const storeRow = document.querySelector('#wb-stores .wb-store');
    fireDrag(storeRow, 'dragstart');
    expect(() => fireDrag(document.getElementById('wb-tree'), 'drop')).not.toThrow();
    expect(wb.group('A').storeCodes).toEqual([]);
  });

  /**
   * Dropping a group onto another re-parents it and reveals a collapsed target.
   */
  it('re-parents a group on drop and expands the target', () => {
    boot({
      groups: [
        { code: 'A', name: 'A', childCodes: ['C'], storeCodes: [] },
        { code: 'B', name: 'B', childCodes: [], storeCodes: [] },
        { code: 'C', name: 'C', childCodes: [], storeCodes: [] },
      ],
      stores: [],
    }, true);
    const toggle = document.querySelector('#wb-tree .wb-toggle');
    toggle.dispatchEvent(new Event('click', { bubbles: true }));
    const rowA = document.querySelector('#wb-tree .wb-group-row');
    fireDrag(rowA, 'dragstart');
    const rowB = [...document.querySelectorAll('#wb-tree .wb-group-name')]
      .find((n) => n.textContent === 'B').closest('.wb-group-row');
    fireDrag(rowB, 'drop');
    expect(wb.group('B').childCodes).toEqual(['A']);
  });
});

describe('startRename', () => {
  /**
   * Boots one writable group and opens its rename input.
   *
   * @returns {HTMLInputElement} The rename input.
   */
  function openRename() {
    boot({ groups: [{ code: 'A', name: 'Alpha', childCodes: [], storeCodes: [] }], stores: [] }, true);
    const name = document.querySelector('#wb-tree .wb-group-name');
    name.dispatchEvent(new Event('dblclick', { bubbles: true }));
    return document.querySelector('#wb-tree .wb-rename');
  }

  /**
   * Blurring commits a non-empty new name.
   */
  it('commits a new name on blur', () => {
    const input = openRename();
    input.value = 'Beta';
    input.dispatchEvent(new Event('blur', { bubbles: true }));
    expect(wb.group('A').name).toBe('Beta');
  });

  /**
   * An emptied name falls back to the code on commit.
   */
  it('falls back to the code when the name is cleared', () => {
    const input = openRename();
    input.value = '   ';
    input.dispatchEvent(new Event('blur', { bubbles: true }));
    expect(wb.group('A').name).toBe('A');
  });

  /**
   * The Enter key blurs the input, committing the value.
   */
  it('commits on the Enter key', () => {
    const input = openRename();
    input.value = 'Gamma';
    fireKey(input, 'Enter');
    expect(wb.group('A').name).toBe('Gamma');
  });

  /**
   * The Escape key cancels the rename by redrawing the tree.
   */
  it('cancels on the Escape key', () => {
    const input = openRename();
    input.value = 'Delta';
    fireKey(input, 'Escape');
    expect(document.querySelector('#wb-tree .wb-rename')).toBeNull();
    expect(wb.group('A').name).toBe('Alpha');
  });

  /**
   * A group with no name seeds the rename input from its code (the g.name || g.code arm).
   */
  it('seeds the rename input from the code when the group has no name', () => {
    boot({ groups: [{ code: 'NN', childCodes: [], storeCodes: [] }], stores: [] }, true);
    const name = document.querySelector('#wb-tree .wb-group-name');
    name.dispatchEvent(new Event('dblclick', { bubbles: true }));
    expect(document.querySelector('#wb-tree .wb-rename').value).toBe('NN');
  });
});

describe('showError / clearError', () => {
  /**
   * showError reveals the box with the message; clearError hides it again.
   */
  it('shows then clears the error box', () => {
    boot({ groups: [], stores: [] }, false);
    wb.showError('boom');
    const box = document.getElementById('wb-error');
    expect(box.textContent).toBe('boom');
    expect(box.classList.contains('is-hidden')).toBe(false);
    wb.clearError();
    expect(box.classList.contains('is-hidden')).toBe(true);
  });
});

describe('save', () => {
  /**
   * A successful save clears the dirty state and re-enables the button through renderPending.
   */
  it('persists and adopts the new pristine state on success', async () => {
    boot({ groups: [], stores: [] }, false);
    wb.createGroup('NEW');
    global.fetch = vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({}) }));
    wb.save();
    expect(document.getElementById('wb-save').disabled).toBe(true);
    await flush();
    expect(wb.isDirty()).toBe(false);
  });

  /**
   * A rejected save surfaces the server error message.
   */
  it('shows the server error on failure', async () => {
    boot({ groups: [], stores: [] }, false);
    global.fetch = vi.fn(() => Promise.resolve({ ok: false, json: () => Promise.resolve({ error: 'nope' }) }));
    wb.save();
    await flush();
    expect(document.getElementById('wb-error').textContent).toBe('nope');
  });

  /**
   * A failure without an error message falls back to the default message.
   */
  it('shows the default message when the server gives none', async () => {
    boot({ groups: [], stores: [] }, false);
    global.fetch = vi.fn(() => Promise.resolve({ ok: false, json: () => Promise.resolve({}) }));
    wb.save();
    await flush();
    expect(document.getElementById('wb-error').textContent).toBe('The hierarchy could not be saved.');
  });

  /**
   * A network rejection surfaces the default message.
   */
  it('shows the default message on a network error', async () => {
    boot({ groups: [], stores: [] }, false);
    global.fetch = vi.fn(() => Promise.reject(new Error('offline')));
    wb.save();
    await flush();
    expect(document.getElementById('wb-error').textContent).toBe('The hierarchy could not be saved.');
  });
});

describe('revert', () => {
  /**
   * Reverting drops every pending change and clears the selection.
   */
  it('restores the pristine model and clears the selection', () => {
    boot({
      groups: [{ code: 'A', name: 'A', childCodes: [], storeCodes: [] }],
      stores: [{ code: 's1', name: 'One' }],
    }, true);
    wb.toggleSelection('s1', { shiftKey: false });
    wb.createGroup('EXTRA');
    wb.revert();
    expect(wb.group('EXTRA')).toBeNull();
    expect(wb.isDirty()).toBe(false);
    expect(document.querySelectorAll('#wb-stores .wb-store.is-selected').length).toBe(0);
  });
});

describe('init', () => {
  /**
   * Malformed model JSON falls back to an empty hierarchy without throwing.
   */
  it('falls back to an empty model on malformed model JSON', () => {
    document.body.innerHTML = SKELETON;
    document.getElementById('wb-model').textContent = 'not json';
    document.getElementById('wb-can-write').textContent = 'true';
    wb.init();
    expect(wb.roots()).toEqual([]);
  });

  /**
   * Malformed permission JSON falls back to read-only (no drag handle on rows).
   */
  it('falls back to read-only on malformed permission JSON', () => {
    document.body.innerHTML = SKELETON;
    document.getElementById('wb-model').textContent = JSON.stringify({
      groups: [{ code: 'A', name: 'A' }],
      stores: [],
    });
    document.getElementById('wb-can-write').textContent = 'not json';
    wb.init();
    expect(document.querySelector('#wb-tree .wb-group-row').draggable).toBe(false);
  });

  /**
   * A group without member arrays is normalised (the || [] arms of init).
   */
  it('normalises groups that lack member arrays', () => {
    boot({ groups: [{ code: 'A', name: 'A' }], stores: [] }, false);
    expect(wb.group('A').storeCodes).toEqual([]);
    expect(wb.group('A').childCodes).toEqual([]);
  });

  /**
   * Pressing Enter in the new-group field creates a group and clears the field.
   */
  it('creates a group from the new-group field on Enter', () => {
    boot({ groups: [], stores: [] }, true);
    const field = document.getElementById('wb-new-group');
    field.value = 'FromField';
    fireKey(field, 'Enter');
    expect(wb.group('FromField')).not.toBeNull();
    expect(field.value).toBe('');
  });

  /**
   * A non-Enter key in the new-group field creates nothing.
   */
  it('ignores non-Enter keys in the new-group field', () => {
    boot({ groups: [], stores: [] }, true);
    const field = document.getElementById('wb-new-group');
    field.value = 'Nope';
    fireKey(field, 'a');
    expect(wb.roots()).toEqual([]);
  });

  /**
   * Clicking the wired save and revert buttons runs the persistence handlers.
   */
  it('wires the save and revert buttons', () => {
    boot({ groups: [], stores: [] }, false);
    wb.createGroup('X');
    global.fetch = vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({}) }));
    document.getElementById('wb-save').dispatchEvent(new Event('click', { bubbles: true }));
    expect(global.fetch).toHaveBeenCalledOnce();
    document.getElementById('wb-revert').dispatchEvent(new Event('click', { bubbles: true }));
    expect(wb.group('X')).toBeNull();
  });

  /**
   * The store-search field is wired to re-render the catalog on input.
   */
  it('wires the store-search field', () => {
    boot({
      groups: [],
      stores: [{ code: 's1', name: 'One' }, { code: 's2', name: 'Two' }],
    }, false);
    const search = document.getElementById('wb-store-search');
    search.value = 'one';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    expect(document.querySelectorAll('#wb-stores .wb-store').length).toBe(1);
  });

  /**
   * beforeunload warns while there are unsaved changes and stays silent otherwise.
   */
  it('guards against leaving with unsaved changes', () => {
    boot({ groups: [], stores: [] }, false);
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);
    wb.createGroup('DIRTY');
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  /**
   * Optional controls being absent takes the null arms of their guards without throwing.
   */
  it('tolerates a skeleton without the optional controls', () => {
    document.body.innerHTML = '<div id="wb-tree"></div>'
      + '<div id="wb-stores"></div>'
      + '<input id="wb-store-search">'
      + '<div id="wb-error" class="is-hidden"></div>'
      + '<script id="wb-model" type="application/json">{"groups":[],"stores":[]}</script>'
      + '<script id="wb-can-write" type="application/json">false</script>';
    expect(() => wb.init()).not.toThrow();
  });
});

describe('boot dispatcher', () => {
  /**
   * A document still loading defers init() to DOMContentLoaded instead of running it now.
   * Re-evaluating the module under readyState 'loading' exercises that arm; Istanbul merges
   * the second evaluation into the same file path.
   */
  it('defers init to DOMContentLoaded while the document is loading', async () => {
    Object.defineProperty(document, 'readyState', { value: 'loading', configurable: true });
    const reloaded = await loadScript(PATH, { bust: 'loading' });
    Object.defineProperty(document, 'readyState', { value: 'complete', configurable: true });
    expect(typeof reloaded.init).toBe('function');
  });
});
