package com.tombstonex.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ReflectionUtils {

    /**
     * Field 缓存: Class -> (fieldName -> Field)
     * L6: 强引用 Class 可能导致 ClassLoader 无法卸载。
     * 在 system_server 中 ClassLoader 不卸载，此泄漏可接受。
     *
     * M-36: 若在其他环境（如独立 JVM 或频繁加载/卸载类的场景）使用此工具类，
     * 需将 ConcurrentHashMap 改为 WeakHashMap 或使用 ClassValue 替代，
     * 避免静态缓存持有的 Class 强引用阻止 ClassLoader 被 GC 回收。
     */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> fieldCache =
        new ConcurrentHashMap<>();

    /**
     * Method 缓存: Class -> (cacheKey -> Method)
     * L6: 强引用 Class 可能导致 ClassLoader 无法卸载。
     * 在 system_server 中 ClassLoader 不卸载，此泄漏可接受。
     *
     * M-36: 若在其他环境（如独立 JVM 或频繁加载/卸载类的场景）使用此工具类，
     * 需将 ConcurrentHashMap 改为 WeakHashMap 或使用 ClassValue 替代，
     * 避免静态缓存持有的 Class 强引用阻止 ClassLoader 被 GC 回收。
     */
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
        if (className == null) return null;
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            Logger.e("未找到类: " + className, e);
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
        if (clazz == null || methodName == null) return null;
        // S-11: paramTypes 为 null 时直接返回 null，避免后续 getDeclaredMethod 调用传入 null 触发 NPE
        if (paramTypes == null) return null;
        // 轻微-14: 校验 paramTypes 不含 null 元素，避免 getDeclaredMethod 触发 NPE
        for (Class<?> p : paramTypes) {
            if (p == null) return null;
        }
        // 中等-2: 使用 "decl:" 前缀与 findMethodRecursive 区分，避免缓存污染
        String cacheKey = "decl:" + buildMethodCacheKey(methodName, paramTypes);
        // L7: 先查缓存
        ConcurrentHashMap<String, Method> classMethods = methodCache.get(clazz);
        if (classMethods != null) {
            Method cached = classMethods.get(cacheKey);
            if (cached != null) return cached;
        }
        try {
            Method method = clazz.getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            // L7: 存入缓存
            methodCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(cacheKey, method);
            return method;
        } catch (NoSuchMethodException e) {
            // 尝试基本类型与包装类自动匹配
            Method fallback = findMethodWithWrapperMatch(clazz, methodName, paramTypes);
            if (fallback != null) {
                // L7: 缓存包装类匹配结果
                methodCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(cacheKey, fallback);
                return fallback;
            }
            Logger.e("未找到方法: " + methodName + " in " + clazz.getName(), e);
            return null;
        }
    }

    /**
     * 构建 Method 缓存键：方法名 + 参数类型，避免同名重载方法冲突。
     *
     * 注意：缓存键使用 Class.getName() 拼接，两个不同 ClassLoader 加载的同名类
     * 会产生相同的 cacheKey，可能导致缓存键碰撞。在 system_server 中类名通常唯一，
     * 此风险可接受。若在其他环境中使用，需考虑在 key 中加入 ClassLoader 标识。
     */
    private static String buildMethodCacheKey(String methodName, Class<?>... paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) {
            return methodName;
        }
        StringBuilder sb = new StringBuilder(methodName).append('#');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(',');
            // L3-修复: 移除死代码 paramTypes[i] == null 检查。
            // 调用方 findMethod 和 findMethodRecursive 已保证所有元素非 null。
            sb.append(paramTypes[i].getName());
        }
        return sb.toString();
    }

    public static Method findMethodRecursive(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        if (clazz == null || methodName == null) return null;
        // S-11: paramTypes 为 null 时直接返回 null，避免后续 getDeclaredMethod 调用传入 null 触发 NPE
        if (paramTypes == null) return null;
        // 轻微-14: 校验 paramTypes 不含 null 元素，避免 getDeclaredMethod 触发 NPE
        for (Class<?> p : paramTypes) {
            if (p == null) return null;
        }
        // 中等-2: 使用 "rec:" 前缀与 findMethod 区分，避免缓存污染
        String cacheKey = "rec:" + buildMethodCacheKey(methodName, paramTypes);
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
        Logger.e("递归查找方法失败: " + methodName + " in " + clazz.getName());
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
        if (clazz == null || fieldName == null) return null;
        // 中等-2: 使用 "decl:" 前缀与 findFieldRecursive 区分，避免缓存污染
        String cacheKey = "decl:" + fieldName;
        // L7: 先查缓存
        ConcurrentHashMap<String, Field> classCache = fieldCache.get(clazz);
        if (classCache != null) {
            Field cached = classCache.get(cacheKey);
            if (cached != null) return cached;
        }
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            // L7: 存入缓存
            fieldCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(cacheKey, field);
            return field;
        } catch (NoSuchFieldException e) {
            Logger.e("未找到字段: " + fieldName + " in " + clazz.getName(), e);
            return null;
        }
    }

    public static Field findFieldRecursive(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        // 中等-2: 使用 "rec:" 前缀与 findField 区分，避免缓存污染
        String cacheKey = "rec:" + fieldName;
        // 先查缓存
        ConcurrentHashMap<String, Field> classCache = fieldCache.get(clazz);
        if (classCache != null) {
            Field cached = classCache.get(cacheKey);
            if (cached != null) return cached;
        }

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                // 存入缓存
                fieldCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>()).put(cacheKey, field);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        Logger.e("递归查找字段失败: " + fieldName + " in " + clazz.getName());
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object obj, Field field) {
        if (field == null) return null;
        try {
            return (T) field.get(obj);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Logger.e("获取字段值失败: " + field.getName(), e);
            return null;
        }
    }

    public static void setFieldValue(Object obj, Field field, Object value) {
        if (field == null) return;
        try {
            field.set(obj, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Logger.e("设置字段值失败: " + field.getName(), e);
        }
    }
}
