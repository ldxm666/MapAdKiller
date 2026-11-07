package io.github.ldxm666.mapadkiller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature

/**
 * 宿主身份诊断：**签名 DN** + **嵌入/模块模式**。
 *
 * <h3>为什么需要</h3>
 * 用户报「root 用户用了模块后原版高德签名被改」。静态排查已证明模块**不可能**改高德本体
 * （全源码 PackageInstaller / pm install / deletePackage / Runtime.exec / APK 写入 全 0 命中），
 * 真实原因是那位用户装的是 **174.8 MB 的免 root 内置版**（用 `CN=xiawanli` 重新签名、
 * 包名仍是 `com.autonavi.minimap` 的重打包高德），而不是 4.2 MB 的模块。
 *
 * <p>所以这里打一条**一次性**日志，以后支持人员一眼就能看出用户装错了包：
 * <pre>
 * event=host_signer pkg=com.autonavi.minimap dn=CN=xiawanli embedded=true sig=1a2b3c4d5e6f7788
 * event=host_signer pkg=com.autonavi.minimap dn=CN=minimap embedded=false sig=9f8e7d6c5b4a3928
 * </pre>
 * 判据：官方高德 `CN=minimap`；内置版 `CN=xiawanli`。
 *
 * <h3>为什么单独一个类，而不是塞进 `App`</h3>
 * `mapadkiller/App` 在精简构建里会被 `tools/variant-stubs/` 的同名 stub 替换
 * （stub 只有构造器）。往 `App` 加方法会**让变体构建缺符号**。新建一个类不受 stub 影响，
 * 两种构建里都是真实实现。`mapclean/App` 同理（它也有 stub）。
 */
object HostInfo {

    /** 只打一次。 */
    /**
     * 已上报过的**宿主包名**集合（按包名去重，不是全局一次性）。
     *
     * <p>为什么按包名：模块可能被注入到多个进程/多个 App（实测日志里出现过
     * `com.miui.contentcatcher`、`com.google.android.webview`），
     * 早期用单个 AtomicBoolean 会被"别的进程先跑"占位，导致真正的高德进程一次都报不出来。
     */
    private val REPORTED: MutableSet<String> =
            java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    /** 模式检测只算一次。 */
    @Volatile
    private var embedded: Boolean? = null

    // ══════════════════════════════════════════════ 嵌入 / 模块 模式

    /**
     * 当前是不是**免 root 内置（LSPatch）**形态。
     *
     * <p>判据按可靠性排序，任一命中即为 true：
     * <ol>
     *   <li><b>宿主 APK 内存在 `assets/lspatch/config.json`</b> —— 最直接；
     *       只用 [java.util.zip.ZipFile] 读**目录项**，不解压、不写盘；</li>
     *   <li>运行期存在 LSPatch 的类（`org.lsposed.lspatch.loader.LSPApplication` 等）；</li>
     *   <li>`mapclean.App.embedded()` 为 true（面板侧已经判过，反射调用避免编译期依赖）。</li>
     * </ol>
     * 全程 try/catch；任何异常都按"模块模式（false）"处理，并且结果静态缓存，只算一次。
     */
    @JvmStatic
    fun isEmbedded(): Boolean {
        val v = embedded
        if (v != null) return v
        val r: Boolean = try {
            detectEmbedded()
        } catch (t: Throwable) {
            false
        }
        embedded = r
        return r
    }

    private fun detectEmbedded(): Boolean {
        // 1) 宿主 APK 里的 assets/lspatch/config.json（只读 zip 中央目录）
        try {
            val apk = apkPath()
            if (apk != null) {
                val z = java.util.zip.ZipFile(apk)
                try {
                    if (z.getEntry("assets/lspatch/config.json") != null) return true
                } finally {
                    try { z.close() } catch (ignored: Throwable) { }
                }
            }
        } catch (ignored: Throwable) { }

        // 2) LSPatch 运行期类
        val probes = arrayOf(
                "org.lsposed.lspatch.loader.LSPApplication",
                "org.lsposed.lspatch.loader.LSPLoader",
                "org.lsposed.lspatch.share.Constants"
        )
        for (c in probes) {
            try {
                Class.forName(c)
                return true
            } catch (ignored: Throwable) { }
        }

        // 3) 面板侧已判过的结果
        try {
            val a = Class.forName("io.github.ldxm666.mapclean.App")
            val v = a.getMethod("embedded").invoke(null)
            if (v is Boolean && v) return true
        } catch (ignored: Throwable) { }
        return false
    }

    // ══════════════════════════════════════════════ 宿主签名诊断

    /**
     * 打**一次** `event=host_signer`。
     *
     * @param pkgIn 当前进程的包名（由框架回调给出）
     */
    @JvmStatic
    fun reportOnce(pkgIn: String) {
        // ★ 不能在这里先置位：早期（module_loaded 后 ~37ms）宿主 Application 还没建好，
        // hostCtx() 返回 null，若此时就把 REPORTED 置 true，就永远不会重试 —— 实测
        // dn=-/sig=- 就是这么来的（和 r23 的 localPrefs() 是同一个坑）。
        // 先判 Context，拿不到直接返回、**不置位**，等 Application 就绪后的第二次调用。
        try {
            val emb = isEmbedded()
            var dn = "-"
            var sig = "-"
            var pkg = pkgIn
            try {
                val ctx: Context? = Config.hostCtx()
                if (ctx == null) return   // 未就绪，留待下次（不置位）
                // ★ 包名以 Context 为准，不用调用方传进来的值：实测 App.onCreate 传进来的
                // 可能是 contentcatcher / webview（模块也被注入到那些进程），而 Context 才是宿主。
                var real = pkg
                try {
                    val fromCtx = ctx.packageName
                    if (fromCtx != null && fromCtx.length > 0) real = fromCtx
                } catch (ignored: Throwable) { }
                if (REPORTED.contains(real)) return
                pkg = real
                if (ctx != null) {
                    val pm: PackageManager = ctx.packageManager
                    // API 33+ 上 GET_SIGNATURES 已废弃且**返回 null**（本机 targetSdk=35 实测
                    // dn=-/sig=-），必须走 GET_SIGNING_CERTIFICATES + signingInfo。
                    var raw: ByteArray? = null
                    try {
                        val pi28: PackageInfo? = pm.getPackageInfo(pkg,
                                PackageManager.GET_SIGNING_CERTIFICATES)
                        if (pi28 != null && pi28.signingInfo != null) {
                            var arr: Array<Signature>? = pi28.signingInfo.getApkContentsSigners()
                            if (arr == null || arr.size == 0) {
                                arr = pi28.signingInfo.getSigningCertificateHistory()
                            }
                            if (arr != null && arr.size > 0) raw = arr[0].toByteArray()
                        }
                    } catch (ignored: Throwable) { }
                    if (raw == null) {
                        // API < 28 兜底（老系统上 GET_SIGNING_CERTIFICATES 不可用）
                        try {
                            val piOld: PackageInfo? = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                            val sigs = piOld?.signatures
                            if (sigs != null && sigs.size > 0) raw = sigs[0].toByteArray()
                        } catch (ignored: Throwable) { }
                    }
                    if (raw != null) {
                        sig = sha256Head(raw)
                        try {
                            val cert = java.security.cert.CertificateFactory
                                    .getInstance("X.509")
                                    .generateCertificate(
                                            java.io.ByteArrayInputStream(raw)) as java.security.cert.X509Certificate
                            dn = cert.getSubjectX500Principal().getName()
                        } catch (ignored: Throwable) {
                            dn = "?"
                        }
                    }
                }
            } catch (ignored: Throwable) { }
            // ★ 不再输出 dn / sig：LSPatch 会把宿主的 PackageManager 包装成"原版应用"，
            // 于是 getPackageInfo() 返回的是高德**原始**签名（CN=minimap），而不是磁盘上
            // 那个重签名包（CN=xiawanli）。这条日志以前因此具有误导性 —— 内置版用户会
            // 以为自己装的是官方包。真正要回答的问题「用户装的是官方版还是内置版」，
            // 用 embedded 一个字段就够了（判据是宿主 APK 里有没有 assets/lspatch/config.json，
            // 不经过 PackageManager，一直准确）。
            val modeLine = "event=host_mode pkg=" + pkg + " embedded=" + emb +
                    (if (emb) " kind=lspatch-embedded" else " kind=official-or-lsposed")
            // 宿主进程里走 H.log（顺带进「运行日志」）；设置页进程里 H 类初始化会失败
            // （缺 libxposed API），兜底用 SafeLog，别让这一行日志把整个方法打断。
            try {
                H.log(modeLine)
            } catch (t: Throwable) {
                SafeLog.i(MainHook.TAG, modeLine)
            }
            // 只有在真正打出日志之后才记入去重集合：拿不到 Context / 抛异常时不会占位。
            REPORTED.add(pkg)
        } catch (ignored: Throwable) { }
    }

    /** 签名字节的 SHA-256 前 8 字节（16 个 hex），够做指纹对照又不刷屏。 */
    private fun sha256Head(raw: ByteArray): String {
        try {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            val sb = StringBuilder(16)
            var i = 0
            while (i < 8 && i < d.size) {
                val v = d[i].toInt() and 0xff
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
                i++
            }
            return sb.toString()
        } catch (t: Throwable) {
            return "-"
        }
    }

    private fun apkPath(): String? {
        try {
            val ctx: Context = Config.hostCtx() ?: return null
            val ai: ApplicationInfo? = ctx.packageManager
                    .getApplicationInfo(ctx.packageName, 0)
            return ai?.sourceDir
        } catch (t: Throwable) {
            return null
        }
    }
}
