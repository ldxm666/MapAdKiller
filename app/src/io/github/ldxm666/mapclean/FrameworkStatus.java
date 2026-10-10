package io.github.ldxm666.mapclean;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/** Public libxposed service information, independent of framework-private IPC. */
public final class FrameworkStatus {
    private FrameworkStatus() {}

    public interface Source {
        int getApiVersion();
        String getFrameworkName();
        String getFrameworkVersion();
        List<String> getScope();
    }

    public interface Clock { long elapsedMillis(); }

    public static final class Snapshot {
        public final boolean connected;
        public final boolean loaded;
        public final int apiVersion;
        public final String frameworkName;
        public final String frameworkVersion;
        public final boolean scopeKnown;
        public final Set<String> scope;
        /** Exception type only; a service's exception message can contain private data. */
        public final String scopeError;

        private Snapshot(boolean connected, boolean loaded, int apiVersion,
                String frameworkName, String frameworkVersion, boolean scopeKnown,
                Set<String> scope, String scopeError) {
            this.connected = connected;
            this.loaded = loaded;
            this.apiVersion = apiVersion;
            this.frameworkName = frameworkName;
            this.frameworkVersion = frameworkVersion;
            this.scopeKnown = scopeKnown;
            this.scope = Collections.unmodifiableSet(new LinkedHashSet<>(scope));
            this.scopeError = scopeError;
        }

        public String frameworkLabel() {
            return frameworkVersion.isEmpty() ? frameworkName
                    : frameworkName + " " + frameworkVersion;
        }
    }

    private static Snapshot pending(boolean connected) {
        return new Snapshot(connected, false, 0, "Xposed 兼容框架", "", false,
                Collections.<String>emptySet(), "");
    }

    /** Each independent endpoint may fail without discarding the others. Run off the UI thread. */
    static Snapshot read(Source source) {
        int api = 0;
        String name = "Xposed 兼容框架";
        String version = "";
        boolean scopeKnown = false;
        Set<String> scope = new LinkedHashSet<>();
        String scopeError = "";
        try { api = Math.max(0, source.getApiVersion()); } catch (Throwable ignored) {}
        try {
            String value = source.getFrameworkName();
            if (value != null && !value.trim().isEmpty()) name = value.trim();
        } catch (Throwable ignored) {}
        try {
            String value = source.getFrameworkVersion();
            if (value != null) version = value.trim();
        } catch (Throwable ignored) {}
        try {
            List<String> value = source.getScope();
            if (value == null) {
                scopeError = "EmptyReply";
            } else {
                for (String entry : value) {
                    if (entry != null && !entry.trim().isEmpty()) scope.add(entry.trim());
                }
                scopeKnown = true;
            }
        } catch (Throwable error) {
            scopeError = error.getClass().getSimpleName();
        }
        return new Snapshot(true, true, api, name, version, scopeKnown, scope, scopeError);
    }

    /** One queued read per interval. Old replies cannot revive a dead or replaced service. */
    public static final class Monitor {
        private final Executor executor;
        private final Clock clock;
        private final long intervalMillis;
        private Source source;
        private long generation;
        private boolean inFlight;
        private boolean attempted;
        private long lastAttempt;
        private volatile Snapshot snapshot = pending(false);

        public Monitor(Executor executor, Clock clock, long intervalMillis) {
            if (executor == null || clock == null || intervalMillis < 0) {
                throw new IllegalArgumentException("Invalid status monitor");
            }
            this.executor = executor;
            this.clock = clock;
            this.intervalMillis = intervalMillis;
        }

        public Snapshot snapshot() { return snapshot; }

        public void bind(Source connected) {
            if (connected == null) throw new IllegalArgumentException("Missing status source");
            synchronized (this) {
                source = connected;
                generation++;
                inFlight = false;
                attempted = false;
                snapshot = pending(true);
            }
            refresh();
        }

        public synchronized void disconnected(Source died) {
            if (source != died) return;
            source = null;
            generation++;
            inFlight = false;
            attempted = false;
            snapshot = pending(false);
        }

        /** Only schedules work: reading the snapshot and calling this method never issue IPC. */
        public void refresh() {
            final Source requested;
            final long requestedGeneration;
            final long now = clock.elapsedMillis();
            synchronized (this) {
                if (source == null || inFlight
                        || (attempted && now - lastAttempt < intervalMillis)) return;
                requested = source;
                requestedGeneration = generation;
                inFlight = true;
                attempted = true;
                lastAttempt = now;
            }
            try {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        synchronized (Monitor.this) {
                            if (generation != requestedGeneration || source != requested) return;
                        }
                        Snapshot result = read(requested);
                        synchronized (Monitor.this) {
                            if (generation != requestedGeneration || source != requested) return;
                            snapshot = result;
                            inFlight = false;
                        }
                    }
                });
            } catch (RuntimeException error) {
                synchronized (this) {
                    if (generation == requestedGeneration && source == requested) {
                        inFlight = false;
                        attempted = false;
                    }
                }
            }
        }
    }
}
