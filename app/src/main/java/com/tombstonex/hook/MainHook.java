package com.tombstonex.hook;

import android.os.Build;
import com.tombstonex.manager.ConfigManager;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String PACKAGE_ANDROID = "android";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

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
        } else if (PACKAGE_SYSTEMUI.equals(pkg)) {
            Logger.i("Hooking SystemUI");
        }
    }

    private void hookSystemFramework(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader classLoader = lpparam.classLoader;
        ConfigManager config = ConfigManager.getInstance();

        // 进程死亡清理 — 始终启用，防止内存泄漏和 PID 复用问题
        ProcessDeathHook.init(classLoader);

        // Activity 切换冻结 — 核心功能
        if (config.isHookActivitySwitchEnabled()) {
            ActivitySwitchHook.init(classLoader);
        } else {
            Logger.i("ActivitySwitchHook disabled by config");
        }

        // 广播拦截
        if (config.isHookBroadcastEnabled()) {
            BroadcastHook.init(classLoader);
        } else {
            Logger.i("BroadcastHook disabled by config");
        }

        // ANR 拦截
        if (config.isHookANREnabled()) {
            ANRHook.init(classLoader);
        } else {
            Logger.i("ANRHook disabled by config");
        }

        // WakeLock 拦截
        if (config.isHookWakeLockEnabled()) {
            WakeLockHook.init(classLoader);
        } else {
            Logger.i("WakeLockHook disabled by config");
        }

        // 锁屏批量冻结
        if (config.isHookScreenStateEnabled()) {
            ScreenStateHook.init(classLoader);
        } else {
            Logger.i("ScreenStateHook disabled by config");
        }

        Logger.i("All system framework hooks initialized");
    }
}
