import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of valuation-view.js.
 *
 * The file is a browser IIFE whose top-level functions are exposed by the test harness.
 * The pure formatters (esc, euro, rate, amountCells, renderRequest, renderAdvantage,
 * renderResponse) are asserted to the exact character, both arms of every guard and
 * ternary exercised. The DOM wiring (build, wireToggle) is driven against a jsdom
 * document. Every value is absolute: no formatter is called to build its own expectation.
 */

/**
 * The source under test, exposing its top-level functions once loaded.
 */
let view;

/**
 * Loads the source once for the whole suite.
 */
beforeAll(async () => {
  view = await loadScript('src/main/resources/META-INF/resources/ui/valuation-view.js');
});

/**
 * Replaces the document body with the given markup.
 *
 * @param {string} html The markup to install.
 */
function setBody(html) {
  document.body.innerHTML = html;
}

/**
 * Shorthand for a single-element query on the current document.
 *
 * @param {string} selector The CSS selector.
 * @returns {Element|null} The first match, or null.
 */
function q(selector) {
  return document.querySelector(selector);
}

describe('esc', () => {
  /**
   * A null value takes the "" arm of the null-coalescing ternary.
   */
  it('maps null to an empty string', () => {
    expect(view.esc(null)).toBe('');
  });

  /**
   * An undefined value is == null and also takes the "" arm.
   */
  it('maps undefined to an empty string', () => {
    expect(view.esc(undefined)).toBe('');
  });

  /**
   * A non-null value takes the other arm and every entity is escaped, ampersand first.
   */
  it('escapes ampersand, lower-than and greater-than in order', () => {
    expect(view.esc('a<b>&c')).toBe('a&lt;b&gt;&amp;c');
  });

  /**
   * A number is coerced through String and returned as text.
   */
  it('stringifies a numeric value', () => {
    expect(view.esc(5)).toBe('5');
  });
});

describe('euro', () => {
  /**
   * A null value takes the left arm of the guard and yields the dash.
   */
  it('renders null as an em dash', () => {
    expect(view.euro(null)).toBe('&mdash;');
  });

  /**
   * NaN takes the right arm of the guard (value is not null) and yields the dash.
   */
  it('renders NaN as an em dash', () => {
    expect(view.euro(NaN)).toBe('&mdash;');
  });

  /**
   * A non-numeric string is isNaN and yields the dash.
   */
  it('renders a non-numeric string as an em dash', () => {
    expect(view.euro('abc')).toBe('&mdash;');
  });

  /**
   * A positive amount is fixed to two decimals with the euro sign.
   */
  it('formats a positive amount to the cent', () => {
    expect(view.euro(12.5)).toBe('12.50 &euro;');
  });

  /**
   * Zero is a valid amount and keeps two decimals.
   */
  it('formats zero to the cent', () => {
    expect(view.euro(0)).toBe('0.00 &euro;');
  });

  /**
   * A numeric string passes both arms of the guard and is formatted.
   */
  it('formats a numeric string to the cent', () => {
    expect(view.euro('1.5')).toBe('1.50 &euro;');
  });
});

describe('rate', () => {
  /**
   * A null rate takes the left arm of the guard and yields the dash.
   */
  it('renders null as an em dash', () => {
    expect(view.rate(null)).toBe('&mdash;');
  });

  /**
   * NaN takes the right arm of the guard and yields the dash.
   */
  it('renders NaN as an em dash', () => {
    expect(view.rate(NaN)).toBe('&mdash;');
  });

  /**
   * A fraction is scaled to a percentage with one decimal.
   */
  it('formats a fraction as a percentage', () => {
    expect(view.rate(0.2)).toBe('20.0%');
  });

  /**
   * Zero is a valid rate and keeps one decimal.
   */
  it('formats a zero rate', () => {
    expect(view.rate(0)).toBe('0.0%');
  });

  /**
   * A rate with a residual decimal keeps its single significant decimal.
   */
  it('formats a fractional percentage', () => {
    expect(view.rate(0.075)).toBe('7.5%');
  });
});

describe('amountCells', () => {
  /**
   * A missing amount takes the guard arm and yields three dash cells.
   */
  it('renders three dash cells when the amount is absent', () => {
    expect(view.amountCells(null)).toBe('<td>&mdash;</td><td>&mdash;</td><td>&mdash;</td>');
  });

  /**
   * A present amount yields the excl./incl./rate trio formatted to the cent.
   */
  it('renders the excl, incl and rate cells for a present amount', () => {
    const amount = { amountExcludingTax: 2, amountIncludingTax: 2.4, vatRate: 0.2 };
    expect(view.amountCells(amount)).toBe(
      '<td class="num">2.00 &euro;</td>'
      + '<td class="num">2.40 &euro;</td>'
      + '<td class="num rate">20.0%</td>',
    );
  });
});

describe('renderRequest', () => {
  /**
   * An empty basket has no header fields and no items, so only the placeholder shows.
   */
  it('renders the no-items placeholder for an empty basket', () => {
    expect(view.renderRequest({})).toBe('<p class="placeholder-note">No items.</p>');
  });

  /**
   * A single header field produces a summary with no separator, then the placeholder.
   */
  it('renders a one-field summary and the placeholder when there are no items', () => {
    expect(view.renderRequest({ customerCode: 'C1' })).toBe(
      '<p class="friendly-summary">Customer <strong>C1</strong></p>'
      + '<p class="placeholder-note">No items.</p>',
    );
  });

  /**
   * A full basket exercises all three header fields, the separator join, and every
   * gesture arm (forced, discount amount, discount percent, none) plus the missing-lineId
   * fallback, which is passed through esc so its ampersand is itself escaped.
   */
  it('renders the summary and every gesture variant for a full basket', () => {
    const basket = {
      storeCode: 'S1',
      customerCode: 'C1',
      deliveryMode: 'DRIVE',
      items: [
        { lineId: 'L1', produceEan: 'E1', quantity: 2, manualForcedPrice: 3.5 },
        { lineId: 'L2', produceEan: 'E2', quantity: 1, manualDiscountAmount: 0.5 },
        { produceEan: 'E3', quantity: 3, manualDiscountPercent: 10 },
        { lineId: 'L4', produceEan: 'E4', quantity: 1 },
      ],
    };
    expect(view.renderRequest(basket)).toBe(
      '<p class="friendly-summary">Store <strong>S1</strong> &middot; '
      + 'Customer <strong>C1</strong> &middot; <strong>DRIVE</strong></p>'
      + '<table class="friendly-table"><colgroup><col class="col-line"><col class="col-prod">'
      + '<col class="col-qty"><col class="col-gesture"></colgroup>'
      + '<thead><tr><th>Line</th><th>Product</th><th class="num">Qty</th><th>Gesture</th></tr></thead><tbody>'
      + '<tr><td>L1</td><td class="mono">E1</td><td class="num">2</td><td>Forced 3.50 &euro;</td></tr>'
      + '<tr><td>L2</td><td class="mono">E2</td><td class="num">1</td><td>-0.50 &euro;</td></tr>'
      + '<tr><td>&amp;mdash;</td><td class="mono">E3</td><td class="num">3</td><td>-10%</td></tr>'
      + '<tr><td>L4</td><td class="mono">E4</td><td class="num">1</td><td>&mdash;</td></tr>'
      + '</tbody></table>',
    );
  });
});

describe('renderAdvantage', () => {
  /**
   * A discount advantage with a type and an offer renders the discounted amount.
   */
  it('renders a discount advantage with its offer', () => {
    const adv = { type: 'OFFER_DISCOUNT', offer: 'OFF10', discountAmount: { amountIncludingTax: 1.5 } };
    expect(view.renderAdvantage(adv)).toBe(
      '<tr><td>OFFER_DISCOUNT</td><td>on OFF10</td><td class="num">-1.50 &euro;</td></tr>',
    );
  });

  /**
   * A discount advantage without a type falls back to "Advantage" and without an offer
   * to the empty string, covering the other arm of both "||" fallbacks.
   */
  it('falls back to default type and empty offer', () => {
    const adv = { discountAmount: { amountIncludingTax: 2 } };
    expect(view.renderAdvantage(adv)).toBe(
      '<tr><td>Advantage</td><td>on </td><td class="num">-2.00 &euro;</td></tr>',
    );
  });

  /**
   * A suggestion advantage renders the add-quantity sentence and a dash amount.
   */
  it('renders a suggestion advantage', () => {
    const adv = { type: 'UPSELL', suggestion: { quantity: 2, ean: 'E9', offerCode: 'U1' } };
    expect(view.renderAdvantage(adv)).toBe(
      '<tr><td>UPSELL</td><td>Add 2 &times; E9 for U1</td><td class="num">&mdash;</td></tr>',
    );
  });

  /**
   * A threshold advantage renders the eligible and threshold amounts.
   */
  it('renders a threshold advantage', () => {
    const adv = { type: 'THRESHOLD', totalEligibleAmount: 10, threshold: 20 };
    expect(view.renderAdvantage(adv)).toBe(
      '<tr><td>THRESHOLD</td><td>Eligible 10.00 &euro; (threshold 20.00 &euro;)</td>'
      + '<td class="num">&mdash;</td></tr>',
    );
  });

  /**
   * A zero eligible amount still takes the "!= null" arm rather than the default row.
   */
  it('treats a zero eligible amount as present', () => {
    const adv = { type: 'T0', totalEligibleAmount: 0, threshold: 5 };
    expect(view.renderAdvantage(adv)).toBe(
      '<tr><td>T0</td><td>Eligible 0.00 &euro; (threshold 5.00 &euro;)</td>'
      + '<td class="num">&mdash;</td></tr>',
    );
  });

  /**
   * An advantage matching no shape falls through to the default dash row.
   */
  it('renders a default row for an unknown advantage', () => {
    expect(view.renderAdvantage({ type: 'MISC' })).toBe(
      '<tr><td>MISC</td><td>&mdash;</td><td class="num">&mdash;</td></tr>',
    );
  });
});

describe('renderResponse', () => {
  /**
   * An empty response takes the "nothing to show" arm: no total, no offers, no advantages.
   */
  it('renders the nothing-to-show placeholder for an empty response', () => {
    expect(view.renderResponse({})).toBe('<p class="placeholder-note">Nothing to show.</p>');
  });

  /**
   * A full response exercises the total, an offer with items (present and absent amount),
   * an offer without amount or items, and an advantages table.
   */
  it('renders the total, offers and advantages for a full response', () => {
    const res = {
      totalPrice: { amountIncludingTax: 10.4, amountExcludingTax: 9.6 },
      offers: [
        {
          type: '2FOR1',
          amount: { amountIncludingTax: 2.5 },
          items: [
            {
              lineId: 'L1',
              produceEan: 'E1',
              quantity: 2,
              amount: { amountExcludingTax: 2, amountIncludingTax: 2.4, vatRate: 0.2 },
            },
            { produceEan: 'E2', quantity: 1 },
          ],
        },
        { type: 'EMPTY' },
      ],
      advantages: [{ type: 'MISC' }],
    };
    expect(view.renderResponse(res)).toBe(
      '<div class="friendly-total"><span class="friendly-total-label">Total</span>'
      + '<span class="friendly-total-value">10.40 &euro;</span>'
      + '<span class="friendly-total-ht">9.60 &euro; excl. tax</span></div>'
      + '<h3 class="friendly-head">Offers</h3>'
      + '<div class="friendly-offer"><div class="friendly-offer-head"><span>2FOR1</span>'
      + '<span class="num">2.50 &euro;</span></div>'
      + '<table class="friendly-table sub"><colgroup><col class="col-line"><col class="col-prod">'
      + '<col class="col-qty"><col class="col-amt"><col class="col-amt"><col class="col-vat"></colgroup>'
      + '<thead><tr><th>Line</th><th>Product</th><th class="num">Qty</th>'
      + '<th class="num">Excl.</th><th class="num">Incl.</th><th class="num">VAT</th></tr></thead><tbody>'
      + '<tr><td>L1</td><td class="mono">E1</td><td class="num">2</td>'
      + '<td class="num">2.00 &euro;</td><td class="num">2.40 &euro;</td><td class="num rate">20.0%</td></tr>'
      + '<tr><td>&amp;mdash;</td><td class="mono">E2</td><td class="num">1</td>'
      + '<td>&mdash;</td><td>&mdash;</td><td>&mdash;</td></tr>'
      + '</tbody></table></div>'
      + '<div class="friendly-offer"><div class="friendly-offer-head"><span>EMPTY</span>'
      + '<span class="num">&mdash;</span></div></div>'
      + '<h3 class="friendly-head">Advantages</h3>'
      + '<table class="friendly-table"><thead><tr><th>Type</th><th>Detail</th>'
      + '<th class="num">Amount</th></tr></thead><tbody>'
      + '<tr><td>MISC</td><td>&mdash;</td><td class="num">&mdash;</td></tr>'
      + '</tbody></table>',
    );
  });

  /**
   * A response with only a total renders the total and skips the nothing-to-show arm,
   * covering the "&& !res.totalPrice" false path of the final guard.
   */
  it('renders only the total when there are no offers or advantages', () => {
    const res = { totalPrice: { amountIncludingTax: 5, amountExcludingTax: 4.2 } };
    expect(view.renderResponse(res)).toBe(
      '<div class="friendly-total"><span class="friendly-total-label">Total</span>'
      + '<span class="friendly-total-value">5.00 &euro;</span>'
      + '<span class="friendly-total-ht">4.20 &euro; excl. tax</span></div>',
    );
  });
});

describe('build', () => {
  /**
   * Starts each case from an empty document.
   */
  beforeEach(() => {
    setBody('');
  });

  /**
   * A missing source element takes the left arm of the guard and leaves the panel alone.
   */
  it('does nothing when the source element is missing', () => {
    setBody('<div data-panel="request-friendly"></div>');
    view.build('request');
    expect(q("[data-panel='request-friendly']").innerHTML).toBe('');
  });

  /**
   * A missing panel takes the right arm of the guard and does not throw.
   */
  it('does nothing when the panel element is missing', () => {
    setBody('<div data-role="request-src">{}</div>');
    expect(() => view.build('request')).not.toThrow();
  });

  /**
   * A well-formed request payload is rendered into the friendly panel.
   */
  it('renders a request payload into the friendly panel', () => {
    setBody(
      '<div data-role="request-src">{"items":[]}</div>'
      + '<div data-panel="request-friendly"></div>',
    );
    view.build('request');
    expect(q("[data-panel='request-friendly']").innerHTML)
      .toBe('<p class="placeholder-note">No items.</p>');
  });

  /**
   * The "response" kind takes the other arm of the render ternary.
   */
  it('renders a response payload into the friendly panel', () => {
    setBody(
      '<div data-role="response-src">{}</div>'
      + '<div data-panel="response-friendly"></div>',
    );
    view.build('response');
    expect(q("[data-panel='response-friendly']").innerHTML)
      .toBe('<p class="placeholder-note">Nothing to show.</p>');
  });

  /**
   * Malformed JSON takes the catch arm and shows the read-error note.
   */
  it('shows a read-error note on malformed JSON', () => {
    setBody(
      '<div data-role="request-src">{not json}</div>'
      + '<div data-panel="request-friendly"></div>',
    );
    view.build('request');
    expect(q("[data-panel='request-friendly']").innerHTML)
      .toBe('<p class="placeholder-note">Could not read this payload. Use the JSON tab.</p>');
  });
});

describe('wireToggle', () => {
  /**
   * Starts each case from an empty document.
   */
  beforeEach(() => {
    setBody('');
  });

  /**
   * Clicking the JSON tab hides the friendly panel and shows the JSON panel, and marks the
   * clicked tab active while clearing its sibling; clicking the friendly tab reverses it.
   * Two tabs share the target so both arms of the "t === tab" toggle are exercised.
   */
  it('toggles panels and active state between friendly and json', () => {
    setBody(
      '<button class="view-tab is-active" data-target="request" data-view="friendly">R</button>'
      + '<button class="view-tab" data-target="request" data-view="json">J</button>'
      + '<div data-panel="request-friendly"></div>'
      + '<div data-panel="request-json" class="is-hidden"></div>',
    );
    view.wireToggle();
    const friendlyTab = q('.view-tab[data-view="friendly"]');
    const jsonTab = q('.view-tab[data-view="json"]');
    const friendly = q("[data-panel='request-friendly']");
    const json = q("[data-panel='request-json']");
    jsonTab.click();
    expect(friendly.classList.contains('is-hidden')).toBe(true);
    expect(json.classList.contains('is-hidden')).toBe(false);
    expect(jsonTab.classList.contains('is-active')).toBe(true);
    expect(friendlyTab.classList.contains('is-active')).toBe(false);
    friendlyTab.click();
    expect(friendly.classList.contains('is-hidden')).toBe(false);
    expect(json.classList.contains('is-hidden')).toBe(true);
    expect(friendlyTab.classList.contains('is-active')).toBe(true);
    expect(jsonTab.classList.contains('is-active')).toBe(false);
  });

  /**
   * Missing friendly and json panels take the false arms of both "if" guards without error.
   */
  it('does not throw when the target panels are absent', () => {
    setBody('<button class="view-tab" data-target="request" data-view="json">J</button>');
    view.wireToggle();
    const tab = q('.view-tab');
    expect(() => tab.click()).not.toThrow();
  });
});
