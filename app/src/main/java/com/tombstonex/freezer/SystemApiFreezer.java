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
            Logger.w("Process.setProcessFrozen not available");
        }
    }

    @Override
    public boolean freeze(int pid, int uid) {
        if (setProcessFrozenMethod == null) return false;
        try {
            setProcessFrozenMethod.invoke(null, pid, uid, true);
            Logger.d("SystemApi frozen: pid=" + pid + " uid=" + uid);
            return true;
        } catch (Exception e) {
            Logger.e("SystemApi freeze failed for pid=" + pid, e);
            return false;
        }
    }

    @Override
    public boolean unfreeze(int pid, int uid) {
        if (setProcessFrozenMethod == null) return false;
        try {
            setProcessFrozenMethod.invoke(null, pid, uid, false);
            Logger.d("SystemApi unfrozen: pid=" + pid + " uid=" + uid);
            return true;
        } catch (Exception e) {
            Logger.e("SystemApi unfreeze failed for pid=" + pid, e);
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