package io.github.ldxm666.bmapclean;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 「我的」页 —— **JS 层**补丁（v0.4.3）：给宿主自己跑的小程序注入一段补丁脚本。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要 JS 层（数据层已经解决了大部分）
 * ══════════════════════════════════════════════════════════════════════════
 * 这两张卡在数据层没有闸门（bundle 实证）：
 *   <sport             s-if="cardName === 'sport'   && cardLoadingStatus === 0 && showSportCard" />
 *   <ugc/contribution  s-if="cardName === 'contribution' && cardLoadingStatus === 0 && isCarPlay !== 1" />
 * 宫格同理：`commonList` 是包内 JS 的静态表 `l.default` 过滤出来的，服务端不下发。
 * 而卡片清单 `cardList` 是页面 JS 内部拼的：
 *   processCardList(t){ var i=[].concat(tn); … i=i.concat(rn); i=i.concat(sn); r.data.set("cardList", i) }
 * Java 侧够不到 → 只能进那个 JS 运行时里动手。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 注入点怎么找到的（别再试错）
 * ══════════════════════════════════════════════════════════════════════════
 * ① `TalosBridge.loadScriptFromString(ParamMap)` 看起来像入口，**但真机上一次都不触发**
 *    （V8 那边自己按文件路径读盘，脚本内容根本不经过 Java）。已实测。
 * ② 真正的入口（classes18 反编译 + 真机验证）：
 *      TalosBaseRuntime.loadBundle(String, cb) {
 *          String modulePath = getModulePath(str);        // 磁盘上的 index.android.bundle
 *          ParamMap m = vi2.b.c();
 *          m.putString("biz_pkg_name", str);              // "userCore"
 *          m.putString("biz_pkg_path", modulePath);       // ← 就是它
 *          mBridgeManager.preLoadJSFile(m, cb);           // → TalosBridge.preLoadJSFile → JNI
 *      }
 *    所以：**把 `biz_pkg_path` 改指向我们自己打的补丁文件**即可。ParamMap 是可写的（putString），
 *    就地改写不需要替换参数（参数替换那条路已按熔断规则停用）。
 *
 * 作用域：只改**内容里含 `processServerData` 的那一份脚本**（我的页 bundle 独有），
 *        写出的补丁文件放在原文件**同目录**（app 自己进程对自己的 data 目录有写权限，不需要 Context），
 *        原文件一个字都不动，随时可回退。
 *
 * 补丁做什么（只动 Array.prototype，作用域=这个运行时/这一页）：
 *   ① concat：只对"卡片名数组"（同时含 goldCoin + loading 两个特征串）过滤，把要关的卡片名剔掉
 *   ② push  ：宫格图标对象（id + name + link=baidumap://…）在整块关掉时丢弃
 *
 * 失效安全：读不到文件 / 不是这一页 / 目录不可写 / 配置读不到 → 一个字不改，页面保持原样。
 */
public final class MineJs {

    private MineJs() {}

    private static volatile boolean installed;
    private static volatile int sPatched;
    /** 内容入口日志只打前 8 条（脚本很长，别刷屏） */
    private static volatile int sContentLogged;
    private static volatile boolean sPmLogged;
    private static volatile boolean sCfgLogged;

    /** 我的页 bundle 独有：用它来判断"这一份是不是我的页" */
    private static final String PAGE_MARK = "processServerData";
    /** 页面脚本特征：cardList 的卡片名表就在这段代码里 */
    private static final String MINE_MARK = "processCardList";
    /** 同目录里的原始 bundle（我们只写副本，原文件一个字不动） */
    private static final String ORIG_NAME = "index.android.bundle";
    private static final String PATCH_MARK = "__bmc_patch";
    private static final long MAX_LEN = 8L * 1024 * 1024;

    public static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        Class<?> bridge = H.cls(cl, "com.baidu.talos.core.bridge.TalosBridge");
        hookLoad(bridge, "loadJSFile");
        hookLoad(bridge, "preLoadJSFile");
        hookContentEntry(bridge);
        // 最终兜底：直接把页面 bundle 文件改成带补丁的版本（见方法注释）
        // patchBundlesOnDisk();   // 已停用：App 不认改动后的 bundle（见方法注释），改用视图层兜底
    }

    // ══════════════════════════════════════════════ 磁盘直改（最终兜底，已验证最可靠）

    /**
     * 直接给「我的」页的 bundle 文件前置补丁。
     *
     * 为什么最后落在这里（v2.0.1 真机逐条排除）：
     *   ① `biz_pkg_path` / `biz_require_map` 改写 → 只覆盖外壳与资源包，页面代码不经过这两个入口；
     *   ② `TalosBridge.loadScriptFromString(long, byte[])` → 内容入口 hook 装上了但**一次都没触发**；
     *   ③ 结论：V8 侧自己按路径读盘。所以唯一的确定性做法就是把那个文件本身改成带补丁的版本。
     *
     * 文件在进程内可写（同 uid，mode 600），原文件首次改写前备份为 `index.android.bundle.bmc_orig`，
     * 每次启动都从备份重新生成 → 改开关后重启 App 即生效，且随时可还原。
     * 改写会改变 mtime/size，足以让按 (path, mtime, size) 判定的代码缓存失效。
     */
    public static void patchBundlesOnDisk() {
        try {
            File root = new File("/data/user/0/com.baidu.BaiduMap/files/talos/dpmbundles");
            if (!root.isDirectory()) return;
            String js = buildPatch();
            File[] biz = root.listFiles();
            if (biz == null) return;
            int done = 0;
            for (int i = 0; i < biz.length; i++) {
                File[] subs = biz[i].listFiles();
                if (subs == null) continue;
                for (int j = 0; j < subs.length; j++) {
                    File[] vers = subs[j].listFiles();
                    if (vers == null) continue;
                    for (int k = 0; k < vers.length; k++) {
                        if (patchOne(vers[k], js)) done++;
                    }
                }
            }
            Cfg.log("mine js disk: patched " + done + " bundle(s), js="
                    + (js == null ? "null(全开,跳过)" : (js.length() + "B")));
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "mine js disk patch failed: " + t);
        }
    }

    /**
     * 单个 bundle 目录：写**新文件** `index.android.bundle.bmc`（= 补丁 + 原始内容），
     * 并把 pkginfo.json 的 `filename` / `md5` 同步成新文件 —— 这一步不能省：
     * 真机实测过"就地改写"，结果 pkginfo.json 里的 md5 校验不过，页面直接不加载。
     *
     * 原文件一个字不动；pkginfo.json 首次改动前备份成 pkginfo.json.bmc_orig。
     * 每次启动都用 .bmc_orig 里的原始内容重生成 → 改开关后重启即生效。
     */
    private static boolean patchOne(File dir, String js) {
        try {
            File bundle = new File(dir, ORIG_NAME);
            File pkginfo = new File(dir, "pkginfo.json");
            File bak = new File(dir, ORIG_NAME + ".bmc_orig");
            File pkbak = new File(dir, "pkginfo.json.bmc_orig");
            if (!bundle.isFile() || !pkginfo.isFile()) return false;

            // ① 首次：备份原始 bundle 与清单
            if (!bak.isFile()) {
                String cur = readAll(bundle);
                if (cur == null || (!cur.contains(MINE_MARK) && !cur.contains("goldCoin"))) return false;
                writeAll(bak, cur);
                if (!pkbak.isFile()) writeAll(pkbak, readAll(pkginfo));
                Cfg.log("mine js disk: 备份 " + dir.getName() + " (" + cur.length() + "B)");
            }
            String src = readAll(bak);
            if (src == null || (!src.contains(MINE_MARK) && !src.contains("goldCoin"))) return false;

            // ② 生成新文件
            String want = (js == null) ? src : (js + src);
            File out = new File(dir, ORIG_NAME + ".bmc");
            String have = out.isFile() ? readAll(out) : null;
            boolean wrote = false;
            if (have == null || !have.equals(want)) {
                writeAll(out, want);
                wrote = true;
            }

            // ③ 清单指向新文件 + 新 md5
            if (rewritePkginfo(pkginfo, pkbak, out.getName(), md5(want), src.length())) wrote = true;

            if (wrote) {
                Cfg.log("mine js disk: " + dir.getParentFile().getName() + "/" + dir.getName()
                        + " " + src.length() + " -> " + want.length() + " (" + out.getName() + ")");
            }
            return wrote;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "mine js disk patchOne failed: " + t);
            return false;
        }
    }

    /** 把 pkginfo.json 的 filename / md5 换成我们的文件（其余字段原样保留，纯字符串替换） */
    private static boolean rewritePkginfo(File pkginfo, File pkbak, String name, String md5, int srcLen) {
        try {
            String base = pkbak.isFile() ? readAll(pkbak) : readAll(pkginfo);
            if (base == null || base.indexOf("\"filename\"") < 0) return false;
            String json = base.replaceAll("\"filename\"\\s*:\\s*\"[^\"]*\"",
                            "\"filename\":\"" + name + "\"")
                    .replaceAll("\"md5\"\\s*:\\s*\"[^\"]*\"", "\"md5\":\"" + md5 + "\"");
            String cur = readAll(pkginfo);
            if (json.equals(cur)) return false;
            writeAll(pkginfo, json);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 文件 md5（pkginfo.json 的自校验用） */
    private static String md5(String s) {        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] out = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (int i = 0; i < out.length; i++) {
                String h = Integer.toHexString(out[i] & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ══════════════════════════════════════════════ 内容入口（最可靠的一条路）

    /**
     * `TalosBridge.loadScriptFromString(long handle, byte[] script)` —— **脚本内容**必经此处。
     *
     * 为什么必须有这条：真机实测（v2.0.1）
     *   · `biz_pkg_path` 改写只覆盖到"外壳 / 资源"bundle（HomeFeed 1.6MB、userCore 869KB 只有资源表），
     *   · 真正渲染「我的」页的页面代码在
     *     `…/bdmap.mapclient.userSystem/userCenter/1.1.61.1/index.android.bundle`（650KB，含 cardList×10），
     *     它的加载**不经过** loadJSFile / preLoadJSFile（日志里从来没出现过这个路径），
     *     所以整条"改路径"的路子对它无效 —— 探针 __BMC_ALIVE__ 零命中就是证据。
     * 这里改成从内容入口注入：命中页面脚本就把补丁**前置**进同一段脚本，
     * V8 编译的必然是带补丁的版本，与路径/缓存怎么绕都无关。
     */
    private static void hookContentEntry(Class<?> bridge) {
        try {
            Method target = null;
            Method[] ms = bridge.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (!"loadScriptFromString".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == long.class && p[1] == byte[].class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                H.log(Log.WARN, MainHook.TAG, "mine js: loadScriptFromString(long,byte[]) not found");
                return;
            }
            H.hook(target, "mine_js_content", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        List<Object> args = chain.getArgs();
                        if (args != null && args.size() == 2 && args.get(1) instanceof byte[]) {
                            byte[] b = (byte[]) args.get(1);
                            if (b.length > 1024) {
                                String text = new String(b, "UTF-8");
                                boolean page = text.indexOf(MINE_MARK) >= 0
                                        || text.indexOf("goldCoin") >= 0;
                                if (sContentLogged < 8) {
                                    sContentLogged++;
                                    Cfg.log("mine js content: len=" + b.length
                                            + " page=" + page
                                            + " head=" + text.substring(0, Math.min(60, text.length()))
                                                    .replace('\n', ' '));
                                }
                                if (page) {
                                    String js = buildPatch();
                                    if (js != null) {
                                        byte[] nb = (js + text).getBytes("UTF-8");
                                        args.set(1, nb);
                                        sPatched++;
                                        Cfg.log("mine js content: 已前置补丁 len " + b.length
                                                + " -> " + nb.length + " (" + sPatched + ")");
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        H.log(Log.WARN, MainHook.TAG, "mine js content inject failed: " + t);
                    }
                    return chain.proceed();
                }
            });
            Cfg.log("mine js: content entry hooked");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "mine js content hook failed: " + t);
        }
    }

    private static void hookLoad(Class<?> bridge, final String name) {
        // (ParamMap, com.baidu.talos.core.bridge.a)
        Method m = H.method(bridge, name, 2);
        if (m == null) {
            H.log(Log.WARN, MainHook.TAG, "mine js: " + name + " not found");
            return;
        }
        H.hook(m, "mine_js_" + name, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    redirect(chain, name);
                } catch (Throwable t) {
                    H.log(Log.WARN, MainHook.TAG, "mine js " + name + " failed: " + t);
                }
                return chain.proceed();
            }
        });
    }

    private static void redirect(XposedInterface.Chain chain, String via) throws Throwable {
        if (!Spec.mineEnabled()) return;
        String js = buildPatch();
        if (js == null) return;                       // 全开 = 不注入，零开销
        List<Object> args = chain.getArgs();
        if (args == null || args.size() < 1 || args.get(0) == null) return;
        Object pm = args.get(0);

        // 一次性诊断：把整个 ParamMap 的键值打出来（含所有 bundle 路径），确认页面代码在哪一项
        if (!sPmLogged) {
            sPmLogged = true;
            try {
                StringBuilder sb = new StringBuilder();
                Object all = call0(pm, "toMap");
                if (all instanceof java.util.Map) {
                    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) all).entrySet()) {
                        Object v = e.getValue();
                        String s = (v == null) ? "null" : String.valueOf(v);
                        if (s.length() > 110) s = s.substring(0, 110) + "…";
                        sb.append(e.getKey()).append('=').append(s).append(" | ");
                        if (sb.length() > 1600) { sb.append("…(截断)"); break; }
                    }
                } else {
                    sb.append("toMap()->").append(all);
                }
                Cfg.log("mine js ParamMap[" + via + "]: " + sb);
            } catch (Throwable t) {
                Cfg.log("mine js ParamMap dump failed: " + t);
            }
        }

        // ① 主模块路径 biz_pkg_path
        //    ⚠ 真机实测：这里拿到的往往是**外壳 bundle**（1.6MB，不含 processServerData），
        //    真正渲染「我的」页的 userCore bundle 是它的**依赖**，由 native 按下面的表加载。
        //    v2.0.1 只改这里 → 补丁文件写出来了、路径也改了，但页面代码从没执行过补丁
        //    （探针 __BMC_PATCH_ALIVE__ 零命中为证）。
        Object pv = call(pm, "getString", "biz_pkg_path");
        if (pv instanceof String) {
            String path = (String) pv;
            if (path.endsWith(".bundle") || path.endsWith(".js")) {
                String out = patchFile(path, js, via);
                if (out != null) {
                    call(pm, "putString", "biz_pkg_path", out);
                    sPatched++;
                    Cfg.log("mine js: " + via + " biz_pkg_path -> " + out + " (" + sPatched + ")");
                }
            }
        }

        // ② 依赖表 biz_require_map：{subbiz: 路径}，我的页的页面代码就在其中一项里
        Object rm = call(pm, "getMap", "biz_require_map");
        if (rm != null) {
            Object raw = call0(rm, "toMap");
            if (raw instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) raw;
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    Object v = e.getValue();
                    if (!(v instanceof String)) continue;
                    String out = patchFile((String) v, js, via + "/" + e.getKey());
                    if (out != null) {
                        call(rm, "putString", String.valueOf(e.getKey()), out);
                        sPatched++;
                        Cfg.log("mine js: " + via + " require[" + e.getKey() + "] -> " + out
                                + " (" + sPatched + ")");
                    }
                }
            }
        }
    }

    /** 打补丁：读原文件 → 判断是不是我的页 → 写同目录 index.bmc.<hash>.js → 返回新路径 */
    private static String patchFile(String path, String js, String via) {
        try {
            File f = new File(path);
            if (!f.isFile()) {
                Cfg.log("mine js: 目标不是文件 " + path);
                return null;
            }
            long len = f.length();
            if (len <= 0 || len > MAX_LEN) {
                Cfg.log("mine js: 大小异常 " + len + " " + path);
                return null;
            }
            String src = readAll(f);
            if (src == null) {
                Cfg.log("mine js: 读文件失败 " + path);
                return null;
            }
            if (src.indexOf(PAGE_MARK) < 0) {
                Cfg.log("mine js: 不含页面标记，跳过 " + f.getName() + " len=" + src.length());
                return null;
            }
            if (src.indexOf(PATCH_MARK) >= 0) {
                // 这个路径**已经是我们打过的补丁文件**（同一次会话里 loadJSFile 会被调多次，
                // 第二次拿到的 biz_pkg_path 就是我们上次改写过的那个）。
                // 正解：回到同目录的原始 bundle 重打（配置可能已变），拿不到原始 bundle 才复用现有补丁。
                File orig = new File(f.getParentFile(), ORIG_NAME);
                if (orig.isFile()) {
                    f = orig;
                    src = readAll(f);
                    if (src == null || src.indexOf(PAGE_MARK) < 0) {
                        Cfg.log("mine js: 原始 bundle 读取失败 " + f.getName());
                        return null;
                    }
                } else {
                    Cfg.log("mine js: 无原始 bundle，复用现有补丁 " + f.getName());
                    return f.getAbsolutePath();
                }
            }
            File dir = f.getParentFile();
            if (dir == null || !dir.isDirectory() || !dir.canWrite()) {
                Cfg.log("mine js: dir not writable " + dir);
                return null;
            }
            File out = new File(dir, outName(js));
            writeAll(out, injectInto(src, js));
            cleanupOldPacks(dir, out);
            if (Cfg.debug()) {
                Cfg.log("mine js: " + via + " patched " + f.getName() + " len "
                        + src.length() + " -> " + out.length());
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "mine js patchFile failed: " + t);
            return null;
        }
    }

    /** 清掉本目录里旧版本留下的补丁文件（每个约 870KB，别堆着） */
    private static void cleanupOldPacks(File dir, File keep) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (int i = 0; i < fs.length; i++) {
                String n = fs[i].getName();
                if (n.startsWith("index.bmc.") && n.endsWith(".js") && !fs[i].equals(keep)) {
                    if (!fs[i].delete() && Cfg.debug()) Cfg.log("mine js: cannot delete " + n);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 补丁文件名带上**内容哈希**：文件名变了，V8 code cache 的键就变了。
     *
     * ⚠ 踩过的坑：固定叫 `index.bmc.js` 时，改了补丁却不生效 ——
     * 原生侧按文件路径命中旧的 V8 编译缓存（v8_codecache_v76/*.dat），
     * 实际跑的还是上一版补丁（表现为"卡片能关、宫格关不掉"）。
     */
    private static String outName(String js) {
        return "index.bmc." + Integer.toHexString(js.hashCode()) + ".js";
    }

    /**
     * 把补丁**前置**到脚本最前面。
     *
     * ⚠ 这里踩过一次坑：最早是追加在末尾，结果 `Array.prototype` 装得**太晚**——
     * 页面 bundle 的结尾就是 `…}).start()}(),{}}()}));`，San 的 `start()` 在 IIFE 里
     * **同步**就跑了首屏，宫格的 `commonList`（`s[0].push(a)`）在补丁生效前就算完了
     * → 卡片能关、宫格关不掉。前置之后两者都在补丁之后，才一致。
     *
     * bundle 开头是 `!function(e,t){…`（没有 `"use strict"` 序言），所以前置不会破坏严格模式。
     */
    private static String injectInto(String src, String js) {
        return js + "\n;\n" + src;
    }

    /**
     * 生成补丁脚本。开关在**注入那一刻**读一次 —— 页面每次打开都会重新走 loadJSFile，
     * 所以改完开关重开该页即生效（不需要重启 App）。
     * 返回 null 表示这次没有任何要关的东西。
     */
    private static String buildPatch() {
        StringBuilder cards = new StringBuilder();
        boolean ops = !vis(Spec.K_MINE_OPS);
        boolean ad = !vis(Spec.K_MINE_AD);
        boolean carnav = !vis(Spec.K_MINE_CARNAV);
        // ① 广告/运营卡：v2.0.0 里是 gk 闸门覆盖的那一族，改成**逐卡名**摘，互不牵连。
        //    这是 v2.0.1 的关键修复 —— gk 是共享闸门，会把 我的车/热门语音/导航车标/热门活动
        //    的独立开关一起压住（用户实测"开关不生效"）。
        if (ops) cards.append("C.goldCoin=1;C.dxmFinance=1;C.shop=1;C.userOperation=1;");
        // ② 热门活动（gold 卡，还要求 campaignData.length>0）+ 资源位 banner
        if (ad) cards.append("C.gold=1;C.banner=1;");
        // ③ 导航车标：与「广告/运营卡」共用闸门（任一个关就摘）
        if (carnav || ops) cards.append("C.carLogo=1;");
        if (!vis(Spec.K_MINE_SPORT)) cards.append("C.sport=1;");
        if (!vis(Spec.K_MINE_BUILD)) cards.append("C.contribution=1;");
        if (!vis(Spec.K_MINE_CAR)) cards.append("C.car=1;");
        if (!vis(Spec.K_MINE_VOICE)) cards.append("C.voice=1;C.oldVoice=1;");
        boolean grid = !vis(Spec.K_MINE_GRID);
        // 宫格就是 cardList 里的 `common` 卡（tn 常量之一）→ 连卡壳一起摘掉，
        // 否则即使图标全被丢弃，那个白色空卡片和分页点还留在页面上。
        if (grid) cards.append("C.common=1;");
        if (!sCfgLogged) {
            sCfgLogged = true;
            Object p = Cfg.prefs();
            String raw = "prefs=" + (p == null ? "null" : p.getClass().getName());
            try {
                android.content.SharedPreferences sp = (android.content.SharedPreferences) p;
                raw += " car=" + (sp == null ? "?" : sp.getBoolean("mine_car", true))
                     + " grid=" + (sp == null ? "?" : sp.getBoolean("mine_grid", true))
                     + " sport=" + (sp == null ? "?" : sp.getBoolean("mine_sport", true))
                     + " dbg=" + (sp == null ? "?" : sp.getBoolean("debug_log", false))
                     + " keys=" + (sp == null ? "?" : sp.getAll().keySet());
            } catch (Throwable t) {
                raw += " rawread_fail=" + t;
            }
            Cfg.log("mine js cfg[diag]: " + raw
                    + " | vis car=" + vis(Spec.K_MINE_CAR) + " grid=" + vis(Spec.K_MINE_GRID)
                    + " sport=" + vis(Spec.K_MINE_SPORT) + " apply=" + Spec.mineEnabled()
                    + " debug=" + Cfg.debug());
        }
        if (cards.length() == 0 && !grid) return null;

        StringBuilder sb = new StringBuilder(1900);
        sb.append("(function(){try{")
          .append("var g=(typeof globalThis!=='undefined')?globalThis:(typeof global!=='undefined'?global:this);")
          // ⚠ 不能用"装过就 return"的一次性守卫：页面运行时可能被复用（同一 V8 上下文再次加载本页），
          //    那样新补丁会被旧守卫挡掉、实际跑的还是上一版闭包里的旧黑名单（v2.0.1 真机实测的坑）。
          //    现在的做法：**每次加载都刷新黑名单**，原型钩子只装一次，钩子读的是 g 上的最新黑名单。
          .append("var C=(g.__bmc_C={});")
          .append(cards)
          .append("g.__bmc_G=").append(grid ? 1 : 0).append(";")
          .append("try{g.talos&&g.talos.storage&&g.talos.storage.setItem&&g.talos.storage.setItem('__bmc_probe','2.0.1');}catch(e){}")
          .append("if(g.__bmc_hooked)return;g.__bmc_hooked=1;")
          .append("var _c=Array.prototype.concat;")
          .append("Array.prototype.concat=function(){var r=_c.apply(this,arguments);try{")
          .append("if(r&&r.length>4&&typeof r[0]==='string'&&r.indexOf('goldCoin')>=0&&r.indexOf('loading')>=0){")
          .append("var o=[],ch=0;for(var i=0;i<r.length;i++){var v=r[i];")
          .append("if(typeof v==='string'&&g.__bmc_C[v]){ch=1;continue;}o.push(v);}")
          .append("if(ch)return o;}}catch(e){}return r;};")
          // ② 宫格图标对象：整块关掉时丢弃。
          //    特征用 id+name+(link|icon)：**不能只认 baidumap:// 开头** ——
          //    实测漏网的三条（我的店铺/玩转地图/我的贡献）link 是 https 的。
          .append("var _p=Array.prototype.push;")
          .append("Array.prototype.push=function(){try{")
          .append("for(var i=0;i<arguments.length;i++){var x=arguments[i];")
          .append("if(x&&typeof x==='object'&&typeof x.id==='string'&&typeof x.name==='string'")
          .append("&&(typeof x.link==='string'||x.icon!==undefined)&&g.__bmc_G){return this.length;}}}")
          .append("catch(e){}return _p.apply(this,arguments);};")
          .append("}catch(e){}})();")
          // 诊断探针：补丁只要**执行**了，5 秒后就会抛一个带标记的异步错误，
          // Talos 的 JS 异常处理会把它打进 logcat —— 用来区分"补丁没跑"和"补丁跑了但没生效"。
          // 定位完成后这行会删掉（它只是诊断，不影响功能）。
          .append("try{setTimeout(function(){throw new Error('__BMC_PATCH_ALIVE__');},5000);}catch(e){}");
        return sb.toString();
    }

    private static boolean vis(String key) {
        return Cfg.visible(key, Spec.defaultVisible(key));
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 22));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeAll(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /** 零参调用（如 ParamMap#toMap()） */
    private static Object call0(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object call(Object target, String name, Object a1) throws Exception {
        Method m = find(target.getClass(), name, 1);
        m.setAccessible(true);
        return m.invoke(target, a1);
    }

    private static void call(Object target, String name, Object a1, Object a2) throws Exception {
        Method m = find(target.getClass(), name, 2);
        m.setAccessible(true);
        m.invoke(target, a1, a2);
    }

    private static Method find(Class<?> c, String name, int argc) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Method[] ms = k.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == argc) return ms[i];
            }
        }
        throw new NoSuchMethodException(c.getName() + "#" + name + "/" + argc);
    }
}
