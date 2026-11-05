import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/**
 * Tests for the shared confirmation handler confirm.js — a boot-only IIFE that attaches one
 * delegated `submit` listener guarding forms that declare a `data-confirm` message.
 *
 * The source exposes no function; importing it boots the listener against the current jsdom
 * document. Each test dispatches a real bubbling `submit` event and asserts the observable
 * effect (whether the default was prevented), exercising both arms of the attribute guard and of
 * the window.confirm decision. The listener is attached once per document; a fresh document per
 * test keeps the cases isolated.
 */
describe('confirm.js', () => {
  /**
   * Boots the confirm handler against a fresh document body and a stubbed window.confirm.
   *
   * @param {boolean} confirmResult what window.confirm returns for this test.
   * @returns {Promise<void>} resolves once the listener is attached.
   */
  async function boot(confirmResult) {
    document.body.innerHTML = '';
    window.confirm = vi.fn(() => confirmResult);
    // Imported without a cache-buster: the module is evaluated once, so exactly one delegated
    // submit listener is attached to the shared jsdom document across all cases.
    await loadScript('src/main/resources/META-INF/resources/ui/confirm.js');
  }

  /**
   * Builds a form, appends it to the body and returns it.
   *
   * @param {string|null} confirmMessage the data-confirm value, or null to omit the attribute.
   * @returns {HTMLFormElement} the appended form.
   */
  function formWith(confirmMessage) {
    const form = document.createElement('form');
    if (confirmMessage !== null) {
      form.setAttribute('data-confirm', confirmMessage);
    }
    document.body.appendChild(form);
    return form;
  }

  /**
   * Dispatches a cancelable bubbling submit event on a target and reports whether it was blocked.
   *
   * @param {EventTarget} target the event target.
   * @returns {boolean} true when the default was prevented.
   */
  function dispatchSubmit(target) {
    const event = new window.Event('submit', { bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    return event.defaultPrevented;
  }

  afterEach(() => {
    document.body.innerHTML = '';
  });

  /**
   * A guarded form whose confirmation the user cancels blocks the submit.
   */
  it('blocks the submit when the user cancels a guarded form', async () => {
    await boot(false);
    const form = formWith('Delete offer OFF_A?');
    const prevented = dispatchSubmit(form);
    expect(window.confirm).toHaveBeenCalledWith('Delete offer OFF_A?');
    expect(prevented).toBe(true);
  });

  /**
   * A guarded form whose confirmation the user accepts lets the submit through.
   */
  it('lets the submit through when the user accepts a guarded form', async () => {
    await boot(true);
    const form = formWith('Delete offer OFF_A?');
    const prevented = dispatchSubmit(form);
    expect(window.confirm).toHaveBeenCalledOnce();
    expect(prevented).toBe(false);
  });

  /**
   * A form without a data-confirm attribute is never intercepted and window.confirm is not called.
   */
  it('ignores a form without a data-confirm attribute', async () => {
    await boot(false);
    const form = formWith(null);
    const prevented = dispatchSubmit(form);
    expect(window.confirm).not.toHaveBeenCalled();
    expect(prevented).toBe(false);
  });

  /**
   * A submit event whose target is not an element (the document) exercises the hasAttribute guard's
   * false arm without throwing.
   */
  it('ignores a submit whose target has no hasAttribute', async () => {
    await boot(false);
    const prevented = dispatchSubmit(document);
    expect(window.confirm).not.toHaveBeenCalled();
    expect(prevented).toBe(false);
  });
});
