package org.navkit.core;

/**
 * 模块版 stub —— 免 root 内置版的悬浮窗控制器在**模块构建里根本不存在**。
 *
 * <p>保留代码里的调用点（核实过的全部）：
 * <ul>
 *   <li>{@code FloatingConfig:384} → {@code FloatingController.setEnabled(value)}</li>
 *   <li>{@code FloatingPanel:1982} → {@code FloatingController.setEnabled(false)}</li>
 * </ul>
 * 模块版没有悬浮球，`setEnabled` 是空操作；悬浮窗开关本身在模块模式下也不该出现。
 */
public final class FloatingController {
    private FloatingController() { }

    /** 空操作：模块版没有悬浮球可开关。 */
    public static void setEnabled(boolean enabled) { }
}
