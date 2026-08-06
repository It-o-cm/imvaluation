import { defineConfig } from 'vitest/config';

/*
 * The browser sources under META-INF/resources/ui are plain IIFEs, not ES modules:
 * they wrap everything in `(function () { 'use strict'; ... })();` and export nothing. They
 * are served verbatim to the browser and must stay intact on disk, yet the bench needs to
 * (a) reach their inner functions and (b) have Istanbul instrument them under their real
 * path so branch coverage is attributed to the actual source file.
 *
 * This Vite plugin rewrites each module IN MEMORY only, at import time. For every top-level
 * IIFE it injects, right after the `'use strict'` directive, a capture that copies the
 * IIFE's top-level functions onto a shared `__ui_exports__` object; the file then default-
 * exports that object. The capture sits before the IIFE's boot/return code and references
 * hoisted function declarations, so the functions are exposed even when the IIFE later
 * boots or returns early. The disk file is never touched.
 *
 * The approach is deliberately general so it needs no per-file special-casing:
 *   - multiple IIFEs (list-filters.js) — each IIFE is matched and captured independently;
 *   - a top-level `return` (valuation-refresh.js) — the IIFE stays a function, so `return`
 *     remains legal, unlike a bare unwrap;
 *   - an auto-boot `init()` — the capture runs before it, so the functions are exposed;
 *     specs keep the boot from throwing by controlling document.readyState / the DOM.
 */
function exposeUiIife() {
  const UI_FILE = /META-INF[/\\]resources[/\\]ui[/\\][^/\\]+\.js(\?|$)/;
  // A top-level IIFE: `(function () { <body> })();`. The body never itself contains the
  // `})();` terminator, so the non-greedy capture stops at this IIFE's own close.
  const IIFE = /\(function\s*\(\s*\)\s*\{([\s\S]*?)\}\)\(\s*\)\s*;/g;
  const USE_STRICT = /^(\s*)(['"])use strict\2;/;
  const TOP_LEVEL_FN = /^ {4}function\s+([A-Za-z_$][\w$]*)\s*\(/gm;
  return {
    name: 'expose-ui-iife',
    enforce: 'pre',
    transform(code, id) {
      if (!UI_FILE.test(id)) {
        return null;
      }
      let matched = false;
      const rewritten = code.replace(IIFE, (full, body) => {
        matched = true;
        const names = [...body.matchAll(TOP_LEVEL_FN)].map((m) => m[1]);
        const capture = names.length
          ? `Object.assign(__ui_exports__, { ${names.join(', ')} });`
          : '';
        const injected = USE_STRICT.test(body)
          ? body.replace(USE_STRICT, `$1$2use strict$2;${capture}`)
          : capture + body;
        return `(function () {${injected}})();`;
      });
      if (!matched) {
        return null;
      }
      return {
        code: `const __ui_exports__ = {};\n${rewritten}\nexport default __ui_exports__;\n`,
        map: null,
      };
    },
  };
}

export default defineConfig({
  plugins: [exposeUiIife()],
  test: {
    environment: 'jsdom',
    include: ['src/test/js/**/*.spec.js'],
    coverage: {
      provider: 'istanbul',
      include: ['src/main/resources/META-INF/resources/ui/*.js'],
      reporter: ['text', 'json', 'html'],
      reportsDirectory: 'target/js-coverage',
      all: false,
    },
  },
});
