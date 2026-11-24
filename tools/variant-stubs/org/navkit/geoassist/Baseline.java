package org.navkit.geoassist;

import android.content.Context;

/**
 * 内置版（免 root）用的 Baseline 空实现。
 *
 * <p>为什么需要它：{@code mapclean.CaptureGate} 在**所有**变体里都会编译，而它引用了
 * {@code mapadkiller.Baseline} 的两个方法（{@code hasPendingRecapture} /
 * {@code pendingRecaptureAt}）。内置版把整个 {@code mapadkiller} 包丢掉，
 * 没有这个空实现就直接编译失败 —— 报错是「找不到符号 类 Baseline」。
 *
 * <p>语义：内置版没有「抓取官方配置」这一步（没有 root，也没有 LSPatch 之外的外壳），
 * 所以这里一律返回「没有待办」和「未抓取」。任何调用方拿到的都是最保守的答案，
 * 不会因此多做事，也不会把界面卡在「抓取中」。
 */
public final class Baseline {

    public static final String DIR = "baseline";

    private Baseline() {}

    public static boolean isCapturing() { return false; }

    public static synchronized boolean maybeCapture() { return false; }

    public static boolean requestRecapture() { return false; }

    public static String lastRequestError() { return null; }

    public static boolean isCaptured() { return false; }

    public static int capturedFiles() { return 0; }

    public static long capturedAt() { return 0L; }

    public static boolean hasPendingRecapture() { return false; }

    public static long pendingRecaptureAt() { return 0L; }

    public static String exportBaseline(Context appCtx) { return null; }
}
