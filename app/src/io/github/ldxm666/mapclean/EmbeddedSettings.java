package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;
import android.view.MotionEvent;
import android.view.View;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Host-local settings: Android attaches the replacement Activity after newActivity returns. */
public final class EmbeddedSettings {
    private static final String TAG = "MapCleanEmbedded";
    private static volatile String hostPackage;
    private static volatile ClassLoader hostLoader;
    private static volatile String placeholderClass;
    private static final java.util.Set<View> entries = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View, Boolean>()));
    private static final java.util.Set<View> consumed = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View, Boolean>()));

    private EmbeddedSettings() {}

    public static boolean enabledFor(String pkg) {
        return EmbeddedSettingsPolicy.canOpen(hostPackage, pkg, hostLoader != null,
                App.hasEmbeddedService());
    }

    /** Bind only a verified host component; ordinary root-module clicks and gestures stay intact. */
    public static void bindEntry(final View view, String pkg) {
        if (view == null || !enabledFor(pkg)) return;
        if (!entries.add(view)) return;
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View entry) {
                Context owner = entry.getContext();
                for (int depth = 0; depth < 16 && owner != null; depth++) {
                    if (owner instanceof Activity) {
                        boolean opened = open((Activity) owner);
                        if (opened) consumed.add(entry);
                        return opened;
                    }
                    if (!(owner instanceof ContextWrapper)) break;
                    Context base = ((ContextWrapper) owner).getBaseContext();
                    if (base == owner) break;
                    owner = base;
                }
                return false;
            }
        });
    }

    /** AMap's tab overrides ACTION_UP with performClick even after Android consumed a long press. */
    public static boolean consumeEntryTouch(View view, MotionEvent event) {
        if (view == null || event == null || !entries.contains(view)) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) consumed.remove(view);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (consumed.remove(view)) {
                view.setPressed(false);
                view.cancelLongPress();
                return true;
            }
        }
        return false;
    }

    public static synchronized void install(final XposedModule module,
            XposedModuleInterface.PackageReadyParam param, String process) {
        final String pkg = param.getPackageName();
        try {
            if (!EmbeddedSettingsPolicy.mayInstall(module.getFrameworkName(), pkg, process)
                    || pkg.equals(hostPackage)) return;
            final ClassLoader loader = param.getClassLoader();
            if (loader == null) return;
            final android.content.SharedPreferences bridge =
                    module.getRemotePreferences(EmbeddedSettingsPolicy.BRIDGE_GROUP);
            module.hook(Instrumentation.class.getDeclaredMethod("newActivity",
                    ClassLoader.class, String.class, Intent.class))
                    .setId("embedded.settings.newActivity")
                    .intercept(new HookGuard("embedded.settings.newActivity", new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object argument = chain.getArg(2);
                            if (!(argument instanceof Intent)) return chain.proceed();
                            Intent intent = (Intent) argument;
                            ComponentName component = intent.getComponent();
                            if (component == null || !enabledFor(pkg)) return chain.proceed();
                            if (!EmbeddedSettingsPolicy.ACTION.equals(intent.getAction())
                                    || !pkg.equals(intent.getPackage())
                                    || !pkg.equals(component.getPackageName())) return chain.proceed();
                            boolean requested;
                            String permitted;
                            try {
                                requested = intent.getBooleanExtra(EmbeddedSettingsPolicy.EXTRA, false);
                                permitted = placeholderClass != null ? placeholderClass
                                        : bridge.getString(EmbeddedSettingsPolicy.PLACEHOLDER, null);
                            }
                            catch (Throwable malformed) { return chain.proceed(); }
                            if (!EmbeddedSettingsPolicy.accepts(pkg, intent.getPackage(),
                                    component.getPackageName(), component.getClassName(),
                                    (String) chain.getArg(1), permitted, intent.getAction(),
                                    requested, chain.getArg(0) == loader)) return chain.proceed();
                            return new MainActivity();
                        }
                    }, new HookGuard.Reporter() {
                        @Override public void disabled(String id, Throwable error) {
                            Log.w(TAG, "settings bridge disabled: " + error.getClass().getSimpleName());
                        }
                    }));
            hostLoader = loader;
            hostPackage = pkg;
            // Manager mode does not deliver a host-local writable service: keep its entry disabled.
            App.listenEmbedded();
            Log.i(TAG, "settings bridge ready package=" + pkg);
        } catch (Throwable error) {
            Log.w(TAG, "settings bridge unavailable: " + error.getClass().getSimpleName());
        }
    }

    /** Invoke from the map's visible settings entry. It opens a new task-stack activity. */
    public static boolean open(Activity activity) {
        if (activity == null || activity instanceof MainActivity || activity.isFinishing()
                || !enabledFor(activity.getPackageName())) return false;
        try {
            App.attachEmbedded(activity.getApplication());
            io.github.libxposed.service.XposedService service = io.github.ldxm666.mapadkiller.App.svc();
            if (service == null) {
                Toast.makeText(activity, "设置服务尚未连接，请稍后重试", Toast.LENGTH_SHORT).show();
                return false;
            }
            ActivityInfo placeholder = placeholder(activity);
            if (placeholder == null) {
                Toast.makeText(activity, "没有可用的宿主设置入口", Toast.LENGTH_SHORT).show();
                return false;
            }
            // Keep the exact permitted component across process death and activity restoration.
            if (!service.getRemotePreferences(EmbeddedSettingsPolicy.BRIDGE_GROUP).edit()
                    .putString(EmbeddedSettingsPolicy.PLACEHOLDER, placeholder.name).commit()) {
                Toast.makeText(activity, "设置入口保存失败，请稍后重试", Toast.LENGTH_SHORT).show();
                return false;
            }
            placeholderClass = placeholder.name;
            Intent intent = new Intent(EmbeddedSettingsPolicy.ACTION)
                    .setPackage(hostPackage)
                    .setComponent(new ComponentName(hostPackage, placeholder.name))
                    .putExtra(EmbeddedSettingsPolicy.EXTRA, true);
            activity.startActivity(intent);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "open settings failed: " + error.getClass().getSimpleName());
            Toast.makeText(activity, "设置入口暂不可用", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static boolean usable(Activity activity, ActivityInfo info) {
        return info != null && info.enabled && hostPackage.equals(info.packageName)
                && info.targetActivity == null && info.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                && (info.flags & ActivityInfo.FLAG_NO_HISTORY) == 0
                && activity.getApplicationInfo().processName.equals(info.processName);
    }

    /** singleTask/singleTop launch entries may reuse the map, bypassing newActivity entirely. */
    private static ActivityInfo placeholder(Activity activity) throws Exception {
        PackageManager pm = activity.getPackageManager();
        ComponentName current = activity.getComponentName();
        if (current != null && hostPackage.equals(current.getPackageName())) {
            ActivityInfo info = pm.getActivityInfo(current, 0);
            if (usable(activity, info)) return info;
        }
        Intent launch = pm.getLaunchIntentForPackage(hostPackage);
        if (launch != null && launch.getComponent() != null) {
            ActivityInfo info = pm.getActivityInfo(launch.getComponent(), 0);
            if (usable(activity, info)) return info;
        }
        PackageInfo info = pm.getPackageInfo(hostPackage, PackageManager.GET_ACTIVITIES);
        if (info.activities != null) {
            for (ActivityInfo candidate : info.activities) if (usable(activity, candidate)) return candidate;
        }
        return null;
    }
}
