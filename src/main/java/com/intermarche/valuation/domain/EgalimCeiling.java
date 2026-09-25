package com.intermarche.valuation.domain;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * The ADMINISTERED ceiling of one EGAlim regime: the number of percent the
 * store node states today for a regime code.
 *
 * <p>Why a table next to the {@link EgalimRegime} enum, and not instead of it.
 * The enum is the CLASSIFICATION: it is what an article carries, what the
 * product feed's {@code EGALIM_REGIME} column states, and what
 * {@link Product#egalimRegime} holds. This table is the CEILING: the figure
 * the law attaches to that classification on a given day. The two are not the
 * same object and they do not move at the same speed — an article changes
 * category when the commercial management reclassifies it, a ceiling changes
 * when Parliament votes. The loi Travert already moved the DPH ceiling from
 * 34 % to 40 % without reclassifying a single article.
 *
 * <p>Until this table the ceiling lived in compiled code, in the enum's own
 * default, and a legal rate that moves by redeployment is a rate nobody can
 * correct the morning it changes. The rate is now data, it is administered on
 * the store node's EGAlim screen, and it reaches the engine as the
 * {@code EGALIM_REGIMES} feed like every other referential.
 *
 * <p>The regime CODE is the identity and the upsert key: it is the token the
 * enum carries ({@code FOOD_34}, {@code DPH_40}, {@code EXEMPT}), which is
 * what makes the join between the classification and the ceiling possible
 * without a foreign key across two feeds. A code this engine's enum does not
 * know is stored all the same and simply matches no article — the referential
 * is allowed to be ahead of the software.
 *
 * <p>An ABSENT ceiling is not a zero one: it is the absence of a ceiling, and
 * it is how the exempt category is stated. Zero would mean "no promotional
 * generosity allowed on these articles", the exact contrary, so the column is
 * nullable and {@link #isCapped()} reads the null rather than a comparison to
 * zero.
 *
 * <p>What this table is NOT: a history. A ceiling that changes legally is a
 * new value on the same code, and the already-valued baskets are unaffected
 * because a valued basket snapshots its EGAlim record at evaluation time.
 */
@Entity
@Table(name = "egalim_ceilings",
        uniqueConstraints = @UniqueConstraint(columnNames = "regime_code"))
@Cacheable
public class EgalimCeiling extends BaseEntity {

    /** The regime code, the upsert key and the token the articles carry. */
    @Column(name = "regime_code", nullable = false, unique = true, length = 30)
    @NotNull(message = "EGAlim regime code is mandatory")
    public String regimeCode;

    /** What a human reads: "Alimentaire et petfood", "Droguerie"… */
    @Column(name = "label", length = 80)
    public String label;

    /** The ceiling as a fraction: 0.3400 for thirty-four percent; null means uncapped. */
    @Column(name = "cap_rate", precision = 5, scale = 4)
    public BigDecimal capRate;

    /**
     * Default constructor for JPA.
     */
    public EgalimCeiling() {
    }

    /**
     * Creates a ceiling with its three values.
     *
     * @param regimeCode the regime code, the upsert key
     * @param label what a human reads, or null
     * @param capRate the ceiling as a fraction, or null for an uncapped regime
     */
    public EgalimCeiling(String regimeCode, String label, BigDecimal capRate) {
        this.regimeCode = regimeCode;
        this.label = label;
        this.capRate = capRate;
    }

    /**
     * Finds the administered ceiling of a regime code.
     *
     * @param regimeCode the regime code, possibly null
     * @return the row, or null when the referential states nothing for that code
     */
    public static EgalimCeiling findByCode(String regimeCode) {
        if (regimeCode == null) {
            return null;
        }
        return find("regimeCode", regimeCode).firstResult();
    }

    /**
     * Lists every administered ceiling in code order.
     *
     * @return the rows, empty when the referential holds none
     */
    public static List<EgalimCeiling> listAllOrdered() {
        return list("order by regimeCode");
    }

    /**
     * Returns what a document prints in front of the ceiling.
     *
     * @return the label, or the regime code when the row carries none
     */
    public String getLabel() {
        return label == null || label.isBlank() ? regimeCode : label;
    }

    /**
     * Tells whether this regime is capped at all.
     *
     * <p>Reads the NULL and not a comparison to zero: a ceiling of zero is a
     * regime allowing no generosity, which is a rule someone wrote; an absent
     * ceiling is a regime nobody capped.
     *
     * @return true when the referential states a ceiling for this regime
     */
    public boolean isCapped() {
        return capRate != null;
    }

    /**
     * Returns the ceiling as a percentage, the way a document states it.
     *
     * @return the percentage with its needless decimals dropped ("34", "33,5"),
     *         or an empty string when the regime is uncapped
     */
    public String getCapFormatted() {
        if (capRate == null) {
            return "";
        }
        BigDecimal percent = capRate.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return percent.toPlainString().replace('.', ',');
    }

    /**
     * The fingerprint input of this row for the referential export.
     *
     * @return a hash of the code, the label and the ceiling
     */
    @Override
    public int getChecksum() {
        return Objects.hash(regimeCode, label, capRate);
    }
}
