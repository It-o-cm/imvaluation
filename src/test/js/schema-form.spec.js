import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of schema-form.js, the schema-driven offer specification editor.
 *
 * The source is a browser IIFE that auto-runs init() at import (jsdom readyState is
 * 'complete'), binding its module-level element references (typeSelect, formHost, ...) to
 * whatever DOM exists at that instant. The suite therefore builds the complete form DOM
 * once, before loadScript, and never rebuilds it — wiring tests mutate the same nodes.
 * Pure resolvers and field builders are exercised directly; DOM wiring is driven against
 * that persistent form. fetch is mocked and timers are faked so no real network or clock
 * is used.
 */

/**
 * Project-relative path of the source under test.
 */
const PATH = 'src/main/resources/META-INF/resources/ui/schema-form.js';

/**
 * The exposed top-level functions of the source, populated once at import.
 */
let view;

/**
 * References to the persistent form nodes the module binds to at import.
 */
let els;

/**
 * The schema catalogue embedded in #offer-schemas for the wiring tests.
 */
const SCHEMAS = {
  RICH: {
    type: 'object',
    required: ['label'],
    properties: {
      ean: { type: 'string' },
      eans: { type: 'array' },
      storeCode: { type: 'string' },
      taxRate: { type: 'number', minimum: 0, maximum: 1 },
      price: { type: 'number', minimum: 0 },
      quantity: { type: 'integer', minimum: 0 },
      active: { type: 'boolean' },
      mode: { enum: ['A', 'B'] },
      tags: { oneOf: [{ const: 'x' }] },
      tiers: { type: 'array', items: { type: 'object', properties: { qty: { type: 'integer' } } }, 'x-item-label': 'tier' },
      block: { type: 'object', properties: { note: { type: 'string' } } },
      vignettes: { type: 'object' },
      label: { type: 'string', description: 'A human label' },
    },
  },
  MINI: { type: 'object', properties: { title: { type: 'string' } } },
};

/**
 * A specification matching the RICH schema, used to hit the value-present builder arms.
 */
const RICH_SPEC = {
  ean: 'E1',
  storeCode: 'S1',
  taxRate: 0.2,
  price: 9.99,
  quantity: 3,
  active: true,
  mode: 'A',
  tags: ['t1'],
  tiers: [{ qty: 2 }],
  block: { note: 'n' },
  vignettes: { E9: 2 },
  label: 'Hello',
};

/**
 * Builds the complete offer form DOM the module binds to, keeping node references in `els`.
 *
 * @param {object} schemasObj The schema catalogue to embed.
 * @param {object} specObj The specification to embed.
 */
function buildForm(schemasObj, specObj) {
  document.body.innerHTML = '';
  const mk = (tag, attrs, text) => {
    const node = document.createElement(tag);
    Object.keys(attrs || {}).forEach((k) => {
      if (k === 'class') { node.className = attrs[k]; } else { node.setAttribute(k, attrs[k]); }
    });
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  };
  const schemasScript = mk('script', { id: 'offer-schemas', type: 'application/json' }, JSON.stringify(schemasObj));
  const specScript = mk('script', { id: 'offer-specification', type: 'application/json' }, JSON.stringify(specObj));
  const form = mk('form', { id: 'offer-form' });
  const select = mk('select', { id: 'offer-type' });
  ['', 'RICH', 'MINI', 'NOSCHEMA'].forEach((v) => { select.appendChild(mk('option', { value: v }, v || '—')); });
  const modeSwitch = mk('div', { class: 'mode-switch' });
  const bForm = mk('button', { type: 'button', 'data-mode': 'form', class: 'is-active' }, 'Form');
  const bJson = mk('button', { type: 'button', 'data-mode': 'json' }, 'JSON');
  modeSwitch.appendChild(bForm);
  modeSwitch.appendChild(bJson);
  const schemaForm = mk('div', { id: 'schema-form' });
  const jsonEditor = mk('div', { id: 'json-editor', class: 'is-hidden' });
  const rawTa = mk('textarea', { id: 'specification-raw' });
  jsonEditor.appendChild(rawTa);
  const hidden = mk('input', { type: 'hidden', id: 'specification', name: 'specification' });
  const errors = mk('div', { id: 'spec-errors', class: 'is-hidden' });
  form.appendChild(select);
  form.appendChild(modeSwitch);
  form.appendChild(schemaForm);
  form.appendChild(jsonEditor);
  form.appendChild(hidden);
  form.appendChild(errors);
  document.body.appendChild(schemasScript);
  document.body.appendChild(specScript);
  document.body.appendChild(form);
  els = { schemasScript, specScript, form, select, bForm, bJson, schemaForm, jsonEditor, rawTa, hidden, errors };
}

/**
 * Flushes pending microtasks so a mocked fetch promise chain settles under fake timers.
 */
async function microflush() {
  for (let i = 0; i < 12; i += 1) { await Promise.resolve(); }
}

/**
 * Makes global.fetch resolve with the given suggestions for the next calls.
 *
 * @param {Array} suggestions The array a successful lookup should yield.
 */
function fetchYields(suggestions) {
  globalThis.fetch.mockImplementation(() => Promise.resolve({ ok: true, json: () => Promise.resolve(suggestions) }));
}

/**
 * Dispatches a keydown carrying the given key on an element.
 *
 * @param {HTMLElement} node The target element.
 * @param {string} key The KeyboardEvent key value.
 */
function keydown(node, key) {
  node.dispatchEvent(new window.KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true }));
}

beforeAll(async () => {
  vi.useFakeTimers();
  globalThis.fetch = vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve([]) }));
  buildForm(SCHEMAS, {});
  view = await loadScript(PATH);
});

afterAll(() => {
  vi.useRealTimers();
});

afterEach(() => {
  vi.clearAllTimers();
  globalThis.fetch.mockReset();
  globalThis.fetch.mockImplementation(() => Promise.resolve({ ok: true, json: () => Promise.resolve([]) }));
});

describe('resolveWidget', () => {
  /**
   * An explicit x-widget annotation short-circuits every other rule.
   */
  it('honours an explicit x-widget annotation', () => {
    expect(view.resolveWidget('anything', { 'x-widget': 'money' })).toBe('money');
  });
  /**
   * The special "vignettes" name resolves to the ean-quantity-map widget.
   */
  it('maps the vignettes name to ean-quantity-map', () => {
    expect(view.resolveWidget('vignettes', {})).toBe('ean-quantity-map');
  });
  /**
   * Each naming convention resolves to its widget, most specific suffix winning first.
   */
  it('resolves every naming convention', () => {
    expect(view.resolveWidget('productEans', {})).toBe('ean-list');
    expect(view.resolveWidget('ean', {})).toBe('ean');
    expect(view.resolveWidget('storeGroupCode', {})).toBe('store-group-code');
    expect(view.resolveWidget('storeCode', {})).toBe('store-code');
    expect(view.resolveWidget('flag', {})).toBe('product-family-flag');
    expect(view.resolveWidget('flags', {})).toBe('product-family-flag');
    expect(view.resolveWidget('discountPercent', {})).toBe('percent');
    expect(view.resolveWidget('taxRate', {})).toBe('rate');
    expect(view.resolveWidget('vatRate', {})).toBe('rate');
    expect(view.resolveWidget('price', {})).toBe('money');
    expect(view.resolveWidget('threshold', {})).toBe('money');
    expect(view.resolveWidget('distance', {})).toBe('distance');
    expect(view.resolveWidget('radius', {})).toBe('distance');
    expect(view.resolveWidget('volume', {})).toBe('volume');
    expect(view.resolveWidget('weight', {})).toBe('weight');
    expect(view.resolveWidget('quantity', {})).toBe('quantity');
  });
  /**
   * A name matching no convention and a null name both fall through to the type resolver.
   */
  it('falls through to the type resolver when no convention matches', () => {
    expect(view.resolveWidget('title', { type: 'string' })).toBe('text');
    expect(view.resolveWidget(null, null)).toBe('text');
  });
});

describe('resolveByType', () => {
  /**
   * A missing schema resolves to text.
   */
  it('resolves a missing schema to text', () => {
    expect(view.resolveByType(null)).toBe('text');
  });
  /**
   * An enum schema resolves to the enum widget.
   */
  it('resolves an enum schema to enum', () => {
    expect(view.resolveByType({ enum: ['A'] })).toBe('enum');
  });
  /**
   * A oneOf schema resolves to a string list.
   */
  it('resolves a oneOf schema to string-list', () => {
    expect(view.resolveByType({ oneOf: [] })).toBe('string-list');
  });
  /**
   * Scalar JSON types resolve to their dedicated widgets.
   */
  it('resolves scalar JSON types', () => {
    expect(view.resolveByType({ type: 'integer' })).toBe('integer');
    expect(view.resolveByType({ type: 'number' })).toBe('number');
    expect(view.resolveByType({ type: 'boolean' })).toBe('boolean');
    expect(view.resolveByType({ type: 'object' })).toBe('object');
  });
  /**
   * An array of objects is an object-list; any other array is a string-list.
   */
  it('resolves arrays by their item type', () => {
    expect(view.resolveByType({ type: 'array', items: { type: 'object' } })).toBe('object-list');
    expect(view.resolveByType({ type: 'array', items: { type: 'string' } })).toBe('string-list');
    expect(view.resolveByType({ type: 'array' })).toBe('string-list');
  });
  /**
   * An unknown or absent type defaults to text.
   */
  it('defaults an unknown type to text', () => {
    expect(view.resolveByType({ type: 'string' })).toBe('text');
    expect(view.resolveByType({})).toBe('text');
  });
});

describe('labelOf', () => {
  /**
   * An explicit x-label annotation is used verbatim.
   */
  it('honours an explicit x-label', () => {
    expect(view.labelOf('whatever', { 'x-label': 'Custom' })).toBe('Custom');
  });
  /**
   * A camel-cased name is spaced and capitalised, with a null schema tolerated.
   */
  it('humanises a camel-cased name with a null schema', () => {
    expect(view.labelOf('storeCode', null)).toBe('Store Code');
  });
  /**
   * Underscores and dashes collapse to single spaces.
   */
  it('collapses separators to spaces', () => {
    expect(view.labelOf('store_code', {})).toBe('Store code');
  });
});

describe('el', () => {
  /**
   * A class name and text content are both applied when provided.
   */
  it('applies class and text', () => {
    const node = view.el('div', 'a', 'hi');
    expect(node.className).toBe('a');
    expect(node.textContent).toBe('hi');
  });
  /**
   * A falsy class name is skipped and undefined text leaves the content empty.
   */
  it('skips a falsy class and undefined text', () => {
    const node = view.el('span', '', undefined);
    expect(node.className).toBe('');
    expect(node.textContent).toBe('');
  });
  /**
   * A null text is skipped while a zero text is written.
   */
  it('skips null text but writes zero', () => {
    expect(view.el('span', 'c', null).textContent).toBe('');
    expect(view.el('span', 'c', 0).textContent).toBe('0');
  });
});

describe('debounce', () => {
  /**
   * Repeated calls collapse into a single trailing call carrying the last arguments.
   */
  it('fires once with the last arguments', () => {
    const fn = vi.fn();
    const debounced = view.debounce(fn, 180);
    debounced('a');
    debounced('b');
    vi.advanceTimersByTime(180);
    expect(fn).toHaveBeenCalledTimes(1);
    expect(fn).toHaveBeenCalledWith('b');
  });
});

describe('lookup', () => {
  /**
   * A 2xx response yields the parsed JSON body.
   */
  it('returns the parsed body on success', async () => {
    fetchYields([{ value: 'E1', label: 'P1' }]);
    const result = await view.lookup('/ui/lookup/products', 'e');
    expect(result).toEqual([{ value: 'E1', label: 'P1' }]);
  });
  /**
   * A non-ok response yields an empty array.
   */
  it('returns an empty array on a non-ok response', async () => {
    globalThis.fetch.mockImplementation(() => Promise.resolve({ ok: false, json: () => Promise.resolve([]) }));
    expect(await view.lookup('/ui/lookup/products', '')).toEqual([]);
  });
  /**
   * A rejected request yields an empty array.
   */
  it('returns an empty array when the request rejects', async () => {
    globalThis.fetch.mockImplementation(() => Promise.reject(new Error('boom')));
    expect(await view.lookup('/ui/lookup/products', 'x')).toEqual([]);
  });
});

describe('buildAutocomplete', () => {
  /**
   * A multiple field renders chips, resolves labels via fetch, and reacts to every event.
   */
  it('drives the full multiple-value lifecycle', async () => {
    fetchYields([{ value: 'E1', label: 'Prod 1' }]);
    const onChange = vi.fn();
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/products', multiple: true, values: ['E1', 'E2'], onChange: onChange });
    await microflush();
    const input = ac.node.querySelector('.ac-input');
    const dropdown = ac.node.querySelector('.ac-dropdown');
    const chips = ac.node.querySelector('.ac-chips');
    expect(ac.node.classList.contains('ac-multi')).toBe(true);
    expect(chips.querySelector('.ac-chip-label')).not.toBeNull();
    expect(chips.querySelectorAll('.ac-chip-unknown').length).toBe(1);
    fetchYields([{ value: 'E3', label: 'Prod 3', detail: 'd3' }, { value: 'E4', label: 'Prod 4' }]);
    input.dispatchEvent(new window.Event('input'));
    await vi.advanceTimersByTimeAsync(200);
    expect(dropdown.querySelectorAll('.ac-item').length).toBe(2);
    expect(dropdown.querySelector('.ac-item-detail')).not.toBeNull();
    dropdown.querySelectorAll('.ac-item')[0].dispatchEvent(new window.MouseEvent('mousedown', { cancelable: true }));
    expect(ac.getValues()).toEqual(['E1', 'E2', 'E3']);
    input.value = 'E3';
    keydown(input, 'Enter');
    expect(ac.getValues()).toEqual(['E1', 'E2', 'E3']);
    input.value = '';
    keydown(input, 'Backspace');
    expect(ac.getValues()).toEqual(['E1', 'E2']);
    input.value = 'typing';
    keydown(input, 'Backspace');
    expect(ac.getValues()).toEqual(['E1', 'E2']);
    keydown(input, 'Escape');
    expect(dropdown.classList.contains('is-hidden')).toBe(true);
    input.dispatchEvent(new window.Event('focus'));
    await vi.advanceTimersByTimeAsync(200);
    vi.clearAllTimers();
    dropdown.classList.remove('is-hidden');
    input.dispatchEvent(new window.Event('blur'));
    await vi.advanceTimersByTimeAsync(200);
    expect(dropdown.classList.contains('is-hidden')).toBe(true);
    chips.querySelector('.ac-chip-remove').dispatchEvent(new window.MouseEvent('click'));
    expect(ac.getValues()).toEqual(['E2']);
    expect(onChange).toHaveBeenCalled();
  });
  /**
   * An empty suggestion set shows the "no match" note and a blank input is not added.
   */
  it('shows the empty note and ignores a blank Enter', async () => {
    fetchYields([]);
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/products', multiple: true, values: [], onChange: vi.fn() });
    const input = ac.node.querySelector('.ac-input');
    const dropdown = ac.node.querySelector('.ac-dropdown');
    input.dispatchEvent(new window.Event('input'));
    await vi.advanceTimersByTimeAsync(200);
    expect(dropdown.querySelector('.ac-empty')).not.toBeNull();
    input.value = '   ';
    keydown(input, 'Enter');
    expect(ac.getValues()).toEqual([]);
  });
  /**
   * A single-value field seeds the input, updates on typing, and clears when emptied.
   */
  it('drives the single-value input', async () => {
    const onChange = vi.fn();
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/stores', multiple: false, values: ['S1'], placeholder: 'p', onChange: onChange });
    const input = ac.node.querySelector('.ac-input');
    expect(input.value).toBe('S1');
    expect(input.placeholder).toBe('p');
    input.value = 'S2';
    input.dispatchEvent(new window.Event('input'));
    expect(ac.getValue()).toBe('S2');
    input.value = '';
    input.dispatchEvent(new window.Event('input'));
    expect(ac.getValue()).toBe('');
    await vi.advanceTimersByTimeAsync(200);
    expect(onChange).toHaveBeenCalled();
  });
  /**
   * A rejected label-resolve request leaves the values usable and unresolved.
   */
  it('tolerates a failed label resolve', async () => {
    globalThis.fetch.mockImplementation(() => Promise.reject(new Error('down')));
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/products', multiple: true, values: ['E9'], onChange: vi.fn() });
    await microflush();
    expect(ac.getValues()).toEqual(['E9']);
  });
  /**
   * A non-ok label-resolve response is treated as no labels.
   */
  it('treats a non-ok label resolve as unresolved', async () => {
    globalThis.fetch.mockImplementation(() => Promise.resolve({ ok: false, json: () => Promise.resolve([]) }));
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/products', multiple: true, values: ['E9'], onChange: vi.fn() });
    await microflush();
    expect(ac.node.querySelectorAll('.ac-chip-unknown').length).toBe(1);
  });
  /**
   * Adding a duplicate value in multiple mode is a no-op.
   */
  it('ignores a duplicate value', () => {
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/stores', multiple: true, values: ['A'], onChange: vi.fn() });
    const input = ac.node.querySelector('.ac-input');
    input.value = 'A';
    keydown(input, 'Enter');
    expect(ac.getValues()).toEqual(['A']);
  });
  /**
   * With no seed values the selection starts empty (the falsy values fallback).
   */
  it('starts empty when no seed values are given', () => {
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/stores', multiple: true, onChange: vi.fn() });
    expect(ac.getValues()).toEqual([]);
  });
  /**
   * A single field commits a typed value on Enter and ignores a blank Enter.
   */
  it('commits a single value on Enter and ignores a blank one', () => {
    const ac = view.buildAutocomplete({ endpoint: '/ui/lookup/stores', multiple: false, values: [], onChange: vi.fn() });
    const input = ac.node.querySelector('.ac-input');
    input.value = 'S9';
    keydown(input, 'Enter');
    expect(ac.getValue()).toBe('S9');
    expect(input.value).toBe('S9');
    input.value = '';
    keydown(input, 'Enter');
    expect(ac.getValue()).toBe('S9');
  });
});

describe('buildLookupControl', () => {
  /**
   * A multiple control seeded from an array reads back the array, or undefined when empty.
   */
  it('reads a multiple control', () => {
    expect(view.buildLookupControl('ean-list', ['A', 'B'], true, vi.fn()).read()).toEqual(['A', 'B']);
    expect(view.buildLookupControl('ean-list', undefined, true, vi.fn()).read()).toBeUndefined();
  });
  /**
   * A single control seeded from a scalar reads it back, or undefined when blank.
   */
  it('reads a single control', () => {
    expect(view.buildLookupControl('store-code', 'S1', false, vi.fn()).read()).toBe('S1');
    expect(view.buildLookupControl('store-code', '', false, vi.fn()).read()).toBeUndefined();
  });
});

describe('buildEnumControl', () => {
  /**
   * The matching option is preselected and read back; a missing enum yields undefined.
   */
  it('preselects and reads the current option', () => {
    const onChange = vi.fn();
    const ctrl = view.buildEnumControl({ enum: ['A', 'B'] }, 'B', onChange);
    expect(ctrl.node.value).toBe('B');
    expect(ctrl.read()).toBe('B');
    ctrl.node.value = '';
    ctrl.node.dispatchEvent(new window.Event('change'));
    expect(ctrl.read()).toBeUndefined();
    expect(onChange).toHaveBeenCalled();
    expect(view.buildEnumControl({}, 'x', vi.fn()).node.querySelectorAll('option').length).toBe(1);
  });
});

describe('buildBooleanControl', () => {
  /**
   * A true value checks the box and reads back true; anything else reads false.
   */
  it('reflects and reads the checked state', () => {
    const onChange = vi.fn();
    const checked = view.buildBooleanControl(true, onChange);
    expect(checked.read()).toBe(true);
    const unchecked = view.buildBooleanControl(false, onChange);
    expect(unchecked.read()).toBe(false);
    unchecked.node.querySelector('input').dispatchEvent(new window.Event('change'));
    expect(onChange).toHaveBeenCalled();
  });
});

describe('buildNumberControl', () => {
  /**
   * A money control keeps the value as-is, carries its unit, and reads a float back.
   */
  it('handles a money control', () => {
    const ctrl = view.buildNumberControl('money', { minimum: 0, maximum: 100 }, 9.99, vi.fn());
    const input = ctrl.node.querySelector('input');
    expect(input.value).toBe('9.99');
    expect(input.min).toBe('0');
    expect(input.max).toBe('100');
    expect(ctrl.node.querySelector('.input-suffix').textContent).toBe('€');
    expect(ctrl.read()).toBe(9.99);
  });
  /**
   * A rate control scales the fraction to a percentage for display and back on read.
   */
  it('scales a rate between fraction and percentage', () => {
    const ctrl = view.buildNumberControl('rate', { minimum: 0, maximum: 1 }, 0.2, vi.fn());
    const input = ctrl.node.querySelector('input');
    expect(input.value).toBe('20');
    expect(input.min).toBe('0');
    expect(input.max).toBe('100');
    expect(ctrl.read()).toBe(0.2);
  });
  /**
   * An integer control uses a step of one and an incremented exclusive minimum.
   */
  it('handles an integer control with an exclusive minimum', () => {
    const ctrl = view.buildNumberControl('integer', { exclusiveMinimum: 0 }, 3, vi.fn());
    const input = ctrl.node.querySelector('input');
    expect(input.step).toBe('1');
    expect(input.min).toBe('1');
    expect(ctrl.read()).toBe(3);
  });
  /**
   * A rate control derives its exclusive minimum by scaling rather than incrementing.
   */
  it('scales an exclusive minimum for a rate', () => {
    const ctrl = view.buildNumberControl('rate', { exclusiveMinimum: 0.1 }, undefined, vi.fn());
    expect(ctrl.node.querySelector('input').min).toBe('10');
  });
  /**
   * A percent control needs no scaling and shows the percent unit.
   */
  it('handles a percent control', () => {
    const ctrl = view.buildNumberControl('percent', {}, 5, vi.fn());
    expect(ctrl.node.querySelector('.input-suffix').textContent).toBe('%');
    expect(ctrl.read()).toBe(5);
  });
  /**
   * A control with no schema and no value renders bare and reads undefined when emptied.
   */
  it('handles a missing schema, unit and value', () => {
    const onChange = vi.fn();
    const ctrl = view.buildNumberControl('number', null, undefined, onChange);
    const input = ctrl.node.querySelector('input');
    expect(ctrl.node.querySelector('.input-suffix')).toBeNull();
    expect(input.value).toBe('');
    input.dispatchEvent(new window.Event('input'));
    expect(onChange).toHaveBeenCalled();
    expect(ctrl.read()).toBeUndefined();
  });
  /**
   * A non-numeric entry reads back undefined (the isNaN guard), forced via a text input.
   */
  it('reads undefined on a non-numeric entry', () => {
    const ctrl = view.buildNumberControl('number', {}, undefined, vi.fn());
    const input = ctrl.node.querySelector('input');
    input.type = 'text';
    input.value = 'abc';
    expect(ctrl.read()).toBeUndefined();
  });
  /**
   * A widget whose type is integer but not a counting widget still reads an integer.
   */
  it('treats a schema integer type as an integer control', () => {
    const ctrl = view.buildNumberControl('number', { type: 'integer' }, 4, vi.fn());
    expect(ctrl.node.querySelector('input').step).toBe('1');
    expect(ctrl.read()).toBe(4);
  });
});

describe('buildTextControl', () => {
  /**
   * A text control applies minLength, seeds the value, and reads it back.
   */
  it('applies minLength and reads the value', () => {
    const onChange = vi.fn();
    const ctrl = view.buildTextControl({ minLength: 3 }, 'hi', onChange);
    const input = ctrl.node;
    expect(input.minLength).toBe(3);
    expect(ctrl.read()).toBe('hi');
    input.dispatchEvent(new window.Event('input'));
    expect(onChange).toHaveBeenCalled();
  });
  /**
   * A control with no schema and no value reads undefined.
   */
  it('reads undefined for an empty control', () => {
    expect(view.buildTextControl(null, undefined, vi.fn()).read()).toBeUndefined();
    expect(view.buildTextControl(null, '', vi.fn()).read()).toBeUndefined();
  });
});

describe('buildStringListControl', () => {
  /**
   * An array seeds the rows; adding, editing and removing update the read-back list.
   */
  it('adds, edits, removes and reads a string list', () => {
    const onChange = vi.fn();
    const ctrl = view.buildStringListControl(['a', 'b'], onChange);
    const rows = () => ctrl.node.querySelectorAll('.repeater-row');
    expect(rows().length).toBe(2);
    ctrl.node.querySelector('.btn-danger').dispatchEvent(new window.MouseEvent('click'));
    expect(rows().length).toBe(1);
    ctrl.node.querySelector('.btn:not(.btn-danger)').dispatchEvent(new window.MouseEvent('click'));
    const inputs = ctrl.node.querySelectorAll('input');
    inputs[0].value = ' b ';
    inputs[0].dispatchEvent(new window.Event('input'));
    inputs[1].value = '   ';
    inputs[1].dispatchEvent(new window.Event('input'));
    expect(ctrl.read()).toEqual(['b']);
    expect(onChange).toHaveBeenCalled();
  });
  /**
   * A scalar value seeds a single row; a falsy value seeds none and reads undefined.
   */
  it('seeds from a scalar and reads undefined when empty', () => {
    expect(view.buildStringListControl('x', vi.fn()).read()).toEqual(['x']);
    expect(view.buildStringListControl(null, vi.fn()).read()).toBeUndefined();
  });
});

describe('buildEanQuantityMapControl', () => {
  /**
   * An object seeds rows; adding, removing and reading reflect the entries.
   */
  it('seeds, edits and reads a map', async () => {
    fetchYields([]);
    const onChange = vi.fn();
    const ctrl = view.buildEanQuantityMapControl({ E9: 2 }, onChange);
    await microflush();
    expect(ctrl.node.querySelectorAll('.repeater-object-row').length).toBe(1);
    expect(ctrl.read()).toEqual({ E9: 2 });
    const eanInput = ctrl.node.querySelector('.ac-input');
    eanInput.value = 'E8';
    eanInput.dispatchEvent(new window.Event('input'));
    const qtyInput = ctrl.node.querySelector('.input-unit input');
    qtyInput.value = '5';
    qtyInput.dispatchEvent(new window.Event('input'));
    expect(ctrl.read()).toEqual({ E8: 5 });
    ctrl.node.querySelector('.btn:not(.btn-danger)').dispatchEvent(new window.MouseEvent('click'));
    expect(ctrl.read()).toEqual({ E8: 5 });
    ctrl.node.querySelectorAll('.btn-danger')[1].dispatchEvent(new window.MouseEvent('click'));
    expect(ctrl.node.querySelectorAll('.repeater-object-row').length).toBe(1);
    expect(onChange).toHaveBeenCalled();
  });
  /**
   * A non-object value seeds no rows and reads undefined.
   */
  it('reads undefined when there is no entry', () => {
    expect(view.buildEanQuantityMapControl(null, vi.fn()).read()).toBeUndefined();
  });
});

describe('buildObjectControl', () => {
  /**
   * A nested object reads back only its filled children.
   */
  it('reads the filled children', () => {
    const schema = { properties: { a: { type: 'string' }, b: { type: 'integer' } }, required: ['a'] };
    const onChange = vi.fn();
    const ctrl = view.buildObjectControl(schema, { a: 'x' }, onChange);
    expect(ctrl.read()).toEqual({ a: 'x' });
    const input = ctrl.node.querySelector('input.input-control');
    input.value = 'y';
    input.dispatchEvent(new window.Event('input'));
    expect(onChange).toHaveBeenCalled();
    expect(ctrl.read()).toEqual({ a: 'y' });
  });
  /**
   * A null schema and an empty value read back undefined.
   */
  it('reads undefined for an empty object', () => {
    expect(view.buildObjectControl(null, null, vi.fn()).read()).toBeUndefined();
  });
});

describe('buildObjectListControl', () => {
  /**
   * A seeded object list reads back its rows and reacts to field edits and removal.
   */
  it('edits fields and reads the rows', () => {
    const schema = { items: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] }, 'x-item-label': 'tier' };
    const ctrl = view.buildObjectListControl(schema, [{ name: 'A' }], vi.fn());
    const input = ctrl.node.querySelector('input.input-control');
    input.value = 'B';
    input.dispatchEvent(new window.Event('input'));
    expect(ctrl.read()).toEqual([{ name: 'B' }]);
    input.value = '';
    input.dispatchEvent(new window.Event('input'));
    expect(ctrl.read()).toEqual([{}]);
    ctrl.node.querySelector('.btn-danger').dispatchEvent(new window.MouseEvent('click'));
    expect(ctrl.read()).toBeUndefined();
  });
  /**
   * A null schema uses defaults; adding a row makes read return the rows.
   */
  it('adds a row with a default schema', () => {
    const ctrl = view.buildObjectListControl(null, undefined, vi.fn());
    expect(ctrl.read()).toBeUndefined();
    ctrl.node.querySelector('.btn:not(.btn-danger)').dispatchEvent(new window.MouseEvent('click'));
    expect(ctrl.read()).toEqual([{}]);
  });
  /**
   * An item schema declaring no properties still renders a bare row.
   */
  it('renders rows for an item schema without properties', () => {
    const ctrl = view.buildObjectListControl({ items: { type: 'object' } }, [{}], vi.fn());
    expect(ctrl.node.querySelectorAll('.repeater-object-row').length).toBe(1);
    expect(ctrl.read()).toEqual([{}]);
  });
});

describe('buildField', () => {
  /**
   * A wide widget adds the wide class and a required field shows the asterisk and hint.
   */
  it('marks a wide, required, documented field', () => {
    const field = view.buildField('tags', { type: 'array', items: { type: 'string' }, description: 'note' }, ['t'], true, vi.fn());
    expect(field.node.classList.contains('field-wide')).toBe(true);
    expect(field.node.querySelector('.field-label em').textContent).toBe('*');
    expect(field.node.querySelector('.field-hint').textContent).toBe('note');
  });
  /**
   * A narrow optional field with no schema has no wide class, asterisk or hint.
   */
  it('leaves a narrow optional field bare', () => {
    const field = view.buildField('title', { type: 'string' }, undefined, false, vi.fn());
    expect(field.node.classList.contains('field-wide')).toBe(false);
    expect(field.node.querySelector('.field-label em')).toBeNull();
    expect(field.node.querySelector('.field-hint')).toBeNull();
  });
});

describe('buildControl', () => {
  /**
   * Each widget identifier routes to a control with the expected root shape.
   */
  it('routes every widget to its control', async () => {
    fetchYields([]);
    expect(view.buildControl('ean', 'ean', {}, undefined, vi.fn()).node.classList.contains('ac')).toBe(true);
    expect(view.buildControl('ean-list', 'eans', {}, undefined, vi.fn()).node.classList.contains('ac-multi')).toBe(true);
    expect(view.buildControl('enum', 'e', { enum: ['A'] }, undefined, vi.fn()).node.tagName).toBe('SELECT');
    expect(view.buildControl('boolean', 'b', {}, true, vi.fn()).node.classList.contains('toggle')).toBe(true);
    expect(view.buildControl('object-list', 'l', {}, [], vi.fn()).node.classList.contains('repeater-object')).toBe(true);
    expect(view.buildControl('object', 'o', {}, {}, vi.fn()).node.classList.contains('nested-object')).toBe(true);
    expect(view.buildControl('ean-quantity-map', 'v', {}, {}, vi.fn()).node.classList.contains('repeater-object')).toBe(true);
    expect(view.buildControl('string-list', 's', {}, [], vi.fn()).node.classList.contains('repeater')).toBe(true);
    expect(view.buildControl('discount-value', 'd', {}, 1, vi.fn()).node.classList.contains('input-unit')).toBe(true);
    expect(view.buildControl('text', 't', {}, 'x', vi.fn()).node.classList.contains('input-control')).toBe(true);
    await microflush();
  });
});

describe('form wiring (init, renderForm, readForm, syncHidden, validate, onFieldChange)', () => {
  /**
   * Restores a clean, form-mode module state before each wiring case.
   */
  beforeEach(() => {
    fetchYields([]);
    els.schemasScript.textContent = JSON.stringify(SCHEMAS);
    els.specScript.textContent = JSON.stringify({});
    els.rawTa.value = '{}';
    els.select.value = '';
    view.switchMode('form');
    view.init();
  });
  /**
   * An empty type shows the pick-a-type placeholder and clears the hidden input.
   */
  it('renders the pick-a-type placeholder for an empty type', () => {
    view.renderForm();
    expect(els.schemaForm.querySelector('.placeholder-note').textContent).toContain('Select an offer type');
    expect(els.hidden.value).toBe('{}');
  });
  /**
   * A type with no registered schema shows the no-schema placeholder.
   */
  it('renders the no-schema placeholder for an unknown type', () => {
    els.select.value = 'NOSCHEMA';
    view.renderForm();
    expect(els.schemaForm.querySelector('.placeholder-note').textContent).toContain('No schema is registered');
  });
  /**
   * A valid type builds a field per property and flags the missing required one.
   */
  it('builds the full form and flags the missing required field', async () => {
    els.select.value = 'RICH';
    view.renderForm();
    await microflush();
    expect(els.schemaForm.querySelector('.field-grid').children.length).toBe(Object.keys(SCHEMAS.RICH.properties).length);
    expect(els.errors.classList.contains('is-hidden')).toBe(false);
    expect(els.errors.textContent).toContain('required');
  });
  /**
   * With every required field supplied, validation hides the error panel.
   */
  it('hides the error panel when nothing is missing', async () => {
    els.specScript.textContent = JSON.stringify(RICH_SPEC);
    view.init();
    els.select.value = 'RICH';
    view.renderForm();
    await microflush();
    expect(els.errors.classList.contains('is-hidden')).toBe(true);
  });
  /**
   * readForm keeps only the fields that read a defined value.
   */
  it('reads only the defined fields', async () => {
    els.specScript.textContent = JSON.stringify(RICH_SPEC);
    view.init();
    els.select.value = 'RICH';
    view.renderForm();
    await microflush();
    const read = view.readForm();
    expect(read.label).toBe('Hello');
    expect(read.taxRate).toBe(0.2);
    expect(Object.prototype.hasOwnProperty.call(read, 'eans')).toBe(false);
  });
  /**
   * syncHidden serialises the form in form mode and copies the textarea in json mode.
   */
  it('syncs the hidden input in both modes', () => {
    els.select.value = 'MINI';
    view.renderForm();
    view.syncHidden();
    expect(els.hidden.value).toBe('{}');
    view.switchMode('json');
    els.rawTa.value = '{"a":1}';
    view.syncHidden();
    expect(els.hidden.value).toBe('{"a":1}');
  });
  /**
   * validate hides the panel when there is no schema and when in json mode.
   */
  it('hides validation without a schema or in json mode', () => {
    els.select.value = '';
    view.validate();
    expect(els.errors.classList.contains('is-hidden')).toBe(true);
    els.select.value = 'RICH';
    view.switchMode('json');
    view.validate();
    expect(els.errors.classList.contains('is-hidden')).toBe(true);
  });
  /**
   * onFieldChange syncs the hidden input and runs validation together.
   */
  it('syncs and validates on a field change', () => {
    els.select.value = 'MINI';
    view.renderForm();
    view.onFieldChange();
    expect(els.hidden.value).toBe('{}');
    expect(els.errors.classList.contains('is-hidden')).toBe(true);
  });
  /**
   * A present but non-array required value covers the array guard's false arm.
   */
  it('treats a present scalar required value as satisfied', async () => {
    els.schemasScript.textContent = JSON.stringify({ ONE: { type: 'object', required: ['title'], properties: { title: { type: 'string' } } } });
    els.specScript.textContent = JSON.stringify({ title: 'ok' });
    const sel = els.select;
    sel.appendChild(document.createElement('option')).value = 'ONE';
    view.init();
    sel.value = 'ONE';
    view.renderForm();
    await microflush();
    expect(els.errors.classList.contains('is-hidden')).toBe(true);
  });
  /**
   * Malformed embedded schemas and specification fall back to empty objects.
   */
  it('falls back to empty state on malformed embedded JSON', () => {
    els.schemasScript.textContent = 'not json';
    els.specScript.textContent = 'also not json';
    expect(() => view.init()).not.toThrow();
    els.select.value = 'RICH';
    view.renderForm();
    expect(els.schemaForm.querySelector('.placeholder-note').textContent).toContain('No schema is registered');
  });
  /**
   * Empty embedded scripts parse as empty objects rather than failing.
   */
  it('parses empty embedded JSON as empty objects', () => {
    els.schemasScript.textContent = '';
    els.specScript.textContent = '';
    expect(() => view.init()).not.toThrow();
    els.select.value = 'RICH';
    view.renderForm();
    expect(els.schemaForm.querySelector('.placeholder-note').textContent).toContain('No schema is registered');
  });
  /**
   * A schema that declares a required field but no properties still reports it missing.
   */
  it('reports a required field on a schema with no properties', () => {
    els.schemasScript.textContent = JSON.stringify({ NOPROPS: { type: 'object', required: ['x'] } });
    els.select.appendChild(document.createElement('option')).value = 'NOPROPS';
    view.init();
    els.select.value = 'NOPROPS';
    view.renderForm();
    expect(els.errors.classList.contains('is-hidden')).toBe(false);
    expect(els.errors.textContent).toContain('X is required');
  });
  /**
   * A filled array-valued required field satisfies validation (the array guard is reached).
   */
  it('counts a filled array required value as satisfied', async () => {
    els.schemasScript.textContent = JSON.stringify({ ARR: { type: 'object', required: ['tags'], properties: { tags: { oneOf: [{ const: 'x' }] } } } });
    els.specScript.textContent = JSON.stringify({ tags: ['t1'] });
    els.select.appendChild(document.createElement('option')).value = 'ARR';
    view.init();
    els.select.value = 'ARR';
    view.renderForm();
    await microflush();
    expect(els.errors.classList.contains('is-hidden')).toBe(true);
  });
  /**
   * Changing the type in form mode captures the current values and rebuilds the form.
   */
  it('rebuilds the form when the type changes in form mode', () => {
    els.select.value = 'MINI';
    els.select.dispatchEvent(new window.Event('change'));
    expect(els.schemaForm.querySelector('.field-grid')).not.toBeNull();
    expect(els.schemaForm.textContent).toContain('Title');
  });
  /**
   * Changing the type in json mode keeps the specification rather than re-reading the form.
   */
  it('keeps the specification when the type changes in json mode', () => {
    view.switchMode('json');
    els.select.value = 'MINI';
    els.select.dispatchEvent(new window.Event('change'));
    els.rawTa.value = '{}';
    view.switchMode('form');
    expect(els.schemaForm.querySelector('.field-grid')).not.toBeNull();
  });
  /**
   * Clicking a mode-switch button toggles the editor through its click listener.
   */
  it('switches mode when a mode-switch button is clicked', () => {
    els.select.value = 'MINI';
    view.renderForm();
    els.bJson.dispatchEvent(new window.MouseEvent('click'));
    expect(els.jsonEditor.classList.contains('is-hidden')).toBe(false);
    els.rawTa.value = '{}';
    els.bForm.dispatchEvent(new window.MouseEvent('click'));
    expect(els.schemaForm.classList.contains('is-hidden')).toBe(false);
  });
});

describe('switchMode', () => {
  /**
   * Restores a clean, form-mode module state before each case.
   */
  beforeEach(() => {
    fetchYields([]);
    els.schemasScript.textContent = JSON.stringify(SCHEMAS);
    els.specScript.textContent = JSON.stringify({});
    els.rawTa.value = '{}';
    els.select.value = 'MINI';
    view.switchMode('form');
    view.init();
    els.select.value = 'MINI';
    view.renderForm();
  });
  /**
   * Switching to the current mode is a no-op.
   */
  it('does nothing when the target is the current mode', () => {
    view.switchMode('form');
    expect(els.jsonEditor.classList.contains('is-hidden')).toBe(true);
  });
  /**
   * Switching to json serialises the form, reveals the editor and marks the tab active.
   */
  it('switches to json and back to form', () => {
    view.switchMode('json');
    expect(els.jsonEditor.classList.contains('is-hidden')).toBe(false);
    expect(els.schemaForm.classList.contains('is-hidden')).toBe(true);
    expect(els.bJson.classList.contains('is-active')).toBe(true);
    expect(els.bForm.classList.contains('is-active')).toBe(false);
    els.rawTa.value = '{"title":"z"}';
    view.switchMode('form');
    expect(els.schemaForm.classList.contains('is-hidden')).toBe(false);
    expect(els.bForm.classList.contains('is-active')).toBe(true);
  });
  /**
   * A blank editor defaults to an empty object when switching back to the form.
   */
  it('defaults a blank editor to an empty object', () => {
    view.switchMode('json');
    els.rawTa.value = '';
    view.switchMode('form');
    expect(els.schemaForm.classList.contains('is-hidden')).toBe(false);
  });
  /**
   * Malformed JSON blocks the switch back to the form and reports the error.
   */
  it('blocks the switch on malformed json', () => {
    view.switchMode('json');
    els.rawTa.value = '{bad';
    view.switchMode('form');
    expect(els.errors.classList.contains('is-hidden')).toBe(false);
    expect(els.errors.textContent).toContain('malformed');
    expect(els.jsonEditor.classList.contains('is-hidden')).toBe(false);
    els.rawTa.value = '{}';
    view.switchMode('form');
  });
});

describe('initTargetWidgets', () => {
  /**
   * Removes any target-widget hosts added by a previous case.
   */
  afterEach(() => {
    document.querySelectorAll('[data-widget]').forEach((n) => n.remove());
  });
  /**
   * Each host gets an autocomplete and a hidden input seeded from its comma list.
   */
  it('wires the declared target pickers', () => {
    fetchYields([]);
    const withValues = document.createElement('div');
    withValues.setAttribute('data-widget', 'store-code');
    withValues.setAttribute('data-name', 'storeCodes');
    withValues.setAttribute('data-value', 'S1, ,S2');
    const withoutValues = document.createElement('div');
    withoutValues.setAttribute('data-widget', 'store-group-code');
    withoutValues.setAttribute('data-name', 'groups');
    document.body.appendChild(withValues);
    document.body.appendChild(withoutValues);
    view.initTargetWidgets();
    const hidden = withValues.querySelector('input[type="hidden"]');
    expect(hidden.value).toBe('S1,S2');
    expect(withValues.querySelector('.ac')).not.toBeNull();
    expect(withoutValues.querySelector('input[type="hidden"]').value).toBe('');
  });
  /**
   * Editing a picker updates its hidden input through the onChange callback.
   */
  it('updates the hidden input on change', () => {
    fetchYields([]);
    const host = document.createElement('div');
    host.setAttribute('data-widget', 'store-code');
    host.setAttribute('data-name', 'storeCodes');
    host.setAttribute('data-value', 'S1,S2');
    document.body.appendChild(host);
    view.initTargetWidgets();
    const hidden = host.querySelector('input[type="hidden"]');
    host.querySelector('.ac-chip-remove').dispatchEvent(new window.MouseEvent('click'));
    expect(hidden.value).toBe('S2');
  });
});

describe('init boot dispatcher', () => {
  /**
   * A still-loading document defers init to DOMContentLoaded instead of running it now.
   */
  it('defers to DOMContentLoaded while the document is loading', async () => {
    const original = Object.getOwnPropertyDescriptor(Document.prototype, 'readyState');
    Object.defineProperty(document, 'readyState', { configurable: true, get: () => 'loading' });
    const addSpy = vi.spyOn(document, 'addEventListener');
    await loadScript(PATH, { bust: 'loading' });
    expect(addSpy.mock.calls.some((c) => c[0] === 'DOMContentLoaded')).toBe(true);
    addSpy.mockRestore();
    if (original) {
      Object.defineProperty(document, 'readyState', { configurable: true, get: () => 'complete' });
    }
  });
});
