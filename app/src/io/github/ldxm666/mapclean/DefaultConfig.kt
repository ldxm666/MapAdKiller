package io.github.ldxm666.mapclean

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Log
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 自带默认配置：模块内置一份 JSON，**全新安装后自动应用一次**。
 *
 * <h3>配置来源</h3>
 * `res/raw/default_config.json` —— 与模块「更多 → 导出 JSON 配置」产出的格式完全一致：
 * <pre>
 * { "_app": "...", "_ver": 2,
 *   "groups": { "amap_enhancer_config": { "tab_探索": false, ... }, ... } }
 * </pre>
 * 值只有 `true` / `false` / `null` 三种，其中 <b>`null` 表示
 * 「未设置、保持缺省」</b>：应用时必须跳过，绝不能写成 `false`（写 false 会变成
 * 一条真实的「已关闭」记录，缺省语义就丢了）。
 *
 * <h3>为什么放在 res/raw 而不是 assets</h3>
 * 两条构建管线都只把 `app/res/` 交给 aapt2（`app/build.ps1` 与
 * `tools/build_variant.py` 的 `build_slim_module`）；`app/assets/` 两边
 * 都不会被打进 APK。放在 `res/raw` 是**唯一**能让「模块模式」与「免 root 内置模式」
 * 都真的带上这份 JSON 的位置，而且不必改动任何构建脚本。
 *
 * <h3>只应用一次，但可以按版本补缺</h3>
 * 用 `_defaults_applied` 记录「做过」，用 `_defaults_rev` 记录「做的是哪一版」。
 * 两者都不存在 = 全新安装 → 应用；版本低于 JSON 的 `_rev` = 老用户升级 → 只补
 * <b>absent</b> 键（[write] 对已存在的键一律跳过），用户显式改过的值绝不覆盖。
 *
 * <h3>为什么需要版本号</h3>
 * 2.3.0 的腾讯组漏了 `tab_*` / `ui_seek_chips` 等键（JSON 里是 `null`，
 * 而 `null` 的语义是「不写」），干净安装于是「所有键 absent ⇒ 兜底 true ⇒ 什么都没精简」。
 * 只改 JSON 不够：老用户 `_defaults_applied` 已置位，永远轮不到新键落盘。
 * 版本门控让这一批新默认值能补进去，同时因为「只填 absent」，不会把用户手动开/关的值改回去。
 *
 * <h3>按组分流</h3>
 * <ul>
 *   <li><b>模块模式</b>（LSPosed）：三个厂商组都存在，逐个应用。</li>
 *   <li><b>免 root 内置模式</b>（LSPatch）：宿主进程里只有它自己那一家的组，只应用那一组，
 *       其它组静默跳过 —— 不报错、不卡住。</li>
 * </ul>
 * 判定用宿主包名（[hostPackage]），它与 `EmbeddedSettingsPolicy.isHostPackage`
 * 同一份名单，但在 `tools/build_variant.py` 的桩替换之后依然成立。
 *
 * <h3>与 ConfigBackup 的关系</h3>
 * 刻意不复用 `ConfigBackup`：那份实现被 `tools/build_variant.py` 在免 root
 * 变体里整个丢掉（`DROP_MAPCLEAN_FILES`），依赖它会让精简构建编译不过。这里的
 * 解析只做这一件事，并且比导入更严格 —— 只接受布尔值，其它类型一律跳过并记日志。
 */
class DefaultConfig private constructor() {

    /** 一次应用的结果，用于日志与设置页提示。 */
    class Result(@JvmField var applied: Int, @JvmField var skipped: Int) {

        @JvmField
        val groups: MutableSet<String> = LinkedHashSet()

        @JvmField
        val failures: MutableSet<String> = LinkedHashSet()

        override fun toString(): String {
            return ("applied=" + applied + " skipped=" + skipped
                    + " groups=" + groups + " failures=" + failures)
        }
    }

    companion object {

        private const val TAG = "MapCleanDefaults"

        /** 内置 JSON 的资源名，对应 `res/raw/default_config.json`。 */
        const val ASSET = "default_config"

        /** 应用标记。存在即表示这份默认配置已经落地过一次。 */
        const val KEY_APPLIED = "_defaults_applied"

        /**
         * 默认配置的**版本号**标记（2.3.1 新增）。
         *
         * <p>[KEY_APPLIED] 只能表达「做过」，不能表达「做的是哪一版」。腾讯那份 JSON 在
         * 2.3.0 里漏了 `tab_*` / `ui_seek_chips` 等键（值为 `null` ⇒ 从未落盘），
         * 于是干净安装等于「全部走缺省 true」—— 底栏四个 tab、首页 chip 行全部保留。
         * 要修就得让**已经装过旧版**的用户也能补上这些新键，但绝不能覆盖用户显式改过的值。
         *
         * <p>所以引入版本门控：JSON 顶层带 `"_rev": 2`，组内 `_defaults_rev` 小于它时
         * 重新走一遍 [write]。`write` 的规则是「键已存在就跳过」，因此这次重放
         * <b>只填 absent 键</b>，用户显式开/关过的键原样保留。写完把 `_defaults_rev`
         * 落到同一个 prefs 上，后续启动直接跳过 —— 幂等，中途被杀下次继续。
         */
        const val KEY_REV = "_defaults_rev"

        /** 旧版 JSON 没有 `_rev`；把它当成第 1 版，任何带版本号的配置都比它新。 */
        private const val REV_LEGACY = 1

        /**
         * 三份厂商配置组名。
         *
         * <p>刻意写成字面量而不是引用 `bmapclean.Cfg.GROUP` / `tmapclean.Cfg.GROUP`：
         * `tools/build_variant.py` 在精简构建里会读编译出来的 DEX，发现被丢掉的厂商包
         * 还有类型描述符残留就直接让构建失败（`banned` 检查）。引用一个外部类的常量会
         * 把 `Lio/github/ldxm666/bmapclean/Cfg;` 带进产物，从而触发那条检查；纯字符串
         * 常量不会。三份名单必须与各厂商 `Cfg.GROUP` / `Config.PREF_GROUP` 保持一致。
         */
        private const val GROUP_AMAP = "amap_enhancer_config"
        private const val GROUP_BAIDU = "bmapclean_config"
        private const val GROUP_TMAP = "tmapclean_config"

        /** 模块自己的界面配置组（桌面图标开关）。它是本地 SharedPreferences，不是远端组。 */
        private const val GROUP_UI = "mak_ui"

        /** 组 → 拥有它的地图包名。免 root 内置模式里只有匹配的那一组会被应用。 */
        private val VENDOR_OF: MutableMap<String, String> = LinkedHashMap()

        init {
            VENDOR_OF[GROUP_AMAP] = EmbeddedSettingsPolicy.AMAP
            VENDOR_OF[GROUP_BAIDU] = EmbeddedSettingsPolicy.BAIDU
            VENDOR_OF[GROUP_TMAP] = EmbeddedSettingsPolicy.TENCENT
        }

        // ------------------------------------------------------------------ 首次应用

        /**
         * 服务可用之后调用一次：全新安装（标记不存在）时应用内置默认配置，否则什么都不做。
         *
         * <p>调用点见 `mapclean.App#bindService` 与 `mapclean.App#attachEmbedded`：
         * 写配置必须走 [XposedService.getRemotePreferences]，而服务只有在悬浮球
         * 附着之后才存在，所以这里绝不能在服务为 null 时硬写。
         */
        @JvmStatic
        fun applyOnce() {
            try {
                apply(false, null)
            } catch (t: Throwable) {
                Log.w(TAG, "applyOnce failed: " + t)
            }
        }

        /**
         * 手动再应用一次（设置页入口）。跳过版本门控，但仍遵守「已存在的键不覆盖」——
         * 它补齐的是缺失项，不是把用户配置重置成默认值。
         *
         * <p>与首启的唯一区别是无条件应用：首启会看 `_defaults_applied` / `_defaults_rev`
         * 标记，这里不看。
         * `mak_ui`（桌面图标，本地 SharedPreferences）只在给出 `context` 且不是
         * 免 root 内置宿主时才写 —— 内置宿主里那是地图 App 自己的偏好文件，不该碰。
         *
         * @param context 可空；给出时在模块模式下连 `mak_ui` 一起恢复。
         * @return 写入的键数；没有可用服务或资源时 `groups` 为空。
         */
        @JvmStatic
        fun apply(context: Context?): Result {
            return apply(true, context)
        }

        private fun apply(force: Boolean, context: Context?): Result {
            val service = service()
            // ★ 2.3.0：宿主包名必须先算，它决定「没有框架服务能不能继续」。
            //   免 root 内置版（LSPatch）里服务通道本来就起不来，而 hook 读的是**宿主本地**文件，
            //   本地后端完全可用。以前这里 service==null 就直接 return，于是
            //   `_defaults_applied` 永远落不下去、内置默认配置从来没生效过一次
            //   （真机日志：`MapCleanDefaults: no framework service yet; defaults not applied`）。
            val host = hostPackage(context)
            val embedded = EmbeddedSettingsPolicy.isHostPackage(host)
            // ★ host 还没定案时**绝不能往下走**：这时候既分不清自己跑在哪家地图里，
            //   也判断不出该走本地还是 remote。真机日志（修复中）：
            //   `defaults applied: applied=140 groups=[amap, bmapclean, tmap] host=null embedded=false`
            //   —— 一次就把三家的默认值全灌进了 LSPatch 的 remote 库。调用点会重试，等就是了。
            if (host == null) {
                Log.i(TAG, "host unknown yet; defaults deferred")
                return Result(0, 0)
            }
            if (service == null && !embedded) {
                Log.i(TAG, "no framework service yet; defaults not applied")
                return Result(0, 0)
            }

            val json: String
            try {
                json = readBundledJson(context)
            } catch (t: Throwable) {
                Log.w(TAG, "bundled default config unreadable: " + t)
                return Result(0, 0)
            }

            val groups = parse(json)
            if (groups.isEmpty()) {
                Log.w(TAG, "bundled default config carries no group")
                return Result(0, 0)
            }
            val rev = parseRev(json)

            val result = Result(0, 0)
            for (entry in groups.entries) {
                val group = entry.key
                if (GROUP_UI == group) continue      // 本地界面项，见下方单独处理
                val owner = VENDOR_OF[group]
                if (owner == null) {
                    Log.i(TAG, "skip unknown group " + group)
                    continue
                }
                // 免 root 内置模式：宿主只有自己那一家的组，其它组根本不存在 —— 静默跳过。
                if (embedded && owner != host) {
                    Log.i(TAG, "skip " + group + " (embedded host is " + host + ")")
                    continue
                }
                try {
                    applyGroup(service, group, entry.value, force, embedded, rev, result)
                } catch (t: Throwable) {
                    // 个别组失败不影响其余组。
                    result.failures.add(group)
                    Log.w(TAG, "group " + group + " failed: " + t)
                }
            }

            if (context != null && !embedded) {
                val ui = groups[GROUP_UI]
                if (ui != null) {
                    try {
                        applyUiGroup(context, ui, force, rev, result)
                    } catch (t: Throwable) {
                        result.failures.add(GROUP_UI)
                        Log.w(TAG, "group " + GROUP_UI + " failed: " + t)
                    }
                }
            }

            invalidate(host)
            Log.i(
                TAG, "defaults " + (if (force) "re-applied" else "applied") + ": " + result
                        + " host=" + host + " embedded=" + embedded
            )
            return result
        }

        // ------------------------------------------------------------------ 单组应用

        /**
         * 应用一个配置组。
         *
         * <p>`force=false` 时只在「标记不存在」或「已应用的版本低于当前 JSON 的 _rev」时进入。
         * 后一种情况就是老用户升级：[write] 只补 absent 键，用户改过的键一个都不碰。
         */
        private fun applyGroup(
            service: XposedService?, group: String,
            values: MutableMap<String, Any?>, force: Boolean, embedded: Boolean, rev: Int,
            result: Result
        ) {
            val prefs = groupPrefs(service, group, embedded)
            if (prefs == null) {
                result.failures.add(group)
                Log.i(TAG, "group " + group + " unavailable")
                return
            }
            if (!force && prefs.contains(KEY_APPLIED) && revOf(prefs) >= rev) {
                Log.i(
                    TAG, "skip " + group + ": " + KEY_APPLIED + " already set, rev="
                            + revOf(prefs) + " >= " + rev
                )
                return
            }
            val written = write(prefs, values, result, group, rev)
            if (written == 0) {
                Log.i(TAG, "group " + group + " had nothing to write (rev=" + rev + ")")
                return
            }
            result.applied += written
            result.groups.add(group)
        }

        /**
         * 这一组该写进哪个 prefs。
         *
         * <p>★ 2.3.0：<b>内置版必须写宿主本地</b>。框架 remote 在 LSPatch 上既写不进去、
         * hook 侧也读不到（`bmapclean.Cfg.prefs()` 在内置版里解析的就是宿主本地那个文件），
         * 把默认值和 `_defaults_applied` 写进 remote，等于写进一个没人看的抽屉 ——
         * 每次启动都会重新判定「全新安装」。
         *
         * <p>本地后端拿不到（宿主 Context 还没创建）才退回 remote；模块版行为不变。
         */
        private fun groupPrefs(service: XposedService?, group: String, embedded: Boolean): SharedPreferences? {
            if (embedded) {
                try {
                    val local = HostPrefs.local(group)
                    if (local != null) return local
                } catch (t: Throwable) {
                    Log.i(TAG, "local prefs unavailable for " + group + ": " + t)
                }
            }
            if (service == null) return null
            try {
                return service.getRemotePreferences(group)
            } catch (t: Throwable) {
                // 该组在这个宿主里不存在（免 root 内置模式的其它厂商组），静默跳过。
                Log.i(TAG, "group " + group + " unavailable: " + t)
                return null
            }
        }

        /**
         * 模块自己的界面组（桌面图标）。
         *
         * <p>只写值，不在这里改组件状态：`mapadkiller.App.setHideIcon` 在免 root 精简
         * 变体里是桩（没有这个方法），而 `tools/build_variant.py` 会据此拒绝构建。
         * 设置页的「导入配置」本来就会在应用之后刷新桌面入口，这里保持一致。
         */
        private fun applyUiGroup(
            context: Context, values: MutableMap<String, Any?>,
            force: Boolean, rev: Int, result: Result
        ) {
            val prefs = context.getSharedPreferences(GROUP_UI, Context.MODE_PRIVATE)
            if (!force && prefs.contains(KEY_APPLIED) && revOf(prefs) >= rev) {
                Log.i(
                    TAG, "skip " + GROUP_UI + ": " + KEY_APPLIED + " already set, rev="
                            + revOf(prefs) + " >= " + rev
                )
                return
            }
            val written = write(prefs, values, result, GROUP_UI, rev)
            if (written == 0) return
            result.applied += written
            result.groups.add(GROUP_UI)
        }

        /**
         * 把一个组的值写进 SharedPreferences。
         *
         * <p>规则：`null` 跳过（= 保持缺省，绝不写 false）；非布尔值跳过并记日志；
         * 布尔值用一次 `commit()` 批量落盘（`apply()` 是异步的，用户装完立刻
         * 杀进程会丢）。标记键在成功落盘后一并写入，保证「只做一次」。
         *
         * <p>★ 2.3.1：`_defaults_rev` 与 `_defaults_applied` 一起落盘。
         * <b>键已存在就跳过</b>这条规则没有变 —— 版本迁移因此只补 absent 键，用户显式改过的
         * 值原样保留。即使一次迁移只写了一半就被杀，下次 `rev` 仍小于 JSON 版本，
         * 会继续把剩下的 absent 键补齐，已写的键照旧跳过 ⇒ 幂等可续。
         */
        private fun write(
            prefs: SharedPreferences, values: MutableMap<String, Any?>,
            result: Result, group: String, rev: Int
        ): Int {
            var written = 0
            var present = 0
            try {
                val editor = prefs.edit()
                for (entry in values.entries) {
                    val value = entry.value
                    if (value == null) {                 // 未设置 → 保持缺省
                        result.skipped++
                        continue
                    }
                    if (value !is Boolean) {             // 只写布尔值
                        result.skipped++
                        Log.w(
                            TAG, "skip " + group + "/" + entry.key
                                    + ": not a boolean (" + value.javaClass.simpleName + ")"
                        )
                        continue
                    }
                    // ★★ 2.3.0（用户反馈「重启后配置没保存」的根因）：
                    // **默认值绝不能覆盖已存在的键**。
                    //
                    // 真机取证：`bmapclean_config.xml` 里有 44 个布尔键，
                    // 但**没有** `_defaults_applied` 标记 ⇒ [applyOnce] 每次启动都判成
                    // 「全新安装」⇒ 重新写一遍默认值 ⇒ 用户关掉的开关被改回 true。
                    // （标记丢失的原因是它被写进了 LSPatch 那个不持久的 remote 库。）
                    //
                    // 所以这里不管标记在不在，只要键已经有值，就跳过
                    // —— 「默认」的语义就是「键不存在时才用」。这样即使标记再次丢失，
                    // 用户的选择也不会被进程重启抹掉。
                    if (prefs.contains(entry.key)) {
                        result.skipped++
                        present++
                        continue
                    }
                    editor.putBoolean(entry.key, value)
                    written++
                }
                // 标记写到与数据**同一个** prefs 上（这里就是传进来的那个），
                // 避免「数据在本地、标记在 remote」这种两家分居。
                //
                // 即使 written==0 也要落标记：老用户升级上来时键全在（上面整段跳过），
                // 不落标记就会每次启动重解析一遍 JSON；版本迁移时更要落 _rev，
                // 否则每次启动都会重跑一遍迁移。
                val needStamp = !prefs.contains(KEY_APPLIED) || revOf(prefs) < rev
                if (written == 0 && !needStamp) {
                    Log.i(
                        TAG, "skip stamp " + group + ": applied + rev=" + revOf(prefs)
                                + " already current (" + present + " keys present)"
                    )
                    return 0
                }
                editor.putBoolean(KEY_APPLIED, true)
                editor.putInt(KEY_REV, rev)
                if (!editor.commit()) {
                    result.failures.add(group)
                    Log.w(TAG, "commit failed for group " + group)
                    return 0
                }
                if (written == 0) {
                    Log.i(
                        TAG, "stamp " + group + ": " + KEY_APPLIED + " + " + KEY_REV + "=" + rev
                                + " (all " + present + " keys already present)"
                    )
                }
                return written
            } catch (t: Throwable) {
                result.failures.add(group)
                Log.w(TAG, "write failed for group " + group + ": " + t)
                return 0
            }
        }

        // ------------------------------------------------------------------ 解析

        /** 只取 `groups` 里的对象，键必须是字符串。null 值保留为 null（= 未设置）。 */
        private fun parse(json: String): MutableMap<String, MutableMap<String, Any?>> {
            val out: MutableMap<String, MutableMap<String, Any?>> = LinkedHashMap()
            try {
                var text = json
                if (text.isNotEmpty() && text[0] == '\uFEFF') {
                    text = text.substring(1)             // 少数编辑器会加 BOM
                }
                val root = JSONObject(text)
                val groups = root.optJSONObject("groups")
                if (groups == null) {
                    Log.w(TAG, "bundled config has no \"groups\" object")
                    return out
                }
                val names = groups.keys()
                while (names.hasNext()) {
                    val group = names.next()
                    val values = groups.optJSONObject(group)
                    if (values == null) continue
                    val flat = LinkedHashMap<String, Any?>()
                    val keys = values.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = values.opt(key)
                        // JSONObject.NULL 是「未设置」的载体；统一归一化成 java null。
                        flat[key] = if (value === JSONObject.NULL) null else value
                    }
                    out[group] = flat
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bundled default config is not valid JSON: " + t)
            }
            return out
        }

        /**
         * 读顶层 `_rev`。旧 JSON / 解析失败一律按 [REV_LEGACY] 处理 ——
         * 这保证没有版本号的配置会被当成「比当前版本旧」，从而补一次 absent 键。
         */
        private fun parseRev(json: String): Int {
            try {
                var text = json
                if (text.isNotEmpty() && text[0] == '\uFEFF') {
                    text = text.substring(1)
                }
                val rev = JSONObject(text).optInt("_rev", REV_LEGACY)
                return if (rev > 0) rev else REV_LEGACY
            } catch (t: Throwable) {
                return REV_LEGACY
            }
        }

        /** 组内已应用的默认配置版本；没有（旧版）或类型不对都返回 0，表示「需要迁移」。 */
        private fun revOf(prefs: SharedPreferences): Int {
            try {
                return prefs.getInt(KEY_REV, 0)
            } catch (t: Throwable) {
                return 0
            }
        }

        // ------------------------------------------------------------------ 资源读取

        /**
         * 读内置 JSON，两条路都要能走通：
         * <ol>
         *   <li>先按资源读（模块模式：模块自己的 Resources；免 root 内置模式：宿主 Resources，
         *       模块的 res 被 LSPatch 并入宿主）；</li>
         *   <li>读不到就直接从模块 APK 的 zip 里取 `res/raw/default_config.json`。
         *       这条路不依赖资源表，是最后一道保险。</li>
         * </ol>
         */
        private fun readBundledJson(context: Context?): String {
            val fromRes = readRawResource(context)
            if (fromRes != null && fromRes.isNotEmpty()) {
                Log.i(TAG, "default config read from resources (" + fromRes.length + " chars)")
                return fromRes
            }
            val fromApk = readFromModuleApk()
            if (fromApk != null && fromApk.isNotEmpty()) {
                Log.i(TAG, "default config read from module apk zip (" + fromApk.length + " chars)")
                return fromApk
            }
            throw IllegalStateException("default_config not found in resources nor module apk")
        }

        /** 依次尝试：宿主 Resources（免 root 内置模式）→ 模块自己的 Resources（模块模式）。 */
        private fun readRawResource(context: Context?): String? {
            if (context != null) {
                val text = openRaw(context, context.resources, context.packageName)
                if (text != null) return text
            }
            try {
                val app = App.app()
                if (app != null) {
                    val text = openRaw(app, app.resources, app.packageName)
                    if (text != null) return text
                }
            } catch (ignored: Throwable) {
            }
            try {
                // 2.3.x 转译：ModuleResources.get 的形参是**非空** Context，而 Java 原版在这里
                // 可能传 null（context 与 App.ctx() 同时为 null）—— Java 侧那一下会 NPE 再被下面的
                // catch 吞掉，等价于「这条路不可用」。Kotlin 显式判空，结果一致且不再靠异常兜。
                val hostCtx = context ?: App.ctx()
                val module = if (hostCtx == null) null else ModuleResources.get(hostCtx)
                if (module != null) {
                    for (pkg in arrayOf("io.github.ldxm666.mapadkiller", context?.packageName, null)) {
                        val id = module.getIdentifier(ASSET, "raw", pkg)
                        if (id != 0) {
                            val text = slurp(module.openRawResource(id))
                            if (text != null) return text
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "module resources path unavailable: " + t)
            }
            return null
        }

        private fun openRaw(context: Context?, resources: Resources?, pkg: String?): String? {
            if (resources == null) return null
            try {
                var id = resources.getIdentifier(ASSET, "raw", pkg)
                if (id == 0) id = resources.getIdentifier(ASSET, "raw", null)
                if (id == 0) return null
                return slurp(resources.openRawResource(id))
            } catch (t: Throwable) {
                Log.i(TAG, "raw resource lookup failed for " + pkg + ": " + t)
                return null
            }
        }

        /**
         * 直接从模块 APK 的 zip 里读 `res/raw/default_config.json`。
         *
         * <p>资源名是 aapt2 生成的 0x7f… 编号，跨进程/跨包名解析并不可靠；zip 条目名是稳定的，
         * 所以这是内置资源最确定的读取方式。
         */
        private fun readFromModuleApk(): String? {
            try {
                val path = ModuleResources.moduleApkPath() ?: return null
                val file = File(path)
                if (!file.isFile) return null
                val zip = ZipFile(file)
                try {
                    var entry: ZipEntry? = zip.getEntry("res/raw/" + ASSET + ".json")
                    if (entry == null) entry = zip.getEntry("assets/" + ASSET + ".json")
                    if (entry == null) {
                        // 资源被重命名/换目录时的兜底：按条目名找。
                        val names = zip.entries()
                        while (names.hasMoreElements()) {
                            val candidate = names.nextElement()
                            if (candidate.name.endsWith("/" + ASSET + ".json")) {
                                entry = candidate
                                break
                            }
                        }
                    }
                    val found = entry ?: return null
                    val input = zip.getInputStream(found)
                    try {
                        return slurp(input)
                    } finally {
                        try {
                            input.close()
                        } catch (ignored: Throwable) {
                        }
                    }
                } finally {
                    try {
                        zip.close()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "module apk zip read failed: " + t)
                return null
            }
        }

        /** 全部读成 UTF-8 文本；失败返回 null。 */
        private fun slurp(input: InputStream?): String? {
            if (input == null) return null
            try {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read > 0) {
                    out.write(buffer, 0, read)
                    read = input.read(buffer)
                }
                return String(out.toByteArray(), Charset.forName("UTF-8"))
            } catch (t: Throwable) {
                Log.i(TAG, "read failed: " + t)
                return null
            } finally {
                try {
                    input.close()
                } catch (ignored: Throwable) {
                }
            }
        }

        // ------------------------------------------------------------------ 环境

        private fun service(): XposedService? {
            // ★ 2.3.0：先问 mapclean.App（真正被 attachEmbedded / onServiceBind 驱动的那个）。
            //   以前只问 io.github.ldxm666.mapadkiller.App.svc()，而 mapadkiller 整包在
            //   百度/腾讯变体里是**空实现** ⇒ 恒 null ⇒ 内置版默认配置永远不应用。
            try {
                val s = App.service()
                if (s != null) return s
            } catch (ignored: Throwable) {
            }
            try {
                return io.github.ldxm666.mapadkiller.App.svc()
            } catch (t: Throwable) {
                return null
            }
        }

        /** 宿主包名：优先取进程上下文，其次取 [Vendor] 记录的宿主。 */
        private fun hostPackage(context: Context?): String? {
            if (context != null) {
                try {
                    return context.packageName
                } catch (ignored: Throwable) {
                }
            }
            try {
                val app = App.app()
                if (app != null) return app.packageName
            } catch (ignored: Throwable) {
            }
            return Vendor.host()
        }

        /**
         * 写完让各厂商的读取缓存立刻失效，并把改动推进正在运行的地图页面。
         *
         * <p>免 root 内置模式下另外两家的类可能是 no-op 桩，因此逐个 try/catch。高德一侧走
         * `AmapPanel.apply`，它与「导入配置」是同一条推送路径（不重复造）；不直接引用
         * `mapadkiller.Config`，因为那个类在腾讯精简变体里不存在。
         */
        private fun invalidate(host: String?) {
            try {
                if (host == null || EmbeddedSettingsPolicy.AMAP == host) AmapPanel.apply(null)
            } catch (ignored: Throwable) {
            }
            try {
                if (host == null || EmbeddedSettingsPolicy.BAIDU == host) BaiduPanel.invalidate()
            } catch (ignored: Throwable) {
            }
            try {
                if (host == null || EmbeddedSettingsPolicy.TENCENT == host) TmapPanel.invalidate()
            } catch (ignored: Throwable) {
            }
        }
    }
}
