package io.github.ldxm666.mapclean

import java.util.concurrent.Executor

/** Public libxposed service information, independent of framework-private IPC. */
class FrameworkStatus private constructor() {

    interface Source {
        val apiVersion: Int
        val frameworkName: String?
        val frameworkVersion: String?
        val scope: List<String>?
    }

    interface Clock {
        fun elapsedMillis(): Long
    }

    class Snapshot private constructor(
        @JvmField val connected: Boolean,
        @JvmField val loaded: Boolean,
        @JvmField val apiVersion: Int,
        @JvmField val frameworkName: String,
        @JvmField val frameworkVersion: String,
        @JvmField val scopeKnown: Boolean,
        @JvmField val scope: Set<String>,
        /** Exception type only; a service's exception message can contain private data. */
        @JvmField val scopeError: String
    ) {
        fun frameworkLabel(): String {
            return if (frameworkVersion.isEmpty()) frameworkName
            else "$frameworkName $frameworkVersion"
        }

        companion object {
            internal fun create(
                connected: Boolean, loaded: Boolean, apiVersion: Int,
                frameworkName: String, frameworkVersion: String, scopeKnown: Boolean,
                scope: Set<String>, scopeError: String
            ): Snapshot {
                return Snapshot(
                    connected, loaded, apiVersion, frameworkName, frameworkVersion,
                    scopeKnown, java.util.Collections.unmodifiableSet(java.util.LinkedHashSet(scope)), scopeError
                )
            }
        }
    }

    companion object {
        internal fun pending(connected: Boolean): Snapshot {
            return Snapshot.create(
                connected, false, 0, "Xposed 兼容框架", "", false,
                java.util.Collections.emptySet(), ""
            )
        }

        /** Each independent endpoint may fail without discarding the others. Run off the UI thread. */
        internal fun read(source: Source): Snapshot {
            var api = 0
            var name = "Xposed 兼容框架"
            var version = ""
            var scopeKnown = false
            val scope = java.util.LinkedHashSet<String>()
            var scopeError = ""
            try {
                api = Math.max(0, source.apiVersion)
            } catch (ignored: Throwable) {
            }
            try {
                val value = source.frameworkName
                if (value != null && value.trim().isNotEmpty()) name = value.trim()
            } catch (ignored: Throwable) {
            }
            try {
                val value = source.frameworkVersion
                if (value != null) version = value.trim()
            } catch (ignored: Throwable) {
            }
            try {
                val value = source.scope
                if (value == null) {
                    scopeError = "EmptyReply"
                } else {
                    for (entry in value) {
                        if (entry != null && entry.trim().isNotEmpty()) scope.add(entry.trim())
                    }
                    scopeKnown = true
                }
            } catch (error: Throwable) {
                scopeError = error.javaClass.simpleName
            }
            return Snapshot.create(true, true, api, name, version, scopeKnown, scope, scopeError)
        }
    }

    /**
     * One queued read per interval. Old replies cannot revive a dead or replaced service.
     *
     * <p>**必须留在 `FrameworkStatus` 的直接嵌套位置**：Java 侧写的是
     * `new FrameworkStatus.Monitor(...)`，放进 companion object 会变成
     * `FrameworkStatus$Companion$Monitor`，调用点直接编译不过。
     */
    class Monitor(executor: Executor?, clock: Clock?, intervalMillis: Long) {

        private val executor: Executor
        private val clock: Clock
        private val intervalMillis: Long
        private var source: Source? = null
        private var generation: Long = 0L
        private var inFlight: Boolean = false
        private var attempted: Boolean = false
        private var lastAttempt: Long = 0L

        @Volatile
        private var current: Snapshot = FrameworkStatus.pending(false)

        init {
            if (executor == null || clock == null || intervalMillis < 0) {
                throw IllegalArgumentException("Invalid status monitor")
            }
            this.executor = executor
            this.clock = clock
            this.intervalMillis = intervalMillis
        }

        fun snapshot(): Snapshot = current

        fun bind(connected: Source?) {
            if (connected == null) throw IllegalArgumentException("Missing status source")
            synchronized(this) {
                source = connected
                generation++
                inFlight = false
                attempted = false
                current = FrameworkStatus.pending(true)
            }
            refresh()
        }

        @Synchronized
        fun disconnected(died: Source?) {
            // Java 里是 `source != died` —— 引用比较。Kotlin 的 == 走 equals，
            // 所以这里必须写 ===，否则两个 equals 相等的不同实例会被当成同一个服务。
            if (source !== died) return
            source = null
            generation++
            inFlight = false
            attempted = false
            current = FrameworkStatus.pending(false)
        }

        /** Only schedules work: reading the snapshot and calling this method never issue IPC. */
        fun refresh() {
            var requested: Source? = null
            var requestedGeneration = 0L
            val now = clock.elapsedMillis()
            synchronized(this) {
                if (source == null || inFlight ||
                    (attempted && now - lastAttempt < intervalMillis)
                ) return
                requested = source
                requestedGeneration = generation
                inFlight = true
                attempted = true
                lastAttempt = now
            }
            val requestedSource: Source = requested ?: return
            val requestedGen: Long = requestedGeneration
            try {
                executor.execute(object : Runnable {
                    override fun run() {
                        synchronized(this@Monitor) {
                            if (generation != requestedGen || source !== requestedSource) return
                        }
                        val result = FrameworkStatus.read(requestedSource)
                        synchronized(this@Monitor) {
                            if (generation != requestedGen || source !== requestedSource) return
                            current = result
                            inFlight = false
                        }
                    }
                })
            } catch (error: RuntimeException) {
                synchronized(this) {
                    if (generation == requestedGen && source === requestedSource) {
                        inFlight = false
                        attempted = false
                    }
                }
            }
        }
    }
}