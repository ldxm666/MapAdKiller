package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.libxposed.api.XposedInterface;

/** Compact homepage keeps the native toolbar and never creates the recommendation canvas. */
public final class AmapNativeHome {
    private static final Set<View> attached = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final java.util.Map<View, int[]> sizes = new WeakHashMap<View, int[]>();
    private static final Set<View> returning = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static volatile boolean compactSupported;
    private AmapNativeHome() {}
    public static boolean compact() {
        return compactSupported && !Config.visible(Config.K_FEED_WEATHER) && !Config.visible(Config.K_FEED_BOARD)
            && !Config.visible(Config.K_FEED_CONTENT) && !Config.visible(Config.K_HOME_CHIPS)
            && !Config.visible(Config.K_FEED_FESTIVAL) && Config.visible(Config.K_RIDE_CARD_OFF);
    }
    public static void install(final ClassLoader cl) {
        Class<?> qs = H.cls(cl, "com.autonavi.bundle.amaphome.lite.NewQuickServiceView");
        Class<?> page = H.cls(cl, "com.autonavi.common.IPageContext");
        Class<?> state = H.cls(cl, "sv3");
        Class<?> factory = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.b");
        Class<?> panelDelegate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel$QsPanelStateDelegate");
        java.util.List<java.lang.reflect.Method> loads = new java.util.ArrayList<java.lang.reflect.Method>();
        if (qs != null) for (java.lang.reflect.Method candidate : qs.getDeclaredMethods()) {
            Class<?>[] args = candidate.getParameterTypes();
            if ("loadAjxQs".equals(candidate.getName()) && View.class.isAssignableFrom(candidate.getReturnType())
                    && (args.length == 2 || args.length == 3) && args[0] == page && !args[1].isPrimitive()
                    && (args.length == 2 || args[2] == boolean.class)) {
                loads.add(candidate);
            }
        }
        compactSupported = !loads.isEmpty() && panelDelegate != null
            && io.github.ldxm666.mapclean.Compatibility.method(factory, "a", null,
                Context.class, String.class, int.class, JSONObject.class, panelDelegate) != null;
        H.log("amap_compact_capability supported=" + compactSupported);
        for (java.lang.reflect.Method load : loads) H.hookSig(qs, "loadAjxQs", "amap_compact_canvas_" + load.getParameterTypes().length, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return compact() ? null : chain.proceed();
            }
        }, load.getParameterTypes());
        H.hookSig(H.cls(cl, "xc5"), "preloadAIData", "amap_compact_preload", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable { return compact() ? null : chain.proceed(); }
        });
        H.hookSig(H.cls(cl, "g13"), "a", "amap_native_home_chips", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return Config.visible(Config.K_HOME_CHIPS) ? chain.proceed() : null;
            }
        }, Context.class, String.class, int.class, JSONObject.class);
        H.hookSig(H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.v3.ToolboxPanelV3"),
            "buildSkeletonView", "amap_native_skeleton", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!compact()) return chain.proceed();
                    View empty = new View((Context) chain.getArg(0));
                    empty.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
                    empty.setVisibility(View.GONE);
                    return empty;
                }
            }, Context.class, String.class, int.class);
        Class<?> delegate = H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel$QsPanelStateDelegate");
        if (delegate != null) H.hookSig(H.cls(cl, "com.autonavi.bundle.amaphome.lite.quickservice.toolbox.b"),
            "a", "amap_native_tools", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!compact()) return chain.proceed();
                    try { return tools(cl, (Context) chain.getArg(0), (JSONObject) chain.getArg(3), ((Integer) chain.getArg(2)) == 1); }
                    catch (Exception e) {
                        Throwable cause = e;
                        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
                        H.log("amap_native_tools fallback=" + android.util.Log.getStackTraceString(cause));
                        return chain.proceed();
                    }
                }
            }, Context.class, String.class, int.class, JSONObject.class, delegate);
        H.hookSig(qs, "initToolBoxPanel", "amap_compact_height", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (compact() && chain.getThisObject() instanceof View) {
                    final View view = (View) chain.getThisObject();
                    Object panel = H.getObjectField(view, "mSplashToolboxPanel");
                    if (panel instanceof View) {
                        View toolbar = (View) panel;
                        ViewGroup.LayoutParams lp = toolbar.getLayoutParams();
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; toolbar.setLayoutParams(lp);
                    }
                    synchronized (attached) {
                        if (attached.add(view)) view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                                if (compact()) resize(v);
                            }
                        });
                    }
                    resize(view);
                }
                return result;
            }
        }, String.class, int.class);
        H.hookSig(qs, "setTransparentHeight", "amap_compact_limit", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!compact() || !(chain.getThisObject() instanceof View)) return chain.proceed();
                View self = (View) chain.getThisObject();
                int[] size = sizes.get(self);
                if (size == null || self.getHeight() <= 0) return chain.proceed();
                return chain.proceed(new Object[]{Math.max(0, self.getHeight() - size[3] - statusBar(self))});
            }
        }, int.class);
        Class<?> home = H.cls(cl, "com.autonavi.bundle.amaphome.page.MapHomePage");
        if (home != null) H.hookSig(qs, "resetQSHeights", "amap_compact_reset", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (compact() && chain.getThisObject() instanceof View) {
                    View view = (View) chain.getThisObject();
                    sizes.remove(view);
                    resize(view);
                }
                return result;
            }
        }, home);
        Class<?> panelState = H.cls(cl, "com.autonavi.bundle.uitemplate.container.internal.SlidableLayout$PanelState");
        if (panelState != null) H.hookSig(qs, "onPanelStateChanged", "amap_compact_rebound", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (compact() && chain.getThisObject() instanceof View) {
                    Object panel = H.getObjectField(chain.getThisObject(), "mSplashToolboxPanel");
                    String next = String.valueOf(chain.getArg(2));
                    if (panel instanceof View) ((View) panel).setVisibility("COLLAPSED".equals(next) || "HIDDEN".equals(next) ? View.INVISIBLE : View.VISIBLE);
                }
                if (compact() && "EXPANDED".equals(String.valueOf(chain.getArg(2))) && chain.getThisObject() instanceof View)
                    rebound((View) chain.getThisObject());
                return result;
            }
        }, View.class, panelState, panelState);
    }
    private static void rebound(final View self) {
        if (!returning.add(self)) return;
        self.post(new Runnable() {
            @Override public void run() {
                returning.remove(self);
                if (!compact() || !self.isAttachedToWindow()) return;
                try {
                    Object state = self.getClass().getMethod("getPanelState").invoke(self);
                    if (!"EXPANDED".equals(String.valueOf(state))) return;
                    Object anchored = state.getClass().getField("ANCHORED").get(null);
                    // Use the native animator after release; never interrupt an active drag.
                    self.getClass().getMethod("setPanelState", state.getClass(), boolean.class).invoke(self, anchored, true);
                    H.log("amap_compact_rebound native_anchor");
                } catch (Exception e) { H.log("amap_compact_rebound failed=" + e.getClass().getSimpleName()); }
            }
        });
    }
    private static int contentHeight(View self) {
        try {
            Object p = H.getObjectField(self, "mSplashToolboxPanel");
            if (!(p instanceof View)) return 0;
            View panel = (View) p;
            int width = self.getWidth() > 0 ? self.getWidth() : self.getResources().getDisplayMetrics().widthPixels;
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            // The native minimum already includes the search bar, bottom tabs and system inset.
            return minimum(self) + panel.getMeasuredHeight() + dp(self.getContext(), 12);
        } catch (Exception ignored) { return 0; }
    }
    private static void resize(View self) {
        if (self.getWidth() <= 0 || self.getHeight() <= 0) return;
        int height = contentHeight(self);
        if (height <= 0) return;
        try {
            Class<?> type = self.getClass();
            int minimum = minimum(self);
            int max = Math.min(self.getHeight() - statusBar(self), height + dp(self.getContext(), 48));
            int[] last = sizes.get(self);
            if (last != null && last[0] == self.getWidth() && last[1] == self.getHeight() && last[2] == height && last[3] == max && last[4] == minimum) return;
            Object state = type.getMethod("getPanelState").invoke(self);
            if ("DRAGGING".equals(String.valueOf(state))) return;
            sizes.put(self, new int[]{self.getWidth(), self.getHeight(), height, max, minimum});
            type.getMethod("setMinHeight", int.class).invoke(self, minimum);
            type.getMethod("setAnchorHeight", int.class).invoke(self, height);
            type.getMethod("setTransparentHeight", int.class).invoke(self, Math.max(0, self.getHeight() - max - statusBar(self)));
            type.getMethod("computeSlideRange").invoke(self);
            type.getMethod("onMeasureComplete").invoke(self);
            // Reposition once after changing the range; never reset the state during a gesture.
            type.getMethod("setPanelState", state.getClass(), boolean.class).invoke(self, state, false);
            H.log("amap_compact_geometry min=" + minimum + " anchor=" + height + " max=" + max + " state=" + state);
        } catch (Exception e) { if (Config.debugLog()) H.log("amap_compact_height error=" + e.getClass().getSimpleName()); }
    }
    private static int minimum(View self) throws Exception {
        Class<?> guide = self.getClass().getClassLoader().loadClass("com.autonavi.bundle.uitemplate.searchbar.SearchBarGuideSpHelper");
        Object mode = guide.getMethod("a").invoke(null);
        return (Integer) self.getClass().getMethod("getMinHeight", boolean.class)
            .invoke(self, !"SEARCHBAR_MODE_TOP".equals(String.valueOf(mode)));
    }
    private static int statusBar(View self) {
        int id = self.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : self.getResources().getDimensionPixelSize(id);
    }
    private static LinearLayout tools(ClassLoader cl, final Context context, JSONObject boot, boolean night) throws Exception {
        LinearLayout root = (LinearLayout) cl.loadClass("com.amap.bundle.commonui.designtoken.view.viewgroup.DtLinearLayout")
            .getConstructor(Context.class).newInstance(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        JSONArray source = new JSONArray();
        android.content.SharedPreferences prefs = context.getSharedPreferences("toolbox", Context.MODE_PRIVATE);
        for (String key : new String[]{"choiceTools", "recommendTools"}) {
            String raw = prefs.getString(key, null);
            if (raw == null) raw = prefs.getString(key + "_SP_DEFAULT_ITEM", null);
            if (raw == null) continue;
            JSONArray list = new JSONObject(raw).optJSONArray(key);
            if (list != null) for (int i = 0; i < list.length(); i++) source.put(list.opt(i));
        }
        {
            String raw = prefs.getString("tool_list_data", null);
            if (raw == null) raw = prefs.getString("tool_list_data_SP_DEFAULT_ITEM", null);
            if (raw != null) {
                JSONArray categories = new JSONObject(raw).optJSONArray("category");
                if (categories != null) for (int i = 0; i < categories.length(); i++) {
                    JSONObject category = categories.optJSONObject(i);
                    JSONArray list = category == null ? null : category.optJSONArray("tools");
                    if (list != null) for (int j = 0; j < list.length(); j++) {
                        JSONObject item = list.optJSONObject(j);
                        if (item != null && AmapData.toolVisible(item.optString("id"), AmapData.CURRENT)) source.put(item);
                    }
                }
            }
        }
        if (source.length() == 0) for (int i = 0; i < 10; i++) {
            String id = boot.optString("id" + i);
            if (id.length() > 0 && !"86".equals(id)) source.put(new JSONObject().put("id", id)
                .put("name", boot.optString("name" + i)).put("schema", boot.optString("schema" + i))
                .put("iconV2", boot.optString("imageUrlRender" + i)));
        }
        // A newly installed app may not have opened More Tools yet. Visible selections
        // still use the routes/icons extracted from that directory, never invented IDs.
        for (String[] entry : AmapToolCatalog.ENTRIES)
            if (AmapData.toolVisible(entry[0], AmapData.CURRENT)) source.put(AmapToolCatalog.item(entry));
        String more = "amapuri://ajx?path=path://amap_bundle_toolbox/src/pages/ToolboxHomepage.page.js&style=Page&animation=1";
        source.put(new JSONObject().put("id", "86").put("name", "更多工具").put("schema", more)
            .put("iconV2", "@Img_icon_aiqs_tool_more_Png"));
        java.util.Set<String> ids = new java.util.HashSet<String>();
        LinearLayout row = null;
        int count = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (!ids.add(id) || !AmapData.toolVisible(id, AmapData.CURRENT)) continue;
            if (count % 5 == 0) {
                row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(android.view.Gravity.CENTER);
            cell.setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 4));
            cell.setMinimumHeight(dp(context, 76));
            View icon = (View) cl.loadClass("com.amap.bundle.commonui.designtoken.view.DtImageView")
                .getConstructor(Context.class).newInstance(context);
            String image = item.optString("iconV2", item.optString("imageV2", item.optString("image")));
            if (image.length() == 0) image = item.optString("icon").split(",")[0];
            AmapToolIcons.load(cl, (android.widget.ImageView) icon, image, boot, id);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)));
            TextView label = new TextView(context); label.setText(item.optString("name"));
            label.setTextSize(12); label.setTextColor(night ? Color.WHITE : Color.rgb(50, 50, 50));
            label.setGravity(android.view.Gravity.CENTER); label.setMaxLines(2);
            cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
            final String schema = item.optString("schema");
            cell.setContentDescription(item.optString("name"));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (schema.length() == 0) return;
                    try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(schema))
                        .setPackage(MainHook.PKG_AMAP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Exception e) { H.log("amap_tool_action failed=" + e.getClass().getSimpleName()); }
                }
            });
            if ("86".equals(id) && io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_AMAP)) {
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View view) {
                        Context owner=view.getContext();
                        for (int depth=0;depth<16;depth++) {
                            if (owner instanceof android.app.Activity)
                                return io.github.ldxm666.mapclean.EmbeddedSettings.open((android.app.Activity)owner);
                            if (!(owner instanceof android.content.ContextWrapper)) break;
                            Context base=((android.content.ContextWrapper)owner).getBaseContext();
                            if (base==owner) break;
                            owner=base;
                        }
                        return false;
                    }
                });
            }
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            count++;
        }
        if (row != null) for (int i = count % 5; i > 0 && i < 5; i++)
            row.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1));
        if (count == 0) { root.setPadding(0, 0, 0, 0); root.setVisibility(View.GONE); }
        return root;
    }
    private static int dp(Context c, int value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }
}
