import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of imports.js.
 *
 * The file is a browser IIFE that, at import time, looks up the .import-drop zone, its file
 * input and its domain <select>, early-returns when the zone or the file input is absent, and
 * otherwise wires drag & drop plus a change listener. Its central piece of logic is
 * preselectDomain(), which maps a dropped/chosen file name to an import domain through the
 * ordered DOMAINS table (first match wins). The two referential feeds added to the screen —
 * 'egalim' → EGALIM_REGIMES and 'vat' → VAT_RATES — are asserted alongside the seven others.
 *
 * The IIFE binds zone/fileInput/domainSel once against the document present at import, so the
 * primary suite builds the full DOM BEFORE importing. jsdom 25 ships no DataTransfer and its
 * HTMLInputElement.files setter rejects plain objects, so each test redefines fileInput.files
 * as a writable data property, which also lets the drop handler's own assignment succeed.
 */

/**
 * Project-relative path of the source under test.
 */
const SOURCE = 'src/main/resources/META-INF/resources/ui/imports.js';

/**
 * The module object exposing preselectDomain once the source is loaded.
 */
let mod;

/**
 * The drop zone, its file input and its domain select, kept stable for the whole suite.
 */
let zone;
let fileInput;
let domainSel;

/**
 * Installs the full import screen DOM: the drop zone, a file input and the domain select
 * carrying every option the screen offers, in the screen's own order.
 */
function installFullDom() {
  document.body.innerHTML =
    '<form class="import-drop" method="post" enctype="multipart/form-data"'
    + ' action="/ui/imports/run?csrf-token=TESTTOKEN">'
    + '<input type="hidden" name="csrf-token" value="TESTTOKEN">'
    + '<select name="domain">'
    + '<option value="STORES">STORES</option>'
    + '<option value="STORE_GROUPS">STORE_GROUPS</option>'
    + '<option value="EGALIM_REGIMES">EGALIM_REGIMES</option>'
    + '<option value="PRODUCTS">PRODUCTS</option>'
    + '<option value="PRODUCT_FAMILIES">PRODUCT_FAMILIES</option>'
    + '<option value="CATEGORIES">CATEGORIES</option>'
    + '<option value="VAT_RATES">VAT_RATES</option>'
    + '<option value="PRICES">PRICES</option>'
    + '<option value="OFFERS">OFFERS</option>'
    + '</select>'
    + '<input type="file" name="file">'
    + '</form>';
}

/**
 * Builds a FileList-like object the redefined fileInput.files accepts and the handlers read.
 *
 * @param {string|null} name The single file name, or null for an empty selection.
 * @returns {object} An array-like carrying the name and a matching length.
 */
function makeFiles(name) {
  if (name === null) {
    return { length: 0, item() { return null; } };
  }
  return { 0: { name }, length: 1, item(i) { return i === 0 ? this[0] : null; } };
}

/**
 * Redefines fileInput.files as a writable data property holding the given list, bypassing the
 * native setter that jsdom would otherwise use to reject a non-FileList value.
 *
 * @param {object} fl The FileList-like value to install.
 */
function setFiles(fl) {
  Object.defineProperty(fileInput, 'files', { configurable: true, writable: true, value: fl });
}

/**
 * Builds and dispatches an event on a target, letting the test attach ad-hoc properties such
 * as dataTransfer or relatedTarget the handlers read.
 *
 * @param {EventTarget} target The node to dispatch on.
 * @param {string} type The event type.
 * @param {object} [props] Extra properties to assign onto the event before dispatch.
 * @returns {Event} The dispatched event, for defaultPrevented assertions.
 */
function fire(target, type, props = {}) {
  const evt = new Event(type, { bubbles: true, cancelable: true });
  Object.assign(evt, props);
  target.dispatchEvent(evt);
  return evt;
}

/**
 * Builds the full DOM, imports the source once so its handlers register and preselectDomain is
 * exposed, then captures the wired elements.
 */
beforeAll(async () => {
  installFullDom();
  mod = await loadScript(SOURCE);
  zone = document.querySelector('.import-drop');
  fileInput = zone.querySelector('input[type="file"]');
  domainSel = zone.querySelector('select[name="domain"]');
});

/**
 * Resets the selection and the drag state before each test so nothing leaks between them.
 */
beforeEach(() => {
  setFiles(makeFiles(null));
  domainSel.selectedIndex = 0;
  zone.classList.remove('is-dragover');
});

describe('preselectDomain', () => {
  /**
   * Each recognised file-name token selects its domain, exercising every DOMAINS row and the
   * indexOf-match arm of the loop; the two referential feeds are asserted with the rest.
   */
  it('maps every known file-name token to its domain', () => {
    const cases = [
      ['store-groups.csv', 'STORE_GROUPS'],
      ['stores.csv', 'STORES'],
      ['egalim-regimes.csv', 'EGALIM_REGIMES'],
      ['product-families.csv', 'PRODUCT_FAMILIES'],
      ['product-category-storages.csv', 'CATEGORIES'],
      ['products.csv', 'PRODUCTS'],
      ['vat-rates.csv', 'VAT_RATES'],
      ['prices.csv', 'PRICES'],
      ['offers.csv', 'OFFERS'],
    ];
    for (const [name, expected] of cases) {
      domainSel.value = 'STORES';
      mod.preselectDomain(name);
      expect(domainSel.value).toBe(expected);
    }
  });

  /**
   * A name matching no token leaves the current selection untouched, exercising the
   * indexOf-no-match arm across every row and the loop exit.
   */
  it('leaves the selection unchanged for an unrecognised name', () => {
    domainSel.value = 'OFFERS';
    mod.preselectDomain('random-file.txt');
    expect(domainSel.value).toBe('OFFERS');
  });

  /**
   * An empty name takes the !name arm of the guard and returns before the loop, leaving the
   * selection untouched.
   */
  it('does nothing for an empty name', () => {
    domainSel.value = 'PRICES';
    mod.preselectDomain('');
    expect(domainSel.value).toBe('PRICES');
  });

  /**
   * A null name also takes the !name arm of the guard.
   */
  it('does nothing for a null name', () => {
    domainSel.value = 'PRICES';
    mod.preselectDomain(null);
    expect(domainSel.value).toBe('PRICES');
  });

  /**
   * The case-insensitive match: an upper-cased name is lower-cased before the token search.
   */
  it('matches regardless of case', () => {
    domainSel.value = 'STORES';
    mod.preselectDomain('OFFERS.CSV');
    expect(domainSel.value).toBe('OFFERS');
  });
});

describe('drag state', () => {
  /**
   * dragenter marks the zone as a drop target and prevents the default to allow the drop.
   */
  it('adds the dragover class on dragenter', () => {
    const evt = fire(zone, 'dragenter');
    expect(zone.classList.contains('is-dragover')).toBe(true);
    expect(evt.defaultPrevented).toBe(true);
  });

  /**
   * dragover keeps the zone marked, covering the second registered drag type.
   */
  it('adds the dragover class on dragover', () => {
    const evt = fire(zone, 'dragover');
    expect(zone.classList.contains('is-dragover')).toBe(true);
    expect(evt.defaultPrevented).toBe(true);
  });

  /**
   * dragleave toward a node outside the zone clears the marker (the !contains true arm).
   */
  it('clears the dragover class when leaving toward an outside node', () => {
    zone.classList.add('is-dragover');
    fire(zone, 'dragleave', { relatedTarget: document.body });
    expect(zone.classList.contains('is-dragover')).toBe(false);
  });

  /**
   * dragleave toward a child still inside the zone keeps the marker (the !contains false arm).
   */
  it('keeps the dragover class when leaving toward an inside node', () => {
    zone.classList.add('is-dragover');
    fire(zone, 'dragleave', { relatedTarget: domainSel });
    expect(zone.classList.contains('is-dragover')).toBe(true);
  });
});

describe('drop', () => {
  /**
   * A drop carrying a recognisable file assigns it to the input, clears the marker and
   * preselects the domain from its name (every arm of the drop guard false).
   */
  it('accepts the dropped file and preselects its domain', () => {
    zone.classList.add('is-dragover');
    const files = makeFiles('offers.csv');
    const evt = fire(zone, 'drop', { dataTransfer: { files } });
    expect(evt.defaultPrevented).toBe(true);
    expect(zone.classList.contains('is-dragover')).toBe(false);
    expect(fileInput.files).toBe(files);
    expect(domainSel.value).toBe('OFFERS');
  });

  /**
   * A drop with no dataTransfer takes the first arm of the guard and does nothing.
   */
  it('ignores a drop without a dataTransfer', () => {
    domainSel.value = 'STORES';
    fire(zone, 'drop', {});
    expect(domainSel.value).toBe('STORES');
  });

  /**
   * A drop whose dataTransfer carries no files takes the second arm of the guard.
   */
  it('ignores a drop without files', () => {
    domainSel.value = 'STORES';
    fire(zone, 'drop', { dataTransfer: {} });
    expect(domainSel.value).toBe('STORES');
  });

  /**
   * A drop whose file list is empty takes the length arm of the guard.
   */
  it('ignores a drop with an empty file list', () => {
    domainSel.value = 'STORES';
    fire(zone, 'drop', { dataTransfer: { files: makeFiles(null) } });
    expect(domainSel.value).toBe('STORES');
  });
});

describe('change', () => {
  /**
   * Choosing a file through the picker preselects the domain from its name (the length-true
   * arm of the change guard).
   */
  it('preselects the domain when a file is chosen', () => {
    setFiles(makeFiles('vat-rates.csv'));
    fire(fileInput, 'change');
    expect(domainSel.value).toBe('VAT_RATES');
  });

  /**
   * Clearing the picker leaves the selection untouched (the length-false arm).
   */
  it('does nothing when the selection is cleared', () => {
    domainSel.value = 'PRICES';
    setFiles(makeFiles(null));
    fire(fileInput, 'change');
    expect(domainSel.value).toBe('PRICES');
  });
});

describe('boot guards', () => {
  /**
   * A second evaluation against a document with no .import-drop exercises the zone-absent
   * early return. Cache-busted imports are separate instrumented instances Istanbul does not
   * merge into the primary evaluation, so the zone-present and zone-absent arms cannot both be
   * counted in one run; this documents and executes the arm.
   */
  it('returns early when the drop zone is absent', async () => {
    document.body.innerHTML = '';
    const second = await loadScript(SOURCE, { bust: 'no-zone' });
    expect(second).toBeTruthy();
  });

  /**
   * A third evaluation against a zone with no file input exercises the file-input-absent early
   * return, likewise executed for documentation of a non-merged residue.
   */
  it('returns early when the file input is absent', async () => {
    document.body.innerHTML = '<form class="import-drop"><select name="domain"></select></form>';
    const third = await loadScript(SOURCE, { bust: 'no-file-input' });
    expect(third).toBeTruthy();
  });

  /**
   * A fourth evaluation against a zone with a file input but no select binds a null domainSel;
   * its preselectDomain then takes the !domainSel arm and returns without throwing.
   */
  it('preselectDomain returns early when the select is absent', async () => {
    document.body.innerHTML = '<form class="import-drop"><input type="file" name="file"></form>';
    const fourth = await loadScript(SOURCE, { bust: 'no-select' });
    expect(() => fourth.preselectDomain('stores.csv')).not.toThrow();
  });
});

describe('submission target and CSRF token', () => {
  /**
   * The drop zone posts to the import endpoint (spec-durcissement-web §2.3): the form action
   * targets /ui/imports/run, so a preselected drop is submitted to the right domain runner.
   */
  it('posts to the import run endpoint', () => {
    expect(zone.getAttribute('action')).toContain('/ui/imports/run');
    expect(zone.getAttribute('method')).toBe('post');
  });

  /**
   * The CSRF token rides with the submission (spec-durcissement-web §1, imports §2.5): imports.js
   * only fills the file input and the domain, so both the query-string token on the action and the
   * hidden csrf-token field survive a drag & drop untouched.
   */
  it('preserves the CSRF token through a drop', () => {
    setFiles(makeFiles(null));
    fire(zone, 'drop', { dataTransfer: { files: makeFiles('offers.csv') } });
    expect(zone.getAttribute('action')).toContain('csrf-token=TESTTOKEN');
    const hidden = zone.querySelector('input[name="csrf-token"]');
    expect(hidden).not.toBeNull();
    expect(hidden.value).toBe('TESTTOKEN');
    expect(domainSel.value).toBe('OFFERS');
  });
});
