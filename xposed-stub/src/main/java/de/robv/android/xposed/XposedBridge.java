package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * XposedBridge stub for compilation only.
 * The real implementation is provided by the Xposed framework at runtime.
 */
public class XposedBridge {

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return null;
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }
}
