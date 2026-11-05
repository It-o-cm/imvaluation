import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of offer-ai.js, the natural-language drafting panel.
 *
 * The source is a browser IIFE whose top-level functions are exposed by the test harness.
 * At import the document is empty, so the auto-run wire()/wireVoice() take their
 * missing-elements arms and bind nothing; the wiring tests build the panel DOM and call
 * the wiring functions themselves. fetch is mocked and the Web Speech API is faked, so no
 * network and no microphone are used; the pure functions (esc, csrfToken, buildPayload,
 * renderResult, applyDraft, speechCtor, isSpeechAvailable, recognitionLang, voiceError)
 * are asserted to the exact character, both arms of every guard and ternary exercised.
 */

/**
 * The instances created by the fake speech recognizer, the last one last.
 */
let created;

/**
 * A fake Web Speech API recognizer recording its calls; the tests drive the callbacks
 * the browser would fire.
 */
class FakeRecognition {
  /**
   * Records the instance and resets its options.
   */
  constructor() {
    created = this;
    this.lang = '';
    this.continuous = false;
    this.interimResults = false;
    this.started = 0;
    this.stopped = 0;
    this.onresult = null;
    this.onend = null;
    this.onerror = null;
  }

  /**
   * Records a start.
   */
  start() { this.started += 1; }

  /**
   * Records a stop.
   */
  stop() { this.stopped += 1; }
}

/**
 * Project-relative path of the source under test.
 */
const PATH = 'src/main/resources/META-INF/resources/ui/offer-ai.js';

/**
 * The exposed top-level functions of the source, populated once at import.
 */
let view;

/**
 * The panel DOM nodes the wiring tests drive.
 */
let els;

/**
 * Builds the assistant panel DOM.
 *
 * @param {object} options Panel options: prompt text, disabled state, existing code.
 */
function buildPanel(options) {
  const o = options || {};
  document.body.innerHTML =
    '<textarea id="ai-prompt"></textarea>'
    + '<button type="button" id="ai-generate"></button>'
    + '<button type="button" id="ai-voice"></button>'
    + '<div id="ai-result" class="is-hidden"></div>'
    + '<form id="offer-form">'
    + '<input type="text" name="code"' + (o.codeDisabled ? ' disabled' : '') + ' value="' + (o.codeValue || '') + '">'
    + '<input type="datetime-local" name="validFrom" value="' + (o.validFrom || '') + '">'
    + '<input type="datetime-local" name="validTo" value="' + (o.validTo || '') + '">'
    + '</form>';
  els = {
    prompt: document.getElementById('ai-prompt'),
    button: document.getElementById('ai-generate'),
    voice: document.getElementById('ai-voice'),
    panel: document.getElementById('ai-result'),
    code: document.querySelector("input[name='code']"),
    validFrom: document.querySelector("input[name='validFrom']"),
    validTo: document.querySelector("input[name='validTo']"),
  };
  els.prompt.value = o.prompt || '';
  if (o.wire !== false) { view.wire(); }
}

/**
 * Flushes pending microtasks so a mocked fetch promise chain settles.
 */
async function microflush() {
  for (let i = 0; i < 12; i += 1) { await Promise.resolve(); }
}

beforeAll(async () => {
  view = await loadScript(PATH);
});

beforeEach(() => {
  globalThis.fetch = vi.fn();
});

afterEach(() => {
  document.body.innerHTML = '';
  delete window.SpeechRecognition;
  delete window.webkitSpeechRecognition;
  document.documentElement.lang = '';
  document.cookie = 'csrf-token=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
});

describe('esc', () => {
  /**
   * A null value takes the "" arm; a present value escapes the three entities.
   */
  it('escapes text for HTML', () => {
    expect(view.esc(null)).toBe('');
    expect(view.esc('a<b>&c')).toBe('a&lt;b&gt;&amp;c');
  });
});

describe('csrfToken', () => {
  /**
   * Without the cookie the token is the empty string; with it, the captured value.
   */
  it('reads the token from the csrf cookie', () => {
    expect(view.csrfToken()).toBe('');
    document.cookie = 'other=x';
    expect(view.csrfToken()).toBe('');
    document.cookie = 'csrf-token=tok-123';
    expect(view.csrfToken()).toBe('tok-123');
  });
});

describe('buildPayload', () => {
  /**
   * The payload is the prompt wrapped in a one-field JSON object.
   */
  it('wraps the prompt in JSON', () => {
    expect(view.buildPayload('une remise')).toBe('{"prompt":"une remise"}');
  });
});

describe('renderResult', () => {
  /**
   * An ok result with an explanation renders the applied alert carrying it, escaped.
   */
  it('renders an ok result with its explanation', () => {
    const html = view.renderResult({ status: 'ok', explanation: 'vingt <pour> cent' });
    expect(html).toBe('<div class="alert alert-ok"><strong>Draft applied to the form.</strong>'
      + ' vingt &lt;pour&gt; cent</div>');
  });

  /**
   * An ok result without an explanation takes the empty arm of the ternary.
   */
  it('renders an ok result without an explanation', () => {
    expect(view.renderResult({ status: 'ok' }))
      .toBe('<div class="alert alert-ok"><strong>Draft applied to the form.</strong></div>');
  });

  /**
   * An impossibility with both fields renders the reason and the suggestion.
   */
  it('renders an impossibility with reason and suggestion', () => {
    const html = view.renderResult({ status: 'impossible', reason: 'r', suggestion: 's' });
    expect(html).toBe('<div class="alert alert-error">'
      + '<strong>This description cannot be expressed as an offer.</strong>'
      + '<p>r</p><p>s</p></div>');
  });

  /**
   * An impossibility with neither field takes both empty arms.
   */
  it('renders an impossibility without reason or suggestion', () => {
    expect(view.renderResult({ status: 'impossible' }))
      .toBe('<div class="alert alert-error">'
        + '<strong>This description cannot be expressed as an offer.</strong></div>');
  });

  /**
   * An error result renders its message; without one, the fallback text shows.
   */
  it('renders an error result with and without a message', () => {
    expect(view.renderResult({ status: 'error', message: 'boom' }))
      .toBe('<div class="alert alert-error">boom</div>');
    expect(view.renderResult({ status: 'error' }))
      .toBe('<div class="alert alert-error">The assistant failed.</div>');
  });
});

describe('applyDraft', () => {
  /**
   * Captures the specification handed to the schema-driven form through its event.
   */
  let captured;

  /**
   * Captures the store targeting handed to the pickers through their event.
   */
  let capturedTargets;

  /**
   * The stable listener capturing the applied specification.
   */
  function onApply(event) {
    captured = event.detail;
  }

  /**
   * The stable listener capturing the applied store targeting.
   */
  function onApplyTargets(event) {
    capturedTargets = event.detail;
  }

  beforeEach(() => {
    captured = null;
    capturedTargets = null;
    document.addEventListener('offer-spec:apply', onApply);
    document.addEventListener('offer-targets:apply', onApplyTargets);
  });

  afterEach(() => {
    document.removeEventListener('offer-spec:apply', onApply);
    document.removeEventListener('offer-targets:apply', onApplyTargets);
  });

  /**
   * A valid result hands the store targeting to the pickers through their event; the two
   * code lists travel even when the draft carries none, so the pickers always receive a
   * renderable detail.
   */
  it('dispatches the store targeting to the pickers', () => {
    buildPanel({});
    view.applyDraft({
      status: 'ok',
      type: 'PERCENT',
      specification: '{}',
      storeCodes: ['0101'],
      storeGroupCodes: ['IDF'],
    });
    expect(capturedTargets).toEqual({ storeCodes: ['0101'], storeGroupCodes: ['IDF'] });
    view.applyDraft({ status: 'ok', type: 'T', specification: '{}' });
    expect(capturedTargets).toEqual({ storeCodes: undefined, storeGroupCodes: undefined });
  });

  /**
   * A valid result hands the type and specification to the form and fills an empty,
   * editable code field.
   */
  it('dispatches the specification and fills the empty code field', () => {
    buildPanel({});
    const applied = view.applyDraft({
      status: 'ok',
      type: 'PERCENT',
      code: 'PROMO_1',
      specification: '{"percent":0.2}',
    });
    expect(applied).toBe(true);
    expect(captured).toEqual({ type: 'PERCENT', specification: { percent: 0.2 } });
    expect(els.code.value).toBe('PROMO_1');
  });

  /**
   * Without a suggested code, or when the code field is disabled or already filled,
   * every false arm of the conjunction leaves the code untouched.
   */
  it('leaves the code field alone when the code cannot land', () => {
    buildPanel({ codeValue: 'MINE' });
    view.applyDraft({ status: 'ok', type: 'T', code: 'PROMO_2', specification: '{}' });
    expect(els.code.value).toBe('MINE');
    buildPanel({ codeDisabled: true });
    view.applyDraft({ status: 'ok', type: 'T', code: 'PROMO_3', specification: '{}' });
    expect(els.code.value).toBe('');
    buildPanel({});
    view.applyDraft({ status: 'ok', type: 'T', specification: '{}' });
    expect(els.code.value).toBe('');
  });

  /**
   * A suggested validity window fills the activation fields the same way the code does:
   * only when empty and editable, and never touched when the draft carries no window.
   */
  it('fills the validity window when the fields are empty', () => {
    buildPanel({});
    view.applyDraft({
      status: 'ok',
      type: 'T',
      code: 'PROMO_1',
      validFrom: '2026-12-12T00:00',
      validTo: '2026-12-25T00:00',
      specification: '{}',
    });
    expect(els.validFrom.value).toBe('2026-12-12T00:00');
    expect(els.validTo.value).toBe('2026-12-25T00:00');
  });

  /**
   * An already typed window wins over the suggestion, both bounds.
   */
  it('leaves a filled validity window alone', () => {
    buildPanel({ validFrom: '2026-01-01T00:00', validTo: '2026-01-31T00:00' });
    view.applyDraft({
      status: 'ok',
      type: 'T',
      validFrom: '2026-12-12T00:00',
      validTo: '2026-12-25T00:00',
      specification: '{}',
    });
    expect(els.validFrom.value).toBe('2026-01-01T00:00');
    expect(els.validTo.value).toBe('2026-01-31T00:00');
  });

  /**
   * A missing specification parses as the empty object and still applies.
   */
  it('applies a result without a specification as an empty object', () => {
    buildPanel({});
    expect(view.applyDraft({ status: 'ok', type: 'T' })).toBe(true);
    expect(captured).toEqual({ type: 'T', specification: {} });
  });

  /**
   * A malformed specification takes the catch arm: nothing is dispatched.
   */
  it('refuses a malformed specification', () => {
    buildPanel({});
    expect(view.applyDraft({ status: 'ok', type: 'T', specification: '{oops' })).toBe(false);
    expect(captured).toBeNull();
  });
});

describe('wire', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  /**
   * Without the panel elements the wiring takes its early-return arm: no listener at all.
   */
  it('does nothing when the panel elements are missing', () => {
    expect(() => view.wire()).not.toThrow();
    document.body.innerHTML = '<button type="button" id="ai-generate"></button>';
    view.wire();
    expect(() => document.getElementById('ai-generate').click()).not.toThrow();
  });

  /**
   * An empty prompt is answered locally: the describe-first error shows, fetch is not called.
   */
  it('answers an empty prompt locally', () => {
    buildPanel({ prompt: '   ' });
    els.button.click();
    expect(els.panel.classList.contains('is-hidden')).toBe(false);
    expect(els.panel.innerHTML)
      .toBe('<div class="alert alert-error">Describe the offer first.</div>');
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  /**
   * A prompt is posted to the endpoint with the CSRF header and, while the call is
   * pending, the button is disabled and the thinking note shows.
   */
  it('posts the prompt with the csrf header and shows the pending state', async () => {
    buildPanel({ prompt: 'une remise' });
    document.cookie = 'csrf-token=tok-9';
    globalThis.fetch.mockImplementation(() => new Promise(() => {}));
    els.button.click();
    expect(els.button.disabled).toBe(true);
    expect(els.panel.innerHTML).toBe('<p class="placeholder-note">Thinking\u2026</p>');
    expect(globalThis.fetch).toHaveBeenCalledWith('/ui/offers/ai-draft', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'tok-9' },
      body: '{"prompt":"une remise"}',
    });
  });

  /**
   * A successful draft is applied to the form and the panel confirms it.
   */
  it('applies a successful draft', async () => {
    buildPanel({ prompt: 'une remise' });
    globalThis.fetch.mockResolvedValue({
      json: () => Promise.resolve({
        status: 'ok',
        type: 'T',
        specification: '{"percent":0.2}',
        explanation: 'exp',
      }),
    });
    els.button.click();
    await microflush();
    expect(els.button.disabled).toBe(false);
    expect(els.panel.innerHTML)
      .toBe('<div class="alert alert-ok"><strong>Draft applied to the form.</strong> exp</div>');
    expect(els.panel.classList.contains('is-hidden')).toBe(false);
  });

  /**
   * A draft whose specification cannot be parsed is refused: the panel says the draft
   * could not be applied instead of pretending success.
   */
  it('refuses a draft it cannot apply', async () => {
    buildPanel({ prompt: 'une remise' });
    globalThis.fetch.mockResolvedValue({
      json: () => Promise.resolve({ status: 'ok', type: 'T', specification: '{oops' }),
    });
    els.button.click();
    await microflush();
    expect(els.button.disabled).toBe(false);
    expect(els.panel.innerHTML)
      .toBe('<div class="alert alert-error">The draft could not be applied to the form.</div>');
  });

  /**
   * An impossibility and an error are rendered as such, without touching the form.
   */
  it('renders an impossibility or an error without applying', async () => {
    buildPanel({ prompt: 'une remise' });
    globalThis.fetch.mockResolvedValue({
      json: () => Promise.resolve({ status: 'impossible', reason: 'r' }),
    });
    els.button.click();
    await microflush();
    expect(els.panel.innerHTML)
      .toBe('<div class="alert alert-error">'
        + '<strong>This description cannot be expressed as an offer.</strong><p>r</p></div>');

    buildPanel({ prompt: 'une remise' });
    globalThis.fetch.mockResolvedValue({
      json: () => Promise.resolve({ status: 'error', message: 'm' }),
    });
    els.button.click();
    await microflush();
    expect(els.panel.innerHTML).toBe('<div class="alert alert-error">m</div>');
  });

  /**
   * A rejected fetch takes the catch arm: the unreachable note shows and the button is
   * re-enabled.
   */
  it('reports an unreachable assistant', async () => {
    buildPanel({ prompt: 'une remise' });
    globalThis.fetch.mockRejectedValue(new Error('offline'));
    els.button.click();
    await microflush();
    expect(els.button.disabled).toBe(false);
    expect(els.panel.innerHTML)
      .toBe('<div class="alert alert-error">The assistant could not be reached.</div>');
  });
});

describe('dictation', () => {
  /**
   * Without the API the feature reports unavailable; a webkit-prefixed constructor alone
   * is enough (the other arm of the prefix fallback).
   */
  it('detects the speech recognizer, prefixed or not', () => {
    expect(view.isSpeechAvailable()).toBe(false);
    window.webkitSpeechRecognition = FakeRecognition;
    expect(view.isSpeechAvailable()).toBe(true);
    window.SpeechRecognition = FakeRecognition;
    expect(view.speechCtor()).toBe(FakeRecognition);
  });

  /**
   * The interface language maps to a recognition locale: English to en-US, anything else
   * (including the absent attribute) to the French default.
   */
  it('maps the interface language to a recognition locale', () => {
    expect(view.recognitionLang()).toBe('fr-FR');
    document.documentElement.lang = 'fr';
    expect(view.recognitionLang()).toBe('fr-FR');
    document.documentElement.lang = 'en';
    expect(view.recognitionLang()).toBe('en-US');
  });

  /**
   * A transcript normalizes for command matching: null to empty, lowercase, punctuation
   * stripped, whitespace collapsed — the shape a spoken command arrives in.
   */
  it('normalizes transcripts for command matching', () => {
    expect(view.normalizeTranscript(null)).toBe('');
    expect(view.normalizeTranscript('Efface !')).toBe('efface');
    expect(view.normalizeTranscript("  Vingt   pour   cent,  s'il vous plait. ")).toBe("vingt pour cent s'il vous plait");
  });

  /**
   * The wipe commands match exactly once normalized — case and punctuation included —
   * while ordinary sentences do not, in the French set by default.
   */
  it('recognizes the French wipe commands', () => {
    expect(view.isCommand('Efface !')).toBe(true);
    expect(view.isCommand('JE RECOMMENCE')).toBe(true);
    expect(view.isCommand('on  recommence…')).toBe(true);
    expect(view.isCommand('efface le ticket')).toBe(false);
    expect(view.isCommand('vingt pour cent')).toBe(false);
    expect(view.isCommand('')).toBe(false);
  });

  /**
   * The command set follows the interface language: the English commands match under the
   * English locale and the French ones no longer do.
   */
  it('follows the interface language for the command set', () => {
    document.documentElement.lang = 'en';
    expect(view.isCommand('Start over.')).toBe(true);
    expect(view.isCommand('CLEAR ALL')).toBe(true);
    expect(view.isCommand('efface')).toBe(false);
  });

  /**
   * The three failure shapes carry distinct messages: the refused microphone, the
   * unheard dictation, and anything else with the code substituted — or a plain
   * fallback when the code is missing.
   */
  it('renders a message per dictation failure', () => {
    expect(view.voiceError('not-allowed'))
      .toBe('The microphone was refused. Allow it and try again.');
    expect(view.voiceError('service-not-allowed'))
      .toBe('The microphone was refused. Allow it and try again.');
    expect(view.voiceError('no-speech')).toBe('Nothing was heard. Try again.');
    expect(view.voiceError('audio-capture')).toBe('Dictation failed: audio-capture');
    expect(view.voiceError(null)).toBe('Dictation failed: unknown error');
  });
});

describe('wireVoice', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.SpeechRecognition;
    delete window.webkitSpeechRecognition;
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  /**
   * Without the panel elements the wiring takes its early-return arm and does nothing —
   * each missing element is covered: the textarea alone, then the textarea and button
   * without the panel.
   */
  it('does nothing when the panel elements are missing', () => {
    expect(() => view.wireVoice()).not.toThrow();
    document.body.innerHTML = '<textarea id="ai-prompt"></textarea>';
    view.wireVoice();
    document.body.innerHTML =
      '<textarea id="ai-prompt"></textarea><button type="button" id="ai-voice"></button>';
    view.wireVoice();
    expect(document.getElementById('ai-voice').classList.contains('is-hidden')).toBe(false);
  });

  /**
   * A browser without the Web Speech API keeps the panel but the dictation button hides
   * itself: graceful degradation, no dead control.
   */
  it('hides the button in a browser without the API', () => {
    buildPanel({ wire: false });
    view.wireVoice();
    expect(els.voice.classList.contains('is-hidden')).toBe(true);
  });

  /**
   * A click starts listening: the recognizer is created with the interface locale, the
   * continuous mode without interim results, and the button lights up. The dictation is
   * then ended by hand with nothing dictated, so no analysis leaves.
   */
  it('starts listening on the first click', () => {
    buildPanel({ wire: false });
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.voice.click();
    expect(created.started).toBe(1);
    expect(created.lang).toBe('fr-FR');
    expect(created.continuous).toBe(true);
    // Interim results stay on: they are the voice-activity signal that keeps the silence
    // watchdog from cutting a live dictation.
    expect(created.interimResults).toBe(true);
    expect(els.voice.classList.contains('is-active')).toBe(true);
    created.onend();
    expect(els.voice.classList.contains('is-active')).toBe(false);
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  /**
   * The final transcripts append to the prompt, separated by a space whether the field
   * was empty or already carried text; interim fragments never land.
   */
  it('appends the final transcripts to the prompt', () => {
    buildPanel({ prompt: 'bonjour' });
    window.SpeechRecognition = FakeRecognition;
    globalThis.fetch.mockImplementation(() => new Promise(() => {}));
    view.wireVoice();
    els.voice.click();
    created.onresult({
      resultIndex: 0,
      results: [
        { isFinal: false, 0: { transcript: 'brouillon' } },
        { isFinal: true, 0: { transcript: 'vingt pour cent' } },
      ],
    });
    expect(els.prompt.value).toBe('bonjour vingt pour cent');
    // A later event carries the accumulated list; only the results from resultIndex on
    // are new, so the already-appended finals do not land twice.
    created.onresult({
      resultIndex: 1,
      results: [
        { isFinal: true, 0: { transcript: 'vingt pour cent' } },
        { isFinal: true, 0: { transcript: 'de remise' } },
      ],
    });
    expect(els.prompt.value).toBe('bonjour vingt pour cent de remise');
    expect(els.prompt.value).toBe('bonjour vingt pour cent de remise');
    // Into an empty prompt the transcript lands bare, no leading space.
    els.prompt.value = '';
    created.onresult({
      resultIndex: 0,
      results: [{ isFinal: true, 0: { transcript: 'nouvelle phrase' } }],
    });
    expect(els.prompt.value).toBe('nouvelle phrase');
    // An event carrying no final result changes nothing.
    created.onresult({
      resultIndex: 0,
      results: [{ isFinal: false, 0: { transcript: 'en cours' } }],
    });
    expect(els.prompt.value).toBe('nouvelle phrase');
    // When the browser ends the dictation by itself with a description in hand, the
    // analysis leaves on its own — here with the last dictated sentence, the field
    // having been emptied mid-test above.
    created.onend();
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    expect(globalThis.fetch).toHaveBeenCalledWith('/ui/offers/ai-draft', {
      method: 'POST',
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      body: '{"prompt":"nouvelle phrase"}',
    });
  });

  /**
   * Four seconds of silence end the dictation and trigger the analysis; any spoken
   * activity resets the watchdog, so the silence is counted from the last word.
   */
  it('ends the dictation after four seconds of silence and triggers the analysis', () => {
    buildPanel({});
    window.SpeechRecognition = FakeRecognition;
    globalThis.fetch.mockImplementation(() => new Promise(() => {}));
    view.wireVoice();
    els.voice.click();
    vi.advanceTimersByTime(3500);
    expect(created.stopped).toBe(0);
    created.onresult({
      resultIndex: 0,
      results: [{ isFinal: true, 0: { transcript: 'vingt pour cent de remise' } }],
    });
    vi.advanceTimersByTime(3500);
    expect(created.stopped).toBe(0);
    vi.advanceTimersByTime(500);
    expect(created.stopped).toBe(1);
    created.onend();
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    expect(globalThis.fetch).toHaveBeenCalledWith('/ui/offers/ai-draft', {
      method: 'POST',
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      body: '{"prompt":"vingt pour cent de remise"}',
    });
  });

  /**
   * A wipe command clears the prompt — typed text included — and what is dictated next
   * starts fresh, including in the same recognition event as the command.
   */
  it('obeys a wipe command during the dictation', () => {
    buildPanel({ wire: false, prompt: 'texte tape' });
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.voice.click();
    created.onresult({
      resultIndex: 0,
      results: [{ isFinal: true, 0: { transcript: 'vingt pour cent' } }],
    });
    expect(els.prompt.value).toBe('texte tape vingt pour cent');
    created.onresult({
      resultIndex: 1,
      results: [
        { isFinal: true, 0: { transcript: 'vingt pour cent' } },
        { isFinal: true, 0: { transcript: 'Efface !' } },
      ],
    });
    expect(els.prompt.value).toBe('');
    created.onresult({
      resultIndex: 1,
      results: [
        { isFinal: true, 0: { transcript: 'je recommence' } },
        { isFinal: true, 0: { transcript: 'dix pour cent' } },
        { isFinal: true, 0: { transcript: 'sur le ticket' } },
      ],
    });
    expect(els.prompt.value).toBe('dix pour cent sur le ticket');
    created.onend();
  });

  /**
   * An interim result — the voice-activity signal flowing while the user is still
   * speaking — keeps the watchdog from cutting a live dictation, even though nothing is
   * appended to the prompt.
   */
  it('keeps listening while interim results flow', () => {
    buildPanel({ wire: false });
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.voice.click();
    vi.advanceTimersByTime(3500);
    created.onresult({
      resultIndex: 0,
      results: [{ isFinal: false, 0: { transcript: 'en train de' } }],
    });
    expect(els.prompt.value).toBe('');
    vi.advanceTimersByTime(3500);
    expect(created.stopped).toBe(0);
    vi.advanceTimersByTime(500);
    expect(created.stopped).toBe(1);
    created.onend();
  });

  /**
   * A speech start is a voice-activity signal too: it resets the watchdog even before
   * any transcript arrives, so a slow speaker is never cut mid-sentence.
   */
  it('keeps listening after a speech start', () => {
    buildPanel({ wire: false });
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.voice.click();
    vi.advanceTimersByTime(3500);
    created.onspeechstart();
    vi.advanceTimersByTime(3500);
    expect(created.stopped).toBe(0);
    vi.advanceTimersByTime(500);
    expect(created.stopped).toBe(1);
    created.onend();
  });

  /**
   * A dictation that ends by itself without a single word sends nothing: the panel is
   * not put to work over an empty description.
   */
  it('sends nothing when the dictation ends silent with no text', () => {
    buildPanel({});
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.voice.click();
    vi.advanceTimersByTime(4000);
    expect(created.stopped).toBe(1);
    created.onend();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  /**
   * A second click stops the recognizer: a manual stop leaves the review to the user, no
   * analysis leaves even with a description in hand. A duplicated end callback — timer
   * already cleared, text cleared — must stay inert as well.
   */
  it('stops on the second click and resets on end', () => {
    buildPanel({ wire: false });
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.prompt.value = 'phrase dictee';
    els.voice.click();
    const first = created;
    els.voice.click();
    expect(first.stopped).toBe(1);
    first.onend();
    expect(els.voice.classList.contains('is-active')).toBe(false);
    expect(globalThis.fetch).not.toHaveBeenCalled();
    els.prompt.value = '';
    first.onend();
    expect(globalThis.fetch).not.toHaveBeenCalled();
    els.voice.click();
    expect(created).not.toBe(first);
    expect(created.started).toBe(1);
  });

  /**
   * A refused microphone surfaces in the result panel with its dedicated message, the
   * end callback that follows still resets the button, and no analysis leaves.
   */
  it('reports a dictation failure in the panel', () => {
    buildPanel({ wire: false });
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    els.prompt.value = 'phrase dictee';
    els.voice.click();
    created.onerror({ error: 'not-allowed' });
    expect(els.panel.classList.contains('is-hidden')).toBe(false);
    expect(els.panel.innerHTML)
      .toBe('<div class="alert alert-error">The microphone was refused. Allow it and try again.</div>');
    created.onend();
    expect(els.voice.classList.contains('is-active')).toBe(false);
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  /**
   * A page without the generate button still ends dictation cleanly: the automatic
   * trigger skips instead of throwing on a missing element.
   */
  it('does not throw when the generate button is absent', () => {
    document.body.innerHTML =
      '<textarea id="ai-prompt"></textarea>'
      + '<button type="button" id="ai-voice"></button>'
      + '<div id="ai-result" class="is-hidden"></div>';
    window.SpeechRecognition = FakeRecognition;
    view.wireVoice();
    document.getElementById('ai-voice').click();
    created.onresult({
      resultIndex: 0,
      results: [{ isFinal: true, 0: { transcript: 'une remise' } }],
    });
    expect(() => created.onend()).not.toThrow();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });
});
