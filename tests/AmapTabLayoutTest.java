package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.*;
import java.util.*;

/** Real Android visibility/weight/layout tests, including both preference update routes. */
public final class AmapTabLayoutTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static final class Model { public String a; Model(String id){a=id;} }
    public static final class Click {
        final Bar bar;final Model model;int calls;
        Click(Bar b,Model m){bar=b;model=m;}
        public void onTabClick(View view){calls++;bar.mCurrentTab=model;}
    }
    public static final class Tab extends View {
        final String tabId;final Click click;
        Tab(Context c,Bar b,Model m){super(c);tabId=m.a;click=new Click(b,m);}
        public Click getOnItemClickListener(){return click;}
    }
    public static final class Bar extends LinearLayout {
        public final List<Model> mTabs=new ArrayList<>();
        public final LinearLayout tabContainer;
        public Model mCurrentTab;
        Bar(Context c,String... ids){
            super(c);tabContainer=new LinearLayout(c);addView(tabContainer,new LayoutParams(-1,-1));
            for(String id:ids){Model model=new Model(id);mTabs.add(model);tabContainer.addView(new Tab(c,this,model),new LayoutParams(0,-1,1));}
            mCurrentTab=mTabs.isEmpty()?null:mTabs.get(0);
        }
        @Override public boolean post(Runnable action){action.run();return true;}
        Tab tab(String id){for(int i=0;i<tabContainer.getChildCount();i++){Tab t=(Tab)tabContainer.getChildAt(i);if(t.tabId.equals(id))return t;}return null;}
    }
    static void layout(Bar bar){
        bar.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(80,View.MeasureSpec.EXACTLY));
        bar.layout(0,0,1000,80);
    }
    public static void main(String[] args)throws Exception{
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context c=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        Field cached=Config.class.getDeclaredField("cached");cached.setAccessible(true);cached.set(null,prefs);
        Field backend=Config.class.getDeclaredField("backend");backend.setAccessible(true);backend.set(null,Config.BACKEND_LOCAL);
        Method settle=AmapTabLayout.class.getDeclaredMethod("settle",Object.class);settle.setAccessible(true);
        String[] ids={"Nearby","Message","Taxi","Mine"};String[] labels={"探索","长按说话","打车","我的"};
        for(String[] source:new String[][]{{"Main","Taxi","Mine"},{"Main","Mine"},{"Main","Nearby","Message","Taxi","Mine"}}){
            FrameLayout parent=new FrameLayout(c);Bar bar=new Bar(c,source);parent.addView(bar,new FrameLayout.LayoutParams(-1,80));
            List<Model> original=bar.mTabs;List<Model> models=new ArrayList<>(original);List<View> views=new ArrayList<>();
            for(int i=0;i<bar.tabContainer.getChildCount();i++)views.add(bar.tabContainer.getChildAt(i));
            settle.invoke(AmapTabLayout.INSTANCE,bar);
            for(int round=0;round<3;round++)for(int mask=0;mask<16;mask++){
                for(int i=0;i<4;i++)values.put("tab_"+labels[i],(mask&(1<<i))!=0);
                Config.invalidate();layout(bar);
                int live=1;
                for(int i=0;i<4;i++)if(bar.tab(ids[i])!=null){boolean on=(mask&(1<<i))!=0;check(bar.tab(ids[i]).getVisibility()==(on?View.VISIBLE:View.GONE),"individual switch "+ids[i]);if(on)live++;}
                check(bar.getVisibility()==(live==1?View.GONE:View.VISIBLE),"actual host tabs control whole-bar collapse");
                check(bar.getLayoutParams().height==(live==1?0:80),"bar height restored exactly");
                check(parent.getLayoutParams()==null||parent.getLayoutParams().height!=0,"parent without layout params safe");
                check(bar.mTabs==original&&bar.mTabs.equals(models),"native model list identity and order retained");
                for(int i=0;i<views.size();i++)check(bar.tabContainer.getChildAt(i)==views.get(i),"native view/controller retained at original index");
                if(live>1)for(String id:source)if(bar.tab(id).getVisibility()==View.VISIBLE)check(Math.abs(bar.tab(id).getMeasuredWidth()-1000f/live)<=1,"remaining weights fill width");
            }
        }
        values.clear();Config.invalidate();Bar bar=new Bar(c,"Main","Taxi","Mine");FrameLayout parent=new FrameLayout(c);parent.setLayoutParams(new FrameLayout.LayoutParams(-1,96));parent.addView(bar,new FrameLayout.LayoutParams(-1,80));settle.invoke(AmapTabLayout.INSTANCE,bar);
        bar.mCurrentTab=bar.mTabs.get(1);values.put("tab_打车",false);Config.invalidate();
        check(bar.mCurrentTab==bar.mTabs.get(0)&&bar.tab("Main").click.calls==1,"hiding selected tab routes through original Main controller");
        values.put("tab_我的",false);Config.invalidate();check(parent.getLayoutParams().height==0,"sole-child wrapper collapsed");
        values.put("tab_打车",true);Config.invalidate();check(parent.getLayoutParams().height==96,"exact wrapper height restored");
        Field lf=Config.class.getDeclaredField("listener");lf.setAccessible(true);SharedPreferences.OnSharedPreferenceChangeListener listener=(SharedPreferences.OnSharedPreferenceChangeListener)lf.get(null);
        values.put("tab_打车",false);listener.onSharedPreferenceChanged(prefs,"tab_打车");check(bar.tab("Taxi").getVisibility()==View.GONE,"module remote preference notification refreshes existing bar");
        values.put("tab_打车",true);listener.onSharedPreferenceChanged(prefs,"tab_打车");check(bar.tab("Taxi").getVisibility()==View.VISIBLE,"module notification restores retained native tab");
        values.put("tab_打车",false);Config.invalidate();Bar cold=new Bar(c,"Main","Taxi","Mine");settle.invoke(AmapTabLayout.INSTANCE,cold);check(cold.tab("Taxi").getVisibility()==View.GONE,"cold start obeys persisted off");
        values.put("tab_打车",true);Config.invalidate();check(cold.tab("Taxi").getVisibility()==View.VISIBLE,"cold-disabled tab can be re-enabled without restart");
        cold.tab("Taxi").setVisibility(View.INVISIBLE);values.put("tab_打车",false);Config.invalidate();values.put("tab_打车",true);Config.invalidate();check(cold.tab("Taxi").getVisibility()==View.INVISIBLE,"host-owned visibility preserved");
        cold.setVisibility(View.GONE);Config.invalidate();check(cold.getVisibility()==View.GONE,"host-owned bar hiding preserved");
        values.put("tab_打车",false);values.put("tab_我的",false);Config.invalidate();Bar future=new Bar(c,"Main","Taxi","FutureTab");settle.invoke(AmapTabLayout.INSTANCE,future);check(future.getVisibility()==View.VISIBLE&&future.tab("FutureTab").getVisibility()==View.VISIBLE,"unknown future tab is retained");
        System.out.println("PASS "+checks+" live bottom-tab assertions");
    }
}
