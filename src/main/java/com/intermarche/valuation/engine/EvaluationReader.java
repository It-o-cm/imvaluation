package com.intermarche.valuation.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a serialized {@link BasketEvaluation} — the {@code /valuation} answer of a closed order,
 * which the selling front (the till, the e-commerce platform or its store node) keeps with the
 * order and hands back to the stateless return valuation (digital sheet DG-01-03).
 * <p>
 * The engine keeps no basket: the return works from the very document the front received at
 * close, rather than from a re-computation, so what was paid is what is restituted. This reader
 * extracts the three views the return needs:
 * <ul>
 *   <li>the advantages, in the shape the till's consumption outbox declares to
 *       {@code /consumptions} ({@code offerCode}, {@code amount}, {@code lines[ean, amount]},
 *       {@code funding}), so the caller can declare back what a return cancels;</li>
 *   <li>the offer codes the evaluation applied;</li>
 *   <li>the amount paid per request line, every discount spread over the lines it targets — a
 *       basket-wide discount over every line, pro-rata of the line amounts.</li>
 * </ul>
 * An advantage names its offer code either in an {@code offerCode} field or, for most kinds, in
 * its label, which the engine always writes as {@code "<Kind>: <CODE> ..."}.
 */
public final class EvaluationReader {

    /**
     * The key prefix of a basket-level charge (a delivery, a deposit basket) in the paid lines.
     */
    static final String CHARGE_PREFIX = "offer:";

    /**
     * Mapper for the order documents: nulls omitted, decimals exact and in plain notation,
     * unknown fields tolerated on read.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    /**
     * Not instantiable.
     */
    private EvaluationReader() {
    }

    /**
     * Serializes a document with {@link #MAPPER}.
     *
     * @param value the document.
     * @return the JSON text.
     * @throws IllegalStateException when the document cannot be serialized.
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize the document.", e);
        }
    }

    /**
     * Deserializes a document with {@link #MAPPER}.
     *
     * @param json the JSON text.
     * @param type the target type.
     * @param <T>  the target type.
     * @return the document.
     * @throws IllegalStateException when the text cannot be read as the type.
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the document: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a JSON node to a type, through its exact text.
     *
     * @param node the node.
     * @param type the target type.
     * @param <T>  the target type.
     * @return the document.
     */
    public static <T> T convert(JsonNode node, Class<T> type) {
        return read(write(node), type);
    }

    /**
     * Reads a JSON number as a decimal.
     *
     * @param node the node, possibly missing.
     * @return the decimal, or null when the node is not a number.
     */
    public static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    /**
     * Reads a textual field of a node, blank values reading as absent.
     *
     * @param node  the node holding the field.
     * @param field the field name.
     * @return the trimmed text, or null when absent, not textual or blank.
     */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    /**
     * Reads the tax-included total of an evaluation.
     *
     * @param evaluation the serialized evaluation.
     * @return the total, zero when absent.
     */
    public static BigDecimal totalIncludingTax(JsonNode evaluation) {
        BigDecimal total = decimal(evaluation.path("totalPrice").path("amountIncludingTax"));
        return total == null ? BigDecimal.ZERO.setScale(2) : total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Reads the tax-excluded total of an evaluation.
     *
     * @param evaluation the serialized evaluation.
     * @return the total, zero when absent.
     */
    public static BigDecimal totalExcludingTax(JsonNode evaluation) {
        BigDecimal total = decimal(evaluation.path("totalPrice").path("amountExcludingTax"));
        return total == null ? BigDecimal.ZERO.setScale(2) : total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Resolves the offer code of an offer or advantage node: its {@code offerCode} field when
     * present, else the token following {@code ": "} in its label.
     *
     * @param node the offer or advantage node.
     * @return the offer code, or null when the node names none.
     */
    public static String offerCodeOf(JsonNode node) {
        String explicit = text(node, "offerCode");
        if (explicit != null) {
            return explicit;
        }
        String type = text(node, "type");
        if (type == null) {
            return null;
        }
        int colon = type.indexOf(": ");
        if (colon < 0) {
            return null;
        }
        String rest = type.substring(colon + 2).trim();
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    /**
     * Lists the offer codes an evaluation applied, offers then advantages, without duplicates.
     *
     * @param evaluation the serialized evaluation.
     * @return the codes in order of appearance.
     */
    public static Set<String> appliedCodes(JsonNode evaluation) {
        Set<String> codes = new LinkedHashSet<>();
        for (String section : List.of("offers", "advantages")) {
            for (JsonNode node : evaluation.path(section)) {
                String code = offerCodeOf(node);
                if (code != null) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    /**
     * Builds the advantages to declare for an evaluation: every advantage that names an offer
     * and carries a tax-included discount amount, as an absolute amount, with its funding and
     * its per-article breakdown.
     *
     * @param evaluation the serialized evaluation.
     * @return the advantages, in the evaluation's order; empty when none qualifies.
     */
    public static List<ConsumptionService.Advantage> advantagesOf(JsonNode evaluation) {
        List<ConsumptionService.Advantage> result = new ArrayList<>();
        for (JsonNode node : evaluation.path("advantages")) {
            BigDecimal amount = decimal(node.path("discountAmount").path("amountIncludingTax"));
            String code = offerCodeOf(node);
            if (amount == null || code == null) {
                continue;
            }
            ConsumptionService.Advantage advantage = new ConsumptionService.Advantage();
            advantage.offerCode = code;
            advantage.amount = amount.abs().setScale(2, RoundingMode.HALF_UP);
            advantage.funding = text(node, "funding");
            advantage.lines = linesOf(evaluation, text(node, "offer"), advantage.amount);
            result.add(advantage);
        }
        return result;
    }

    /**
     * Merges advantages per offer code, as {@code /consumptions} counts them: amounts summed,
     * per-article lines concatenated, the first funding kept.
     *
     * @param advantages the advantages, possibly several per offer.
     * @return one advantage per offer code, in order of first appearance.
     */
    public static List<ConsumptionService.Advantage> perOffer(List<ConsumptionService.Advantage> advantages) {
        Map<String, ConsumptionService.Advantage> merged = new LinkedHashMap<>();
        for (ConsumptionService.Advantage advantage : advantages) {
            ConsumptionService.Advantage target = merged.get(advantage.offerCode);
            if (target == null) {
                target = new ConsumptionService.Advantage();
                target.offerCode = advantage.offerCode;
                target.amount = BigDecimal.ZERO.setScale(2);
                target.funding = advantage.funding;
                merged.put(advantage.offerCode, target);
            }
            target.amount = target.amount.add(advantage.amount);
            if (advantage.lines != null) {
                if (target.lines == null) {
                    target.lines = new ArrayList<>();
                }
                target.lines.addAll(advantage.lines);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Sums the declared advantage amounts per offer code.
     *
     * @param evaluation the serialized evaluation.
     * @return the absolute amount earned per offer code, insertion-ordered.
     */
    public static Map<String, BigDecimal> earnedByOffer(JsonNode evaluation) {
        Map<String, BigDecimal> earned = new LinkedHashMap<>();
        for (ConsumptionService.Advantage advantage : advantagesOf(evaluation)) {
            earned.merge(advantage.offerCode, advantage.amount, BigDecimal::add);
        }
        return earned;
    }

    /**
     * Spreads an advantage amount over the articles of the offer it targets, pro-rata of their
     * tax-included amounts, the rounding residue going to the last share.
     *
     * @param evaluation the serialized evaluation.
     * @param offerType  the label of the targeted offer, or null.
     * @param amount     the absolute advantage amount.
     * @return the per-article lines, or null when the offer has no priced article.
     */
    private static List<ConsumptionService.Line> linesOf(JsonNode evaluation, String offerType,
                                                         BigDecimal amount) {
        Map<String, BigDecimal> weightByEan = new LinkedHashMap<>();
        for (JsonNode offer : evaluation.path("offers")) {
            if (offerType == null || !offerType.equals(text(offer, "type"))) {
                continue;
            }
            for (JsonNode item : offer.path("items")) {
                String ean = text(item, "produceEan");
                BigDecimal weight = decimal(item.path("amount").path("amountIncludingTax"));
                if (ean != null && weight != null) {
                    weightByEan.merge(ean, weight.abs(), BigDecimal::add);
                }
            }
        }
        BigDecimal total = weightByEan.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return null;
        }
        List<ConsumptionService.Line> lines = new ArrayList<>();
        List<BigDecimal> weights = new ArrayList<>(weightByEan.values());
        List<BigDecimal> parts = prorate(amount, weights);
        int index = 0;
        for (String ean : weightByEan.keySet()) {
            ConsumptionService.Line line = new ConsumptionService.Line();
            line.ean = ean;
            line.amount = parts.get(index++);
            lines.add(line);
        }
        return lines;
    }

    /**
     * Splits an amount pro-rata of weights at scale 2, the rounding residue going to the last
     * share, as the per-line split of the sale does, so the parts sum back to the amount.
     *
     * @param amount  the amount to split.
     * @param weights the weights, at least one, summing to a non-zero value.
     * @return one part per weight, in the same order.
     */
    public static List<BigDecimal> prorate(BigDecimal amount, List<BigDecimal> weights) {
        BigDecimal total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < weights.size() - 1; i++) {
            BigDecimal part = amount.multiply(weights.get(i)).divide(total, 2, RoundingMode.HALF_UP);
            parts.add(part);
            assigned = assigned.add(part);
        }
        parts.add(amount.subtract(assigned).setScale(2, RoundingMode.HALF_UP));
        return parts;
    }

    /**
     * Computes what was paid for each request line of an evaluation: the line's offer amounts,
     * minus its share of every discount. A discount is spread over the lines of the offer it
     * targets, or over every line when it targets none (a basket-wide discount), pro-rata of the
     * line amounts. A basket-level offer without articles (a delivery, a deposit basket) becomes
     * a charge line of its own, so the lines sum back to the evaluation total.
     *
     * @param evaluation the serialized evaluation.
     * @return the paid lines keyed by line identifier (charges by {@code offer:<label>}).
     */
    public static Map<String, PaidLine> paidLines(JsonNode evaluation) {
        Map<String, PaidLine> lines = new LinkedHashMap<>();
        Map<String, List<PaidLine>> byOffer = new LinkedHashMap<>();
        for (JsonNode offer : evaluation.path("offers")) {
            String type = text(offer, "type");
            List<PaidLine> targets = byOffer.computeIfAbsent(String.valueOf(type), k -> new ArrayList<>());
            JsonNode items = offer.path("items");
            if (items.size() == 0) {
                BigDecimal ttc = decimal(offer.path("amount").path("amountIncludingTax"));
                if (ttc != null) {
                    PaidLine charge = new PaidLine(null, null, type, true);
                    charge.add(BigDecimal.ONE, decimal(offer.path("amount").path("amountExcludingTax")), ttc,
                            decimal(offer.path("amount").path("vatRate")));
                    lines.put(CHARGE_PREFIX + type, charge);
                    targets.add(charge);
                }
                continue;
            }
            for (JsonNode item : items) {
                String lineId = text(item, "lineId");
                String ean = text(item, "produceEan");
                String key = lineId != null ? lineId : String.valueOf(ean);
                PaidLine line = lines.computeIfAbsent(key, k -> new PaidLine(lineId, ean, text(item, "label"), false));
                JsonNode amount = item.path("amount");
                line.add(decimal(item.path("quantity")), decimal(amount.path("amountExcludingTax")),
                        decimal(amount.path("amountIncludingTax")), decimal(amount.path("vatRate")));
                if (!targets.contains(line)) {
                    targets.add(line);
                }
            }
        }
        for (JsonNode advantage : evaluation.path("advantages")) {
            BigDecimal discount = decimal(advantage.path("discountAmount").path("amountIncludingTax"));
            if (discount == null || lines.isEmpty()) {
                continue;
            }
            List<PaidLine> targets = byOffer.get(String.valueOf(text(advantage, "offer")));
            if (targets == null || targets.isEmpty()) {
                targets = new ArrayList<>(lines.values());
            }
            allocate(discount, targets);
        }
        return lines;
    }

    /**
     * Allocates a discount over target lines pro-rata of their gross tax-included amounts.
     *
     * @param discount the discount amount, tax included.
     * @param targets  the lines the discount applies to, at least one.
     */
    private static void allocate(BigDecimal discount, List<PaidLine> targets) {
        List<BigDecimal> weights = new ArrayList<>();
        for (PaidLine target : targets) {
            weights.add(target.grossTtc.abs());
        }
        if (weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add).signum() == 0) {
            return;
        }
        List<BigDecimal> parts = prorate(discount, weights);
        for (int i = 0; i < targets.size(); i++) {
            targets.get(i).discountTtc = targets.get(i).discountTtc.add(parts.get(i));
        }
    }

    /**
     * What one request line of a valued basket amounted to and what was paid for it.
     */
    public static final class PaidLine {

        /**
         * The request line identifier, or null for a charge.
         */
        public final String lineId;

        /**
         * The product EAN, or null for a charge or an unknown article.
         */
        public final String ean;

        /**
         * The product label (or the charge's offer label), or null.
         */
        public final String label;

        /**
         * Whether this is a basket-level charge rather than an article line.
         */
        public final boolean charge;

        /**
         * The quantity of the line, summed over its valued portions.
         */
        public BigDecimal quantity = BigDecimal.ZERO;

        /**
         * The line's offer amounts, tax excluded.
         */
        public BigDecimal grossHt = BigDecimal.ZERO;

        /**
         * The line's offer amounts, tax included.
         */
        public BigDecimal grossTtc = BigDecimal.ZERO;

        /**
         * The VAT rate of the line, zero when unknown.
         */
        public BigDecimal vatRate = BigDecimal.ZERO;

        /**
         * The line's share of the discounts, tax included.
         */
        public BigDecimal discountTtc = BigDecimal.ZERO;

        /**
         * Builds an empty paid line.
         *
         * @param lineId the request line identifier, or null.
         * @param ean    the product EAN, or null.
         * @param label  the label, or null.
         * @param charge whether the line is a basket-level charge.
         */
        public PaidLine(String lineId, String ean, String label, boolean charge) {
            this.lineId = lineId;
            this.ean = ean;
            this.label = label;
            this.charge = charge;
        }

        /**
         * Adds one valued portion to the line; absent figures count as zero.
         *
         * @param quantity the portion's quantity, or null.
         * @param ht       the portion's amount tax excluded, or null.
         * @param ttc      the portion's amount tax included, or null.
         * @param rate     the portion's VAT rate, or null to keep the current one.
         */
        void add(BigDecimal quantity, BigDecimal ht, BigDecimal ttc, BigDecimal rate) {
            this.quantity = this.quantity.add(quantity == null ? BigDecimal.ZERO : quantity);
            this.grossHt = this.grossHt.add(ht == null ? BigDecimal.ZERO : ht);
            this.grossTtc = this.grossTtc.add(ttc == null ? BigDecimal.ZERO : ttc);
            if (rate != null) {
                this.vatRate = rate;
            }
        }

        /**
         * Returns what was paid for the line, tax included.
         *
         * @return the gross amount minus the line's share of the discounts, scale 2.
         */
        public BigDecimal paidTtc() {
            return grossTtc.subtract(discountTtc).setScale(2, RoundingMode.HALF_UP);
        }

        /**
         * Returns the tax-excluded figure of an amount at the line's VAT rate.
         *
         * @param ttc the amount, tax included.
         * @return the amount tax excluded, scale 2.
         */
        public BigDecimal excludingTax(BigDecimal ttc) {
            return ttc.divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
        }

        /**
         * Returns what was paid for a part of the line, pro-rata of its quantity.
         *
         * @param part the quantity of the line concerned.
         * @return the paid amount for that quantity, tax included, scale 2.
         */
        public BigDecimal paidFor(BigDecimal part) {
            if (quantity.signum() == 0 || part.compareTo(quantity) == 0) {
                return paidTtc();
            }
            return paidTtc().multiply(part).divide(quantity, 2, RoundingMode.HALF_UP);
        }
    }
}
