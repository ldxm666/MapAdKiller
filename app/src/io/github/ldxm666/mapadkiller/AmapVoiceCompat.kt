package io.github.ldxm666.mapadkiller

import io.github.libxposed.api.XposedInterface

/** Select the host's retained classic voice implementation, including its native lifecycle. */
object AmapVoiceCompat {
    @JvmStatic fun install(cl: ClassLoader) {
        val info = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP)
        // This cloud key and the two voice implementations were verified on the 17.02 DEX.
        // Unknown versions keep the native decision rather than guessing obfuscated methods.
        if (info?.versionName?.startsWith("17.02.") != true) return
        val cloud = H.cls(cl, "com.autonavi.bundle.vui.util.CloudController") ?: return
        val method = H.method(cloud, "b", Integer.TYPE, String::class.java) ?: return
        if (method.returnType != Integer.TYPE) return
        H.hookSig(cloud, "b", "amap_voice_classic", object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (chain.getArg(1) == "vui_llm_switch_new" &&
                    Config.visible(Config.K_VOICE_CLASSIC)) return 0
                return chain.proceed()
            }
        }, Integer.TYPE, String::class.java)
    }
}
