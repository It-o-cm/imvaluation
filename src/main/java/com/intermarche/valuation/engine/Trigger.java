package com.intermarche.valuation.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The AND node of a trigger — today the only composite, and the root the engine sees.
 * <p>
 * A {@code Trigger} is the implicit AND of its {@code conditions[]}: it is satisfied when
 * every child is, and its contributors are the union (deduplicated) of the children's
 * contributors (spec §3.3). An absent trigger block is {@link #ALWAYS}, never {@code null}:
 * always satisfied, zero contributors. Children are typed on {@link TriggerCondition}, so a
 * future OR node is one class and one schema entry away, with no rework here.
 * <p>
 * {@code conditions[]} is <em>the</em> notation of the AND — the immutable structure of
 * spec §3.4. This node never combines with an {@code ALL_OF} kind, which does not and will
 * not exist at the root.
 */
public final class Trigger implements TriggerCondition {

    /**
     * The always-satisfied trigger: an empty AND. Returned whenever a configuration carries
     * no trigger block. Satisfied vacuously, with no contributors.
     */
    public static final Trigger ALWAYS = new Trigger(List.of());

    /**
     * The children of the AND node, typed on the interface.
     */
    private final List<TriggerCondition> children;

    /**
     * Builds an AND node over the given children.
     *
     * @param children the children conditions; copied defensively.
     */
    public Trigger(List<TriggerCondition> children) {
        this.children = List.copyOf(children);
    }

    /**
     * Returns the children of this AND node.
     *
     * @return an unmodifiable view of the children.
     */
    public List<TriggerCondition> getChildren() {
        return children;
    }

    /**
     * Parses a trigger from its JSON node, applying the cross-field rules of spec §3.8.
     * <p>
     * A {@code null} node (absent block) yields {@link #ALWAYS}. Otherwise the node must
     * carry a non-empty {@code conditions[]} array; each condition is parsed by its
     * {@code kind}. The rules rejected here, through {@link IllegalArgumentException}, are:
     * an empty {@code conditions[]} (§3.8.3), scope {@code ITEMS} without {@code eans}
     * (§3.8.1), scope {@code TICKET} with {@code eans} (§3.8.2), and an unknown {@code kind}
     * (§3.8.4). Duplicate EANs are tolerated and deduplicated (§3.8.6).
     *
     * @param triggerNode the JSON node of the trigger block, or {@code null} when absent.
     * @return the parsed AND node, or {@link #ALWAYS} when the block is absent.
     * @throws IllegalArgumentException if a cross-field rule is violated.
     */
    public static Trigger of(JsonNode triggerNode) {
        if (triggerNode == null || triggerNode.isNull()) {
            return ALWAYS;
        }
        JsonNode conditions = triggerNode.get("conditions");
        if (conditions == null || !conditions.isArray() || conditions.isEmpty()) {
            throw new IllegalArgumentException(
                    "A trigger must declare at least one condition; an absent trigger is ALWAYS.");
        }
        List<TriggerCondition> children = new ArrayList<>();
        for (JsonNode condition : conditions) {
            children.add(parseCondition(condition));
        }
        return new Trigger(children);
    }

    /**
     * Parses a single leaf condition from its JSON node.
     *
     * @param condition the condition node.
     * @return the parsed leaf.
     * @throws IllegalArgumentException if the kind is unknown or a cross-field rule fails.
     */
    private static TriggerCondition parseCondition(JsonNode condition) {
        JsonNode kindNode = condition.get("kind");
        String kind = kindNode == null ? null : kindNode.asText();
        if (kind == null) {
            throw new IllegalArgumentException("A trigger condition requires a 'kind'.");
        }
        switch (kind) {
            case "MINIMUM_AMOUNT":
                return parseMinimumAmount(condition);
            case "MINIMUM_QUANTITY":
                return parseMinimumQuantity(condition);
            case "COUPON_CODE":
                return parseCouponCode(condition);
            default:
                throw new IllegalArgumentException("Unknown trigger condition kind '" + kind + "'.");
        }
    }

    /**
     * Parses a {@code MINIMUM_AMOUNT} condition, enforcing the scope/eans cross-rules.
     *
     * @param condition the condition node.
     * @return the parsed condition.
     * @throws IllegalArgumentException if scope/eans rules or the threshold are violated.
     */
    private static TriggerCondition parseMinimumAmount(JsonNode condition) {
        JsonNode scopeNode = condition.get("scope");
        if (scopeNode == null) {
            throw new IllegalArgumentException("MINIMUM_AMOUNT requires a 'scope'.");
        }
        MinimumAmountCondition.Scope scope;
        try {
            scope = MinimumAmountCondition.Scope.valueOf(scopeNode.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown MINIMUM_AMOUNT scope '" + scopeNode.asText() + "'.");
        }
        Set<String> eans = parseEans(condition);
        if (scope == MinimumAmountCondition.Scope.ITEMS && eans.isEmpty()) {
            throw new IllegalArgumentException("MINIMUM_AMOUNT scope ITEMS requires a non-empty 'eans'.");
        }
        if (scope == MinimumAmountCondition.Scope.TICKET && !eans.isEmpty()) {
            throw new IllegalArgumentException("MINIMUM_AMOUNT scope TICKET forbids 'eans'.");
        }
        return new MinimumAmountCondition(scope, eans, requireThreshold(condition));
    }

    /**
     * Parses a {@code MINIMUM_QUANTITY} condition, requiring a non-empty EAN list.
     *
     * @param condition the condition node.
     * @return the parsed condition.
     * @throws IllegalArgumentException if the EAN list is empty or the threshold is missing.
     */
    private static TriggerCondition parseMinimumQuantity(JsonNode condition) {
        Set<String> eans = parseEans(condition);
        if (eans.isEmpty()) {
            throw new IllegalArgumentException("MINIMUM_QUANTITY requires a non-empty 'eans'.");
        }
        return new MinimumQuantityCondition(eans, requireThreshold(condition));
    }

    /**
     * Parses a {@code COUPON_CODE} condition, requiring a non-blank code.
     *
     * @param condition the condition node.
     * @return the parsed condition.
     * @throws IllegalArgumentException if the code is missing or blank.
     */
    private static TriggerCondition parseCouponCode(JsonNode condition) {
        JsonNode codeNode = condition.get("code");
        if (codeNode == null || codeNode.asText().trim().isEmpty()) {
            throw new IllegalArgumentException("COUPON_CODE requires a non-blank 'code'.");
        }
        return new CouponCodeCondition(codeNode.asText());
    }

    /**
     * Extracts and deduplicates the EANs of a condition node.
     *
     * @param condition the condition node.
     * @return the deduplicated EANs, preserving order; empty when absent.
     */
    private static Set<String> parseEans(JsonNode condition) {
        Set<String> eans = new LinkedHashSet<>();
        JsonNode eansNode = condition.get("eans");
        if (eansNode != null && eansNode.isArray()) {
            for (JsonNode ean : eansNode) {
                eans.add(ean.asText());
            }
        }
        return eans;
    }

    /**
     * Reads the required numeric {@code threshold} of a condition node.
     *
     * @param condition the condition node.
     * @return the threshold as a {@link BigDecimal}.
     * @throws IllegalArgumentException if the threshold is missing or not a number.
     */
    private static BigDecimal requireThreshold(JsonNode condition) {
        JsonNode thresholdNode = condition.get("threshold");
        if (thresholdNode == null || !thresholdNode.isNumber()) {
            throw new IllegalArgumentException("This trigger condition requires a numeric 'threshold'.");
        }
        return thresholdNode.decimalValue();
    }

    /**
     * Evaluates the AND node against the current basket evaluation.
     * <p>
     * Satisfied when every child is; contributors are the deduplicated union of the
     * children's contributors, kept in first-seen order (identity semantics, so the same
     * offer application is never counted twice). The empty AND ({@link #ALWAYS}) is satisfied
     * with no contributors.
     *
     * @param evaluation the evaluation context.
     * @return the combined outcome.
     */
    @Override
    public TriggerResult evaluate(BasketEvaluation evaluation) {
        boolean satisfied = true;
        LinkedHashSet<OfferApplication> contributors = new LinkedHashSet<>();
        for (TriggerCondition child : children) {
            TriggerResult outcome = child.evaluate(evaluation);
            if (!outcome.satisfied()) {
                satisfied = false;
            }
            contributors.addAll(outcome.contributors());
        }
        return new TriggerResult(satisfied, new ArrayList<>(contributors));
    }
}
