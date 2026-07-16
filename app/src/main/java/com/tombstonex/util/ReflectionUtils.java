package com.tombstonex.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ReflectionUtils {

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            Logger.e("Class not found: " + className, e);
            return null;
        }
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            Logger.e("Method not found: " + methodName + " in " + clazz.getName(), e);
            return null;
        }
    }

    public static Method findMethodRecursive(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(methodName, paramTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        Logger.e("Method not found recursively: " + methodName + " in " + clazz.getName());
        return null;
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            Logger.e("Field not found: " + fieldName + " in " + clazz.getName(), e);
            return null;
        }
    }

    public static Field findFieldRecursive(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        Logger.e("Field not found recursively: " + fieldName + " in " + clazz.getName());
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object obj, Field field) {
        try {
            return (T) field.get(obj);
        } catch (IllegalAccessException e) {
            Logger.e("Failed to get field value: " + field.getName(), e);
            return null;
        }
    }

    public static void setFieldValue(Object obj, Field field, Object value) {
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            Logger.e("Failed to set field value: " + field.getName(), e);
        }
    }
}