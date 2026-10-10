package io.github.ldxm666.mapclean;

/** Native style assets are selected once, before the map renderer initializes. */
public final class NativeLabelAssets {
    private NativeLabelAssets() {}
    private static native void configure(int map, int hiddenMask);
    private static native boolean configureBaidu(String[] originalPaths, String[] filteredPaths);
    public static boolean prepareBaidu(String[] originalPaths, String[] filteredPaths) {
        try {
            System.loadLibrary("mapclean_labels");
            return configureBaidu(originalPaths, filteredPaths);
        } catch (Throwable error) {
            android.util.Log.w("MapCleanLabels", "Native exact-file interface unavailable", error);
            return false;
        }
    }
    public static void start(String process) {
        int map = 0, mask = 0;
        if ("com.autonavi.minimap".equals(process)) {
            map = 1;
            mask = io.github.ldxm666.mapadkiller.Config.visible(io.github.ldxm666.mapadkiller.Config.K_MAP_POI) ? 0 : 1;
        } else return;
        try {
            System.loadLibrary("mapclean_labels");
            configure(map, mask);
        } catch (Throwable error) {
            android.util.Log.w("MapCleanLabels", "Native style interface unavailable", error);
        }
    }
}
