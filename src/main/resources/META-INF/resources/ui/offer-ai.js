/*
 * Natural-language offer drafting: sends the plain-language description to the valuation
 * back office, which drafts a specification through the Anthropic API. A valid draft is
 * applied to the schema-driven form through the 'offer-spec:apply' event and the
 * administrator reviews it before saving; an impossibility or an error is rendered in
 * place. Nothing is persisted by this panel.
 *
 * The description can be dictated: the Web Speech API transcribes the microphone straight
 * into the prompt textarea, in the interface's language. The dictation obeys a small set
 * of spoken commands — « efface », « je recommence » — that wipe everything dictated so
 * far. Browsers without the API keep the exact same panel, minus the dictation button.
 */
(function () {
    "use strict";

    /**
     * Silence that ends a dictation, in milliseconds.
     */
    var SILENCE_MS = 4000;

    /**
     * Granularity of the silence watchdog, in milliseconds.
     */
    var SILENCE_TICK_MS = 500;

    /**
     * Escapes text for safe insertion as HTML.
     * @param {*} value The value to escape.
     * @returns {string} The escaped string.
     */
    function esc(value) {
        return String(value == null ? "" : value)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    /**
     * Reads the per-session CSRF token from its cookie, echoed back on the fetch as the
     * X-CSRF-TOKEN header (spec-durcissement-web §1).
     * @returns {string} The token, empty when the cookie is absent.
     */
    function csrfToken() {
        var match = document.cookie.match(/(?:^|;\s*)csrf-token=([^;]+)/);
        return match ? match[1] : "";
    }

    /**
     * Builds the JSON request body of a draft call.
     * @param {string} prompt The trimmed description.
     * @returns {string} The serialized body.
     */
    function buildPayload(prompt) {
        return JSON.stringify({ prompt: prompt });
    }

    /**
     * Renders the outcome panel content for one result.
     * @param {object} result The result, one of ok / impossible / error.
     * @returns {string} The HTML.
     */
    function renderResult(result) {
        if (result.status === "ok") {
            return "<div class=\"alert alert-ok\"><strong>Draft applied to the form.</strong>"
                + (result.explanation ? " " + esc(result.explanation) : "") + "</div>";
        }
        if (result.status === "impossible") {
            return "<div class=\"alert alert-error\">"
                + "<strong>This description cannot be expressed as an offer.</strong>"
                + (result.reason ? "<p>" + esc(result.reason) + "</p>" : "")
                + (result.suggestion ? "<p>" + esc(result.suggestion) + "</p>" : "")
                + "</div>";
        }
        return "<div class=\"alert alert-error\">"
            + esc(result.message || "The assistant failed.") + "</div>";
    }

    /**
     * Applies a valid draft to the editor: the specification is handed to the schema-driven
     * form through its event, the store targeting to the pickers through theirs, and the
     * suggested code and validity window fill their form fields when these are still empty
     * and editable.
     * @param {object} result The ok result.
     * @returns {boolean} true when the specification was applied.
     */
    function applyDraft(result) {
        var specification;
        try {
            specification = JSON.parse(result.specification || "{}");
        } catch (e) {
            return false;
        }
        document.dispatchEvent(new CustomEvent("offer-spec:apply", {
            detail: { type: result.type, specification: specification }
        }));
        document.dispatchEvent(new CustomEvent("offer-targets:apply", {
            detail: { storeCodes: result.storeCodes, storeGroupCodes: result.storeGroupCodes }
        }));
        ["code", "validFrom", "validTo"].forEach(function (name) {
            var input = document.querySelector("input[name='" + name + "']");
            if (result[name] && input && !input.disabled && !input.value) {
                input.value = result[name];
            }
        });
        return true;
    }

    /**
     * Wires the assistant panel: describes, submits, renders, applies.
     */
    function wire() {
        var button = document.getElementById("ai-generate");
        var textarea = document.getElementById("ai-prompt");
        var panel = document.getElementById("ai-result");
        if (!button || !textarea || !panel) {
            return;
        }
        button.addEventListener("click", function () {
            // A textarea value is always a string, so the trim is safe unguarded.
            var prompt = textarea.value.trim();
            panel.classList.remove("is-hidden");
            if (!prompt) {
                panel.innerHTML = renderResult({ status: "error", message: "Describe the offer first." });
                return;
            }
            button.disabled = true;
            panel.innerHTML = "<p class=\"placeholder-note\">Thinking\u2026</p>";
            fetch("/ui/offers/ai-draft", {
                method: "POST",
                headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrfToken() },
                body: buildPayload(prompt)
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                button.disabled = false;
                if (result.status === "ok") {
                    if (applyDraft(result)) {
                        panel.innerHTML = renderResult(result);
                    } else {
                        panel.innerHTML = renderResult({
                            status: "error",
                            message: "The draft could not be applied to the form."
                        });
                    }
                } else {
                    panel.innerHTML = renderResult(result);
                }
            }).catch(function () {
                button.disabled = false;
                panel.innerHTML = renderResult({
                    status: "error",
                    message: "The assistant could not be reached."
                });
            });
        });
    }

    /**
     * Returns the browser's speech recognition constructor, when it exists.
     * @returns {Function|null} The constructor, or null in browsers without the API.
     */
    function speechCtor() {
        return window.SpeechRecognition || window.webkitSpeechRecognition;
    }

    /**
     * Indicates whether dictation is possible in this browser.
     * @returns {boolean} true when the Web Speech API is available.
     */
    function isSpeechAvailable() {
        return !!speechCtor();
    }

    /**
     * Maps the interface language to a recognition locale: the recognition API wants a
     * BCP-47 tag with a region, the layout only carries the language.
     * @returns {string} The recognition locale.
     */
    function recognitionLang() {
        var lang = document.documentElement.lang || "fr";
        return lang.indexOf("en") === 0 ? "en-US" : "fr-FR";
    }

    /**
     * Renders a dictation failure in a form the result panel can show.
     * @param {string} code The error code reported by the recognition API, may be null.
     * @returns {string} The human-readable message.
     */
    function voiceError(code) {
        if (code === "not-allowed" || code === "service-not-allowed") {
            return "The microphone was refused. Allow it and try again.";
        }
        if (code === "no-speech") {
            return "Nothing was heard. Try again.";
        }
        return "Dictation failed: " + (code || "unknown error");
    }

    /**
     * Returns the dictation commands that wipe everything dictated so far, in the
     * interface's language.
     * @returns {string[]} The exact commands, matched after normalization.
     */
    function dictationCommands() {
        return recognitionLang().indexOf("en") === 0
            ? ["clear", "clear all", "start over", "start again"]
            : ["efface", "efface tout", "je recommence", "recommence", "on recommence"];
    }

    /**
     * Normalizes a transcript for command matching: lowercase, punctuation stripped,
     * whitespace collapsed — so a spoken « Efface ! » matches the « efface » command.
     * @param {*} text The raw transcript.
     * @returns {string} The normalized transcript.
     */
    function normalizeTranscript(text) {
        return String(text == null ? "" : text)
            .toLowerCase()
            .replace(/[.,!?;:…]/g, "")
            .replace(/\s+/g, " ")
            .trim();
    }

    /**
     * Indicates whether a transcript is one of the dictation commands.
     * @param {*} text The raw transcript.
     * @returns {boolean} true when the transcript orders a wipe.
     */
    function isCommand(text) {
        var normalized = normalizeTranscript(text);
        return normalized !== "" && dictationCommands().indexOf(normalized) !== -1;
    }

    /**
     * Wires the dictation button: a click starts listening and the button lights up,
     * another click stops; each final transcript is appended to the prompt, unless it is
     * a dictation command — « efface », « je recommence » — which wipes everything
     * dictated so far instead. A silence of {@link SILENCE_MS} ends the dictation by
     * itself — any voice activity, interim result or speech start, resets the watchdog,
     * so only true silence counts — and when the dictation ends on its own with a
     * description in hand, the analysis is triggered automatically; a manual stop
     * leaves the review to the user.
     */
    function wireVoice() {
        var button = document.getElementById("ai-voice");
        var textarea = document.getElementById("ai-prompt");
        var panel = document.getElementById("ai-result");
        if (!button || !textarea || !panel) {
            return;
        }
        if (!isSpeechAvailable()) {
            // Graceful degradation: a browser without the API keeps the panel, minus
            // the dictation entry point.
            button.classList.add("is-hidden");
            return;
        }
        var recognition = null;
        var manualStop = false;
        var hadError = false;
        var silenceTimer = null;
        var silenceTicks = 0;

        /**
         * Stops the silence watchdog.
         */
        function endSilenceWatch() {
            if (silenceTimer !== null) {
                clearInterval(silenceTimer);
                silenceTimer = null;
            }
        }

        button.addEventListener("click", function () {
            if (recognition) {
                manualStop = true;
                recognition.stop();
                return;
            }
            manualStop = false;
            hadError = false;
            recognition = new (speechCtor())();
            recognition.lang = recognitionLang();
            recognition.continuous = true;
            // Interim results are kept on even though only finals are appended: they are the
            // voice-activity signal that keeps the silence watchdog honest. With them off,
            // Safari delivers nothing while the user is still speaking and the watchdog
            // cut a live dictation at exactly its silence threshold.
            recognition.interimResults = true;
            recognition.onspeechstart = function () {
                silenceTicks = 0;
            };
            recognition.onresult = function (event) {
                silenceTicks = 0;
                var text = "";
                for (var i = event.resultIndex; i < event.results.length; i += 1) {
                    if (!event.results[i].isFinal) {
                        continue;
                    }
                    var transcript = event.results[i][0].transcript;
                    if (isCommand(transcript)) {
                        // A command wipes everything dictated so far, typed text included;
                        // whatever the same event dictates afterwards starts fresh.
                        textarea.value = "";
                        text = "";
                    } else {
                        text = (text ? text + " " : "") + transcript;
                    }
                }
                if (text) {
                    textarea.value = (textarea.value ? textarea.value + " " : "") + text;
                }
            };
            recognition.onend = function () {
                endSilenceWatch();
                recognition = null;
                button.classList.remove("is-active");
                var analyze = !manualStop && !hadError && textarea.value.trim();
                manualStop = false;
                hadError = false;
                if (analyze) {
                    // The dictation ended by itself — silence or browser cutoff — and the
                    // description is complete: the analysis leaves on its own.
                    var generate = document.getElementById("ai-generate");
                    if (generate) {
                        generate.click();
                    }
                }
            };
            recognition.onerror = function (event) {
                hadError = true;
                panel.classList.remove("is-hidden");
                panel.innerHTML = renderResult({ status: "error", message: voiceError(event.error) });
            };
            recognition.start();
            button.classList.add("is-active");
            silenceTicks = 0;
            silenceTimer = setInterval(function () {
                silenceTicks += 1;
                if (silenceTicks * SILENCE_TICK_MS >= SILENCE_MS) {
                    recognition.stop();
                }
            }, SILENCE_TICK_MS);
        });
    }

    wire();
    wireVoice();
})();
