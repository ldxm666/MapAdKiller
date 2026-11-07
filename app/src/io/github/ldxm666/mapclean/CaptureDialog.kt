package io.github.ldxm666.mapclean

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 与面板同款的确认弹窗：深/浅色圆角卡片 + 一个主按钮 + 一个文字按钮。
 *
 * <p>系统 {@code AlertDialog} 在高德这种全屏地图上会弹出一张白底方角卡片，和面板完全是两套
 * 视觉，用户直接说「丑」。这里全部自绘，颜色/圆角/字重都和面板一致；纯代码，不依赖资源。
 */
class CaptureDialog private constructor() {

    companion object {
        @JvmStatic
        fun show(
            ctx: Context?, title: String?, message: CharSequence?,
            primaryLabel: String?, onPrimary: Runnable?,
            secondaryLabel: String?, onSecondary: Runnable?
        ) {
            build(ctx, title, message, primaryLabel, onPrimary, secondaryLabel, onSecondary, true)
        }

        @JvmStatic
        fun showBlocking(
            ctx: Context?, title: String?, message: CharSequence?,
            primaryLabel: String?, onPrimary: Runnable?,
            secondaryLabel: String?, onSecondary: Runnable?
        ) {
            build(ctx, title, message, primaryLabel, onPrimary, secondaryLabel, onSecondary, false)
        }

        private fun build(
            ctx: Context?, title: String?, message: CharSequence?,
            primaryLabel: String?, onPrimary: Runnable?,
            secondaryLabel: String?, onSecondary: Runnable?,
            cancelable: Boolean
        ) {
            if (ctx == null) return
            val dark = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val cardBg = if (dark) 0xFF1F2126.toInt() else 0xFFF7F8FA.toInt()
            val stroke = if (dark) 0x1AFFFFFF else 0x14000000
            val titleColor = if (dark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
            val msgColor = if (dark) 0xCCFFFFFF.toInt() else 0x99000000.toInt()

            val dialog = Dialog(ctx)
            try {
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            } catch (ignored: Throwable) {
            }

            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.cornerRadius = dp(ctx, 20f).toFloat()
            bg.setColor(cardBg)
            bg.setStroke(Math.max(1, dp(ctx, 1f / 3f)), stroke)
            card.background = bg
            val pad = dp(ctx, 20f)
            card.setPadding(pad, dp(ctx, 18f), pad, dp(ctx, 12f))

            val t = TextView(ctx)
            t.text = title
            t.setTextSize(17f)
            t.setTextColor(titleColor)
            t.typeface = android.graphics.Typeface.DEFAULT_BOLD
            card.addView(t, wrap())

            val m = TextView(ctx)
            m.text = message
            m.setTextSize(13f)
            m.setTextColor(msgColor)
            m.setLineSpacing(0f, 1.25f)
            val mlp = wrap()
            mlp.topMargin = dp(ctx, 8f)
            card.addView(m, mlp)

            val divider = View(ctx)
            divider.setBackgroundColor(if (dark) 0x14FFFFFF else 0x14000000)
            val dlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1f / 3f))
            )
            dlp.topMargin = dp(ctx, 16f)
            card.addView(divider, dlp)

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val rlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rlp.topMargin = dp(ctx, 10f)
            card.addView(row, rlp)

            if (secondaryLabel != null && secondaryLabel.length > 0) {
                val sec = TextView(ctx)
                sec.text = secondaryLabel
                sec.setTextSize(13f)
                sec.setTextColor(if (dark) 0xFF8A8F98.toInt() else 0xFF6B7280.toInt())
                sec.gravity = Gravity.CENTER
                sec.setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f))
                sec.setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        dismiss(dialog)
                        onSecondary?.run()
                    }
                })
                val slp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                slp.rightMargin = dp(ctx, 8f)
                row.addView(sec, slp)
            }

            val primary = TextView(ctx)
            primary.text = primaryLabel
            primary.setTextSize(13f)
            primary.setTextColor(Color.WHITE)
            primary.typeface = android.graphics.Typeface.DEFAULT_BOLD
            primary.gravity = Gravity.CENTER
            primary.setPadding(dp(ctx, 16f), dp(ctx, 10f), dp(ctx, 16f), dp(ctx, 10f))
            val btn = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF3D7EFF.toInt(), 0xFF2A5FE0.toInt())
            )
            btn.cornerRadius = dp(ctx, 14f).toFloat()
            primary.background = btn
            primary.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    dismiss(dialog)
                    onPrimary?.run()
                }
            })
            row.addView(
                primary, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            dialog.setContentView(card)
            dialog.setCancelable(cancelable)
            dialog.setCanceledOnTouchOutside(cancelable)
            val w = dialog.window
            if (w != null) {
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                w.setDimAmount(if (cancelable) 0.45f else 0.55f)
                w.addFlags(Window.FEATURE_NO_TITLE)
                val width = Math.min(
                    ctx.resources.displayMetrics.widthPixels - dp(ctx, 48f),
                    dp(ctx, 340f)
                )
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                try {
                    w.setGravity(Gravity.CENTER)
                } catch (ignored: Throwable) {
                }
            }
            try {
                dialog.show()
            } catch (ignored: Throwable) {
            }
        }

        private fun dismiss(d: Dialog?) {
            try {
                if (d != null && d.isShowing) d.dismiss()
            } catch (ignored: Throwable) {
            }
        }

        private fun wrap(): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        private fun dp(ctx: Context, v: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.resources.displayMetrics
            ).toInt()
        }
    }
}