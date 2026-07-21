package com.intermarche.valuation.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Entity representing a logical grouping of Stores.
 * <p>
 * Supports a hierarchical (or graph) structure where a StoreGroup can contain:
 * <ul>
 *   <li>A list of {@link Store} entities (Leaves).</li>
 *   <li>A list of other {@link StoreGroup} entities (Sub-groups/Children).</li>
 * </ul>
 * <p>
 * The relationship is unidirectional: the Parent StoreGroup holds the lists.
 * The Child (Store or StoreGroup) does not hold a reference to the Parent.
 * <p>
 * <b>Multiple Parents:</b> A group (or a store) can belong to multiple parent groups.
 * This requires traversing the structure as a Directed Acyclic Graph (DAG).
 */
@Entity
@Table(name = "store_groups",
        uniqueConstraints = @UniqueConstraint(columnNames = "code")
)
@Cacheable
public class StoreGroup extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Group code is mandatory")
    public String code;

    @Column(name = "name", nullable = false, length = 100)
    @NotBlank(message = "Group name is mandatory")
    public String name;

    // --------------------------------------------------
    // Relations: Direct Stores
    // --------------------------------------------------

    /**
     * The list of physical stores directly belonging to this group.
     * Unidirectional relationship via a foreign key in the 'stores' table.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    public Set<Store> stores = new HashSet<>();

    // --------------------------------------------------
    // Relations: Child Groups (Hierarchy)
    // --------------------------------------------------

    /**
     * The list of sub-groups (StoreGroups) contained within this group.
     * <p>
     * Unidirectional relationship.
     * Note: To support true multiple parents in the database schema, this mapping
     * should ideally use @JoinTable instead of @JoinColumn.
     */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "parent_group_id")
    public Set<StoreGroup> storeGroups = new HashSet<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a StoreGroup by its unique code.
     * @param code The store group code.
     * @return The StoreGroup entity or null if not found.
     */
    public static StoreGroup findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Retrieves all parent StoreGroups for a given Store, traversing up the hierarchy.
     * <p>
     * This method supports graphs where a group can have multiple parents.
     * It performs a Depth-First Search (DFS) to find all ancestors.
     *
     * @param store The store to search for.
     * @return A Set of unique {@link StoreGroup}.
     *         Returns an empty Set if the store belongs to no group or if the store is null.
     */
    public static Set<StoreGroup> findAllStoreGroups(Store store) {
        Set<StoreGroup> ancestors = new HashSet<>();
        if (store == null || store.id == null) {
            return ancestors;
        }
        // 1. Find all direct parents of the Store
        // Query: Select StoreGroup where the collection 'stores' contains the specific store ID
        List<StoreGroup> directParents = find(
                "select sg from StoreGroup sg join sg.stores s where s.id = ?1",
                store.id
        ).list();
        // 2. Recursively find all parent groups for each direct parent
        for (StoreGroup parent : directParents) {
            findAncestorsRecursive(parent, ancestors);
        }
        return ancestors;
    }

    /**
     * Recursive helper to find all ancestors of a specific group.
     * Handles cycles and multiple parents.
     *
     * @param group The group whose parents we are looking for.
     * @param ancestors The accumulating set of ancestor groups to avoid duplicates.
     */
    private static void findAncestorsRecursive(StoreGroup group, Set<StoreGroup> ancestors) {
        // Stop if null, already visited, or missing ID
        if (ancestors.contains(group)) {
            return;
        }
        // Add current group to ancestors
        ancestors.add(group);
        // Find all parents of the current group
        // Query: Select Parent StoreGroup where the collection 'storeGroups' contains the current group
        List<StoreGroup> parents = find(
                "select parent from StoreGroup parent join parent.storeGroups child where child.id = ?1",
                group.id
        ).list();
        // Recurse for each parent found (handling multiple branches)
        for (StoreGroup parent : parents) {
            findAncestorsRecursive(parent, ancestors);
        }
    }

    @Override
    public int getChecksum() {
        // Excluding children from checksum for performance and stability.
        return Objects.hash(code, name);
    }
}