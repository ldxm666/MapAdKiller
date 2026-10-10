package io.github.ldxm666.mapclean;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** JVM test: public service capabilities, delayed Binder replies, and connection races. */
public final class FrameworkStatusTest {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Clock implements FrameworkStatus.Clock {
        long now;
        @Override public long elapsedMillis() { return now; }
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("test");
            work.add(task);
        }
        void runNext() { work.remove().run(); }
    }

    private static final class Service implements FrameworkStatus.Source {
        int calls;
        int api = 102;
        String name = "Vector";
        String version = "2.2";
        List<String> scope = new ArrayList<>(Arrays.asList("com.autonavi.minimap", "com.baidu.BaiduMap"));
        boolean badApi, badName, badVersion, badScope;
        Runnable duringScope;
        @Override public int getApiVersion() {
            calls++;
            if (badApi) throw new IllegalStateException("private API error");
            return api;
        }
        @Override public String getFrameworkName() {
            calls++;
            if (badName) throw new LinkageError("private name error");
            return name;
        }
        @Override public String getFrameworkVersion() {
            calls++;
            if (badVersion) throw new IllegalArgumentException("private version error");
            return version;
        }
        @Override public List<String> getScope() {
            calls++;
            if (duringScope != null) duringScope.run();
            if (badScope) throw new UnsupportedOperationException("private scope error");
            return scope;
        }
    }

    public static void main(String[] args) {
        Queue queue = new Queue();
        Clock clock = new Clock();
        FrameworkStatus.Monitor monitor = new FrameworkStatus.Monitor(queue, clock, 5000);
        check(!monitor.snapshot().connected && !monitor.snapshot().scopeKnown, "unbound is waiting, not an empty scope");
        monitor.refresh();
        check(queue.work.isEmpty(), "unbound refresh never reads a service");

        Service vector = new Service();
        monitor.bind(vector);
        check(monitor.snapshot().connected && !monitor.snapshot().loaded, "binding immediately records the connected pending state");
        check(vector.calls == 0 && queue.work.size() == 1, "bind only queues public API work");
        for (int i = 0; i < 20; i++) monitor.refresh();
        check(queue.work.size() == 1 && vector.calls == 0, "UI polling does not perform IPC or duplicate queued reads");
        queue.runNext();
        FrameworkStatus.Snapshot state = monitor.snapshot();
        check(state.connected && state.loaded && state.scopeKnown, "Vector public API replies are accepted");
        check("Vector 2.2".equals(state.frameworkLabel()) && state.apiVersion == 102, "framework label and API are reported from the service");
        check(state.scope.contains("com.autonavi.minimap") && state.scope.contains("com.baidu.BaiduMap"), "map scope is retained");
        vector.scope.clear();
        check(state.scope.size() == 2, "snapshot is independent of a mutable service reply");
        boolean immutable = false;
        try { state.scope.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "UI cannot mutate the snapshot");
        clock.now = 4999;
        monitor.refresh();
        check(queue.work.isEmpty() && vector.calls == 4, "status reads are throttled between refresh intervals");
        clock.now = 5000;
        monitor.refresh();
        check(queue.work.size() == 1 && vector.calls == 4, "elapsed interval schedules work without synchronous IPC");
        queue.runNext();
        check(monitor.snapshot().scopeKnown && monitor.snapshot().scope.isEmpty(), "a successful empty scope is distinct from failure");

        vector.badScope = true;
        clock.now = 10000;
        monitor.refresh();
        queue.runNext();
        state = monitor.snapshot();
        check(state.connected && !state.scopeKnown, "scope endpoint failure does not imply disconnection or disabled module");
        check("UnsupportedOperationException".equals(state.scopeError), "scope diagnostic contains only the exception type");
        check(!state.scopeError.contains("private"), "service error messages are not exposed");
        vector.badScope = false;
        vector.scope = null;
        clock.now = 15000;
        monitor.refresh();
        queue.runNext();
        check(!monitor.snapshot().scopeKnown && "EmptyReply".equals(monitor.snapshot().scopeError), "null scope reply is unknown rather than unselected");
        vector.scope = Collections.singletonList("com.autonavi.minimap");
        clock.now = 20000;
        monitor.refresh();
        queue.runNext();
        check(monitor.snapshot().scopeKnown && monitor.snapshot().scope.contains("com.autonavi.minimap"), "a transient scope failure recovers on the same connection");

        Service patch = new Service();
        patch.name = "LSPatch";
        patch.version = "1.2";
        patch.scope = Collections.singletonList("com.autonavi.minimap");
        monitor.bind(patch);
        queue.runNext();
        check("LSPatch 1.2".equals(monitor.snapshot().frameworkLabel()), "embedded framework uses the same public service interface");
        check(monitor.snapshot().scopeKnown && monitor.snapshot().scope.size() == 1, "embedded framework's actual target scope is used");
        monitor.disconnected(vector);
        check(monitor.snapshot().connected && "LSPatch 1.2".equals(monitor.snapshot().frameworkLabel()), "death of a replaced service does not clear the current connection");

        Service uncertain = new Service();
        uncertain.badApi = uncertain.badName = uncertain.badVersion = true;
        uncertain.scope = Arrays.asList(null, "", " com.baidu.BaiduMap ", "com.baidu.BaiduMap");
        monitor.bind(uncertain);
        queue.runNext();
        state = monitor.snapshot();
        check(state.connected && state.scopeKnown && state.scope.size() == 1, "metadata failures do not hide a valid scope reply");
        check("Xposed 兼容框架".equals(state.frameworkLabel()) && state.apiVersion == 0, "missing metadata uses a neutral label without crashing");
        uncertain.badApi = uncertain.badName = uncertain.badVersion = false;
        uncertain.name = "  ";
        uncertain.version = null;
        state = FrameworkStatus.read(uncertain);
        check("Xposed 兼容框架".equals(state.frameworkLabel()), "null and blank metadata remain safe");
        uncertain.api = 101;
        state = FrameworkStatus.read(uncertain);
        check(state.apiVersion == 101 && state.scopeKnown, "older API is reported, allowing a specific compatibility warning");

        Service slow = new Service();
        Service replacement = new Service();
        replacement.name = "LSPosed";
        replacement.version = "2.1.1";
        slow.duringScope = new Runnable() {
            @Override public void run() { monitor.bind(replacement); }
        };
        monitor.bind(slow);
        queue.runNext();
        check(monitor.snapshot().connected && !monitor.snapshot().loaded, "an old in-flight reply cannot overwrite a newer binding");
        queue.runNext();
        check("LSPosed 2.1.1".equals(monitor.snapshot().frameworkLabel()), "the replacement service supplies the current snapshot");

        Service late = new Service();
        monitor.bind(late);
        monitor.disconnected(late);
        queue.runNext();
        check(!monitor.snapshot().connected && late.calls == 0, "a queued read is discarded after service death");
        monitor.refresh();
        check(queue.work.isEmpty(), "death stops status polling until a new binder arrives");

        queue.reject = true;
        monitor.bind(vector);
        check(monitor.snapshot().connected && !monitor.snapshot().loaded, "executor failure does not assert a disabled module");
        queue.reject = false;
        vector.scope = Collections.singletonList("com.baidu.BaiduMap");
        monitor.refresh();
        queue.runNext();
        check(monitor.snapshot().scopeKnown, "an executor rejection can be retried immediately");
        System.out.println("PASS " + assertions + " framework status assertions");
    }
}
