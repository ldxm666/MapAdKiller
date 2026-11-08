package io.github.ldxm666.bmapclean

/** Page-specific cardList assignments, never Array.prototype or generic JSON mutation. */
class BaiduPageScripts private constructor() {

    companion object {

        @JvmStatic
        fun signature(): String {
            val out = StringBuilder()
            for (c in Spec.cats())
                for (r in c.rows) {
                    val key = r.key ?: continue
                    out.append(if (Cfg.visible(key, Spec.defaultVisible(key))) '1' else '0')
                }
            return out.toString()
        }

        @JvmStatic
        fun adapt(module: String, source: String): String {
            if ("HomeFeed" == module) return filterFeed(source,
                    Spec.effectiveVisible(Spec.K_FEED_CHIPS, Spec.HOOK_READ),
                    Spec.effectiveVisible(Spec.K_FEED_QUALITY, Spec.HOOK_READ),
                    Spec.effectiveVisible(Spec.K_WX_CARD, Spec.HOOK_READ), Cfg.visible(Spec.K_HC))
            if ("userCore" == module || "userCenter" == module) {
                // Install the reversible binding even when all cards/the master are on/off.
                // A cached Talos runtime can then receive later preference changes.
                return filterMine(source, BaiduMineCards.hidden())
            }
            return source
        }

        @JvmStatic
        fun filterFeed(source: String, chips: Boolean, content: Boolean, weather: Boolean, addresses: Boolean): String {
            var src = source
            val marker = "xL.kind=Xk.kind,"
            // Verified HomeFeed 1.0.432.1 feed-list store, including both network and cached datasets.
            if (!src.contains(marker) || !src.contains("key:\"handleFeedListNew\",value:function(t){")) return src
            val filter = "function(v){var k=v&&v.content_type;" +
                    "if(k==='usual_address')return " + addresses + ";" +
                    "if(k==='travel_card'||k==='horizontal_top_card'||k==='feed_baikan_weather')return " + weather + ";" +
                    "return " + content + ";}"
            val hook = "!function(p){var set=p.set;function f(v){return Array.isArray(v)?v.filter(" + filter + "):v;}" +
                    "p.set=function(k,v){var a=Array.prototype.slice.call(arguments);" +
                    "if(typeof k==='string'&&k.indexOf('feedDatas.')===0&&k.slice(-5)==='.list')a[1]=f(v);" +
                    "else if(k==='feedDatas'&&v&&typeof v==='object'){var copy={};Object.keys(v).forEach(function(id){" +
                    "var item=v[id];copy[id]=item&&typeof item==='object'?Object.assign({},item,{list:f(item.list)}):item;});a[1]=copy;}" +
                    "return set.apply(this,a);};}(xL.prototype),"
            if (!content || !weather || !addresses) src = src.replace(marker, hook + marker)
            if (!chips) src = src.replace("s-if=\"isTabShow\"", "s-if=\"false\"")
            return src
        }

        @JvmStatic
        fun filterMine(source: String, hidden: List<String>): String {
            return BaiduMineCards.script(source, hidden)
        }
    }
}
