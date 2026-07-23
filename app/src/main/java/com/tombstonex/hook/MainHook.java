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

    // L1: 确保 startBinderRetryThread 仅启动一次，避免多个重试线程并发注册
    private static final java.util.concurrent.atomic.AtomicBoolean retryStarted =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // R11-m-4: hookSystemFramework 重复注册守卫，避免多次调用导致 Hook 重复注册
    private static final java.util.concurrent.atomic.AtomicBoolean hooksRegistered =
        new java.util.concurrent.atomic.AtomicBoolean(false);

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
        // M5 修复：使用非持久属性 sys.tombstonex.loaded（不带 persist. 前缀）。
        // 旧属性 persist.sys.tombstonex.loaded 跨重启持久化，禁用模块后仍为 1，
        // 导致 App 端误判模块已加载。非持久属性会在重启后清除，准确反映模块当前状态。
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
            setMethod.invoke(null, "sys.tombstonex.loaded", "1");
            Logger.i("系统属性 sys.tombstonex.loaded 已设置为 1");
        } catch (Throwable t) {
            Logger.e("设置系统属性 sys.tombstonex.loaded 失败", t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        String pkg = lpparam.packageName;
        // R8-m4: processName 可能为 null，做 null 安全处理避免日志输出 "null" 引起混淆
        Logger.i("handleLoadPackage: " + pkg + " 进程="
            + (lpparam.processName != null ? lpparam.processName : "null"));

        if (PACKAGE_ANDROID.equals(pkg)) {
            Logger.i("正在 Hook 系统框架 (android)");

            // 设置系统属性标记模块已加载到 system_server。
            // App 端通过读取此属性区分"模块未加载"和"Binder服务注册失败"。
            // R8-M1: 改用非持久属性 sys.tombstonex.active（不带 persist. 前缀）。
            // 旧属性 persist.sys.tombstonex.active 跨重启持久化，模块禁用后重启仍为 "1"，
            // 导致 App 端误判模块已激活。非持久属性会在重启后清除，准确反映模块当前状态。
            try {
                Class<?> spClass = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
                setMethod.invoke(null, "sys.tombstonex.active", "1");
                Logger.i("系统属性 sys.tombstonex.active 已设置为 1");
            } catch (Throwable t) {
                Logger.e("设置系统属性 tombstonex.active 失败", t);
            }

            // R9-M3: 清除旧版持久属性（兼容升级场景）。
            // R8-M1 已将写入侧改为非持久属性 sys.tombstonex.active，但旧版模块曾写入
            // persist.sys.tombstonex.active，该值跨重启持久化，升级后仍残留旧值 "1"，
            // 导致 App 端（旧版 ServiceClient）误判模块已激活。此处主动清除。
            try {
                java.lang.reflect.Method setMethod = Class.forName("android.os.SystemProperties")
                    .getMethod("set", String.class, String.class);
                setMethod.invoke(null, "persist.sys.tombstonex.active", "");
            } catch (Throwable ignored) {
                Logger.d("清除 persist.sys.tombstonex.active 失败: " + ignored.getMessage());
            }
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
        // R11-m-4: 重复注册守卫，避免多次调用 hookSystemFramework 导致所有 Hook 被重复注册
        if (!hooksRegistered.compareAndSet(false, true)) {
            Logger.w("Hook 已注册，跳过重复注册");
            return;
        }
        ClassLoader classLoader = lpparam.classLoader;
        ConfigManager config = ConfigManager.getInstance();

        // M-5: 清除旧的持久属性 persist.sys.tombstonex.regstatus（兼容升级场景）。
        // regstatus 已改用非持久属性 sys.tombstonex.regstatus，旧值跨重启持久化会导致误判。
        try {
            java.lang.reflect.Method setMethod = Class.forName("android.os.SystemProperties")
                .getMethod("set", String.class, String.class);
            setMethod.invoke(null, "persist.sys.tombstonex.regstatus", "");
        } catch (Throwable ignored) {
            Logger.d("清除 persist.sys.tombstonex.regstatus 失败: " + ignored.getMessage());
        }
        try {
            SystemFreezerDisableHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("系统冻结器禁用 Hook 初始化失败", t);
        }

        // 2. 智能状态识别（其他 Hook 依赖此模块判断应用是否活跃）
        try {
            SmartStateHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("智能状态 Hook 初始化失败", t);
        }

        // 3. 进程死亡清理 — 始终启用，防止内存泄漏和 PID 复用问题
        try {
            ProcessDeathHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("进程死亡 Hook 初始化失败", t);
        }

        // 4. Activity 切换冻结 — 核心功能
        if (config.isHookActivitySwitchEnabled()) {
            try {
                ActivitySwitchHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("Activity 切换 Hook 初始化失败", t);
            }
        } else {
            Logger.i("Activity 切换 Hook 已被配置禁用");
        }

        // 5. 广播拦截
        if (config.isHookBroadcastEnabled()) {
            try {
                BroadcastHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("广播拦截 Hook 初始化失败", t);
            }
        } else {
            Logger.i("广播拦截 Hook 已被配置禁用");
        }

        // 6. ANR 拦截
        if (config.isHookANREnabled()) {
            try {
                ANRHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("ANR 拦截 Hook 初始化失败", t);
            }
        } else {
            Logger.i("ANR 拦截 Hook 已被配置禁用");
        }

        // 7. WakeLock 拦截
        if (config.isHookWakeLockEnabled()) {
            try {
                WakeLockHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("WakeLock 拦截 Hook 初始化失败", t);
            }
        } else {
            Logger.i("WakeLock 拦截 Hook 已被配置禁用");
        }

        // 8. 锁屏批量冻结
        if (config.isHookScreenStateEnabled()) {
            try {
                ScreenStateHook.init(classLoader);
            } catch (Throwable t) {
                Logger.e("锁屏状态 Hook 初始化失败", t);
            }
        } else {
            Logger.i("锁屏状态 Hook 已被配置禁用");
        }

        // 9. Activity 休眠保护（防止冻结应用在最近任务中消失）
        try {
            ActivitySleepHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("Activity 休眠保护 Hook 初始化失败", t);
        }

        // 10. 冻结新进程（拦截后台应用启动新进程）
        try {
            ProcessStartHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("进程启动拦截 Hook 初始化失败", t);
        }

        // 11. 冻结后断网
        try {
            NetworkHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("网络拦截 Hook 初始化失败", t);
        }

        // 12. 自启拦截
        try {
            AutoStartHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("自启拦截 Hook 初始化失败", t);
        }

        // 13. 定时器限制（禁止冻结应用设置闹钟/定时器）
        // R10-m-2: TimerHook 为基础功能，始终启用，未像其他 Hook 一样通过配置门控。
        // 若后续需要支持禁用，可通过 ConfigManager.isHookTimerEnabled() 门控（留作后续优化）。
        // TODO: 后续在 ConfigManager 中添加 isHookTimerEnabled() 配置项并在此门控。
        try {
            TimerHook.init(classLoader);
        } catch (Throwable t) {
            Logger.e("定时器限制 Hook 初始化失败", t);
        }

        Logger.i("所有系统框架 Hook 已初始化");

        // 启动后台管理器
        try {
            // 定时冻结（每分钟扫描）
            com.tombstonex.manager.ScheduledFreezeManager.getInstance().start();
            Logger.i("定时冻结管理器已启动");
        } catch (Throwable t) {
            Logger.e("定时冻结管理器启动失败", t);
        }

        try {
            // 轮番解冻（定期解冻最久应用 3 秒）
            com.tombstonex.manager.RotationThawManager.getInstance().start();
            Logger.i("轮换解冻管理器已启动");
        } catch (Throwable t) {
            Logger.e("轮换解冻管理器启动失败", t);
        }

        try {
            // ReKernel 集成（可选，ReKernel 不存在时安全跳过）
            com.tombstonex.hook.ReKernelHook.init();
        } catch (Throwable t) {
            Logger.e("ReKernel Hook 初始化失败", t);
        }

        // 注册 IPC 服务到 ServiceManager，供 UI 进程调用
        // 优先尝试 Binder 注册（需要 SELinux 策略支持，Magisk 模块提供）
        // R8-S3: 包裹 try-catch 防止 register() 异常传播到 handleLoadPackage，
        // 导致后续 Binder 重试逻辑被跳过
        try {
            TombstoneXService.register();
        } catch (Throwable t) {
            Logger.e("TombstoneXService 注册失败", t);
        }

        // 检查 Binder 是否注册成功，如果失败则启动后台重试线程
        // （SELinux 策略可能由 service.sh 在稍后注入，需要重试）
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = spClass.getMethod("get", String.class);
            String regStatus = (String) getMethod.invoke(null, "sys.tombstonex.regstatus");
            if (!"ok".equals(regStatus) && !"already_registered".equals(regStatus)) {
                Logger.i("Binder 注册失败 (status=" + regStatus + ")，启动后台重试线程");
                startBinderRetryThread();
            } else {
                Logger.i("Binder 注册成功");
            }
        } catch (Throwable t) {
            Logger.e("检查注册状态失败，无法决定是否重试", t);
        }
    }

    /**
     * 后台重试 Binder 注册。
     * SELinux 策略可能由 Magisk 模块的 service.sh 在 system_server 启动后注入，
     * 因此需要定期重试 addService，直到成功或超过最大重试次数。
     *
     * L1 修复：使用 AtomicBoolean 保证仅启动一个重试线程，避免多次调用
     * startBinderRetryThread 时创建多个并发重试线程。
     */
    private static void startBinderRetryThread() {
        if (!retryStarted.compareAndSet(false, true)) return;
        Thread retryThread = new Thread(() -> {
            // R8-M9: 使用 try-finally 在重试线程结束时（无论成功/失败/中断）重置 retryStarted，
            // 允许模块重载后重新启动重试线程。旧代码设为 true 后永不重置，导致模块重载后无法重试。
            try {
                for (int i = 0; i < 30; i++) {  // 最多重试 30 次，每次间隔 5 秒（共 150 秒）
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        // R10-m-1: 恢复中断标志，允许上层（如线程池关闭/模块卸载）感知中断状态，
                        // 避免吞掉中断信号导致线程无法被正确终止。
                        Thread.currentThread().interrupt();
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
                        String regStatus = (String) getMethod.invoke(null, "sys.tombstonex.regstatus");
                        if ("ok".equals(regStatus) || "already_registered".equals(regStatus)) {
                            Logger.i("Binder 重试：注册成功（status=" + regStatus + "），共尝试 " + (i + 1) + " 次");
                            return;
                        }
                        Logger.d("Binder 重试第 " + (i + 1) + " 次失败，status=" + regStatus);
                    } catch (Throwable t) {
                        Logger.e("Binder 重试第 " + (i + 1) + " 次出错", t);
                    }
                }
                Logger.w("Binder 重试：已达到最大重试次数，Binder 服务未注册成功");
            } finally {
                retryStarted.set(false);
            }
        }, "TombstoneX-BinderRetry");
        retryThread.setDaemon(true);
        retryThread.start();
    }
}
