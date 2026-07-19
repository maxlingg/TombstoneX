package com.tombstonex.freezer;

import com.tombstonex.util.Logger;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class CgroupFreezerV1 implements IFreezer {

    private static final String CGROUP_V1_PATH = "/sys/fs/cgroup/freezer";

    @Override
    public boolean freeze(int pid, int uid) {
        String stateFile = getFreezerStatePath(pid, uid);
        if (stateFile == null) return false;
        return writeCgroupFile(stateFile, "FROZEN");
    }

    @Override
    public boolean unfreeze(int pid, int uid) {
        String stateFile = getFreezerStatePath(pid, uid);
        if (stateFile == null) return false;
        return writeCgroupFile(stateFile, "THAWED");
    }

    private String getFreezerStatePath(int pid, int uid) {
        String[] paths = {
            String.format("%s/uid_%d/pid_%d/freezer.state", CGROUP_V1_PATH, uid, pid),
            String.format("/dev/freezer/uid_%d/pid_%d/freezer.state", uid, pid),
        };
        for (String path : paths) {
            if (new File(path).exists()) return path;
        }
        return null;
    }

    private boolean writeCgroupFile(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
            writer.flush();
            Logger.d("CgroupV1 写入 " + value + " 到 " + path);
            return true;
        } catch (IOException e) {
            Logger.e("CgroupV1 写入失败: " + path, e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "CgroupV1";
    }

    @Override
    public boolean isAvailable() {
        File cgroupDir = new File(CGROUP_V1_PATH);
        return cgroupDir.exists() && cgroupDir.isDirectory();
    }
}