package io.github.ldxm666.mapadkiller

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log

import java.lang.reflect.Method

/** 在 Activity onResume 后延迟清扫视图树（异步/懒加载广告兜底）。API 102 hook。 */
object Sweeper {

    @JvmStatic
    fun install(killer: ViewKiller) {
        try {
            val onResume: Method = Activity::class.java.getDeclaredMethod("onResume")
            val mod = H.module ?: return
            mod.hook(onResume)
              .setId("sweep_activity")
              .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
              .intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                  override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                      val r = chain.proceed()
                      val act = chain.getThisObject() as Activity
                      val h = Handler(Looper.getMainLooper())
                      // v2.1.7：只保留 300ms 一轮，砍掉 2500ms 那轮全树扫描。
                      //
                      // 腾讯侧广告已在**数据层**掐断：GDTADManager.initWith / isInitialized
                      // 返回 false、TGSplashAD.fetchAdOnly / preLoad 空转、gcd.a(ArrayList)
                      // 过滤服务端卡片。视图树上不会再长出广告子树，每多一轮全树递归都是
                      // 纯浪费 —— 用户反馈的"hook 卡顿"主要来自这里：地图是重视图树场景，
                      // 每次切页面回来都要多走一遍上千节点的递归。
                      // 真有懒加载广告冒出来，300ms 这一轮仍在。
                      h.postDelayed(object : Runnable {
                          override fun run() {
                              killer.sweep(act)
                          }
                      }, 300)
                      return r
                  }
              })
            H.log(Log.INFO, MainHook.TAG, "sweeper installed")
        } catch (t: Throwable) {
            H.log(Log.WARN, MainHook.TAG, "sweeper fail " + t)
        }
    }
}
