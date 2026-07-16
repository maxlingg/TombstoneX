package com.tombstonex.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class FileUtils {

    private static final String CONFIG_DIR = "/data/system/TombstoneX";

    public static Set<String> readLines(String filename) {
        Set<String> lines = new HashSet<>();
        File file = new File(CONFIG_DIR, filename);
        if (!file.exists()) return lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
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

    public static void writeLines(String filename, Set<String> lines) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            Logger.e("Failed to write file: " + filename, e);
        }
    }

    public static void appendLine(String filename, String line) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            Logger.e("Failed to append to file: " + filename, e);
        }
    }

    public static boolean exists(String filename) {
        return new File(CONFIG_DIR, filename).exists();
    }
}