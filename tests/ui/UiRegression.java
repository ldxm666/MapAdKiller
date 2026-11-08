package io.github.ldxm666.mapclean.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.PixelCopy;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Framework-only device regression checks; never writes map configuration. */
public final class UiRegression extends Instrumentation {
    private Activity activity;
    private int checks;
    private final StringBuilder log = new StringBuilder();
    private ClassLoader loader;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private interface Work { void run() throws Exception; }
    private void main(Work work) throws Exception {
        final Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new Exception(error[0]);
    }
    private void idle() { SystemClock.sleep(750); waitForIdleSync(); }
    private void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; log.append("PASS ").append(name).append('\n');
        Bundle progress = new Bundle(); progress.putString("stream", "PASS " + name + "\n"); sendStatus(0, progress);
    }
    private Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args);
    }
    @SuppressWarnings("unchecked") private List<View> nav() throws Exception { return (List<View>) field(activity, "navItems"); }
    private void tab(int index) throws Exception { main(() -> nav().get(index).performClick()); idle(); }
    private View tagged(View view, int tag) {
        if (view.getTag(tag) != null) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View hit = tagged(((ViewGroup)view).getChildAt(i), tag); if (hit != null) return hit;
        }
        return null;
    }
    private void capture(String name) throws Exception {
        idle();
        final Bitmap[] shot = new Bitmap[1]; final int[] code = {-1};
        CountDownLatch latch = new CountDownLatch(1);
        main(() -> {
            View decor = activity.getWindow().getDecorView();
            shot[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), shot[0], result -> { code[0]=result; latch.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        if (!latch.await(5, TimeUnit.SECONDS) || code[0] != PixelCopy.SUCCESS) throw new AssertionError("window capture failed: " + code[0]);
        Bitmap bitmap = shot[0];
        File directory = new File(getTargetContext().getCacheDir(), "ui-qa"); directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
    private void floatingNav() throws Exception {
        main(() -> {
            View holder = (View) field(activity, "navHolder");
            View bar = (View) field(activity, "navBar");
            check(holder.getWidth() < bar.getWidth(), "floating capsule has side margins");
            check(holder.getElevation() > 0, "floating capsule retains elevation");
            for (View item : nav()) {
                TextView label = (TextView)((ViewGroup)item).getChildAt(1);
                check(Math.abs(label.getX() + label.getWidth()/2f - item.getWidth()/2f) < 2,
                    "nav label centered: " + label.getText());
                check(label.getBottom() <= item.getHeight(), "nav label not clipped: " + label.getText());
            }
        });
    }
    @SuppressWarnings("unchecked") private void accordion() throws Exception {
        tab(1);
        final View[] head = new View[1]; final View[] content = new View[1];
        main(() -> {
            List<View> pages = (List<View>)field(activity, "appPages");
            int index = (Integer)field(activity, "appIndex");
            head[0] = tagged(pages.get(index), 0x7f0f0003);
            check(head[0] != null && head[0].isEnabled() && head[0].isClickable(), "category remains interactive on read-only page");
            content[0] = ((ViewGroup)head[0].getParent()).getChildAt(1);
            check(content[0].getVisibility() == View.GONE, "category starts collapsed");
            head[0].performClick();
        });
        idle();
        main(() -> check(content[0].getVisibility() == View.VISIBLE && content[0].getHeight() > 0, "category expands with measured content"));
        capture("config-expanded");
        for (int i=0; i<7; i++) { main(() -> head[0].performClick()); SystemClock.sleep(45); }
        idle();
        main(() -> check(content[0].getVisibility() == View.GONE, "rapid accordion toggles end collapsed"));
        main(() -> head[0].performClick()); idle();
        main(() -> check(content[0].getHeight() > 0 && content[0].getAlpha() == 1f, "accordion can reopen after interruption"));
        main(() -> head[0].performClick()); idle();
    }
    private void rapidNavigation() throws Exception {
        for (int index : new int[]{3, 0, 2, 1, 0, 3, 1}) {
            main(() -> nav().get(index).performClick()); SystemClock.sleep(45);
        }
        idle();
        main(() -> {
            View pager = (View)field(activity, "pager");
            check(pager.getScrollX() == pager.getWidth(), "rapid navigation settles to exact requested page");
            check(nav().get(1).isSelected(), "navigation selection follows requested page");
            View indicator = (View)field(activity, "navIndicator");
            View holder = (View)field(activity, "navHolder");
            float inset = 6 * activity.getResources().getDisplayMetrics().density;
            check(Math.abs(indicator.getTranslationX() - (holder.getWidth()-2*inset)/4f) < 2,
                "indicator spring settles after repeated retargeting");
        });
    }
    private void springs() throws Exception {
        Class<?> spring = loader.loadClass("io.github.ldxm666.mapclean.HyperMotion$Spring");
        Method at = spring.getMethod("at", float.class,float.class,float.class,float.class);
        Method to = spring.getMethod("to",float.class), step = spring.getMethod("advance",float.class);
        Method value = spring.getMethod("value"), velocity = spring.getMethod("velocity");
        Object sixty = at.invoke(null,.85f,.38f,0f,.00001f), oneTwenty = at.invoke(null,.85f,.38f,0f,.00001f);
        to.invoke(sixty,1f); to.invoke(oneTwenty,1f);
        for(int i=0;i<12;i++)step.invoke(sixty,1f/60f);
        for(int i=0;i<24;i++)step.invoke(oneTwenty,1f/120f);
        check(Math.abs((Float)value.invoke(sixty)-(Float)value.invoke(oneTwenty)) < .0001f, "spring consistent at 60 and 120 Hz");
        float before = (Float)value.invoke(sixty), speed = (Float)velocity.invoke(sixty); to.invoke(sixty,0f);
        check((Float)value.invoke(sixty)==before && (Float)velocity.invoke(sixty)==speed, "spring retarget preserves value and velocity");
        Object small = at.invoke(null,.9f,.3f,0f,.0001f); to.invoke(small,.01f);
        check((Boolean)step.invoke(small,1f/120f), "normalized spring does not settle after first frame");
        for(int i=0;i<300;i++)step.invoke(small,1f/120f);
        check((Float)value.invoke(small)==.01f, "normalized spring settles to exact target");
    }
    private void collect(Object[] items, Set<String> keys, String vendor, JSONArray output, boolean[] valid) throws Exception {
        if (items == null) return;
        Class<?> config = loader.loadClass("io.github.ldxm666.mapadkiller.Config");
        for (Object item : items) {
            String key = (String)item.getClass().getField("key").get(item);
            if (key == null || key.equals("float_enabled")) continue;
            if (!keys.add(key)) valid[0] = false;
            String title = (String)item.getClass().getField("title").get(item);
            boolean strict = item.getClass().getField("strict").getBoolean(item);
            if (vendor.equals("amap") && strict) {
                check(!(Boolean)config.getMethod("defaultVisible",String.class).invoke(null,key), "explicit-only default aligned: " + key);
            }
            output.put(new JSONObject().put("vendor",vendor).put("key",key).put("title",title).put("strict",strict));
        }
    }
    @SuppressWarnings("unchecked") private void catalogues() throws Exception {
        JSONArray output = new JSONArray();
        for (String[] vendor : new String[][]{{"amap","AmapPanel","switches"},{"baidu","BaiduPanel","bmapSwitches"},{"tmap","TmapPanel","tmapSwitches"}}) {
            Object[] groups = (Object[])loader.loadClass("io.github.ldxm666.mapclean."+vendor[1]).getMethod("groups").invoke(null);
            Set<String> keys = new LinkedHashSet<>(); boolean[] valid = {true};
            for (Object group : groups) {
                collect((Object[])group.getClass().getField("items").get(group),keys,vendor[0],output,valid);
                Object[] sections = (Object[])group.getClass().getField("sections").get(group);
                if (sections != null) for(Object section : sections) collect((Object[])((Object[])section)[1],keys,vendor[0],output,valid);
            }
            Set<String> actual = new LinkedHashSet<>(((Map<String,View>)field(activity,vendor[2])).keySet());
            Set<String> missing = new LinkedHashSet<>(keys); missing.removeAll(actual);
            Set<String> extra = new LinkedHashSet<>(actual); extra.removeAll(keys);
            check(valid[0], vendor[0]+" catalogue has no duplicate keys");
            check(missing.isEmpty() && extra.isEmpty(), vendor[0]+" module/panel key parity missing="+missing+" extra="+extra);
        }
        File directory = new File(getTargetContext().getCacheDir(), "ui-qa"); directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory,"catalogue.json"))) { out.write(output.toString(2).getBytes("UTF-8")); }
    }
    @SuppressWarnings("unchecked") private void configurationRoundTrip() throws Exception {
        for (String[] vendor : new String[][]{{"amap","mapadkiller","amap_enhancer_config","switches","writeBoolean"},
                {"baidu","bmapclean","bmapclean_config","bmapSwitches","write"},{"tmap","tmapclean","tmapclean_config","tmapSwitches","write"}}) {
            Class<?> app = loader.loadClass("io.github.ldxm666."+vendor[1]+".App");
            Object service = app.getMethod("svc").invoke(null);
            check(service != null, vendor[0]+" module backend connected");
            SharedPreferences prefs = (SharedPreferences)service.getClass().getMethod("getRemotePreferences",String.class).invoke(service,vendor[2]);
            String key = ((Map<String,View>)field(activity,vendor[3])).keySet().iterator().next();
            boolean contained = prefs.contains(key); boolean old = prefs.getBoolean(key,false);
            try {
                boolean saved = (Boolean)app.getMethod(vendor[4],String.class,boolean.class).invoke(null,key,!old);
                check(saved && prefs.getBoolean(key,old)==!old, vendor[0]+" actual switch key commits to correct group");
                if (!vendor[0].equals("amap")) check((Boolean)app.getMethod("read",String.class,boolean.class).invoke(null,key,old)==!old,
                    vendor[0]+" settings read uses committed backend");
            } finally {
                SharedPreferences.Editor editor=prefs.edit(); if(contained)editor.putBoolean(key,old);else editor.remove(key);
                check(editor.commit(),vendor[0]+" original setting restored");
            }
        }
    }
    private void pressAndSwitch() throws Exception {
        final View[] sw = new View[1];
        main(() -> {
            sw[0] = (View)loader.loadClass("io.github.ldxm666.mapclean.GlassSwitch").getConstructor(Context.class).newInstance(activity);
            ((ViewGroup)activity.getWindow().getDecorView()).addView(sw[0], new ViewGroup.LayoutParams(160,130));
            call(sw[0],"setChecked",new Class[]{boolean.class,boolean.class},true,false);
            call(sw[0],"setChecked",new Class[]{boolean.class,boolean.class},false,true);
        });
        SystemClock.sleep(60);
        main(() -> call(sw[0],"setChecked",new Class[]{boolean.class,boolean.class},true,true)); idle();
        main(() -> {
            check(Math.abs((Float)field(sw[0],"progress")-1f) < .001f, "switch rehydrates and retargets correctly");
            long now = SystemClock.uptimeMillis();
            sw[0].dispatchTouchEvent(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,20,20,0));
            sw[0].dispatchTouchEvent(MotionEvent.obtain(now,now+30,MotionEvent.ACTION_CANCEL,20,20,0));
        }); idle();
        main(() -> {
            check(Math.abs(sw[0].getScaleX()-1f)<.001f, "switch cancel restores pressed scale");
            ((ViewGroup)sw[0].getParent()).removeView(sw[0]);
        });
    }
    private void sheet() throws Exception {
        final View[] panel = new View[1]; final int[] closed={0};
        main(() -> {
            panel[0] = (View)loader.loadClass("io.github.ldxm666.mapclean.FloatingPanel")
                .getConstructor(Context.class, boolean.class).newInstance(activity, (activity.getResources().getConfiguration().uiMode&48)==32);
            ((ViewGroup)activity.getWindow().getDecorView()).addView(panel[0],new ViewGroup.LayoutParams(-1,-1));
            call(panel[0],"setOnClose",new Class[]{Runnable.class},(Runnable)()->closed[0]++);
            // Exercise the shared view in isolation; do not bypass the production
            // module/embedded backend gate or write any host preference.
            Field opened = panel[0].getClass().getDeclaredField("panelOpen"); opened.setAccessible(true); opened.set(panel[0], true);
            panel[0].setVisibility(View.VISIBLE);
            call(panel[0],"select",new Class[]{int.class,boolean.class},0,false);
            call(panel[0],"animateSheet",new Class[]{float.class},1f);
        }); idle();
        main(() -> {
            View card = (View)field(panel[0],"card");
            check(card.getWidth() <= panel[0].getWidth(), "embedded sheet fits window width");
            check(card.getBottom() <= panel[0].getHeight(), "embedded sheet fits window height");
            call(panel[0],"select",new Class[]{int.class,boolean.class},1,true);
        });
        SystemClock.sleep(25);
        main(() -> call(panel[0],"select",new Class[]{int.class,boolean.class},1,true)); idle();
        main(() -> check(((View)field(panel[0],"body")).getAlpha()==1f, "repeated category tap cannot strand fading body"));
        capture("embedded-sheet");
        main(() -> call(panel[0],"close",new Class[]{}));
        idle();
        main(() -> {
            check(panel[0].getVisibility()==View.GONE && closed[0]==1, "sheet close completes and calls back once");
            ((ViewGroup)panel[0].getParent()).removeView(panel[0]);
        });
    }
    private void dialogs() throws Exception {
        final android.app.Dialog[] dialog = new android.app.Dialog[1]; final int[] callbacks = {0};
        main(() -> dialog[0] = (android.app.Dialog)call(activity,"glassDialog",
            new Class[]{String.class,String.class,String.class,Runnable.class,String.class,Runnable.class,String.class,Runnable.class},
            "回归检查","用于检查弹窗显示与退出，不修改配置。","关闭",null,null,null,null,null));
        idle();
        main(() -> {
            check(dialog[0].isShowing(), "motion dialog enters");
            check(dialog[0].getWindow().getDecorView().getWidth()<=activity.getResources().getDisplayMetrics().widthPixels,"dialog fits available width");
            dialog[0].cancel();
        }); idle();
        main(() -> check(!dialog[0].isShowing(),"native cancel path completes dialog exit"));
        main(() -> {
            dialog[0].show();
            call(dialog[0],"dismissAfter",new Class[]{Runnable.class},(Runnable)()->callbacks[0]++);
            dialog[0].dismiss();
        }); idle();
        main(() -> check(!dialog[0].isShowing() && callbacks[0]==1,"interrupted dialog enter exits and calls action once"));
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            Intent intent = new Intent().setClassName("io.github.ldxm666.mapadkiller", "io.github.ldxm666.mapclean.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent); idle();
            floatingNav(); springs(); catalogues(); configurationRoundTrip(); capture("home");
            tab(1); capture("config"); accordion(); rapidNavigation(); pressAndSwitch();
            tab(2); capture("backup"); tab(3);
            main(() -> {
                ViewGroup page=(ViewGroup)field(activity,"aboutPage");
                ViewGroup root=(ViewGroup)page.getChildAt(0); ViewGroup header=(ViewGroup)root.getChildAt(0);
                check(((android.widget.ImageView)header.getChildAt(0)).getDrawable()!=null,"about app icon is loaded");
            });
            capture("about"); dialogs(); sheet(); tab(0);
            result.putString("stream",log.toString()+"OK checks="+checks+"\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream",log.toString()+"FAIL "+android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
