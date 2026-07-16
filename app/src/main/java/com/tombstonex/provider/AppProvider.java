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

    private static AppProvider instance;
    private final PackageManager pm;

    private AppProvider(Context context) {
        this.pm = context.getApplicationContext().getPackageManager();
    }

    public static synchronized AppProvider getInstance(Context context) {
        if (instance == null) {
            instance = new AppProvider(context.getApplicationContext());
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
            List<PackageInfo> packages = pm.getInstalledPackages(
                PackageManager.GET_META_DATA);

            for (PackageInfo pkg : packages) {
                ApplicationInfo appInfo = pkg.applicationInfo;
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
            }

            // 按名称排序
            Collections.sort(result, Comparator.comparing(a -> a.label.toLowerCase()));

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
