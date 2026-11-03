package io.github.ldxm666.mapclean;

import java.lang.reflect.Executable;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/**
 * 只下线**确实不兼容**的 hook，且绝不把宿主方法执行第二次。
 *
 * <p>下线策略见 {@link #intercept}：结构性缺失（{@link LinkageError}）立即停，其余异常连续
 * {@link #FAIL_LIMIT} 次才停 —— 一条 hook 因为一次偶发 NPE 就永久失效，是"开关没反应"的头号来源。
 */
public final class HookGuard implements XposedInterface.Hooker {
    public interface Reporter { void disabled(String id, Throwable error); }
    private final XposedInterface.Hooker hook;
    private final String id;
    private final Reporter reporter;
    /**
     * 连续失败多少次才下线。
     *
     * <p>以前是 **1 次就永久下线**：一个"控件还没 attach，所以 getLayoutParams() 返回 null"
     * 之类的一次性 NPE，会让这条 hook 在整个进程生命周期里都不再工作 —— 用户看到的就是
     * 「开关点了没反应，重启 App 又好了」。3 次是折中：偶发异常只要中间成功过一次就清零；
     * 真正每个版本都炸的实现，最多多付两次调用就停，不会每帧抛。
     */
    private static final int FAIL_LIMIT = 3;
    private volatile boolean disabled;
    /** 连续失败次数（成功一次清零）。hook 可能被多个线程同时调用，用原子量避免 ++ 丢更新。 */
    private final java.util.concurrent.atomic.AtomicInteger failures =
            new java.util.concurrent.atomic.AtomicInteger();
    public HookGuard(String id, XposedInterface.Hooker hook, Reporter reporter) {
        this.id = id; this.hook = hook; this.reporter = reporter;
    }
    @Override public Object intercept(XposedInterface.Chain original) throws Throwable {
        if (disabled) return original.proceed();
        Call call = new Call(original);
        try {
            Object result = hook.intercept(call);
            // 成功路径必须几乎零成本：这些 hook 里有的每帧都在跑。只在"确实有历史失败"时
            // 才写这个原子量，平时只是一次 volatile 读，不产生写屏障。
            if (failures.get() != 0) failures.set(0);
            return result;
        }
        catch (Throwable error) {
            if (call.failure != null) throw call.failure;
            // 结构性缺失 = 宿主版本漂了（方法/字段/父类签名不存在、类找不到、so 没加载）。
            // 这类重试没有任何意义，只会每帧抛一次并把日志刷满 ⇒ 立刻下线。
            // 其余（NPE / IllegalArgumentException / IndexOutOfBounds …）常常只是"这一刻的
            // 状态不对"，下一次调用可能就正常了 ⇒ 连续 FAIL_LIMIT 次才下线。
            boolean structural = error instanceof LinkageError;
            boolean stop = structural || failures.incrementAndGet() >= FAIL_LIMIT;
            if (stop) disabled = true;
            // 只在"第一次失败"和"真的下线"时记日志：否则一个每帧都在抛的 hook 会把 logcat
            // 和抓包环形缓冲一起刷爆，反而盖掉真正的现场。
            if (stop || failures.get() == 1) {
                try { reporter.disabled(id, error); } catch (Throwable ignored) {}
            }
            return call.completed ? call.result : original.proceed();
        }
    }

    /**
     * 一行可读的异常描述：类名 + message + 栈顶帧。
     *
     * <p>原来 reporter 只打 {@code getClass().getSimpleName()}，NPE 就只有 "NullPointerException"
     * —— 既不知道在哪一行，也不知道哪个字段为 null，排障等于瞎。旧 Android 上
     * {@code getMessage()} 还常常是 null，所以补一个栈顶帧兜底。只在失败路径调用，代价可忽略。
     */
    public static String describe(Throwable error) {
        if (error == null) return "null";
        StringBuilder out = new StringBuilder(error.getClass().getSimpleName());
        try {
            String msg = error.getMessage();
            if (msg != null && msg.length() > 0) out.append(": ").append(msg);
        } catch (Throwable ignored) { }
        try {
            StackTraceElement[] trace = error.getStackTrace();
            if (trace != null && trace.length > 0 && trace[0] != null) {
                out.append(" @ ").append(trace[0].getClassName()).append('.')
                   .append(trace[0].getMethodName()).append(':').append(trace[0].getLineNumber());
            }
        } catch (Throwable ignored) { }
        return out.toString();
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
