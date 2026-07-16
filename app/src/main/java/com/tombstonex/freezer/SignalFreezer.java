package com.tombstonex.freezer;

import android.os.Process;
import com.tombstonex.util.Logger;

public class SignalFreezer implements IFreezer {

    private final int stopSignal;
    private static final int CONT_SIGNAL = 18;

    public SignalFreezer(boolean useSigTstp) {
        this.stopSignal = useSigTstp ? 20 : 19;
    }

    @Override
    public boolean freeze(int pid, int uid) {
        try {
            Process.sendSignal(pid, stopSignal);
            Logger.d("Signal freeze: pid=" + pid + " signal=" + stopSignal);
            return true;
        } catch (Exception e) {
            Logger.e("Signal freeze failed for pid=" + pid, e);
            return false;
        }
    }

    @Override
    public boolean unfreeze(int pid, int uid) {
        try {
            Process.sendSignal(pid, CONT_SIGNAL);
            Logger.d("Signal unfreeze: pid=" + pid);
            return true;
        } catch (Exception e) {
            Logger.e("Signal unfreeze failed for pid=" + pid, e);
            return false;
        }
    }

    @Override
    public String getName() {
        return stopSignal == 19 ? "SIGSTOP" : "SIGTSTP";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}