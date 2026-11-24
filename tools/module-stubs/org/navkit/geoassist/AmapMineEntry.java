package org.navkit.geoassist;

/**
 * 模块版 stub —— 免 root 内置版的「长按『我的』打开面板」入口在**模块构建里根本不存在**。
 *
 * <p>保留代码里的调用点（核实过的全部）：{@code AmapHooks:23} → {@code AmapMineEntry.install(cl)}。
 *
 * <p>模块版的面板入口是模块 App 自己的 `MainActivity`（root 用户从桌面图标进），
 * 不需要在宿主里劫持长按手势，所以这里是空操作。
 */
public final class AmapMineEntry {
    private AmapMineEntry() { }

    /** 空操作：模块版不劫持宿主的长按入口。 */
    public static void install(ClassLoader cl) { }
}
