#!/system/bin/sh
# TombstoneX SELinux Helper - service.sh
# 在 late_start service 阶段执行，确保 SELinux 规则在重启后仍然有效。
# post-fs-data 注入的规则是运行时（live）的，重启后丢失，
# 但 system_server 在每次启动时都会尝试注册，而 LSPosed 在 post-fs-data 后加载，
# 所以 post-fs-data 注入的规则足够覆盖 system_server 启动时机。

# 此脚本主要用于：
# 1. 再次确认 SELinux 规则已注入（双保险）
# 2. 清理旧的 IPC 文件（如果存在）

MODDIR=${0%/*}

# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# 给 system_server 一点时间启动并加载 LSPosed
sleep 5

# 重新注入 SELinux 规则（双保险，处理某些设备时序问题）
if [ -x /data/adb/magisk/magiskpolicy ]; then
    POLICY_TOOL=/data/adb/magisk/magiskpolicy
elif [ -x /system/bin/magiskpolicy ]; then
    POLICY_TOOL=/system/bin/magiskpolicy
elif [ -x /data/adb/ksu/bin/magiskpolicy ]; then
    POLICY_TOOL=/data/adb/ksu/bin/magiskpolicy
elif [ -x /data/adb/ap/bin/magiskpolicy ]; then
    POLICY_TOOL=/data/adb/ap/bin/magiskpolicy
else
    exit 0
fi

# 仅当 post-fs-data 没有成功注入时才重新注入
if [ ! -f /data/system/TombstoneX/selinux_injected ]; then
    $POLICY_TOOL --live \
        "allow system_server servicemanager binder call" \
        "allow system_server servicemanager binder transfer" \
        "allow system_server service_manager_type service_manager add" \
        "allow system_server service_manager_type service_manager find" \
        "allow untrusted_app servicemanager binder call" \
        "allow untrusted_app system_server binder call" \
        "allow untrusted_app system_server binder transfer" \
        "allow priv_app servicemanager binder call" \
        "allow priv_app system_server binder call" \
        "allow priv_app system_server binder transfer" \
        2>/dev/null

    mkdir -p /data/system/TombstoneX
    echo "1" > /data/system/TombstoneX/selinux_injected
    echo "TombstoneX: SELinux policies re-injected in service.sh"
fi

echo "TombstoneX: SELinux helper service started"
