package io.github.ldxm666.mapclean

/** 版本与对外链接的**唯一事实源**（设置页 / 更新检查 / 诊断都用这里）。 */
object Version {

    /** 模块版本：与 module.prop、AndroidManifest 保持一致 */
    const val NAME = "2.3.3"
    /** Full label shown in UI / module manager; the git tag uses the same string. */
    const val LABEL = "2.3.3"
    const val CODE = 2303

    const val REPO = "https://github.com/ldxm666/MapAdKiller"
    const val RELEASES = "$REPO/releases/latest"
    const val ISSUES = "$REPO/issues"
    /** TG **通知频道**（发版公告 / 重要通知）。 */
    const val TG = "https://t.me/MapAdkillerr"
    /** TG **聊天群**（反馈 / 交流 / 提问）。 */
    const val TG_CHAT = "https://t.me/MapAdkiller"

    /**
     * 「最佳适配应用版本」：本模块的 hook 点是在这些目标版本上真机验证过的。
     * 设置页会把它和**本机实际安装版本**并排显示（一致=✔，不一致=✘）。
     */
    @JvmField
    val TARGETS: Array<Array<String>> = arrayOf(
        arrayOf("com.baidu.BaiduMap", "百度地图", "22.1.20"),
        arrayOf("com.autonavi.minimap", "高德地图", "17.02.0.2010"),
        arrayOf("com.tencent.map", "腾讯地图", "11.7.0"),
    )

    /**
     * 更新检查的多个源，**按实测可用性排序**（国内网络、无 VPN，逐个 curl
     * `raw.githubusercontent.com/.../app/META-INF/xposed/module.prop` 验证）。
     *
     * **为什么优先 module.prop 而不是 API JSON**：它只有 ~300 字节、没有速率限制、
     * 是纯文本（一行 `version=`），解析比 JSON 稳。GitHub 官方 API 放在**最后**：
     * 国内直连不通，只有挂代理才通，放前面只会让首查多等几秒超时。
     *
     * 实测结论：
     * - 通：gh-proxy.com / gh.llkk.cc / ghfast.top / ghproxy.net / cdn.jsdelivr.net / fastly.jsdelivr.net
     * - 死（已删除，留着只会拖慢首查）：ghproxy.cc / hub.gitmirror.com / ghp.ci / raw.kkgithub.com
     *
     * 任一个拿到版本号即止；全部失败 → 界面显示「未获取」，不弹错误框。
     */
    @JvmField
    val UPDATE_SOURCES: Array<String> = arrayOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/ldxm666/MapAdKiller/master/app/META-INF/xposed/module.prop",
        "https://gh.llkk.cc/https://raw.githubusercontent.com/ldxm666/MapAdKiller/master/app/META-INF/xposed/module.prop",
        "https://ghfast.top/https://raw.githubusercontent.com/ldxm666/MapAdKiller/master/app/META-INF/xposed/module.prop",
        "https://ghproxy.net/https://raw.githubusercontent.com/ldxm666/MapAdKiller/master/app/META-INF/xposed/module.prop",
        "https://cdn.jsdelivr.net/gh/ldxm666/MapAdKiller@master/app/META-INF/xposed/module.prop",
        "https://fastly.jsdelivr.net/gh/ldxm666/MapAdKiller@master/app/META-INF/xposed/module.prop",
        "https://gh-proxy.com/https://api.github.com/repos/ldxm666/MapAdKiller/releases/latest",
        "https://api.github.com/repos/ldxm666/MapAdKiller/releases/latest",
    )

    /**
     * 下载页镜像（**给没有代理的用户**）：点「镜像下载」时按顺序试第一个能用的。
     * 这里只做「打开网页」的兜底，不做下载器。
     *
     * 顺序与 [UPDATE_SOURCES] 一致：实测可用的反代在前，官方 GitHub 放最后
     * （国内直连打不开）。已死的 ghproxy.cc / hub.gitmirror.com 不再保留。
     */
    @JvmField
    val DOWNLOAD_MIRRORS: Array<String> = arrayOf(
        "https://gh-proxy.com/https://github.com/ldxm666/MapAdKiller/releases/latest",
        "https://gh.llkk.cc/https://github.com/ldxm666/MapAdKiller/releases/latest",
        "https://ghfast.top/https://github.com/ldxm666/MapAdKiller/releases/latest",
        "https://ghproxy.net/https://github.com/ldxm666/MapAdKiller/releases/latest",
        "https://github.com/ldxm666/MapAdKiller/releases/latest",
    )
}
