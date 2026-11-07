package io.github.ldxm666.mapclean

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 首次进入的强制提示：告知「更新与公告已走自有通道」，并给出 TG 群反馈入口。
 *
 * <p>语义是「强制阅读」而不是「弹一次广告」：只有点了「我知道了」才写标记，进程被杀 /
 * 用户按 Home 走掉都算没读过，下次进来还会弹。因此 {@code setCancelable(false)} + 拦返回键，
 * 并且「我知道了」前 3 秒禁用（倒计时文案 3s→2s→1s→无）。
 *
 * <p>这里所有路径都包了 try/catch：设置页崩掉的代价远大于少弹一次提示。
 */
class FirstRunNotice private constructor() {

    companion object {
        /** 独立的 prefs 文件：不写进 App.UI_PREFS，避免被 RemotePreferences 镜像同步到 Hook 侧。 */
        private const val PREFS = "mapclean_notice"

        // 键名带版本号 ⇒ **每个版本弹一次**，而不是一辈子只弹一次。
        // 理由：这条提示是「首次进入告知新通道」——
        // 每次升级正是用户最需要看到它的时刻；一次性的键会让老用户永远看不到后续提醒。
        // 同一版本内点过「我知道了」就不再弹（标记落 prefs，进程被杀不算读过）。
        private val KEY: String = "notice_tg_v" + Version.CODE
        private const val COUNTDOWN_S = 3
        private const val TICK_MS = 1000L

        private const val TITLE = "重要提示"

        // 2.3.1 起：分发与公告都走自有服务器，不再依赖任何第三方平台，
        // 所以「怕失联」这个前提消失了。这条提示改成告知新通道，TG 群保留做反馈用。
        private const val BODY =
            "版本更新与重要通知已改为自有通道自动推送，无需再手动找新版本。\n\n遇到问题可加 TG 群反馈。"

        /** 已读过则直接返回；任何异常都不向外抛。 */
        @JvmStatic
        fun maybeShow(a: Activity?) {
            if (a == null) return
            try {
                if (read(a)) return
            } catch (ignored: Throwable) {
                return // 读不到标记就别弹了，宁可少弹一次
            }
            try {
                if (a.isFinishing) return
                show(a)
            } catch (ignored: Throwable) {
                // 弹窗构造失败：静默放弃
            }
        }

        private fun read(c: Context): Boolean {
            val p: SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getBoolean(KEY, false)
        }

        private fun write(c: Context) {
            try {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                p.edit().putBoolean(KEY, true).apply()
            } catch (ignored: Throwable) {
            }
        }

        private fun show(a: Activity) {
            var dark = false
            try {
                val cfg: Configuration = a.resources.configuration
                dark = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            } catch (ignored: Throwable) {
            }
            val skin = Skin(a, dark)

            val box = LinearLayout(a)
            box.orientation = LinearLayout.VERTICAL
            box.background = skin.glassFor(box, Skin.R_CARD.toFloat())
            val padH = skin.dp(Skin.S5.toFloat())
            box.setPadding(padH, skin.dp(Skin.S5.toFloat()), padH, skin.dp(Skin.S3.toFloat()))

            val title = skin.text(TITLE, Skin.T_TITLE, Skin.BOLD, skin.text)
            box.addView(title, skin.matchWrap())

            val body = skin.text(BODY, 14f, Skin.REGULAR, skin.text2)
            body.setLineSpacing(skin.dp(3f).toFloat(), 1.15f)
            val bodyLp = skin.matchWrap()
            bodyLp.topMargin = skin.dp(Skin.S3.toFloat())
            box.addView(body, bodyLp)

            val hideRef = arrayOfNulls<Runnable>(1)
            val handler = Handler(Looper.getMainLooper())

            // 次要动作：TG **通知频道**（发版公告）。无浏览器时 toast 兜底，不能崩。
            box.addView(tgBtn(a, skin, "TG 通知频道", Version.TG), topLp(skin, Skin.S5.toFloat()))
            // 次要动作：TG **聊天群**（反馈 / 交流）。
            box.addView(tgBtn(a, skin, "TG 聊天群", Version.TG_CHAT), topLp(skin, Skin.S3.toFloat()))

            // 主按钮：3 秒后才可用，文案随倒计时变化。
            val ok = skin.primaryButton(label(COUNTDOWN_S))
            ok.isEnabled = false
            ok.alpha = 0.55f
            val left = intArrayOf(COUNTDOWN_S)
            val tick = arrayOfNulls<Runnable>(1)
            tick[0] = object : Runnable {
                override fun run() {
                    try {
                        left[0]--
                        if (left[0] > 0) {
                            ok.text = label(left[0])
                            handler.postDelayed(tick[0]!!, TICK_MS)
                        } else {
                            ok.text = "我知道了"
                            ok.isEnabled = true
                            ok.alpha = 1f
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }
            handler.postDelayed(tick[0]!!, TICK_MS)
            ok.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    if (!ok.isEnabled) return // 倒计时期间的点击直接吃掉
                    write(a)
                    hideRef[0]?.run()
                }
            })
            val okLp = skin.matchWrap()
            okLp.topMargin = skin.dp(Skin.S2.toFloat())
            box.addView(ok, okLp)

            // 与 Announce.showDialog 同一套：自绘遮罩浮层，不用 AlertDialog ——
            // 宿主对话框主题会在 content 矩形上铺一层纯黑 9-patch（windowBackgroundFallback），
            // 不属于任何 View 的背景，代码层清不掉，只能绕开。
            val host = Announce.overlayHost(a) ?: return
            val overlay = FrameLayout(a)
            val dim = ColorDrawable(0xFF000000.toInt())   // 不透明黑，靠 alpha 动画到 55%
            dim.alpha = 0
            overlay.background = dim
            overlay.isClickable = true                  // 强制阅读：点空白不关

            val widthPx = a.resources.displayMetrics.widthPixels
            val cardLp = FrameLayout.LayoutParams(
                Math.min(widthPx - skin.dp(Skin.S7.toFloat()) * 2, skin.dp(340f)),
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            cardLp.gravity = Gravity.CENTER
            overlay.addView(box, cardLp)

            val done = booleanArrayOf(false)
            val removeNow = object : Runnable {
                override fun run() {
                    if (done[0]) return
                    done[0] = true
                    try {
                        host.removeView(overlay)
                    } catch (ignored: Throwable) {
                    }
                    // 销毁即摘回调，否则 Handler 会一直攥着 Activity。
                    try {
                        handler.removeCallbacksAndMessages(null)
                    } catch (ignored: Throwable) {
                    }
                }
            }
            val hide = object : Runnable {
                override fun run() {
                    try {
                        overlay.postDelayed(removeNow, 400L)
                        Announce.playOut(box, dim, removeNow)
                    } catch (ignored: Throwable) {
                        removeNow.run()
                    }
                }
            }
            hideRef[0] = hide
            box.isClickable = true
            // 强制阅读：吞掉返回键
            overlay.isFocusableInTouchMode = true
            overlay.setOnKeyListener(object : View.OnKeyListener {
                override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
                    return keyCode == KeyEvent.KEYCODE_BACK
                }
            })

            // 卡片自己的软阴影：轮廓给成圆角，否则 setElevation 画出来的是方角投影。
            try {
                val radiusPx = skin.dp(Skin.R_CARD.toFloat()).toFloat()
                box.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        val r = Math.min(radiusPx, Math.min(v.width, v.height) / 2f)
                        outline.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
                skin.shadow(box, 8f)
            } catch (ignored: Throwable) {
            }

            host.addView(
                overlay, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            overlay.requestFocus()
            Announce.playIn(box, dim)
        }

        /** TG 跳转按钮：无浏览器时 toast 兜底，绝不崩。 */
        private fun tgBtn(a: Activity, skin: Skin, label: String, url: String): TextView {
            val b = skin.secondaryButton(label)
            b.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    try {
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (t: Throwable) {
                        try {
                            Toast.makeText(a, "没有可用的浏览器，请手动访问 $url", Toast.LENGTH_LONG).show()
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            })
            return b
        }

        private fun topLp(skin: Skin, dp: Float): LinearLayout.LayoutParams {
            val lp = skin.matchWrap()
            lp.topMargin = skin.dp(dp)
            return lp
        }

        private fun label(seconds: Int): String {
            return "我知道了（" + seconds + "s）"
        }
    }
}