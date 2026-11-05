package com.intermarche.valuation.ai;

/**
 * The outcome of a natural-language offer draft call, returned to the browser as JSON.
 * <p>
 * Exactly one of three statuses is carried:
 * <ul>
 *   <li>{@code ok} — the model produced a draft that validates against the schema of the
 *       chosen offer type: {@link #type} and {@link #specification} (a JSON object
 *       serialised as a string) can pre-fill the form, {@link #explanation} says what the
 *       model understood, and {@link #code} optionally suggests an offer code;</li>
 *   <li>{@code impossible} — no combination of the available offer types represents the
 *       request: {@link #reason} says why, and {@link #suggestion} optionally proposes a
 *       reformulation that would come close;</li>
 *   <li>{@code error} — the call could not produce a usable answer: {@link #message}
 *       explains the failure (missing key, unreachable API, incoherent reply, draft that
 *       failed server-side validation).</li>
 * </ul>
 * The fields are public for JSON serialization, mirroring the engine's result types.
 */
public class OfferDraftResult {

    /**
     * The draft was produced and validated.
     */
    public static final String OK = "ok";

    /**
     * The request cannot be represented by the available offer types.
     */
    public static final String IMPOSSIBLE = "impossible";

    /**
     * The call failed before or during interpretation.
     */
    public static final String ERROR = "error";

    /**
     * One of {@link #OK}, {@link #IMPOSSIBLE}, {@link #ERROR}.
     */
    public String status;

    /**
     * The chosen offer type, set on {@link #OK}.
     */
    public String type;

    /**
     * A suggested offer code, set on {@link #OK} when the model proposed one.
     */
    public String code;

    /**
     * The suggested validity-window start (ISO local date-time, {@code yyyy-MM-dd'T'HH:mm}),
     * set on {@link #OK} when the description expressed one. The form's field is excluded
     * from what the engine evaluates until then, so this only pre-fills the input.
     */
    public String validFrom;

    /**
     * The suggested validity-window end, exclusive, set on {@link #OK} when the description
     * expressed one.
     */
    public String validTo;

    /**
     * The suggested store codes, set on {@link #OK} when the description named stores the
     * referential carries. The model is constrained to the catalogue it was sent, so a
     * code here exists — and the save re-resolves it anyway.
     */
    public java.util.List<String> storeCodes;

    /**
     * The suggested store group codes, set on {@link #OK} when the description named a
     * region or a zone a group of the referential carries.
     */
    public java.util.List<String> storeGroupCodes;

    /**
     * The specification, a JSON object serialised as a string, set on {@link #OK}.
     */
    public String specification;

    /**
     * What the model understood and built, set on {@link #OK}.
     */
    public String explanation;

    /**
     * Why the request is impossible, set on {@link #IMPOSSIBLE}.
     */
    public String reason;

    /**
     * A reformulation that would come close, set on {@link #IMPOSSIBLE} when the model
     * proposed one.
     */
    public String suggestion;

    /**
     * Why the call failed, set on {@link #ERROR}.
     */
    public String message;

    /**
     * Default constructor for JSON serialization.
     */
    public OfferDraftResult() {
    }

    /**
     * Builds a validated draft ready to pre-fill the form.
     *
     * @param type            The offer type, validated against the registry.
     * @param code            A suggested offer code, may be null.
     * @param validFrom       The suggested validity start, may be null.
     * @param validTo         The suggested validity end, exclusive, may be null.
     * @param storeCodes      The suggested store codes, never null.
     * @param storeGroupCodes The suggested store group codes, never null.
     * @param specification   The specification JSON, validated against the type's schema.
     * @param explanation     What the model understood, may be null.
     * @return The {@link #OK} result.
     */
    public static OfferDraftResult ok(String type, String code, String validFrom, String validTo,
                                      java.util.List<String> storeCodes,
                                      java.util.List<String> storeGroupCodes,
                                      String specification, String explanation) {
        OfferDraftResult result = new OfferDraftResult();
        result.status = OK;
        result.type = type;
        result.code = code;
        result.validFrom = validFrom;
        result.validTo = validTo;
        result.storeCodes = storeCodes;
        result.storeGroupCodes = storeGroupCodes;
        result.specification = specification;
        result.explanation = explanation;
        return result;
    }

    /**
     * Builds a declaration of impossibility.
     *
     * @param reason     Why the request cannot be represented.
     * @param suggestion A reformulation that would come close, may be null.
     * @return The {@link #IMPOSSIBLE} result.
     */
    static OfferDraftResult impossible(String reason, String suggestion) {
        OfferDraftResult result = new OfferDraftResult();
        result.status = IMPOSSIBLE;
        result.reason = reason;
        result.suggestion = suggestion;
        return result;
    }

    /**
     * Builds a failure report.
     *
     * @param message Why the call failed.
     * @return The {@link #ERROR} result.
     */
    public static OfferDraftResult error(String message) {
        OfferDraftResult result = new OfferDraftResult();
        result.status = ERROR;
        result.message = message;
        return result;
    }
}
