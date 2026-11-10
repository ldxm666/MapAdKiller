package io.github.ldxm666.mapclean;

/**
 * Stub for the 百度-only variant.
 *
 * <p>Mirror of the 百度 stub: the 高德 catalogue and its config group are not compiled in, but
 * {@link FloatingPanel} and {@link NativeLabelAssets} still name this class, so it has to exist
 * and answer safely.
 */
final class AmapPanel {

    private AmapPanel() {}

    static FloatingPanel.Group[] groups() { return new FloatingPanel.Group[0]; }

    static boolean writable() { return false; }

    static boolean read(String key) { return true; }

    static boolean write(String key, boolean value) { return false; }

    static void invalidate() { }

    static void apply(String key) { }



    /** No 高德 config channel in this variant: nothing is writable through it. */
    static int writePath() { return 0; }        // FloatingConfig.WRITE_NONE

    static int effectiveBackend() { return 0; } // BACKEND_NONE

    static int lastWriteState() { return 0; }

    static boolean hasStored(String key) { return false; }

    static int readInt(String key, int fallback) { return fallback; }

    static boolean writeInt(String key, int value) { return false; }
    /** No 高德 write path in this variant: nothing to report. */
    static String lastWriteReason() { return null; }

    /** 高德's strict read is not compiled in; report the safe default (visible). */
    static boolean readStrict(String key) { return true; }
    /** Visible = do not touch the map; 高德's filter is not compiled into this variant. */
    static boolean mapPoiVisible() { return true; }
    static int mapPoiHiddenMask() { return 0; }
}
