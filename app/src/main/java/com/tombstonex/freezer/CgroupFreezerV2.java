package com.tombstonex.freezer;

import com.tombstonex.util.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class CgroupFreezerV2 implements IFreezer {

    private static final String CGROUP_V2_PATH = "/sys/fs/cgroup";

    // 缓存 isAvailable() 结果，避免每次调用都读文件
    private volatile Boolean cachedAvailable = null;

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

    /**
     * 返回 cgroup.freeze 路径。
     * 直接返回第一个候选路径，移除 exists() 检查以消除 TOCTOU 竞态。
     * 若 cgroup 路径不存在，由 writeCgroupFile() 的 IOException 处理。
     */
    private String getFreezeFilePath(int pid, int uid) {
        return String.format("%s/uid_%d/pid_%d/cgroup.freeze", CGROUP_V2_PATH, uid, pid);
    }

    private boolean writeCgroupFile(String path, String value) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.US_ASCII)) {
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
        // 缓存 isAvailable() 结果，避免每次调用都读文件。
        Boolean cached = cachedAvailable;
        if (cached != null) return cached;
        // S7: 仅检查 /sys/fs/cgroup 目录存在会误判 cgroup v1 系统（v1 也有此目录）。
        // 改为检查 cgroup v2 的特征文件 cgroup.controllers 是否存在且内容包含 freezer。
        // 轻微-9: 不能用 content.contains("freezer")，否则会误匹配 "memoryfreezer"
        // 之类的控制器名；改为按空白拆分后精确匹配 "freezer"。
        File controllers = new File(CGROUP_V2_PATH, "cgroup.controllers");
        if (!controllers.exists()) {
            cachedAvailable = false;
            return false;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(controllers.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] controllerArray = content.trim().split("\\s+");
            for (String c : controllerArray) {
                if (c.equals("freezer")) {
                    cachedAvailable = true;
                    return true;
                }
            }
            cachedAvailable = false;
            return false;
        } catch (IOException e) {
            cachedAvailable = false;
            return false;
        }
    }
}