package io.github.ldxm666.mapclean;

/** 版本与对外链接的**唯一事实源**（设置页 / 更新检查 / 诊断都用这里）。 */
public final class Version {

    private Version() {}

    /** 模块版本：与 module.prop、AndroidManifest 保持一致 */
    public static final String NAME = "2.0.2";
    public static final int CODE = 202;

    public static final String REPO = "https://github.com/ldxm666/MapAdKiller";
    public static final String RELEASES = REPO + "/releases/latest";
    public static final String ISSUES = REPO + "/issues";
    /** Telegram 群（来自 MapAdKiller v1.1.0 的「联系作者」） */
    public static final String TG = "https://t.me/+2rqisPe5tJxjNTk1";

    /**
     * 「最佳适配应用版本」：本模块的 hook 点是在这些目标版本上真机验证过的。
     * 设置页会把它和**本机实际安装版本**并排显示（一致=✔，不一致=✘）。
     */
    public static final String[][] TARGETS = {
            {"com.baidu.BaiduMap", "百度地图", "22.0.0"},
            {"com.autonavi.minimap", "高德地图", "17.00.0.2005"},
            {"com.tencent.map", "腾讯地图", "11.6.0"},
    };

    /**
     * 更新检查的多个源，**按实测可用性排序**（2026-09-30 逐条 curl 验证）：
     *  1) gh-proxy.com 反代 GitHub API —— 国内直连可用 ✔（HTTP 200，返回完整 JSON）
     *  2) jsDelivr CDN 取仓库里的 module.prop —— 国内可用 ✔（纯文本，最好解析）
     *  3) jsDelivr 备用节点 fastly
     *  4) GitHub 官方 API —— 有代理/海外网络时最快
     *  5) gh-proxy 反代 raw.githubusercontent
     * 已淘汰：api.kkgithub.com、raw.gitmirror.com（DNS 解析失败）、ghproxy.net（403）。
     * 任一个拿到版本号即止；全部失败 → 界面显示「未获取」，不弹错误框。
     */
    public static final String[] UPDATE_SOURCES = {
            "https://gh-proxy.com/https://api.github.com/repos/ldxm666/MapAdKiller/releases/latest",
            "https://cdn.jsdelivr.net/gh/ldxm666/MapAdKiller@master/app/META-INF/xposed/module.prop",
            "https://fastly.jsdelivr.net/gh/ldxm666/MapAdKiller@master/app/META-INF/xposed/module.prop",
            "https://api.github.com/repos/ldxm666/MapAdKiller/releases/latest",
            "https://gh-proxy.com/https://raw.githubusercontent.com/ldxm666/MapAdKiller/master/app/META-INF/xposed/module.prop",
    };

    /**
     * 下载页镜像（**给没有代理的用户**）：点「镜像下载」时按顺序试第一个能用的。
     * 这里只做「打开网页」的兜底，不做下载器。
     */
    public static final String[] DOWNLOAD_MIRRORS = {
            "https://gh-proxy.com/https://github.com/ldxm666/MapAdKiller/releases/latest",
            "https://ghproxy.cc/https://github.com/ldxm666/MapAdKiller/releases/latest",
            "https://github.com/ldxm666/MapAdKiller/releases/latest",
    };
}
