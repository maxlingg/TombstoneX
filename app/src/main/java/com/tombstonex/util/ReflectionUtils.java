package com.tombstonex.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ReflectionUtils {

    /** Field 缓存: Class -> (fieldName -> Field) */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> fieldCache =
        new ConcurrentHashMap<>();

    /** Method 缓存: Class -> (cacheKey -> Method) */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> methodCache =
        new ConcurrentHashMap<>();

    /** 基本类型与包装类的映射，用于方法参数匹配 */
    private static final Map<Class<?>, Class<?>> primitiveToWrapper;
    private static final Map<Class<?>, Class<?>> wrapperToPrimitive;

    static {
        Map<Class<?>, Class<?>> p2w = new HashMap<>();
        p2w.put(boolean.class, Boolean.class);
        p2w.put(byte.class, Byte.class);
        p2w.put(char.class, Character.class);
        p2w.put(short.class, Short.class);
        p2w.put(int.class, Integer.class);
        p2w.put(long.class, Long.class);
        p2w.put(float.class, Float.class);
        p2w.put(double.class, Double.class);
        p2w.put(void.class, Void.class);
        primitiveToWrapper = Collections.unmodifiableMap(p2w);

        Map<Class<?>, Class<?>> w2p = new HashMap<>();
        w2p.put(Boolean.class, boolean.class);
        w2p.put(Byte.class, byte.class);
        w2p.put(Character.class, char.class);
        w2p.put(Short.class, short.class);
        w2p.put(Integer.class, int.class);
        w2p.put(Long.class, long.class);
        w2p.put(Float.class, float.class);
        w2p.put(Double.class, double.class);
        w2p.put(Void.class, void.class);
        wrapperToPrimitive = Collections.unmodifiableMap(w2p);
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            Logger.e("Class not found: " + className, e);
            return null;
        }
    }

    /**
     * 判断两个类型是否兼容（基本类型与包装类自动匹配）
     */
    private static boolean isTypeCompatible(Class<?> expected, Class<?> actual) {
        if (expected == actual) return true;
        if (expected == null || actual == null) return false;
        if (expected.isPrimitive()) {
            return wrapperToPrimitive.get(actual) == expected;
        }
        if (actual.isPrimitive()) {
            return primitiveToWrapper.get(actual) == expected;
        }
        return expected.isAssignableFrom(actual);
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            // 尝试基本类型与包装类自动匹配
            Method fallback = findMethodWithWrapperMatch(clazz, methodName, paramTypes);
            if (fallback != null) return fallback;
            Logger.e("Method not found: " + methodName + " in " + clazz.getName(), e);
            return null;
        }
    }

    /**
     * 构建 Method 缓存键：方法名 + 参数类型，避免同名重载方法冲突
     */
    private static String buildMethodCacheKey(String methodName, Class<?>... paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) {
            return methodName;
        }
        StringBuilder sb = new StringBuilder(methodName).append('#');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(paramTypes[i] == null ? "null" : paramTypes[i].getName());
        }
        return sb.toString();
    }

    public static Method findMethodRecursive(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        String cacheKey = buildMethodCacheKey(methodName, paramTypes);
        // 先查缓存
        ConcurrentHashMap<String, Method> classMethods = methodCache.get(clazz);
        if (classMethods != null) {
            Method cached = classMethods.get(cacheKey);
            if (cached != null) return cached;
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(methodName, paramTypes);
                method.setAccessible(true);
                // 找到方法后存入缓存
                methodCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(cacheKey, method);
                return method;
            } catch (NoSuchMethodException e) {
                // 尝试基本类型与包装类自动匹配
                Method fallback = findMethodWithWrapperMatch(current, methodName, paramTypes);
                if (fallback != null) {
                    // 找到方法后存入缓存
                    methodCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(cacheKey, fallback);
                    return fallback;
                }
                current = current.getSuperclass();
            }
        }
        Logger.e("Method not found recursively: " + methodName + " in " + clazz.getName());
        return null; // 不缓存负面结果
    }

    /**
     * 遍历声明的方法，通过基本类型/包装类兼容匹配
     */
    private static Method findMethodWithWrapperMatch(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            Class<?>[] methodParamTypes = method.getParameterTypes();
            if (methodParamTypes.length != paramTypes.length) continue;
            boolean allMatch = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!isTypeCompatible(paramTypes[i], methodParamTypes[i])) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                method.setAccessible(true);
                return method;
            }
        }
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
        // 先查缓存
        ConcurrentHashMap<String, Field> classCache = fieldCache.get(clazz);
        if (classCache != null) {
            Field cached = classCache.get(fieldName);
            if (cached != null) return cached;
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                // 存入缓存
                fieldCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(fieldName, field);
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
