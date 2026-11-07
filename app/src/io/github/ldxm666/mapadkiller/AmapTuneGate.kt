package io.github.ldxm666.mapadkiller

import java.util.Locale

/**
 * 实验开关的闸门：用户开关 <b>或</b> 调试系统属性。
 *
 * <h3>为什么需要第二道</h3>
 * [Config.K_AMAP_FPS_ON] / [Config.K_AMAP_BUILDING_ON] 默认关，这是产品要求，
 * 不能为了验证去动默认值 —— 那样验的就不是发布的那个包了。
 * 但这两个功能的实测（帧率三档、三档缩放截图）必须让 hook 真的装上，于是走本模块里
 * 已有的那条约定：`adb shell setprop debug.mapclean.*`。
 * `AmapLaunchFast` 的 `debug.mapclean.launch_fast` / `launch_probe`
 * 用的是同一套写法，这里是第三个用户。
 *
 * <pre>
 *   adb shell setprop debug.mapclean.amap_tune 1   # 强制打开两个实验特性
 *   adb shell setprop debug.mapclean.amap_tune 0   # 回到"只看面板开关"
 * </pre>
 *
 * <p>属性值按 `0/false/off/no` 判假、其余任意非空值判真 —— 与
 * `AmapLaunchFast.flag` 逐字一致，避免两处语义分叉。
 *
 * <p>普通用户永远碰不到它：装机后没人会去设这个属性，所以面板开关仍然是唯一的用户入口。
 * 验证跑完我会把属性清掉，并留一张"默认关"的日志作为反向证据。
 */
object AmapTuneGate {

    /** 一个属性同时管两个实验特性：它们总是一起验，分开反而要多记一个键。 */
    private const val PROP = "debug.mapclean.amap_tune"

    /**
     * 目标帧率的调试覆盖：`debug.mapclean.amap_fps`。
     *
     * <p>存在的理由很具体：60 / 90 / 120 三档要在**同一次地图会话**里测完。
     * [AmapFpsCap.target] 有 1 秒 TTL，所以 setprop 之后一秒内就生效，
     * 不用重启高德。要是每档都重启一次，三次采样的视角就都不一样了 ——
     * 那正是当初把"车跑了 170 米"误读成"楼没了"的同一类错误。
     */
    private const val PROP_FPS = "debug.mapclean.amap_fps"

    /** 该特性是否生效：面板开关打开，或调试属性打开。 */
    @JvmStatic
    fun on(key: String): Boolean {
        try {
            if (Config.visible(key)) return true
        } catch (ignored: Throwable) {
        }
        return forced()
    }

    @JvmStatic
    fun forced(): Boolean {
        var value = prop(PROP) ?: return false
        value = value.trim().lowercase(Locale.US)
        if (value.isEmpty()) return false
        return !(value == "0" || value == "false" || value == "off"
                || value == "no")
    }

    /**
     * 调试路径的目标帧率。
     *
     * @return 属性给的值；属性缺失或不是正数时返回 `fallback`
     */
    @JvmStatic
    fun target(fallback: Int): Int {
        val value = prop(PROP_FPS) ?: return fallback
        try {
            val parsed = Integer.parseInt(value.trim())
            return if (parsed > 0) parsed else fallback
        } catch (error: Throwable) {
            return fallback
        }
    }

    /**
     * `android.os.SystemProperties` 是 @hide，公开 SDK 里没有；直接 import 的话
     * `javac --release 8 android.jar` 编译不过，只能反射。
     */
    private fun prop(key: String): String? {
        try {
            val type = Class.forName("android.os.SystemProperties")
            val value = type.getMethod("get", String::class.java).invoke(null, key)
            return value as? String
        } catch (error: Throwable) {
            return null
        }
    }
}
