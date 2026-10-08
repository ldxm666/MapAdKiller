package io.github.ldxm666.mapadkiller;

import android.widget.ImageView;
import org.json.JSONObject;

/** Uses AMap's own asynchronous image loader and its disk/memory caches. */
final class AmapToolIcons {
    static void load(ClassLoader cl, ImageView view, String image, JSONObject boot, String id) throws Exception {
        String token = image.startsWith("@") ? image : "";
        for (int i = 0; i < 10; i++) if (id.equals(boot.optString("id" + i))) {
            token = boot.optString("imageToken" + i, token); break;
        }
        if (!token.isEmpty()) {
            Object proxy = view.getClass().getMethod("proxy").invoke(view);
            proxy.getClass().getMethod("s", String.class).invoke(proxy, token);
        }
        if (image.startsWith("https://") || image.startsWith("http://")) {
            Object loader = cl.loadClass("com.amap.AppInterfaces").getMethod("getImageLoader").invoke(null);
            cl.loadClass("com.amap.imageloader.api.IImageLoader")
                .getMethod("bind", ImageView.class, String.class).invoke(loader, view, image);
        }
    }
}
