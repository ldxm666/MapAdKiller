package io.github.ldxm666.mapadkiller;

/**
 * Variant stub: 百度 / 腾讯 内置版把整个 {@code mapadkiller} 包丢掉，共享的悬浮窗代码又要问
 * 「我是不是内置版」，所以这个类必须存在。
 *
 * <h3>2.3.0：两个变化</h3>
 *
 * <ol>
 *   <li><b>不再给高德变体用。</b>以前它在 {@code SHARED_STUBS} 里，连 amap target 也顶掉真类；
 *       而 amap 的 {@code mapadkiller} 包是**保留**的，真件
 *       （{@code HostInfo.java:62}，按宿主 APK 里的 {@code assets/lspatch/config.json} 判定）
 *       本来就能正确回答。硬编码成 true 的后果：高德进程里
 *       {@code FloatingHooks.install} / {@code FloatingController.attach} /
 *       {@code AmapHooks} 的 {@code mine_entry} 全部无条件为真，
 *       于是多挂 {@code AmapMineEntry} 的四个钩子 + 一个吞触摸的 resume 钩子 ——
 *       同时把「少挂这些钩子也能跑」的对照组删掉了，高德白屏根本没得二分。
 *       现在 {@code tools/build_variant.py} 只给 baidu / tmap 写这个文件。</li>
 *   <li><b>不再是常量。</b>保留真实判据，只是判不出来时 fail-open 回 true。</li>
 * </ol>
 */
public final class HostInfo {
    /** 判定只做一次（真件也是静态缓存）。三态：null = 未判，TRUE/FALSE = 已判。 */
    private static volatile Boolean embedded;

    private HostInfo() {}

    /**
     * 内置版判定。
     *
     * <p>判据按可靠性排序，任一命中即为 true：
     * <ol>
     *   <li>LSPatch 运行期类（{@code org.lsposed.lspatch.loader.LSPApplication} 等）——
     *       integrated 补丁必然把 loader 打进宿主 APK，最硬的信号，且模块加载时就能用；</li>
     *   <li>{@code mapclean.App.embedded()} —— 面板侧 {@code attachEmbedded} 之后为 true；</li>
     *   <li>宿主 {@code Assets} 里能打开 {@code lspatch/config.json} —— 与真件同一条判据，
     *       需要 Application，最晚可用。</li>
     * </ol>
     *
     * <p><b>判不出来时返回 true（fail-open）</b>：这个类只在免 root 内置版里存在，
     * 极端情况下宁可多挂悬浮球 —— 返回 false 会让内置版**唯一**的设置入口整条消失，
     * 那才是真的没法用。任何异常都不抛。
     */
    public static boolean isEmbedded() {
        Boolean cached = embedded;
        if (cached != null) return cached.booleanValue();
        Boolean verdict;
        try {
            verdict = detect();
        } catch (Throwable ignored) {
            verdict = null;
        }
        if (verdict == null) {
            // fail-open 但**不缓存**：这个时机（模块加载）宿主 Application 还没建，
            // 下一次调用（attachBaseContext 之后）能用真判据给出确定答案。
            return true;
        }
        embedded = verdict;
        return verdict.booleanValue();
    }

    /** @return TRUE / FALSE = 判出来了；null = 这个时机还判不出来。 */
    private static Boolean detect() {
        // 1) LSPatch 运行期类。反射用模块自己的 ClassLoader，父加载器就是宿主，
        //    integrated 补丁把 loader 打在宿主 dex 里，所以这里必然能查到。
        String[] probes = {
                "org.lsposed.lspatch.loader.LSPApplication",
                "org.lsposed.lspatch.loader.LSPLoader",
                "org.lsposed.lspatch.share.Constants"
        };
        for (String name : probes) {
            try {
                Class.forName(name);
                return Boolean.TRUE;
            } catch (Throwable ignored) {
            }
        }

        // 2) 面板侧已经判过的结果（attachEmbedded 之后才有）。
        try {
            Class<?> app = Class.forName("io.github.ldxm666.mapclean.App");
            Object v = app.getMethod("embedded").invoke(null);
            if (v instanceof Boolean && ((Boolean) v).booleanValue()) return Boolean.TRUE;
            // 拿到了 App 但还没 attach：继续往下用 Assets 判。
        } catch (Throwable ignored) {
        }

        // 3) 宿主 assets/lspatch/config.json。Application 还没建时 app() 为 null ⇒ 判不出来。
        try {
            Class<?> app = Class.forName("io.github.ldxm666.mapclean.App");
            Object instance = app.getMethod("app").invoke(null);
            if (instance instanceof android.content.Context) {
                android.content.Context ctx = (android.content.Context) instance;
                java.io.InputStream in = null;
                try {
                    in = ctx.getAssets().open("lspatch/config.json");
                    return Boolean.TRUE;
                } finally {
                    if (in != null) {
                        try { in.close(); } catch (Throwable ignored) { }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;   // 这个时机判不出来 —— 交给 fail-open，且**不缓存**
    }

    /**
     * 真件在这里打 {@code event=host_signer}（宿主签名 DN + 嵌入/模块模式），用来识别
     * 「装错包」。变体里做不了签名摘要（要读 PackageManager 的签名，而这段代码只在
     * 百度/腾讯内置版存在），所以只留一条可 grep 的模式行 —— 排障时问「这个进程走的是哪条
     * 分支」, {@code adb logcat | grep host_mode} 一行就能回答。
     *
     * <p>保持方法存在：{@code mapclean.App.onCreate} 用反射调它
     * （{@code getMethod("reportOnce", String.class)}），缺了会静默跳过 —— 但签名一旦漂移，
     * 下一个人拿到的是 {@code NoSuchMethodException} 而不是编译错误。stub 与真件的公开面
     * 必须一致，这是本目录唯一的硬规矩。
     */
    public static void reportOnce(String pkg) {
        try {
            android.util.Log.i("MapAdKiller", "event=host_mode embedded=" + isEmbedded()
                    + " pkg=" + pkg + " variant=stub");
        } catch (Throwable ignored) {
        }
    }
}