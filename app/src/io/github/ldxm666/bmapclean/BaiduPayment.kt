package io.github.ldxm666.bmapclean

import android.app.Application
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only observation: never dispatch/retry/rewrite a payment or its result.
 * No URLs, Intent extras, orders, credentials or full SDK result strings are logged.
 */
object BaiduPayment {
    private val installed = AtomicBoolean()
    private val armed = AtomicBoolean()
    private val channel = ThreadLocal<Boolean>()

    @JvmStatic fun install(cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val app = currentApplication()
        if (app != null) ready(app, cl)
        if (armed.get()) return
        H.hook(Application::class.java.getDeclaredMethod("onCreate"), "baidu_payment_ready",
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    (chain.getThisObject() as? Application)?.let { ready(it, cl) }
                    return result
                }
            })
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication")
            .invoke(null) as? Application
    } catch (_: Throwable) { null }

    private fun ready(app: Application, cl: ClassLoader) {
        if (app.packageName != MainHook.PKG_BMAP || armed.get()) return
        io.github.ldxm666.mapclean.HostPrefs.remember(app)
        Cfg.resetBackend()
        if (!Cfg.debug() || !armed.compareAndSet(false, true)) return
        observe(cl, "com.baidu.mapframework.api2imp.ComPayApiImp", "requestPloymerPay", 2, "cashier_request")
        observe(cl, "com.baidu.mapframework.sandbox.wallet.proxy.MapWalletProxy", "nuomiPloymerPay", 2, "cashier_dispatch")
        observe(cl, "com.baidu.poly.wallet.paychannel.WeChatPayChannel", "pay", 3, "wechat_channel", true)
        observe(cl, "com.baidu.poly.wallet.paychannel.AliPayChannel", "pay", 3, "alipay_channel", true)
        observe(cl, "com.baidu.android.lbspay.channelpay.wxpay.ChannelWXPay", "pay", 2, "wechat_legacy", true)
        observe(cl, "com.baidu.android.lbspay.channelpay.alipay.ChannelAliPay", "pay", 2, "alipay_legacy", true)
        observe(cl, "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10", "isWXAppInstalled", 0, "wechat_installed", onlyChannel = true)
        observe(cl, "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10", "getWXAppSupportAPI", 0, "wechat_api", onlyChannel = true)
        observe(cl, "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10", "sendReq", 1, "wechat_send", onlyChannel = true)
        observe(cl, "com.alipay.sdk.app.PayTask", "payV2", 2, "alipay_sdk")
        observe(cl, "com.baidu.poly.wallet.paychannel.WeChatPayChannel", "handlerPayResult", 2, "wechat_callback")
        H.log("event=payment_diag stage=armed process=" +
            if (Application.getProcessName().contains(":")) "child" else "main")
    }

    private fun observe(cl: ClassLoader, className: String, methodName: String, argc: Int,
                        stage: String, scope: Boolean = false, onlyChannel: Boolean = false) {
        val cls = H.cls(cl, className) ?: return
        // Skip ambiguous overloads after a vendor signature change.
        val methods = cls.declaredMethods.filter { it.name == methodName &&
            it.parameterTypes.size == argc && !Modifier.isAbstract(it.modifiers) }
        if (methods.size != 1) return
        H.hook(methods[0], "baidu_payment_$stage", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? =
                trace(chain, stage, scope, onlyChannel)
        })
    }

    private fun trace(chain: XposedInterface.Chain, stage: String, scope: Boolean,
                      onlyChannel: Boolean): Any? {
        if (!Cfg.debug() || (onlyChannel && channel.get() != true)) return chain.proceed()
        val previous = channel.get()
        if (scope) channel.set(true)
        note(stage, "enter")
        try {
            val result = chain.proceed() // exactly once, including when the original throws
            val value = try { safeResult(stage, result) } catch (_: Throwable) { null }
            note(stage, "return" + if (value == null) "" else " value=$value")
            return result
        } catch (error: Throwable) {
            note(stage, "throw type=" + error.javaClass.simpleName)
            throw error
        } finally {
            if (scope) { if (previous == null) channel.remove() else channel.set(previous) }
        }
    }

    private fun safeResult(stage: String, result: Any?): String? = when {
        result is Boolean -> result.toString()
        stage == "wechat_api" && result is Int -> result.toString()
        stage == "alipay_sdk" && result is Map<*, *> ->
            (result["resultStatus"] as? String)?.takeIf { it.matches(Regex("[0-9]{4}")) }
        else -> null
    }

    private fun note(stage: String, status: String) {
        try { H.log("event=payment_diag stage=$stage status=$status") } catch (_: Throwable) {}
    }
}
