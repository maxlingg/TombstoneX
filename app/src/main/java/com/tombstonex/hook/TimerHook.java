package com.tombstonex.hook;

import android.os.Binder;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * 定时器限制 Hook
 * 禁止被冻结应用设置定时器/闹钟：
 * - Hook AlarmManagerService.setImpl（系统服务端，多签名兼容）— 主拦截点
 * 在 beforeHookedMethod 中检查调用方 UID 是否已冻结，若已冻结则 setResult(null) 阻止。
 * 注意：只拦截用户应用（uid >= 10000）的定时器，不拦截系统应用。
 *
 * R8-m1: 已删除死代码 hookAlarmManagerSet（应用端 AlarmManager，system_server 中无效）
 * 和 hookHandler（system_server uid 守卫直接 return，恒为无效）。
 * 真正的拦截在 hookAlarmManagerServiceSetImpl（system_server 端 AlarmManagerService）中完成。
 */
public class TimerHook {

    public static void init(ClassLoader classLoader) {
        hookAlarmManagerServiceSetImpl(classLoader);
        // R8-m1: hookAlarmManagerSet 与 hookHandler 已删除（死代码）。
        // 真正的拦截在 hookAlarmManagerServiceSetImpl（system_server 端 AlarmManagerService）中完成。
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
     * 检查 Binder 调用方进程是否已冻结，若已冻结则阻断（setResult(null)）
     *
     * R10-m-5: 改为使用 Binder.getCallingPid() 精确判断调用方进程是否冻结，
     * 避免多进程应用中任一进程冻结即拦截整个 UID 的过于激进行为。
     */
    private static void blockIfCallerFrozen(XC_MethodHook.MethodHookParam param) {
        try {
            int callingPid = Binder.getCallingPid();
            AppInfo info = ProcessTracker.getInstance().getByPid(callingPid);
            if (info != null && info.getState() == AppState.FROZEN) {
                Logger.d("拦截已冻结进程的闹钟: " + info.packageName + " pid=" + callingPid);
                param.setResult(null);
            }
        } catch (Throwable t) {
            Logger.e("TimerHook 拦截出错", t);
        }
    }

    /**
     * 枚举类中所有指定名称的方法并逐一 hook（替代 hookAllMethods，stub 未提供该方法）
     *
     * M-5: 仅在当前类上 hook，不遍历 superclass，避免 hook 到父类中
     * 同名但参数列表不同的非目标方法。
     * m-6: 过滤 synthetic/bridge 方法，避免 hook 编译器生成的方法。
     */
    private static int hookAllMethodsByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        if (clazz == null) return 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && !method.isSynthetic()) {
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, callback);
                    count++;
                } catch (Throwable t) {
                    Logger.d("hookMethod 失败 " + methodName + ": " + t.getMessage());
                }
            }
        }
        return count;
    }
}
