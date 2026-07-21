package com.intermarche.valuation.domain.util;

import java.lang.reflect.Field;

/**
 * Utility class for accessing private fields using reflection.
 */
public class ReflectionUtils {
    /**
     * Retrieves the value of a private field from a target object.
     *
     * @param target     The object containing the field.
     * @param fieldName  The name of the field.
     * @return The value of the field.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to access field '" + fieldName + "' via reflection", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> void setField(Object target, String fieldName, T value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to access field '" + fieldName + "' via reflection", e);
        }
    }
}