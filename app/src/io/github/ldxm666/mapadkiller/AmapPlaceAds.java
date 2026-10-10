package io.github.ldxm666.mapadkiller;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;

/** Filters the POI-detail feed before AJX lays out its modules, including refreshed pages. */
public final class AmapPlaceAds {
    private static final Map<Object, Boolean> requests = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final AtomicBoolean reported = new AtomicBoolean();
    private AmapPlaceAds() {}

    public static void install(ClassLoader cl) throws Exception {
        final Class<?> request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest");
        final Class<?> callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        final Class<?> module = H.cls(cl, "com.autonavi.minimap.ajx3.modules.AbstractModule");
        final Class<?> context = H.cls(cl, "com.autonavi.minimap.ajx3.context.IAjxContext");
        if (request == null || callback == null || module == null || context == null) return;
        final Method getContext = module.getMethod("getContext");
        final Method getPath = context.getMethod("getJsPath");
        XposedInterface.Hooker register = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object options = chain.getArg(1);
                    JSONObject source = options instanceof JSONObject ? (JSONObject) options : new JSONObject((String) options);
                    if (AmapPlaceData.DETAIL_PATH.equals(AmapData.requestPath(source.optString("url")))) {
                        Object pageContext = getContext.invoke(chain.getThisObject());
                        Object page = pageContext == null ? null : getPath.invoke(pageContext);
                        if (detailPage(page) && chain.getArg(2) != null) requests.put(chain.getArg(2), Boolean.TRUE);
                    }
                } catch (Exception ignored) {}
                return chain.proceed();
            }
        };
        H.hookSig(request, "fetch", "amap_place_request_fetch", register, String.class, String.class, callback);
        H.hookSig(request, "sendHttp", "amap_place_request_http", register, String.class, JSONObject.class, callback);
        H.hookSig(request, "notifyJs", "amap_place_response_xhr", response(true),
                callback, int.class, int.class, String.class, String.class, int.class, String.class, String.class);
        H.hookSig(request, "notifyResponse", "amap_place_response_http", response(false),
                callback, int.class, JSONObject.class, String.class, int.class, String.class);
    }

    private static boolean detailPage(Object value) {
        if (!(value instanceof String)) return false;
        String path = (String) value;
        int query = path.indexOf('?'), fragment = path.indexOf('#');
        int end = query < 0 ? path.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        path = path.substring(0, end);
        return "path://amap_bundle_poi/src/poi.jsx.js".equals(path)
                || "path://amap_bundle_poi/src/FirstPoint.page.js".equals(path);
    }

    private static XposedInterface.Hooker response(final boolean xhr) {
        return new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (xhr && !Integer.valueOf(4).equals(chain.getArg(2))) return chain.proceed();
                boolean scoped = Boolean.TRUE.equals(requests.remove(chain.getArg(0)));
                if (!scoped || !Integer.valueOf(200).equals(chain.getArg(1)) || !(chain.getArg(3) instanceof String))
                    return chain.proceed();
                String raw = (String) chain.getArg(3);
                String filtered = AmapPlaceData.response(AmapPlaceData.DETAIL_PATH, raw, Config.visible(Config.K_PLACE_RECOMMEND));
                if (raw.equals(filtered)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[3] = filtered;
                if (reported.compareAndSet(false, true)) H.log("event=amap_place_feed_drop modules=WaterFeedTitle,WaterFeed");
                return chain.proceed(args);
            }
        };
    }
}
