package io.github.ldxm666.mapclean

import android.util.Log

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * v2.0.0 合并版的**唯一 Xposed 入口**（`META-INF/xposed/java_init.list` 只有这一行）。
 *
 * 两个模块的 hook 代码原样保留，各自仍用自己的：
 *   - 日志 TAG（BMapClean / MapAdKiller）
 *   - 配置 group（bmapclean_config / amap_enhancer_config）
 *   - 静态工具类（H / Config / Spec …）
 * 这里只做分发 + 顶层日志，**不做任何合并改写**，把回归风险压到最低。
 *
 * 进程视角（免 root 内置版：只并入高德）：
 *   com.autonavi.minimap → mapadkiller（去广告 + 首页/我的页 UI）+ 悬浮窗入口
 *
 * 三家都要收到回调，**转发一次都不能少**：一个 APK 只能有一个
 * `META-INF/xposed/java_init.list` 入口，另外两半自己没有入口。
 * 免 root 精简构建会把百度/腾讯的代码换成 no-op 桩类
 * （见 tools/build_variant.py 的 STUBS），所以这里的转发在两种构建里都能编译 ——
 * 曾经为了省事直接把转发删掉，结果完整模块的百度也跟着失效了。
 */
class MainHook : XposedModule() {

    @Volatile
    private var loadedProcess: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        loadedProcess = param.processName
        Log.i(TAG, "event=module_loaded process=" + loadedProcess +
            " api=" + getApiVersion() + " framework=" + getFrameworkName())
        try {
            io.github.ldxm666.mapadkiller.MainHook.onModuleLoaded(this, param)
        } catch (t: Throwable) {
            Log.w(TAG, "amap module_loaded failed", t)
        }
        // 百度那一半同样要收到 onModuleLoaded：它的 H.module 必须在挂钩之前被赋值，
        // 否则每一次 hook 都会以 "miss <id> (module null)" 失败 —— 装了等于没装。
        try {
            io.github.ldxm666.bmapclean.MainHook.onModuleLoaded(this, param)
        } catch (t: Throwable) {
            Log.w(TAG, "baidu module_loaded failed", t)
        }
        // 腾讯那一半同样要收到 onModuleLoaded：tmapclean.H.module 必须在挂钩之前被赋值，
        // 否则每一次 hook 都会以 "miss <id> (module null)" 失败 —— 装了等于没装。
        try {
            io.github.ldxm666.tmapclean.MainHook.onModuleLoaded(this, param)
        } catch (t: Throwable) {
            Log.w(TAG, "tencent module_loaded failed", t)
        }
        // 百度标签资源属于完整版；精简变体没有这个类，所以用反射调用，
        // 避免为了一个可选特性在 hook 入口里引出一个编译期硬依赖。
        //
        // 注意：NativeLabelAssets 并不是百度专用的 —— 它按进程名分流，
        // "com.autonavi.minimap" 走 map=1，是**高德地图标注开关唯一的实现**：
        // 它驱动 libmapclean_labels.so 在原生层改写 map_assets/style_*X_Main
        // 样式 protobuf，只去掉兴趣点、保留道路/水域/地名。
        // 瘦身构建曾经把它当成「百度专用」删掉，导致高德地图标注开关彻底失效。
        try {
            Class.forName("io.github.ldxm666.mapclean.NativeLabelAssets")
                .getMethod("start", String::class.java).invoke(null, loadedProcess)
        } catch (missing: ClassNotFoundException) {
            // 只有"类根本不存在"才是真正的变体不支持 —— 瘦身/免 root 构建会把
            // NativeLabelAssets 换成桩，这里静默跳过是对的。
            Log.i(TAG, "native label assets unavailable in this variant")
        } catch (t: Throwable) {
            // 类在、但启动失败：so 没加载起来 / 方法签名变了 / configure 内部抛。
            // 以前这种也打上面那句 INFO，等于把"高德地图标注整条链路已经死了"伪装成
            // "这个变体没有这个功能"，排障方向直接被带偏、日志里还查不到堆栈。
            Log.w(TAG, "native label start failed", t)
        }
    }

    /**
     * Retry the native label library once the host's {@code ApplicationInfo} exists.
     *
     * <p>At module-load time there is no ApplicationInfo, so the only paths available are the
     * linker search path and the module APK. In an integrated LSPatch build neither can supply
     * the library: the patcher drops the {@code lib/} level from the nested module APK, and the
     * module class loader never searches the host's own native library directory. The patched
     * host APK therefore also carries a copy at {@code lib/<abi>/libmapclean_labels.so}, which
     * the platform extracts -- this is where that copy is picked up.
     */
    private fun retryNativeLabels(param: XposedModuleInterface.PackageReadyParam) {
        try {
            var directory: String? = null
            // ⚠ 不要写 param.getApplicationInfo()：编译 stub 与运行时都没有这个方法
            // （同一族接口实测缺方法，见 stub-src 里的说明），调用即 NoSuchMethodError。
            // 这条路径本身只是"再试一次"，反射拿不到就保持 null，让
            // NativeLabelAssets 走它自己的其余候选目录。
            try {
                val m = param.javaClass.getMethod("getApplicationInfo")
                val info = m.invoke(param)
                if (info is android.content.pm.ApplicationInfo) {
                    directory = info.nativeLibraryDir
                }
            } catch (ignored: Throwable) {
                // 方法不存在：directory 保持 null。
            }
            Class.forName("io.github.ldxm666.mapclean.NativeLabelAssets")
                .getMethod("start", String::class.java, String::class.java)
                .invoke(null, loadedProcess, directory)
        } catch (t: Throwable) {
            Log.i(TAG, "native label library not re-checked: $t")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName ?: return

        retryNativeLabels(param)

        EmbeddedSettings.install(this, param, loadedProcess)

        // 免 root 内置版：把悬浮窗入口挂进地图自己的窗口。
        // 只在集成模式下有意义（LSPatch 把模块并进宿主），普通 LSPosed 安装时
        // 模块自带设置页，不需要也不应该在别人的窗口里画东西。
        //
        // 注意：这里不能用 App.app() 做宿主探测 —— onPackageReady 时宿主 Application
        // 还没创建，拿到的是 null。框架名是此时唯一可用的信号。
        if (EmbeddedSettingsPolicy.integrated(getFrameworkName(), null)) {
            FloatingHooks.install(this, pkg, true)
        }

        // 百度内置版：长按底部「我的」那一格 ≈750ms 弹出面板 —— 这是「关掉悬浮球之后」
        // 唯一的找回入口（百度内置版没有 MainActivity、也没有 tab 入口）。
        // 内部自带三道门控：模块模式跳过 / 非百度进程跳过 / 锚点缺失跳过，任何一道不满足都
        // 一条 hook 都不装。
        //
        // ★ 2.3.1：在调用前再过滤一次。BaiduMineEntry 内部的门控本身是安全的（不满足就
        //   一条 hook 都不装），但每个进程都仍要付一次 Vendor.host() 状态机 + 一行 Log.i：
        //   腾讯地图 5 个进程 ⇒ 每次冷启动 5 行
        //   `baidu_mine_entry_skipped reason=not_baidu host=com.tencent.map`。
        //   排障时这些噪音还会盖住真正有用的行；一个字符串比较就能全省掉。
        if (io.github.ldxm666.mapadkiller.MainHook.PKG_BMAP == pkg) {
            try {
                BaiduMineEntry.install(this, pkg, param.classLoader)
            } catch (t: Throwable) {
                Log.w(TAG, "baidu mine entry install failed", t)
            }
        }

        // 高德 / 百度 / 腾讯：去广告基线（含 SDK 自动检索）。
        // 本变体（免 root 内置版）只并入高德，但仍保留 mapadkiller 自己的基线分发，
        // 它内部已经按包名分别处理三家，不会重复安装。
        try {
            io.github.ldxm666.mapadkiller.MainHook.onPackageReady(this, param)
        } catch (t: Throwable) {
            Log.e(TAG, "amap install failed", t)
        }
    }

    companion object {
        const val TAG = "MapClean"
    }
}