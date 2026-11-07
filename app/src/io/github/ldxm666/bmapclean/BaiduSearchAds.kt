package io.github.ldxm666.bmapclean

import android.view.View
import android.view.ViewGroup

import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

import org.json.JSONArray

import io.github.libxposed.api.XposedInterface

/** Search recommendations have two independent producers: the Mystique header
 * and PoiNoteListPresenter's RankingInfo row appended to historyDataList.
 * Remove only the verified ranking model/type and preserve unknown entries,
 * including cache reads when the page returns from search results.
 */
class BaiduSearchAds private constructor() {

    companion object {

        @JvmStatic
        fun install(loader: ClassLoader) {
            installRankingList(loader)
            val version = io.github.ldxm666.mapclean.Compatibility.packageInfo(loader, MainHook.PKG_BMAP)
            // 22.0.0 (1650) 起生效。旧写法是 `versionCode == 1650`，等于 22.0.40 /
            // 22.0.50 / 22.1.0 整段被跳过 —— 搜索框那一栏的推广就是这么漏出来的
            // （用户报障「搜索框下一栏隐藏失效，开关无用」）。
            // 反射段自带 try/catch + schema 校验（见 installSearchPromotions 末尾的
            // ReflectiveOperationException 分支），版本不符只会记 unsupported，不会崩。
            if (version == null || version.versionCode >= 1650) installSearchPromotions(loader)
            val presenter = H.cls(loader, "com.baidu.baidumaps.poi.newpoi.home.presenter.PoiRecommendHotWordPresenter")
            val template = H.cls(loader, "com.baidu.entity.pb.HwResult\$ReciTemplate")
            BaiduHooks.hook(presenter, "onCreateView", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    collapse(chain.thisObject)
                    return null
                }
            })
            if (template != null) BaiduHooks.hook(presenter, "handleRecommend", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    collapse(chain.thisObject)
                    return null
                }
            }, template, Boolean::class.javaPrimitiveType!!)
        }

        @JvmStatic
        private fun installSearchPromotions(loader: ClassLoader) {
            val home = H.cls(loader, "com.baidu.baidumaps.aihome.search.SearchPresenter")
            val hintLogs = AtomicInteger()
            // Only advertctrl's box_txt JSONArray enters handleDatas(). Empty
            // data follows the host's own default hint/model reset and UI dispatch.
            BaiduHooks.hook(home, "handleDatas", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (hintLogs.getAndIncrement() < 4) H.log("event=baidu_search_promotion_drop source=box_txt")
                    return chain.proceed(arrayOf<Any?>(JSONArray()))
                }
            }, JSONArray::class.java)

            val header = H.cls(loader, "com.baidu.baidumaps.poi.newpoi.home.presenter.PoiSearchNewNaHeaderPresenter")
            val manager = H.cls(loader, "com.baidu.baidumaps.poi.newpoi.home.presenter.PoiSearchNewNaHeaderPresenter\$ViewManager")
            try {
                if (header == null || manager == null) return
                val addresses = header.getDeclaredMethod("refreshAddressCapsuleView")
                val managerField = header.getDeclaredField("viewManager")
                val guessCard = manager.getDeclaredMethod("l")
                if (addresses.returnType !== java.lang.Void.TYPE || managerField.type !== manager
                        || guessCard.returnType !== View::class.java) return
                addresses.isAccessible = true
                managerField.isAccessible = true
                guessCard.isAccessible = true
                val chipLogs = AtomicInteger()
                // Reuse the native address builder for home/company/favorites and
                // saved addresses. Jingang category buttons are updated separately.
                BaiduHooks.hook(header, "refreshCapsuleView", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        addresses.invoke(chain.thisObject)
                        if (chipLogs.getAndIncrement() < 4) H.log("event=baidu_search_promotion_drop source=query_chips")
                        return null
                    }
                })
                // Address rendering calls this independent RcmdWordInfo card.
                // Collapse its exact getter; never touch the address/category rows.
                BaiduHooks.hook(header, "refreshGuessCard", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val view = guessCard.invoke(managerField.get(chain.thisObject))
                        if (view is View) view.visibility = View.GONE
                        return null
                    }
                })
            } catch (ignored: ReflectiveOperationException) {
                H.log("event=baidu_search_promotion_unsupported reason=header_schema")
            }
        }

        @JvmStatic
        private fun installRankingList(loader: ClassLoader) {
            val presenter = H.cls(loader, "com.baidu.baidumaps.poi.newpoi.home.presenter.PoiNoteListPresenter")
            val note = H.cls(loader, "com.baidu.baidumaps.poi.model.PoiNoteModel")
            val sug = H.cls(loader, "com.baidu.baidumaps.poi.model.PoiSugModel")
            var type: Field? = null
            try {
                if (note != null && sug != null && note.superclass === sug) {
                    val candidate = sug.getDeclaredField("type")
                    if (candidate.type === Int::class.javaPrimitiveType) {
                        candidate.isAccessible = true
                        type = candidate
                    }
                }
            } catch (ignored: ReflectiveOperationException) {}
            if (type == null) {
                H.log("event=baidu_hot_ranking_unsupported reason=model_schema")
                return
            }
            val rankingType = type
            val logs = AtomicInteger()
            // In Baidu 22.0.0, handleNoteData() supplies only RankingInfo as a
            // PoiNoteModel(type=18). The merger receives no search/history rows.
            BaiduHooks.hook(presenter, "addRecommendDataToHisList", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val input = chain.getArg(0)
                    val output = withoutRanking(input, note, rankingType)
                    logRankingDrop(logs, "merge", input, output)
                    return if (input === output) chain.proceed() else chain.proceed(arrayOf<Any?>(output))
                }
            }, ArrayList::class.java)
            // PoiHistoryPresenter.initHistoryDataList() appends this cached feed
            // after constructing the actual history and its clear/more controls.
            BaiduHooks.hook(presenter, "getNoteDataList", object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val input = chain.proceed()
                    val output = withoutRanking(input, note, rankingType)
                    logRankingDrop(logs, "cache", input, output)
                    return output
                }
            })
        }

        @Throws(IllegalAccessException::class)
        @JvmStatic
        private fun withoutRanking(input: Any?, note: Class<*>?, type: Field): Any? {
            if (input !is List<*>) return input
            val rows = input
            var kept: ArrayList<Any?>? = null
            for (i in 0 until rows.size) {
                val row = rows[i]
                if (row != null && row.javaClass === note && type.getInt(row) == 18) {
                    if (kept == null) kept = ArrayList(rows.subList(0, i))
                } else if (kept != null) kept.add(row)
            }
            return if (kept == null) input else kept
        }

        @JvmStatic
        private fun logRankingDrop(logs: AtomicInteger, source: String, input: Any?, output: Any?) {
            if (logs.getAndIncrement() < 8) H.log("event=baidu_hot_ranking_drop source=" + source
                    + " dropped=" + (if (input is List<*> && output is List<*>) input.size - output.size else 0))
        }

        @Throws(Exception::class)
        @JvmStatic
        private fun collapse(presenter: Any?) {
            val component = BaiduHooks.field(presenter, "component")
            val list = BaiduHooks.field(component, "listPresenter")
            val binding = BaiduHooks.field(list, "headerBinding")
            val container = BaiduHooks.field(binding, "recHotWordContainer")
            if (container !is View) return
            val view = container
            view.minimumHeight = 0
            view.visibility = View.GONE
            if (view is ViewGroup && view.childCount != 0) view.removeAllViews()
        }
    }
}