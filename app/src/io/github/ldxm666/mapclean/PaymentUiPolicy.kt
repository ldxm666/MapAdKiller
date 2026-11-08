package io.github.ldxm666.mapclean

/** SDK namespaces verified in supported APKs; never match generic "pay" text. */
object PaymentUiPolicy {
    private val prefixes = arrayOf(
        "com.baidu.poly.", "com.baidu.wallet.", "com.dxmpay.wallet.",
        "com.duxiaoman.wallet.", "com.baidu.android.lbspay.",
        "com.baidu.BaiduMap.wxapi.", "com.tencent.map.wxapi.",
        "com.autonavi.minimap.wxapi.", "com.alipay.sdk.", "com.unionpay."
    )
    @JvmStatic fun protectedActivity(name: String?): Boolean =
        name != null && prefixes.any { name.startsWith(it) }
}
