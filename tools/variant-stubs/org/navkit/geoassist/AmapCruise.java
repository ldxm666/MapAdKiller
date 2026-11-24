package org.navkit.geoassist;

/**
 * Variant stub: 百度 / 腾讯 内置版把整个 {@code mapadkiller} 包丢掉（那是高德适配层），
 * 但共享的 {@code CaptureLog} 在录制采样时会带一行巡航状态，只用到 {@link #diag()}。
 *
 * <p>内置版没有巡航，返回固定文案即可；这个方法的结果只进日志文本，不参与任何判定。
 */
public final class AmapCruise {
    private AmapCruise() {}

    public static String diag() { return "variant=n/a"; }
}
