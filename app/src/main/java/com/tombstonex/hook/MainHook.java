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
        Logger.i("TombstoneX Zygote 初始化, SDK=" + Build.VERSION.SDK_INT);

        // 设置系统属性标记模块已被 LSPosed 加载。
        // initZygote 在 Zygote 进程中运行，只要 LSPosed 启用了模块就会调用。
        // App 端通过此属性判断 LSPosed 是否已启用模块。
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
            setMethod.invoke(null, "persist.sys.tombstonex.loaded", "1");
            Logger.i("系统属性 'persist.sys.tombstonex.loaded' 已设置为 1");
        } catch (Throwable t) {
            Logger.e("设置系统属性 tombstonex.loaded 失败", t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        String pkg = lpparam.packageName;
        Logger.i("handleLoadPackage: " + pkg + " 进程=" + lpparam.processName);

        if (PACKAGE_ANDROID.equals(pkg)) {
            Logger.i("正在 Hook 系统 Framework (android)");

            // 设置系统属性标记模块已加载到 system_server。
            // App 端通过读取此属性区分"模块未加载"和"Binder服务注册失败"。
            try {
                Class<?> spClass = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
                setMethod.invoke(null, "persist.sys.tombstonex.active", "1");
                Logger.i("系统属性 'persist.sys.tombstonex.active' 已设置为 1");
            } catch (Throwable t) {
                Logger.e("设置系统属性 tombstonex.active 失败", t);
            }

            // P2-04: 在 system_server 中初始化 ConfigManager（此时 /data/system 已挂载就绪）。
            // ConfigManager.loadConfig() 内部已调用 Logger.init(debugEnabled) 修正日志级别。
            // P3-R4: 移除冗余的 Logger.init 调用，避免重复关闭/重新打开日志文件
            ConfigManager config = ConfigManager.getInstance();
            Logger.i("TombstoneX 配置已加载, SDK=" + Build.VERSION.SDK_INT
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
            Logger.e("SystemFreezerDisableHook 初始化失败", t);
        }

        // 2. 智能状态识别（其他 Hook 依赖此模块判断应用是否活跃）
        try {
            SmartStateHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("SmartStateHook 初始化失败", t);
        }

        // 3. 进程死亡清理 — 始终启用，防止内存泄漏和 PID 复用问题
        try {
            ProcessDeathHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("ProcessDeathHook 初始化失败", t);
        }

        // 4. Activity 切换冻结 — 核心功能
        if (config.isHookActivitySwitchEnabled()) {
            try {
                ActivitySwitchHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("ActivitySwitchHook 初始化失败", t);
            }
        } else {
            Logger.i("ActivitySwitchHook 已被配置禁用");
        }

        // 5. 广播拦截
        if (config.isHookBroadcastEnabled()) {
            try {
                BroadcastHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("BroadcastHook 初始化失败", t);
            }
        } else {
            Logger.i("BroadcastHook 已被配置禁用");
        }

        // 6. ANR 拦截
        if (config.isHookANREnabled()) {
            try {
                ANRHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("ANRHook 初始化失败", t);
            }
        } else {
            Logger.i("ANRHook 已被配置禁用");
        }

        // 7. WakeLock 拦截
        if (config.isHookWakeLockEnabled()) {
            try {
                WakeLockHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("WakeLockHook 初始化失败", t);
            }
        } else {
            Logger.i("WakeLockHook 已被配置禁用");
        }

        // 8. 锁屏批量冻结
        if (config.isHookScreenStateEnabled()) {
            try {
                ScreenStateHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("ScreenStateHook 初始化失败", t);
            }
        } else {
            Logger.i("ScreenStateHook 已被配置禁用");
        }

        // 9. Activity 休眠保护（防止冻结应用在最近任务中消失）
        try {
            ActivitySleepHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("ActivitySleepHook 初始化失败", t);
        }

        // 10. 冻结新进程（拦截后台应用启动新进程）
        try {
            ProcessStartHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("ProcessStartHook 初始化失败", t);
        }

        // 11. 冻结后断网
        try {
            NetworkHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("NetworkHook 初始化失败", t);
        }

        // 12. 自启拦截
        try {
            AutoStartHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("AutoStartHook 初始化失败", t);
        }

        // 13. 定时器限制（禁止冻结应用设置闹钟/定时器）
        try {
            TimerHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("TimerHook 初始化失败", t);
        }

        Logger.i("所有系统 Framework Hook 已初始化");

        // 启动后台管理器
        try {
            // 定时冻结（每分钟扫描）
            com.tombstonex.manager.ScheduledFreezeManager.getInstance().start();
            Logger.i("ScheduledFreezeManager 已启动");
        } catch (Throwable t) {
            Logger.e("ScheduledFreezeManager 启动失败", t);
        }

        try {
            // 轮番解冻（定期解冻最久应用 3 秒）
            com.tombstonex.manager.RotationThawManager.getInstance().start();
            Logger.i("RotationThawManager 已启动");
        } catch (Throwable t) {
            Logger.e("RotationThawManager 启动失败", t);
        }

        try {
            // ReKernel 集成（可选，ReKernel 不存在时安全跳过）
            com.tombstonex.hook.ReKernelHook.init();
        } catch (Throwable t) {
            Logger.e("ReKernelHook 初始化失败", t);
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
                Logger.i("Binder 注册失败 (status=" + regStatus + ")，启动后台重试线程");
                startBinderRetryThread();
            } else {
                Logger.i("Binder 注册成功，无需 FileIPC");
            }
        } catch (Throwable t) {
            Logger.e("检查注册状态失败，无法决定是否重试", t);
        }

        // 启动文件 IPC 作为降级通信方案（Binder 成功时作为备用，失败时作为主通道）
        try {
            com.tombstonex.service.FileIPC.start();
            Logger.i("FileIPC 已启动，作为降级 IPC 通道");
        } catch (Throwable t) {
            Logger.e("FileIPC 启动失败", t);
        }
    }

    /**
     * 后台重试 Binder 注册。
     * SELinux 策略可能由 Magisk 模块的 service.sh 在 system_server 启动后注入，
     * 因此需要定期重试 addService，直到成功或超过最大重试次数。
     */
    private static void startBinderRetryThread() {
        Thread retryThread = new Thread(() -> {
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
                        Logger.i("Binder 重试：服务已被其他实例注册");
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
                        Logger.i("Binder 重试：注册成功，共尝试 " + (i + 1) + " 次");
                        return;
                    }
                    Logger.d("Binder 重试第 " + (i + 1) + " 次失败，status=" + regStatus);
                } catch (Throwable t) {
                    Logger.e("Binder 重试第 " + (i + 1) + " 次出错", t);
                }
            }
            Logger.w("Binder 重试：已达到最大重试次数，继续使用 FileIPC");
        }, "TombstoneX-BinderRetry");
        retryThread.setDaemon(true);
        retryThread.start();
    }
}
