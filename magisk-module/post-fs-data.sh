#!/system/bin/sh
# TombstoneX SELinux Helper - post-fs-data.sh
# 在 post-fs-data 阶段注入 SELinux 策略，允许 system_server 注册自定义 Binder 服务
#
# 此脚本运行在 early-init 之后的 post-fs-data 阶段，
# 此时 SELinux 策略已加载，magiskpolicy 可以注入规则。
# system_server 尚未启动（它在 zygote 启动后才启动），
# 因此注入的规则会在 system_server 启动前生效。

MODDIR=${0%/*}

# 等待 SELinux 策略就绪
while [ ! -f /sys/fs/selinux/policy ]; do
    sleep 1
done

# ======== 注入 SELinux 策略 ========
# 核心问题：system_server 运行在 u:r:system_server:s0 域，
# 默认 SELinux 策略不允许它通过 ServiceManager.addService 注册自定义服务。
# 
# 需要允许的规则：
# 1. system_server -> servicemanager (binder call) — 注册服务
# 2. system_server -> servicemanager (add) — addService 操作
# 3. 任意应用 -> tombstonex service (binder call) — App 调用服务

# 检查 magiskpolicy 是否可用
if [ -x /data/adb/magisk/magiskpolicy ]; then
    POLICY_TOOL=/data/adb/magisk/magiskpolicy
elif [ -x /system/bin/magiskpolicy ]; then
    POLICY_TOOL=/system/bin/magiskpolicy
elif command -v magiskpolicy >/dev/null 2>&1; then
    POLICY_TOOL=magiskpolicy
else
    # KernelSU/APatch 兼容
    if [ -x /data/adb/ksu/bin/magiskpolicy ]; then
        POLICY_TOOL=/data/adb/ksu/bin/magiskpolicy
    elif [ -x /data/adb/ap/bin/magiskpolicy ]; then
        POLICY_TOOL=/data/adb/ap/bin/magiskpolicy
    else
        echo "TombstoneX: magiskpolicy not found, SELinux injection skipped"
        exit 0
    fi
fi

echo "TombstoneX: injecting SELinux policies with $POLICY_TOOL"

# 注入 system_server 注册自定义服务的权限
# servicemanager 域在 Android 12+ 通常是 servicemanager 或 servicemanager_exec
$POLICY_TOOL --live \
    "allow system_server servicemanager binder call" \
    "allow system_server servicemanager binder transfer" \
    "allow system_server servicemanager_service service_manager add" \
    "allow system_server servicemanager_service service_manager find" \
    2>/dev/null

# 兼容不同 Android 版本的 servicemanager 类型名
# Android 12+ 使用 "servicemanager"，旧版可能使用 "servicemanager_exec"
$POLICY_TOOL --live \
    "allow system_server servicemanager_exec binder call" \
    "allow system_server servicemanager_exec binder transfer" \
    2>/dev/null

# 允许 system_server 注册任意服务（最宽松策略，确保兼容性）
# 这是必要的，因为自定义服务名 "tombstonex" 不在默认 service_contexts 中
$POLICY_TOOL --live \
    "allow system_server service_manager_type service_manager add" \
    "allow system_server service_manager_type service_manager find" \
    2>/dev/null

# 允许 untrusted_app（App 端）调用 tombstonex 服务
# App 进程需要通过 getService 获取服务代理，然后 transact 调用
$POLICY_TOOL --live \
    "allow untrusted_app servicemanager binder call" \
    "allow untrusted_app servicemanager_service service_manager find" \
    "allow untrusted_app system_server binder call" \
    "allow untrusted_app system_server binder transfer" \
    2>/dev/null

# 兼容 priv_app（特权应用）
$POLICY_TOOL --live \
    "allow priv_app servicemanager binder call" \
    "allow priv_app servicemanager_service service_manager find" \
    "allow priv_app system_server binder call" \
    "allow priv_app system_server binder transfer" \
    2>/dev/null

# ======== 禁用系统自带 Cached Apps Freezer ========
# 通过设置系统属性禁用系统的 cgroup freezer，
# 让 TombstoneX 完全接管冻结逻辑，避免冲突。
# 此属性在 system_server 启动前设置，确保生效。
setprop device_config_runtime_native_boot_native_debuggable_cached_apps_freezer disabled 2>/dev/null
setprop persist.sys.cached_apps_freezer disabled 2>/dev/null

# 写入标记文件，告知 TombstoneX 模块 SELinux 策略已注入
mkdir -p /data/system/TombstoneX
echo "1" > /data/system/TombstoneX/selinux_injected
chmod 644 /data/system/TombstoneX/selinux_injected 2>/dev/null

echo "TombstoneX: SELinux policies injected successfully"
