package io.github.ldxm666.mapadkiller;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴 —— 「隐藏桌面图标」之后**唯一不会消失的入口**。
 *
 * 为什么必须有它：
 *   LSPosed 管理器给模块显示的「设置」入口，底层是拿 MAIN + LAUNCHER 去 resolveActivity。
 *   桌面入口一旦被禁用，同一个查询也一起返回 null —— 于是「隐藏桌面图标」会连带把
 *   LSPosed 里的入口一起弄没，用户就被锁在设置页外面了。磁贴不走桌面、不进启动器，
 *   天然绕开这个死结。
 *
 * 用法：下拉通知栏 → 编辑磁贴 → 把 MapAdKiller 拖进面板。
 * 隐藏图标前请先把磁贴加好（设置页弹窗里也会提示）。
 *
 * 实现说明：这里用反射调用 startActivityAndCollapse ——
 * TileService#startActivityAndCollapse(Intent) 在 API 34 已标记过时，直接调用会让 javac
 * 往 stderr 打一条「使用或覆盖了已过时的 API」note；而 build.ps1 是 Stop 策略，
 * 原生命令往 stderr 写东西会把整个构建中断。反射调用既避开 note，又能按版本挑重载。
 */
public final class SettingsTile extends TileService {

    @Override
    public void onStartListening() {
        try {
            Tile t = getQsTile();
            if (t != null) {
                t.setLabel("MapAdKiller");
                t.setState(App.hideIcon(this) ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
                t.updateTile();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (collapse(i)) return;
        try { startActivity(i); } catch (Throwable ignored) {}
    }

    /** 优先走系统认可的面板收拢路径；失败返回 false 由调用方兜底。 */
    private boolean collapse(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                getClass().getMethod("startActivityAndCollapse", PendingIntent.class)
                        .invoke(this, pi);
            } else {
                getClass().getMethod("startActivityAndCollapse", Intent.class)
                        .invoke(this, i);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
