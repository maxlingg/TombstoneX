package com.tombstonex.freezer;

import com.tombstonex.util.Logger;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class CgroupFreezerV2 implements IFreezer {

    private static final String CGROUP_V2_PATH = "/sys/fs/cgroup";

    @Override
    public boolean freeze(int pid, int uid) {
        String freezeFile = getFreezeFilePath(pid, uid);
        if (freezeFile == null) return false;
        return writeCgroupFile(freezeFile, "1");
    }

    @Override
    public boolean unfreeze(int pid, int uid) {
        String freezeFile = getFreezeFilePath(pid, uid);
        if (freezeFile == null) return false;
        return writeCgroupFile(freezeFile, "0");
    }

    private String getFreezeFilePath(int pid, int uid) {
        String[] paths = {
            String.format("%s/uid_%d/pid_%d/cgroup.freeze", CGROUP_V2_PATH, uid, pid),
            String.format("%s/unified/uid_%d/pid_%d/cgroup.freeze", CGROUP_V2_PATH, uid, pid),
            String.format("/dev/cg2_bpf/uid_%d/pid_%d/cgroup.freeze", uid, pid),
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
            Logger.d("CgroupV2 写入 " + value + " 到 " + path);
            return true;
        } catch (IOException e) {
            Logger.e("CgroupV2 写入失败: " + path, e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "CgroupV2";
    }

    @Override
    public boolean isAvailable() {
        File cgroupDir = new File(CGROUP_V2_PATH);
        return cgroupDir.exists() && cgroupDir.isDirectory();
    }
}