package io.github.ldxm666.mapadkiller;

import java.util.Locale;

/**
 * 实验开关的闸门：用户开关 <b>或</b> 调试系统属性。
 *
 * <h3>为什么需要第二道</h3>
 * {@link Config#K_AMAP_FPS_ON} / {@link Config#K_AMAP_BUILDING_ON} 默认关，这是产品要求，
 * 不能为了验证去动默认值 —— 那样验的就不是发布的那个包了。
 * 但这两个功能的实测（帧率三档、三档缩放截图）必须让 hook 真的装上，于是走本模块里
 * 已有的那条约定：{@code adb shell setprop debug.mapclean.*}。
 * {@code AmapLaunchFast} 的 {@code debug.mapclean.launch_fast} / {@code launch_probe}
 * 用的是同一套写法，这里是第三个用户。
 *
 * <pre>
 *   adb shell setprop debug.mapclean.amap_tune 1   # 强制打开两个实验特性
 *   adb shell setprop debug.mapclean.amap_tune 0   # 回到"只看面板开关"
 * </pre>
 *
 * <p>属性值按 {@code 0/false/off/no} 判假、其余任意非空值判真 —— 与
 * {@code AmapLaunchFast.flag} 逐字一致，避免两处语义分叉。
 *
 * <p>普通用户永远碰不到它：装机后没人会去设这个属性，所以面板开关仍然是唯一的用户入口。
 * 验证跑完我会把属性清掉，并留一张"默认关"的日志作为反向证据。
 */
final class AmapTuneGate {

    /** 一个属性同时管两个实验特性：它们总是一起验，分开反而要多记一个键。 */
    private static final String PROP = "debug.mapclean.amap_tune";

    /**
     * 目标帧率的调试覆盖：{@code debug.mapclean.amap_fps}。
     *
     * <p>存在的理由很具体：60 / 90 / 120 三档要在**同一次地图会话**里测完。
     * {@link AmapFpsCap#target()} 有 1 秒 TTL，所以 setprop 之后一秒内就生效，
     * 不用重启高德。要是每档都重启一次，三次采样的视角就都不一样了 ——
     * 那正是当初把"车跑了 170 米"误读成"楼没了"的同一类错误。
     */
    private static final String PROP_FPS = "debug.mapclean.amap_fps";

    private AmapTuneGate() {}

    /** 该特性是否生效：面板开关打开，或调试属性打开。 */
    static boolean on(String key) {
        try {
            if (Config.visible(key)) return true;
        } catch (Throwable ignored) {
        }
        return forced();
    }

    static boolean forced() {
        String value = prop(PROP);
        if (value == null) return false;
        value = value.trim().toLowerCase(Locale.US);
        if (value.length() == 0) return false;
        return !("0".equals(value) || "false".equals(value) || "off".equals(value)
                || "no".equals(value));
    }

    /**
     * 调试路径的目标帧率。
     *
     * @return 属性给的值；属性缺失或不是正数时返回 {@code fallback}
     */
    static int target(int fallback) {
        String value = prop(PROP_FPS);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Throwable error) {
            return fallback;
        }
    }

    /**
     * {@code android.os.SystemProperties} 是 @hide，公开 SDK 里没有；直接 import 的话
     * {@code javac --release 8 android.jar} 编译不过，只能反射。
     */
    private static String prop(String key) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable error) {
            return null;
        }
    }
}