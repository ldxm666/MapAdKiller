package io.github.ldxm666.mapclean

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「赞赏支持」弹窗 —— 模块版（{@code MainActivity}）与内置版（{@code FloatingPanel}）**共用同一份 UI**。
 *
 * <h3>为什么抽出来</h3>
 * 之前两处各写一份，样式不一致且都很素（只有标题 + 二维码 + 一行小字 + 系统按钮）。
 * 统一到这里后：①两版观感完全一致 ②以后改样式只改一处 ③不依赖 {@code Skin} 实例
 * （{@code FloatingPanel} 没有 Skin），自带配色，两版都能用。
 *
 * <h3>视觉规格</h3>
 * 圆角 24dp 白卡 + 1px 描边；顶部胶囊标签；20sp 主标题 + 12.5sp 副标题；
 * 二维码放在独立白卡里（18dp 圆角 + 描边 + 6dp 投影 + 12dp 内边距）；
 * 两行说明；底部 46dp 主色按钮。整宽 = min(屏宽 − 48dp, 340dp)。
 */
class DonateUi private constructor() {

    companion object {
        /** 主色（与模块 Skin.accent 同源）。 */
        private val ACCENT = 0xFF2B7FFF.toInt()
        private val ACCENT_SOFT = 0x1A2B7FFF
        private val TX = 0xFF1A1A1A.toInt()
        private val TX2 = 0xFF6B7280.toInt()
        private val TX3 = 0xFF9AA0A6.toInt()
        private val CARD = 0xFFFFFFFF.toInt()
        private val LINE = 0x14000000

        @JvmStatic
        fun show(ctx: Context?) {
            try {
                val c = ctx ?: return
                val box = LinearLayout(c)
                box.orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable()
                bg.setColor(CARD)
                bg.cornerRadius = dp(c, 24f).toFloat()
                bg.setStroke(Math.max(1, dp(c, 1f)), LINE)
                box.background = bg
                box.setPadding(dp(c, 22f), dp(c, 22f), dp(c, 22f), dp(c, 16f))

                // ① 顶部胶囊标签
                val badge = TextView(c)
                badge.text = "赞赏支持"
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                badge.typeface = Typeface.DEFAULT_BOLD
                badge.setTextColor(ACCENT)
                val pill = GradientDrawable()
                pill.setColor(ACCENT_SOFT)
                pill.cornerRadius = dp(c, 99f).toFloat()
                badge.background = pill
                badge.setPadding(dp(c, 12f), dp(c, 5f), dp(c, 12f), dp(c, 5f))
                val blp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                blp.gravity = Gravity.CENTER_HORIZONTAL
                box.addView(badge, blp)

                // ② 主标题 + ③ 副标题
                val title = TextView(c)
                title.text = "感谢投喂"
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                title.typeface = Typeface.DEFAULT_BOLD
                title.setTextColor(TX)
                title.gravity = Gravity.CENTER
                addTop(c, box, title, 12f)

                val sub = TextView(c)
                sub.text = "你的支持是持续维护的动力"
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                sub.setTextColor(TX3)
                sub.gravity = Gravity.CENTER
                addTop(c, box, sub, 6f)

                // ④ 二维码白卡
                val qrCard = FrameLayout(c)
                val cardBg = GradientDrawable()
                cardBg.setColor(0xFFFFFFFF.toInt())
                cardBg.cornerRadius = dp(c, 18f).toFloat()
                cardBg.setStroke(Math.max(1, dp(c, 1f)), LINE)
                qrCard.background = cardBg
                try {
                    qrCard.elevation = dp(c, 6f).toFloat()
                } catch (ignored: Throwable) {
                }

                var qr: Bitmap? = null
                try {
                    qr = DonateAsset.bitmap()
                } catch (ignored: Throwable) {
                }

                val side = dp(c, 216f)
                if (qr != null) {
                    val iv = ImageView(c)
                    iv.setImageBitmap(qr)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    val ilp = FrameLayout.LayoutParams(side, side)
                    ilp.gravity = Gravity.CENTER
                    val m = dp(c, 12f)
                    ilp.setMargins(m, m, m, m)
                    qrCard.addView(iv, ilp)
                } else {
                    val fail = TextView(c)
                    fail.text = "收款码加载失败"
                    fail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    fail.setTextColor(TX3)
                    fail.gravity = Gravity.CENTER
                    val flp = FrameLayout.LayoutParams(side, side)
                    flp.gravity = Gravity.CENTER
                    qrCard.addView(fail, flp)
                }
                addTop(c, box, qrCard, 18f)

                // ⑤ 两行说明
                val cap1 = TextView(c)
                cap1.text = "cakercc 的赞赏码"
                cap1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                cap1.typeface = Typeface.DEFAULT_BOLD
                cap1.setTextColor(TX2)
                cap1.gravity = Gravity.CENTER
                addTop(c, box, cap1, 16f)

                val cap2 = TextView(c)
                cap2.text = "微信 / 支付宝 扫一扫"
                cap2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                cap2.setTextColor(TX3)
                cap2.gravity = Gravity.CENTER
                addTop(c, box, cap2, 4f)

                // ⑥ 主按钮
                val holder = arrayOfNulls<Dialog>(1)
                val close = TextView(c)
                close.text = "关闭"
                close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                close.typeface = Typeface.DEFAULT_BOLD
                close.setTextColor(0xFFFFFFFF.toInt())
                close.gravity = Gravity.CENTER
                val btnBg = GradientDrawable()
                btnBg.setColor(ACCENT)
                btnBg.cornerRadius = dp(c, 14f).toFloat()
                close.background = btnBg
                close.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        try {
                            holder[0]?.dismiss()
                        } catch (ignored: Throwable) {
                        }
                    }
                })
                val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 46f))
                clp.topMargin = dp(c, 20f)
                box.addView(close, clp)

                // ★ 用裸 Dialog 而不是 AlertDialog：AlertController 的内容面板会额外内缩 ~16dp/边，
                //   导致「窗口 340dp、白卡只有 308dp」。裸 Dialog + 透明 windowBackground + setLayout
                //   才能让白卡宽度精确等于 min(屏宽−48dp, 340dp)。
                val dlg = Dialog(c)
                dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dlg.setContentView(box)
                dlg.setCanceledOnTouchOutside(true)
                holder[0] = dlg
                dlg.show()

                val w = dlg.window
                if (w != null) {
                    w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    w.setDimAmount(0.45f)
                    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val screen = c.resources.displayMetrics.widthPixels
                    val width = Math.min(screen - dp(c, 48f), dp(c, 340f))
                    w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val a = asActivity(c)
                if (a != null) {
                    try {
                        a.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                // 弹窗失败绝不影响调用方
            }
        }

        private fun addTop(c: Context, parent: LinearLayout, child: View, topDp: Float) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(c, topDp)
            parent.addView(child, lp)
        }

        private fun asActivity(c: Context?): Activity? {
            var cur: Context? = c
            var i = 0
            while (i < 8 && cur != null) {
                if (cur is Activity) return cur
                if (cur !is ContextWrapper) return null
                cur = cur.baseContext
                i++
            }
            return null
        }

        private fun dp(c: Context, v: Float): Int {
            return (v * c.resources.displayMetrics.density + 0.5f).toInt()
        }
    }
}