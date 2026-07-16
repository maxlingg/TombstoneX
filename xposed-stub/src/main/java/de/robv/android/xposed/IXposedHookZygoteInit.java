package de.robv.android.xposed;

public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam);

    class StartupParam {
        public String modulePath;
    }
}
