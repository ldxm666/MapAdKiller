package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;

/** Exact signatures verified from the installed 17.00.0.2005 DEX via JADX MCP. */
public final class AmapProtocolHooks {
    private static final Map<Object, Boolean> sessions = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, String> requests = Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final java.util.Set<String> reportedCards = Collections.synchronizedSet(new java.util.HashSet<String>());
    private AmapProtocolHooks() {}

    public static boolean compatible(ClassLoader cl) {
        PackageInfo p = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_AMAP);
        H.log("amap_adapter version=" + (p == null ? "unknown" : p.versionName) + " mode=capabilities");
        return true;
    }

    public static void install(final ClassLoader cl) {
        installStream(cl);
        installTools(cl);
        installResponses(cl);
        H.hookSig(H.cls(cl, "com.autonavi.inter.impl.HomeTabInitConfigFactory"), "obtainTabList", "amap_tabs", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object original = chain.proceed();
                if (!(original instanceof List)) return original;
                List<?> source = (List<?>) original;
                List<Object> out = new ArrayList<Object>();
                for (Object tab : source) {
                    String id = String.valueOf(tab);
                    String label = "Nearby".equals(id) ? "探索" : "Message".equals(id) ? "长按说话"
                        : "Taxi".equals(id) ? "打车" : "Mine".equals(id) ? "我的" : null;
                    if (label == null || Config.tabVisible(label)) out.add(tab);
                }
                return out.size() == source.size() ? original : out;
            }
        }, Context.class);
    }

    private static void installStream(final ClassLoader cl) {
        Class<?> session = H.cls(cl, "com.autonavi.bundle.vui.llm.d");
        final Class<?> callback = H.cls(cl, "com.autonavi.bundle.vui.api.ILLMSession$Callback");
        if (callback == null) return;
        H.hookSig(session, "setSceneId", "amap_stream_scope", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                sessions.put(chain.getThisObject(), "home_qs".equals(chain.getArg(0)));
                return chain.proceed();
            }
        }, String.class);
        H.hookSig(session, "start", "amap_stream_start", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object target = chain.getArg(0);
                H.log("amap_stream scoped=" + Boolean.TRUE.equals(sessions.get(chain.getThisObject())));
                if (target == null || !Boolean.TRUE.equals(sessions.get(chain.getThisObject()))) return chain.proceed();
                Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
                    @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                        if ("onMessageReceived".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof JSONObject) {
                            JSONObject source = (JSONObject) args[0];
                            JSONObject result = source;
                            try { result = AmapData.stream(source, AmapData.CURRENT); }
                            catch (Exception e) { diagnose("stream", e); }
                            JSONObject component = source.optJSONObject("component");
                            JSONObject extra = component == null ? null : component.optJSONObject("extra_info");
                            String name = extra == null ? "terminal" : extra.optString("cardName");
                            if (reportedCards.add(name)) H.log("amap_stream card=" + name + " keep=" + (result != null));
                            if (result == null) return null;
                            args = new Object[]{result};
                        }
                        try { return method.invoke(target, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                });
                return chain.proceed(new Object[]{proxy});
            }
        }, callback);
    }

    private static void installTools(ClassLoader cl) {
        Class<?> storage = H.cls(cl, "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage");
        H.hookSig(storage, "getItemSync", "amap_tool_storage", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!"toolbox".equals(chain.getArg(0)) || !(result instanceof String)) return result;
                try { return AmapData.storage((String) chain.getArg(1), (String) result, AmapData.CURRENT); }
                catch (Exception e) { diagnose("tool_storage", e); return result; }
            }
        }, String.class, String.class);
        final Class<?> callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (callback != null && storage != null) {
            try {
                final Method callbackMethod = callback.getMethod("callback", Object[].class);
                final Method getItem = storage.getMethod("getItemSync", String.class, String.class);
                H.hookSig(storage, "getItemASYNC", "amap_tool_async", new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!"toolbox".equals(chain.getArg(0)) || chain.getArg(2) == null) return chain.proceed();
                        Object value;
                        try { value = getItem.invoke(chain.getThisObject(), chain.getArg(0), chain.getArg(1)); }
                        catch (Exception e) { diagnose("tool_async_read", e); return chain.proceed(); }
                        try { callbackMethod.invoke(chain.getArg(2), new Object[]{new Object[]{value}}); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                        return null;
                    }
                }, String.class, String.class, callback);
            } catch (Exception e) { diagnose("tool_async_signature", e); }
        }
        H.hookSig(H.cls(cl, "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl"), "getToolboxData", "amap_tool_boot", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!(result instanceof JSONObject)) return result;
                try { return AmapData.toolbox((JSONObject) result, AmapData.CURRENT); }
                catch (Exception e) { diagnose("tool_boot", e); return result; }
            }
        });
    }

    private static void installResponses(ClassLoader cl) {
        Class<?> request = H.cls(cl, "com.autonavi.minimap.ajx3.modules.net.ModuleRequest");
        Class<?> callback = H.cls(cl, "com.autonavi.minimap.ajx3.core.JsFunctionCallback");
        if (callback == null) return;
        XposedInterface.Hooker register = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object options = chain.getArg(1);
                    JSONObject j = options instanceof JSONObject ? (JSONObject) options : new JSONObject((String) options);
                    String path = AmapData.requestPath(j.optString("url"));
                    if (AmapData.handles(path) && chain.getArg(2) != null) requests.put(chain.getArg(2), path);
                } catch (Exception ignored) {}
                return chain.proceed();
            }
        };
        H.hookSig(request, "fetch", "amap_request_fetch", register, String.class, String.class, callback);
        H.hookSig(request, "sendHttp", "amap_request_http", register, String.class, JSONObject.class, callback);
        H.hookSig(request, "notifyJs", "amap_request_response", response(true), callback, int.class, int.class, String.class, String.class, int.class, String.class, String.class);
        H.hookSig(request, "notifyResponse", "amap_http_response", response(false), callback, int.class, JSONObject.class, String.class, int.class, String.class);
    }
    private static XposedInterface.Hooker response(final boolean xhr) {
        return new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (xhr && !Integer.valueOf(4).equals(chain.getArg(2))) return chain.proceed();
                String path = requests.remove(chain.getArg(0));
                Object raw = chain.getArg(3);
                if (path == null || !(raw instanceof String) || !Integer.valueOf(200).equals(chain.getArg(1))) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                try { args[3] = AmapData.response(path, (String) raw, AmapData.CURRENT); }
                catch (Exception e) { diagnose("response", e); return chain.proceed(); }
                return chain.proceed(args);
            }
        };
    }
    private static void diagnose(String area, Exception e) {
        if (Config.debugLog()) H.log("amap_adapter area=" + area + " error=" + e.getClass().getSimpleName());
    }
}
