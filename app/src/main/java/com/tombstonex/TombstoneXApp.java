package com.tombstonex;

import android.app.Application;
import android.os.Process;

import com.tombstonex.util.Logger;

import java.io.BufferedReader;
import java.io.FileReader;

public class TombstoneXApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 区分进程：只在主进程初始化，避免子进程（如磁贴服务进程）重复初始化
        String processName = getCurrentProcessName();
        if (processName == null || processName.equals(getPackageName())) {
            // App 进程无权限访问 /data/system/ 配置目录，
            // 不在此初始化 ConfigManager（会因无权限失败），仅初始化 Logger。
            // M28: 传入 App 私有目录作为日志目录，避免在主进程写入 /data/system/ 失败。
            // 调试开关默认关闭（false），配置写入由 system_server 侧的模块完成。
            Logger.init(false, getFilesDir().getAbsolutePath() + "/logs");
            Logger.i("TombstoneX Application 已创建");
        }
    }

    /**
     * 获取当前进程名。
     * 通过读取 /proc/<pid>/cmdline 实现，兼容所有 API 级别。
     *
     * @return 当前进程名，读取失败时返回 null
     */
    private String getCurrentProcessName() {
        String cmdlinePath = "/proc/" + Process.myPid() + "/cmdline";
        try (BufferedReader reader = new BufferedReader(new FileReader(cmdlinePath))) {
            String name = reader.readLine();
            return name != null ? name.trim() : null;
        } catch (Exception e) {
            // m-8: 记录 cmdline 读取异常，便于排查进程名检测失败的原因
            Logger.e("无法读取进程名称: " + cmdlinePath, e);
            return null;
        }
    }
}
