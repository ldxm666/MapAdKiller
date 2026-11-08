package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Real Android view coordinates plus the production drawer-fitting implementation. */
public final class BaiduPanelLayoutTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    public static final class NativeSheet {
        public int mState=4, mParentHeight=1000, mMinOffset=0, mMaxOffset=700;
        public int mPeekHeight=300, extendHeight=0, mHideHeight=0;
        public boolean mPeekHeightAuto=false, mHideable=true;
    }
    private static void setStatic(String name, Object value) throws Exception {
        Field field=BaiduPanelHeight.class.getDeclaredField(name);
        field.setAccessible(true);field.set(null,value);
    }
    private static void fit(NativeSheet sheet, View coord, View drawer) throws Exception {
        Method measure=BaiduPanelHeight.class.getDeclaredMethod("measure",Object.class,View.class,View.class);
        Method apply=BaiduPanelHeight.class.getDeclaredMethod("applyIfSettled",Object.class,View.class,View.class);
        measure.setAccessible(true);apply.setAccessible(true);
        measure.invoke(null,sheet,coord,drawer);measure.invoke(null,sheet,coord,drawer);
        apply.invoke(null,sheet,coord,drawer);
    }
    public static void main(String[] args) throws Exception {
        if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        Object thread=threadClass.getMethod("systemMain").invoke(null);
        Context context=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
        FrameLayout drawer=new FrameLayout(context);drawer.layout(0,0,500,1000);
        FrameLayout root=new FrameLayout(context);drawer.addView(root);root.layout(0,40,500,1040);
        View bg=new View(context);bg.setId(123);root.addView(bg);bg.layout(0,0,500,1000);
        FrameLayout row=new FrameLayout(context);root.addView(row);row.layout(0,20,500,220);
        row.setPadding(0,0,0,12);
        View icon=new View(context);row.addView(icon);icon.layout(0,10,100,110);
        int[] excluded={123};
        check(BaiduPanelContent.bottom(root,drawer,excluded)==182,"root offset counted once and native bottom padding retained");
        row.setTranslationY(7);root.scrollTo(0,5);
        check(BaiduPanelContent.bottom(root,drawer,excluded)==184,"translation and scroll use the drawer coordinate system");
        row.setAlpha(0);
        check(BaiduPanelContent.bottom(root,drawer,excluded)==184,"collapsed native row still contributes its expansion extent");
        icon.setVisibility(View.GONE);
        check(BaiduPanelContent.bottom(root,drawer,excluded)==-1,"hidden children do not turn a full-height wrapper into content");
        icon.setVisibility(View.VISIBLE);row.setVisibility(View.INVISIBLE);
        check(BaiduPanelContent.bottom(root,drawer,excluded)==-1,"invisible branches do not reserve blank travel");
        row.setVisibility(View.VISIBLE);
        FrameLayout unrelated=new FrameLayout(context);
        check(BaiduPanelContent.bottom(root,unrelated,excluded)==-1,"unrelated coordinate roots fail open");

        // The native fitting path must grow again after it has fitted a small one-row grid.
        drawer.removeView(root);View content=new View(context);drawer.addView(content);
        FrameLayout coordinator=new FrameLayout(context);coordinator.layout(0,0,500,1000);
        NativeSheet sheet=new NativeSheet();setStatic("ENGINE_SHEET",sheet);setStatic("ENGINE_PEEK",300);
        for(int height:new int[]{60,160,520,820,240,90,620,160}) {
            content.layout(0,0,500,height);fit(sheet,coordinator,drawer);
            check(sheet.mMinOffset==1000-height,"expanded bound follows actual content: "+height);
            check(sheet.mPeekHeight==Math.min(300,height),"peek retains original native demand: "+height);
            check(sheet.mMaxOffset==1000-Math.min(300,height),"collapsed bound matches fitted content: "+height);
            check(sheet.mMaxOffset>=sheet.mMinOffset,"native range never inverted: "+height);
            check(sheet.extendHeight==1000-height,"native extendHeight uses measured extent: "+height);
        }
        sheet.mMinOffset=0;sheet.mMaxOffset=700;sheet.extendHeight=0;sheet.mPeekHeight=300;
        fit(sheet,coordinator,drawer);
        check(sheet.mMinOffset==840 && sheet.mMaxOffset==840,"native reset repaired even if cached targets are unchanged");
        sheet.mState=1;content.layout(0,0,500,500);fit(sheet,coordinator,drawer);
        check(sheet.mMinOffset==840,"bounds do not move during active dragging");
        sheet.mState=2;fit(sheet,coordinator,drawer);
        check(sheet.mMinOffset==840,"bounds do not move during native settling");
        sheet.mState=4;fit(sheet,coordinator,drawer);
        check(sheet.mMinOffset==500 && sheet.mMaxOffset==700,"new extent applied after settling");
        System.out.println("BaiduPanelLayoutTest: "+checks+" assertions passed");
    }
}
