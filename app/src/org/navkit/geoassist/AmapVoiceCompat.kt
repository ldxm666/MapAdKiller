package org.navkit.geoassist

import io.github.libxposed.api.XposedInterface

/** Select the host's retained classic voice implementation, including its native lifecycle. */
object AmapVoiceCompat {
    @JvmStatic fun install(cl: ClassLoader) {
        val info = org.navkit.core.Compatibility.packageInfo(cl, MainHook.PKG_AMAP)
        val ver = info?.versionName ?: return
        val cloud = H.cls(cl, "com.autonavi.bundle.vui.util.CloudController") ?: return
        if (ver.startsWith("17.03.")) {
            val g = H.method(cloud, "g")
            if (g != null && g.returnType == java.lang.Boolean.TYPE &&
                    java.lang.reflect.Modifier.isStatic(g.modifiers)) {
                H.hookSig(cloud, "g", "amap_voice_classic_v3", object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (Config.visible(Config.K_VOICE_CLASSIC)) return java.lang.Boolean.FALSE
                        return chain.proceed()
                    }
                })
            }
            return
        }
        // This cloud key and the two voice implementations were verified on the 17.02 DEX.
        // Unknown versions keep the native decision rather than guessing obfuscated methods.
        if (!ver.startsWith("17.02.")) return
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
