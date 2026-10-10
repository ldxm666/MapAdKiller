package io.github.ldxm666.mapclean;

import java.lang.reflect.Executable;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** Disable only an incompatible hook; never execute an application's method twice. */
public final class HookGuard implements XposedInterface.Hooker {
    public interface Reporter { void disabled(String id, Throwable error); }
    private final XposedInterface.Hooker hook;
    private final String id;
    private final Reporter reporter;
    private volatile boolean disabled;
    public HookGuard(String id, XposedInterface.Hooker hook, Reporter reporter) {
        this.id = id; this.hook = hook; this.reporter = reporter;
    }
    @Override public Object intercept(XposedInterface.Chain original) throws Throwable {
        if (disabled) return original.proceed();
        Call call = new Call(original);
        try { return hook.intercept(call); }
        catch (Throwable error) {
            if (call.failure != null) throw call.failure;
            disabled = true;
            try { reporter.disabled(id, error); } catch (Throwable ignored) {}
            return call.completed ? call.result : original.proceed();
        }
    }
    private static final class Call implements XposedInterface.Chain {
        private final XposedInterface.Chain chain;
        private boolean completed;
        private Object result;
        private Throwable failure;
        Call(XposedInterface.Chain chain) { this.chain = chain; }
        public Executable getExecutable() { return chain.getExecutable(); }
        public Object getThisObject() { return chain.getThisObject(); }
        public List<Object> getArgs() { return chain.getArgs(); }
        public Object getArg(int i) { return chain.getArg(i); }
        public Object proceed() throws Throwable { return run(0, null, null); }
        public Object proceed(Object[] args) throws Throwable { return run(1, null, args); }
        public Object proceedWith(Object self) throws Throwable { return run(2, self, null); }
        public Object proceedWith(Object self, Object[] args) throws Throwable { return run(3, self, args); }
        private Object run(int kind, Object self, Object[] args) throws Throwable {
            try {
                switch (kind) {
                    case 1: result = chain.proceed(args); break;
                    case 2: result = chain.proceedWith(self); break;
                    case 3: result = chain.proceedWith(self, args); break;
                    default: result = chain.proceed();
                }
                completed = true;
                return result;
            } catch (Throwable error) { failure = error; throw error; }
        }
    }
}
