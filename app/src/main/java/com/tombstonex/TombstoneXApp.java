package com.tombstonex;

import android.app.Application;
import com.tombstonex.util.Logger;

public class TombstoneXApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 尝试从配置文件读取调试开关
        // 如果无法访问配置目录（UI 进程无权限），则默认关闭调试
        try {
            com.tombstonex.manager.ConfigManager config =
                com.tombstonex.manager.ConfigManager.getInstance();
            Logger.init(config.isDebugEnabled());
        } catch (Throwable t) {
            Logger.init(false);
        }
        Logger.i("TombstoneX Application created");
    }
}
