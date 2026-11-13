package io.github.ldxm666.mapadkiller

import android.view.View
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Hide the native AJX tool cell as well as its synthetic More Tools entry.
 * Both 17.02 CN and Play expose the same DOM members below (node types are obfuscated).
 * The existing canvas reload restores native content when the switch is enabled again.
 */
object AmapHomeTools {
    private val scheduled = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val diagnostics = AtomicInteger()
    private class Probe(val view: View) {
        var pathMatch: Boolean? = null
        var rootClassExact: Boolean? = null
        var matchCount: Int? = null
        fun report(stage: String, attempt: Int) {
            if (!Config.debugLog() || diagnostics.getAndIncrement() >= 32) return
            H.log("event=home_tools_probe stage=$stage attempt=$attempt" +
                    " tools=${Config.visible(Config.K_TOOLS_VISIBLE)} more=${moreEnabled()}" +
                    " pathMatch=$pathMatch rootClassExact=$rootClassExact matchCount=$matchCount" +
                    " attached=${view.isAttachedToWindow}")
        }
    }

    @JvmStatic fun shouldHideTools(path: String?, enabled: Boolean): Boolean =
            !enabled && AmapData.homeToolPage(path)

    @JvmStatic fun shouldHideMore(path: String?, toolsEnabled: Boolean, moreEnabled: Boolean): Boolean =
            toolsEnabled && !moreEnabled && AmapData.homeToolPage(path)

    private fun moreEnabled(): Boolean = AmapData.toolVisible(AmapData.MORE_TOOLS, AmapData.CURRENT)

    private fun needsUpdate(): Boolean = !Config.visible(Config.K_TOOLS_VISIBLE) || !moreEnabled()

    /** Called only with the homepage AmapAjxView returned by loadAjxQs. */
    @JvmStatic fun schedule(value: Any?) {
        val view = value as? View ?: return
        if (!needsUpdate()) { Probe(view).report("schedule_skip_enabled", 0); return }
        synchronized(scheduled) {
            if (scheduled.put(view, true) != null) { Probe(view).report("schedule_skip_existing", 0); return }
        }
        Probe(view).report("scheduled", 0)
        view.postDelayed({ apply(view, 0) }, 100)
    }

    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        return null
    }

    private fun apply(view: View, attempt: Int) {
        val probe = Probe(view)
        if (!needsUpdate()) {
            scheduled.remove(view)
            probe.report("cancel_enabled", attempt)
            return
        }
        fun retry(reason: String) {
            if (attempt < 20) view.postDelayed({ apply(view, attempt + 1) }, 100)
            else { scheduled.remove(view); probe.report("timeout_$reason", attempt) }
        }
        fun stop(reason: String) { probe.report(reason, attempt) }
        if (!view.isAttachedToWindow) { retry("view_detached"); return }
        try {
            val context = view.javaClass.getMethod("getAjxContext").invoke(view)
            if (context == null) { retry("context_missing"); return }
            val loader = view.javaClass.classLoader ?: run { stop("loader_missing"); return }
            val api = loader.loadClass("com.autonavi.minimap.ajx3.context.IAjxContext")
            val path = api.getMethod("getJsPath").invoke(context) as? String
            probe.pathMatch = AmapData.homeToolPage(path)
            if (probe.pathMatch != true) { stop("path_mismatch"); return }
            val tree = api.getMethod("getDomTree").invoke(context)
            if (tree == null) { retry("tree_missing"); return }
            // AjxDomTree.g performs exact attribute equality against its registered nodes.
            val tool = tree.javaClass.getMethod("g", String::class.java, String::class.java)
                    .invoke(tree, "id", "toolBoxContainer") as? View
            if (tool == null) { retry("root_missing"); return }
            val property = tool.javaClass.getMethod("getProperty").invoke(tool) ?: run { stop("property_missing"); return }
            val node = property.javaClass.getMethod("getNode").invoke(property) ?: run { stop("node_missing"); return }
            val attr = node.javaClass.getMethod("h", String::class.java)
            probe.rootClassExact = attr.invoke(node, "class") == "tool_box_container"
            if (attr.invoke(node, "id") != "toolBoxContainer" || probe.rootClassExact != true) { stop("root_contract_mismatch"); return }
            val define = loader.loadClass("com.autonavi.jni.ajx3.dom.KeyDefine")
            val display = define.getMethod("name2Type", String::class.java).invoke(null, "display") as Int
            if (display == 0) { stop("display_unknown"); return }
            if (shouldHideMore(path, Config.visible(Config.K_TOOLS_VISIBLE), moreEnabled())) {
                val matches = moreCells(node)
                probe.matchCount = matches.size
                if (matches.isEmpty()) { retry("more_match"); return }
                // AJX can retain both compact and expanded More nodes when a third
                // row is needed. Hide every bound copy of this one business entry.
                // Never act through an old DOM node whose native view was recycled.
                val targets = LinkedHashSet<View>()
                var rowsHidden = 0
                for ((item, row) in matches) {
                    val itemView = boundView(item) ?: continue
                    val rowView = boundView(row) ?: continue
                    if (!itemView.isAttachedToWindow || !rowView.isAttachedToWindow) continue
                    val rowItems = children(row).filter { hasClass(it, "tool_box_item") }
                    if (!rowItems.any { it === item }) continue
                    targets.add(itemView)
                    if (rowItems.all { sibling -> matches.any { it.first === sibling } } && targets.add(rowView)) rowsHidden++
                }
                if (targets.isEmpty()) { retry("more_binding"); return }
                if (!shouldHideMore(path, Config.visible(Config.K_TOOLS_VISIBLE), moreEnabled())) { stop("more_changed"); return }
                hide(targets.toList(), display)
                probe.report("more_hidden", attempt)
                H.log("event=home_tools_more hidden=true copies=${matches.size} rows=$rowsHidden")
                return
            }
            if (!shouldHideTools(path, Config.visible(Config.K_TOOLS_VISIBLE))) { stop("tools_changed"); return }
            // CN vr.c and Play rr.c are the DOM parent, f is tag, u is the native view.
            val parent = field(node, "c") ?: run { stop("cell_parent_missing"); return }
            if (field(parent, "f") != "cell") { stop("cell_tag_mismatch"); return }
            val children = parent.javaClass.getMethod("i").invoke(parent) as? List<*> ?: run { stop("cell_children_missing"); return }
            if (children.size != 1 || children[0] !== node) { stop("cell_contract_mismatch"); return }
            val cell = field(parent, "u") as? View ?: run { stop("cell_view_missing"); return }
            if (!tool.isAttachedToWindow || !cell.isAttachedToWindow) { retry("cell_detached"); return }
            if (Config.visible(Config.K_TOOLS_VISIBLE)) { stop("tools_changed"); return }
            hide(listOf(tool, cell), display)
            probe.report("tools_hidden", attempt)
            H.log("event=home_tools_cell hidden=true")
        } catch (error: Throwable) {
            probe.report("exception_${error.javaClass.simpleName}", attempt)
            H.log("event=home_tools_cell_failed type=${error.javaClass.simpleName}")
        }
    }

    private fun attribute(node: Any, key: String): Any? =
            node.javaClass.getMethod("h", String::class.java).invoke(node, key)

    private fun hasClass(node: Any, expected: String): Boolean {
        val value = attribute(node, "class") as? String ?: return false
        return value.length <= 256 && value.split(Regex("\\s+")).contains(expected)
    }

    private fun children(node: Any): List<Any> =
            (node.javaClass.getMethod("i").invoke(node) as? List<*>)?.filterNotNull().orEmpty()

    private fun boundView(node: Any): View? {
        val view = field(node, "u") as? View ?: return null
        val property = view.javaClass.getMethod("getProperty").invoke(view) ?: return null
        return if (property.javaClass.getMethod("getNode").invoke(property) === node) view else null
    }

    /** Exact native layout: toolBoxContainer -> row -> tool_box_item -> tl_item_label.
     * Match the labelled item only within that business container, never arbitrary text views.
     */
    private fun moreCells(root: Any): List<Pair<Any, Any>> {
        val matches = ArrayList<Pair<Any, Any>>()
        var inspected = 0
        fun hasMoreLabel(node: Any, depth: Int): Boolean {
            if (depth > 4 || inspected++ >= 180) return false
            if (field(node, "f") == "label" && attribute(node, "class") == "tl_item_label" &&
                    attribute(node, "text") == "更多工具") return true
            return children(node).any { hasMoreLabel(it, depth + 1) }
        }
        for (row in children(root).take(4)) {
            if (field(row, "c") !== root) continue
            for (item in children(row).take(6)) {
                if (field(item, "c") !== row || !hasClass(item, "tool_box_item")) continue
                if (hasMoreLabel(item, 0)) matches.add(Pair(item, row))
            }
        }
        return matches
    }

    private fun hide(targets: List<View>, display: Int) {
        val args = arrayOf(2, display, 0x3f00007e, true, true, true, true)
        val types = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Any::class.java, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        // Resolve all methods first. Native setStyle updates view, DOM and JS together.
        val methods = targets.map { it.javaClass.getMethod("setStyle", *types) }
        targets.forEachIndexed { index, target -> methods[index].invoke(target, *args) }
    }
}
