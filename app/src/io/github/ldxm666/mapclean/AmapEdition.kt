package io.github.ldxm666.mapclean

import android.content.Context
import android.content.pm.PackageManager

/**
 * 高德两个发行版的判定。
 *
 * <p>高德的**国内版**和 **Play 版是两次独立构建**：8 个 DEX 全不同、`libamapr.so` 大小
 * 也不同（32,984,120 vs 32,983,424）。混淆名会重排（同一个方法在国内版是 `k84.f()`，
 * 在 Play 版是 `s27.d()`），native 里的 vtable 槽位和 gadget 地址也各是一套。
 *
 * <p>所以「现在是哪个版」不是一个展示用的小标签，它是**决定用哪套锚点的输入**。
 * [nativeVariant] 直接对应 `app/native/amap_buildings.c` 里 `VARIANTS[]` 的 tag 字段。
 *
 * <h3>判定依据（按可靠性排序）</h3>
 * 1. **安装渠道**：`installerPackageName == com.android.vending` 一定是 Play 版。
 *    这条最硬 —— 它由系统写入，改不了。
 * 2. **versionName 的第 3 段**：国内版是 `17.02.0.x`，Play 版是 `17.02.2.x`。
 *    两版共用前两段，差异只在第 3 段。
 * 3. **versionCode 号段**：国内版 = `170200` 这种 `主*10000+次*100` 的排法；
 *    Play 版走 Google 的独立号段（17.02.2.2014 → `170105`），两者不重叠。
 *
 * <p>三条都不命中就返回 [Edition.UNKNOWN]，**不做猜测** —— 猜错会让 native 补丁
 * 打错地址，那是地图错乱级别的后果，比"不知道"严重得多。
 */
object AmapEdition {

    const val PKG_AMAP = "com.autonavi.minimap"
    private const val PLAY_INSTALLER = "com.android.vending"

    enum class Edition {
        /** 国内版：17.02.0.x，versionCode 1702xx */
        DOMESTIC,
        /** Google Play 版：17.02.2.x，versionCode 1701xx */
        PLAY,
        /** 认不出来。**不要**当成任何一个版本来处理。 */
        UNKNOWN,
        /** 没装 */
        ABSENT
    }

    /** 一次判定的结果，带上判定理由，便于日志与界面直接引用。 */
    class Info(
        @JvmField val edition: Edition,
        @JvmField val versionName: String?,
        @JvmField val versionCode: Long,
        @JvmField val installer: String?,
        @JvmField val reason: String
    ) {
        val installed: Boolean get() = edition != Edition.ABSENT

        /** 界面上的短标签。 */
        fun label(): String = when (edition) {
            Edition.DOMESTIC -> "国内版"
            Edition.PLAY -> "Play 版"
            Edition.UNKNOWN -> "版本未知"
            Edition.ABSENT -> "未安装"
        }

        /**
         * 对应 `amap_buildings.c` 里 `VARIANTS[]` 的 tag。
         *
         * <p>返回 null 表示**不要打 native 补丁** —— 认不出就什么都不做，让自检那三关
         * 自然失败并记一行 anchor_mismatch，而不是拿另一个版的地址去试。
         */
        fun nativeVariant(): String? = when (edition) {
            Edition.DOMESTIC -> "17.02.0"
            Edition.PLAY -> "17.02.2"
            else -> null
        }
    }

    /**
     * 读当前安装的高德版本并判定。
     *
     * <p>任何异常都吞掉并返回 [Edition.ABSENT] 或 [Edition.UNKNOWN] —— 这个函数会在
     * 界面刷新时被调，不能因为 PackageManager 抛异常把整页打挂。
     */
    @JvmStatic
    fun detect(c: Context?): Info {
        if (c == null) return Info(Edition.UNKNOWN, null, 0L, null, "no_context")
        var info: android.content.pm.PackageInfo? = null
        try {
            info = c.packageManager.getPackageInfo(PKG_AMAP, 0)
        } catch (t: Throwable) {
            // NameNotFoundException = 没装；其它异常按未安装处理，不用它做判断。
            return Info(Edition.ABSENT, null, 0L, null, "not_installed")
        }
        if (info == null) return Info(Edition.ABSENT, null, 0L, null, "not_installed")

        val name = info.versionName
        val code = if (android.os.Build.VERSION.SDK_INT >= 28)
            info.longVersionCode else info.versionCode.toLong()
        var installer: String? = null
        try {
            installer = c.packageManager.getInstallSourceInfo(PKG_AMAP).installingPackageName
        } catch (t: Throwable) {
            // API 30 以下或 ROM 限制：退回旧接口，再不行就当未知渠道。
            try {
                @Suppress("DEPRECATION")
                installer = c.packageManager.getInstallerPackageName(PKG_AMAP)
            } catch (ignored: Throwable) {
            }
        }

        // 1) 安装渠道最硬
        if (PLAY_INSTALLER == installer) {
            return Info(Edition.PLAY, name, code, installer, "installer=play")
        }

        // 2) versionName 第 3 段：国内 17.02.0.x / Play 17.02.2.x
        if (name != null) {
            val parts = name.split(".")
            if (parts.size >= 3) {
                val third = parts[2].toIntOrNull()
                if (third != null) {
                    if (third >= 2) return Info(Edition.PLAY, name, code, installer, "versionName.seg3=$third")
                    if (third == 0) return Info(Edition.DOMESTIC, name, code, installer, "versionName.seg3=0")
                }
            }
        }

        // 3) versionCode 号段
        if (code >= 170000L && code < 170200L) {
            return Info(Edition.PLAY, name, code, installer, "versionCode=$code")
        }
        if (code >= 170200L && code < 180000L) {
            return Info(Edition.DOMESTIC, name, code, installer, "versionCode=$code")
        }

        return Info(Edition.UNKNOWN, name, code, installer, "no_rule_matched")
    }

    /** 日志行，供 hook 侧与排障直接 grep。 */
    @JvmStatic
    fun describe(info: Info): String =
        "event=amap_edition edition=" + info.edition.name.lowercase() +
                " name=" + (info.versionName ?: "?") +
                " code=" + info.versionCode +
                " installer=" + (info.installer ?: "?") +
                " variant=" + (info.nativeVariant() ?: "none") +
                " reason=" + info.reason
}
