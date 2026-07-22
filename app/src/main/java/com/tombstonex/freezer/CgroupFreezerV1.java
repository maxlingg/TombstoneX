package com.tombstonex.freezer;

import com.tombstonex.util.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

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

    /**
     * 返回 freezer.state 路径。
     * 直接返回第一个候选路径，移除 exists() 检查以消除 TOCTOU 竞态。
     * 若 cgroup 路径不存在，由 writeCgroupFile() 的 IOException 处理。
     */
    private String getFreezerStatePath(int pid, int uid) {
        return String.format("%s/uid_%d/pid_%d/freezer.state", CGROUP_V1_PATH, uid, pid);
    }

    private boolean writeCgroupFile(String path, String value) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.US_ASCII)) {
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