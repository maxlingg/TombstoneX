package com.tombstonex.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

public class FileUtils {

    /**
     * CONFIG_DIR: 配置文件目录，与 Logger.LOG_DIR 保持一致路径结构。
     * 两者都位于 /data/system/TombstoneX/，避免配置文件和日志文件分散在不同目录。
     */
    private static final String CONFIG_DIR = "/data/system/TombstoneX";

    /** 附加日志文件大小警告阈值（1MB） */
    private static final long APPEND_FILE_SIZE_WARN_THRESHOLD = 1024 * 1024L;

    /**
     * 检测并跳过 UTF-8 BOM（字节顺序标记）。
     * 若输入流前 3 个字节为 0xEF 0xBB 0xBF，则已跳过并返回 true；
     * 否则调用 reset() 回到流开头并返回 false。
     *
     * 提取为共享方法，供 readLines() 和 appendLine() 共同使用，
     * 避免 BOM 检测逻辑重复。
     */
    private static boolean skipUtf8Bom(BufferedInputStream bis) throws IOException {
        bis.mark(3);
        byte[] bom = new byte[3];
        int read = bis.read(bom);
        if (read >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
            // 检测到 UTF-8 BOM，已跳过，不需要 reset
            return true;
        }
        // 没有 BOM，reset 回开头
        bis.reset();
        return false;
    }

    /**
     * 读取配置文件所有行。
     *
     * M-38: IOException 时返回 null 而非部分数据，让调用方能够区分
     * "文件为空"（返回空 Set）和 "读取失败"（返回 null）。
     *
     * @return 非空行集合；读取失败时返回 null
     */
    public static Set<String> readLines(String filename) {
        if (filename == null) throw new IllegalArgumentException("filename cannot be null");
        Set<String> lines = new HashSet<>();
        File file = new File(CONFIG_DIR, filename);
        if (!file.exists()) return lines;
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            // M1-修复: skipUtf8Bom 必须在 BufferedReader 创建之前调用，
            // 避免 BufferedReader 内部缓冲与 BOM 检测发生冲突。
            skipUtf8Bom(bis);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(bis, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
            } // inner try-with-resources (BufferedReader)
        } catch (IOException e) {
            Logger.e("读取文件失败: " + filename, e);
            // M-38: 返回 null 让调用方区分"文件为空"和"读取失败"
            return null;
        }
        return lines;
    }

    /**
     * 原子写入：先写到临时文件 file.tmp，再 renameTo(file)。
     *
     * M-39: 检查 dir.mkdirs() 返回值，失败时记录日志并返回 false。
     */
    public static synchronized boolean writeLines(String filename, Set<String> lines) {
        if (filename == null) throw new IllegalArgumentException("filename cannot be null");
        if (lines == null) throw new IllegalArgumentException("lines cannot be null");
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                // M-39: 目录创建失败时记录日志并返回 false，避免后续写入失败
                Logger.e("无法创建配置目录: " + dir.getAbsolutePath());
                return false;
            }
        }
        File file = new File(dir, filename);
        File tmpFile = new File(dir, filename + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmpFile), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            Logger.e("写入文件失败: " + filename, e);
            return false;
        }
        // 原子替换
        try {
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("重命名临时文件失败: " + filename, e);
            // P3-6: 清理残留的 tmp 文件
            tmpFile.delete();
            return false;
        }
        return true;
    }

    /**
     * 原子追加写入：先写到临时文件 file.tmp（含原内容+新行），再 renameTo(file)。
     *
     * M-40: 当前设计适用于小配置文件（< 1MB），通过先读取整个文件再写入实现原子追加。
     * 如果文件超过 1MB，记录警告日志。对于大文件追加场景，应考虑使用
     * FileChannel + 文件锁 或数据库方案。
     */
    public static synchronized boolean appendLine(String filename, String line) {
        if (filename == null) throw new IllegalArgumentException("filename cannot be null");
        if (line == null) throw new IllegalArgumentException("line cannot be null");
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.exists()) {
                Logger.w("无法创建配置目录: " + dir.getAbsolutePath());
                return false;
            }
        }
        File file = new File(dir, filename);
        File tmpFile = new File(dir, filename + ".tmp");

        // M-40: 文件大小检查
        if (file.exists() && file.length() > APPEND_FILE_SIZE_WARN_THRESHOLD) {
            Logger.w("配置文件过大（" + file.length() + " bytes），appendLine 可能会消耗大量内存: " + filename);
        }

        // 先复制已有内容到临时文件
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmpFile), StandardCharsets.UTF_8))) {
            // 如果原文件存在，先读取并写入原内容
            if (file.exists()) {
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                    // M1-修复: skipUtf8Bom 必须在 BufferedReader 创建之前调用
                    skipUtf8Bom(bis);
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(bis, StandardCharsets.UTF_8))) {
                    String existingLine;
                    while ((existingLine = reader.readLine()) != null) {
                        writer.write(existingLine);
                        writer.newLine();
                    }
                    } // inner try-with-resources (BufferedReader)
                }
            }
            // 写入新行
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            Logger.e("追加写入文件失败: " + filename, e);
            return false;
        }
        // 原子替换
        try {
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("重命名临时文件失败: " + filename, e);
            // P3-6: 清理残留的 tmp 文件
            tmpFile.delete();
            return false;
        }
        return true;
    }

    public static boolean exists(String filename) {
        if (filename == null) throw new IllegalArgumentException("filename cannot be null");
        return new File(CONFIG_DIR, filename).exists();
    }
}