package com.tombstonex.hook;

import android.os.Build;
import com.tombstonex.manager.ConfigManager;
import com.tombstonex.service.TombstoneXService;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String PACKAGE_ANDROID = "android";

    @Override
    public void initZygote(StartupParam startupParam) {
        // 从配置文件初始化日志级别
        ConfigManager config = ConfigManager.getInstance();
        Logger.init(config.isDebugEnabled());
        Logger.i("TombstoneX Zygote init, SDK=" + Build.VERSION.SDK_INT
            + " freezeMode=" + config.getFreezeMode()
            + " delay=" + config.getFreezeDelay() + "s");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        String pkg = lpparam.packageName;
        Logger.i("handleLoadPackage: " + pkg + " process=" + lpparam.processName);

        if (PACKAGE_ANDROID.equals(pkg)) {
            Logger.i("Hooking System Framework (android)");
            hookSystemFramework(lpparam);
        }
    }

    private void hookSystemFramework(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader classLoader = lpparam.classLoader;
        ConfigManager config = ConfigManager.getInstance();

        // 进程死亡清理 — 始终启用，防止内存泄漏和 PID 复用问题
        try {
            ProcessDeathHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init ProcessDeathHook", t);
        }

        // Activity 切换冻结 — 核心功能
        if (config.isHookActivitySwitchEnabled()) {
            try {
                ActivitySwitchHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init ActivitySwitchHook", t);
            }
        } else {
            Logger.i("ActivitySwitchHook disabled by config");
        }

        // 广播拦截
        if (config.isHookBroadcastEnabled()) {
            try {
                BroadcastHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init BroadcastHook", t);
            }
        } else {
            Logger.i("BroadcastHook disabled by config");
        }

        // ANR 拦截
        if (config.isHookANREnabled()) {
            try {
                ANRHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init ANRHook", t);
            }
        } else {
            Logger.i("ANRHook disabled by config");
        }

        // WakeLock 拦截
        if (config.isHookWakeLockEnabled()) {
            try {
                WakeLockHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init WakeLockHook", t);
            }
        } else {
            Logger.i("WakeLockHook disabled by config");
        }

        // 锁屏批量冻结
        if (config.isHookScreenStateEnabled()) {
            try {
                ScreenStateHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init ScreenStateHook", t);
            }
        } else {
            Logger.i("ScreenStateHook disabled by config");
        }

        Logger.i("All system framework hooks initialized");

        // 注册 IPC 服务到 ServiceManager，供 UI 进程调用
        TombstoneXService.register();
    }
}
