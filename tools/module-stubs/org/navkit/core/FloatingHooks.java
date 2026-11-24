package org.navkit.core;

import io.github.libxposed.api.XposedModule;

/**
 * 模块版 stub —— 免 root 内置版的悬浮窗 hook 安装在**模块构建里根本不存在**。
 *
 * <p>保留代码里的调用点（核实过的全部）：{@code MainHook:122}
 * → {@code FloatingHooks.install(this, pkg, true)}。
 *
 * <p>模块版不需要在宿主里挂"悬浮窗"相关的 Activity 生命周期 hook（那是给内置版
 * 自托管面板用的），所以这里是空操作 —— **一条 hook 都不装**。
 */
public final class FloatingHooks {
    private FloatingHooks() { }

    /** 空操作：模块版不装任何悬浮窗 hook。 */
    public static void install(XposedModule module, String pkg, boolean embedded) { }
}
