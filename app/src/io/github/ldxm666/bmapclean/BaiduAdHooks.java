package io.github.ldxm666.bmapclean;

import android.view.View;
import java.util.List;

/** Native homepage promotions, verified against Baidu Map 22.0.0 (1650). */
final class BaiduAdHooks {
    private static final String PRESENTERS = "com.baidu.baidumaps.aihome.map.presenter.";

    private BaiduAdHooks() {}

    static void install(ClassLoader cl) {
        Class<?> yellow = H.cls(cl, PRESENTERS + "YellowBannerPresenter");
        // Prevent both ordinary notices and carousel notices before inflation/timer creation.
        BaiduHooks.hook(yellow, "showYellowBanner", chain -> null,
                H.cls(cl, "com.baidu.baidumaps.base.yellowbanner.e"));
        BaiduHooks.hook(yellow, "showSwitcherBanner", chain -> null, List.class);
        BaiduHooks.hook(yellow, "tryShowYellowBanner", chain -> null);
        BaiduHooks.hook(yellow, "showYbBannerAnim", chain -> null);
        BaiduHooks.hook(yellow, "onResumeForYB", chain -> null);
        BaiduHooks.hook(yellow, "initViews", chain -> {
            Object result = chain.proceed();
            BaiduHooks.hide((View) BaiduHooks.field(chain.getThisObject(), "mYbView"));
            return result;
        });

        Class<?> operation = H.cls(cl, PRESENTERS + "HomeMapOperatePresenter");
        // V22/coin activity icon: suppress ordinary and splash-linked entry creation.
        BaiduHooks.hook(operation, "showEventEntry", chain -> null, boolean.class);
        BaiduHooks.hook(operation, "onEventMainThread", chain -> null, H.cls(cl, "xe.c"));
        BaiduHooks.hook(operation, "setEventEntryVisibleForYB", chain ->
                chain.proceed(new Object[]{false}), boolean.class);
        BaiduHooks.hook(operation, "onResume", chain -> {
            Object result = chain.proceed();
            BaiduHooks.hide((View) BaiduHooks.field(chain.getThisObject(), "mEventEntryView"));
            return result;
        });
    }
}
