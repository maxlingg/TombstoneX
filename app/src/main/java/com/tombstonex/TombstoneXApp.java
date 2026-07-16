package com.tombstonex;

import android.app.Application;
import com.tombstonex.util.Logger;

public class TombstoneXApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(false);
        Logger.i("TombstoneX Application created");
    }
}