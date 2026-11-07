package io.github.ldxm666.mapclean

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import android.view.MotionEvent
import android.view.View

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** Host-local settings: Android attaches the replacement Activity after newActivity returns. */
class EmbeddedSettings private constructor() {

    companion object {
        private const val TAG = "MapCleanEmbedded"

        @Volatile
        private var hostPackage: String? = null

        @Volatile
        private var hostLoader: ClassLoader? = null

        @Volatile
        private var placeholderClass: String? = null

        private val entries: MutableSet<View> = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
        )

        private val consumed: MutableSet<View> = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
        )

        @JvmStatic
        fun enabledFor(pkg: String?): Boolean {
            return EmbeddedSettingsPolicy.canOpen(
                hostPackage, pkg, hostLoader != null,
                App.hasEmbeddedService()
            )
        }

        /** Bind only a verified host component; ordinary root-module clicks and gestures stay intact. */
        @JvmStatic
        fun bindEntry(view: View?, pkg: String?) {
            if (view == null || !enabledFor(pkg)) return
            if (!entries.add(view)) return
            view.setOnLongClickListener(object : View.OnLongClickListener {
                override fun onLongClick(entry: View): Boolean {
                    var owner: Context? = entry.context
                    var depth = 0
                    while (depth < 16 && owner != null) {
                        if (owner is Activity) {
                            val opened = open(owner)
                            if (opened) consumed.add(entry)
                            return opened
                        }
                        if (owner !is ContextWrapper) break
                        val base = owner.baseContext
                        if (base === owner) break
                        owner = base
                        depth++
                    }
                    return false
                }
            })
        }

        /** AMap's tab overrides ACTION_UP with performClick even after Android consumed a long press. */
        @JvmStatic
        fun consumeEntryTouch(view: View?, event: MotionEvent?): Boolean {
            if (view == null || event == null || !entries.contains(view)) return false
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                consumed.remove(view)
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (consumed.remove(view)) {
                    view.isPressed = false
                    view.cancelLongPress()
                    return true
                }
            }
            return false
        }

        @JvmStatic
        @Synchronized
        fun install(module: XposedModule, param: XposedModuleInterface.PackageReadyParam, process: String?) {
            val pkg = param.packageName
            try {
                if (!EmbeddedSettingsPolicy.mayInstall(module.getFrameworkName(), pkg, process) ||
                    pkg == hostPackage
                ) return
                val loader = param.classLoader ?: return
                val bridge = module.getRemotePreferences(EmbeddedSettingsPolicy.BRIDGE_GROUP)
                module.hook(
                    Instrumentation::class.java.getDeclaredMethod(
                        "newActivity",
                        ClassLoader::class.java, String::class.java, Intent::class.java
                    )
                )
                    .setId("embedded.settings.newActivity")
                    .intercept(HookGuard("embedded.settings.newActivity", object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val argument = chain.getArg(2)
                            if (argument !is Intent) return chain.proceed()
                            val intent = argument
                            val component = intent.component ?: return chain.proceed()
                            if (!enabledFor(pkg)) return chain.proceed()
                            if (EmbeddedSettingsPolicy.ACTION != intent.action ||
                                pkg != intent.getPackage() ||
                                pkg != component.packageName
                            ) return chain.proceed()
                            val requested: Boolean
                            val permitted: String?
                            try {
                                requested = intent.getBooleanExtra(EmbeddedSettingsPolicy.EXTRA, false)
                                permitted = placeholderClass
                                    ?: bridge.getString(EmbeddedSettingsPolicy.PLACEHOLDER, null)
                            } catch (malformed: Throwable) {
                                return chain.proceed()
                            }
                            if (!EmbeddedSettingsPolicy.accepts(
                                    pkg, intent.getPackage(),
                                    component.packageName, component.className,
                                    chain.getArg(1) as String?, permitted, intent.action,
                                    requested, chain.getArg(0) === loader
                                )
                            ) return chain.proceed()
                            return MainActivity()
                        }
                    }, object : HookGuard.Reporter {
                        override fun disabled(id: String, error: Throwable) {
                            Log.w(TAG, "settings bridge disabled: " + error.javaClass.simpleName)
                        }
                    }))
                hostLoader = loader
                hostPackage = pkg
                // Manager mode does not deliver a host-local writable service: keep its entry disabled.
                App.listenEmbedded()
                Log.i(TAG, "settings bridge ready package=$pkg")
            } catch (error: Throwable) {
                Log.w(TAG, "settings bridge unavailable: " + error.javaClass.simpleName)
            }
        }

        /** Invoke from the map's visible settings entry. It opens a new task-stack activity. */
        @JvmStatic
        fun open(activity: Activity?): Boolean {
            if (activity == null || activity is MainActivity || activity.isFinishing ||
                !enabledFor(activity.packageName)
            ) return false
            // enabledFor() 已经保证 hostPackage 非空；取出来用局部量，ComponentName 的构造器
            // 在 Kotlin 里是 @NonNull 参数，直接传 String? 编不过。
            val host = hostPackage ?: return false
            try {
                App.attachEmbedded(activity.application)
                val service = io.github.ldxm666.mapadkiller.App.svc()
                if (service == null) {
                    Toast.makeText(activity, "设置服务尚未连接，请稍后重试", Toast.LENGTH_SHORT).show()
                    return false
                }
                val placeholder = placeholder(activity)
                if (placeholder == null) {
                    Toast.makeText(activity, "没有可用的宿主设置入口", Toast.LENGTH_SHORT).show()
                    return false
                }
                // Keep the exact permitted component across process death and activity restoration.
                if (!service.getRemotePreferences(EmbeddedSettingsPolicy.BRIDGE_GROUP).edit()
                        .putString(EmbeddedSettingsPolicy.PLACEHOLDER, placeholder.name).commit()
                ) {
                    Toast.makeText(activity, "设置入口保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return false
                }
                placeholderClass = placeholder.name
                val intent = Intent(EmbeddedSettingsPolicy.ACTION)
                    .setPackage(host)
                    .setComponent(ComponentName(host, placeholder.name))
                    .putExtra(EmbeddedSettingsPolicy.EXTRA, true)
                activity.startActivity(intent)
                return true
            } catch (error: Throwable) {
                Log.w(TAG, "open settings failed: " + error.javaClass.simpleName)
                Toast.makeText(activity, "设置入口暂不可用", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        private fun usable(activity: Activity, info: ActivityInfo?): Boolean {
            return info != null && info.enabled && hostPackage == info.packageName &&
                info.targetActivity == null && info.launchMode == ActivityInfo.LAUNCH_MULTIPLE &&
                (info.flags and ActivityInfo.FLAG_NO_HISTORY) == 0 &&
                activity.applicationInfo.processName == info.processName
        }

        /** singleTask/singleTop launch entries may reuse the map, bypassing newActivity entirely. */
        private fun placeholder(activity: Activity): ActivityInfo? {
            val pm: PackageManager = activity.packageManager
            val current = activity.componentName
            if (current != null && hostPackage == current.packageName) {
                val info = pm.getActivityInfo(current, 0)
                if (usable(activity, info)) return info
            }
            val launch = pm.getLaunchIntentForPackage(hostPackage ?: return null)
            if (launch != null && launch.component != null) {
                val info = pm.getActivityInfo(launch.component!!, 0)
                if (usable(activity, info)) return info
            }
            val info: PackageInfo = pm.getPackageInfo(
                hostPackage ?: return null, PackageManager.GET_ACTIVITIES
            )
            val acts = info.activities
            if (acts != null) for (candidate in acts) if (usable(activity, candidate)) return candidate
            return null
        }
    }
}