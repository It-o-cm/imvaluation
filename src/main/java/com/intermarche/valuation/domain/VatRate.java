package com.intermarche.valuation.domain;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * One VAT regime of the referential: a number, a rate and a label.
 *
 * <p>A table rather than a decimal on every price, because a regime is NAMED
 * before it is a number of percent. The documents the engine feeds are asked
 * for the VAT NUMBER — the ticket's VAT table and the invoice both state it —
 * and a rate copied onto a hundred thousand price rows cannot be numbered,
 * cannot be relabelled, and cannot be corrected anywhere but in the feed that
 * wrote it.
 *
 * <p>The number is the identity and the upsert key: the commercial management
 * numbers a regime once and every price points at that number. The rate is a
 * FRACTION — {@code 0.0550} for five and a half percent — which is the form
 * every computation in the suite already expects.
 *
 * <p>What this table is NOT: a history. A rate that changes legally is a new
 * value on the same number, and the valued lines are unaffected because a
 * valued line snapshots the rate at evaluation time. A ticket reprinted two
 * years later therefore states the rate of its day, not the rate of today.
 */
@Entity
@Table(name = "vat_rates",
        uniqueConstraints = @UniqueConstraint(columnNames = "vat_number"))
@Cacheable
public class VatRate extends BaseEntity {

    /** The number the commercial management knows this regime by, the upsert key. */
    @Column(name = "vat_number", nullable = false, unique = true)
    @NotNull(message = "VAT number is mandatory")
    public Integer number;

    /** The rate as a fraction: 0.2000 for twenty percent. */
    @Column(name = "rate", nullable = false, precision = 5, scale = 4)
    @NotNull(message = "VAT rate is mandatory")
    @PositiveOrZero(message = "VAT rate must be positive")
    public BigDecimal rate;

    /** What a human reads: "Taux normal", "Taux réduit"… */
    @Column(name = "label", length = 60)
    public String label;

    /**
     * Default constructor for JPA.
     */
    public VatRate() {
    }

    /**
     * Creates a regime with its three values.
     *
     * @param number the number, the upsert key
     * @param rate the rate as a fraction
     * @param label what a human reads, or null
     */
    public VatRate(Integer number, BigDecimal rate, String label) {
        this.number = number;
        this.rate = rate;
        this.label = label;
    }

    /**
     * Finds a regime by its number.
     *
     * @param number the number, possibly null
     * @return the regime, or null when no row carries that number
     */
    public static VatRate findByNumber(Integer number) {
        if (number == null) {
            return null;
        }
        return find("number", number).firstResult();
    }

    /**
     * Finds a regime by its rate.
     *
     * <p>The way a feed that states rates rather than numbers — every
     * historical price file, and the engine feed which computes with the rate
     * — is attached to the referential. Two regimes sharing a rate is a
     * referential anomaly, not a case to arbitrate: the first row wins.
     *
     * @param rate the rate as a fraction, possibly null
     * @return the regime, or null when no row carries that rate
     */
    public static VatRate findByRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        return find("rate", rate).firstResult();
    }

    /**
     * Lists every regime in number order.
     *
     * @return the regimes, empty when the referential holds none
     */
    public static List<VatRate> listAllOrdered() {
        return list("order by number");
    }

    /**
     * Returns what a document prints in front of the rate.
     *
     * @return the label, or the number when the regime carries none
     */
    public String getLabel() {
        return label == null || label.isBlank() ? String.valueOf(number) : label;
    }

    /**
     * Returns the rate as a percentage, the way a document states it.
     *
     * @return the percentage with its needless decimals dropped ("5,5"), or an
     *         empty string when the regime carries no rate
     */
    public String getRateFormatted() {
        if (rate == null) {
            return "";
        }
        BigDecimal percent = rate.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return percent.toPlainString().replace('.', ',');
    }

    /**
     * The fingerprint input of this regime for the referential export.
     *
     * @return a hash of the number, the rate and the label
     */
    @Override
    public int getChecksum() {
        return Objects.hash(number, rate, label);
    }
}
