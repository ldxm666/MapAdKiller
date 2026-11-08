package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/** View-based miuix TabRow. The indicator is drawn below labels, never elevated. */
class MiuixTabs(context: Context, private val dark: Boolean) : LinearLayout(context) {
    fun interface OnSelect { fun select(index: Int) }

    private val skin = Skin(context, dark)
    private val design = HyperDesign.of(dark)
    private val indicator = SquircleDrawable(design.surfaceContainer, skin.dp(HyperDesign.R_CONTROL).toFloat())
    private var position = 0f
    private var indicatorWidth = 0
    private var selectedIndex = 0
    private var geometryWidth = 0
    private val driver = HyperMotion.drivePage(0f, HyperMotion.Driver.Sink {
        position = it
        invalidate()
    }).bindTo(this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPadding(skin.dp(4f), skin.dp(4f), skin.dp(4f), skin.dp(4f))
        background = skin.solid(design.surface, HyperDesign.R_CONTROL + 4f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setItems(labels: List<String>, equalWidth: Boolean, onSelect: OnSelect) {
        driver.cancel()
        removeAllViews()
        selectedIndex = 0
        geometryWidth = 0
        for ((index, label) in labels.withIndex()) {
            val chip = skin.text(label, HyperDesign.T_BODY2, Skin.MEDIUM, skin.text2)
            chip.gravity = Gravity.CENTER
            chip.isSingleLine = true
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.minimumHeight = skin.dp(HyperDesign.TAB_H.toFloat())
            chip.minimumWidth = if (equalWidth) 0 else skin.dp(76f)
            chip.setPadding(skin.dp(12f), skin.dp(8f), skin.dp(12f), skin.dp(8f))
            chip.isFocusable = true
            chip.isClickable = true
            chip.setOnClickListener { onSelect.select(index) }
            chip.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.isSelected = index == selectedIndex
                    info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, index, 1, false, index == selectedIndex)
                }
            }
            UiKit.pressable(chip, HyperDesign.PRESS_ROW)
            addView(chip, LayoutParams(if (equalWidth) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (equalWidth) 1f else 0f))
        }
        select(0, false)
    }

    fun labels(): List<TextView> = (0 until childCount).map { getChildAt(it) as TextView }

    fun select(index: Int, animate: Boolean) {
        if (index !in 0 until childCount) return
        selectedIndex = index
        for (i in 0 until childCount) {
            val chip = getChildAt(i) as TextView
            chip.isSelected = i == index
            chip.setTextColor(if (i == index) skin.text else skin.text2)
            chip.typeface = if (i == index) Skin.MEDIUM else Skin.REGULAR
        }
        val child = getChildAt(index)
        if (child.width == 0) return
        indicatorWidth = child.width
        if (animate && HyperMotion.enabled(this)) driver.to(child.left.toFloat())
        else driver.snapTo(child.left.toFloat())
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val selected = getChildAt(selectedIndex) ?: return
        if (geometryWidth != width || indicatorWidth != selected.width) {
            geometryWidth = width
            select(selectedIndex, false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (indicatorWidth <= 0) return
        val x = position.toInt()
        indicator.setBounds(x, paddingTop, x + indicatorWidth, height - paddingBottom)
        indicator.draw(canvas)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(1, childCount, false,
            AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE)
    }
}
