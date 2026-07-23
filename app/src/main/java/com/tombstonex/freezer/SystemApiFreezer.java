package com.tombstonex.freezer;

import android.os.Build;
import android.os.Process;
import com.tombstonex.util.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class SystemApiFreezer implements IFreezer {

    private Method setProcessFrozenMethod;

    public SystemApiFreezer() {
        // Android 版本检查: setProcessFrozen 仅 Android 11+ (API 30) 可用
        if (Build.VERSION.SDK_INT < 30) {
            Logger.w("SystemApi 冻结器需要 Android 11+ (API 30)，当前 SDK=" + Build.VERSION.SDK_INT);
            // S1-修复: final 字段必须在所有构造函数路径中赋值，提前 return 前显式赋 null
            setProcessFrozenMethod = null;
            return;
        }
        try {
            setProcessFrozenMethod = Process.class.getDeclaredMethod(
                "setProcessFrozen", int.class, int.class, boolean.class);
            try {
                setProcessFrozenMethod.setAccessible(true);
            } catch (SecurityException e) {
                Logger.w("SystemApi 冻结器: setAccessible 被拒绝: " + e.getMessage());
                setProcessFrozenMethod = null;
            }
        } catch (NoSuchMethodException e) {
            Logger.w("Process.setProcessFrozen 不可用");
            // S1-修复: final 字段必须在所有构造函数路径中赋值，catch 块中显式赋 null
            setProcessFrozenMethod = null;
        }
    }

    @Override
    public boolean freeze(int pid, int uid) {
        if (setProcessFrozenMethod == null) return false;
        try {
            setProcessFrozenMethod.invoke(null, pid, uid, true);
            // S-8: Native 方法可能静默失败，读取 /proc/pid/status 验证冻结状态。
            String state = readProcessState(pid);
            if ("T".equals(state)) {
                Logger.d("SystemApi 已冻结: pid=" + pid + " uid=" + uid);
                return true;
            }
            Logger.e("SystemApi 冻结验证失败 state=" + state + " pid=" + pid);
            return false;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Logger.e("SystemApi 冻结失败 pid=" + pid + " cause=" + (cause != null ? cause.getMessage() : "null"), cause != null ? cause : e);
            return false;
        } catch (Exception e) {
            Logger.e("SystemApi 冻结失败 pid=" + pid, e);
            return false;
        }
    }

    @Override
    public boolean unfreeze(int pid, int uid) {
        if (setProcessFrozenMethod == null) return false;
        try {
            setProcessFrozenMethod.invoke(null, pid, uid, false);
            // S-8: Native 方法可能静默失败，读取 /proc/pid/status 验证解冻状态。
            String state = readProcessState(pid);
            if (state != null && !"T".equals(state)) {
                Logger.d("SystemApi 已解冻: pid=" + pid + " uid=" + uid);
                return true;
            }
            Logger.e("SystemApi 解冻验证失败 state=" + state + " pid=" + pid);
            return false;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Logger.e("SystemApi 解冻失败 pid=" + pid + " cause=" + (cause != null ? cause.getMessage() : "null"), cause != null ? cause : e);
            return false;
        } catch (Exception e) {
            Logger.e("SystemApi 解冻失败 pid=" + pid, e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "SystemAPI";
    }

    /**
     * S-8: 读取 /proc/pid/status 的 State 字段，验证冻结/解冻结果。
     * 冻结后进程状态应为 "T"（stopped），解冻后不应为 "T"。
     *
     * @return State 字段的首字符（如 "T" 表示停止），读取失败返回 null
     */
    private String readProcessState(int pid) {
        File file = new File("/proc/" + pid + "/status");
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("State:")) {
                    String rest = line.substring("State:".length()).trim();
                    if (rest.isEmpty()) return null;
                    return rest.substring(0, 1);
                }
            }
        } catch (Exception e) {
            Logger.d("SystemApi 冻结器: 读取 /proc/" + pid + "/status 失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        return setProcessFrozenMethod != null;
    }
}