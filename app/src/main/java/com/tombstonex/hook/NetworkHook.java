package com.tombstonex.hook;

import com.tombstonex.manager.AppConfigManager;
import com.tombstonex.manager.ProcessTracker;
import com.tombstonex.model.AppInfo;
import com.tombstonex.model.AppState;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冻结后断网 Hook
 * 冻结应用后断开其网络连接，解冻时恢复：
 * - Hook ConnectivityService.getAllNetworkStateForUid，对冻结应用返回空网络
 * - onProcessFrozen：通过 NetworkManagementService.setFirewallUidRule 设置防火墙规则阻断 UID 流量
 * - onProcessUnfrozen：恢复 UID 网络访问
 * 注意：通过反射调用隐藏 API；检查 AppConfigManager.keepConnection 配置（不存在则默认 false）
 */
public class NetworkHook {

    private static volatile boolean initialized = false;

    // R8-S7: UID 级别锁，原子化 isUidFrozen 检查与 setUidNetworkPolicy 设置，
    // 避免 sharedUserId 下另一线程在检查与设置之间冻结同 UID 进程的 TOCTOU 竞态。
    // R9-m9-2: uidLocks 设计上永不清理。每个 UID 条目仅持有一个 Object 锁对象（约16字节），
    // 200 个应用 UID 总计约 3.2KB，内存开销可接受。清理需引入额外同步与生命周期管理，
    // 收益不抵成本，故保持现状。
    private static final ConcurrentHashMap<Integer, Object> uidLocks = new ConcurrentHashMap<>();

    // R10-m-3: 缓存 setFirewallUidRule / setUidNetworkRules 的 Method 引用，
    // 避免每次 setUidNetworkPolicy 都反射查找方法。NMS 代理对象的类稳定，Method 可安全缓存。
    private static volatile Method cachedFirewallMethod = null;
    private static volatile Method cachedFallbackMethod = null;
    // R10-m-3: 缓存 NetworkManagementService 引用，避免每次都通过 ServiceManager 解析。
    // 注意：Binder 代理可能失效（DeadObjectException），失效时需重置缓存并重新获取。
    private static volatile Object cachedNms = null;

    // S-2: setFirewallUidRule 是否可用（在 init() 阶段探测）。
    // Android 12+ 起，防火墙规则从 netd 迁移到 BpfNetMaps（基于 eBPF），
    // NMS.setFirewallUidRule 在部分 Android 12+ 版本上可能被废弃或内部转发到
    // BpfNetMaps 后行为不一致。若探测到不可用，需回退到 ConnectivityService
    // 的 setUidFirewallRule 接口作为替代路径。
    private static volatile boolean firewallUidRuleProbed = false;
    private static volatile boolean firewallUidRuleAvailable = false;
    // S-2: 缓存 ConnectivityService 的 setUidFirewallRule Method（替代路径）
    private static volatile Method cachedCtSetUidFirewallRule = null;
    private static volatile Object cachedConnectivityService = null;

    public static void init(ClassLoader classLoader) {
        hookGetAllNetworkStateForUid(classLoader);
        // R8-m8-2: 已移除 hookNetworkAgentInfo 调用——NetworkAgentInfo 无 mUid 字段，
        // 旧 Hook 的 getIntField("mUid") 恒失败后 return，纯性能开销无收益。
        // S-2: 探测 NMS.setFirewallUidRule 是否可用。
        // Android 12+ 起，防火墙规则从 netd 迁移到 BpfNetMaps（基于 eBPF），
        // NMS.setFirewallUidRule 可能被废弃或行为不一致。提前探测以便在
        // setUidNetworkPolicy 中选择正确的路径，避免运行时静默失败。
        probeFirewallUidRuleAvailability();
        initialized = true;
        Logger.i("NetworkHook 已初始化");
    }

    /**
     * S-2: 探测 NMS.setFirewallUidRule 方法是否可用。
     * 通过获取 NMS 代理并检查方法是否存在来判断。
     * 若不可用，后续 setUidNetworkPolicy 将回退到 ConnectivityService.setUidFirewallRule。
     */
    private static void probeFirewallUidRuleAvailability() {
        firewallUidRuleProbed = true;
        try {
            Object nms = getCachedNms();
            if (nms == null) {
                Logger.w("S-2: NMS 不可用，setFirewallUidRule 探测失败，将使用回退路径");
                firewallUidRuleAvailable = false;
                return;
            }
            Method m = findMethod(nms.getClass(), "setFirewallUidRule",
                int.class, int.class, int.class);
            firewallUidRuleAvailable = (m != null);
            if (firewallUidRuleAvailable) {
                Logger.i("S-2: NMS.setFirewallUidRule 可用");
            } else {
                // S-2: Android 12+ BpfNetMaps 迁移说明
                Logger.e("S-2: NMS.setFirewallUidRule 不可用，可能因 Android 12+ BpfNetMaps 迁移。" +
                    "将回退到 ConnectivityService.setUidFirewallRule 或 setUidNetworkRules", null);
            }
        } catch (Throwable t) {
            firewallUidRuleAvailable = false;
            Logger.w("S-2: 探测 setFirewallUidRule 可用性失败: " + t.getMessage());
        }
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
                        // R8-m8-7: 假设 UID 是该方法第一个 int 参数（AOSP getAllNetworkStateForUid
                        // 签名为 (int uid, ...)）。
                        // M-3: 优先取 param.args[0] 作为 UID，仅在它不是 Integer 时才扫描后续参数，
                        // 避免首个非 UID 的 Integer 参数（如 flags 等）被误匹配。
                        if (param.args == null || param.args.length == 0) return;
                        int uid = -1;
                        // M-3: 优先取首个参数
                        if (param.args[0] instanceof Integer) {
                            uid = (int) param.args[0];
                        } else {
                            // M-3: 首个参数不是 Integer，扫描后续参数
                            for (int i = 1; i < param.args.length; i++) {
                                if (param.args[i] instanceof Integer) {
                                    uid = (int) param.args[i];
                                    break;
                                }
                            }
                        }
                        if (uid < 0) return; // 未找到 int 参数，无法判定
                        if (uid < 10000) return; // 系统应用不拦截

                        if (isUidFrozen(uid)) {
                            // 返回该返回类型的空数组
                            Class<?> returnType = param.method.getReturnType();
                            if (returnType.isArray()) {
                                Object emptyArray = Array.newInstance(
                                    returnType.getComponentType(), 0);
                                param.setResult(emptyArray);
                                Logger.d("已拦截已冻结 uid 的 getAllNetworkStateForUid uid=" + uid);
                            }
                        }
                    } catch (Throwable t) {
                        Logger.e("getAllNetworkStateForUid Hook 出错", t);
                    }
                }
            };

            int n = hookAllMethodsByName(csClass, "getAllNetworkStateForUid", callback);
            if (n > 0) {
                Logger.i("已 Hook getAllNetworkStateForUid (" + n + " 个重载)");
            } else {
                Logger.w("未找到 getAllNetworkStateForUid");
            }
        } catch (Throwable t) {
            Logger.e("Hook getAllNetworkStateForUid 失败", t);
        }
    }

    /**
     * R8-m8-2: hookNetworkAgentInfo 已移除。
     *
     * 旧代码 Hook NetworkAgentInfo.setConnected / unregister 并通过 getIntField("mUid")
     * 获取 uid，但 NetworkAgentInfo 在 AOSP 各版本中均无 mUid 字段（uid 存于其
     * networkCapabilities / mNetworkCapabilities 中），导致 getIntField 恒失败后 return，
     * Hook 回调从不产生实际效果，仅带来 Xposed 回调链 + 反射的性能开销。
     * 如未来需要观察网络代理状态，应从 networkCapabilities 提取 UID（较复杂），暂移除。
     */

    /**
     * 进程冻结成功后调用：断开该 UID 的网络
     * 通过 setUidNetworkPolicy 设置防火墙规则（allow=false）。
     */
    public static void onProcessFrozen(int uid, String packageName) {
        if (!initialized) return;
        if (uid < 10000) return; // 系统应用不断网
        // R8-S7: UID 级别锁内执行设置，与 onProcessUnfrozen 的检查+设置互斥，避免竞态
        Object lock = uidLocks.computeIfAbsent(uid, k -> new Object());
        synchronized (lock) {
            // S-3: TOCTOU 修复——先收集同 UID 所有包名的 keepConnection 配置快照，
            // 再基于快照统一判断，避免在逐个检查过程中配置发生变化导致判断不一致。
            // 旧代码在循环中逐个调用 isKeepConnection，每次调用间配置可能被修改。
            java.util.List<AppInfo> sameUidProcesses = ProcessTracker.getInstance().getByUid(uid);
            java.util.Map<String, Boolean> keepConnectionSnapshot = new java.util.HashMap<>();
            for (AppInfo info : sameUidProcesses) {
                keepConnectionSnapshot.put(info.packageName, isKeepConnection(info.packageName));
            }
            // R9-M9-3: keepConnection 检查移入锁内，并在检测到 keepConnection 时主动恢复网络，
            // 撤销可能已存在的阻断。旧代码在锁外检查后直接 return，无法撤销已存在的阻断。
            // R8-M8-7: keepConnection 是 per-package，而 setUidNetworkPolicy 是 per-UID。
            // sharedUserId 场景下同 UID 可能有多个包，需检查同 UID 是否有任一包启用 keepConnection，
            // 否则会误断共享该 UID 的 keepConnection 包的网络。
            // R11-M-4: keepConnection 检查与 registerProcess 之间存在 TOCTOU 窗口——
            // 新进程可能在 keepConnection 检查之后、setUidNetworkPolicy 之前注册到 ProcessTracker。
            // 此窗口在实践中概率极低（批量冻结在息屏后延迟执行，新进程注册需前台启动），
            // 且解冻时 onProcessUnfrozen 会恢复网络，故不改代码。
            for (java.util.Map.Entry<String, Boolean> entry : keepConnectionSnapshot.entrySet()) {
                if (entry.getValue()) {
                    Logger.d("同 UID 存在 keepConnection 包，跳过断网并恢复 uid=" + uid
                        + " pkg=" + packageName + " keepConnPkg=" + entry.getKey());
                    setUidNetworkPolicy(uid, true); // 撤销可能已存在的阻断
                    return;
                }
            }
            // R10-m-5: 在日志中使用 packageName 参数提升可读性
            Logger.d("冻结进程断网 uid=" + uid + " pkg=" + packageName);
            setUidNetworkPolicy(uid, false);
        }
    }

    /**
     * 进程解冻时调用：恢复该 UID 的网络访问
     */
    public static void onProcessUnfrozen(int uid, String packageName) {
        if (!initialized) return;
        if (uid < 10000) return;
        // R9-S9-1: 移除 isKeepConnection 早返回。
        // onProcessFrozen 已通过同 UID keepConnection 检查防止了不必要的阻断；
        // onProcessUnfrozen 应始终执行 isUidFrozen 检查并在无冻结进程时恢复网络。
        // 旧代码在 sharedUserId 场景下：非 keepConnection 包 A 先冻结阻断了 UID 网络，
        // keepConnection 包 B 解冻时因 keepConnection=true 跳过恢复，导致 UID 网络永久阻断。
        // R8-S7: 在 UID 级别锁内原子化检查+设置，避免 sharedUserId 下另一线程在
        // isUidFrozen 检查与 setUidNetworkPolicy 之间冻结同 UID 进程的 TOCTOU 竞态
        Object lock = uidLocks.computeIfAbsent(uid, k -> new Object());
        synchronized (lock) {
            // M2: 检查同 UID 是否还有其他冻结进程，若有则保持 DENY，避免过早恢复网络
            if (isUidFrozen(uid)) {
                // R10-m-5: 在日志中使用 packageName 参数提升可读性
                Logger.d("解冻进程但同 UID 仍有冻结进程，保持断网 uid=" + uid + " pkg=" + packageName);
                return;
            }
            // R10-m-5: 在日志中使用 packageName 参数提升可读性
            Logger.d("解冻进程恢复网络 uid=" + uid + " pkg=" + packageName);
            setUidNetworkPolicy(uid, true);
        }
    }

    /**
     * 通过 NetworkManagementService 设置 UID 网络阻断规则
     *
     * S5 修复：旧代码调用 setUidNetworkRules(int, boolean)，其 boolean 参数语义为
     * "是否允许在计量网络上访问"（allowOnMetered），而非"是否允许所有网络访问"。
     * 设为 false 仅阻断计量网络（如移动数据），不阻断 Wi-Fi 等非计量网络，
     * 导致冻结应用仍可通过 Wi-Fi 联网。
     *
     * 改用 firewall 规则 setFirewallUidRule(chain, uid, rule) 直接阻断 UID 的所有流量。
     * 在 DOZABLE / STANDBY / POWERSAVE / RESTRICTED / LOW_POWER_STANDBY（及 Android 15+ 的 BACKGROUND）链上同时设置规则，最大化阻断覆盖：
     *   - FIREWALL_CHAIN_DOZABLE (1): 设备进入 Doze 时生效
     *   - FIREWALL_CHAIN_STANDBY (2): 应用处于待机时生效
     *   - FIREWALL_CHAIN_POWERSAVE (3): 省电模式时生效
     *   - FIREWALL_CHAIN_RESTRICTED (4): Android 13+ 受限网络时生效
     *   - FIREWALL_CHAIN_LOW_POWER_STANDBY (5): Android 13+ 低电量待机时生效
     *   - FIREWALL_CHAIN_BACKGROUND (6): Android 15+ 后台限制时生效
     * rule: FIREWALL_RULE_ALLOW (1) / FIREWALL_RULE_DENY (2)
     *
     * system_server 通过 NetworkManagementService 调用 netd（root 权限）操作防火墙，
     * 无需 su。若 firewall 接口不可用，回退到 setUidNetworkRules（仅阻断计量网络，聊胜于无）。
     *
     * 注意：firewall 规则仅在设备进入对应模式（Doze/Standby）时生效。
     * 在正常后台状态下，依赖 hookGetAllNetworkStateForUid 提供应用层网络拦截。
     *
     * @param uid 目标 UID
     * @param allow true=允许网络（解冻），false=拒绝网络（冻结）
     */
    private static void setUidNetworkPolicy(int uid, boolean allow) {
        // R10-m-3: 缓存 Method 和 NMS 引用减少反射开销；NMS Binder 代理可能失效，
        // 失效时重置缓存并重试一次。
        boolean retried = false;
        retry:
        while (true) {
        try {
            Object nms = getCachedNms();
            if (nms == null) {
                Logger.w("NetworkManagementService 不可用，无法设置网络规则 uid=" + uid);
                return;
            }

            // firewall 链与规则常量（与 AOSP SocketBindParse / NetworkPolicyManager 一致）
            final int FIREWALL_CHAIN_DOZABLE = 1;
            final int FIREWALL_CHAIN_STANDBY = 2;
            // R8-M8-5: 补充 POWERSAVE(3) 链；Android 14+ 新增 BACKGROUND(6) 链。
            // 旧代码仅设置 DOZABLE/STANDBY 两条链，POWERSAVE/BACKGROUND 模式下规则不生效，
            // 导致冻结应用在省电模式或后台限制下仍可联网。
            final int FIREWALL_CHAIN_POWERSAVE = 3;
            // R9-M9-4: 补充 RESTRICTED(4) / LOW_POWER_STANDBY(5) 链。
            // AOSP 自 Android 13 (API 33, TIRAMISU) 起存在这两条链。
            final int FIREWALL_CHAIN_RESTRICTED = 4;
            final int FIREWALL_CHAIN_LOW_POWER_STANDBY = 5;
            // R9-M9-1: BACKGROUND(6) 链的 @RequiresApi 为 VANILLA_ICE_CREAM (API 35)。
            // 旧代码使用 SDK_INT >= 34 (Android 14)，但在 Android 14 上 chain 6 是
            // LOCKDOWN_VPN 而非 BACKGROUND，会导致误设置。修正为 SDK_INT >= 35。
            final int FIREWALL_CHAIN_BACKGROUND = 6; // Android 15+ (API 35)
            final int FIREWALL_RULE_ALLOW = 1;
            final int FIREWALL_RULE_DENY = 2;
            int rule = allow ? FIREWALL_RULE_ALLOW : FIREWALL_RULE_DENY;

            // R10-m-3: 优先使用缓存的 Method 引用，miss 时反射查找后缓存
            Method firewallMethod = getCachedFirewallMethod(nms);
            if (firewallMethod != null) {
                // m-3: setAccessible(true) 已在 getCachedFirewallMethod 缓存时一次性设置
                // R9-M9-1/R9-M9-4: 根据 SDK 版本分层选择链集合（if-else 确保正确的链集合）
                int[] chains;
                String[] chainNames;
                if (android.os.Build.VERSION.SDK_INT >= 35) { // VANILLA_ICE_CREAM
                    chains = new int[]{FIREWALL_CHAIN_DOZABLE, FIREWALL_CHAIN_STANDBY,
                        FIREWALL_CHAIN_POWERSAVE, FIREWALL_CHAIN_RESTRICTED,
                        FIREWALL_CHAIN_LOW_POWER_STANDBY, FIREWALL_CHAIN_BACKGROUND};
                    chainNames = new String[]{"DOZABLE", "STANDBY", "POWERSAVE",
                        "RESTRICTED", "LOW_POWER_STANDBY", "BACKGROUND"};
                } else if (android.os.Build.VERSION.SDK_INT >= 33) { // TIRAMISU
                    chains = new int[]{FIREWALL_CHAIN_DOZABLE, FIREWALL_CHAIN_STANDBY,
                        FIREWALL_CHAIN_POWERSAVE, FIREWALL_CHAIN_RESTRICTED,
                        FIREWALL_CHAIN_LOW_POWER_STANDBY};
                    chainNames = new String[]{"DOZABLE", "STANDBY", "POWERSAVE",
                        "RESTRICTED", "LOW_POWER_STANDBY"};
                } else {
                    chains = new int[]{FIREWALL_CHAIN_DOZABLE, FIREWALL_CHAIN_STANDBY,
                        FIREWALL_CHAIN_POWERSAVE};
                    chainNames = new String[]{"DOZABLE", "STANDBY", "POWERSAVE"};
                }
                // M7: per-chain try-catch，避免某链成功但另一链失败时状态不一致
                for (int i = 0; i < chains.length; i++) {
                    try {
                        firewallMethod.invoke(nms, chains[i], uid, rule);
                    } catch (InvocationTargetException e) {
                        // R8-M8-4: 解包反射调用目标异常，记录真实 cause
                        Throwable cause = e.getCause();
                        // R10-m-3: 检测 Binder 代理失效，重置缓存并重试一次
                        if (!retried && isDeadBinder(cause)) {
                            retried = true;
                            invalidateNmsCache();
                            Logger.w("NMS Binder 代理已失效（setFirewallUidRule），重置缓存并重试 uid=" + uid);
                            continue retry;
                        }
                        Logger.e("setFirewallUidRule " + chainNames[i] + " 失败 uid=" + uid,
                            cause != null ? cause : e);
                    } catch (Throwable t) {
                        Logger.e("setFirewallUidRule " + chainNames[i] + " 失败 uid=" + uid, t);
                    }
                }
                Logger.d("setFirewallUidRule uid=" + uid + " allow=" + allow);
                return;
            }

            // S-2: setFirewallUidRule 不可用时的替代路径——通过 ConnectivityService.setUidFirewallRule
            // 设置 UID 防火墙规则。Android 12+ 起 BpfNetMaps 迁移后 NMS.setFirewallUidRule
            // 可能失效，ConnectivityService.setUidFirewallRule 内部会调用正确的防火墙实现。
            // 签名：setUidFirewallRule(int uid, boolean allowOnChain) 或类似变体。
            // S-2: 至少在 NMS.setFirewallUidRule 不可用时记录 Logger.e 告警
            if (firewallUidRuleProbed && !firewallUidRuleAvailable) {
                Logger.e("S-2: NMS.setFirewallUidRule 探测为不可用（Android 12+ BpfNetMaps 迁移），" +
                    "尝试 ConnectivityService.setUidFirewallRule 替代路径 uid=" + uid, null);
            }
            Method ctFirewallMethod = getCachedCtSetUidFirewallRule();
            if (ctFirewallMethod != null) {
                Object ctService = getCachedConnectivityService();
                if (ctService != null) {
                    try {
                        // ConnectivityService.setUidFirewallRule(int uid, boolean isAllow)
                        // 或 setUidFirewallRule(int chain, int uid, int rule) 等变体
                        ctFirewallMethod.invoke(ctService, uid, allow);
                        Logger.d("setUidFirewallRule(ConnectivityService) uid=" + uid + " allow=" + allow);
                        return;
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        Logger.e("setUidFirewallRule(ConnectivityService) 调用失败 uid=" + uid,
                            cause != null ? cause : e);
                    } catch (Throwable t) {
                        Logger.e("setUidFirewallRule(ConnectivityService) 调用失败 uid=" + uid, t);
                    }
                }
            }

            // 回退：setUidNetworkRules（仅阻断计量网络，聊胜于无）
            Logger.w("setFirewallUidRule 不可用，回退到 setUidNetworkRules（仅阻断计量网络）uid=" + uid);
            // R10-m-3: 优先使用缓存的 Method 引用
            Method fallback = getCachedFallbackMethod(nms);
            if (fallback != null) {
                // m-3: setAccessible(true) 已在 getCachedFallbackMethod 缓存时一次性设置
                try {
                    fallback.invoke(nms, uid, allow);
                } catch (InvocationTargetException e) {
                    // R8-M8-4: 解包反射调用目标异常，记录真实 cause
                    Throwable cause = e.getCause();
                    // R10-m-3: 检测 Binder 代理失效，重置缓存并重试一次
                    if (!retried && isDeadBinder(cause)) {
                        retried = true;
                        invalidateNmsCache();
                        Logger.w("NMS Binder 代理已失效（setUidNetworkRules 回退），重置缓存并重试 uid=" + uid);
                        continue retry;
                    }
                    Logger.e("setUidNetworkRules(回退) 调用失败 uid=" + uid,
                        cause != null ? cause : e);
                }
                Logger.d("setUidNetworkRules(回退) uid=" + uid + " allow=" + allow);
            } else {
                Logger.w("在 " + nms.getClass().getName() + " 上未找到 setFirewallUidRule / setUidNetworkRules 方法");
            }
            // 注：R8-M8-4 的 InvocationTargetException 解包已在上方 per-chain 与 fallback 的
            // invoke 处就近处理；此处外层不再单独 catch InvocationTargetException（两个 invoke
            // 均已在内部捕获 ITE，外层 catch ITE 会被编译器判定为不可达）。
            return;
        } catch (Throwable t) {
            Logger.e("setUidNetworkPolicy 失败 uid=" + uid + " allow=" + allow, t);
            return;
        }
        } // end retry while
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
            Logger.e("获取 NetworkManagementService 失败", t);
            return null;
        }
    }

    /**
     * R10-m-3: 获取缓存的 NetworkManagementService 引用，缓存 miss 时通过 ServiceManager 解析。
     * Binder 代理可能失效（DeadObjectException），调用方应在检测到失效后调用
     * invalidateNmsCache() 重置缓存并重试。
     */
    private static Object getCachedNms() {
        Object nms = cachedNms;
        if (nms != null) return nms;
        nms = getNetworkManagementService();
        if (nms != null) {
            cachedNms = nms;
        }
        return nms;
    }

    /**
     * R10-m-3: 重置 NMS 缓存，下次 getCachedNms 将重新通过 ServiceManager 解析。
     * R11-m-5: 同时重置 Method 缓存，因为缓存的 Method 绑定在旧 NMS 代理对象上，
     * 代理失效后 Method 不可复用。
     */
    private static void invalidateNmsCache() {
        cachedNms = null;
        cachedFirewallMethod = null;
        cachedFallbackMethod = null;
    }

    /**
     * S-2: 获取缓存的 ConnectivityService 引用（替代防火墙路径）。
     * 通过 ServiceManager 获取 "connectivity" 服务的 IConnectivityManager$Stub 代理。
     */
    private static Object getCachedConnectivityService() {
        Object cs = cachedConnectivityService;
        if (cs != null) return cs;
        try {
            Object binder = getService("connectivity");
            if (binder == null) return null;
            Class<?> stubClass = Class.forName("android.net.IConnectivityManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            cs = asInterface.invoke(null, binder);
            if (cs != null) {
                cachedConnectivityService = cs;
            }
        } catch (Throwable t) {
            Logger.d("S-2: 获取 ConnectivityService 失败: " + t.getMessage());
        }
        return cs;
    }

    /**
     * S-2: 获取缓存的 ConnectivityService.setUidFirewallRule Method。
     * 尝试多种签名变体以适配不同 Android 版本。
     * m-3: 在此处一次性设置 setAccessible(true)。
     */
    private static Method getCachedCtSetUidFirewallRule() {
        Method m = cachedCtSetUidFirewallRule;
        if (m != null) return m;
        Object cs = getCachedConnectivityService();
        if (cs == null) return null;
        // 尝试常见签名变体
        Class<?>[][] paramVariants = {
            {int.class, boolean.class},           // setUidFirewallRule(int uid, boolean isAllow)
            {int.class, int.class, int.class},    // setUidFirewallRule(int chain, int uid, int rule)
        };
        for (Class<?>[] params : paramVariants) {
            m = findMethod(cs.getClass(), "setUidFirewallRule", params);
            if (m != null) {
                m.setAccessible(true); // m-3: 缓存时一次性设置
                cachedCtSetUidFirewallRule = m;
                return m;
            }
        }
        return null;
    }

    /**
     * R10-m-3: 判断异常链中是否包含 Binder 代理失效异常（DeadObjectException 等）。
     * 通过类名匹配避免依赖具体 RemoteException 类型（hidden API）。
     *
     * M-4: 移除 TransactionTooLargeException 检查。该异常表示 Binder 事务数据过大
     * 而非 Binder 代理失效，将其误判为 dead binder 会导致不必要的缓存重置和重试。
     * 仅保留 DeadObjectException 和 DeadSystemException。
     */
    private static boolean isDeadBinder(Throwable t) {
        while (t != null) {
            String name = t.getClass().getName();
            if ("android.os.DeadObjectException".equals(name)
                || "android.os.DeadSystemException".equals(name)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** R10-m-3: 获取缓存的 setFirewallUidRule Method，miss 时反射查找后缓存。
     *  m-3: 在此处一次性设置 setAccessible(true)，避免每次调用处重复执行。 */
    private static Method getCachedFirewallMethod(Object nms) {
        Method m = cachedFirewallMethod;
        if (m != null) return m;
        m = findMethod(nms.getClass(), "setFirewallUidRule",
            int.class, int.class, int.class);
        if (m != null) {
            m.setAccessible(true); // m-3: 缓存时一次性设置
            cachedFirewallMethod = m;
        }
        return m;
    }

    /** R10-m-3: 获取缓存的 setUidNetworkRules Method，miss 时反射查找后缓存。
     *  m-3: 在此处一次性设置 setAccessible(true)，避免每次调用处重复执行。 */
    private static Method getCachedFallbackMethod(Object nms) {
        Method m = cachedFallbackMethod;
        if (m != null) return m;
        m = findMethod(nms.getClass(), "setUidNetworkRules", int.class, boolean.class);
        if (m != null) {
            m.setAccessible(true); // m-3: 缓存时一次性设置
            cachedFallbackMethod = m;
        }
        return m;
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
            Logger.e("getService 失败: " + name, t);
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
                if (info.getState() == AppState.FROZEN) return true;
            }
        } catch (Throwable t) {
            Logger.d("isUidFrozen 失败 uid=" + uid + ": " + t.getMessage());
        }
        return false;
    }

    /**
     * 检查 AppConfigManager.keepConnection 配置。
     * R8-m8-1: 旧代码使用反射调用 AppConfigManager（含 NoSuchMethodException 兼容逻辑），
     * 现改为直接调用 AppConfigManager.getInstance().isKeepConnection(packageName)，
     * 避免不必要的反射开销。keepConnection=true 时冻结应用后不断网。
     */
    private static boolean isKeepConnection(String packageName) {
        if (packageName == null) return false;
        try {
            return AppConfigManager.getInstance().isKeepConnection(packageName);
        } catch (Throwable t) {
            Logger.w("isKeepConnection 检查失败，保守保持连接: " + packageName + " - " + t.getMessage());
            return true; // R11-M-2: fail-closed，避免误断网
        }
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
     * m-6: 过滤 synthetic/bridge 方法，避免 hook 编译器生成的方法（与 TimerHook 保持一致）
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
