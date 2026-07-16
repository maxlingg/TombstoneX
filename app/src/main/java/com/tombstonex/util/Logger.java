package com.tombstonex.util;

import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {
    private static final String TAG = "TombstoneX";
    private static final String LOG_DIR = "/data/system/TombstoneX";
    private static boolean debugEnabled = false;
    private static FileWriter logWriter;

    public static void init(boolean debug) {
        debugEnabled = debug;
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "current.log");
            logWriter = new FileWriter(logFile, true);
        } catch (IOException e) {
            Log.e(TAG, "Failed to init log file", e);
        }
    }

    private static String format(String level, String msg) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return String.format("[%s][%s] %s\n", sdf.format(new Date()), level, msg);
    }

    public static void d(String msg) {
        if (debugEnabled) Log.d(TAG, msg);
        writeFile("D", msg);
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
        Log.e(TAG, msg, t);
        writeFile("E", msg + " | " + t.getMessage());
    }

    private static void writeFile(String level, String msg) {
        try {
            if (logWriter != null) {
                logWriter.write(format(level, msg));
                logWriter.flush();
            }
        } catch (IOException ignored) {}
    }

    public static void close() {
        try {
            if (logWriter != null) logWriter.close();
        } catch (IOException ignored) {}
    }
}