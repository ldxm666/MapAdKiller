package io.github.ldxm666.mapadkiller

import android.content.ClipData
import android.content.Context
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ldxm666.mapclean.*

/** Shared editor; uses the same design tokens and dialog as the settings page. */
object AmapToolOrderEditor {
    @JvmStatic fun entry(context: Context): View {
        val skin = Skin(context, HyperDesign.of(context).dark)
        return skin.textButton("调整工具顺序").apply {
            contentDescription = "调整已开启工具的顺序，支持拖动和上下移动"
            setOnClickListener { show(context) }
        }
    }

    @JvmStatic fun show(context: Context): MotionDialog {
        val skin = Skin(context, HyperDesign.of(context).dark)
        fun dp(n: Int) = skin.dp(n.toFloat())
        var saved = ToolOrderCodec.read(AmapPanel.toolOrderValues()[ToolOrderCodec.KEY] as? String)
        val values = AmapPanel.toolOrderValues()
        val settings = object : AmapData.Settings {
            override fun enabled(key: String) = Config.readVisible(values, key)
            override fun selected(key: String) = values[key] == true
        }
        var visible = AmapToolOrder.enabledIds(settings, saved)
        val labels = AmapToolCatalog.ENTRIES.associate { it[0] to it[1] }
        val dialog = MotionDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = skin.solid(skin.surface, HyperDesign.R_DIALOG)
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }
        root.addView(skin.text("首页工具排序", HyperDesign.T_SUBTITLE, Skin.MEDIUM, skin.text))
        root.addView(skin.body("长按拖动，或点右侧箭头调整，改完即保存。").apply {
            textSize = 13f
        }, skin.matchWrap().apply { topMargin = dp(8) })
        val scroll = BoundedScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
        }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        root.addView(scroll, skin.matchWrap().apply { topMargin = dp(16) })
        root.addView(skin.body("更多工具固定在最后；关闭工具仍会保留它的顺序。").apply {
            textSize = 12f
        }, skin.matchWrap().apply { topMargin = dp(12) })
        val error = skin.body("").apply {
            setTextColor(skin.danger)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(error, skin.matchWrap().apply { topMargin = dp(8) })
        fun save(order: List<String>): Boolean {
            val ok = AmapPanel.writeToolOrder(order)
            error.text = if (ok) "" else "保存失败，顺序未改变。请检查模块服务连接后重试。"
            error.visibility = if (ok) View.GONE else View.VISIBLE
            return ok
        }
        lateinit var render: () -> Unit
        fun move(from: Int, to: Int) {
            if (from !in visible.indices || to !in visible.indices || from == to) return
            val next = visible.toMutableList().apply { add(to, removeAt(from)) }
            val remembered = AmapToolOrder.remember(saved, next)
            if (save(remembered)) {
                saved = remembered
                visible = next
                render()
                rows.announceForAccessibility("已移至第 ${to + 1} 位")
            }
        }
        render = {
            val oldScroll = scroll.scrollY
            rows.removeAllViews()
            if (visible.isEmpty()) rows.addView(skin.body("先在「首页工具」中开启需要显示的工具，再来调整顺序。"))
            for ((index, id) in visible.withIndex()) {
                val label = labels[id] ?: id
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(10), dp(6), dp(6), dp(6))
                    background = skin.solid(skin.bg, 14f)
                }
                row.addView(skin.text("≡", 22f, Skin.REGULAR, skin.text3).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(44)))
                row.addView(skin.text(label, 16f, Skin.MEDIUM, skin.text).apply {
                    setPadding(dp(8), 0, dp(4), 0)
                    gravity = Gravity.CENTER_VERTICAL
                    contentDescription = "$label，第 ${index + 1} 位，长按拖动排序"
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                for (delta in intArrayOf(-1, 1)) {
                    val enabled = index + delta in visible.indices
                    val action = FrameLayout(context).apply {
                        contentDescription = (if (delta < 0) "上移 " else "下移 ") + label
                        isEnabled = enabled
                        isFocusable = enabled
                        isClickable = enabled
                        alpha = if (enabled) 1f else .28f
                        background = UiKit.rippleBare(HyperDesign.alpha(skin.accent, .12f), dp(12).toFloat())
                        setOnClickListener { move(visible.indexOf(id), visible.indexOf(id) + delta) }
                    }
                    action.addView(Icon(context, Icon.CHEVRON, skin.text2).apply {
                        rotation = if (delta < 0) 270f else 90f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    row.addView(action, LinearLayout.LayoutParams(dp(44), dp(44)))
                }
                row.setOnLongClickListener {
                    row.startDragAndDrop(ClipData.newPlainText("mapclean_tool", id), View.DragShadowBuilder(row), id, 0)
                }
                row.setOnDragListener { _, event ->
                    if (event.localState !is String) false else {
                        when (event.action) {
                            DragEvent.ACTION_DRAG_ENTERED -> row.background = skin.solid(skin.accentSoft, 14f)
                            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> row.background = skin.solid(skin.bg, 14f)
                            DragEvent.ACTION_DRAG_LOCATION -> {
                                val rowAt = IntArray(2); val scrollAt = IntArray(2)
                                row.getLocationOnScreen(rowAt); scroll.getLocationOnScreen(scrollAt)
                                val y = rowAt[1] + event.y
                                if (y < scrollAt[1] + dp(36)) scroll.scrollBy(0, -dp(8))
                                else if (y > scrollAt[1] + scroll.height - dp(36)) scroll.scrollBy(0, dp(8))
                            }
                            DragEvent.ACTION_DROP -> {
                                row.background = skin.solid(skin.bg, 14f)
                                move(visible.indexOf(event.localState as String), visible.indexOf(id))
                            }
                        }
                        true
                    }
                }
                rows.addView(row, skin.matchWrap().apply { if (index > 0) topMargin = dp(8) })
            }
            scroll.post { scroll.scrollTo(0, oldScroll) }
        }
        render()
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(skin.textButton("恢复默认").apply {
            minimumHeight = dp(48)
            setOnClickListener {
                if (save(emptyList())) {
                    saved = emptyList()
                    visible = AmapToolOrder.enabledIds(settings, saved)
                    render()
                    announceForAccessibility("已恢复默认顺序，工具开关未改变")
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(skin.textButton("完成").apply {
            minimumHeight = dp(48)
            setTextColor(skin.onAccent)
            background = UiKit.ripple(HyperDesign.alpha(skin.onAccent, .16f), skin.solid(skin.accent, 14f), dp(14).toFloat())
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(12) })
        root.addView(actions, skin.matchWrap().apply { topMargin = dp(16) })
        dialog.setMotionView(root)
        dialog.show()
        return dialog
    }
}
