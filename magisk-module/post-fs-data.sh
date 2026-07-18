#!/system/bin/sh
MODDIR=${0%/*}
mkdir -p /data/system/TombstoneX
echo "1" > /data/system/TombstoneX/selinux_injected
