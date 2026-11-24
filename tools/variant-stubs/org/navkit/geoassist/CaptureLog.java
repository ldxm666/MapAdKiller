package org.navkit.geoassist;

import android.content.Context;

/**
 * 内置版（免 root）用的 CaptureLog 空实现。
 *
 * <p>为什么需要它：{@code mapclean.FloatingPanel} 在**所有**变体里都会编译，悬浮球面板上有
 * 一个「抓取后 10 分钟」的日志录制按钮，它直接调用 {@code CaptureLog}。内置版把整个
 * {@code mapadkiller} 包丢掉，没有这个空实现就编译失败（报「找不到符号 类 CaptureLog」）。
 *
 * <p>语义：内置版没有抓取流程，这个按钮应当表现为「未在录制」——
 * {@link #running()} 恒 false，{@link #toggleLive(Context)} 恒 false（点不动，面板会显示
 * 原始文案），{@link #dumpRecent(Context)} 返回 null（调用方据此显示「没有日志」）。
 */
public final class CaptureLog {

    private CaptureLog() {}

    public static boolean running() { return false; }

    public static int remainSeconds() { return 0; }

    public static String currentName() { return null; }

    public static boolean toggleLive(Context ctx) { return false; }

    public static String dumpRecent(Context ctx) { return null; }

    public static String recentText(int max) { return null; }

    public static int recentCount() { return 0; }

    public static void note(int priority, String tag, String msg) { }
}
