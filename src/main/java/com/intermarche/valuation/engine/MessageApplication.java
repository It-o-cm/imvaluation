package com.intermarche.valuation.engine;

/**
 * An advantage that carries a till message rather than a monetary effect (spec-messages-editique §1).
 * <p>
 * It flows through the arbitration like any other advantage — so triggers, priority and per-ticket
 * limits apply — but produces no discount. The engine surfaces it in the dedicated {@code messages[]}
 * response block and keeps it out of the monetary {@code advantages[]} block; this marker is how
 * {@link BasketEvaluation} tells the two apart.
 */
public interface MessageApplication extends AdvantageApplication {

    /**
     * Returns the configuration code the message was declared under.
     *
     * @return the offer code.
     */
    String getCode();

    /**
     * Returns the resolved message text, with its variables substituted.
     *
     * @return the text shown at the till.
     */
    String getText();
}
