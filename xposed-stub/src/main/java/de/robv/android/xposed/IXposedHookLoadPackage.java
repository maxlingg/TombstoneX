package de.robv.android.xposed;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(callbacks.XC_LoadPackage.LoadPackageParam lpparam);
}
