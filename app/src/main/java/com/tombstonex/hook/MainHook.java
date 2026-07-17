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
        // P2-04: 不在 initZygote 中初始化 ConfigManager。
        // Zygote 启动阶段 /data/system 可能尚未挂载，此时读取配置文件会失败。
        // ConfigManager 改在 handleLoadPackage（android 包，即 system_server）中初始化，
        // 那时 /data/system 已就绪。这里仅用默认级别初始化 Logger，实际级别后续修正。
        Logger.init(false);
        Logger.i("TombstoneX Zygote init, SDK=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        String pkg = lpparam.packageName;
        Logger.i("handleLoadPackage: " + pkg + " process=" + lpparam.processName);

        if (PACKAGE_ANDROID.equals(pkg)) {
            Logger.i("Hooking System Framework (android)");

            // 设置系统属性标记模块已加载到 system_server。
            // App 端通过读取此属性区分"模块未加载"和"Binder服务注册失败"。
            try {
                Class<?> spClass = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
                setMethod.invoke(null, "persist.sys.tombstonex.active", "1");
                Logger.i("System property 'persist.sys.tombstonex.active' set to 1");
            } catch (Throwable t) {
                Logger.e("Failed to set system property tombstonex.active", t);
            }

            // P2-04: 在 system_server 中初始化 ConfigManager（此时 /data/system 已挂载就绪）。
            // ConfigManager.loadConfig() 内部已调用 Logger.init(debugEnabled) 修正日志级别。
            // P3-R4: 移除冗余的 Logger.init 调用，避免重复关闭/重新打开日志文件
            ConfigManager config = ConfigManager.getInstance();
            Logger.i("TombstoneX config loaded, SDK=" + Build.VERSION.SDK_INT
                + " freezeMode=" + config.getFreezeMode()
                + " delay=" + config.getFreezeDelay() + "s");
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
