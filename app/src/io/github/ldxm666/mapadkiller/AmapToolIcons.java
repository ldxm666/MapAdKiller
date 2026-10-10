package io.github.ldxm666.mapadkiller;

import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/** Uses AMap's own asynchronous image loader and its disk/memory caches. */
final class AmapToolIcons {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static void load(final ClassLoader cl, final ImageView view, final String image, JSONObject boot, String id) {
        String token = image.startsWith("@") ? image : "";
        for (int i = 0; i < 10; i++) if (id.equals(boot.optString("id" + i))) {
            token = boot.optString("imageToken" + i, token); break;
        }
        final String imageToken = token;
        Runnable bind = new Runnable() {
            @Override public void run() {
                try {
                    if (!imageToken.isEmpty()) {
                        Object proxy = view.getClass().getMethod("proxy").invoke(view);
                        proxy.getClass().getMethod("s", String.class).invoke(proxy, imageToken);
                    }
                    if (image.startsWith("https://") || image.startsWith("http://")) {
                        Object loader = cl.loadClass("com.amap.AppInterfaces").getMethod("getImageLoader").invoke(null);
                        cl.loadClass("com.amap.imageloader.api.IImageLoader")
                            .getMethod("bind", ImageView.class, String.class).invoke(loader, view, image);
                    }
                } catch (Exception e) {
                    if (Config.debugLog()) H.log("amap_tool_icon failed=" + e.getClass().getSimpleName());
                }
            }
        };
        // The native BootUIPreloader builds this view on its HandlerThread.
        // Picasso requires the main looper; an icon failure must not replace the tool list.
        if (Looper.myLooper() == Looper.getMainLooper()) bind.run();
        else MAIN.post(bind);
    }
}
