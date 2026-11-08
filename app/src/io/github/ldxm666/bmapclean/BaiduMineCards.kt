package io.github.ldxm666.bmapclean

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.WeakHashMap

/** Keep the native card model intact; filter only the verified user-center render loop. */
class BaiduMineCards private constructor() {
    companion object {
        const val EVENT = "mapclean.mine.cards.234"
        private const val FIELD = "__mapcleanMineCards234"
        private const val BIND = "__mapcleanMineBind234"
        private val runtimes = WeakHashMap<Any, Boolean>()
        private var handler: Handler? = null
        private val update = Runnable { publish() }

        @JvmStatic
        fun hidden(): List<String> {
            if (!Spec.mineEnabled()) return emptyList()
            val out = ArrayList<String>()
            fun add(key: String, vararg names: String) {
                if (!Cfg.visible(key, Spec.defaultVisible(key))) out.addAll(names)
            }
            add(Spec.K_MINE_GRID, "common")
            add(Spec.K_MINE_CAR, "car")
            add(Spec.K_MINE_VOICE, "voice", "oldVoice")
            add(Spec.K_MINE_CARNAV, "carLogo")
            add(Spec.K_MINE_SPORT, "sport")
            add(Spec.K_MINE_BUILD, "contribution", "ugc")
            add(Spec.K_MINE_OPS, "goldCoin", "dxmFinance", "userOperation", "shop")
            add(Spec.K_MINE_AD, "gold", "banner")
            return out
        }

        @JvmStatic
        fun track(runtime: Any) {
            synchronized(runtimes) { runtimes[runtime] = true }
            refresh(null)
            // Bundle loading precedes JS created(). Also deliver after the listener is ready.
            mainHandler()?.postDelayed({ publish() }, 1200)
        }

        @JvmStatic
        fun refresh(key: String?) {
            if (key != null && !key.startsWith("mine_")) return
            if (synchronized(runtimes) { runtimes.isEmpty() }) return
            val h = mainHandler() ?: return
            h.removeCallbacks(update)
            h.post(update)
            h.postDelayed(update, 350)
        }

        @Synchronized
        private fun mainHandler(): Handler? {
            if (handler == null) {
                val looper = Looper.getMainLooper() ?: return null
                handler = Handler(looper)
            }
            return handler
        }

        private fun publish() {
            val targets = synchronized(runtimes) { runtimes.keys.toList() }
            val config = JSONArray(hidden()).toString()
            for (runtime in targets) {
                try {
                    val context = runtime.javaClass.getMethod("getTalosRuntimeContext").invoke(runtime) ?: continue
                    // 22.1.20 DEX: ki2.d.o(String,Object) obtains TalosEventEmitter and calls emit.
                    // Resolve the unique signature rather than depending on that obfuscated name.
                    val emit = context.javaClass.methods.filter {
                        it.returnType == Void.TYPE && it.parameterTypes.contentEquals(
                                arrayOf(String::class.java, Any::class.java))
                    }.singleOrNull() ?: continue
                    emit.isAccessible = true
                    emit.invoke(context, EVENT, config)
                } catch (ignored: Throwable) {
                    // A collected/destroyed runtime must not affect preferences or other pages.
                }
            }
        }

        /** Supports the string template (userCore) and compiled San template (userCenter). */
        @JvmStatic
        fun script(source: String, hidden: List<String>): String {
            if (source.contains(BIND)) return source
            val model = "processCardList:function("
            val server = "processServerData:function("
            if (source.indexOf(model) < 0 || source.indexOf(model) != source.lastIndexOf(model)
                    || source.indexOf(server) < 0 || source.indexOf(server) != source.lastIndexOf(server)) return source
            val end = source.indexOf(model)
            val init = source.lastIndexOf("initData:function(", end)
            val created = source.indexOf("created:function(){", init)
            val detached = source.indexOf("detached:function(){", created)
            if (init < 0 || created <= init || created >= end || detached <= created || detached >= end) return source
            val textLoop = "s-for=\"cardName in cardList\""
            val compiledLoop = "for:{item:\"cardName\",value:{type:4,paths:[{type:1,value:\"cardList\"}]}}"
            val loop = when {
                source.contains(textLoop) -> textLoop
                source.contains(compiledLoop) -> compiledLoop
                else -> return source
            }
            if (source.indexOf(loop) != source.lastIndexOf(loop)) return source
            // All insertion offsets refer to the original source. Apply from right to left.
            var result = source.substring(0, detached + "detached:function(){".length) +
                    "this.__mapcleanMineDispose234&&this.__mapcleanMineDispose234();" +
                    source.substring(detached + "detached:function(){".length)
            result = result.substring(0, created + "created:function(){".length) + "$BIND(this);" +
                    result.substring(created + "created:function(){".length)
            result = result.replace(loop, loop.replace("cardList", FIELD))
            val js = "var $BIND=(function(){var C=" + JSONArray(hidden).toString() + ";" +
                    "return function(p){try{if(p.__mapcleanMineBound234)return;" +
                    "var d=p.data,b=p.\$app&&p.\$app.TalosEmitter;" +
                    "if(!d||!b||typeof p.watch!=='function')return;" +
                    "function apply(){var a=d.get('cardList');if(!Array.isArray(a))return;" +
                    "d.set('$FIELD',a.filter(function(v){return C.indexOf(v)<0;}));}" +
                    "function change(v){try{var c=typeof v==='string'?JSON.parse(v):v;" +
                    "if(!Array.isArray(c)||!c.every(function(x){return typeof x==='string';}))return;" +
                    "C=c;apply();}catch(e){}}" +
                    "b.on('$EVENT',change);p.watch('cardList',apply);p.__mapcleanMineBound234=1;" +
                    "p.__mapcleanMineDispose234=function(){try{b.off('$EVENT',change);}catch(e){}};" +
                    "apply();}catch(e){}};})();\n;\n"
            return js + result
        }
    }
}
