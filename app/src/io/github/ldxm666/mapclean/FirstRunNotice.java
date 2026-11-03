package io.github.ldxm666.mapclean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 首次进入的强制提示：告知「更新与公告已走自有通道」，并给出 TG 群反馈入口。
 *
 * <p>语义是「强制阅读」而不是「弹一次广告」：只有点了「我知道了」才写标记，进程被杀 /
 * 用户按 Home 走掉都算没读过，下次进来还会弹。因此 {@code setCancelable(false)} + 拦返回键，
 * 并且「我知道了」前 3 秒禁用（倒计时文案 3s→2s→1s→无）。
 *
 * <p>这里所有路径都包了 try/catch：设置页崩掉的代价远大于少弹一次提示。
 */
final class FirstRunNotice {

    /** 独立的 prefs 文件：不写进 App.UI_PREFS，避免被 RemotePreferences 镜像同步到 Hook 侧。 */
    private static final String PREFS = "mapclean_notice";
    // 键名带版本号 ⇒ **每个版本弹一次**，而不是一辈子只弹一次。
    // 理由：这条提示是「首次进入告知新通道」——
    // 每次升级正是用户最需要看到它的时刻；一次性的键会让老用户永远看不到后续提醒。
    // 同一版本内点过「我知道了」就不再弹（标记落 prefs，进程被杀不算读过）。
    private static final String KEY = "notice_tg_v" + Version.CODE;
    private static final int COUNTDOWN_S = 3;
    private static final long TICK_MS = 1000L;

    private static final String TITLE = "重要提示";
    // 2.3.1 起：分发与公告都走自有服务器，不再依赖任何第三方平台，
    // 所以「怕失联」这个前提消失了。这条提示改成告知新通道，TG 群保留做反馈用。
    private static final String BODY =
            "版本更新与重要通知已改为自有通道自动推送，无需再手动找新版本。\n\n遇到问题可加 TG 群反馈。";

    private FirstRunNotice() {}

    /** 已读过则直接返回；任何异常都不向外抛。 */
    static void maybeShow(final Activity a) {
        if (a == null) return;
        try {
            if (read(a)) return;
        } catch (Throwable ignored) {
            return; // 读不到标记就别弹了，宁可少弹一次
        }
        try {
            if (a.isFinishing()) return;
            show(a);
        } catch (Throwable ignored) {
            // 弹窗构造失败：静默放弃
        }
    }

    private static boolean read(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p != null && p.getBoolean(KEY, false);
    }

    private static void write(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p != null) p.edit().putBoolean(KEY, true).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void show(final Activity a) {
        boolean dark = false;
        try {
            Configuration cfg = a.getResources().getConfiguration();
            dark = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
        }
        final Skin skin = new Skin(a, dark);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(skin.glassFor(box, Skin.R_CARD));
        int padH = skin.dp(Skin.S5);
        box.setPadding(padH, skin.dp(Skin.S5), padH, skin.dp(Skin.S3));

        TextView title = skin.text(TITLE, Skin.T_TITLE, Skin.BOLD, skin.text);
        box.addView(title, skin.matchWrap());

        TextView body = skin.text(BODY, 14f, Skin.REGULAR, skin.text2);
        body.setLineSpacing(skin.dp(3), 1.15f);
        LinearLayout.LayoutParams bodyLp = skin.matchWrap();
        bodyLp.topMargin = skin.dp(Skin.S3);
        box.addView(body, bodyLp);

        final Runnable[] hideRef = new Runnable[1];
        final Handler handler = new Handler(Looper.getMainLooper());

        // 次要动作：TG **通知频道**（发版公告）。无浏览器时 toast 兜底，不能崩。
        box.addView(tgBtn(a, skin, "TG 通知频道", Version.TG), topLp(skin, Skin.S5));
        // 次要动作：TG **聊天群**（反馈 / 交流）。
        box.addView(tgBtn(a, skin, "TG 聊天群", Version.TG_CHAT), topLp(skin, Skin.S3));

        // 主按钮：3 秒后才可用，文案随倒计时变化。
        final TextView ok = skin.primaryButton(label(COUNTDOWN_S));
        ok.setEnabled(false);
        ok.setAlpha(0.55f);
        final int[] left = { COUNTDOWN_S };
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            @Override public void run() {
                try {
                    left[0]--;
                    if (left[0] > 0) {
                        ok.setText(label(left[0]));
                        handler.postDelayed(tick[0], TICK_MS);
                    } else {
                        ok.setText("我知道了");
                        ok.setEnabled(true);
                        ok.setAlpha(1f);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        handler.postDelayed(tick[0], TICK_MS);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ok.isEnabled()) return; // 倒计时期间的点击直接吃掉
                write(a);
                if (hideRef[0] != null) hideRef[0].run();
            }
        });
        LinearLayout.LayoutParams okLp = skin.matchWrap();
        okLp.topMargin = skin.dp(Skin.S2);
        box.addView(ok, okLp);

        // 与 Announce.showDialog 同一套：自绘遮罩浮层，不用 AlertDialog ——
        // 宿主对话框主题会在 content 矩形上铺一层纯黑 9-patch（windowBackgroundFallback），
        // 不属于任何 View 的背景，代码层清不掉，只能绕开。
        final ViewGroup host = Announce.overlayHost(a);
        if (host == null) return;
        final FrameLayout overlay = new FrameLayout(a);
        final ColorDrawable dim = new ColorDrawable(0xFF000000);   // 不透明黑，靠 alpha 动画到 55%
        dim.setAlpha(0);
        overlay.setBackground(dim);
        overlay.setClickable(true);                  // 强制阅读：点空白不关

        int widthPx = a.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(widthPx - skin.dp(Skin.S7) * 2, skin.dp(340)),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(box, cardLp);

        final boolean[] done = { false };
        final Runnable removeNow = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                try { host.removeView(overlay); } catch (Throwable ignored) { }
                // 销毁即摘回调，否则 Handler 会一直攥着 Activity。
                try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
            }
        };
        final Runnable hide = new Runnable() {
            @Override public void run() {
                try {
                    overlay.postDelayed(removeNow, 400L);
                    Announce.playOut(box, dim, removeNow);
                } catch (Throwable ignored) {
                    removeNow.run();
                }
            }
        };
        hideRef[0] = hide;
        box.setClickable(true);
        // 强制阅读：吞掉返回键
        overlay.setFocusableInTouchMode(true);
        overlay.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                return keyCode == KeyEvent.KEYCODE_BACK;
            }
        });

        // 卡片自己的软阴影：轮廓给成圆角，否则 setElevation 画出来的是方角投影。
        try {
            final float radiusPx = skin.dp(Skin.R_CARD);
            box.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline outline) {
                    float r = Math.min(radiusPx, Math.min(v.getWidth(), v.getHeight()) / 2f);
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            skin.shadow(box, 8f);
        } catch (Throwable ignored) { }

        host.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.requestFocus();
        Announce.playIn(box, dim);
    }

    /** TG 跳转按钮：无浏览器时 toast 兜底，绝不崩。 */
    private static TextView tgBtn(final android.app.Activity a, Skin skin, String label, final String url) {
        TextView b = skin.secondaryButton(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable t) {
                    try {
                        Toast.makeText(a, "没有可用的浏览器，请手动访问 " + url, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) { }
                }
            }
        });
        return b;
    }

    private static LinearLayout.LayoutParams topLp(Skin skin, float dp) {
        LinearLayout.LayoutParams lp = skin.matchWrap();
        lp.topMargin = skin.dp(dp);
        return lp;
    }

    private static String label(int seconds) {
        return "我知道了（" + seconds + "s）";
    }
}
