# TombstoneX SELinux Helper Magisk Module

## 简介

本 Magisk 模块为 TombstoneX 墓碑冻结模块提供 SELinux 策略支持。

### 解决的问题

Android 12+ 的 SELinux 策略不允许 `system_server` 通过 `ServiceManager.addService()` 注册自定义 Binder 服务。这导致 TombstoneX 只能降级使用 FileIPC（文件系统 IPC），每次调用需 spawn `su` 进程，开销大、速度慢。

### 解决方案

本模块在开机时通过 `magiskpolicy --live` 注入 SELinux 规则，允许：
- `system_server` 注册自定义 Binder 服务
- 应用进程通过 Binder 调用 TombstoneX 服务

注入后，TombstoneX 将使用高效的 Binder IPC（~1ms）替代 FileIPC（~1s），性能提升 1000 倍。

### 附加功能

- 禁用系统自带 Cached Apps Freezer，避免与 TombstoneX 冲突

## 安装要求

- Android 12+
- Magisk / KernelSU / APatch
- LSPosed（已启用 TombstoneX 模块）

## 安装方法

1. 下载 `tombstonex-selinux-helper.zip`
2. 在 Magisk Manager 中选择"模块" → "从存储安装" → 选择 zip 文件
3. 重启设备
4. 打开 TombstoneX App，状态应显示"已激活（Binder）"

## 验证

安装并重启后，打开 TombstoneX App：
- 如果首页顶部显示"Binder 服务已连接"，说明 SELinux 策略注入成功
- 如果仍显示"FileIPC 降级模式"，请检查 Magisk 日志中是否有 `TombstoneX: SELinux policies injected successfully`

## 文件说明

```
magisk-module/
├── module.prop          # 模块元数据
├── post-fs-data.sh      # 开机时注入 SELinux 策略（核心脚本）
├── service.sh           # late_start 阶段双保险
└── uninstall.sh         # 卸载时清理
```

## 技术细节

### SELinux 规则

注入的规则允许以下操作：

```
# system_server 注册服务
allow system_server servicemanager binder call
allow system_server servicemanager binder transfer
allow system_server service_manager_type service_manager add
allow system_server service_manager_type service_manager find

# App 调用服务
allow untrusted_app servicemanager binder call
allow untrusted_app system_server binder call
allow untrusted_app system_server binder transfer
```

### 时机

- `post-fs-data.sh` 在 SELinux 策略加载后、system_server 启动前执行
- 此时注入的规则会在 system_server 启动时生效
- LSPosed 在 post-fs-data 后加载，其 hook 的 `addService` 调用已被规则允许

### 安全性

注入的规则仅允许 `system_server` 注册服务和 App 调用服务，不涉及其他敏感操作，安全性可控。
