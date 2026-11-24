package org.navkit.core;

/**
 * 模块版 stub —— 免 root 内置版的悬浮球在**模块构建里根本不存在**。
 *
 * <p>为什么需要这个文件：`FloatingPanel`（`MainActivity` 也要用，不能删）持有
 * {@code FloatingBall ball} 字段并调用它的两个方法。模块版删掉真实 `FloatingBall.java` 后，
 * 这里顶一个"空壳"让编译通过；运行时它永远不会被实例化（`FloatingController` 也被删了）。
 */
public final class FloatingBall {
    public void setSizeDp(int dp) { }
    public void setAlphaPercent(int percent) { }
}
