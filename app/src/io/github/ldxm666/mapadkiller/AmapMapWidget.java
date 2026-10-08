package io.github.ldxm666.mapadkiller;

import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;

/** Blocks the operational map widget before it starts image/animation loading. */
final class AmapMapWidget {
    static void install(ClassLoader cl) {
        Class<?> presenter = H.cls(cl, "com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityWidgetPresenter");
        Class<?> message = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.entity.AmapMessage");
        Class<?> type = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.entity.ActivityMsgType");
        Class<?> callback = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.listener.ActivityEventCallback");
        if (message == null || type == null || callback == null) return;
        H.hookSig(presenter, "showActivity", "amap_map_promo_start", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return Config.visible(Config.K_MAP_PROMO) ? chain.proceed() : false;
            }
        }, message, java.util.HashMap.class, String.class, type, callback, Bundle.class);
        H.hookSig(presenter, "setWidgetVisible", "amap_map_promo_visibility", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (Config.visible(Config.K_MAP_PROMO)) return chain.proceed();
                return chain.proceed(new Object[]{android.view.View.GONE, null});
            }
        }, int.class, message);
    }
}
