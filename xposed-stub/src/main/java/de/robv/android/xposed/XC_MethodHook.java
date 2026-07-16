package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public Unhook unhook() {
        return null;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public class MethodHookParam {
        public Method method;
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    public class Unhook {
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }
    }
}
