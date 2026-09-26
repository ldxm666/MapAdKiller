package io.github.ldxm666.mapadkiller;

/** LSPosed 状态自检用；被 hook 后 amEnabled() 返回 true */
public final class StatusCheck {
    private StatusCheck() {}
    public static boolean amEnabled() { return false; }
}
