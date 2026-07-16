package com.tombstonex.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

public class FileUtils {

    private static final String CONFIG_DIR = "/data/system/TombstoneX";

    public static Set<String> readLines(String filename) {
        Set<String> lines = new HashSet<>();
        File file = new File(CONFIG_DIR, filename);
        if (!file.exists()) return lines;
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
             BufferedReader reader = new BufferedReader(
                new InputStreamReader(bis, StandardCharsets.UTF_8))) {

            // BOM 检测：读取前 3 个字节判断是否有 UTF-8 BOM
            bis.mark(3);
            byte[] bom = new byte[3];
            int read = bis.read(bom);
            if (read >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
                // 检测到 UTF-8 BOM，已跳过，不需要 reset
            } else {
                // 没有 BOM，reset 回开头
                bis.reset();
            }

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            Logger.e("Failed to read file: " + filename, e);
        }
        return lines;
    }

    /**
     * 原子写入：先写到临时文件 file.tmp，再 renameTo(file)
     */
    public static void writeLines(String filename, Set<String> lines) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        File tmpFile = new File(dir, filename + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmpFile), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            Logger.e("Failed to write file: " + filename, e);
            return;
        }
        // 原子替换
        try {
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("Failed to rename tmp file to: " + filename);
        }
    }

    /**
     * 原子追加写入：先写到临时文件 file.tmp（含原内容+新行），再 renameTo(file)
     */
    public static synchronized void appendLine(String filename, String line) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        File tmpFile = new File(dir, filename + ".tmp");

        // 先复制已有内容到临时文件
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmpFile), StandardCharsets.UTF_8))) {
            // 如果原文件存在，先读取并写入原内容
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String existingLine;
                    while ((existingLine = reader.readLine()) != null) {
                        writer.write(existingLine);
                        writer.newLine();
                    }
                }
            }
            // 写入新行
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            Logger.e("Failed to append to file: " + filename, e);
            return;
        }
        // 原子替换
        try {
            Files.move(tmpFile.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Logger.e("Failed to rename tmp file to: " + filename);
        }
    }

    public static boolean exists(String filename) {
        return new File(CONFIG_DIR, filename).exists();
    }
}
