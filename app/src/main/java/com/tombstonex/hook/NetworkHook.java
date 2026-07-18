package com.tombstonex.hook;

import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 冻结后断网 Hook
 * 冻结应用后断开其网络连接，解冻时恢复：
 * - Hook ConnectivityService.getAllNetworkStateForUid，对冻结应用返回空网络
 * - Hook NetworkAgentInfo.setConnected / unregister（观察性日志）
 * - onProcessFrozen：通过 NetworkManagementService.setUidNetworkRules 设置防火墙规则
 * - onProcessUnfrozen：恢复 UID 网络访问
 * 注意：通过反射调用隐藏 API；检查 AppConfigManager.keepConnection 配置（不存在则默认 false）
 */
public class NetworkHook {

    private static volatile boolean initialized = false;

    public static void init(ClassLoader classLoader) {
        hookGetAllNetworkStateForUid(classLoader);
        hookNetworkAgentInfo(classLoader);
        initialized = true;
        Logger.i("NetworkHook initialized");
    }

    /**
     * Hook ConnectivityService.getAllNetworkStateForUid — 对冻结 UID 返回空网络数组
     * 该方法被应用查询自身网络状态时调用，返回空可使冻结应用认为无网络可用。
     */
    private static void hookGetAllNetworkStateForUid(ClassLoader classLoader) {
        try {
            Class<?> csClass = XposedHelpers.findClass(
                "com.android.server.ConnectivityService", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 第一个 int 参数为 uid
                        int uid = -1;
                        for (Object arg : param.args) {
                            if (arg instanceof Integer) {
                                uid = (int) arg;
                                break;
                            }
                        }
                        if (uid < 10000) return; // 系统应用不拦截

                        if (isUidFrozen(uid)) {
                            // 返回该返回类型的空数组
                            Class<?> returnType = param.method.getReturnType();
                            if (returnType.isArray()) {
                                Object emptyArray = Array.newInstance(
                                    returnType.getComponentType(), 0);
                                param.setResult(emptyArray);
                                Logger.d("Blocked getAllNetworkStateForUid for frozen uid=" + uid);
                            }
                        }
                    } catch (Throwable t) {
                        Logger.e("getAllNetworkStateForUid hook error", t);
                    }
                }
            };

            int n = hookAllMethodsByName(csClass, "getAllNetworkStateForUid", callback);
            if (n > 0) {
                Logger.i("Hooked getAllNetworkStateForUid (" + n + " overloads)");
            } else {
                Logger.w("getAllNetworkStateForUid not found");
            }
        } catch (Throwable t) {
            Logger.e("Failed to hook getAllNetworkStateForUid", t);
        }
    }

    /**
     * Hook NetworkAgentInfo.setConnected / unregister — 观察性日志
     * 当冻结应用相关的网络代理状态变化时记录日志。
     */
    private static void hookNetworkAgentInfo(ClassLoader classLoader) {
        try {
            Class<?> naiClass = XposedHelpers.findClass(
                "com.android.server.connectivity.NetworkAgentInfo", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 尝试从 NetworkAgentInfo 获取 uid
                        int uid = -1;
                        try {
                            uid = XposedHelpers.getIntField(param.thisObject, "mUid");
                        } catch (Throwable ignore) {
                            // 某些版本字段名不同，尝试其他字段
                            try {
                                uid = XposedHelpers.getIntField(param.thisObject, "uid");
                            } catch (Throwable ignore2) {
                                return;
                            }
                        }
                        if (uid >= 10000 && isUidFrozen(uid)) {
                            Logger.d("NetworkAgentInfo." + param.method.getName()
                                + " for frozen uid=" + uid);
                        }
                    } catch (Throwable t) {
                        Logger.e("NetworkAgentInfo hook error", t);
                    }
                }
            };

            int n1 = hookAllMethodsByName(naiClass, "setConnected", callback);
            int n2 = hookAllMethodsByName(naiClass, "unregister", callback);
            Logger.i("Hooked NetworkAgentInfo (setConnected=" + n1 + " unregister=" + n2 + ")");
        } catch (Throwable t) {
            Logger.w("Failed to hook NetworkAgentInfo: " + t.getMessage());
        }
    }

    /**
     * 进程冻结成功后调用：断开该 UID 的网络
     * 通过 NetworkManagementService.setUidNetworkRules 设置防火墙规则（allow=false）。
     */
    public static void onProcessFrozen(int uid, String packageName) {
        if (!initialized) return;
        if (uid < 10000) return; // 系统应用不断网
        if (isKeepConnection(packageName)) {
            Logger.d("keepConnection enabled, skip blocking network for uid=" + uid);
            return;
        }
        setUidNetworkRules(uid, false);
    }

    /**
     * 进程解冻时调用：恢复该 UID 的网络访问
     */
    public static void onProcessUnfrozen(int uid, String packageName) {
        if (!initialized) return;
        if (uid < 10000) return;
        if (isKeepConnection(packageName)) return;
        setUidNetworkRules(uid, true);
    }

    /**
     * 通过反射调用 NetworkManagementService.setUidNetworkRules 设置 UID 防火墙规则
     * @param uid 目标 UID
     * @param allow true=允许网络（解冻），false=拒绝网络（冻结）
     */
    private static void setUidNetworkRules(int uid, boolean allow) {
        try {
            Object nms = getNetworkManagementService();
            if (nms == null) {
                Logger.w("NetworkManagementService unavailable, cannot setUidNetworkRules uid=" + uid);
                return;
            }
            // 反射调用 setUidNetworkRules(int uid, boolean allowOnMetered)
            // 注意：AOSP 中此方法签名为 setUidNetworkRules(int, boolean)，allow=false 即切断网络
            Method method = findMethod(nms.getClass(), "setUidNetworkRules", int.class, boolean.class);
            if (method == null) {
                Logger.w("setUidNetworkRules method not found on " + nms.getClass().getName());
                return;
            }
            method.setAccessible(true);
            method.invoke(nms, uid, allow);
            Logger.d("setUidNetworkRules uid=" + uid + " allow=" + allow);
        } catch (Throwable t) {
            Logger.e("setUidNetworkRules failed uid=" + uid + " allow=" + allow, t);
        }
    }

    /**
     * 通过 ServiceManager 获取 NetworkManagementService 的 Binder 代理
     */
    private static Object getNetworkManagementService() {
        try {
            Object binder = getService("network_management");
            if (binder == null) return null;
            // INetworkManagementService$Stub.asInterface(IBinder)
            Class<?> stubClass = Class.forName(
                "android.os.INetworkManagementService$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Throwable t) {
            Logger.e("getNetworkManagementService failed", t);
            return null;
        }
    }

    /**
     * 反射 ServiceManager.getService(String)
     */
    private static Object getService(String name) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            return getServiceMethod.invoke(null, name);
        } catch (Throwable t) {
            Logger.e("getService failed: " + name, t);
            return null;
        }
    }

    /**
     * 检查指定 UID 是否已被冻结
     */
    private static boolean isUidFrozen(int uid) {
        try {
            List<AppInfo> processes = ProcessTracker.getInstance().getByUid(uid);
            for (AppInfo info : processes) {
                if (info.state == AppState.FROZEN) return true;
            }
        } catch (Throwable t) {
            Logger.d("isUidFrozen failed uid=" + uid + ": " + t.getMessage());
        }
        return false;
    }

    /**
     * 检查 AppConfigManager.keepConnection 配置（通过反射调用，不存在则默认 false）
     * 实际签名为 isKeepConnection(String packageName)；keepConnection=true 时冻结应用后不断网。
     */
    private static boolean isKeepConnection(String packageName) {
        if (packageName == null) return false;
        try {
            Class<?> clazz = Class.forName("com.tombstonex.manager.AppConfigManager");
            Method getInstance = clazz.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) return false;
            // 优先尝试带 packageName 的签名
            try {
                Method m = clazz.getMethod("isKeepConnection", String.class);
                Object result = m.invoke(instance, packageName);
                if (result instanceof Boolean) return (Boolean) result;
            } catch (NoSuchMethodException ignore) {
                // 兼容：尝试无参变体
                try {
                    Method m = clazz.getMethod("isKeepConnection");
                    Object result = m.invoke(instance);
                    if (result instanceof Boolean) return (Boolean) result;
                } catch (NoSuchMethodException ignore2) {
                    // 方法不存在，默认 false
                }
            }
        } catch (ClassNotFoundException e) {
            // AppConfigManager 不存在，默认 false（即冻结后断网）
            Logger.d("AppConfigManager not found, keepConnection defaults to false");
        } catch (Throwable t) {
            Logger.d("isKeepConnection check failed: " + t.getMessage());
        }
        return false;
    }

    /**
     * 在类及其父类中查找指定方法（不抛异常，找不到返回 null）
     */
    private static Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 枚举类中所有指定名称的方法并逐一 hook（替代 hookAllMethods，stub 未提供该方法）
     */
    private static int hookAllMethodsByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        if (clazz == null) return 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, callback);
                    count++;
                } catch (Throwable t) {
                    Logger.d("hookMethod failed for " + methodName + ": " + t.getMessage());
                }
            }
        }
        return count;
    }
}
