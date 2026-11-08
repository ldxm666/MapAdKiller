package io.github.ldxm666.mapadkiller

import android.util.Log

import io.github.libxposed.api.XposedInterface

/**
 * \u300c\u6062\u590d\u9ad8\u5fb7**\u5b98\u65b9**\u81ea\u52a8\u8fdb\u5165\u5de1\u822a\u300d\u3002
 *
 * <h3>\u4e3a\u4ec0\u4e48\u9700\u8981\u8fd9\u4e2a</h3>
 * 17.02.0.1541 \u7684\u5b98\u65b9\u72b6\u6001\u673a\u5b8c\u6574\u5b58\u5728\u3001\u4e5f\u80fd\u8dd1\uff0c\u53ea\u662f**\u6ca1\u4eba\u628a\u5b83\u5e03\u9632\u8d77\u6765**\u3002DEX \u5b9e\u67e5\u94fe\u8def\uff1a
 * <pre>
 *   SpeedDetectManager  \u2014\u2014 requestLocationUpdates("gps", 2000ms, 0f, listener)
 *     \u2514\u2500 SpeedDetectManager$a#onLocationChanged(Location)  \u2192 Location.getSpeed()
 *          \u2514\u2500 Lnt6$a#onCurrentSpeed(F)
 *               \u00b7 \u9608\u503c 0x40b1eb85 = 5.56 m/s = **20.0 km/h**\uff1b\u2264 \u9608\u503c \u5c31\u6e05\u96f6\u8ba1\u65f6\u8fd4\u56de
 *               \u00b7 now - mSpeedTimeMillis < 0x2710 = **10000ms** \u5219\u8fd4\u56de
 *               \u00b7 \u2265 10s \u2192 ModuleRouteDriveResult#startDriveRadarPage()
 * </pre>
 * \u5e03\u9632\u5165\u53e3\u53ea\u6709\u4e00\u4e2a\uff1a
 * <pre>
 *   JS \u2192 ModuleRouteDriveResultImpl#setAutoEnterDriveRadar(Z)
 *      \u2192 AjxBaseResultPage#setAutoEnterDriveRadar(Z)   \u2190 \u5185\u90e8 new SpeedDetectManager
 * </pre>
 *
 * <h3>\u4e0e\u9690\u79c1\u4e0e\u7a33\u5b9a\u6027\u6709\u5173\u7684\u4e00\u4e2a\u5b9e\u6d4b\u7ed3\u8bba</h3>
 * \u8bbe\u7f6e\u9875\u90a3\u4e2a\u300c\u81ea\u52a8\u8fdb\u5165\u5de1\u822a\u300d\u5f00\u5173 <b>Java \u4fa7\u96f6\u843d\u70b9</b>\uff1a
 * 8 \u4e2a dex \u5b57\u7b26\u4e32\u6c60\u5bf9 `MainMapCruise` / `mainCruiseSwitch` / `minSpeed`
 * / `quickBackTime` / `foregroundContinue` \u5168\u90e8 0 \u547d\u4e2d\uff1b\u8bbe\u5907\u4e0a shared_prefs
 * \u5168\u91cf grep `cruise` \u53ea\u6709\u4e91\u63a7/\u8f66\u578b\u7f13\u5b58 7 \u4e2a\u6587\u4ef6\uff0cMMKV 0 \u547d\u4e2d\u3002
 * \u5f00\u5173\u503c\u53ea\u5728 AJX(JS) \u6216\u670d\u52a1\u7aef\u3002\u800c JS \u4ece\u4e0d\u4e0b\u53d1
 * `setAutoEnterDriveRadar(true)` \u21d2 `AjxBaseResultPage` \u91cc\u90a3\u4e2a
 * `SpeedDetectManager` \u6c38\u8fdc\u662f null \u21d2 GPS \u4e0d\u6ce8\u518c \u21d2 \u56de\u8c03\u6c38\u4e0d\u6765 \u21d2
 * `startDriveRadarPage()` \u6c38\u4e0d\u89e6\u53d1\u3002\u4e91\u63a7\u5199\u7740 `switch:1` \u4e5f\u6ca1\u4eba\u8bfb\u3002
 *
 * <h3>\u505a\u6cd5</h3>
 * \u628a\u90a3\u4e2a\u5e03\u9632\u5165\u53e3\u7684\u53c2\u6570**\u5f3a\u5236\u6210 true**\u3002\u4e00\u65e6\u5e03\u9632\u6210\u529f\uff0c\u540e\u9762\u5168\u4ea4\u7ed9\u5b98\u65b9\uff1a
 * \u4ec0\u4e48\u65f6\u5019\u8fdb\uff0820km/h \u00d7 10s\uff09\u3001\u4ec0\u4e48\u65f6\u5019\u9000\u3001\u524d\u53f0/\u540e\u53f0\u600e\u4e48\u5224\uff0c\u90fd\u662f\u5b98\u65b9\u81ea\u5df1\u7684\u903b\u8f91\u3002
 * \u8fd9\u6bd4\u6a21\u5757\u81ea\u5df1\u9020\u4e00\u5957\u72b6\u6001\u673a\u7a33\u5f97\u591a \u2014\u2014 \u5b98\u65b9\u7684\u8fdb\u5165\u52a8\u4f5c\u8d70\u7684\u662f JS \u6865
 * (`startDriveRadarPage`) \u4e0e\u771f\u6b63\u7684 `DriveRadar.page.js`\uff0c\u4e0d\u4f1a\u51fa\u73b0\u300c\u8fdb\u4e86\u9875\u9762\u4f46\u6ca1\u5de1\u822a\u64ad\u62a5\u300d\u7684\u534a\u6b8b\u72b6\u6001\u3002
 *
 * <p><b>\u4e0e\u6a21\u5757\u63a5\u7ba1\u7684\u5173\u7cfb</b>\uff1a\u4e24\u6761\u8def\u4e92\u65a5\u5730\u5e76\u5b58\u3002\u8fd9\u91cc\u6062\u590d\u7684\u662f\u5b98\u65b9\uff08\u63a8\u8350\uff09\uff1b
 * [AmapCruise] \u662f\u515c\u5e95\uff08\u5b98\u65b9\u9875\u9762\u6ca1\u751f\u6548\u65f6\u624d\u9700\u8981\uff09\u3002\u4e24\u8005\u5f00\u5173\u72ec\u7acb\u3002
 *
 * <h3>\u5b89\u5168\u8fb9\u754c</h3>
 * <ul>
 *   <li>\u53ea\u6539\u4e00\u4e2a\u5e03\u5c14\u53c2\u6570 <b>false \u2192 true</b>\uff0c\u4ece\u4e0d\u628a true \u6539\u6210 false\uff08\u90a3\u4f1a\u53d6\u6d88\u7528\u6237\u81ea\u5df1\u5f00\u7684\uff09\uff1b</li>
 *   <li>\u53ea\u5728\u53c2\u6570\u4e2a\u6570\u4e0e\u7c7b\u578b\u5b8c\u5168\u5339\u914d\u65f6\u624d\u6539\uff1b\u62ff\u4e0d\u51c6\u5c31\u539f\u6837\u8f6c\u53d1\uff1b</li>
 *   <li>\u4efb\u4f55\u5f02\u5e38\u9759\u9ed8\u964d\u7ea7\uff0c\u7edd\u4e0d\u5d29\u5bbf\u4e3b\u3002</li>
 * </ul>
 */
object AmapCruiseOfficial {

    /** \u5e03\u9632\u5165\u53e3\uff08JS \u6865\u4fa7\uff09\u3002 */
    private const val CLS_MODULE = "com.amap.bundle.drive.ajx.module.ModuleRouteDriveResultImpl"

    /** \u5e03\u9632\u5165\u53e3\uff08\u9875\u9762\u4fa7\uff0c\u5185\u90e8 new SpeedDetectManager\uff09\u3002 */
    private const val CLS_PAGE =
            "com.amap.bundle.drive.result.driveresult.opt.page.AjxBaseResultPage"

    @JvmStatic
    fun install(cl: ClassLoader) {
        // Install once, consult the live preference in the hook. An initially OFF
        // process must still be able to enable this feature without restarting.

        // Both CN and Play 17.02 expose these exact single-boolean entry points.
        // Do not intercept unrelated overloads when the native API changes.
        var n = 0
        try {
            val before = H.ok.get()
            H.hookSig(H.cls(cl, CLS_MODULE), "setAutoEnterDriveRadar",
                    "amap_cruise_official_module", FORCE_TRUE, Boolean::class.javaPrimitiveType)
            n += H.ok.get() - before
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_official_fail part=module", t)
        }
        try {
            val before = H.ok.get()
            H.hookSig(H.cls(cl, CLS_PAGE), "setAutoEnterDriveRadar",
                    "amap_cruise_official_page", FORCE_TRUE, Boolean::class.javaPrimitiveType)
            n += H.ok.get() - before
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "event=cruise_official_fail part=page", t)
        }

        H.log(Log.INFO, MainHook.TAG, "event=cruise_official_install hooks=" + n)
        if (n == 0) {
            // \u4e00\u4e2a\u90fd\u6ca1\u6302\u4e0a\uff1a\u8981\u4e48\u7248\u672c\u6f02\u4e86\uff0c\u8981\u4e48\u7c7b\u540d\u53d8\u4e86\u3002
            // \u4e0d\u58f0\u4e0d\u54cd\u5730\u9000\u5316\u6bd4\u62a5\u9519\u66f4\u7cdf \u2014\u2014 \u7528\u6237\u4f1a\u4ee5\u4e3a\u5f00\u5173\u5f00\u4e86\u3002
            H.log(Log.WARN, MainHook.TAG, "event=cruise_official_miss class="
                    + CLS_PAGE + " reason=anchor_not_found")
        }
    }

    /**
     * \u628a\u53c2\u6570\u91cc\u7684 `false` \u6539\u6210 `true`\uff0c\u7136\u540e\u539f\u6837\u8f6c\u53d1\u3002
     *
     * <p>\u53ea\u6539 false\u2192true\u3002\u7528\u6237\u81ea\u5df1\u5173\u6389\u4e86\u5b98\u65b9\u5f00\u5173\u7684\u60c5\u51b5\u4e0d\u5728\u8fd9\u91cc\u5904\u7406
     * \u2014\u2014 \u90a3\u662f\u672c\u6a21\u5757\u7684\u603b\u5f00\u5173 [Config.K_CRUISE_OFFICIAL] \u7684\u804c\u8d23\u3002
     */
    private val FORCE_TRUE: XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            var replacement: Array<Any?>? = null
            try {
                val list = if (Config.cruiseOfficial()) chain.getArgs() else null
                if (list != null && list.size == 1 && list[0] is Boolean) {
                    val args = list.toTypedArray()
                    var changed = false
                    for (i in args.indices) {
                        if (java.lang.Boolean.FALSE == args[i]) {
                            args[i] = java.lang.Boolean.TRUE
                            changed = true
                        }
                    }
                    if (changed) {
                        H.log(Log.INFO, MainHook.TAG, "event=cruise_official_forced method="
                                + chain.executable.name)
                        replacement = args
                    }
                }
            } catch (t: Throwable) {
                // \u6539\u53c2\u5931\u8d25\u5c31\u5f53\u6ca1\u6539\u8fc7\uff1a\u539f\u6837\u8f6c\u53d1\u6bd4\u625b\u5f02\u5e38\u5b89\u5168\u3002
                H.log(Log.WARN, MainHook.TAG, "event=cruise_official_argfail err="
                        + t.javaClass.simpleName)
            }
            // Never catch an exception from the native method and proceed a second time.
            return if (replacement != null) chain.proceed(replacement!!) else chain.proceed()
        }
    }
}
