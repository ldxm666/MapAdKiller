package io.github.ldxm666.mapclean;

/**
 * Stub for the variants that do not carry 腾讯 (both 免 root 内置 builds).
 *
 * <p>The whole 腾讯 adapter is dropped from those builds, yet shared code still names this class:
 * {@link FloatingPanel} asks which vendor the orb is drawn over, and {@link FloatingConfig}
 * routes reads and writes through the matching panel. Neither question can be answered here, so
 * every answer is the safe one -- nothing to show, nothing to write, "leave the map alone".
 */
final class TmapPanel {

    private TmapPanel() {}

    static FloatingPanel.Group[] groups() { return new FloatingPanel.Group[0]; }

    static boolean writable() { return false; }
    static boolean hasStored(String key) { return false; }

    static boolean read(String key) { return true; }

    static boolean write(String key, boolean value) { return false; }

    static void invalidate() { }

    static void apply(String key) { }

    static void syncLearnedTools() { }
}
