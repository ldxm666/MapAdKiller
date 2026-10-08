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
    private static volatile boolean sCfgLogged;

    /** 我的页 bundle 独有：用它来判断"这一份是不是我的页" */
    private static final String PAGE_MARK = "processServerData";
    private static final String PATCH_MARK = "__bmc_patch";
    private static final long MAX_LEN = 8L * 1024 * 1024;

    public static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        Class<?> bridge = H.cls(cl, "com.baidu.talos.core.bridge.TalosBridge");
        hookLoad(bridge, "loadJSFile");
        hookLoad(bridge, "preLoadJSFile");
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
        Object pv = call(pm, "getString", "biz_pkg_path");
        if (!(pv instanceof String)) return;
        String path = (String) pv;
        if (!path.endsWith(".bundle") && !path.endsWith(".js")) return;

        String out = patchFile(path, js, via);
        if (out != null) {
            call(pm, "putString", "biz_pkg_path", out);
            sPatched++;
            Cfg.log("mine js: " + via + " biz_pkg_path -> " + out + " (" + sPatched + ")");
        }
    }

    /** 打补丁：读原文件 → 判断是不是我的页 → 写同目录 index.bmc.js → 返回新路径 */
    private static String patchFile(String path, String js, String via) {
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            long len = f.length();
            if (len <= 0 || len > MAX_LEN) return null;
            String src = readAll(f);
            if (src == null || src.indexOf(PAGE_MARK) < 0) return null;    // 不是我的页
            if (src.indexOf(PATCH_MARK) >= 0) return null;                 // 已含补丁（不该发生）
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
        if (!vis(Spec.K_MINE_SPORT)) cards.append("C.sport=1;");
        if (!vis(Spec.K_MINE_BUILD)) cards.append("C.contribution=1;");
        if (!vis(Spec.K_MINE_CAR)) cards.append("C.car=1;");
        if (!vis(Spec.K_MINE_VOICE)) cards.append("C.voice=1;C.oldVoice=1;");
        if (!vis(Spec.K_MINE_CARNAV)) cards.append("C.carLogo=1;");
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

        StringBuilder sb = new StringBuilder(1700);
        sb.append("(function(){try{")
          .append("var g=(typeof globalThis!=='undefined')?globalThis:(typeof global!=='undefined'?global:this);")
          .append("if(g.__bmc_patch)return;g.__bmc_patch=1;")
          .append("var C={};").append(cards)
          .append("var G=").append(grid ? 1 : 0).append(";")
          // ① cardList：只对"卡片名数组"过滤（特征：同时含 goldCoin 与 loading）
          .append("var _c=Array.prototype.concat;")
          .append("Array.prototype.concat=function(){var r=_c.apply(this,arguments);try{")
          .append("if(r&&r.length>4&&typeof r[0]==='string'&&r.indexOf('goldCoin')>=0&&r.indexOf('loading')>=0){")
          .append("var o=[],ch=0;for(var i=0;i<r.length;i++){var v=r[i];")
          .append("if(typeof v==='string'&&C[v]){ch=1;continue;}o.push(v);}")
          .append("if(ch)return o;}}catch(e){}return r;};")
          // ② 宫格图标对象：整块关掉时丢弃。
          //    特征用 id+name+(link|icon)：**不能只认 baidumap:// 开头** ——
          //    实测漏网的三条（我的店铺/玩转地图/我的贡献）link 是 https 的。
          .append("var _p=Array.prototype.push;")
          .append("Array.prototype.push=function(){try{")
          .append("for(var i=0;i<arguments.length;i++){var x=arguments[i];")
          .append("if(x&&typeof x==='object'&&typeof x.id==='string'&&typeof x.name==='string'")
          .append("&&(typeof x.link==='string'||x.icon!==undefined)&&G){return this.length;}}}")
          .append("catch(e){}return _p.apply(this,arguments);};")
          .append("}catch(e){}})();");
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
