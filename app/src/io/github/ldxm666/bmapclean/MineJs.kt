package io.github.ldxm666.bmapclean

import android.util.Log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method

import io.github.libxposed.api.XposedInterface

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
class MineJs private constructor() {

    companion object {

        @Volatile
        private var installed = false
        @Volatile
        private var sPatched = 0
        @Volatile
        private var sCfgLogged = false

        /** 我的页 bundle 独有：用它来判断"这一份是不是我的页" */
        private const val PAGE_MARK = "processServerData"
        private const val PATCH_MARK = "__bmc_patch"
        private const val MAX_LEN = 8L * 1024 * 1024

        @JvmStatic
        fun install(cl: ClassLoader) {
            if (installed) return
            installed = true
            val bridge = H.cls(cl, "com.baidu.talos.core.bridge.TalosBridge")
            hookLoad(bridge, "loadJSFile")
            hookLoad(bridge, "preLoadJSFile")
        }

        private fun hookLoad(bridge: Class<*>?, name: String) {
            // (ParamMap, com.baidu.talos.core.bridge.a)
            val m = H.method(bridge, name, 2)
            if (m == null) {
                H.log(Log.WARN, MainHook.TAG, "mine js: " + name + " not found")
                return
            }
            H.hook(m, "mine_js_" + name, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        redirect(chain, name)
                    } catch (t: Throwable) {
                        H.log(Log.WARN, MainHook.TAG, "mine js " + name + " failed: " + t)
                    }
                    return chain.proceed()
                }
            })
        }

        private fun redirect(chain: XposedInterface.Chain, via: String) {
            if (!Spec.mineEnabled()) return
            val js = buildPatch()
            if (js == null) return                       // 全开 = 不注入，零开销
            val args = chain.getArgs()
            if (args == null || args.size < 1 || args[0] == null) return
            val pm = args[0]!!
            val pv = call(pm, "getString", "biz_pkg_path")
            if (pv !is String) return
            val path = pv
            if (!path.endsWith(".bundle") && !path.endsWith(".js")) return

            val out = patchFile(path, js, via)
            if (out != null) {
                call(pm, "putString", "biz_pkg_path", out)
                sPatched++
                Cfg.log("mine js: " + via + " biz_pkg_path -> " + out + " (" + sPatched + ")")
            }
        }

        /** 打补丁：读原文件 → 判断是不是我的页 → 写同目录 index.bmc.js → 返回新路径 */
        private fun patchFile(path: String, js: String, via: String): String? {
            try {
                val f = File(path)
                if (!f.isFile) return null
                val len = f.length()
                if (len <= 0 || len > MAX_LEN) return null
                val src = readAll(f)
                if (src == null || src.indexOf(PAGE_MARK) < 0) return null    // 不是我的页
                if (src.indexOf(PATCH_MARK) >= 0) return null                 // 已含补丁（不该发生）
                val dir = f.parentFile
                if (dir == null || !dir.isDirectory || !dir.canWrite()) {
                    Cfg.log("mine js: dir not writable " + dir)
                    return null
                }
                val out = File(dir, outName(js))
                writeAll(out, injectInto(src, js))
                cleanupOldPacks(dir, out)
                if (Cfg.debug()) {
                    Cfg.log("mine js: " + via + " patched " + f.name + " len "
                            + src.length + " -> " + out.length())
                }
                return out.absolutePath
            } catch (t: Throwable) {
                H.log(Log.WARN, MainHook.TAG, "mine js patchFile failed: " + t)
                return null
            }
        }

        /** 清掉本目录里旧版本留下的补丁文件（每个约 870KB，别堆着） */
        private fun cleanupOldPacks(dir: File, keep: File) {
            try {
                val fs = dir.listFiles()
                if (fs == null) return
                for (i in 0 until fs.size) {
                    val n = fs[i].name
                    if (n.startsWith("index.bmc.") && n.endsWith(".js") && fs[i] != keep) {
                        if (!fs[i].delete() && Cfg.debug()) Cfg.log("mine js: cannot delete " + n)
                    }
                }
            } catch (ignored: Throwable) {}
        }

        /**
         * 补丁文件名带上**内容哈希**：文件名变了，V8 code cache 的键就变了。
         *
         * ⚠ 踩过的坑：固定叫 `index.bmc.js` 时，改了补丁却不生效 ——
         * 原生侧按文件路径命中旧的 V8 编译缓存（v8_codecache_v76/&#42;.dat），
         * 实际跑的还是上一版补丁（表现为"卡片能关、宫格关不掉"）。
         */
        private fun outName(js: String): String {
            return "index.bmc." + Integer.toHexString(js.hashCode()) + ".js"
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
        private fun injectInto(src: String, js: String): String {
            return js + "\n;\n" + src
        }

        /**
         * 生成补丁脚本。开关在**注入那一刻**读一次 —— 页面每次打开都会重新走 loadJSFile，
         * 所以改完开关重开该页即生效（不需要重启 App）。
         * 返回 null 表示这次没有任何要关的东西。
         */
        private fun buildPatch(): String? {
            val cards = StringBuilder()
            if (!vis(Spec.K_MINE_SPORT)) cards.append("C.sport=1;")
            if (!vis(Spec.K_MINE_BUILD)) cards.append("C.contribution=1;")
            if (!vis(Spec.K_MINE_CAR)) cards.append("C.car=1;")
            if (!vis(Spec.K_MINE_VOICE)) cards.append("C.voice=1;C.oldVoice=1;")
            if (!vis(Spec.K_MINE_CARNAV)) cards.append("C.carLogo=1;")
            val grid = !vis(Spec.K_MINE_GRID)
            // 宫格就是 cardList 里的 `common` 卡（tn 常量之一）→ 连卡壳一起摘掉，
            // 否则即使图标全被丢弃，那个白色空卡片和分页点还留在页面上。
            if (grid) cards.append("C.common=1;")
            if (!sCfgLogged) {
                sCfgLogged = true
                val p = Cfg.prefs()
                var raw = "prefs=" + (if (p == null) "null" else p.javaClass.name)
                try {
                    val sp: android.content.SharedPreferences? = p
                    raw += " car=" + (if (sp == null) "?" else sp.getBoolean("mine_car", true)) +
                            " grid=" + (if (sp == null) "?" else sp.getBoolean("mine_grid", true)) +
                            " sport=" + (if (sp == null) "?" else sp.getBoolean("mine_sport", true)) +
                            " dbg=" + (if (sp == null) "?" else sp.getBoolean("debug_log", false)) +
                            " keys=" + (if (sp == null) "?" else sp.getAll().keys)
                } catch (t: Throwable) {
                    raw += " rawread_fail=" + t
                }
                Cfg.log("mine js cfg[diag]: " + raw
                        + " | vis car=" + vis(Spec.K_MINE_CAR) + " grid=" + vis(Spec.K_MINE_GRID)
                        + " sport=" + vis(Spec.K_MINE_SPORT) + " apply=" + Spec.mineEnabled()
                        + " debug=" + Cfg.debug())
            }
            if (cards.length == 0 && !grid) return null

            val sb = StringBuilder(1700)
            sb.append("(function(){try{")
              .append("var g=(typeof globalThis!=='undefined')?globalThis:(typeof global!=='undefined'?global:this);")
              .append("if(g.__bmc_patch)return;g.__bmc_patch=1;")
              .append("var C={};").append(cards)
              .append("var G=").append(if (grid) 1 else 0).append(";")
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
              .append("}catch(e){}})();")
            return sb.toString()
        }

        private fun vis(key: String): Boolean {
            return Cfg.visible(key, Spec.defaultVisible(key))
        }

        private fun readAll(f: File): String? {
            val `in` = FileInputStream(f)
            try {
                val bos = ByteArrayOutputStream(Math.min(f.length(), (1 shl 22).toLong()).toInt())
                val buf = ByteArray(65536)
                var n = `in`.read(buf)
                while (n > 0) {
                    bos.write(buf, 0, n)
                    n = `in`.read(buf)
                }
                return String(bos.toByteArray(), Charsets.UTF_8)
            } finally {
                try { `in`.close() } catch (ignored: Throwable) {}
            }
        }

        private fun writeAll(f: File, s: String) {
            val out = FileOutputStream(f, false)
            try {
                out.write(s.toByteArray(Charsets.UTF_8))
                out.flush()
            } finally {
                try { out.close() } catch (ignored: Throwable) {}
            }
        }

        private fun call(target: Any?, name: String, a1: Any?): Any? {
            val m = find(target!!.javaClass, name, 1)
            m.isAccessible = true
            return m.invoke(target, a1)
        }

        private fun call(target: Any?, name: String, a1: Any?, a2: Any?) {
            val m = find(target!!.javaClass, name, 2)
            m.isAccessible = true
            m.invoke(target, a1, a2)
        }

        private fun find(c: Class<*>, name: String, argc: Int): Method {
            var k: Class<*>? = c
            while (k != null) {
                val ms = k.declaredMethods
                for (i in 0 until ms.size) {
                    if (ms[i].name == name && ms[i].parameterTypes.size == argc) return ms[i]
                }
                k = k.superclass
            }
            throw NoSuchMethodException(c.name + "#" + name + "/" + argc)
        }
    }
}