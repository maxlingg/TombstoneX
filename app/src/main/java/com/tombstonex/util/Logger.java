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
import java.util.ArrayDeque;
import java.util.Date;
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
    // 中等-3 修复：日志文件不可用标志。init() 确认目录不可写时设为 true，
    // writeFile() 和 clearLog() 在该标志为 true 时直接返回，避免每次写入都尝试重建 writer 并失败。
    private static volatile boolean logFileUnavailable = false;

    /**
     * M28: 自定义日志目录。
     * App 主进程无权限访问 /data/system/，可通过 {@link #init(boolean, String)}
     * 指定 App 私有目录。system_server 侧不设置此字段，仍使用 {@link #LOG_DIR}。
     */
    private static volatile String customLogDir = null;

    /**
     * 获取实际生效的日志目录：优先使用 customLogDir，否则使用 LOG_DIR。
     */
    private static String getEffectiveLogDir() {
        return customLogDir != null ? customLogDir : LOG_DIR;
    }

    /** ThreadLocal 缓存 SimpleDateFormat，避免频繁创建 */
    /**
     * M-35: 在 system_server 线程池环境下，ThreadLocal 持有的 SimpleDateFormat
     * 实例可能在线程被回收后仍然存在，但 system_server 的线程池通常不会频繁创建/销毁
     * 线程，因此 ThreadLocal 内存泄漏风险可接受。若在其他环境中使用（如频繁创建线程），
     * 需考虑使用 ThreadLocal.remove() 或改用 java.time 包。
     */
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
                File dir = new File(getEffectiveLogDir());
                if (!dir.exists() && !dir.mkdirs()) {
                    // 中等-3: 目录不可写，禁用文件日志，仅使用 Logcat
                    // 删除原硬编码的 /data/data/com.tombstonex/files/logs 兜底路径，
                    // 避免包名变更后路径失效；不可写时降级为纯 Logcat。
                    logWriter = null;
                    // 中等-3: 设置不可用标志，writeFile()/clearLog() 据此直接返回，
                    // 不再每次写入都尝试重建 writer 并失败
                    logFileUnavailable = true;
                    Log.w(TAG, "日志目录不可写: " + dir + "，仅使用 Logcat");
                    return;
                }
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
                // 中等-3: 成功创建 writer，清除不可用标志（支持目录变为可写后重新启用）
                logFileUnavailable = false;
                // P3-R2: 初始化时重置计数器
                approxLogBytes = logFile.exists() ? logFile.length() : 0;
            } catch (IOException e) {
                Log.e(TAG, "Failed to init log file", e);
            }
        }
    }

    /**
     * M28: 带自定义日志目录的初始化方法。
     * App 主进程无权限访问 /data/system/，通过此方法传入 App 私有目录。
     * system_server 侧仍使用 {@link #init(boolean)}，使用默认 LOG_DIR。
     *
     * @param debug  是否开启调试日志
     * @param logDir 自定义日志目录路径
     *
     * P7-R7: customLogDir 赋值移入 writerLock 同步块，消除与 init(debug)
     * 重建 writer 之间的竞态窗口（原实现 customLogDir 在锁外赋值，可能导致
     * init(debug) 在锁内读到旧值或半初始化值，进而使用错误的目录重建 writer）。
     */
    public static void init(boolean debug, String logDir) {
        synchronized (writerLock) {
            customLogDir = logDir;
        }
        init(debug);
    }

    private static String format(String level, String msg) {
        SimpleDateFormat sdf = dateFormatHolder.get();
        // P3-7: null msg 输出空字符串而非字面量 "null"
        String safeMsg = msg != null ? msg : "";
        return String.format("[%s][%s] %s\n", sdf.format(new Date()), level, safeMsg);
    }

    /**
     * R8-m3: 返回调试日志是否启用，供调用方在非 debug 模式下跳过无意义的处理逻辑。
     */
    public static boolean isDebug() {
        return debugEnabled;
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
                        File logFile = new File(getEffectiveLogDir(), "current.log");
                        if (logFile.exists() && logFile.length() >= MAX_LOG_SIZE) {
                            try {
                                logWriter.close();
                            } catch (IOException e) {
                                Log.d(TAG, "Failed to close log writer during rotation: " + e.getMessage());
                            }
                            File oldFile = new File(getEffectiveLogDir(), "current.log.old");
                            if (oldFile.exists() && !oldFile.delete()) {
                                Log.w(TAG, "Failed to delete old log file during rotation");
                            }
                            if (!logFile.renameTo(oldFile)) {
                                Log.e(TAG, "Failed to rename log file during rotation");
                                // S-10: renameTo 失败时不再截断文件（会导致数据丢失）。
                                // 改为：尝试复制文件到备份、删除原文件、创建新文件的三步操作。
                                // 如果复制也失败，则在现有日志文件末尾追加警告标记并继续追加写入，不截断。
                                boolean copied = false;
                                try {
                                    java.nio.file.Files.copy(
                                        logFile.toPath(), oldFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    if (logFile.delete()) {
                                        copied = true;
                                    }
                                } catch (IOException copyEx) {
                                    Log.e(TAG, "Failed to copy log during rotation fallback: " + copyEx.getMessage());
                                }
                                if (copied) {
                                    // 复制成功，创建新文件
                                    approxLogBytes = 0;
                                    try {
                                        logWriter = new OutputStreamWriter(
                                            new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                                    } catch (IOException e2) {
                                        Log.e(TAG, "Failed to recreate log writer after copy rotation", e2);
                                        logWriter = null;
                                    }
                                } else {
                                    // S-10: 复制也失败，不截断，在现有文件末尾追加警告并继续追加写入
                                    Log.e(TAG, "LOG ROTATION FAILED, continuing in append mode");
                                    try {
                                        logWriter = new OutputStreamWriter(
                                            new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                                        logWriter.write("=== LOG ROTATION FAILED, CONTINUING IN APPEND MODE ===\n");
                                        logWriter.flush();
                                        approxLogBytes = logFile.length();
                                    } catch (IOException e2) {
                                        Log.e(TAG, "Failed to open log in append mode after rotation failure", e2);
                                        logWriter = null;
                                    }
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
                    // 中等-3: 目录被确认不可写时不再尝试重建 writer，避免每次写入都失败
                    if (logFileUnavailable) return;
                    // 尝试重建 writer
                    try {
                        File dir = new File(getEffectiveLogDir());
                        if (!dir.exists()) dir.mkdirs();
                        File logFile = new File(dir, "current.log");
                        logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                        logFileUnavailable = false;
                        String formatted = format(level, msg);
                        logWriter.write(formatted);
                        logWriter.flush();
                        // 修复：重建时包含文件已有内容大小，避免 approxLogBytes 低估导致轮转延迟
                        approxLogBytes = logFile.length() + formatted.getBytes(StandardCharsets.UTF_8).length;
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to recreate log writer: " + e.getMessage());
                        logWriter = null;
                        // P7-R7: 扩大 logFileUnavailable 设置范围。原实现仅在
                        // FileNotFoundException 时设置不可用标志，其它 IOException
                        // （如权限拒绝、磁盘满、目录被删除等）未设置，导致后续每次写入
                        // 都重复尝试重建 writer 并失败。改为对所有 IOException 都设置
                        // 不可用标志，避免持续无效重试。
                        logFileUnavailable = true;
                        Log.w(TAG, "日志写入不可用，禁用文件日志");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to write log file: " + e.getMessage());
                logWriter = null;
                // L1 修复：外层 catch 也需在 IOException 时设置不可用标志，
                // 避免后续每次写入都重复尝试并失败
                // P7-R7: 同样扩大到所有 IOException，与重建 writer 的 catch 保持一致
                logFileUnavailable = true;
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
     *
     * P7-R7: 使用 ArrayDeque 作为固定容量滑动窗口，仅保留最后 maxLines 行。
     * 原实现先读全文件入 ArrayList 再取 subList，内存峰值约为日志文件大小
     * （可达 2MB）。改用滑动窗口后内存峰值仅约 maxLines 行的字节数。
     */
    public static String readLog(int maxLines) {
        if (maxLines <= 0) return "";
        File logFile = new File(getEffectiveLogDir(), "current.log");
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
        // 锁外读取文件，使用 ArrayDeque 滑动窗口仅保留最后 maxLines 行
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            ArrayDeque<String> deque = new ArrayDeque<>(maxLines);
            String line;
            while ((line = reader.readLine()) != null) {
                // 超过容量时淘汰最旧的一行，保证窗口固定为 maxLines
                if (deque.size() >= maxLines) {
                    deque.pollFirst();
                }
                deque.offerLast(line);
            }
            StringBuilder sb = new StringBuilder();
            for (String l : deque) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (FileNotFoundException e) {
            // P3-R5: 文件在锁释放后被 clearLog 删除，返回空字符串而非错误信息
            return "";
        } catch (IOException e) {
            Log.e(TAG, "readLog failed", e);
            return "";
        }
    }

    /**
     * 清空日志（供 TombstoneXService 调用）
     */
    public static void clearLog() {
        synchronized (writerLock) {
            // 中等-3: 目录不可写时文件日志已禁用，无需清空
            if (logFileUnavailable) return;
            try {
                if (logWriter != null) logWriter.close();
            } catch (IOException e) {
                Log.d(TAG, "Failed to close log writer during clear: " + e.getMessage());
            }
            logWriter = null;
            File logFile = new File(getEffectiveLogDir(), "current.log");
            File oldFile = new File(getEffectiveLogDir(), "current.log.old");
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
