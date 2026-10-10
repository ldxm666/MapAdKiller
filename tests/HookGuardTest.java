package io.github.ldxm666.mapclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;

/** Fallback must not repeat side effects or hide exceptions thrown by the map itself. */
public final class HookGuardTest {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
    private static final class Original implements XposedInterface.Chain {
        int calls;
        Throwable error;
        Object self;
        Object[] args = {"original"};
        public Executable getExecutable() { return null; }
        public Object getThisObject() { return self; }
        public List<Object> getArgs() { return Arrays.asList(args); }
        public Object getArg(int index) { return args[index]; }
        public Object proceed() throws Throwable { calls++; if (error != null) throw error; return "native-result"; }
        public Object proceed(Object[] next) throws Throwable { args = next; return proceed(); }
        public Object proceedWith(Object next) throws Throwable { self = next; return proceed(); }
        public Object proceedWith(Object next, Object[] values) throws Throwable { self = next; return proceed(values); }
    }
    public static void main(String[] ignored) throws Throwable {
        final int[] reports = {0}, hooks = {0};
        HookGuard.Reporter report = (id, error) -> reports[0]++;
        HookGuard before = new HookGuard("before", chain -> { hooks[0]++; throw new IllegalStateException("module-before"); }, report);
        Original original = new Original();
        check("native-result".equals(before.intercept(original)), "pre-hook failure uses original");
        check(original.calls == 1 && reports[0] == 1, "pre-hook fallback executes once");
        check("native-result".equals(before.intercept(original)) && original.calls == 2, "disabled hook passes through on next call");
        check(hooks[0] == 1 && reports[0] == 1, "incompatible hook is disabled once");
        HookGuard after = new HookGuard("after", chain -> { chain.proceed(); throw new IllegalStateException("module-after"); }, report);
        original = new Original();
        check("native-result".equals(after.intercept(original)), "post-hook failure keeps original result");
        check(original.calls == 1, "post-hook fallback never repeats native side effects");
        Throwable mapError = new IllegalArgumentException("map-error");
        original = new Original(); original.error = mapError;
        int reported = reports[0];
        HookGuard pass = new HookGuard("map", chain -> chain.proceed(), report);
        Throwable caught = null;
        try { pass.intercept(original); } catch (Throwable e) { caught = e; }
        check(caught == mapError && original.calls == 1, "map exception identity preserved");
        check(reports[0] == reported, "map error does not mark hook incompatible");
        original.error = null;
        check("native-result".equals(pass.intercept(original)), "hook stays enabled after a native error");
        Original changed = new Original(); Object receiver = new Object();
        HookGuard arguments = new HookGuard("args", chain -> chain.proceedWith(receiver, new Object[]{"changed"}), report);
        check("native-result".equals(arguments.intercept(changed)), "receiver-and-args overload returns native result");
        check(changed.calls == 1 && changed.self == receiver && "changed".equals(changed.args[0]), "modified invocation forwarded exactly once");
        HookGuard shortCircuit = new HookGuard("removed", chain -> null, report);
        Original hidden = new Original();
        check(shortCircuit.intercept(hidden) == null && hidden.calls == 0, "intentional removal does not invoke original");
        System.out.println("PASS " + assertions + " hook fallback assertions");
    }
}
