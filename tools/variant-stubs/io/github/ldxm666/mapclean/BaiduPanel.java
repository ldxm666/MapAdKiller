package io.github.ldxm666.mapclean;

/**
 * Stub for the 高德-only variant.
 *
 * <p>The whole 百度 adapter is dropped from this build, yet two pieces of shared code still name
 * this class: {@link FloatingPanel} asks which vendor the orb is drawn over, and
 * {@link NativeLabelAssets} asks for the POI switch. Neither question can be answered here, so
 * both answers are the safe ones -- nothing to show, and "leave the map alone".
 */
final class BaiduPanel {

    private BaiduPanel() {}

    static FloatingPanel.Group[] groups() { return new FloatingPanel.Group[0]; }

    static boolean writable() { return false; }
    static boolean hasStored(String key) { return false; }

    static boolean read(String key) { return true; }

    static boolean write(String key, boolean value) { return false; }

    static void invalidate() { }

    static void apply(String key) { }

    /** Visible = do not touch the map; the 百度 filter is not compiled into this variant. */
    static boolean mapPoiVisible() { return true; }
}
