package io.github.ldxm666.bmapclean

import android.view.View
import java.util.List

import io.github.libxposed.api.XposedInterface

/** Native homepage promotions, verified against Baidu Map 22.0.0 (1650). */
class BaiduAdHooks private constructor() {

    companion object {

        private const val PRESENTERS = "com.baidu.baidumaps.aihome.map.presenter."

        /**
         * {@code showEventEntry} 两个重载共用的短路体：不 proceed ⇒ 图标根本不建。
         *
         * <p>抽成常量而不是写两遍 {@code chain -> null}：这两处必须是同一个决定，
         * 分开写迟早有人只改一份，就变成「普通入口拦了、强制入口漏了」这种半截状态。
         * 目标方法返回 {@code void}，返回 {@code null} 合法（调用方丢弃返回值）。
         */
        private val SUPPRESS: XposedInterface.Hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                return null
            }
        }

        @JvmStatic
        fun install(cl: ClassLoader) {
            val yellow = H.cls(cl, PRESENTERS + "YellowBannerPresenter")
            // Prevent both ordinary notices and carousel notices before inflation/timer creation.
            BaiduHooks.hook(yellow, "showYellowBanner", XposedInterface.Hooker { chain -> null },
                    H.cls(cl, "com.baidu.baidumaps.base.yellowbanner.e"))
            BaiduHooks.hook(yellow, "showSwitcherBanner", XposedInterface.Hooker { chain -> null }, List::class.java)
            BaiduHooks.hook(yellow, "tryShowYellowBanner", XposedInterface.Hooker { chain -> null })
            BaiduHooks.hook(yellow, "showYbBannerAnim", XposedInterface.Hooker { chain -> null })
            BaiduHooks.hook(yellow, "onResumeForYB", XposedInterface.Hooker { chain -> null })
            BaiduHooks.hook(yellow, "initViews", XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                BaiduHooks.hide(BaiduHooks.field(chain.getThisObject(), "mYbView") as View?)
                result
            })

            val operation = H.cls(cl, PRESENTERS + "HomeMapOperatePresenter")
            // V22/coin activity icon: suppress ordinary and splash-linked entry creation.
            // 22.1.0 实查（dexsql 直读 base.apk 的 methods 表）showEventEntry 有**两个**重载：
            //   showEventEntry()V    ← 无参入口
            //   showEventEntry(Z)V   ← 带"是否强制"标志的入口
            // 只挂 (Z) 时无参那条照常把 V22 / 现金签到图标建出来。更难受的是它**查不出来**：
            // 宿主走无参重载 ⇒ 我们的钩子从未被调用 ⇒ logcat 里只有 HOOKED、没有 HIT、没有 miss，
            // 表现就是「升级后广告图标又回来了」却一条异常都没有。
            BaiduHooks.hook(operation, "showEventEntry", SUPPRESS, java.lang.Boolean.TYPE)
            // 无参重载：id 显式区分（baidu_evententry_0），不与被上面那条占用的自动 id 混在一起。
            BaiduHooks.hook(operation, "showEventEntry", "baidu_evententry_0", SUPPRESS)
            // 22.0.50 混淆名迁移：该重载的事件参数类由 xe.c 换成 fe.c。
            // 反编译实证（tools/dex_query.py --Class Lcom/baidu/.../HomeMapOperatePresenter; --Methods）：
            //   HomeMapOperatePresenter 只声明了 onEventMainThread(Lfe/c;)V，
            //   没有 onEventMainThread(Lxe/c;)V；fe.c = { <init>(SplashLinkageModel), a()SplashLinkageModel }
            //   —— 正是开屏联动图标（现金签到入口）的事件载体。
            // 旧代码传 H.cls(cl,"xe.c")：类还在（classes10.dex），但该类型上没有此重载 →
            // getDeclaredMethod 抛 NoSuchMethodException → H.hook(null) 记 miss。
            // 这就是 install_done miss=1 的那一条（logcat: miss baidu_onEventMainThreadHomeMapOperatePresenter）。
            BaiduHooks.hook(operation, "onEventMainThread", XposedInterface.Hooker { chain -> null },
                    H.cls(cl, "fe.c"))
            BaiduHooks.hook(operation, "setEventEntryVisibleForYB", XposedInterface.Hooker { chain ->
                chain.proceed(arrayOf<Any?>(false))
            }, java.lang.Boolean.TYPE)
            BaiduHooks.hook(operation, "onResume", XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                BaiduHooks.hide(BaiduHooks.field(chain.getThisObject(), "mEventEntryView") as View?)
                result
            })
        }
    }
}
