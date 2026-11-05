package com.intermarche.valuation.engine;

/**
 * A request-level refusal of a {@code POST /display-valuation} call
 * (spec-valorisation-affichage-ecommerce §2, §6): the EAN cap exceeded, an unreadable or missing
 * date, a missing store code, or an unknown store. It carries the HTTP status the resource must
 * answer and a message naming the cause, so the caller learns what to correct.
 * <p>
 * It is distinct from a per-EAN referential gap, which does not fail the batch: an unknown EAN is
 * reported on its own output entry while the other EANs are valued normally (§7 criterion 5).
 */
public class DisplayValuationException extends RuntimeException {

    /**
     * The HTTP status the resource answers with (400 for a malformed request, 422 for an unknown
     * store — the referential-gap contract of {@code /valuation}).
     */
    private final int status;

    /**
     * Builds a refusal.
     *
     * @param status  the HTTP status to answer.
     * @param message the message naming the cause.
     */
    public DisplayValuationException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * Returns the HTTP status to answer with.
     *
     * @return the status.
     */
    public int getStatus() {
        return status;
    }
}
