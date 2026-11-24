package org.navkit.geoassist;

/**
 * Stub for the slim (高德-only) variant.
 *
 * The real 腾讯 adapter is not compiled into this build. {@code mapadkiller.MainHook}
 * still dispatches on package name, so the entry point has to exist; it deliberately does
 * nothing and says so, rather than silently pretending to work.
 */
public final class TmapHooks {
    private TmapHooks() {}
    public static void install(final ClassLoader cl) {
        H.log("event=variant_skip vendor=tencent reason=not-included");
    }
}
