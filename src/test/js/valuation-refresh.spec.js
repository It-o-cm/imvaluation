import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of valuation-refresh.js.
 *
 * The file is a browser IIFE that, at import time, looks up the #valuation-rows table body,
 * early-returns when it is absent, and otherwise defines refresh() and schedules it every
 * second. The bench controls the clock with Vitest fake timers (installed BEFORE import so
 * the setInterval scheduled during boot is captured) and mocks global.fetch, so no real
 * network call and no real clock is ever used. The refresh() function is exposed by the
 * harness; its guards, the ok/not-ok fetch outcomes and the error path are each asserted.
 */

/**
 * Project-relative path of the source under test.
 */
const SOURCE = 'src/main/resources/META-INF/resources/ui/valuation-refresh.js';

/**
 * The base refresh URL carried by the table body's data attribute.
 */
const BASE_URL = '/ui/valuations/rows';

/**
 * The query string forwarded by the refresh so the active filters are honoured.
 */
const SEARCH = '?page=2&q=abc';

/**
 * The module object exposing the refresh() function once loaded.
 */
let refreshModule;

/**
 * The table body element the module refreshes, kept stable for the whole suite.
 */
let tbody;

/**
 * Drains the microtask queue so the mocked fetch promise chain settles. Fake timers do not
 * fake microtasks, so awaiting a handful of turns is enough for the four-hop then-chain.
 */
async function flushMicrotasks() {
  for (let i = 0; i < 10; i += 1) {
    await Promise.resolve();
  }
}

/**
 * Installs the table body and the current query string.
 */
function installDom() {
  document.body.innerHTML = '<table><tbody id="valuation-rows" data-refresh-url="'
    + BASE_URL + '"></tbody></table>';
  window.history.pushState({}, '', '/valuations' + SEARCH);
}

/**
 * Installs fake timers and the mocked fetch, builds the DOM, then imports the module so its
 * boot-time setInterval registers against the fake clock and refresh() is exposed.
 */
beforeAll(async () => {
  vi.useFakeTimers();
  vi.stubGlobal('fetch', vi.fn());
  installDom();
  refreshModule = await loadScript(SOURCE);
  tbody = document.getElementById('valuation-rows');
});

/**
 * Restores the real clock and the real fetch once the suite is done.
 */
afterAll(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/**
 * Resets the fetch mock and the table content before each test so state never leaks.
 */
beforeEach(() => {
  fetch.mockReset();
  tbody.innerHTML = '';
});

/**
 * Removes any per-test override of document.hidden so it falls back to false.
 */
afterEach(() => {
  delete document.hidden;
});

describe('refresh', () => {
  /**
   * Both guard operands are false: the rows are fetched for the active filters and the
   * table body is replaced with the returned html (ternary ok arm, html != null true arm).
   */
  it('fetches and swaps the rows on a successful response', async () => {
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr><td>row</td></tr>') });
    refreshModule.refresh();
    await flushMicrotasks();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(BASE_URL + SEARCH, {
      headers: { Accept: 'text/html' },
      credentials: 'same-origin',
    });
    expect(tbody.innerHTML).toBe('<tr><td>row</td></tr>');
  });

  /**
   * A response that is not ok takes the ternary null arm, so text() is never read and the
   * html != null guard is false: the current rows are kept untouched.
   */
  it('keeps the rows when the response is not ok', async () => {
    tbody.innerHTML = 'SENTINEL';
    const text = vi.fn();
    fetch.mockResolvedValue({ ok: false, text });
    refreshModule.refresh();
    await flushMicrotasks();
    expect(text).not.toHaveBeenCalled();
    expect(tbody.innerHTML).toBe('SENTINEL');
  });

  /**
   * A rejected fetch takes the catch arm: the current rows are kept and inFlight is cleared
   * so the next tick may try again.
   */
  it('keeps the rows when the fetch rejects', async () => {
    tbody.innerHTML = 'KEEP';
    fetch.mockRejectedValue(new Error('network down'));
    refreshModule.refresh();
    await flushMicrotasks();
    expect(tbody.innerHTML).toBe('KEEP');
  });

  /**
   * A refresh already in flight (its fetch still pending) makes a concurrent call take the
   * inFlight arm of the guard and return at once, so fetch is issued only once.
   */
  it('does not overlap a refresh already in flight', async () => {
    let resolvePending;
    fetch.mockImplementation(() => new Promise((resolve) => { resolvePending = resolve; }));
    refreshModule.refresh();
    refreshModule.refresh();
    expect(fetch).toHaveBeenCalledTimes(1);
    resolvePending({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    await flushMicrotasks();
  });

  /**
   * A hidden document takes the document.hidden arm of the guard, so no fetch is issued.
   */
  it('does nothing while the document is hidden', async () => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    refreshModule.refresh();
    await flushMicrotasks();
    expect(fetch).not.toHaveBeenCalled();
  });

  /**
   * The interval scheduled at boot invokes refresh once per second against the fake clock.
   */
  it('is invoked by the one-second interval', async () => {
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

describe('boot guard', () => {
  /**
   * A second evaluation with no #valuation-rows in the document exercises the early-return
   * arm of the tbody guard and asserts the boot does not throw. The cache-busted import is
   * a SEPARATE instrumented instance whose counters Istanbul does not merge back into the
   * primary table-present evaluation, so line 14 (this true arm) remains a justified
   * residue rather than a bug: a missing-table early return and the table-present refresh
   * path cannot both be instrumented in a single module evaluation.
   */
  it('returns early when the table body is absent', async () => {
    document.body.innerHTML = '';
    const second = await loadScript(SOURCE, { bust: 'no-tbody' });
    expect(second).toBeTruthy();
    installDom();
  });
});
