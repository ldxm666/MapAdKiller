package io.github.ldxm666.tmapclean;

import android.view.View;
import android.view.ViewGroup;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Home seek-chip row: MapBaseView owns its native host and row geometry. */
final class TmapSeekChips {
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<View, Boolean>();
    private static final Map<Object, Boolean> BASES = new WeakHashMap<Object, Boolean>();
    private TmapSeekChips() {}

    static void install(ClassLoader cl) {
        Class<?> base = H.cls(cl, "com.tencent.map.api.view.MapBaseView");
        H.hookSig(base, "setSeekChipRowHeight", "tmap_seek_row", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean hide = !Cfg.visible(Spec.K_SEEK_CHIPS);
                Object result = hide ? chain.proceed(new Object[]{Integer.valueOf(0)})
                        : chain.proceed();
                apply(chain.getThisObject());
                return result;
            }
        }, int.class);
    }

    private static void apply(Object base) {
        if (base == null) return;
        synchronized (BASES) { BASES.put(base, Boolean.TRUE); }
        if (Boolean.FALSE.equals(H.call(base, "isHomePage"))) return;
        Object parent = H.call(base, "getSeekParentView");
        if (!(parent instanceof ViewGroup)) return;
        View view = (View) parent;
        boolean show = Cfg.visible(Spec.K_SEEK_CHIPS);
        if (!show) {
            // The host uses this field for the floating row's travel and spacing.
            H.setField(base, "seekChipRowHeight", Integer.valueOf(0));
            H.setField(base, "lastSeekChipRowHeight", Integer.valueOf(0));
        }
        synchronized (HIDDEN) {
            if (!show) {
                if (view.getVisibility() != View.GONE) {
                    HIDDEN.put(view, Boolean.TRUE);
                    view.setVisibility(View.GONE);
                }
            } else if (HIDDEN.remove(view) != null && view.getVisibility() == View.GONE) {
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    static void refresh() {
        Object[] bases;
        synchronized (BASES) { bases = BASES.keySet().toArray(); }
        for (Object base : bases) apply(base);
    }
}
