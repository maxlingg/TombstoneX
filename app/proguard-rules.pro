# LSPosed 入口
-keep class com.tombstonex.hook.** { *; }

# Binder 服务（运行在 system_server，不可混淆）
-keep class com.tombstonex.service.TombstoneXService { *; }
-keep class com.tombstonex.service.ServiceClient { *; }

# 冻结器实现（通过反射实例化）
-keep class com.tombstonex.freezer.** { *; }

# 管理器单例
-keep class com.tombstonex.manager.** { *; }

# 数据模型（IPC 序列化）
-keep class com.tombstonex.model.** { *; }

# Xposed API
-keep class de.robv.android.xposed.** { *; }

# Provider（IPC ContentProvider，不可混淆）
-keep class com.tombstonex.provider.** { *; }

# 工具类（反射调用）
-keep class com.tombstonex.util.** { *; }

# BuildConfig（运行时反射读取）
-keep class com.tombstonex.BuildConfig { *; }

# ServiceClient 内部类（IPC 回调/Stub）
-keep class com.tombstonex.service.ServiceClient$* { *; }
