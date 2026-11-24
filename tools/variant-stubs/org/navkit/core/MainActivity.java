package org.navkit.core;

import android.app.Activity;
import android.os.Bundle;

/**
 * Stub for the slim (免 root 内置) variant.
 *
 * In an integrated LSPatch build the module has no launcher entry of its own; the
 * floating orb is the only UI. This placeholder exists so shared code that references the
 * type still links, and it is never shown.
 */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}
