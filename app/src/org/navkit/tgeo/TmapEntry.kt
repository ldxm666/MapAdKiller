package org.navkit.tgeo

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.navkit.core.FloatingConfig
import org.navkit.core.FloatingController
import org.navkit.core.FloatingPanel

/** A recovery entry that remains available when the orb is disabled. Native clicks are untouched. */
object TmapEntry {
    @JvmStatic fun bind(view: View) {
        if (!Cfg.embedded()) return
        view.setOnLongClickListener {
            FloatingConfig.ensureServiceFrom(it.context)
            if (FloatingController.openPanelNear(it)) true else open(it)
        }
    }

    private fun open(anchor: View): Boolean {
        val decor = anchor.rootView as? ViewGroup ?: return false
        val dark = anchor.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val panel = FloatingPanel(anchor.context, dark)
        val host = FrameLayout(anchor.context)
        host.addView(panel, FrameLayout.LayoutParams(-1, -1))
        decor.addView(host, ViewGroup.LayoutParams(-1, -1))
        panel.setOnClose(Runnable { (host.parent as? ViewGroup)?.removeView(host) })
        host.post { panel.open() }
        return true
    }
}
