package io.github.ldxm666.mapadkiller;

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** The original 115 dp cap leaves a narrow selected pill after removing tabs. */
final class AmapTabLayout {
    static void install(ClassLoader cl) {
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar"), "init", "amap_tab_padding",
            new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    List<?> source = (List<?>) chain.getArg(0);
                    List<Object> kept = new ArrayList<Object>();
                    for (Object tab : source) if (visible(tabId(tab))) kept.add(tab);
                    if (kept.isEmpty()) return chain.proceed();
                    Object own = H.getObjectField(chain.getThisObject(), "mTabs");
                    if (own instanceof List) {
                        List<Object> tabs = (List<Object>) own;
                        tabs.clear(); tabs.addAll(kept);
                    }
                    Object result = chain.proceed(new Object[]{kept});
                    Object container = H.getObjectField(chain.getThisObject(), "tabContainer");
                    if (container instanceof View) ((View) container).setPadding(0, 0, 0, 0);
                    return result;
                }
            }, java.util.List.class);
        Class<?> click = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar$c");
        for (String method : new String[]{"onTabClick", "onTabDoubleClick"}) H.hookSig(click, method, "amap_tab_click_" + method, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object tab = H.getObjectField(chain.getThisObject(), "a");
                String displayed = viewId((View) chain.getArg(0));
                if (!displayed.isEmpty()) return visible(displayed) && displayed.equals(tabId(tab)) ? chain.proceed() : null;
                return visible(tabId(tab)) ? chain.proceed() : null;
            }
        }, View.class);
        Class<?> tab = H.cls(cl, "th6");
        Class<?> bar = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar");
        Class<?> layout = H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabItemLayoutV2");
        H.hookSig(layout, "setTabId", "amap_embedded_home_entry", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                // BootHomeTabPage's default page is the stable Main tab and cannot be hidden.
                if ("Main".equals(chain.getArg(0)))
                    io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry((View) chain.getThisObject(), MainHook.PKG_AMAP);
                return result;
            }
        }, String.class);
        H.hookSig(layout, "onTouchEvent", "amap_embedded_home_touch", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return io.github.ldxm666.mapclean.EmbeddedSettings.consumeEntryTouch(
                        (View) chain.getThisObject(), (MotionEvent) chain.getArg(0)) ? true : chain.proceed();
            }
        }, MotionEvent.class);
        if (tab != null) {
            XposedInterface.Hooker dispatch = new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object model = chain.getArg(chain.getArgs().size() - 1);
                    return visible(tabId(model)) ? chain.proceed() : null;
                }
            };
            H.hookSig(bar, "dispatchTabClick", "amap_tab_dispatch", dispatch, tab);
            H.hookSig(bar, "dispatchTabClick", "amap_tab_dispatch_event", dispatch, int.class, tab);
            H.hookSig(bar, "dispatchTabClickBefore", "amap_tab_dispatch_before", dispatch, tab);
        }
        if (tab != null) H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar"), "dispatchTabTouchEvent", "amap_tab_touch", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return visible(tabId(chain.getArg(0))) ? chain.proceed() : true;
            }
        }, tab, View.class, MotionEvent.class);
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabItemLayoutV2"),
            "applyAmapEdgeContentOffset", "amap_tab_center", H.VOID);
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabFocusBackgroundViewHolder"),
            "d", "amap_tab_focus_edges", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object image = H.getObjectField(chain.getThisObject(), "d");
                    if (image instanceof View) {
                        ViewGroup.LayoutParams lp = ((View) image).getLayoutParams();
                        if (lp instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams margin = (ViewGroup.MarginLayoutParams) lp;
                            margin.leftMargin = 0; margin.rightMargin = 0; margin.topMargin = 0; margin.bottomMargin = 0;
                        }
                    }
                    return null;
                }
            });
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.view.TabItemLayoutV2"), "onMeasure",
            "amap_tab_background", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    View view = (View) chain.getThisObject();
                    int id = view.getResources().getIdentifier("tab_bg_layer", "id", MainHook.PKG_AMAP);
                    View background = id == 0 ? null : view.findViewById(id);
                    if (background != null) {
                        ViewGroup.LayoutParams lp = background.getLayoutParams();
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    }
                    return chain.proceed();
                }
            }, int.class, int.class);
    }
    private static String tabId(Object tab) {
        if (tab == null) return "";
        Object id = H.getObjectField(tab, "a");
        return id == null ? "" : String.valueOf(id);
    }
    private static String viewId(View view) {
        if (view == null) return "";
        try { return String.valueOf(view.getClass().getMethod("getTabId").invoke(view)); }
        catch (Exception ignored) { return ""; }
    }
    private static boolean visible(String id) {
        String label = "Nearby".equals(id) ? "探索" : "Message".equals(id) ? "长按说话"
            : "Taxi".equals(id) ? "打车" : "Mine".equals(id) ? "我的" : null;
        return label == null || Config.tabVisible(label);
    }
}
