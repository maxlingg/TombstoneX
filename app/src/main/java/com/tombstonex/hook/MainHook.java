package com.tombstonex.hook;

import android.os.Build;
import com.tombstonex.util.Logger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String PACKAGE_ANDROID = "android";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";

    @Override
    public void initZygote(StartupParam startupParam) {
        Logger.i("TombstoneX Zygote init, SDK=" + Build.VERSION.SDK_INT);
        Logger.init(false);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        String pkg = lpparam.packageName;
        Logger.i("handleLoadPackage: " + pkg + " process=" + lpparam.processName);

        if (PACKAGE_ANDROID.equals(pkg)) {
            Logger.i("Hooking System Framework (android)");
            hookSystemFramework(lpparam);
        } else if (PACKAGE_SYSTEMUI.equals(pkg)) {
            Logger.i("Hooking SystemUI");
        }
    }

    private void hookSystemFramework(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader classLoader = lpparam.classLoader;

        ActivitySwitchHook.init(classLoader);
        BroadcastHook.init(classLoader);
        ANRHook.init(classLoader);
        WakeLockHook.init(classLoader);

        Logger.i("All system framework hooks initialized");
    }
}