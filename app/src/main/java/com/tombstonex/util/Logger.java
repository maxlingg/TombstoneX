package com.tombstonex.util;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class Logger {
    private static final String TAG = "TombstoneX";
    private static final String LOG_DIR = "/data/system/TombstoneX";
    private static final long MAX_LOG_SIZE = 2 * 1024 * 1024; // 2MB
    private static volatile boolean debugEnabled = false;
    private static OutputStreamWriter logWriter;
    private static final Object writerLock = new Object();
    // P3-R2: 内存近似写入字节计数器，避免每条日志都做文件系统 stat 调用。
    // 仅当计数器超过阈值时才检查实际文件大小并轮转。
    private static long approxLogBytes = 0;

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
                } catch (IOException e) {
                    Log.d(TAG, "Failed to close old log writer: " + e.getMessage());
                }
                logWriter = null;
            }
            try {
                File dir = new File(LOG_DIR);
                if (!dir.exists()) dir.mkdirs();
                File logFile = new File(dir, "current.log");
                // 日志轮转：如果超过 2MB，备份为 .old
                if (logFile.exists() && logFile.length() >= MAX_LOG_SIZE) {
                    File oldFile = new File(dir, "current.log.old");
                    if (oldFile.exists() && !oldFile.delete()) {
                        Log.w(TAG, "Failed to delete old log file during init");
                    }
                    if (!logFile.renameTo(oldFile)) {
                        Log.e(TAG, "Failed to rename log file during init rotation");
                    }
                }
                logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                // P3-R2: 初始化时重置计数器
                approxLogBytes = logFile.exists() ? logFile.length() : 0;
            } catch (IOException e) {
                Log.e(TAG, "Failed to init log file", e);
            }
        }
    }

    private static String format(String level, String msg) {
        SimpleDateFormat sdf = dateFormatHolder.get();
        // P3-7: null msg 输出空字符串而非字面量 "null"
        String safeMsg = msg != null ? msg : "";
        return String.format("[%s][%s] %s\n", sdf.format(new Date()), level, safeMsg);
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
        // P3-R3: 与 format() 的 null msg 处理保持一致
        String safeMsg = msg != null ? msg : "";
        String detail = safeMsg;
        if (t != null) {
            String tMsg = t.getMessage();
            detail = safeMsg + " | " + (tMsg != null ? tMsg : t.toString());
        }
        Log.e(TAG, detail);
        writeFile("E", detail);
    }

    private static void writeFile(String level, String msg) {
        synchronized (writerLock) {
            try {
                if (logWriter != null) {
                    String formatted = format(level, msg);
                    logWriter.write(formatted);
                    logWriter.flush();
                    // P3-R2: 使用内存计数器而非每次 stat 文件
                    approxLogBytes += formatted.getBytes(StandardCharsets.UTF_8).length;
                    // 仅当计数器超过阈值时才检查实际文件大小并轮转
                    if (approxLogBytes >= MAX_LOG_SIZE) {
                        File logFile = new File(LOG_DIR, "current.log");
                        if (logFile.exists() && logFile.length() >= MAX_LOG_SIZE) {
                            try {
                                logWriter.close();
                            } catch (IOException e) {
                                Log.d(TAG, "Failed to close log writer during rotation: " + e.getMessage());
                            }
                            File oldFile = new File(LOG_DIR, "current.log.old");
                            if (oldFile.exists() && !oldFile.delete()) {
                                Log.w(TAG, "Failed to delete old log file during rotation");
                            }
                            if (!logFile.renameTo(oldFile)) {
                                Log.e(TAG, "Failed to rename log file during rotation, truncating instead");
                                // P3-R2: rename 失败时降级为截断，避免文件持续增长
                                try {
                                    logWriter = new OutputStreamWriter(
                                        new FileOutputStream(logFile, false), StandardCharsets.UTF_8);
                                    approxLogBytes = 0;
                                } catch (IOException e2) {
                                    Log.e(TAG, "Failed to truncate log after rename failure", e2);
                                    logWriter = null;
                                }
                            } else {
                                // 轮转后重建 writer 并重置计数器
                                approxLogBytes = 0;
                                try {
                                    logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                                } catch (IOException e2) {
                                    Log.e(TAG, "Failed to recreate log writer after rotation", e2);
                                    logWriter = null;
                                }
                            }
                        } else {
                            // 文件实际未超限（可能被 clearLog 截断），重置计数器
                            approxLogBytes = 0;
                        }
                    }
                } else {
                    // 尝试重建 writer
                    try {
                        File dir = new File(LOG_DIR);
                        if (!dir.exists()) dir.mkdirs();
                        File logFile = new File(dir, "current.log");
                        logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                        String formatted = format(level, msg);
                        logWriter.write(formatted);
                        logWriter.flush();
                        approxLogBytes = formatted.getBytes(StandardCharsets.UTF_8).length;
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to recreate log writer: " + e.getMessage());
                        logWriter = null;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to write log file: " + e.getMessage());
                logWriter = null;
            }
        }
    }

    public static void close() {
        synchronized (writerLock) {
            try {
                if (logWriter != null) logWriter.close();
            } catch (IOException e) {
                Log.d(TAG, "Failed to close log writer: " + e.getMessage());
            } finally {
                logWriter = null;
            }
        }
    }

    /**
     * 读取最近的日志（供 TombstoneXService 调用）
     * @param maxLines 最多读取的行数
     * @return 日志内容字符串
     */
    public static String readLog(int maxLines) {
        if (maxLines <= 0) return "";
        File logFile = new File(LOG_DIR, "current.log");
        List<String> lines = new ArrayList<>();
        // P3-R1: 仅在锁内做 flush 确保 writer 缓冲区写入磁盘，文件读取移到锁外，
        // 避免持有 writerLock 期间全量读取日志文件阻塞所有线程的日志写入。
        synchronized (writerLock) {
            if (logWriter != null) {
                try {
                    logWriter.flush();
                } catch (IOException e) {
                    Log.d(TAG, "Failed to flush before read: " + e.getMessage());
                }
            }
            if (!logFile.exists()) return "";
        }
        // 锁外读取文件
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (FileNotFoundException e) {
            // P3-R5: 文件在锁释放后被 clearLog 删除，返回空字符串而非错误信息
            return "";
        } catch (IOException e) {
            return "Failed to read log: " + e.getMessage();
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
                if (logWriter != null) logWriter.close();
            } catch (IOException e) {
                Log.d(TAG, "Failed to close log writer during clear: " + e.getMessage());
            }
            logWriter = null;
            File logFile = new File(LOG_DIR, "current.log");
            File oldFile = new File(LOG_DIR, "current.log.old");
            if (oldFile.exists() && !oldFile.delete()) {
                Log.w(TAG, "Failed to delete old log file during clear");
            }
            // P2-R2: 用截断模式打开替代 delete + recreate，避免删除失败导致清空无效
            // FileOutputStream(file, false) 会截断文件为 0 字节，在拥有写权限时不会失败
            try {
                logWriter = new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8);
                approxLogBytes = 0;
            } catch (IOException e) {
                Log.e(TAG, "Failed to truncate log file during clear", e);
            }
        }
    }
}
