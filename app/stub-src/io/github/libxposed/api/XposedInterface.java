package io.github.libxposed.api;

import java.lang.reflect.Executable;

/**
 * compile-only stub of libxposed:api 102 (io.github.libxposed.api.XposedInterface).
 * NEVER packaged — the framework provides the real classes at runtime.
 * Descriptors must match upstream exactly for runtime linkage.
 */
public interface XposedInterface {

    int PRIORITY_LOWEST = Integer.MIN_VALUE;
    int PRIORITY_NORMAL = 0;
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;
    int PRIORITY_DEFAULT = PRIORITY_NORMAL;

    enum ExceptionMode {
        DEFAULT,
        PASSTHROUGH
    }

    HookBuilder hook(Executable target);

    interface HookBuilder {
        HookBuilder setId(String id);
        HookBuilder setPriority(int priority);
        HookBuilder setExceptionMode(ExceptionMode exceptionMode);
        HookHandle intercept(Hooker hooker);
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface Chain {
        Executable getExecutable();
        Object getThisObject();
        java.util.List<Object> getArgs();
        Object getArg(int index);
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
        Object proceedWith(Object thisObject) throws Throwable;
        Object proceedWith(Object thisObject, Object[] args) throws Throwable;
    }

    interface HookHandle {
        String getId();
        Executable getExecutable();
        void unhook();
        HookHandle replaceHook(Hooker hooker);
    }
}
