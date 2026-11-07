package io.github.ldxm666.tmapclean

import android.util.Log

import io.github.libxposed.api.XposedInterface

/**
 * 腾讯地图 <b>升级检查 / 升级弹窗</b>拦截 —— 11.7.0 / versionCode 6013 实证。
 *
 * <p><b>默认生效，不设开关</b>：升级弹窗不属于"界面精简"，它是骚扰；
 * 与开屏广告同级处理，恒开。
 *
 * <h3>6013 的升级链路（逐类解析自 base.apk）</h3>
 * <pre>
 *   启动入口：navisdk.a.evp.&lt;init&gt;() → navisdk.a.hgi.a(Context)      ← 6013 新路径，全是混淆名
 *   com.tencent.map.navisdk.a.hgi          ← 真正的升级管理器（混淆名，不挂）
 *       hgi.h()  → Settings.getString("PUSH_SERVICE_UPDATEINFO")
 *                → AppUpgradeInfo.parseJson(String)
 *                → iget version → StringUtil.compareVersion(version, 本机版本)
 *       hgi.i()  → Apollo 配置 "SlideDialog" → AppUpgradeInfo
 *       hgi.j/k(AppUpgradeInfo)            服务端下发的强制/普通升级
 *       hgi.a(Context,AppUpgradeInfo) / b(...) → 新建 SlideDialog
 *       hgi.c(Context,AppUpgradeInfo)          → dismiss AppUpgradeDialog
 *   com.tencent.map.ama.upgrade.AppUpgradeInfo     数据体（isForce / isSlideDialog / title …）
 *   com.tencent.map.ama.upgrade.view.AppUpgradeDialog#show()V        ← 标准升级弹窗
 *   com.tencent.map.ama.upgrade.slidedialog.SlideDialog              ← 图文轮播升级弹窗
 * </pre>
 *
 * <p><b>6011 → 6013 的差异（实测）</b>：旧入口 {@code UpgradeInitTask} 在 6013 里
 * <b>没有任何调用点</b>（{@code --InvokeXref} = 0 条），已退化成死代码；检查改由
 * {@code navisdk.a.evp} 拉起 {@code navisdk.a.hgi}。所以"挂住 UpgradeInitTask 就等于拦住检查"
 * 这个假设在新包上<b>不成立</b> —— 旧名字还在、签名没变，但它永远不会被调用。
 * 这正是"只按名字挂 hook"的典型陷阱。
 *
 * <h3>为什么既压数据又堵弹窗</h3>
 * {@code hgi} 是混淆名，每个构建都换（11.6 的 {@code navisdk.a.gak$12} 到 11.7 就是
 * {@code gas$12}），挂在它上面等于给自己埋一颗下一版必炸的雷。所以取两个稳定锚点：
 * <ol>
 *   <li>{@code AppUpgradeInfo.parseJson} —— 腾讯自有命名、跨版本稳定。放行解析，只把
 *       {@code version} 改写成 {@code 0.0.0}，于是"服务端说有新版本"这件事在任何
 *       比较逻辑里都不成立（见 [defuseUpdateInfo]）。</li>
 *   <li>{@code AppUpgradeDialog.show()} —— 兜底。即使版本号从别的路径进来，
 *       弹窗也不会出现。</li>
 * </ol>
 *
 * <p>{@code SlideDialog} 没有覆写 {@code show()}（它只声明了 {@code a()V}，而 {@code a()V}
 * 是"构建 ViewPager 页面列表"，实证自字节码，不是显示入口），所以它走的是
 * {@code android.app.Dialog#show()}。为了连它一起拦住，这里在 {@code Dialog.show()} 上挂一个
 * <b>按运行时类名过滤</b>的闸门：只有 this 是那两个升级弹窗时才吞，其余对话框原样放行。
 * 这样即使下个版本 {@code SlideDialog} 改了名，只要 {@code AppUpgradeDialog} 还在就仍然有效；
 * 两个类名都不在了，闸门退化成一次 {@code proceed()}，不影响任何东西。
 *
 * <h3>为什么不碰 AboutActivity.updateNew / SettingActivity.updateNew</h3>
 * 那是用户自己在「关于 / 设置」里点"检查更新"，属于用户意图，不该拦。
 * 即使点了，弹窗仍然被本类挡住 —— 检查照做，骚扰不出现。
 */
object TmapUpdate {

    @JvmStatic
    fun install(cl: ClassLoader) {
        blockStartupCheck(cl)
        defuseUpdateInfo(cl)
        blockDialogs(cl)
    }

    /**
     * ① 入参源头：把"服务端说有个新版本"这个字段压平成 {@code 0.0.0}。
     *
     * <p>这是本类真正起作用的一条。{@code hgi.h()} 的字节码是：
     * <pre>
     *   Settings.getString("PUSH_SERVICE_UPDATEINFO")
     *     → AppUpgradeInfo.parseJson(...)
     *     → iget-object AppUpgradeInfo->version
     *     → StringUtil.compareVersion(version, 本机版本)
     * </pre>
     * 注意它<b>不解引用判空</b>（parseJson 之后直接 iget），所以让 parseJson 返回 null
     * 会当场 NPE —— 这条路不能走。改成"放行解析、只把 version 改写"：
     * 返回对象照旧完整，只是永远不比本机新。
     *
     * <p>为什么不用 {@code ""}：{@code StringUtil.compareVersion} 的实现是
     * {@code split("\\.")} + {@code Integer.valueOf(...)}（实证自字节码），
     * 空串会在 {@code Integer.valueOf("")} 抛 {@code NumberFormatException}。
     * {@code "0.0.0"} 是合法版本号，任何真实版本都比它大。
     */
    private fun defuseUpdateInfo(cl: ClassLoader) {
        val info = H.cls(cl, Anchors.UPGRADE_INFO)
        if (info == null) {
            H.log(
                Log.DEBUG, MainHook.TAG,
                "skip upgrade_info (" + Anchors.UPGRADE_INFO + " not in this build)"
            )
            return
        }
        H.hookAll(info, "parseJson", "tmap_update_parse", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (result != null) {
                    val before = H.field(result, "version")
                    if (H.setField(result, "version", "0.0.0")) {
                        Diag.note("tmap_update_version", before.toString() + " -> 0.0.0")
                    }
                }
                return result
            }
        })
    }

    /**
     * ② 启动任务 {@code UpgradeInitTask#run()}。
     *
     * <p><b>6013 实况：这个类是死代码 —— 全包 0 个 invoke 指向它</b>（{@code --InvokeXref}
     * 结果为 0 条）。6011 上它是启动检查的入口，6013 换成了
     * {@code navisdk.a.evp} → {@code navisdk.a.hgi.a(Context)} 这条路。
     * 钩子保留（类还在、签名没变，装上不会有害），但<b>不能把它当作检查已被拦住的证据</b>：
     * 它永远不会 HIT。真正兜底的是 ①（压低版本号）和 ③（弹窗本身）。
     */
    private fun blockStartupCheck(cl: ClassLoader) {
        val task = H.cls(cl, Anchors.UPGRADE_TASK)
        if (task == null) {
            H.log(
                Log.DEBUG, MainHook.TAG,
                "skip upgrade_task (" + Anchors.UPGRADE_TASK + " not in this build)"
            )
            return
        }
        H.hookDeclared(task, "run", "tmap_update_check", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                Diag.hit("tmap_update_check_blocked")
                return null
            }
        })
    }

    /** ③ 两个升级弹窗。 */
    private fun blockDialogs(cl: ClassLoader) {
        val dialog = H.cls(cl, Anchors.UPGRADE_DIALOG)
        if (dialog == null) {
            H.log(
                Log.DEBUG, MainHook.TAG,
                "skip upgrade_dialog (" + Anchors.UPGRADE_DIALOG + " not in this build)"
            )
        } else {
            H.hookDeclared(dialog, "show", "tmap_update_dialog", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    Diag.hit("tmap_update_dialog_blocked")
                    return null
                }
            })
        }

        // 轮播版升级弹窗走 Dialog.show()，按运行时类名过滤（见类注释）。
        val androidDialog = H.cls(cl, "android.app.Dialog") ?: return
        H.hookSig(androidDialog, "show", "tmap_update_dialog_gate",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.getThisObject()
                    val name: String? = if (self == null) null else self.javaClass.name
                    if (Anchors.UPGRADE_DIALOG == name
                        || Anchors.UPGRADE_SLIDE_DIALOG == name
                    ) {
                        Diag.hit("tmap_update_dialog_blocked")
                        return null
                    }
                    return chain.proceed()
                }
            })
    }
}
