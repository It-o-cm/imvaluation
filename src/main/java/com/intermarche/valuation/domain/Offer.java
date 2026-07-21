package com.intermarche.valuation.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Entity representing an Offer with specific specifications.
 * <p>
 * An Offer can be linked to multiple {@link Store} entities and multiple {@link StoreGroup} entities.
 * <p>
 * This entity extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "offers",
        indexes = {
                @Index(name = "idx_offer_code", columnList = "code"),
                @Index(name = "idx_offer_type", columnList = "type")
        }
)
@Cacheable
public class Offer extends BaseEntity {

    /**
     * A static and immutable instance of {@code ObjectMapper} to handle
     * JSON serialization and deserialization throughout the application.
     *
     * The {@code ObjectMapper} is provided by the Jackson library and is
     * used to convert Java objects to JSON and vice-versa. This is a
     * thread-safe instance intended for reuse to improve performance
     * and reduce overhead of creating multiple instances.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    // --------------------------------------------------
    // Offer Details
    // --------------------------------------------------

    /**
     * The unique code of the offer (e.g., "PROMO_SUMMER_2024").
     * Serves as the primary business key.
     */
    @Column(unique = true, nullable = false, length = 50)
    @NotBlank(message = "Offer code is mandatory")
    public String code;

    /**
     * The type of the offer (e.g., "PROMO", "DISCOUNT", "PRICE_DROP").
     * Used for filtering offers efficiently.
     */
    @Column(nullable = false, length = 50)
    @NotBlank(message = "Offer type is mandatory")
    public String type;

    /**
     * The specification of the offer stored as a JSON string.
     * <p>
     * In Java, this is a simple String. In the database, this is mapped
     * to a JSON column (e.g., JSONB in PostgreSQL).
     */
    @Column(nullable = false, length = 1000)
    @NotBlank(message = "Offer specification is mandatory")
    public String specification;

    /**
     * The list of EANs extracted from the specification.
     * <p>
     * This list is automatically populated when the entity is persisted or updated,
     * based on keys ending with "ean" (single value) or "eans" (list of values)
     * found in the JSON specification.
     * <p>
     * Stored in a separate table 'offer_eans' with an index on the 'ean' column.
     */
    @ElementCollection
    @CollectionTable(name = "offer_eans",
            joinColumns = @JoinColumn(name = "offer_id"),
            indexes = @Index(columnList = "ean", name = "idx_offer_ean_value"))
    @Column(name = "ean", length = 13)
    @OrderColumn(name = "list_index")
    public List<String> eans = new ArrayList<>();

    // --------------------------------------------------
    // Relations (Targeting)
    // --------------------------------------------------

    /**
     * The set of Stores this offer applies to.
     * Unidirectional relationship managed via a join table.
     */
    @ManyToMany
    @JoinTable(
            name = "offer_stores",
            joinColumns = @JoinColumn(name = "offer_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id")
    )
    public Set<Store> stores = new HashSet<>();

    /**
     * The set of StoreGroups this offer applies to.
     * Unidirectional relationship managed via a join table.
     */
    @ManyToMany
    @JoinTable(
            name = "offer_store_groups",
            joinColumns = @JoinColumn(name = "offer_id"),
            inverseJoinColumns = @JoinColumn(name = "store_group_id")
    )
    public Set<StoreGroup> storeGroups = new HashSet<>();

    // --------------------------------------------------
    // Lifecycle Callbacks
    // --------------------------------------------------

    /**
     * Automatically updates the list of EANs from the specification before persisting.
     */
    @Override
    @PrePersist
    public void onCreate() {
        updateEansFromSpecification(); // 1. Ma logique (avant)
        super.onCreate();               // 2. La logique parente (après)
    }

    /**
     * Automatically updates the list of EANs from the specification before updating.
     */
    @Override
    @PreUpdate
    public void onUpdate() {
        updateEansFromSpecification(); // 1. Ma logique (avant)
        super.onUpdate();              // 2. La logique parente (après)
    }

    /**
     * Parses the JSON specification and extracts all EANs.
     * It looks for keys ending with "ean" (String value) or "eans" (Array value).
     */
    private void updateEansFromSpecification() {
        this.eans.clear();
        if (this.specification == null || this.specification.isBlank()) {
            return;
        }
        try {
            JsonNode rootNode = mapper.readTree(this.specification);
            Set<String> extractedEans = new HashSet<>();
            extractEansRecursively(rootNode, extractedEans);
            this.eans.addAll(extractedEans);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse specification for Offer " + this.code + ": " + e.getMessage());
        }
    }

    /**
     * Helper method to recursively traverse the JSON tree and find EANs.
     *
     * @param node       The current JSON node.
     * @param eanSet     The set to accumulate found EANs.
     */
    void extractEansRecursively(JsonNode node, Set<String> eanSet) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase();
                JsonNode valueNode = entry.getValue();

                // Case 1: Key ends with "ean" (typically singular) and value is a String
                // Note: "targetEans" ends with "ean" is false, so this handles singular keys safely.
                if (key.endsWith("ean") && valueNode.isTextual()) {
                    eanSet.add(valueNode.asText());
                }
                // Case 2: Key ends with "eans" (plural)
                else if (key.endsWith("eans")) {
                    if (valueNode.isArray()) {
                        // Handle Array of strings
                        for (JsonNode item : valueNode) {
                            if (item.isTextual()) {
                                eanSet.add(item.asText());
                            }
                        }
                    } else if (valueNode.isTextual()) {
                        // Handle Single string for plural key (fix for the failing test)
                        eanSet.add(valueNode.asText());
                    }
                }

                // Recurse into nested objects
                if (valueNode.isObject() || valueNode.isArray()) {
                    extractEansRecursively(valueNode, eanSet);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode arrayItem : node) {
                extractEansRecursively(arrayItem, eanSet);
            }
        }
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds an offer by its unique code.
     *
     * @param code The offer code.
     * @return The Offer entity or null if not found.
     */
    public static Offer findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Finds offers linked to a specific store and of a specific type.
     *
     * @param store The store entity.
     * @param type  The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByStoreAndType(Store store, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.stores s where s = ?1) and o.type = ?2", store, type);
    }

    /**
     * Finds offers linked to any of the provided store groups and of a specific type.
     *
     * @param storeGroups A list of StoreGroup entities.
     * @param type        The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByStoreGroupsAndType(Collection<StoreGroup> storeGroups, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.storeGroups g where g in ?1) and o.type = ?2", storeGroups, type);
    }

    /**
     * Finds offers applicable to a list of specific EANs, linked to a specific store, and of a specific type.
     *
     * @param eans  The list of EANs the offer must target.
     * @param store The store entity.
     * @param type  The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByEansAndStoreAndType(Collection<String> eans, Store store, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.stores s where s = ?2) and o.type = ?3", eans, store, type);
    }

    /**
     * Finds offers applicable to a list of specific EANs, linked to store groups, and of a specific type.
     *
     * @param eans        The list of EANs the offer must target.
     * @param storeGroups A list of StoreGroup entities.
     * @param type        The type of the offer.
     * @return A list of matching Offer entities.
     */
    public static List<Offer> findByEansAndStoreGroupsAndType(Collection<String> eans, Collection<StoreGroup> storeGroups, String type) {
        return list("select distinct o from Offer o where exists (select 1 from o.eans e where e in ?1) and exists (select 1 from o.storeGroups g where g in ?2) and o.type = ?3", eans, storeGroups, type);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the offer's key attributes and its targets.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String storeCodes = stores.stream()
                .map(s -> s.code)
                .sorted()
                .collect(Collectors.joining("|"));
        String groupCodes = storeGroups.stream()
                .map(g -> g.code)
                .sorted()
                .collect(Collectors.joining("|"));
        return Objects.hash(code, type, specification, storeCodes, groupCodes);
    }
}