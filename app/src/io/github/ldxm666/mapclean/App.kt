package io.github.ldxm666.mapclean

import android.app.Application
import android.content.Context
import android.util.Log

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 合并版（v2.0.0）里**唯一真正的 Application**。
 *
 * 原来 BMapClean 与 MapAdKiller 各自有一个 `App extends Application`，一个 APK 只能有一个，
 * 所以这里做转发：本类负责向 LSPosed 注册监听，拿到服务后喂给静态持有者，
 * 设置页代码因此一行都不用改（仍然读自己的 RemotePreferences group）：
 *   - io.github.ldxm666.mapadkiller.App → group `amap_enhancer_config`
 *
 * 免 root 内置版（v2.2.0 起）里只并入高德部分，因此不再转发百度侧。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "MapCleanApp"

        @Volatile
        private var self: Application? = null

        private val status = FrameworkStatus.Monitor(
            Executors.newSingleThreadExecutor(object : ThreadFactory {
                override fun newThread(task: Runnable): Thread {
                    val worker = Thread(task, "MapClean-framework-status")
                    worker.isDaemon = true
                    return worker
                }
            }),
            object : FrameworkStatus.Clock {
                override fun elapsedMillis(): Long = android.os.SystemClock.elapsedRealtime()
            },
            5000L
        )

        @Volatile
        private var boundService: XposedService? = null

        private var boundStatusSource: FrameworkStatus.Source? = null
        private var listenerRegistered: Boolean = false

        @Volatile
        private var embeddedMode: Boolean = false

        /**
         * 防重入标记：worker 正在跑的时候不重复起线程。
         *
         * <p>**不是**「本进程只做一次」的标记 —— 那个语义由 {@code DefaultConfig} 的
         * {@code _defaults_applied} 键保证。这里跑完就放开，是为了让"第一次因为服务刚起、
         * 资源还读不到而失败"的那次能够在后续的服务绑定里再试一次；否则一次失败就等于
         * 整批内置默认配置在本进程内永久不生效，用户看到的是"装了模块但全是默认值"。
         */
        private var defaultsRequested: Boolean = false

        private val embeddedListener: XposedServiceHelper.OnServiceListener =
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    bindService(service)
                }

                override fun onServiceDied(service: XposedService) {
                    diedService(service)
                }
            }

        @JvmStatic
        fun frameworkStatus(): FrameworkStatus.Snapshot = status.snapshot()

        @JvmStatic
        fun refreshFrameworkStatus() {
            status.refresh()
        }

        @JvmStatic
        fun embedded(): Boolean = embeddedMode

        @JvmStatic
        internal fun hasEmbeddedService(): Boolean = boundService != null

        /**
         * The framework service this process is bound to, or {@code null}.
         *
         * <p>Package-private on purpose: it exists so [FloatingConfig] can read and write
         * the orb's own int-valued appearance keys without naming a vendor package (the other
         * three holders live in packages that {@code tools/build_variant.py} drops from the
         * shipped APK). Callers must handle {@code null} -- the bind is asynchronous.
         */
        @JvmStatic
        internal fun service(): XposedService? = boundService

        /** LSPatch delivers its writable Binder after modules load, before the host Application. */
        @JvmStatic
        internal fun listenEmbedded() {
            registerListener(embeddedListener)
        }

        /** Reuse the attached host Application; never construct an Application with an unset base. */
        @JvmStatic
        @Synchronized
        fun attachEmbedded(host: Application?) {
            if (host == null || !EmbeddedSettings.enabledFor(host.packageName)) {
                throw IllegalStateException("Embedded settings require the LSPatch host")
            }
            // Java 这里是 `self == host` —— 引用比较。Kotlin 的 == 走 equals，
            // Application 没覆写 equals 时行为一样，但语义必须写对：这是同一个实例的判断。
            if (self === host && embeddedMode && listenerRegistered) return
            self = host
            embeddedMode = true
            // ★ 2.3.0：把宿主 Application 直接交给 HostPrefs —— 与写入路径同源。
            //   必须在 applyDefaultsOnce() 之前：否则首次默认配置会落到 LSPatch 的 remote 库
            //   （真机证据：同一次安装，12:43 那次写进了 shared_prefs/tmapclean_config.xml，
            //   12:44 那次落进了 databases/lspatch-xposed-remote.db —— 两次走的是两条 Context 路）。
            HostPrefs.remember(host)
            io.github.ldxm666.mapadkiller.App.attach(host)
            // In an integrated build this method -- not onCreate -- is what runs, because the host
            // Application is the map's own, never the module's. So every vendor holder has to be
            // attached here too, or that vendor's orb has no service to write through.
            io.github.ldxm666.bmapclean.App.attach(host)
            io.github.ldxm666.tmapclean.App.attach(host)
            val service = boundService
            if (service != null) {
                try {
                    io.github.ldxm666.mapadkiller.App.onBind(service)
                } catch (ignored: Throwable) {
                }
                try {
                    io.github.ldxm666.bmapclean.App.onBind(service)
                } catch (ignored: Throwable) {
                }
                try {
                    io.github.ldxm666.tmapclean.App.onBind(service)
                } catch (ignored: Throwable) {
                }
            }
            registerListener(embeddedListener)
            // 免 root 内置模式下服务可能先到、宿主 Application 后到：这条路径同样要把默认配置
            // 应用一次，否则 attachEmbedded 之后就没有第二次机会了（标记键保证不会重复应用）。
            //
            // ★ 2.3.0：**不再要求 service != null**。LSPatch 内置版的服务通道本来就起不来，
            //   而默认配置在那一形态下的正确落点是宿主本地 prefs（见 DefaultConfig.groupPrefs）。
            //   以前这个 if 让它一次都没跑过 —— 真机日志「no framework service yet」就是它。
            applyDefaultsOnce()
            // 服务器公告：宿主 Application 已就绪，注册 Activity 观察者并在 3 秒后查一次。
            // 内置版这条路径不会经过 mapclean.App.onCreate（跑的是地图自己的 Application），
            // 所以这里是**唯一**的启动挂点。
            try {
                Announce.onAppStart(host)
            } catch (ignored: Throwable) {
            }
        }

        @Synchronized
        private fun registerListener(listener: XposedServiceHelper.OnServiceListener) {
            if (listenerRegistered) return
            try {
                XposedServiceHelper.registerListener(listener)
                listenerRegistered = true
                Log.i(TAG, "registerListener ok")
            } catch (t: Throwable) {
                Log.e(TAG, "registerListener failed", t)
            }
        }

        @JvmStatic
        fun app(): Application? = self

        @JvmStatic
        fun ctx(): Context? = self

        @Synchronized
        private fun bindService(s: XposedService) {
            Log.i(TAG, "onServiceBind ok")
            // Grep-able form for a verification run: this line plus `event=cfg_write_ok via=svc`
            // is the whole proof that the framework write path is alive.
            Log.i(TAG, "event=svc_bind ok")
            boundService = s
            val source = ServiceStatusSource(s)
            boundStatusSource = source
            status.bind(source)
            try {
                io.github.ldxm666.mapadkiller.App.onBind(s)
            } catch (t: Throwable) {
                Log.w(TAG, "amap bind", t)
            }
            // 百度组（bmapclean_config）的读写全靠这一句。少了它，设置页里百度那一页的每个开关
            // 都会写失败并弹「LSPosed 服务未连接」—— 用户看到的就是这个。
            try {
                io.github.ldxm666.bmapclean.App.onBind(s)
            } catch (t: Throwable) {
                Log.w(TAG, "baidu bind", t)
            }
            // 腾讯组（tmapclean_config）的读写全靠这一句。
            try {
                io.github.ldxm666.tmapclean.App.onBind(s)
            } catch (t: Throwable) {
                Log.w(TAG, "tencent bind", t)
            }
            // 服务刚可用：重判一次悬浮窗总开关。首次读发生在服务未绑定时，会回落到默认值（内置版 = 开），
            // 用户看到的就是「明明关掉了，重开 App 又出现」。
            try {
                FloatingController.refreshEnabled()
            } catch (ignored: Throwable) {
            }
            // 每天最多一次的后台自动检查：只要用户今天开过一次高德，当天就会查
            // （24h 节流在 UpdateChecker 内部，失败也计时，不会每次启动都重试）。
            try {
                val ctx = self
                if (ctx != null) UpdateChecker.maybeAutoCheck(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "auto update check failed to start", t)
            }
            // 自带默认配置：服务可用之后才有写入口（写配置必须走 XposedService），所以第一次
            // 应用挂在这里；标记键存在时内部直接跳过，不会覆盖用户改过的配置。
            applyDefaultsOnce()
        }

        /**
         * 全新安装后自动应用一次模块内置的默认配置。
         *
         * <p>只在服务可用之后调用（见 [bindService] 与 [attachEmbedded]）。
         * 真正的判断与写入在 [DefaultConfig]：标记键 {@code _defaults_applied} 存在即跳过。
         * 放到后台线程是因为 {@code commit()} 是同步落盘，不能占住主线程。
         *
         * <p>标记只在 worker 运行期间有效，跑完就放开（见字段注释）。放开是安全的：
         * 重复进入时 {@code DefaultConfig} 的标记会把整组跳过，代价只是一次 JSON 读取。
         */
        @JvmStatic
        fun applyDefaultsOnce() {
            synchronized(App::class.java) {
                if (defaultsRequested) return
                defaultsRequested = true
            }
            try {
                val worker = Thread(object : Runnable {
                    override fun run() {
                        try {
                            DefaultConfig.applyOnce()
                        } catch (t: Throwable) {
                            // applyOnce 内部已经把异常吞掉了，这里只是兜住"万一"。
                            Log.w(TAG, "defaults apply failed", t)
                        } finally {
                            // 关键：不在这里放开，一次失败就等于本进程永不重试 ——
                            // 内置默认配置整批不生效，而用户完全看不出是"没应用"还是"应用错了"。
                            synchronized(App::class.java) { defaultsRequested = false }
                        }
                    }
                }, "MapClean-defaults")
                worker.isDaemon = true
                worker.start()
            } catch (t: Throwable) {
                // 线程都没起来（OOM / 线程数上限）：立刻放开，别把这一次也算成"做过了"。
                synchronized(App::class.java) { defaultsRequested = false }
                Log.w(TAG, "defaults worker failed to start", t)
            }
        }

        @Synchronized
        private fun diedService(s: XposedService) {
            Log.w(TAG, "onServiceDied")
            if (boundService === s) {
                status.disconnected(boundStatusSource)
                boundStatusSource = null
                boundService = null
            }
            try {
                io.github.ldxm666.mapadkiller.App.onDied(s)
            } catch (ignored: Throwable) {
            }
            try {
                io.github.ldxm666.bmapclean.App.onDied(s)
            } catch (ignored: Throwable) {
            }
            try {
                io.github.ldxm666.tmapclean.App.onDied(s)
            } catch (ignored: Throwable) {
            }
        }
    }

    private class ServiceStatusSource(private val service: XposedService) : FrameworkStatus.Source {
        override val apiVersion: Int get() = service.apiVersion
        override val frameworkName: String? get() = service.frameworkName
        override val frameworkVersion: String? get() = service.frameworkVersion
        override val scope: List<String>? get() = service.scope
    }

    override fun onCreate() {
        super.onCreate()
        self = this
        io.github.ldxm666.mapadkiller.App.attach(this)
        // 百度那一半同样要拿到 Application：它的诊断/回报读取走的是自己的 SharedPreferences，
        // 少了这一步 bmapclean.App.app() 恒为 null，「最近一次规则执行」「累计回报」永远是空。
        io.github.ldxm666.bmapclean.App.attach(this)
        // 腾讯那一半同理：tmapclean.App.app() 为 null 时，它的诊断读取与 token 生成全失效。
        io.github.ldxm666.tmapclean.App.attach(this)
        registerListener(embeddedListener)
        // 宿主 Application 此时一定已就绪 —— 补一次签名/模式诊断。
        // 早期（hook 安装时）那次调用拿不到 Context 会直接返回且不置位，这里才是真正能拿到 DN 的时机。
        // 反射调用：mapadkiller 在百度/腾讯变体里会被 DROP，不能有编译期依赖。
        try {
            Class.forName("io.github.ldxm666.mapadkiller.HostInfo")
                .getMethod("reportOnce", String::class.java)
                .invoke(null, packageName)
        } catch (ignored: Throwable) {
        }
        // 服务器公告：3 秒后查一次（避开冷启动），并注册 Activity 观察者决定弹窗依附谁。
        // 模块版（LSPosed）走的就是这条路径 —— 模块 App 自己就是那个宿主进程。
        try {
            Announce.onAppStart(this)
        } catch (ignored: Throwable) {
        }
    }

    override fun onServiceBind(s: XposedService) {
        bindService(s)
    }

    override fun onServiceDied(s: XposedService) {
        diedService(s)
    }
}