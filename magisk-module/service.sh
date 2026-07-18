#!/system/bin/sh
# TombstoneX SELinux Helper - service.sh
# 在 late_start service 阶段执行，确保标记文件存在

MODDIR=${0%/*}

# 确保 SELinux 标记文件存在（post-fs-data 可能因时序问题未执行）
if [ ! -f /data/system/TombstoneX/selinux_injected ]; then
    mkdir -p /data/system/TombstoneX
    echo "1" > /data/system/TombstoneX/selinux_injected
    chmod 644 /data/system/TombstoneX/selinux_injected 2>/dev/null
    echo "TombstoneX: SELinux mark file created in service.sh"
fi

echo "TombstoneX: SELinux helper service started"
