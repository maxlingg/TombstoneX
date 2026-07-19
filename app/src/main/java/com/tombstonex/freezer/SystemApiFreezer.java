package com.tombstonex.freezer;

import android.os.Process;
import com.tombstonex.util.Logger;
import java.lang.reflect.Method;

public class SystemApiFreezer implements IFreezer {

    private Method setProcessFrozenMethod;

    public SystemApiFreezer() {
        try {
            setProcessFrozenMethod = Process.class.getDeclaredMethod(
                "setProcessFrozen", int.class, int.class, boolean.class);
            setProcessFrozenMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            Logger.w("Process.setProcessFrozen 不可用");
        }
    }

    @Override
    public boolean freeze(int pid, int uid) {
        if (setProcessFrozenMethod == null) return false;
        try {
            setProcessFrozenMethod.invoke(null, pid, uid, true);
            Logger.d("SystemApi 已冻结: pid=" + pid + " uid=" + uid);
            return true;
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
            Logger.d("SystemApi 已解冻: pid=" + pid + " uid=" + uid);
            return true;
        } catch (Exception e) {
            Logger.e("SystemApi 解冻失败 pid=" + pid, e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "SystemAPI";
    }

    @Override
    public boolean isAvailable() {
        return setProcessFrozenMethod != null;
    }
}