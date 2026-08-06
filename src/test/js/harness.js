import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/**
 * Absolute path of the project root, derived from this file's location
 * (src/test/js/harness.js is three directories below the root).
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * Loads one of the static browser sources and returns its top-level functions.
 * <p>
 * The sources are plain IIFEs, not ES modules, and stay untouched on disk. They are made
 * importable by the `expose-ui-iife` Vite plugin (see vitest.config.js), which injects a
 * capture of each IIFE's top-level functions onto a `__ui_exports__` object and default-
 * exports it. Importing through Vite — rather than reading the file and eval-ing it — is
 * what lets Istanbul instrument the code under its real path, so branch coverage is
 * attributed to the actual source file. The IIFE body runs once against the current jsdom
 * document on first import; callers that boot DOM code control document.readyState or the
 * DOM beforehand so that run does not throw.
 *
 * @param {string} relativePath Project-relative path of the source, e.g.
 *   "src/main/resources/META-INF/resources/ui/schema-form.js".
 * @param {object} [options] Loading options.
 * @param {string|number} [options.bust] A cache-busting token appended to the import URL so
 *   the module is evaluated afresh (a second boot), used to cover a boot arm that a single
 *   evaluation cannot reach. Istanbul still attributes coverage to the real file path.
 * @returns {Promise<object>} The object exposing the source's top-level functions.
 */
export async function loadScript(relativePath, options = {}) {
  const absolute = resolve(ROOT, relativePath);
  let url = pathToFileURL(absolute).href;
  if (options.bust !== undefined) {
    url += `?bust=${options.bust}`;
  }
  const module = await import(/* @vite-ignore */ url);
  return module.default;
}
