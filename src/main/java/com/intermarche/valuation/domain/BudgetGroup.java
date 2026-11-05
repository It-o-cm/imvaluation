package com.intermarche.valuation.domain;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A generosity budget group: the ONLY thing the engine knows of a "campaign".
 * <p>
 * The "campaign" is an impos concept — its label, notes, association and
 * duplication all live in the back-office. The engine receives only a
 * regrouping code that carries a shared envelope and a period, because that is
 * the sole thing the valuation needs (spec §2, arbitrage 2). Several offers may
 * point at the same group; the group's envelope is then the ceiling of their
 * combined generosity, and the group's period bounds the period of the offers
 * attached to it — never the other way round (spec §5.4).
 * <p>
 * The envelope has two independent caps, either or both nullable: {@link #maxAmount}
 * in euros (the cumulated absolute value of the advantages granted) and
 * {@link #maxApplications} in number of tickets. {@link #perStore} tells whether
 * the ceiling is understood globally (false) or per point of sale (true).
 * <p>
 * The group's own consumption is never stored: it is computed by summing the
 * {@link ConsumptionCounter} of its offers (spec §4).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "budget_groups",
        uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Cacheable
public class BudgetGroup extends BaseEntity {

    /**
     * The unique business key of the group — the code of the campaign on the impos side.
     */
    @Column(name = "code", nullable = false, unique = true, length = 50)
    @NotBlank(message = "Budget group code is mandatory")
    public String code;

    /**
     * Start of the group period (inclusive); null means no lower bound (activation pattern,
     * open bound), so a group without a start is in force from the beginning.
     */
    @Column(name = "valid_from")
    public LocalDateTime validFrom;

    /**
     * End of the group period (exclusive); null leaves the window open-ended.
     */
    @Column(name = "valid_to")
    public LocalDateTime validTo;

    /**
     * The envelope in euros: the cumulated absolute value of the advantages granted, scale 2;
     * null means no amount ceiling.
     */
    @Column(name = "max_amount", precision = 19, scale = 2)
    public BigDecimal maxAmount;

    /**
     * The envelope in number of tickets where an offer of the group applied; null means no
     * application ceiling.
     */
    @Column(name = "max_applications")
    public Integer maxApplications;

    /**
     * Whether the ceiling is understood PER point of sale (true) or globally over every PDV
     * (false, the default).
     */
    @Column(name = "per_store", nullable = false)
    public boolean perStore = false;

    /**
     * Default constructor for JPA.
     */
    public BudgetGroup() {
    }

    /**
     * Creates a budget group with its business values.
     *
     * @param code            the unique group code
     * @param validFrom       the inclusive start bound, or null for an open bound
     * @param validTo         the exclusive end bound, or null for an open bound
     * @param maxAmount       the amount envelope in euros, or null for no amount ceiling
     * @param maxApplications the ticket envelope, or null for no application ceiling
     * @param perStore        whether the ceiling is understood per point of sale
     */
    public BudgetGroup(String code, LocalDateTime validFrom, LocalDateTime validTo,
                       BigDecimal maxAmount, Integer maxApplications, boolean perStore) {
        this.code = code;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.maxAmount = maxAmount;
        this.maxApplications = maxApplications;
        this.perStore = perStore;
    }

    /**
     * Finds a budget group by its unique code.
     *
     * @param code the group code, possibly null
     * @return the group, or null when no group carries that code
     */
    public static BudgetGroup findByCode(String code) {
        if (code == null) {
            return null;
        }
        return find("code", code).firstResult();
    }

    /**
     * Lists every budget group in code order.
     *
     * @return the groups, empty when none exists
     */
    public static List<BudgetGroup> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Indicates whether the group is in force at the given instant: inside its period
     * (validFrom inclusive, validTo exclusive). A null bound is open.
     *
     * @param at the instant to test; when null the current engine time is used
     * @return true when the instant falls inside the group period
     */
    public boolean isInForceAt(LocalDateTime at) {
        LocalDateTime moment = at != null ? at
                : com.intermarche.valuation.domain.util.DateTimeProvider.now();
        if (validFrom != null && moment.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || moment.isBefore(validTo);
    }

    /**
     * Calculates a checksum based on the group's business fields.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, validFrom, validTo, maxAmount, maxApplications, perStore);
    }
}
