package com.tombstonex.provider;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import com.tombstonex.util.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用信息提供器
 * 通过 PackageManager 获取设备上所有已安装的应用
 */
public class AppProvider {

    private static volatile AppProvider instance;
    private static final Object lock = new Object();
    private final PackageManager pm;

    /** 模块自身包名，需从列表中过滤 */
    private static final String SELF_PACKAGE = "com.tombstonex";

    private AppProvider(Context context) {
        this.pm = context.getApplicationContext().getPackageManager();
    }

    public static AppProvider getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new AppProvider(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * 获取所有已安装的应用信息
     * @param includeSystem 是否包含系统应用
     */
    public List<AppData> getAllApps(boolean includeSystem) {
        List<AppData> result = new ArrayList<>();
        try {
            // 不使用 GET_META_DATA 标志，减少不必要的开销
            List<PackageInfo> packages = pm.getInstalledPackages(0);

            for (PackageInfo pkg : packages) {
                try {
                    // 过滤模块自身包名
                    if (SELF_PACKAGE.equals(pkg.packageName)) continue;

                    ApplicationInfo appInfo = pkg.applicationInfo;
                    if (appInfo == null) continue;

                    boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                    if (!includeSystem && isSystem) continue;

                    String label = pm.getApplicationLabel(appInfo).toString();
                    Drawable icon = pm.getApplicationIcon(appInfo);
                    String packageName = pkg.packageName;

                    result.add(new AppData(
                        label,
                        packageName,
                        isSystem,
                        (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                        icon
                    ));
                } catch (Exception e) {
                    // 逐应用捕获异常，不影响其他应用的加载
                    Logger.w("Failed to load app info: " + pkg.packageName + " - " + e.getMessage());
                }
            }

            // 按名称排序
            Collections.sort(result, Comparator.comparing(a -> a.label.toLowerCase(java.util.Locale.ROOT)));

            Logger.i("AppProvider: loaded " + result.size() + " apps"
                + (includeSystem ? " (including system)" : " (user only)"));
        } catch (Exception e) {
            Logger.e("AppProvider: failed to load apps", e);
        }
        return result;
    }

    /**
     * 获取所有用户安装的应用（非系统应用）
     */
    public List<AppData> getUserApps() {
        return getAllApps(false);
    }

    /**
     * 获取所有应用（含系统应用）
     */
    public List<AppData> getAllAppsList() {
        return getAllApps(true);
    }

    /**
     * 应用数据类
     */
    public static class AppData {
        public final String label;       // 应用名称
        public final String packageName; // 包名
        public final boolean isSystem;   // 是否系统应用
        public final boolean isDebuggable;
        public final Drawable icon;      // 应用图标

        public AppData(String label, String packageName, boolean isSystem,
                       boolean isDebuggable, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.isSystem = isSystem;
            this.isDebuggable = isDebuggable;
            this.icon = icon;
        }

        @Override
        public String toString() {
            return label + " (" + packageName + ")";
        }
    }
}
