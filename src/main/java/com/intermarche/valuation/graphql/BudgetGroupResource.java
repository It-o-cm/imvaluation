package com.intermarche.valuation.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.valuation.domain.BudgetGroup;
import com.intermarche.valuation.domain.ConsumptionCounter;
import com.intermarche.valuation.domain.Offer;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing budget groups and exposing budget consumption (spec §2, §4).
 * <p>
 * Budget groups are administered on the impos back-office and reach the engine by flux and by
 * this API, on the pattern of the other referentials. The consumption status queries are the
 * read side impos uses to follow a campaign: the engine holds the counters and answers, always
 * from its local state, no network call.
 * <p>
 * Implements {@link GraphQLTrait} for the centralized error handling ("System error" mask).
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class BudgetGroupResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(BudgetGroupResource.class);

    /**
     * Mapper used to read the optional {@code budget} block off an offer's specification.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves every budget group in code order.
     *
     * @return the budget groups.
     */
    @Query
    @Description("Get the list of all budget groups")
    @RolesAllowed("MANAGER")
    public List<BudgetGroup> allBudgetGroups() {
        LOGGER.info("Entering method allBudgetGroups");
        List<BudgetGroup> result = BudgetGroup.listAllOrdered();
        LOGGER.info("Exiting method allBudgetGroups");
        return result;
    }

    /**
     * Retrieves a budget group by its unique identifier.
     *
     * @param id the group id.
     * @return the budget group.
     * @throws NoSuchElementException when no group carries that id.
     */
    @Query
    @Description("Get a budget group by its ID")
    @RolesAllowed("MANAGER")
    public BudgetGroup budgetGroup(@Name("id") Long id) {
        LOGGER.info("Entering method budgetGroup with id: " + id);
        BudgetGroup group = BudgetGroup.findById(id);
        if (group == null) {
            LOGGER.error("Budget group with id " + id + " not found");
            throw new NoSuchElementException("Budget group with id " + id + " not found");
        }
        LOGGER.info("Exiting method budgetGroup");
        return group;
    }

    /**
     * Retrieves a budget group by its unique code.
     *
     * @param code the group code.
     * @return the budget group.
     * @throws NoSuchElementException when no group carries that code.
     */
    @Query
    @Description("Get a budget group by its code")
    @RolesAllowed("MANAGER")
    public BudgetGroup budgetGroupByCode(@Name("code") String code) {
        LOGGER.info("Entering method budgetGroupByCode with code: " + code);
        BudgetGroup group = BudgetGroup.findByCode(code);
        if (group == null) {
            LOGGER.error("Budget group with code " + code + " not found");
            throw new NoSuchElementException("Budget group with code " + code + " not found");
        }
        LOGGER.info("Exiting method budgetGroupByCode");
        return group;
    }

    /**
     * Exposes the budget consumption status of a single offer (spec §4): the offer's own ceilings
     * read from its {@code budget} block, and the consumption summed from its counters.
     *
     * @param offerCode the offer code.
     * @return the budget status.
     * @throws NoSuchElementException when no offer carries that code.
     */
    @Query
    @Description("Get the budget consumption status of an offer")
    @RolesAllowed("MANAGER")
    public BudgetStatus budgetStatus(@Name("offerCode") String offerCode) {
        LOGGER.info("Entering method budgetStatus for offer: " + offerCode);
        Offer offer = Offer.findByCode(offerCode);
        if (offer == null) {
            LOGGER.error("Offer with code " + offerCode + " not found");
            throw new NoSuchElementException("Offer with code " + offerCode + " not found");
        }
        JsonNode budget = readBudget(offer);
        BigDecimal maxAmount = budget != null && budget.hasNonNull("maxAmount")
                ? budget.get("maxAmount").decimalValue() : null;
        Integer maxApplications = budget != null && budget.hasNonNull("maxApplications")
                ? budget.get("maxApplications").asInt() : null;
        boolean perStore = budget != null && budget.hasNonNull("perStore") && budget.get("perStore").asBoolean();
        BudgetStatus status = buildStatus(List.of(offerCode), maxAmount, maxApplications, perStore);
        LOGGER.info("Exiting method budgetStatus");
        return status;
    }

    /**
     * Exposes the budget consumption status of a group (spec §4): the group's ceilings and the
     * consumption summed from the counters of every offer of the group.
     *
     * @param groupCode the group code.
     * @return the budget status.
     * @throws NoSuchElementException when no group carries that code.
     */
    @Query
    @Description("Get the budget consumption status of a budget group")
    @RolesAllowed("MANAGER")
    public BudgetStatus groupBudgetStatus(@Name("groupCode") String groupCode) {
        LOGGER.info("Entering method groupBudgetStatus for group: " + groupCode);
        BudgetGroup group = BudgetGroup.findByCode(groupCode);
        if (group == null) {
            LOGGER.error("Budget group with code " + groupCode + " not found");
            throw new NoSuchElementException("Budget group with code " + groupCode + " not found");
        }
        List<Offer> members = Offer.list("budgetGroup.id = ?1", group.id);
        List<String> codes = new ArrayList<>();
        for (Offer member : members) {
            codes.add(member.code);
        }
        BudgetStatus status = buildStatus(codes, group.maxAmount, group.maxApplications, group.perStore);
        LOGGER.info("Exiting method groupBudgetStatus");
        return status;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new budget group.
     *
     * @param input the group details.
     * @return the created group.
     * @throws GraphQLException when the code already exists or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new budget group")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public BudgetGroup createBudgetGroup(@Name("input") BudgetGroupRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createBudgetGroup for code: " + input.code);
            BudgetGroup existing = BudgetGroup.findByCode(input.code);
            if (existing != null) {
                LOGGER.warn("Attempt to create budget group with existing code: " + input.code);
                throw new AlreadyExistsException("Budget group with code '" + input.code + "' already exists.");
            }
            BudgetGroup group = new BudgetGroup();
            group.code = input.code;
            group.validFrom = parseIso(input.validFrom);
            group.validTo = parseIso(input.validTo);
            group.maxAmount = input.maxAmount;
            group.maxApplications = input.maxApplications;
            group.perStore = input.perStore != null && input.perStore;
            group.persist();
            LOGGER.info("Exiting method createBudgetGroup. Created ID: " + group.id);
            return group;
        }, BudgetGroupResource.class, "createBudgetGroup");
    }

    /**
     * Updates an existing budget group identified by its id. Only the non-null fields of the input
     * are applied (partial update).
     *
     * @param id    the group id.
     * @param input the fields to update.
     * @return the updated group.
     * @throws GraphQLException when the group is not found, the new code conflicts, or a
     *                          persistence error occurs.
     */
    @Mutation
    @Description("Update an existing budget group")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public BudgetGroup updateBudgetGroup(@Name("id") Long id, @Name("input") BudgetGroupRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateBudgetGroup for id: " + id);
            BudgetGroup group = BudgetGroup.findById(id);
            if (group == null) {
                LOGGER.error("Budget group with id " + id + " not found");
                throw new NoSuchElementException("Budget group with id " + id + " not found");
            }
            if (input.code != null && !input.code.equals(group.code)) {
                BudgetGroup other = BudgetGroup.findByCode(input.code);
                if (other != null) {
                    LOGGER.warn("Attempt to update budget group to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another budget group with code '" + input.code + "' already exists.");
                }
                group.code = input.code;
            }
            if (input.validFrom != null) group.validFrom = parseIso(input.validFrom);
            if (input.validTo != null) group.validTo = parseIso(input.validTo);
            if (input.maxAmount != null) group.maxAmount = input.maxAmount;
            if (input.maxApplications != null) group.maxApplications = input.maxApplications;
            if (input.perStore != null) group.perStore = input.perStore;
            LOGGER.info("Exiting method updateBudgetGroup");
            return group;
        }, BudgetGroupResource.class, "updateBudgetGroup");
    }

    /**
     * Deletes a budget group by its id.
     *
     * @param id the group id.
     * @return true when a group was deleted.
     * @throws GraphQLException when a persistence error occurs.
     */
    @Mutation
    @Description("Delete a budget group by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteBudgetGroup(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteBudgetGroup for id: " + id);
            boolean result = BudgetGroup.deleteById(id);
            LOGGER.info("Exiting method deleteBudgetGroup. Result: " + result);
            return result;
        }, BudgetGroupResource.class, "deleteBudgetGroup");
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a budget status for a set of offer codes and a pair of ceilings.
     *
     * @param offerCodes      the offer codes whose consumption is summed.
     * @param maxAmount       the amount ceiling, or null when uncapped.
     * @param maxApplications the application ceiling, or null when uncapped.
     * @param perStore        whether the ceiling is understood per point of sale.
     * @return the assembled status, with the per-store breakdown and the remaining values.
     */
    private BudgetStatus buildStatus(List<String> offerCodes, BigDecimal maxAmount,
                                     Integer maxApplications, boolean perStore) {
        BudgetStatus status = new BudgetStatus();
        status.maxAmount = maxAmount;
        status.maxApplications = maxApplications;
        status.perStore = perStore;
        status.consumedAmount = ConsumptionCounter.sumAmount(offerCodes, null);
        status.consumedApplications = ConsumptionCounter.sumApplications(offerCodes, null);
        status.remainingAmount = maxAmount == null ? null : maxAmount.subtract(status.consumedAmount);
        status.remainingApplications = maxApplications == null ? null
                : (long) maxApplications - status.consumedApplications;
        status.byStore = breakdownByStore(offerCodes);
        return status;
    }

    /**
     * Aggregates the consumption of a set of offers per point of sale, in store-code order.
     *
     * @param offerCodes the offer codes.
     * @return the per-store breakdown.
     */
    private List<StoreBudgetStatus> breakdownByStore(List<String> offerCodes) {
        Map<String, StoreBudgetStatus> byStore = new LinkedHashMap<>();
        for (ConsumptionCounter counter : ConsumptionCounter.listForOffers(offerCodes)) {
            StoreBudgetStatus line = byStore.computeIfAbsent(counter.storeCode, code -> {
                StoreBudgetStatus fresh = new StoreBudgetStatus();
                fresh.storeCode = code;
                fresh.consumedAmount = BigDecimal.ZERO;
                fresh.consumedApplications = 0L;
                return fresh;
            });
            if (counter.consumedAmount != null) {
                line.consumedAmount = line.consumedAmount.add(counter.consumedAmount);
            }
            line.consumedApplications += counter.consumedApplications;
        }
        return new ArrayList<>(byStore.values());
    }

    /**
     * Reads the optional {@code budget} block off an offer's specification.
     *
     * @param offer the offer.
     * @return the {@code budget} node, or null when the offer carries none or the spec is unreadable.
     */
    private static JsonNode readBudget(Offer offer) {
        if (offer.specification == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(offer.specification).get("budget");
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses an ISO local date-time, tolerating a null/blank/unparseable value as an open bound.
     *
     * @param raw the raw value, possibly null.
     * @return the parsed instant, or null.
     */
    private static LocalDateTime parseIso(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Input record type for budget group mutations. All fields are nullable to support partial
     * updates; dates are ISO local date-time strings.
     */
    @Input
    public static class BudgetGroupRecord {

        /**
         * The unique group code.
         */
        public String code;

        /**
         * The inclusive period start (ISO local date-time), or null for an open bound.
         */
        public String validFrom;

        /**
         * The exclusive period end (ISO local date-time), or null for an open bound.
         */
        public String validTo;

        /**
         * The amount envelope in euros, or null for no amount ceiling.
         */
        public BigDecimal maxAmount;

        /**
         * The ticket envelope, or null for no application ceiling.
         */
        public Integer maxApplications;

        /**
         * Whether the ceiling is understood per point of sale.
         */
        public Boolean perStore;

        /**
         * Default constructor for GraphQL binding.
         */
        public BudgetGroupRecord() {
        }
    }

    /**
     * The budget consumption status of an offer or a group (spec §4).
     */
    @Type
    public static class BudgetStatus {

        /**
         * The amount ceiling in euros, or null when uncapped.
         */
        public BigDecimal maxAmount;

        /**
         * The application ceiling, or null when uncapped.
         */
        public Integer maxApplications;

        /**
         * The cumulated consumed amount in euros.
         */
        public BigDecimal consumedAmount;

        /**
         * The cumulated number of applications.
         */
        public long consumedApplications;

        /**
         * The remaining amount ({@code maxAmount - consumedAmount}), or null when uncapped.
         */
        public BigDecimal remainingAmount;

        /**
         * The remaining applications ({@code maxApplications - consumedApplications}), or null when
         * uncapped.
         */
        public Long remainingApplications;

        /**
         * Whether the ceiling is understood per point of sale.
         */
        public boolean perStore;

        /**
         * The per-store consumption breakdown.
         */
        public List<StoreBudgetStatus> byStore = new ArrayList<>();

        /**
         * Default constructor for GraphQL binding.
         */
        public BudgetStatus() {
        }
    }

    /**
     * The consumption of one point of sale within a budget status.
     */
    @Type
    public static class StoreBudgetStatus {

        /**
         * The point-of-sale code.
         */
        public String storeCode;

        /**
         * The consumed amount in euros at that point of sale.
         */
        public BigDecimal consumedAmount;

        /**
         * The number of applications at that point of sale.
         */
        public long consumedApplications;

        /**
         * Default constructor for GraphQL binding.
         */
        public StoreBudgetStatus() {
        }
    }
}
