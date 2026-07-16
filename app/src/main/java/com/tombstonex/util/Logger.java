package com.tombstonex.util;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class Logger {
    private static final String TAG = "TombstoneX";
    private static final String LOG_DIR = "/data/system/TombstoneX";
    private static final long MAX_LOG_SIZE = 2 * 1024 * 1024; // 2MB
    private static volatile boolean debugEnabled = false;
    private static FileWriter logWriter;
    private static final Object writerLock = new Object();

    /** ThreadLocal 缓存 SimpleDateFormat，避免频繁创建 */
    private static final ThreadLocal<SimpleDateFormat> dateFormatHolder =
        ThreadLocal.withInitial(() ->
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()));

    public static void init(boolean debug) {
        debugEnabled = debug;
        synchronized (writerLock) {
            // 先关闭旧 writer
            if (logWriter != null) {
                try {
                    logWriter.close();
                } catch (IOException ignored) {}
                logWriter = null;
            }
            try {
                File dir = new File(LOG_DIR);
                if (!dir.exists()) dir.mkdirs();
                File logFile = new File(dir, "current.log");
                // 日志轮转：如果超过 2MB，备份为 .old
                if (logFile.exists() && logFile.length() >= MAX_LOG_SIZE) {
                    File oldFile = new File(dir, "current.log.old");
                    if (oldFile.exists()) oldFile.delete();
                    logFile.renameTo(oldFile);
                }
                logWriter = new FileWriter(logFile, true);
            } catch (IOException e) {
                Log.e(TAG, "Failed to init log file", e);
            }
        }
    }

    private static String format(String level, String msg) {
        SimpleDateFormat sdf = dateFormatHolder.get();
        return String.format("[%s][%s] %s\n", sdf.format(new Date()), level, msg);
    }

    public static void d(String msg) {
        if (debugEnabled) {
            Log.d(TAG, msg);
            writeFile("D", msg);
        }
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        writeFile("I", msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        writeFile("W", msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        writeFile("E", msg);
    }

    public static void e(String msg, Throwable t) {
        String detail = msg;
        if (t != null) {
            String tMsg = t.getMessage();
            detail = msg + " | " + (tMsg != null ? tMsg : t.toString());
        }
        Log.e(TAG, detail);
        writeFile("E", detail);
    }

    private static void writeFile(String level, String msg) {
        synchronized (writerLock) {
            try {
                if (logWriter != null) {
                    logWriter.write(format(level, msg));
                    logWriter.flush();
                    // 日志轮转检查
                    File logFile = new File(LOG_DIR, "current.log");
                    if (logFile.exists() && logFile.length() >= MAX_LOG_SIZE) {
                        try {
                            logWriter.close();
                        } catch (IOException ignored) {}
                        File oldFile = new File(LOG_DIR, "current.log.old");
                        if (oldFile.exists()) oldFile.delete();
                        logFile.renameTo(oldFile);
                        // 轮转后重建 writer 失败时的恢复
                        try {
                            logWriter = new FileWriter(logFile, true);
                        } catch (IOException e2) {
                            Log.e(TAG, "Failed to recreate log writer after rotation", e2);
                            // 尝试下一次写入时重新初始化
                            logWriter = null;
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    public static void close() {
        synchronized (writerLock) {
            try {
                if (logWriter != null) logWriter.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 读取最近的日志（供 TombstoneXService 调用）
     * @param maxLines 最多读取的行数
     * @return 日志内容字符串
     */
    public static String readLog(int maxLines) {
        File logFile = new File(LOG_DIR, "current.log");
        if (!logFile.exists()) return "";
        List<String> lines = new ArrayList<>();
        synchronized (writerLock) {
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                return "Failed to read log: " + e.getMessage();
            }
        }
        int start = Math.max(0, lines.size() - maxLines);
        List<String> subList = lines.subList(start, lines.size());
        StringBuilder sb = new StringBuilder();
        for (String line : subList) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 清空日志（供 TombstoneXService 调用）
     */
    public static void clearLog() {
        synchronized (writerLock) {
            try {
                if (logWriter != null) {
                    logWriter.close();
                }
            } catch (IOException ignored) {}
            File logFile = new File(LOG_DIR, "current.log");
            File oldFile = new File(LOG_DIR, "current.log.old");
            if (oldFile.exists()) oldFile.delete();
            if (logFile.exists()) logFile.delete();
            try {
                logWriter = new FileWriter(logFile, true);
            } catch (IOException e) {
                Log.e(TAG, "Failed to recreate log file after clear", e);
            }
        }
    }
}
