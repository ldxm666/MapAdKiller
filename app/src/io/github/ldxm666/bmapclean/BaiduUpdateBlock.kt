package io.github.ldxm666.bmapclean

import io.github.libxposed.api.XposedInterface

/**
 * 版本更新链路的默认拦截：<b>更新检查不发、更新弹窗不弹</b>。
 *
 * <h3>为什么单独成类</h3>
 * 这是模块里唯一一处<b>没有开关</b>的清理项 —— 用户明确要求「默认加入无需开关」。
 * 所以它不进 {@code Cfg}、不读 {@code baidu.*} 的任何键，也不会因为某个默认值写错
 * 而悄悄失效。想确认它是否生效，只能看日志和界面。
 *
 * <h3>落点怎么定的（22.1.20 / 1665，18 dex 全表反编译 + xref）</h3>
 * 版本更新不是一条链，是「检查 → 回调分发 → 弹窗」三段：
 *
 * <pre>
 *  VersionUpdateController.N(ZZ)      ← 检查入口（自动检查 + 设置页手动「检查更新」）
 *    └─ ClientUpdater.checkUpdate(cb) ← 真正发请求的 SDK 方法（见下：不挂它）
 *  VersionUpdateController.u0()       ← 回调分发（服务端结果 → 选哪条提示分支）
 *    ├─ j0()   强制升级弹窗：标题「提示」，按钮「版本升级」「退出程序」，setCancelable(false)
 *    ├─ l0()   可升级推荐弹窗（转 {@code common.app.startup.e}）
 *    └─ k0()   「已是最新版本」toast
 *  VersionUpdateController.x0()       ← 「有新版本」主弹窗：全类唯一 new BMAlertDialog + show()
 * </pre>
 *
 * <p><b>N(ZZ) 的调用者只有 4 处</b>（全表 xref，不是抽样）：{@code MapsActivity#onEventMainThread}、
 * {@code entry.parse.newopenapi.command.d#b}、{@code com.baidu.drivemode.HiCarMapsActivity}
 * 与 {@code com.baidu.hicar.HiCarMapsActivity} 的 {@code onEventMainThread}。四条全是
 * 「版本更新事件」，没有一条和普通网络请求或搜索结果有关 —— 这是「不误伤」的实证依据。
 *
 * <h3>为什么不挂 ClientUpdater.checkUpdate</h3>
 * {@code ClientUpdater.checkUpdate} 的调用者也只有 2 处：{@code VersionUpdateController.N}
 * 和 {@code ClientUpdater.appLaunchedCheckUpdate}（后者在本包内<b>没有任何调用者</b>）。
 * 也就是说堵住 N 之后，SDK 侧已经没有任何活着的入口 —— 再往下多挂一层，收益是 0，
 * 换来的是「万一百度别处也用这个 SDK 做别的检查」的误伤面。**只 no-op 宿主侧入口，
 * 不碰 SDK 的方法**，是这里刻意的取舍。
 *
 * <h3>为什么堵了 N 还要堵 x0 / j0 / l0</h3>
 * 「检查」和「显示」之间隔着服务端下发 + 事件总线 + {@code ClientUpdateInfo} 落盘缓存。
 * 只堵 N，遇到「上次的检查结果还在缓存里、本次冷启动直接显示」时会变成
 * 「请求没了、弹窗还在」—— 比不拦更难看。四个出口一起封，链路才是断的。
 *
 * <h3>不碰什么</h3>
 * <ul>
 *   <li>{@code com.baidu.clientupdate.*} —— SDK 本体，见上。</li>
 *   <li>{@code Component3.update.*} —— 那是<b>离线数据 / dex 补丁</b>的组件更新，
 *       堵了地图数据就不更新了，属于误伤。</li>
 *   <li>{@code common.app.startup.d}（{@code OfflineNavUpgradeTip}）—— 类名像「升级」，
 *       实际是<b>离线导航数据</b>的下载提示，跟版本号无关。</li>
 * </ul>
 *
 * <p>类或方法找不到时 {@link H#hook} 自己记 miss 并返回 false，不抛 ——
 * 百度下次改版最多是这一层静默失效，绝不会把别的 hook 一起带走。
 */
class BaiduUpdateBlock private constructor() {

    companion object {

        /** 版本更新控制器。包名 + 类名从未变过，是本类唯一的语义锚点。 */
        private const val CONTROLLER = "com.baidu.baidumaps.common.upgrade.VersionUpdateController"

        /** 已打过日志的拦截点（进程内去重，避免每次检查更新都刷屏）。 */
        private val sLogged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * void 方法统一吞掉：调用方丢弃返回值，走自己的「没有更新」分支。
         *
         * <p>第一次命中打一行日志。没有这行，用户看到「没弹更新窗」根本分不清是模块
         * 拦住了还是本来就没更新 —— 而这两件事的排查方向完全相反。
         */
        @JvmStatic
        private fun block(what: String): XposedInterface.Hooker {
            return object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (sLogged.add(what)) H.log("baidu update blocked " + what)
                    return null
                }
            }
        }

        @JvmStatic
        fun install(cl: ClassLoader) {
            val controller = H.cls(cl, CONTROLLER)
            // 检查入口。参数表 (ZZ)：z = 是否用户手动触发，z2 = 是否静默检查。
            // 两个 boolean 都必须显式给类型 —— getDeclaredMethod 不做装箱匹配，
            // 少写一个 boolean.class 就是 NoSuchMethodException，然后是一条看不见的 miss。
            BaiduHooks.hook(controller, "N", "baidu_update_check",
                    block("VersionUpdateController.N"), Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            // 主弹窗：x0() 是 updateVersion，全类唯一 new BMAlertDialog + show()。
            BaiduHooks.hook(controller, "x0", "baidu_update_dialog",
                    block("VersionUpdateController.x0"))
            // 强制升级弹窗（不可取消，「版本升级」/「退出程序」）。
            BaiduHooks.hook(controller, "j0", "baidu_update_force",
                    block("VersionUpdateController.j0"))
            // 可升级推荐弹窗。
            BaiduHooks.hook(controller, "l0", "baidu_update_recommend",
                    block("VersionUpdateController.l0"))

            H.log("baidu_update block installed controller=" + (controller != null))
        }
    }
}