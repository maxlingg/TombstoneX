#!/system/bin/sh
# TombstoneX SELinux Helper - post-fs-data.sh
# 
# SELinux 规则通过 sepolicy.rule 文件自动注入（Magisk/KernelSU 原生支持）
# 系统属性通过 system.prop 文件自动设置（Magisk/KernelSU 原生支持）
#
# 此脚本仅负责写入标记文件，告知 App 端 SELinux 策略已注入

MODDIR=${0%/*}

# 写入标记文件，告知 TombstoneX 模块 SELinux 策略已注入
mkdir -p /data/system/TombstoneX
echo "1" > /data/system/TombstoneX/selinux_injected
chmod 644 /data/system/TombstoneX/selinux_injected 2>/dev/null

echo "TombstoneX: SELinux helper initialized"
