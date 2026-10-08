package io.github.ldxm666.mapadkiller;

import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;

/** The original 115 dp cap leaves a narrow selected pill after removing tabs. */
final class AmapTabLayout {
    static void install(ClassLoader cl) {
        H.hookSig(H.cls(cl, "com.autonavi.bundle.uitemplate.tab.LiteTabBar"), "init", "amap_tab_padding",
            new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object container = H.getObjectField(chain.getThisObject(), "tabContainer");
                    if (container instanceof View) ((View) container).setPadding(0, 0, 0, 0);
                    return result;
                }
            }, java.util.List.class);
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
}
