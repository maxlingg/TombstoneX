package com.tombstonex.freezer;

public interface IFreezer {
    boolean freeze(int pid, int uid);
    boolean unfreeze(int pid, int uid);
    String getName();
    boolean isAvailable();
}