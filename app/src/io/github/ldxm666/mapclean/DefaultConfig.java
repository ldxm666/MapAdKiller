package io.github.ldxm666.mapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import io.github.libxposed.service.XposedService;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 自带默认配置：模块内置一份 JSON，**全新安装后自动应用一次**。
 *
 * <h3>配置来源</h3>
 * {@code res/raw/default_config.json} —— 与模块「更多 → 导出 JSON 配置」产出的格式完全一致：
 * <pre>
 * { "_app": "...", "_ver": 2,
 *   "groups": { "amap_enhancer_config": { "tab_探索": false, ... }, ... } }
 * </pre>
 * 值只有 {@code true} / {@code false} / {@code null} 三种，其中 <b>{@code null} 表示
 * 「未设置、保持缺省」</b>：应用时必须跳过，绝不能写成 {@code false}（写 false 会变成
 * 一条真实的「已关闭」记录，缺省语义就丢了）。
 *
 * <h3>为什么放在 res/raw 而不是 assets</h3>
 * 两条构建管线都只把 {@code app/res/} 交给 aapt2（{@code app/build.ps1} 与
 * {@code tools/build_variant.py} 的 {@code build_slim_module}）；{@code app/assets/} 两边
 * 都不会被打进 APK。放在 {@code res/raw} 是**唯一**能让「模块模式」与「免 root 内置模式」
 * 都真的带上这份 JSON 的位置，而且不必改动任何构建脚本。
 *
 * <h3>只应用一次，但可以按版本补缺</h3>
 * 用 {@code _defaults_applied} 记录「做过」，用 {@code _defaults_rev} 记录「做的是哪一版」。
 * 两者都不存在 = 全新安装 → 应用；版本低于 JSON 的 {@code _rev} = 老用户升级 → 只补
 * <b>absent</b> 键（{@link #write} 对已存在的键一律跳过），用户显式改过的值绝不覆盖。
 *
 * <h3>为什么需要版本号</h3>
 * 2.3.0 的腾讯组漏了 {@code tab_*} / {@code ui_seek_chips} 等键（JSON 里是 {@code null}，
 * 而 {@code null} 的语义是「不写」），干净安装于是「所有键 absent ⇒ 兜底 true ⇒ 什么都没精简」。
 * 只改 JSON 不够：老用户 {@code _defaults_applied} 已置位，永远轮不到新键落盘。
 * 版本门控让这一批新默认值能补进去，同时因为「只填 absent」，不会把用户手动开/关的值改回去。
 *
 * <h3>按组分流</h3>
 * <ul>
 *   <li><b>模块模式</b>（LSPosed）：三个厂商组都存在，逐个应用。</li>
 *   <li><b>免 root 内置模式</b>（LSPatch）：宿主进程里只有它自己那一家的组，只应用那一组，
 *       其它组静默跳过 —— 不报错、不卡住。</li>
 * </ul>
 * 判定用宿主包名（{@link #hostPackage()}），它与 {@code EmbeddedSettingsPolicy.isHostPackage}
 * 同一份名单，但在 {@code tools/build_variant.py} 的桩替换之后依然成立。
 *
 * <h3>与 ConfigBackup 的关系</h3>
 * 刻意不复用 {@code ConfigBackup}：那份实现被 {@code tools/build_variant.py} 在免 root
 * 变体里整个丢掉（{@code DROP_MAPCLEAN_FILES}），依赖它会让精简构建编译不过。这里的
 * 解析只做这一件事，并且比导入更严格 —— 只接受布尔值，其它类型一律跳过并记日志。
 */
public final class DefaultConfig {

    private static final String TAG = "MapCleanDefaults";

    /** 内置 JSON 的资源名，对应 {@code res/raw/default_config.json}。 */
    public static final String ASSET = "default_config";

    /** 应用标记。存在即表示这份默认配置已经落地过一次。 */
    public static final String KEY_APPLIED = "_defaults_applied";

    /**
     * 默认配置的**版本号**标记（2.3.1 新增）。
     *
     * <p>{@link #KEY_APPLIED} 只能表达「做过」，不能表达「做的是哪一版」。腾讯那份 JSON 在
     * 2.3.0 里漏了 {@code tab_*} / {@code ui_seek_chips} 等键（值为 {@code null} ⇒ 从未落盘），
     * 于是干净安装等于「全部走缺省 true」—— 底栏四个 tab、首页 chip 行全部保留。
     * 要修就得让**已经装过旧版**的用户也能补上这些新键，但绝不能覆盖用户显式改过的值。
     *
     * <p>所以引入版本门控：JSON 顶层带 {@code "_rev": 2}，组内 {@code _defaults_rev} 小于它时
     * 重新走一遍 {@link #write}。{@code write} 的规则是「键已存在就跳过」，因此这次重放
     * <b>只填 absent 键</b>，用户显式开/关过的键原样保留。写完把 {@code _defaults_rev}
     * 落到同一个 prefs 上，后续启动直接跳过 —— 幂等，中途被杀下次继续。
     */
    public static final String KEY_REV = "_defaults_rev";

    /** 旧版 JSON 没有 {@code _rev}；把它当成第 1 版，任何带版本号的配置都比它新。 */
    private static final int REV_LEGACY = 1;

    /**
     * 三份厂商配置组名。
     *
     * <p>刻意写成字面量而不是引用 {@code bmapclean.Cfg.GROUP} / {@code tmapclean.Cfg.GROUP}：
     * {@code tools/build_variant.py} 在精简构建里会读编译出来的 DEX，发现被丢掉的厂商包
     * 还有类型描述符残留就直接让构建失败（{@code banned} 检查）。引用一个外部类的常量会
     * 把 {@code Lio/github/ldxm666/bmapclean/Cfg;} 带进产物，从而触发那条检查；纯字符串
     * 常量不会。三份名单必须与各厂商 {@code Cfg.GROUP} / {@code Config.PREF_GROUP} 保持一致。
     */
    private static final String GROUP_AMAP = "amap_enhancer_config";
    private static final String GROUP_BAIDU = "bmapclean_config";
    private static final String GROUP_TMAP = "tmapclean_config";

    /** 模块自己的界面配置组（桌面图标开关）。它是本地 SharedPreferences，不是远端组。 */
    private static final String GROUP_UI = "mak_ui";

    /** 组 → 拥有它的地图包名。免 root 内置模式里只有匹配的那一组会被应用。 */
    private static final Map<String, String> VENDOR_OF = new LinkedHashMap<String, String>();
    static {
        VENDOR_OF.put(GROUP_AMAP, EmbeddedSettingsPolicy.AMAP);
        VENDOR_OF.put(GROUP_BAIDU, EmbeddedSettingsPolicy.BAIDU);
        VENDOR_OF.put(GROUP_TMAP, EmbeddedSettingsPolicy.TENCENT);
    }

    private DefaultConfig() {}

    /** 一次应用的结果，用于日志与设置页提示。 */
    public static final class Result {
        public int applied;
        public int skipped;
        public final Set<String> groups = new LinkedHashSet<String>();
        public final Set<String> failures = new LinkedHashSet<String>();
        Result(int applied, int skipped) { this.applied = applied; this.skipped = skipped; }
        @Override public String toString() {
            return "applied=" + applied + " skipped=" + skipped
                    + " groups=" + groups + " failures=" + failures;
        }
    }

    // ------------------------------------------------------------------ 首次应用

    /**
     * 服务可用之后调用一次：全新安装（标记不存在）时应用内置默认配置，否则什么都不做。
     *
     * <p>调用点见 {@code mapclean.App#bindService} 与 {@code mapclean.App#attachEmbedded}：
     * 写配置必须走 {@link XposedService#getRemotePreferences(String)}，而服务只有在悬浮球
     * 附着之后才存在，所以这里绝不能在服务为 null 时硬写。
     */
    public static void applyOnce() {
        try {
            apply(false, null);
        } catch (Throwable t) {
            Log.w(TAG, "applyOnce failed: " + t);
        }
    }

    /**
     * 手动再应用一次（设置页入口）。跳过版本门控，但仍遵守「已存在的键不覆盖」——
     * 它补齐的是缺失项，不是把用户配置重置成默认值。
     *
     * <p>与首启的唯一区别是无条件应用：首启会看 {@code _defaults_applied} / {@code _defaults_rev}
     * 标记，这里不看。
     * {@code mak_ui}（桌面图标，本地 SharedPreferences）只在给出 {@code context} 且不是
     * 免 root 内置宿主时才写 —— 内置宿主里那是地图 App 自己的偏好文件，不该碰。
     *
     * @param context 可空；给出时在模块模式下连 {@code mak_ui} 一起恢复。
     * @return 写入的键数；没有可用服务或资源时 {@code groups} 为空。
     */
    public static Result apply(Context context) {
        return apply(true, context);
    }

    private static Result apply(boolean force, Context context) {
        XposedService service = service();
        // ★ 2.3.0：宿主包名必须先算，它决定「没有框架服务能不能继续」。
        //   免 root 内置版（LSPatch）里服务通道本来就起不来，而 hook 读的是**宿主本地**文件，
        //   本地后端完全可用。以前这里 service==null 就直接 return，于是
        //   `_defaults_applied` 永远落不下去、内置默认配置从来没生效过一次
        //   （真机日志：`MapCleanDefaults: no framework service yet; defaults not applied`）。
        String host = hostPackage(context);
        boolean embedded = EmbeddedSettingsPolicy.isHostPackage(host);
        // ★ host 还没定案时**绝不能往下走**：这时候既分不清自己跑在哪家地图里，
        //   也判断不出该走本地还是 remote。真机日志（修复中）：
        //   `defaults applied: applied=140 groups=[amap, bmapclean, tmap] host=null embedded=false`
        //   —— 一次就把三家的默认值全灌进了 LSPatch 的 remote 库。调用点会重试，等就是了。
        if (host == null) {
            Log.i(TAG, "host unknown yet; defaults deferred");
            return new Result(0, 0);
        }
        if (service == null && !embedded) {
            Log.i(TAG, "no framework service yet; defaults not applied");
            return new Result(0, 0);
        }

        String json;
        try {
            json = readBundledJson(context);
        } catch (Throwable t) {
            Log.w(TAG, "bundled default config unreadable: " + t);
            return new Result(0, 0);
        }

        Map<String, Map<String, Object>> groups = parse(json);
        if (groups.isEmpty()) {
            Log.w(TAG, "bundled default config carries no group");
            return new Result(0, 0);
        }
        int rev = parseRev(json);

        Result result = new Result(0, 0);
        for (Map.Entry<String, Map<String, Object>> entry : groups.entrySet()) {
            String group = entry.getKey();
            if (GROUP_UI.equals(group)) continue;      // 本地界面项，见下方单独处理
            String owner = VENDOR_OF.get(group);
            if (owner == null) {
                Log.i(TAG, "skip unknown group " + group);
                continue;
            }
            // 免 root 内置模式：宿主只有自己那一家的组，其它组根本不存在 —— 静默跳过。
            if (embedded && !owner.equals(host)) {
                Log.i(TAG, "skip " + group + " (embedded host is " + host + ")");
                continue;
            }
            try {
                applyGroup(service, group, entry.getValue(), force, embedded, rev, result);
            } catch (Throwable t) {
                // 个别组失败不影响其余组。
                result.failures.add(group);
                Log.w(TAG, "group " + group + " failed: " + t);
            }
        }

        if (context != null && !embedded) {
            Map<String, Object> ui = groups.get(GROUP_UI);
            if (ui != null) {
                try {
                    applyUiGroup(context, ui, force, rev, result);
                } catch (Throwable t) {
                    result.failures.add(GROUP_UI);
                    Log.w(TAG, "group " + GROUP_UI + " failed: " + t);
                }
            }
        }

        invalidate(host);
        Log.i(TAG, "defaults " + (force ? "re-applied" : "applied") + ": " + result
                + " host=" + host + " embedded=" + embedded);
        return result;
    }

    // ------------------------------------------------------------------ 单组应用

    /**
     * 应用一个配置组。
     *
     * <p>{@code force=false} 时只在「标记不存在」或「已应用的版本低于当前 JSON 的 _rev」时进入。
     * 后一种情况就是老用户升级：{@link #write} 只补 absent 键，用户改过的键一个都不碰。
     */
    private static void applyGroup(XposedService service, String group,
            Map<String, Object> values, boolean force, boolean embedded, int rev,
            Result result) {
        SharedPreferences prefs = groupPrefs(service, group, embedded);
        if (prefs == null) {
            result.failures.add(group);
            Log.i(TAG, "group " + group + " unavailable");
            return;
        }
        if (!force && prefs.contains(KEY_APPLIED) && revOf(prefs) >= rev) {
            Log.i(TAG, "skip " + group + ": " + KEY_APPLIED + " already set, rev="
                    + revOf(prefs) + " >= " + rev);
            return;
        }
        int written = write(prefs, values, result, group, rev);
        if (written == 0) {
            Log.i(TAG, "group " + group + " had nothing to write (rev=" + rev + ")");
            return;
        }
        result.applied += written;
        result.groups.add(group);
    }

    /**
     * 这一组该写进哪个 prefs。
     *
     * <p>★ 2.3.0：<b>内置版必须写宿主本地</b>。框架 remote 在 LSPatch 上既写不进去、
     * hook 侧也读不到（{@code bmapclean.Cfg.prefs()} 在内置版里解析的就是宿主本地那个文件），
     * 把默认值和 {@code _defaults_applied} 写进 remote，等于写进一个没人看的抽屉 ——
     * 每次启动都会重新判定「全新安装」。
     *
     * <p>本地后端拿不到（宿主 Context 还没创建）才退回 remote；模块版行为不变。
     */
    private static SharedPreferences groupPrefs(XposedService service, String group, boolean embedded) {
        if (embedded) {
            try {
                SharedPreferences local = HostPrefs.local(group);
                if (local != null) return local;
            } catch (Throwable t) {
                Log.i(TAG, "local prefs unavailable for " + group + ": " + t);
            }
        }
        if (service == null) return null;
        try {
            return service.getRemotePreferences(group);
        } catch (Throwable t) {
            // 该组在这个宿主里不存在（免 root 内置模式的其它厂商组），静默跳过。
            Log.i(TAG, "group " + group + " unavailable: " + t);
            return null;
        }
    }

    /**
     * 模块自己的界面组（桌面图标）。
     *
     * <p>只写值，不在这里改组件状态：{@code mapadkiller.App.setHideIcon} 在免 root 精简
     * 变体里是桩（没有这个方法），而 {@code tools/build_variant.py} 会据此拒绝构建。
     * 设置页的「导入配置」本来就会在应用之后刷新桌面入口，这里保持一致。
     */
    private static void applyUiGroup(Context context, Map<String, Object> values,
            boolean force, int rev, Result result) {
        SharedPreferences prefs = context.getSharedPreferences(GROUP_UI, Context.MODE_PRIVATE);
        if (!force && prefs.contains(KEY_APPLIED) && revOf(prefs) >= rev) {
            Log.i(TAG, "skip " + GROUP_UI + ": " + KEY_APPLIED + " already set, rev="
                    + revOf(prefs) + " >= " + rev);
            return;
        }
        int written = write(prefs, values, result, GROUP_UI, rev);
        if (written == 0) return;
        result.applied += written;
        result.groups.add(GROUP_UI);
    }

    /**
     * 把一个组的值写进 SharedPreferences。
     *
     * <p>规则：{@code null} 跳过（= 保持缺省，绝不写 false）；非布尔值跳过并记日志；
     * 布尔值用一次 {@code commit()} 批量落盘（{@code apply()} 是异步的，用户装完立刻
     * 杀进程会丢）。标记键在成功落盘后一并写入，保证「只做一次」。
     *
     * <p>★ 2.3.1：{@code _defaults_rev} 与 {@code _defaults_applied} 一起落盘。
     * <b>键已存在就跳过</b>这条规则没有变 —— 版本迁移因此只补 absent 键，用户显式改过的
     * 值原样保留。即使一次迁移只写了一半就被杀，下次 {@code rev} 仍小于 JSON 版本，
     * 会继续把剩下的 absent 键补齐，已写的键照旧跳过 ⇒ 幂等可续。
     */
    private static int write(SharedPreferences prefs, Map<String, Object> values,
            Result result, String group, int rev) {
        int written = 0;
        int present = 0;
        try {
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {                 // 未设置 → 保持缺省
                    result.skipped++;
                    continue;
                }
                if (!(value instanceof Boolean)) {   // 只写布尔值
                    result.skipped++;
                    Log.w(TAG, "skip " + group + "/" + entry.getKey()
                            + ": not a boolean (" + value.getClass().getSimpleName() + ")");
                    continue;
                }
                // ★★ 2.3.0（用户反馈「重启后配置没保存」的根因）：
                // **默认值绝不能覆盖已存在的键**。
                //
                // 真机取证：{@code bmapclean_config.xml} 里有 44 个布尔键，
                // 但**没有** {@code _defaults_applied} 标记 ⇒ {@link #applyOnce()} 每次启动都判成
                // 「全新安装」⇒ 重新写一遍默认值 ⇒ 用户关掉的开关被改回 true。
                // （标记丢失的原因是它被写进了 LSPatch 那个不持久的 remote 库。）
                //
                // 所以这里不管标记在不在，只要键已经有值，就跳过
                // —— 「默认」的语义就是「键不存在时才用」。这样即使标记再次丢失，
                // 用户的选择也不会被进程重启抹掉。
                if (prefs.contains(entry.getKey())) {
                    result.skipped++;
                    present++;
                    continue;
                }
                editor.putBoolean(entry.getKey(), (Boolean) value);
                written++;
            }
            // 标记写到与数据**同一个** prefs 上（这里就是传进来的那个），
            // 避免「数据在本地、标记在 remote」这种两家分居。
            //
            // 即使 written==0 也要落标记：老用户升级上来时键全在（上面整段跳过），
            // 不落标记就会每次启动重解析一遍 JSON；版本迁移时更要落 _rev，
            // 否则每次启动都会重跑一遍迁移。
            boolean needStamp = !prefs.contains(KEY_APPLIED) || revOf(prefs) < rev;
            if (written == 0 && !needStamp) {
                Log.i(TAG, "skip stamp " + group + ": applied + rev=" + revOf(prefs)
                        + " already current (" + present + " keys present)");
                return 0;
            }
            editor.putBoolean(KEY_APPLIED, true);
            editor.putInt(KEY_REV, rev);
            if (!editor.commit()) {
                result.failures.add(group);
                Log.w(TAG, "commit failed for group " + group);
                return 0;
            }
            if (written == 0) {
                Log.i(TAG, "stamp " + group + ": " + KEY_APPLIED + " + " + KEY_REV + "=" + rev
                        + " (all " + present + " keys already present)");
            }
            return written;
        } catch (Throwable t) {
            result.failures.add(group);
            Log.w(TAG, "write failed for group " + group + ": " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 只取 {@code groups} 里的对象，键必须是字符串。null 值保留为 null（= 未设置）。 */
    private static Map<String, Map<String, Object>> parse(String json) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        try {
            String text = json;
            if (text != null && text.length() > 0 && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);            // 少数编辑器会加 BOM
            }
            JSONObject root = new JSONObject(text);
            JSONObject groups = root.optJSONObject("groups");
            if (groups == null) {
                Log.w(TAG, "bundled config has no \"groups\" object");
                return out;
            }
            java.util.Iterator<String> names = groups.keys();
            while (names.hasNext()) {
                String group = names.next();
                JSONObject values = groups.optJSONObject(group);
                if (values == null) continue;
                Map<String, Object> flat = new LinkedHashMap<String, Object>();
                java.util.Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = values.opt(key);
                    // JSONObject.NULL 是「未设置」的载体；统一归一化成 java null。
                    flat.put(key, value == JSONObject.NULL ? null : value);
                }
                out.put(group, flat);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bundled default config is not valid JSON: " + t);
        }
        return out;
    }

    /**
     * 读顶层 {@code _rev}。旧 JSON / 解析失败一律按 {@link #REV_LEGACY} 处理 ——
     * 这保证没有版本号的配置会被当成「比当前版本旧」，从而补一次 absent 键。
     */
    private static int parseRev(String json) {
        try {
            String text = json;
            if (text != null && text.length() > 0 && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            int rev = new JSONObject(text).optInt("_rev", REV_LEGACY);
            return rev > 0 ? rev : REV_LEGACY;
        } catch (Throwable t) {
            return REV_LEGACY;
        }
    }

    /** 组内已应用的默认配置版本；没有（旧版）或类型不对都返回 0，表示「需要迁移」。 */
    private static int revOf(SharedPreferences prefs) {
        try {
            return prefs.getInt(KEY_REV, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 资源读取

    /**
     * 读内置 JSON，两条路都要能走通：
     * <ol>
     *   <li>先按资源读（模块模式：模块自己的 Resources；免 root 内置模式：宿主 Resources，
     *       模块的 res 被 LSPatch 并入宿主）；</li>
     *   <li>读不到就直接从模块 APK 的 zip 里取 {@code res/raw/default_config.json}。
     *       这条路不依赖资源表，是最后一道保险。</li>
     * </ol>
     */
    private static String readBundledJson(Context context) throws Exception {
        String text = readRawResource(context);
        if (text != null && text.length() > 0) {
            Log.i(TAG, "default config read from resources (" + text.length() + " chars)");
            return text;
        }
        text = readFromModuleApk();
        if (text != null && text.length() > 0) {
            Log.i(TAG, "default config read from module apk zip (" + text.length() + " chars)");
            return text;
        }
        throw new IllegalStateException("default_config not found in resources nor module apk");
    }

    /** 依次尝试：宿主 Resources（免 root 内置模式）→ 模块自己的 Resources（模块模式）。 */
    private static String readRawResource(Context context) {
        if (context != null) {
            String text = openRaw(context, context.getResources(), context.getPackageName());
            if (text != null) return text;
        }
        try {
            android.app.Application app = App.app();
            if (app != null) {
                String text = openRaw(app, app.getResources(), app.getPackageName());
                if (text != null) return text;
            }
        } catch (Throwable ignored) { }
        try {
            Resources module = ModuleResources.get(context == null ? App.ctx() : context);
            if (module != null) {
                for (String pkg : new String[]{
                        "io.github.ldxm666.mapadkiller",
                        context == null ? null : context.getPackageName(),
                        null}) {
                    int id = module.getIdentifier(ASSET, "raw", pkg);
                    if (id != 0) {
                        String text = slurp(module.openRawResource(id));
                        if (text != null) return text;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "module resources path unavailable: " + t);
        }
        return null;
    }

    private static String openRaw(Context context, Resources resources, String pkg) {
        if (resources == null) return null;
        try {
            int id = resources.getIdentifier(ASSET, "raw", pkg);
            if (id == 0) id = resources.getIdentifier(ASSET, "raw", null);
            if (id == 0) return null;
            return slurp(resources.openRawResource(id));
        } catch (Throwable t) {
            Log.i(TAG, "raw resource lookup failed for " + pkg + ": " + t);
            return null;
        }
    }

    /**
     * 直接从模块 APK 的 zip 里读 {@code res/raw/default_config.json}。
     *
     * <p>资源名是 aapt2 生成的 0x7f… 编号，跨进程/跨包名解析并不可靠；zip 条目名是稳定的，
     * 所以这是内置资源最确定的读取方式。
     */
    private static String readFromModuleApk() {
        try {
            String path = ModuleResources.moduleApkPath();
            if (path == null) return null;
            File file = new File(path);
            if (!file.isFile()) return null;
            ZipFile zip = new ZipFile(file);
            try {
                ZipEntry entry = zip.getEntry("res/raw/" + ASSET + ".json");
                if (entry == null) entry = zip.getEntry("assets/" + ASSET + ".json");
                if (entry == null) {
                    // 资源被重命名/换目录时的兜底：按条目名找。
                    java.util.Enumeration<? extends ZipEntry> names = zip.entries();
                    while (names.hasMoreElements()) {
                        ZipEntry candidate = names.nextElement();
                        String name = candidate.getName();
                        if (name.endsWith("/" + ASSET + ".json")) { entry = candidate; break; }
                    }
                }
                if (entry == null) return null;
                InputStream in = zip.getInputStream(entry);
                try { return slurp(in); } finally { try { in.close(); } catch (Throwable ignored) { } }
            } finally {
                try { zip.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.i(TAG, "module apk zip read failed: " + t);
            return null;
        }
    }

    /** 全部读成 UTF-8 文本；失败返回 null。 */
    private static String slurp(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            Log.i(TAG, "read failed: " + t);
            return null;
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ 环境

    private static XposedService service() {
        // ★ 2.3.0：先问 mapclean.App（真正被 attachEmbedded / onServiceBind 驱动的那个）。
        //   以前只问 io.github.ldxm666.mapadkiller.App.svc()，而 mapadkiller 整包在
        //   百度/腾讯变体里是**空实现** ⇒ 恒 null ⇒ 内置版默认配置永远不应用。
        try {
            XposedService s = App.service();
            if (s != null) return s;
        } catch (Throwable ignored) { }
        try { return io.github.ldxm666.mapadkiller.App.svc(); } catch (Throwable t) { return null; }
    }

    /** 宿主包名：优先取进程上下文，其次取 {@link Vendor} 记录的宿主。 */
    private static String hostPackage(Context context) {
        if (context != null) {
            try { return context.getPackageName(); } catch (Throwable ignored) { }
        }
        try {
            android.app.Application app = App.app();
            if (app != null) return app.getPackageName();
        } catch (Throwable ignored) { }
        return Vendor.host();
    }

    /**
     * 写完让各厂商的读取缓存立刻失效，并把改动推进正在运行的地图页面。
     *
     * <p>免 root 内置模式下另外两家的类可能是 no-op 桩，因此逐个 try/catch。高德一侧走
     * {@code AmapPanel.apply}，它与「导入配置」是同一条推送路径（不重复造）；不直接引用
     * {@code mapadkiller.Config}，因为那个类在腾讯精简变体里不存在。
     */
    private static void invalidate(String host) {
        try {
            if (host == null || EmbeddedSettingsPolicy.AMAP.equals(host)) AmapPanel.apply(null);
        } catch (Throwable ignored) { }
        try {
            if (host == null || EmbeddedSettingsPolicy.BAIDU.equals(host)) BaiduPanel.invalidate();
        } catch (Throwable ignored) { }
        try {
            if (host == null || EmbeddedSettingsPolicy.TENCENT.equals(host)) TmapPanel.invalidate();
        } catch (Throwable ignored) { }
    }
}
