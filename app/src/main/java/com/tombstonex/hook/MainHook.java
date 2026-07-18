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

        // 设置系统属性标记模块已被 LSPosed 加载。
        // initZygote 在 Zygote 进程中运行，只要 LSPosed 启用了模块就会调用。
        // App 端通过此属性判断 LSPosed 是否已启用模块。
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
            setMethod.invoke(null, "persist.sys.tombstonex.loaded", "1");
            Logger.i("System property 'persist.sys.tombstonex.loaded' set to 1");
        } catch (Throwable t) {
            Logger.e("Failed to set system property tombstonex.loaded", t);
        }
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

        // 1. 禁用系统自带 Cached Apps Freezer（必须最先执行，避免与 TombstoneX 冲突）
        try {
            SystemFreezerDisableHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init SystemFreezerDisableHook", t);
        }

        // 2. 智能状态识别（其他 Hook 依赖此模块判断应用是否活跃）
        try {
            SmartStateHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init SmartStateHook", t);
        }

        // 3. 进程死亡清理 — 始终启用，防止内存泄漏和 PID 复用问题
        try {
            ProcessDeathHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init ProcessDeathHook", t);
        }

        // 4. Activity 切换冻结 — 核心功能
        if (config.isHookActivitySwitchEnabled()) {
            try {
                ActivitySwitchHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init ActivitySwitchHook", t);
            }
        } else {
            Logger.i("ActivitySwitchHook disabled by config");
        }

        // 5. 广播拦截
        if (config.isHookBroadcastEnabled()) {
            try {
                BroadcastHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init BroadcastHook", t);
            }
        } else {
            Logger.i("BroadcastHook disabled by config");
        }

        // 6. ANR 拦截
        if (config.isHookANREnabled()) {
            try {
                ANRHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init ANRHook", t);
            }
        } else {
            Logger.i("ANRHook disabled by config");
        }

        // 7. WakeLock 拦截
        if (config.isHookWakeLockEnabled()) {
            try {
                WakeLockHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init WakeLockHook", t);
            }
        } else {
            Logger.i("WakeLockHook disabled by config");
        }

        // 8. 锁屏批量冻结
        if (config.isHookScreenStateEnabled()) {
            try {
                ScreenStateHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Failed to init ScreenStateHook", t);
            }
        } else {
            Logger.i("ScreenStateHook disabled by config");
        }

        // 9. Activity 休眠保护（防止冻结应用在最近任务中消失）
        try {
            ActivitySleepHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init ActivitySleepHook", t);
        }

        // 10. 冻结新进程（拦截后台应用启动新进程）
        try {
            ProcessStartHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init ProcessStartHook", t);
        }

        // 11. 冻结后断网
        try {
            NetworkHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init NetworkHook", t);
        }

        // 12. 自启拦截
        try {
            AutoStartHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init AutoStartHook", t);
        }

        // 13. 定时器限制（禁止冻结应用设置闹钟/定时器）
        try {
            TimerHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Failed to init TimerHook", t);
        }

        Logger.i("All system framework hooks initialized");

        // 启动后台管理器
        try {
            // 定时冻结（每分钟扫描）
            com.tombstonex.manager.ScheduledFreezeManager.getInstance().start();
            Logger.i("ScheduledFreezeManager started");
        } catch (Throwable t) {
            Logger.e("Failed to start ScheduledFreezeManager", t);
        }

        try {
            // 轮番解冻（定期解冻最久应用 3 秒）
            com.tombstonex.manager.RotationThawManager.getInstance().start();
            Logger.i("RotationThawManager started");
        } catch (Throwable t) {
            Logger.e("Failed to start RotationThawManager", t);
        }

        try {
            // ReKernel 集成（可选，ReKernel 不存在时安全跳过）
            com.tombstonex.hook.ReKernelHook.init();
        } catch (Throwable t) {
            Logger.e("Failed to init ReKernelHook", t);
        }

        // 注册 IPC 服务到 ServiceManager，供 UI 进程调用
        // 优先尝试 Binder 注册（需要 SELinux 策略支持，Magisk 模块提供）
        TombstoneXService.register();

        // 检查 Binder 是否注册成功，如果失败则启动后台重试线程
        // （SELinux 策略可能由 service.sh 在稍后注入，需要重试）
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = spClass.getMethod("get", String.class);
            String regStatus = (String) getMethod.invoke(null, "persist.sys.tombstonex.regstatus");
            if (!"ok".equals(regStatus) && !"already_registered".equals(regStatus)) {
                Logger.i("Binder registration failed (status=" + regStatus + "), starting background retry thread");
                startBinderRetryThread();
            } else {
                Logger.i("Binder registration OK, FileIPC not needed");
            }
        } catch (Throwable t) {
            Logger.e("Failed to check reg status for retry decision", t);
        }

        // 启动文件 IPC 作为降级通信方案（Binder 成功时作为备用，失败时作为主通道）
        try {
            com.tombstonex.service.FileIPC.start();
            Logger.i("FileIPC started as fallback IPC channel");
        } catch (Throwable t) {
            Logger.e("Failed to start FileIPC", t);
        }
    }

    /**
     * 后台重试 Binder 注册。
     * SELinux 策略可能由 Magisk 模块的 service.sh 在 system_server 启动后注入，
     * 因此需要定期重试 addService，直到成功或超过最大重试次数。
     */
    private static void startBinderRetryThread() {
        new Thread(() -> {
            for (int i = 0; i < 30; i++) {  // 最多重试 30 次，每次间隔 5 秒（共 150 秒）
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    // 检查是否已有服务注册（可能其他实例已成功）
                    Class<?> smClass = Class.forName("android.os.ServiceManager");
                    java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
                    Object existing = getService.invoke(null, "tombstonex");
                    if (existing != null) {
                        Logger.i("Binder retry: service already registered by another instance");
                        TombstoneXService.setRegStatusPublic("already_registered");
                        return;
                    }
                    // 尝试注册
                    TombstoneXService.register();
                    // 检查结果
                    Class<?> spClass = Class.forName("android.os.SystemProperties");
                    java.lang.reflect.Method getMethod = spClass.getMethod("get", String.class);
                    String regStatus = (String) getMethod.invoke(null, "persist.sys.tombstonex.regstatus");
                    if ("ok".equals(regStatus)) {
                        Logger.i("Binder retry: registration succeeded after " + (i + 1) + " attempts");
                        return;
                    }
                    Logger.d("Binder retry attempt " + (i + 1) + " failed, status=" + regStatus);
                } catch (Throwable t) {
                    Logger.e("Binder retry attempt " + (i + 1) + " error", t);
                }
            }
            Logger.w("Binder retry: max attempts reached, staying on FileIPC");
        }, "TombstoneX-BinderRetry").start();
    }
}
