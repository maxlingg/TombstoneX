# LSPosed 模块入口不能混淆
-keep class com.tombstonex.hook.MainHook { *; }

# 反射使用的类不能混淆
-keep class com.tombstonex.freezer.** { *; }
-keep class com.tombstonex.manager.** { *; }
-keep class com.tombstonex.model.** { *; }

# Xposed API
-keep class de.robv.android.xposed.** { *; }