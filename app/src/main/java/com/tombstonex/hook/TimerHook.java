package com.tombstonex.hook;

import android.os.Binder;
import android.os.Process;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 定时器限制 Hook
 * 禁止被冻结应用设置定时器/闹钟：
 * - Hook AlarmManagerService.setImpl（系统服务端，多签名兼容）— 主拦截点
 * - Hook AlarmManager.set / setAndAllowWhileIdle / setExact / setExactAndAllowWhileIdle（应用端）
 * - Hook Handler.sendMessageDelayed / postDelayed（仅对冻结应用进程，通过 UID 判断）
 * 在 beforeHookedMethod 中检查调用方 UID 是否已冻结，若已冻结则 setResult(null) 阻止。
 * 注意：只拦截用户应用（uid >= 10000）的定时器，不拦截系统应用。
 */
public class TimerHook {

    public static void init(ClassLoader classLoader) {
        hookAlarmManagerServiceSetImpl(classLoader);
        hookAlarmManagerSet(classLoader);
        hookHandler(classLoader);
    }

    /**
     * Hook AlarmManagerService.setImpl — 系统服务端，多签名兼容
     * 所有应用的闹钟设置最终都经过此方法，通过 Binder.getCallingUid() 判断调用方。
     * 这是主拦截点（在 system_server 中有效，ProcessTracker 可用）。
     */
    private static void hookAlarmManagerServiceSetImpl(ClassLoader classLoader) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                "com.android.server.alarm.AlarmManagerService", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        blockIfCallerFrozen(param);
                    } catch (Throwable t) {
                        Logger.e("AlarmManagerService.setImpl Hook 出错", t);
                    }
                }
            };

            int n = hookAllMethodsByName(amsClass, "setImpl", callback);
            if (n > 0) {
                Logger.i("已 Hook AlarmManagerService.setImpl（" + n + " 个重载）");
            } else {
                Logger.w("未找到 AlarmManagerService.setImpl");
            }
        } catch (Throwable t) {
            Logger.e("Hook AlarmManagerService.setImpl 失败", t);
        }
    }

    /**
     * Hook AlarmManager.set / setAndAllowWhileIdle / setExact / setExactAndAllowWhileIdle
     * 这些方法在应用进程中执行，作为应用端补充拦截。
     * 注意：system_server 中 hook 仅覆盖系统自身的调用；应用端需模块注入应用进程才生效。
     */
    private static void hookAlarmManagerSet(ClassLoader classLoader) {
        try {
            Class<?> alarmManagerClass = XposedHelpers.findClass(
                "android.app.AlarmManager", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 应用端：通过当前进程 uid 判断；system_server 端通过 Binder 调用方判断
                        int uid = Binder.getCallingUid();
                        if (uid < 10000) return; // 系统应用不拦截
                        if (isUidFrozen(uid)) {
                            Logger.d("已拦截已冻结 uid 的 " + param.method.getName()
                                + " uid=" + uid);
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        Logger.e("AlarmManager.set Hook 出错", t);
                    }
                }
            };

            String[] methodNames = {
                "set", "setAndAllowWhileIdle", "setExact", "setExactAndAllowWhileIdle",
                "setWindow", "setRepeating", "setAlarmClock"
            };
            int total = 0;
            for (String name : methodNames) {
                total += hookAllMethodsByName(alarmManagerClass, name, callback);
            }
            Logger.i("已 Hook AlarmManager 方法（" + total + " 个重载）");
        } catch (Throwable t) {
            Logger.e("Hook AlarmManager 失败", t);
        }
    }

    /**
     * Hook Handler.sendMessageDelayed / postDelayed — 仅对冻结应用进程生效
     * 注意：Handler 是系统热路径，为避免在 system_server 上增加开销，
     * 仅在应用进程（myUid >= 10000）中注册此 hook。system_server 中直接跳过。
     * 已知限制：应用进程中 ProcessTracker 为独立实例（无冻结状态），
     * 完整生效需通过 IPC 查询 system_server 的冻结状态。
     */
    private static void hookHandler(ClassLoader classLoader) {
        // 仅在应用进程中注册，避免 system_server 热路径开销
        if (Process.myUid() < 10000) {
            Logger.d("跳过系统进程中的 Handler Hook（uid=" + Process.myUid() + "）");
            return;
        }
        try {
            Class<?> handlerClass = XposedHelpers.findClass("android.os.Handler", classLoader);

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int uid = Process.myUid();
                        if (uid < 10000) return;
                        if (isUidFrozen(uid)) {
                            Logger.d("已拦截已冻结进程的 " + param.method.getName()
                                + " uid=" + uid);
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        Logger.e("Handler Hook 出错", t);
                    }
                }
            };

            int n1 = hookAllMethodsByName(handlerClass, "sendMessageDelayed", callback);
            int n2 = hookAllMethodsByName(handlerClass, "postDelayed", callback);
            Logger.i("已 Hook Handler（sendMessageDelayed=" + n1
                + " postDelayed=" + n2 + "）于应用进程中");
        } catch (Throwable t) {
            Logger.w("Hook Handler 失败: " + t.getMessage());
        }
    }

    /**
     * 检查 Binder 调用方是否已冻结，若已冻结则阻断（setResult(null)）
     * 仅拦截用户应用（uid >= 10000），不拦截系统应用。
     */
    private static void blockIfCallerFrozen(XC_MethodHook.MethodHookParam param) {
        int callingUid = Binder.getCallingUid();
        if (callingUid < 10000) return; // 系统应用不拦截
        if (isUidFrozen(callingUid)) {
            Logger.d("已拦截已冻结调用方的 " + param.method.getName()
                + " uid=" + callingUid);
            param.setResult(null);
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
            Logger.d("isUidFrozen 失败 uid=" + uid + ": " + t.getMessage());
        }
        return false;
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
                    Logger.d("hookMethod 失败: " + methodName + ": " + t.getMessage());
                }
            }
        }
        return count;
    }
}
