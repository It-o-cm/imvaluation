import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of list-filters.js.
 *
 * The file holds TWO browser IIFEs: the autocomplete-filter one (el, debounce, lookup,
 * buildFilterInput, init) and the CSV-import one (initImport). The test harness exposes the
 * top-level functions of both. Pure helpers are asserted to the exact value; the DOM wiring
 * is driven against a jsdom document, with fetch mocked and Vitest fake timers standing in
 * for the 180 ms debounce and the 150 ms blur delay.
 */

/**
 * Project-relative path of the source under test.
 */
const PATH = 'src/main/resources/META-INF/resources/ui/list-filters.js';

/**
 * The source under test, exposing its top-level functions once loaded.
 */
let lf;

/**
 * Loads the source once for the whole suite (its two IIFEs boot against an empty document,
 * which is harmless: init finds no hosts and initImport returns on the missing trigger).
 */
beforeAll(async () => {
  global.fetch = vi.fn();
  lf = await loadScript(PATH);
});

/**
 * Resets the document and the fetch mock between cases.
 */
beforeEach(() => {
  document.body.innerHTML = '';
  global.fetch = vi.fn();
});

/**
 * Restores real timers after any case that installed fake ones.
 */
afterEach(() => {
  vi.useRealTimers();
});

/**
 * Builds a filter host inside a form and returns both, so input.form resolves for the
 * submit-on-pick path.
 *
 * @param {object} attrs The data-lookup / data-name / data-value attributes to set.
 * @returns {{host: HTMLElement, form: HTMLElement}} The host and its enclosing form.
 */
function buildHost(attrs) {
  const form = document.createElement('form');
  const host = document.createElement('div');
  if (attrs.lookup !== undefined) { host.setAttribute('data-lookup', attrs.lookup); }
  if (attrs.name !== undefined) { host.setAttribute('data-name', attrs.name); }
  if (attrs.value !== undefined) { host.setAttribute('data-value', attrs.value); }
  form.appendChild(host);
  document.body.appendChild(form);
  return { host, form };
}

/**
 * Dispatches a named event on a node.
 *
 * @param {Node} node The event target.
 * @param {string} type The event type.
 */
function fire(node, type) {
  node.dispatchEvent(new window.Event(type, { bubbles: true }));
}

describe('el', () => {
  /**
   * A class name and text set both the className and the textContent.
   */
  it('sets class and text when both are given', () => {
    const node = lf.el('div', 'cls', 'hello');
    expect(node.tagName).toBe('DIV');
    expect(node.className).toBe('cls');
    expect(node.textContent).toBe('hello');
  });

  /**
   * A falsy class name and an undefined text leave both untouched.
   */
  it('leaves class and text untouched when omitted', () => {
    const node = lf.el('span');
    expect(node.className).toBe('');
    expect(node.textContent).toBe('');
  });

  /**
   * A null text takes the second arm of the guard (not undefined, but null) and is skipped,
   * while the class name is still applied.
   */
  it('applies the class but skips a null text', () => {
    const node = lf.el('p', 'c', null);
    expect(node.className).toBe('c');
    expect(node.textContent).toBe('');
  });
});

describe('debounce', () => {
  /**
   * Two quick calls collapse into a single deferred call carrying the last arguments and
   * the caller's `this`.
   */
  it('fires once with the last arguments and this after the delay', () => {
    vi.useFakeTimers();
    let seenThis = null;
    const fn = vi.fn(function () { seenThis = this; });
    const context = { tag: 'ctx' };
    const debounced = lf.debounce(fn, 100);
    debounced.call(context, 'a');
    debounced.call(context, 'b');
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(100);
    expect(fn).toHaveBeenCalledTimes(1);
    expect(fn).toHaveBeenCalledWith('b');
    expect(seenThis).toBe(context);
  });
});

describe('lookup', () => {
  /**
   * A successful response is parsed as JSON and returned, and the request carries the query
   * and the JSON headers.
   */
  it('returns the parsed JSON on a successful response', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([{ value: 'v' }]) });
    const result = await lf.lookup('/e', 'q');
    expect(result).toEqual([{ value: 'v' }]);
    expect(global.fetch).toHaveBeenCalledWith('/e?q=q', {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    });
  });

  /**
   * A non-ok response yields an empty array.
   */
  it('returns an empty array on a non-ok response', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, json: () => Promise.resolve([{ value: 'x' }]) });
    const result = await lf.lookup('/e', 'q');
    expect(result).toEqual([]);
  });

  /**
   * A rejected fetch is caught and yields an empty array.
   */
  it('returns an empty array when the fetch rejects', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('offline'));
    const result = await lf.lookup('/e', 'q');
    expect(result).toEqual([]);
  });

  /**
   * A missing query takes the empty-string arm of the fallback and is encoded to nothing.
   */
  it('encodes a missing query as an empty term', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    await lf.lookup('/e');
    expect(global.fetch).toHaveBeenCalledWith('/e?q=', expect.anything());
  });

  /**
   * A query with a space is URL-encoded.
   */
  it('url-encodes the query term', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    await lf.lookup('/e', 'a b');
    expect(global.fetch).toHaveBeenCalledWith('/e?q=a%20b', expect.anything());
  });
});

describe('buildFilterInput', () => {
  /**
   * An unknown data-lookup kind takes the early-return arm and builds nothing.
   */
  it('does nothing for an unknown kind', () => {
    const { host } = buildHost({ lookup: 'nope', name: 'n' });
    lf.buildFilterInput(host);
    expect(host.querySelector('.ac')).toBeNull();
  });

  /**
   * A known kind builds the input with the mapped placeholder, the given name and the
   * data-value as its initial value.
   */
  it('builds an input carrying the placeholder, name and initial value', () => {
    const { host } = buildHost({ lookup: 'store', name: 'storeCode', value: 'S1' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    expect(input).not.toBeNull();
    expect(input.name).toBe('storeCode');
    expect(input.placeholder).toBe('Store code or name');
    expect(input.value).toBe('S1');
  });

  /**
   * A missing data-value takes the empty-string arm of the value fallback.
   */
  it('defaults the value to an empty string when data-value is absent', () => {
    const { host } = buildHost({ lookup: 'ean', name: 'ean' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    expect(input.value).toBe('');
    expect(input.placeholder).toBe('EAN or product name');
  });

  /**
   * Typing triggers a debounced lookup and renders the suggestions, the first with a detail
   * span and the second without.
   */
  it('renders suggestions on input, with and without a detail', async () => {
    vi.useFakeTimers();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([
        { value: 'A1', label: 'Apple', detail: 'fruit' },
        { value: 'B2', label: 'Banana' },
      ]),
    });
    const { host } = buildHost({ lookup: 'ean', name: 'ean' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    const dropdown = host.querySelector('.ac-dropdown');
    input.value = 'app';
    fire(input, 'input');
    await vi.advanceTimersByTimeAsync(180);
    expect(dropdown.classList.contains('is-hidden')).toBe(false);
    expect(dropdown.children.length).toBe(2);
    const first = dropdown.children[0];
    expect(first.querySelector('.ac-item-value').textContent).toBe('A1');
    expect(first.querySelector('.ac-item-label').textContent).toBe('Apple');
    expect(first.querySelector('.ac-item-detail').textContent).toBe('fruit');
    expect(dropdown.children[1].querySelector('.ac-item-detail')).toBeNull();
  });

  /**
   * A focus with no matching suggestions hides the dropdown.
   */
  it('hides the dropdown when the lookup returns nothing', async () => {
    vi.useFakeTimers();
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    const { host } = buildHost({ lookup: 'store-any', name: 'target' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    const dropdown = host.querySelector('.ac-dropdown');
    fire(input, 'focus');
    await vi.advanceTimersByTimeAsync(180);
    expect(dropdown.classList.contains('is-hidden')).toBe(true);
    expect(dropdown.innerHTML).toBe('');
  });

  /**
   * Picking a suggestion sets the input value, hides the dropdown and submits the form.
   */
  it('submits the form when a suggestion is picked', async () => {
    vi.useFakeTimers();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([{ value: 'S9', label: 'Shop 9' }]),
    });
    const { host, form } = buildHost({ lookup: 'store', name: 'storeCode' });
    form.submit = vi.fn();
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    const dropdown = host.querySelector('.ac-dropdown');
    fire(input, 'input');
    await vi.advanceTimersByTimeAsync(180);
    const item = dropdown.querySelector('.ac-item');
    item.dispatchEvent(new window.MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(input.value).toBe('S9');
    expect(dropdown.classList.contains('is-hidden')).toBe(true);
    expect(form.submit).toHaveBeenCalledTimes(1);
  });

  /**
   * Blurring the input hides the dropdown after the 150 ms grace delay.
   */
  it('hides the dropdown on blur after the delay', async () => {
    vi.useFakeTimers();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([{ value: 'A1', label: 'Apple' }]),
    });
    const { host } = buildHost({ lookup: 'ean', name: 'ean' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    const dropdown = host.querySelector('.ac-dropdown');
    fire(input, 'input');
    await vi.advanceTimersByTimeAsync(180);
    expect(dropdown.classList.contains('is-hidden')).toBe(false);
    fire(input, 'blur');
    await vi.advanceTimersByTimeAsync(150);
    expect(dropdown.classList.contains('is-hidden')).toBe(true);
  });

  /**
   * Escape hides the dropdown immediately.
   */
  it('hides the dropdown on the Escape key', async () => {
    vi.useFakeTimers();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([{ value: 'A1', label: 'Apple' }]),
    });
    const { host } = buildHost({ lookup: 'ean', name: 'ean' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    const dropdown = host.querySelector('.ac-dropdown');
    fire(input, 'input');
    await vi.advanceTimersByTimeAsync(180);
    input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(dropdown.classList.contains('is-hidden')).toBe(true);
  });

  /**
   * A non-Escape key takes the other arm of the keydown guard and leaves the dropdown open.
   */
  it('ignores a non-Escape key', async () => {
    vi.useFakeTimers();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([{ value: 'A1', label: 'Apple' }]),
    });
    const { host } = buildHost({ lookup: 'ean', name: 'ean' });
    lf.buildFilterInput(host);
    const input = host.querySelector('.ac-input');
    const dropdown = host.querySelector('.ac-dropdown');
    fire(input, 'input');
    await vi.advanceTimersByTimeAsync(180);
    input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'a', bubbles: true }));
    expect(dropdown.classList.contains('is-hidden')).toBe(false);
  });
});

describe('init', () => {
  /**
   * Every declared host is wired, except the one with an unknown kind which is skipped.
   */
  it('wires known filter hosts and skips unknown ones', () => {
    buildHost({ lookup: 'store', name: 'storeCode' });
    buildHost({ lookup: 'ean', name: 'ean' });
    buildHost({ lookup: 'bad', name: 'nope' });
    lf.init();
    const hosts = document.querySelectorAll('[data-lookup]');
    expect(hosts[0].querySelector('.ac-input')).not.toBeNull();
    expect(hosts[1].querySelector('.ac-input')).not.toBeNull();
    expect(hosts[2].querySelector('.ac-input')).toBeNull();
  });
});

describe('initImport', () => {
  /**
   * Builds the import toolbar markup.
   *
   * @param {object} present Which of trigger/input/form to include.
   * @returns {{trigger: HTMLElement, input: HTMLElement, form: HTMLElement}} The nodes.
   */
  function buildImport(present) {
    if (present.form !== false) {
      const form = document.createElement('form');
      form.id = 'import-form';
      document.body.appendChild(form);
    }
    if (present.trigger !== false) {
      const trigger = document.createElement('button');
      trigger.id = 'import-trigger';
      document.body.appendChild(trigger);
    }
    if (present.input !== false) {
      const input = document.createElement('input');
      input.type = 'file';
      input.id = 'import-file';
      document.body.appendChild(input);
    }
    return {
      trigger: document.getElementById('import-trigger'),
      input: document.getElementById('import-file'),
      form: document.getElementById('import-form'),
    };
  }

  /**
   * A missing trigger takes the first arm of the guard and returns without wiring.
   */
  it('returns when the trigger is missing', () => {
    buildImport({ trigger: false });
    expect(() => lf.initImport()).not.toThrow();
  });

  /**
   * A missing file input takes the second arm of the guard.
   */
  it('returns when the file input is missing', () => {
    buildImport({ input: false });
    expect(() => lf.initImport()).not.toThrow();
  });

  /**
   * A missing form takes the third arm of the guard.
   */
  it('returns when the form is missing', () => {
    buildImport({ form: false });
    expect(() => lf.initImport()).not.toThrow();
  });

  /**
   * The trigger opens the hidden file input.
   */
  it('clicks the file input when the trigger is pressed', () => {
    const { trigger, input } = buildImport({});
    const clickSpy = vi.spyOn(input, 'click').mockImplementation(() => {});
    lf.initImport();
    fire(trigger, 'click');
    expect(clickSpy).toHaveBeenCalledTimes(1);
  });

  /**
   * Choosing a file submits the form.
   */
  it('submits the form when a file is chosen', () => {
    const { input, form } = buildImport({});
    form.submit = vi.fn();
    Object.defineProperty(input, 'files', { value: { length: 1 }, configurable: true });
    lf.initImport();
    fire(input, 'change');
    expect(form.submit).toHaveBeenCalledTimes(1);
  });

  /**
   * An empty selection (files present, length 0) does not submit.
   */
  it('does not submit when the selection is empty', () => {
    const { input, form } = buildImport({});
    form.submit = vi.fn();
    Object.defineProperty(input, 'files', { value: { length: 0 }, configurable: true });
    lf.initImport();
    fire(input, 'change');
    expect(form.submit).not.toHaveBeenCalled();
  });

  /**
   * A null files property takes the left arm of the guard and does not submit.
   */
  it('does not submit when files is null', () => {
    const { input, form } = buildImport({});
    form.submit = vi.fn();
    Object.defineProperty(input, 'files', { value: null, configurable: true });
    lf.initImport();
    fire(input, 'change');
    expect(form.submit).not.toHaveBeenCalled();
  });
});

describe('boot dispatch', () => {
  /**
   * Re-evaluating the module while the document is still loading takes the "loading" arm of
   * both IIFEs and registers a DOMContentLoaded listener for each. A cache-busted re-import
   * is used so a second boot happens; Istanbul attributes its coverage to the real path.
   */
  it('registers DOMContentLoaded when the document is loading', async () => {
    const spy = vi.spyOn(document, 'addEventListener');
    Object.defineProperty(document, 'readyState', { configurable: true, get: () => 'loading' });
    await loadScript(PATH, { bust: 'loading' });
    const domReady = spy.mock.calls.filter((call) => call[0] === 'DOMContentLoaded');
    expect(domReady.length).toBeGreaterThanOrEqual(2);
    spy.mockRestore();
    delete document.readyState;
  });
});
