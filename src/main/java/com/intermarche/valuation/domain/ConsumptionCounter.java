package com.intermarche.valuation.domain;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The aggregated generosity consumption of one offer at one point of sale.
 * <p>
 * The engine controls a budget at the arbitration by reading THESE counters, at
 * home, without any network call (spec §5.1). The counters are the comptable
 * truth: they are fed exclusively by the declared consumption of cashed tickets
 * ({@code POST /consumptions}), never by a valuation, and are kept up to date
 * transactionally by the consumption ingestion (spec §3, §4).
 * <p>
 * The key is the pair {@code (offerCode, storeCode)}. An offer's global counter
 * is the sum of its per-store rows; a group's counter is the sum of the counters
 * of its offers (spec §4) — neither is stored, both are summed on read.
 * <p>
 * {@link #consumedAmount} is the cumulated absolute value of the advantage
 * granted (euros, scale 2); {@link #consumedApplications} is the number of
 * tickets where the offer applied (not the number of lines). A CANCELLATION
 * decrements both; a counter may temporarily read negative when a cancellation
 * is relayed before its sale (spec §4).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "consumption_counters",
        uniqueConstraints = @UniqueConstraint(name = "uq_counter_offer_store",
                columnNames = {"offer_code", "store_code"}),
        indexes = @Index(name = "idx_counter_offer", columnList = "offer_code"))
@Cacheable
public class ConsumptionCounter extends BaseEntity {

    /**
     * The offer code the counter aggregates; kept even when the offer has been deleted from the
     * referential, because the counter is a comptable truth (spec §4).
     */
    @Column(name = "offer_code", nullable = false, length = 50)
    @NotBlank(message = "Offer code is mandatory")
    public String offerCode;

    /**
     * The point-of-sale code the counter is scoped to.
     */
    @Column(name = "store_code", nullable = false, length = 20)
    @NotBlank(message = "Store code is mandatory")
    public String storeCode;

    /**
     * The cumulated absolute value of the advantage granted, in euros, scale 2.
     */
    @Column(name = "consumed_amount", nullable = false, precision = 19, scale = 2)
    public BigDecimal consumedAmount = BigDecimal.ZERO;

    /**
     * The number of tickets where the offer applied.
     */
    @Column(name = "consumed_applications", nullable = false)
    public long consumedApplications = 0L;

    /**
     * Default constructor for JPA.
     */
    public ConsumptionCounter() {
    }

    /**
     * Creates a zeroed counter for a pair.
     *
     * @param offerCode the offer code
     * @param storeCode the point-of-sale code
     */
    public ConsumptionCounter(String offerCode, String storeCode) {
        this.offerCode = offerCode;
        this.storeCode = storeCode;
    }

    /**
     * Finds the counter of an exact {@code (offerCode, storeCode)} pair.
     *
     * @param offerCode the offer code
     * @param storeCode the point-of-sale code
     * @return the counter, or null when no consumption was ever declared for that pair
     */
    public static ConsumptionCounter findCounter(String offerCode, String storeCode) {
        return find("offerCode = ?1 and storeCode = ?2", offerCode, storeCode).firstResult();
    }

    /**
     * Lists the per-store counters of one offer, in store-code order.
     *
     * @param offerCode the offer code
     * @return the counters, empty when the offer consumed nothing
     */
    public static List<ConsumptionCounter> listForOffer(String offerCode) {
        return list("offerCode = ?1 order by storeCode", offerCode);
    }

    /**
     * Lists the counters of a set of offers, in offer-code then store-code order.
     *
     * @param offerCodes the offer codes
     * @return the counters, empty when none of the offers consumed anything
     */
    public static List<ConsumptionCounter> listForOffers(Collection<String> offerCodes) {
        if (offerCodes == null || offerCodes.isEmpty()) {
            return List.of();
        }
        return list("offerCode in ?1 order by offerCode, storeCode", offerCodes);
    }

    /**
     * Sums the consumed amount of a set of offers, optionally scoped to one point of sale.
     *
     * @param offerCodes the offer codes to sum over
     * @param storeCode  the point-of-sale code, or null to sum across every point of sale
     * @return the cumulated amount, {@link BigDecimal#ZERO} when nothing was consumed
     */
    public static BigDecimal sumAmount(Collection<String> offerCodes, String storeCode) {
        BigDecimal total = BigDecimal.ZERO;
        for (ConsumptionCounter counter : select(offerCodes, storeCode)) {
            if (counter.consumedAmount != null) {
                total = total.add(counter.consumedAmount);
            }
        }
        return total;
    }

    /**
     * Sums the consumed applications of a set of offers, optionally scoped to one point of sale.
     *
     * @param offerCodes the offer codes to sum over
     * @param storeCode  the point-of-sale code, or null to sum across every point of sale
     * @return the cumulated number of applications, zero when nothing was consumed
     */
    public static long sumApplications(Collection<String> offerCodes, String storeCode) {
        long total = 0L;
        for (ConsumptionCounter counter : select(offerCodes, storeCode)) {
            total += counter.consumedApplications;
        }
        return total;
    }

    /**
     * Selects the counters of a set of offers, optionally scoped to one point of sale.
     *
     * @param offerCodes the offer codes
     * @param storeCode  the point-of-sale code, or null for every point of sale
     * @return the matching counters, empty when the set is empty or nothing matched
     */
    private static List<ConsumptionCounter> select(Collection<String> offerCodes, String storeCode) {
        if (offerCodes == null || offerCodes.isEmpty()) {
            return List.of();
        }
        if (storeCode == null) {
            return list("offerCode in ?1", offerCodes);
        }
        return list("offerCode in ?1 and storeCode = ?2", offerCodes, storeCode);
    }

    /**
     * Calculates a checksum based on the counter's key and values.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(offerCode, storeCode, consumedAmount, consumedApplications);
    }
}
