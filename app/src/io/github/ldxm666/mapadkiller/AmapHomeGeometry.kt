package io.github.ldxm666.mapadkiller

/** Native 17.02 CN/Play QS minima: 106dp at top, 152dp at bottom, plus system inset. */
object AmapHomeGeometry {
    @JvmStatic
    fun minimum(native: Int, top: Int, bottom: Int, bottomSearch: Boolean,
                hiddenTabs: Boolean, density: Float): Int {
        if (!hiddenTabs || !density.isFinite() || density <= 0) return native
        val reserved = Math.round(106 * density)
        val search = Math.round(152 * density) - reserved
        val inset = top.toLong() - reserved
        // Validate BOTH native methods before applying this version-specific rule. A future
        // layout must fail open; never subtract from an unknown minimum or from system insets.
        if (inset < 0 || inset > Math.round(96 * density) ||
                kotlin.math.abs((bottom.toLong() - top) - search) > 1 ||
                native != if (bottomSearch) bottom else top) return native
        return native - reserved
    }
}
