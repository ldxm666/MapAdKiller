package io.github.ldxm666.mapadkiller;

/**
 * Variant stub: 百度 / 腾讯 内置版 drop the whole {@code mapadkiller} package (that is 高德's
 * adapter), but the shared 悬浮窗 config code names this class for its backend constants.
 *
 * <p>Only the three constants are required to compile; the vendor variants route every read and
 * write through their own {@code Cfg} instead, so nothing here is ever called.
 */
public final class Config {
    private Config() {}

    public static final int BACKEND_NONE = 0;
    public static final int BACKEND_LOCAL = 1;
    public static final int BACKEND_REMOTE = 2;

    /** 与真实 Config 同名同值：CaptureLog 的配置快照按这个前缀过滤工具槽位键。 */
    public static final String K_TOOL_PREFIX = "tool_";

    /**
     * 日志开关。**真件里它读 {@code debug_log} 配置；本变体里
     * {@code mapadkiller/H.java} 是按文件保留的真件（百度内置版靠它抓日志），
     * 而 {@code H.logv()} 会读这个方法 —— 缺了就是一个编译错。
     *
     * <p>这里返回 {@code false}：本变体没有可读的配置后端（见类注释），
     * 而 {@code logv} 的语义就是「默认不输出启动期嘈音」。
     */
    public static boolean debugLog() {
        return false;
    }

    /**
     * 以下三个是 2.3.0 新增的开关读取器。**本变体里永远返回缺省值**（本变体
     * 把 {@code mapadkiller} 整包拆了，没有可读的配置后端），但必须存在 ——
     * 否则哪天有人把引用它们的类加进 {@code keepFiles}，构建就会突然报
     * 「找不到符号」，而那时候没人会想到根因在一个桩里。
     */
    public static boolean splashSkip() {
        return false;
    }

    public static boolean cruiseOfficial() {
        return false;
    }

    public static Boolean explicit(String key) {
        return null;
    }

    /** 内置版没有可读的配置后端，快照恒为空 —— CaptureLog 只会把它写进日志文本。 */
    public static java.util.Map<String, Object> snapshot() {
        return java.util.Collections.emptyMap();
    }
}