package io.github.ldxm666.tmapclean

import android.os.SystemClock
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Coalesced diagnostics. No formatting or broadcast IPC on a layout/binding thread. */
object Diag {
    private val LOCK = Any()
    private val COUNTS = LinkedHashMap<String, IntArray>()
    private val NOTES = LinkedHashMap<String, String>()
    private val WORKER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        object : ThreadFactory {
            override fun newThread(task: Runnable): Thread {
                val thread = Thread(task, "TMapClean-report")
                thread.isDaemon = true
                return thread
            }
        })
    private var dirty = false
    private var pending = false
    private var lastAt: Long = 0L

    @JvmStatic
    fun count(key: String, total: Int, hidden: Int) {
        synchronized(LOCK) {
            val count = COUNTS[key]
            if (count != null && count[0] == total && count[1] == hidden) return
            if (count == null) COUNTS.put(key, intArrayOf(total, hidden))
            else {
                count[0] = total
                count[1] = hidden
            }
            changedLocked()
        }
    }

    @JvmStatic
    fun note(key: String, value: String?) {
        if (value == null) return
        synchronized(LOCK) {
            if (value == NOTES[key]) return
            NOTES.put(key, value)
            changedLocked()
        }
    }

    // A hit confirms a path is alive; per-call counters add no useful status information.
    @JvmStatic
    fun hit(key: String) {
        synchronized(LOCK) {
            if (COUNTS.containsKey(key)) return
            COUNTS.put(key, intArrayOf(1, 0))
            changedLocked()
        }
    }

    private fun changedLocked() {
        dirty = true
        if (pending) return
        pending = true
        val delay = Math.max(400L, 1600L - (SystemClock.elapsedRealtime() - lastAt))
        WORKER.schedule(object : Runnable {
            override fun run() {
                emit()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @JvmStatic
    fun flush() {
        synchronized(LOCK) {
            if (dirty) changedLocked()
        }
    }

    private fun emit() {
        val detail: String
        synchronized(LOCK) {
            pending = false
            if (!dirty) return
            dirty = false
            val out = StringBuilder(256)
            for (entry in COUNTS.entries) {
                if (out.length > 0) out.append(" | ")
                out.append(entry.key).append(' ').append(entry.value[0])
                    .append('/').append(entry.value[1])
            }
            for (entry in NOTES.entries) {
                if (out.length > 0) out.append(" | ")
                out.append(entry.key).append('=').append(entry.value)
            }
            detail = out.toString()
            lastAt = SystemClock.elapsedRealtime()
        }
        if (detail.length > 0) Report.send(null, "scan", detail, false)
    }
}
