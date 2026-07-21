package com.intermarche.valuation.domain;

import com.intermarche.valuation.domain.util.DateTimeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BaseEntity}.
 * <p>
 * Tests the lifecycle callbacks (PrePersist, PreUpdate) for timestamp management,
 * checksum calculation, and the equality/hashCode logic based on ID and Class.
 * </p>
 * Since {@link BaseEntity} is abstract, a concrete inner implementation is used for testing.
 */
public class BaseEntityTest {

    /**
     * Cleans up the static state of {@link DateTimeProvider} after each test
     * to ensure no side effects between test methods.
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    /**
     * Concrete implementation of {@link BaseEntity} used for testing purposes.
     * Implements the abstract {@link #getChecksum()} method.
     */
    static class TestEntity extends BaseEntity {
        public String businessField;

        @Override
        public int getChecksum() {
            return Objects.hash(businessField);
        }
    }

    // --------------------------------------------------
    // Lifecycle Callbacks Tests
    // --------------------------------------------------

    /**
     * Tests the {@link BaseEntity#onCreate()} method.
     * <p>
     * Verifies that both {@code createdAt} and {@code updatedAt} are set to the
     * current time provided by {@link DateTimeProvider}, and that the checksum
     * is calculated upon creation.
     */
    @Test
    void onCreate_shouldSetTimestampsAndChecksum() {
        LocalDateTime fixedTime = LocalDateTime.of(2023, 1, 1, 12, 0, 0);
        DateTimeProvider.setFixedDateTime(fixedTime);
        TestEntity entity = new TestEntity();
        entity.businessField = "Test Data";
        entity.onCreate();
        assertEquals(fixedTime, entity.createdAt, "CreatedAt should be set to current time");
        assertEquals(fixedTime, entity.updatedAt, "UpdatedAt should be set to current time on create");
        assertNotNull(entity.checksum, "Checksum should be calculated");
        assertEquals(Objects.hash("Test Data"), entity.checksum, "Checksum should match business fields");
    }

    /**
     * Tests the {@link BaseEntity#onUpdate()} method.
     * <p>
     * Verifies that only {@code updatedAt} is refreshed, {@code createdAt} remains
     * unchanged, and the {@code checksum} is recalculated to reflect data changes.
     */
    @Test
    void onUpdate_shouldUpdateTimestampAndRecalculateChecksum() {
        LocalDateTime createTime = LocalDateTime.of(2023, 1, 1, 12, 0, 0);
        DateTimeProvider.setFixedDateTime(createTime);
        TestEntity entity = new TestEntity();
        entity.businessField = "Initial Data";
        entity.onCreate(); // Initialize
        // Simulate time passing and data changing
        LocalDateTime updateTime = LocalDateTime.of(2023, 1, 1, 13, 0, 0);
        DateTimeProvider.setFixedDateTime(updateTime);
        entity.businessField = "Updated Data";
        entity.onUpdate();
        assertEquals(createTime, entity.createdAt, "CreatedAt should not be modified on update");
        assertEquals(updateTime, entity.updatedAt, "UpdatedAt should be refreshed to current time");
        assertEquals(Objects.hash("Updated Data"), entity.checksum, "Checksum should reflect new data");
    }

    // --------------------------------------------------
    // Equals and HashCode Tests
    // --------------------------------------------------

    /**
     * Tests {@link BaseEntity#equals(Object)} for two entities with the same ID and class.
     */
    @Test
    void equals_shouldReturnTrue_forSameClassAndId() {
        TestEntity entity1 = new TestEntity();
        entity1.id = 1L;
        TestEntity entity2 = new TestEntity();
        entity2.id = 1L;
        assertEquals(entity1, entity2);
    }

    /**
     * Tests {@link BaseEntity#equals(Object)} for the exact same instance.
     */
    @Test
    void equals_shouldReturnTrue_forSameInstance() {
        TestEntity entity = new TestEntity();
        assertEquals(entity, entity);
    }

    /**
     * Tests {@link BaseEntity#equals(Object)} when the parameter is null.
     * <p>
     * According to the Object contract, any non-null instance should return false when compared to null.
     */
    @Test
    void equals_shouldReturnFalse_whenOtherIsNull() {
        TestEntity entity = new TestEntity();
        // Explicitly calling equals to verify the implementation logic
        assertFalse(entity.equals(null));
    }

    /**
     * Tests {@link BaseEntity#equals(Object)} when comparing with a different class.
     */
    @Test
    void equals_shouldReturnFalse_forDifferentClass() {
        TestEntity entity = new TestEntity();
        entity.id = 1L;
        // Another concrete implementation for testing class difference
        AnotherEntity another = new AnotherEntity();
        another.id = 1L;
        assertNotEquals(entity, another);
    }

    /**
     * Tests {@link BaseEntity#equals(Object)} when IDs are different.
     */
    @Test
    void equals_shouldReturnFalse_forDifferentId() {
        TestEntity entity1 = new TestEntity();
        entity1.id = 1L;
        TestEntity entity2 = new TestEntity();
        entity2.id = 2L;
        assertNotEquals(entity1, entity2);
    }

    /**
     * Tests {@link BaseEntity#equals(Object)} when the ID of the checked entity is null.
     * <p>
     * According to the implementation, entities with null IDs are not equal to any other
     * entity (even if the other has a null ID) unless they are the same reference.
     */
    @Test
    void equals_shouldReturnFalse_forTransientEntities() {
        TestEntity entity1 = new TestEntity(); // id is null
        TestEntity entity2 = new TestEntity(); // id is null
        assertNotEquals(entity1, entity2, "Two separate transient entities should not be equal");
    }

    /**
     * Tests that {@link BaseEntity#hashCode()} is consistent with {@link BaseEntity#equals(Object)}.
     * <p>
     * Objects that are equal must have the same hash code.
     */
    @Test
    void hashCode_shouldBeConsistent_withEquals() {
        TestEntity entity1 = new TestEntity();
        entity1.id = 1L;
        TestEntity entity2 = new TestEntity();
        entity2.id = 1L;
        assertEquals(entity1, entity2);
        assertEquals(entity1.hashCode(), entity2.hashCode());
    }

    /**
     * Tests that {@link BaseEntity#hashCode()} includes the Class type.
     * <p>
     * Two entities of different classes but with the same ID should have different hash codes.
     */
    @Test
    void hashCode_shouldIncludeClassType() {
        TestEntity entity1 = new TestEntity();
        entity1.id = 1L;
        AnotherEntity entity2 = new AnotherEntity();
        entity2.id = 1L;
        assertNotEquals(entity1.hashCode(), entity2.hashCode());
    }

    // --------------------------------------------------
    // Helper Classes
    // --------------------------------------------------

    /**
     * Another concrete implementation of {@link BaseEntity} to test class-specific behavior
     * in equals/hashCode.
     */
    static class AnotherEntity extends BaseEntity {
        @Override
        public int getChecksum() {
            return 0;
        }
    }
}