package io.github.ldxm666.mapclean

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView

/** One enter/exit path for buttons, Back, outside taps and programmatic dismissal. */
class MotionDialog(context: Context) : AlertDialog(context) {
    private var content: View? = null
    private var dim: ValueAnimator? = null
    private var exiting = false
    private var afterDismiss: Runnable? = null
    private val dimAmount = ((HyperDesign.of(context).windowDimming ushr 24) and 0xff) / 255f

    fun setMotionView(view: View) {
        content = view
        setView(view, 0, 0, 0, 0)
    }

    override fun show() {
        exiting = false
        afterDismiss = null
        val view = content
        val animate = HyperMotion.enabled()
        view?.animate()?.cancel()
        view?.alpha = if (animate) 0f else 1f
        view?.scaleX = if (animate) 0.98f else 1f
        view?.scaleY = if (animate) 0.98f else 1f
        view?.translationY = if (animate) UiKit.dpf(context, HyperMotion.DISTANCE_SMALL) else 0f
        super.show()
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setWindowAnimations(0)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val lp = w.attributes
            lp.width = minOf(context.resources.displayMetrics.widthPixels - UiKit.dp(context, 24f),
                UiKit.dp(context, HyperDesign.DIALOG_MAX_W.toFloat()))
            lp.dimAmount = if (animate) 0f else dimAmount
            w.attributes = lp
        }
        if (animate && view != null) {
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(0L).setDuration(HyperMotion.DUR_MOVE)
                .setInterpolator(HyperMotion.DECELERATE).start()
            animateDim(dimAmount, HyperMotion.DUR_MOVE)
        }
    }

    fun dismissAfter(action: Runnable?) {
        if (exiting) return
        afterDismiss = action
        dismiss()
    }

    override fun dismiss() {
        if (exiting) return
        if (!isShowing) { super.dismiss(); return }
        exiting = true
        val view = content
        val activity = context as? Activity
        if (view == null || !HyperMotion.enabled(view) || activity?.isFinishing == true || activity?.isDestroyed == true) {
            finishDismiss()
            return
        }
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f)
            .translationY(UiKit.dpf(context, HyperMotion.DISTANCE_SMALL / 2f))
            .setStartDelay(0L).setDuration(HyperMotion.DUR_EXIT)
            .setInterpolator(HyperMotion.ACCELERATE).withEndAction { finishDismiss() }.start()
        animateDim(0f, HyperMotion.DUR_EXIT)
    }

    private fun animateDim(target: Float, duration: Long) {
        dim?.cancel()
        val w = window ?: return
        dim = ValueAnimator.ofFloat(w.attributes.dimAmount, target).apply {
            this.duration = duration
            interpolator = HyperMotion.STANDARD
            addUpdateListener {
                if (isShowing) {
                    val lp = w.attributes
                    lp.dimAmount = it.animatedValue as Float
                    w.attributes = lp
                }
            }
            start()
        }
    }

    private fun finishDismiss() {
        dim?.cancel()
        dim = null
        val action = afterDismiss
        afterDismiss = null
        super.dismiss()
        action?.run()
    }

    companion object {
        @JvmStatic
        fun confirm(context: Context, title: String, message: String, positive: String, action: Runnable): MotionDialog {
            val skin = Skin(context, HyperDesign.of(context).dark)
            val dialog = MotionDialog(context)
            val box = android.widget.LinearLayout(context)
            box.orientation = android.widget.LinearLayout.VERTICAL
            box.setPadding(skin.dp(24f), skin.dp(24f), skin.dp(24f), skin.dp(16f))
            box.background = skin.solid(skin.surface, HyperDesign.R_DIALOG)
            box.addView(skin.text(title, HyperDesign.T_SUBTITLE, Skin.MEDIUM, skin.text))
            val scroll = BoundedScrollView(context)
            scroll.addView(skin.body(message))
            box.addView(scroll, skin.matchWrap().apply { topMargin = skin.dp(12f) })
            val buttons = android.widget.LinearLayout(context)
            for ((label, run) in listOf(Pair("取消", null), Pair(positive, action))) {
                val button = skin.textButton(label)
                button.minimumHeight = skin.dp(48f)
                button.setOnClickListener { dialog.dismissAfter(run) }
                buttons.addView(button, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(buttons, skin.matchWrap().apply { topMargin = skin.dp(16f) })
            dialog.setMotionView(box)
            dialog.show()
            return dialog
        }
    }
}

/** Long status/log text scrolls while the title and actions stay reachable. */
class BoundedScrollView(context: Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
            if (mode == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap), MeasureSpec.AT_MOST))
    }
}
