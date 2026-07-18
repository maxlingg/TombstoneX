#!/system/bin/sh
# TombstoneX SELinux Helper - uninstall.sh
# 模块卸载时清理标记文件

rm -f /data/system/TombstoneX/selinux_injected 2>/dev/null
echo "TombstoneX: SELinux helper uninstalled, mark file removed"
