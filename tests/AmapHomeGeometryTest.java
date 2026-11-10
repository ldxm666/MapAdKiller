package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** TEST-AMAP-HOME-BOTTOM-235: actual Android trees, native-shaped QS, both update routes. */
public final class AmapHomeGeometryTest {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private static void set(Class<?> c,String name,Object value)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    public static final class Guide {
        static boolean bottom=true;
        public static String a(){return bottom?"SEARCHBAR_MODE_BOTTOM":"SEARCHBAR_MODE_TOP";}
    }
    public enum State { ANCHORED,COLLAPSED,EXPANDED,DRAGGING,HIDDEN,HIDDEN_EMPTY }
    public static final class QS extends FrameLayout {
        public final FrameLayout mSplashToolboxPanel;
        public final View mSlideableView;
        int minimum,anchor,transparent,writes,extra=-1,settles,drag;
        int statusBar(){int id=getResources().getIdentifier("status_bar_height","dimen","android");return id==0?0:getResources().getDimensionPixelSize(id);}
        State state=State.ANCHORED;
        QS(Context c){super(c);mSplashToolboxPanel=new FrameLayout(c);mSplashToolboxPanel.addView(new View(c),new LayoutParams(100,326));addView(mSplashToolboxPanel);mSlideableView=new View(c);addView(mSlideableView);}
        int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
        public int getMinHeight(boolean bottom){return dp(bottom?152:106)+42;}
        public int getMinHeight(){return minimum;}
        public int getAnchorHeight(){return anchor;}
        public int getTransparentHeight(){return transparent;}
        public float getAnchorPoint(){return (float)(anchor-minimum)/(getHeight()-transparent-minimum);}
        private int computePanelTopPosition(float point){return getHeight()-minimum-(int)(point*(getHeight()-transparent-minimum));}
        public int getViewDragState(){return drag;}
        public int getAnchorHeightExtraNow(){return extra;}
        public State getPanelState(){return state;}
        public void setMinHeight(int n){minimum=n+dp(10);writes++;}
        public void setAnchorHeight(int n){anchor=n+dp(10);}
        public void setTransparentHeight(int n){transparent=n+statusBar();}
        public void computeSlideRange(){}
        public void onMeasureComplete(){}
        public void setPanelState(State s,boolean animate){state=s;if(!animate){settles++;extra=-1;int top=computePanelTopPosition(s==State.COLLAPSED?0:getAnchorPoint());mSlideableView.layout(0,top,100,top+100);}}
        @Override public boolean post(Runnable r){r.run();return true;}
    }
    public static void main(String[] ignored)throws Exception{
        for(float density:new float[]{0.75f,1,1.5f,2,2.625f,3,4})for(int insetDp:new int[]{0,24,42,96})for(boolean bottom:new boolean[]{false,true}){
            int inset=Math.round(insetDp*density),base=Math.round(106*density),top=base+inset,low=Math.round(152*density)+inset,nativeMin=bottom?low:top;
            check(AmapHomeGeometry.minimum(nativeMin,top,low,bottom,false,density)==nativeMin,"visible tabs retain native geometry");
            check(AmapHomeGeometry.minimum(nativeMin,top,low,bottom,true,density)==nativeMin-base,"only reserve removed, exact search and inset retained");
        }
        check(AmapHomeGeometry.minimum(165,119,165,true,true,1)==59,"different inset preserved");
        check(AmapHomeGeometry.minimum(170,124,170,true,true,Float.NaN)==170,"invalid density fail open");
        check(AmapHomeGeometry.minimum(160,106,160,true,true,1)==160,"unknown native spacing fail open");
        check(AmapHomeGeometry.minimum(145,99,145,true,true,1)==145,"undersized native geometry fail open");
        check(AmapHomeGeometry.minimum(350,304,350,true,true,1)==350,"unrecognized oversized inset fail open");
        check(AmapHomeGeometry.minimum(153,106,152,true,true,1)==153,"inconsistent current native minimum fail open");
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");Context c=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        set(Config.class,"cached",prefs);set(Config.class,"backend",Config.BACKEND_LOCAL);set(AmapNativeHome.class,"compactSupported",true);
        for(String key:new String[]{Config.K_FEED_WEATHER,Config.K_FEED_BOARD,Config.K_FEED_CONTENT,Config.K_FEED_FESTIVAL})values.put(key,false);
        for(String label:Config.TABS)values.put("tab_"+label,false);
        set(AmapNativeHome.class,"guideClass",Guide.class);set(AmapNativeHome.class,"guideModeMethod",Guide.class.getMethod("a"));
        Method resize=AmapNativeHome.Companion.getClass().getDeclaredMethod("resize",View.class);resize.setAccessible(true);
        Method settle=AmapTabLayout.class.getDeclaredMethod("settle",Object.class);settle.setAccessible(true);
        FrameLayout root=new FrameLayout(c);QS qs=new QS(c);root.addView(qs,new FrameLayout.LayoutParams(1080,2200));qs.layout(0,0,1080,2200);
        set(AmapNativeHome.class,"quickService",new WeakReference<View>(qs));Config.invalidate();
        resize.invoke(AmapNativeHome.Companion,qs);check(qs.minimum==qs.getMinHeight(true)+qs.dp(10),"no observed bar: retain native minimum");
        AmapTabLayoutTest.Bar bar=new AmapTabLayoutTest.Bar(c,"Main","Taxi","Mine");
        settle.invoke(AmapTabLayout.INSTANCE,bar);check(!AmapTabLayout.hiddenFor(qs),"unattached preload cannot shrink screen");
        root.addView(bar,new FrameLayout.LayoutParams(-1,80));settle.invoke(AmapTabLayout.INSTANCE,bar);
        check(AmapTabLayout.hiddenFor(qs),"same-tree owned hidden bar recognized on attachment");
        SharedPreferences.OnSharedPreferenceChangeListener listener;
        Field listenerField=Config.class.getDeclaredField("listener");listenerField.setAccessible(true);listener=(SharedPreferences.OnSharedPreferenceChangeListener)listenerField.get(null);
        for(boolean bottom:new boolean[]{false,true})for(State state:new State[]{State.ANCHORED,State.COLLAPSED,State.EXPANDED})for(int repeat=0;repeat<4;repeat++)for(boolean visible:new boolean[]{true,false}){
            Guide.bottom=bottom;qs.state=state;values.put("tab_我的",visible);
            if((repeat&1)==0)Config.invalidate("tab_我的");else listener.onSharedPreferenceChanged(prefs,"tab_我的");
            int expected=qs.getMinHeight(bottom)-(visible?0:qs.dp(106));
            check(qs.minimum==expected+qs.dp(10),"both preference routes resize live QS");
            check(qs.anchor==expected+326+qs.dp(12)+qs.dp(10),"grid content included exactly once");
            check(qs.state==state,"collapsed/anchored/expanded state retained");
            int writes=qs.writes;resize.invoke(AmapNativeHome.Companion,qs);check(qs.writes==writes,"unchanged geometry avoids layout loop");
        }
        qs.state=State.DRAGGING;int writes=qs.writes;values.put("tab_我的",true);Config.invalidate("tab_我的");check(qs.writes==writes,"do not jump during active drag");
        qs.state=State.ANCHORED;resize.invoke(AmapNativeHome.Companion,qs);check(qs.minimum==qs.getMinHeight(Guide.bottom)+qs.dp(10),"deferred change applied after gesture");
        values.put("tab_我的",false);Config.invalidate();
        FrameLayout otherRoot=new FrameLayout(c);QS other=new QS(c);otherRoot.addView(other);check(!AmapTabLayout.hiddenFor(other),"another screen's bar cannot shrink QS");
        AmapTabLayoutTest.Bar future=new AmapTabLayoutTest.Bar(c,"Main","FutureTab");root.addView(future);settle.invoke(AmapTabLayout.INSTANCE,future);
        check(!AmapTabLayout.hiddenFor(qs)&&qs.minimum==qs.getMinHeight(Guide.bottom)+qs.dp(10),"unknown native tab conservatively retains reserve");
        root.removeView(future);settle.invoke(AmapTabLayout.INSTANCE,bar);check(qs.minimum==qs.getMinHeight(Guide.bottom)+qs.dp(10)-qs.dp(106),"detached obsolete bar no longer controls live geometry");
        // TEST-AMAP-REPEATED-DRAG-235: the outer bounds and cached desired sizes
        // stay unchanged while the native panel is restored to a stale anchor or position.
        Method finish=AmapNativeHome.Companion.getClass().getDeclaredMethod("finishCompact",View.class);finish.setAccessible(true);
        for(int round=0;round<20;round++){
            qs.state=State.ANCHORED;qs.anchor+=200;qs.minimum+=100;qs.transparent-=80;
            resize.invoke(AmapNativeHome.Companion,qs);
            check(qs.minimum==qs.getMinHeight(Guide.bottom)+qs.dp(10)-qs.dp(106)&&qs.anchor==qs.minimum+326+qs.dp(12),"host geometry drift repaired despite unchanged bounds/cache");
            qs.mSlideableView.layout(0,500,100,600);qs.extra=round;int settles=qs.settles;
            finish.invoke(AmapNativeHome.Companion,qs);
            check(qs.settles==settles+1&&qs.extra==-1&&Math.abs(qs.mSlideableView.getTop()-qs.computePanelTopPosition(qs.getAnchorPoint()))<=1,"extra/interrupted anchor snaps to actual content bottom");
            settles=qs.settles;finish.invoke(AmapNativeHome.Companion,qs);check(qs.settles==settles,"aligned stable anchor avoids repeated layout");
            qs.state=State.COLLAPSED;qs.mSlideableView.layout(0,500,100,600);finish.invoke(AmapNativeHome.Companion,qs);
            check(qs.state==State.COLLAPSED&&qs.mSlideableView.getTop()==qs.computePanelTopPosition(0),"collapsed position corrected without expanding");
            qs.state=State.DRAGGING;qs.mSlideableView.layout(0,500,100,600);settles=qs.settles;finish.invoke(AmapNativeHome.Companion,qs);
            check(qs.settles==settles&&qs.mSlideableView.getTop()==500,"active drag never interrupted");
            qs.state=State.EXPANDED;finish.invoke(AmapNativeHome.Companion,qs);check(qs.state==State.ANCHORED,"expanded blank canvas returns to content anchor");
        }
        // Fullscreen and native settling own their position/state until they finish.
        for(State hidden:new State[]{State.HIDDEN,State.HIDDEN_EMPTY}){
            qs.state=hidden;qs.minimum+=100;qs.mSlideableView.setVisibility(View.INVISIBLE);
            writes=qs.writes;int settles=qs.settles;
            for(int i=0;i<30;i++){resize.invoke(AmapNativeHome.Companion,qs);finish.invoke(AmapNativeHome.Companion,qs);}
            check(qs.writes==writes&&qs.settles==settles&&qs.state==hidden,"immersive layout leaves native state and animation untouched");
        }
        qs.mSlideableView.setVisibility(View.VISIBLE);qs.state=State.ANCHORED;qs.drag=2;
        writes=qs.writes;int priorSettles=qs.settles;resize.invoke(AmapNativeHome.Companion,qs);finish.invoke(AmapNativeHome.Companion,qs);
        check(qs.writes==writes&&qs.settles==priorSettles,"native animation never aborted by layout correction");
        qs.drag=0;resize.invoke(AmapNativeHome.Companion,qs);finish.invoke(AmapNativeHome.Companion,qs);
        check(qs.minimum==qs.getMinHeight(Guide.bottom)+qs.dp(10)-qs.dp(106),"restored native panel gets deferred geometry correction");
        writes=qs.writes;priorSettles=qs.settles;
        for(int i=0;i<100;i++){resize.invoke(AmapNativeHome.Companion,qs);finish.invoke(AmapNativeHome.Companion,qs);}
        check(qs.writes==writes&&qs.settles==priorSettles,"100 stable frames cause no new layout or state reset");
        System.out.println("AmapHomeGeometryTest: "+checks+" assertions passed");
    }
}
