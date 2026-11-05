package com.intermarche.valuation.engine.offers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.valuation.domain.Offer;
import com.intermarche.valuation.domain.Store;
import com.intermarche.valuation.engine.AdvantageApplication;
import com.intermarche.valuation.engine.AdvantageApplier;
import com.intermarche.valuation.engine.AdvantageApplierFactory;
import com.intermarche.valuation.engine.BasketEvaluation;
import com.intermarche.valuation.engine.ConfigurationException;
import com.intermarche.valuation.engine.EngineTrait;
import com.intermarche.valuation.engine.MessageApplication;
import com.intermarche.valuation.engine.MinimumAmountCondition;
import com.intermarche.valuation.engine.OfferApplication;
import com.intermarche.valuation.engine.OfferApplier;
import com.intermarche.valuation.engine.Trigger;
import com.intermarche.valuation.engine.TriggerCondition;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for the {@code MESSAGE} advantage (spec-messages-editique §1): a canonical advantage that
 * discounts nothing but says something to the till.
 * <p>
 * It is triggered and arbitrated like any other advantage — it carries the shared {@code trigger},
 * {@code arbitration} and compliance fragments, so a message can be conditional, prioritised and
 * capped per ticket — yet it produces no {@code DiscountApplication}: no monetary effect, no budget
 * counter, score zero. Its output is the additive {@code messages[]} response block; the monetary
 * {@code advantages[]} block stays untouched (bit-for-bit when no message applies).
 * <p>
 * The {@code text} carries variables resolved at evaluation: {@code {{total}}} (current tax-included
 * total), {@code {{missingAmount}}} (the gap to the configuration's {@code MINIMUM_AMOUNT} threshold,
 * zero when triggered or thresholdless) and {@code {{storeName}}}. An unknown variable fails the
 * configuration closed (skipped and traced), never the whole evaluation.
 * <p>
 * {@code showWhen} decides when the message appears: {@code WHEN_TRIGGERED} (default) shows it when
 * the trigger is satisfied — the standard arbitration gate; {@code BELOW_TRIGGER} shows it when the
 * trigger is NOT satisfied (the incentive "how much is left" case). Because the arbitration gate only
 * runs an advantage whose trigger is satisfied, a {@code BELOW_TRIGGER} message is arbitrated with an
 * always-on trigger (its own trigger is stripped from the configuration the arbitration sees) and the
 * applier evaluates the real trigger itself to decide, keeping the arbitration engine untouched.
 */
@ApplicationScoped
public class MessageAdvantageFactory implements AdvantageApplierFactory, EngineTrait {

    private static final Logger LOGGER = Logger.getLogger(MessageAdvantageFactory.class);

    /**
     * The offer type discriminator handled by this factory.
     */
    public static final String OFFER_TYPE = "MESSAGE";

    /**
     * The {@code showWhen} value showing the message when the trigger is NOT satisfied.
     */
    private static final String BELOW_TRIGGER = "BELOW_TRIGGER";

    /**
     * The mapper used to strip the trigger block for a {@code BELOW_TRIGGER} configuration.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A {@code {{variable}}} token, capturing the variable name.
     */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*}}");

    /**
     * JSON Schema for the message specification (spec §1): {@code text} required, optional
     * {@code showWhen}. The {@code printMode} is provided by the shared injected fragment.
     */
    private static final String OFFER_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "Message Offer Specification",
      "description": "A till message with resolved variables; no monetary effect.",
      "type": "object",
      "additionalProperties": false,
      "required": ["text"],
      "properties": {
        "text": {
          "type": "string",
          "minLength": 1,
          "description": "The message text, with {{total}} / {{missingAmount}} / {{storeName}} variables.",
          "x-label": "Message text"
        },
        "showWhen": {
          "enum": ["WHEN_TRIGGERED", "BELOW_TRIGGER"],
          "default": "WHEN_TRIGGERED",
          "description": "Show the message when the trigger is satisfied, or below its threshold.",
          "x-label": "Show when"
        }
      }
    }
    """;

    /**
     * Returns the offer type handled by this factory.
     *
     * @return the {@code "MESSAGE"} discriminator.
     */
    @Override
    public String getOfferType() {
        return OFFER_TYPE;
    }

    /**
     * Returns the JSON Schema describing the message specification.
     *
     * @return the JSON Schema as a string.
     */
    @Override
    public String getSchema() {
        return OFFER_SCHEMA;
    }

    /**
     * Builds a message applier for every in-force {@code MESSAGE} configuration of the store.
     *
     * @param basketEvaluation the basket evaluation (store and groups context).
     * @return the message appliers, empty when no configuration is in force.
     * @throws IllegalStateException    if the context carries no basket.
     * @throws IllegalArgumentException if a specification violates the schema.
     */
    @Override
    public Collection<AdvantageApplier> buildAppliers(BasketEvaluation basketEvaluation) {
        List<AdvantageApplier> appliers = new ArrayList<>();
        getBasket(basketEvaluation, "Cannot create MESSAGE appliers without a valid basket.");
        Store store = basketEvaluation.getStore();
        for (Offer offer : getOffers(basketEvaluation, OFFER_TYPE)) {
            processOffer(offer, appliers, store);
        }
        return appliers;
    }

    /**
     * Validates one message specification against the schema and adds the corresponding applier.
     *
     * @param offer    the offer to process.
     * @param appliers the list receiving the created applier.
     * @param store    the store, for the {@code {{storeName}}} variable.
     * @throws IllegalArgumentException if the specification violates the schema.
     */
    private void processOffer(Offer offer, List<AdvantageApplier> appliers, Store store) {
        this.processSpecification(OFFER_SCHEMA, offer, (spec) -> {
            String text = spec.get("text").asText();
            String showWhen = spec.hasNonNull("showWhen") ? spec.get("showWhen").asText() : "WHEN_TRIGGERED";
            String printMode = spec.hasNonNull("printMode") ? spec.get("printMode").asText() : null;
            Trigger trigger = Trigger.of(spec.get("trigger"));
            Offer arbitrated = BELOW_TRIGGER.equals(showWhen) ? strippedOffer(offer) : offer;
            appliers.add(new MessageAdvantageApplier(offer.code, text, showWhen, printMode,
                    trigger, arbitrated, store));
        });
    }

    /**
     * Returns a copy of the offer whose specification carries no {@code trigger} block, so the
     * arbitration sees an always-on trigger while its other parameters (priority, per-ticket cap)
     * are preserved. Used for {@code BELOW_TRIGGER}, whose show condition is the inverse of the gate.
     *
     * @param offer the original offer.
     * @return the trigger-stripped copy, or the original when its specification cannot be parsed.
     */
    private Offer strippedOffer(Offer offer) {
        try {
            JsonNode root = MAPPER.readTree(offer.specification);
            if (root instanceof ObjectNode object) {
                object.remove("trigger");
                Offer copy = new Offer();
                copy.code = offer.code;
                copy.type = offer.type;
                copy.specification = MAPPER.writeValueAsString(object);
                return copy;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not strip the trigger of MESSAGE " + offer.code + ": " + e.getMessage());
        }
        return offer;
    }

    /**
     * The message applier: decides whether the message shows, resolves its variables, and produces a
     * single {@link MessageApplication} — never a discount.
     */
    public static class MessageAdvantageApplier implements AdvantageApplier {

        /**
         * The configuration code.
         */
        private final String code;

        /**
         * The raw message text, with variables.
         */
        private final String text;

        /**
         * {@code WHEN_TRIGGERED} or {@code BELOW_TRIGGER}.
         */
        private final String showWhen;

        /**
         * The print mode ({@code LINE}/{@code FOOTER}) copied to the output, or null.
         */
        private final String printMode;

        /**
         * The configuration's real trigger, evaluated to compute the amounts and, for
         * {@code BELOW_TRIGGER}, the show decision.
         */
        private final Trigger trigger;

        /**
         * The configuration the arbitration reads (trigger-stripped for {@code BELOW_TRIGGER}).
         */
        private final Offer configuration;

        /**
         * The store, for the {@code {{storeName}}} variable.
         */
        private final Store store;

        /**
         * Builds a message applier.
         *
         * @param code          the configuration code.
         * @param text          the raw message text.
         * @param showWhen      the show mode.
         * @param printMode     the print mode, or null.
         * @param trigger       the real trigger.
         * @param configuration the configuration the arbitration reads.
         * @param store         the store, may be null.
         */
        public MessageAdvantageApplier(String code, String text, String showWhen, String printMode,
                                       Trigger trigger, Offer configuration, Store store) {
            this.code = code;
            this.text = text;
            this.showWhen = showWhen;
            this.printMode = printMode;
            this.trigger = trigger;
            this.configuration = configuration;
            this.store = store;
        }

        /**
         * A message never registers on an offer applier: it constitutes no assiette.
         *
         * @param offerApplier the offer applier to check.
         * @return always {@code false}.
         */
        @Override
        public boolean isApplicable(OfferApplier offerApplier) {
            return false;
        }

        /**
         * Returns the sandbox efficiency score, zero by convention: a message competes with no one.
         *
         * @return {@code 0.0}.
         */
        @Override
        public double getEfficiencyScore() {
            return 0.0;
        }

        /**
         * Returns the configuration the arbitration reads.
         *
         * @return the configuration.
         */
        @Override
        public Offer getConfiguration() {
            return configuration;
        }

        /**
         * Produces the message when it must show, resolving its variables.
         * <p>
         * {@code WHEN_TRIGGERED}: the arbitration already gated on the satisfied trigger, so the
         * message shows. {@code BELOW_TRIGGER}: the applier evaluates the real trigger and shows only
         * when it is NOT satisfied. When the message does not show, an empty result leaves the
         * arbitration to move on.
         *
         * @param evaluation the evaluation context.
         * @return the single message application, or an empty list when the message does not show.
         * @throws ConfigurationException when the text carries an unknown variable (fail-closed).
         */
        @Override
        public Collection<AdvantageApplication> apply(BasketEvaluation evaluation) {
            boolean satisfied = trigger.evaluate(evaluation).satisfied();
            boolean show = BELOW_TRIGGER.equals(showWhen) ? !satisfied : satisfied;
            if (!show) {
                return List.of();
            }
            String resolved = resolve(evaluation);
            return List.of(new MessageAdvantageApplication(code, resolved, printMode));
        }

        /**
         * Resolves the variables of the text, failing closed on an unknown one.
         *
         * @param evaluation the evaluation context.
         * @return the resolved text.
         * @throws ConfigurationException when a variable is not recognised.
         */
        private String resolve(BasketEvaluation evaluation) {
            Matcher matcher = VARIABLE.matcher(text);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = switch (name) {
                    case "total" -> plain(ticketTotal(evaluation));
                    case "missingAmount" -> plain(missingAmount(evaluation));
                    case "storeName" -> store == null || store.name == null ? "" : store.name;
                    default -> throw new ConfigurationException(
                            "MESSAGE " + code + " uses an unknown variable '{{" + name + "}}'.");
                };
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        /**
         * Computes the current tax-included ticket total ({@code {{total}}}).
         *
         * @param evaluation the evaluation context.
         * @return the current ticket total, tax included.
         */
        private BigDecimal ticketTotal(BasketEvaluation evaluation) {
            return new MinimumAmountCondition(MinimumAmountCondition.Scope.TICKET, Set.of(),
                    BigDecimal.ZERO).measure(evaluation);
        }

        /**
         * Computes the amount still missing to the configuration's {@code MINIMUM_AMOUNT} threshold
         * ({@code {{missingAmount}}}): zero when triggered (measured amount at or above the threshold)
         * or when the configuration declares no such condition.
         *
         * @param evaluation the evaluation context.
         * @return the missing amount, tax included, never negative.
         */
        private BigDecimal missingAmount(BasketEvaluation evaluation) {
            for (TriggerCondition child : trigger.getChildren()) {
                if (child instanceof MinimumAmountCondition minimum) {
                    BigDecimal gap = minimum.getThreshold().subtract(minimum.measure(evaluation));
                    return gap.signum() < 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                            : gap.setScale(2, RoundingMode.HALF_UP);
                }
            }
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        /**
         * Renders an amount in plain notation at scale 2.
         *
         * @param amount the amount.
         * @return the plain string.
         */
        private String plain(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }

    /**
     * The message produced for the till (spec §1): a {@link MessageApplication} with no monetary
     * effect, surfaced in the {@code messages[]} block and kept out of {@code advantages[]}.
     */
    public static class MessageAdvantageApplication implements MessageApplication {

        /**
         * The application moment restituted in the response, set by the arbitration; AT_TOTAL here.
         */
        private String applicationMoment = "AT_TOTAL";

        /**
         * The configuration code.
         */
        private final String code;

        /**
         * The resolved message text.
         */
        private final String text;

        /**
         * The print mode, or null.
         */
        private final String printMode;

        /**
         * Builds the message application.
         *
         * @param code      the configuration code.
         * @param text      the resolved message text.
         * @param printMode the print mode, or null.
         */
        public MessageAdvantageApplication(String code, String text, String printMode) {
            this.code = code;
            this.text = text;
            this.printMode = printMode;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getCode() {
            return code;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getText() {
            return text;
        }

        /**
         * Returns the print mode copied from the configuration.
         *
         * @return the print mode ({@code LINE}/{@code FOOTER}), or null.
         */
        @Override
        public String getPrintMode() {
            return printMode;
        }

        /**
         * Records the print mode; a no-op, the message carries its own from construction.
         *
         * @param printMode ignored.
         */
        @Override
        public void setPrintMode(String printMode) {
            // The message keeps the print mode it was built with.
        }

        /**
         * Returns the display type of this advantage.
         *
         * @return {@code "Message: <code>"}.
         */
        public String getType() {
            return "Message: " + code;
        }

        /**
         * Returns the display offer; the message has no targeted offer, so its own type stands in.
         *
         * @return the same string as {@link #getType()}.
         */
        @Override
        public String getOffer() {
            return getType();
        }

        /**
         * Returns the application moment.
         *
         * @return the application moment, AT_TOTAL until the arbitration sets it.
         */
        @Override
        public String getApplicationMoment() {
            return applicationMoment;
        }

        /**
         * Records the application moment set by the arbitration.
         *
         * @param applicationMoment the moment.
         */
        @Override
        public void setApplicationMoment(String applicationMoment) {
            this.applicationMoment = applicationMoment;
        }

        /**
         * Returns no targeted offer application: a message modulates no price.
         *
         * @return always {@code null}.
         */
        @Override
        @JsonIgnore
        public OfferApplication getOfferApplication() {
            return null;
        }
    }
}
