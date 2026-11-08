package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** TEST-CFG-BAIDU-234: remote callbacks and embedded invalidation refresh the same native consumers. */
public final class BaiduConfigRefreshTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void set(Class<?> cls,String name,Object value)throws Exception{Field f=cls.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    public static final class Tools { public int binds;public void updateView(){binds++;} }
    public static void main(String[] ignored)throws Exception{
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context c=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        set(Cfg.class,"localCache",prefs);set(Cfg.class,"resolved",prefs);set(Cfg.class,"embState",1);
        Field lf=Cfg.class.getDeclaredField("listener");lf.setAccessible(true);
        SharedPreferences.OnSharedPreferenceChangeListener listener=(SharedPreferences.OnSharedPreferenceChangeListener)lf.get(null);
        values.put(Spec.K_HC,true);values.put(Spec.K_HC_HOME,true);values.put(Spec.K_HC_COMPANY,true);values.put(Spec.K_HC_SETTING,true);
        Cfg.invalidate();
        BaiduCommuteTest.NativeCommute commute=new BaiduCommuteTest.NativeCommute(c);BaiduCommute.apply(commute);
        Tools tools=new Tools();set(BaiduToolGrid.class,"component",new WeakReference<Object>(tools));
        set(BaiduToolGrid.class,"updateView",Tools.class.getMethod("updateView"));
        for(int i=0;i<12;i++){
            boolean show=i%2==0;int before=commute.binds,tbefore=tools.binds;
            values.put(Spec.K_HC_HOME,show);listener.onSharedPreferenceChanged(prefs,Spec.K_HC_HOME);
            check(commute.binds==before+1,"remote preference callback rebinds commute");
            check(commute.homeInfo.getVisibility()==(show?View.VISIBLE:View.GONE),"remote Home switch applies without settings UI");
            check(commute.companyInfo.getVisibility()==View.VISIBLE,"Company independent of Home");
            check(tools.binds==tbefore,"commute callback leaves tools alone");
            values.put(Spec.K_HC_HOME,!show);Cfg.invalidate(Spec.K_HC_HOME);
            check(commute.homeInfo.getVisibility()==(!show?View.VISIBLE:View.GONE),"embedded invalidation has same result");
            before=commute.binds;listener.onSharedPreferenceChanged(prefs,Spec.K_TOOL+"test");
            check(tools.binds==tbefore+1&&commute.binds==before,"tool callback refreshes only native tools");
        }
        int cb=commute.binds,tb=tools.binds;listener.onSharedPreferenceChanged(prefs,"unrelated");
        check(commute.binds==cb&&tools.binds==tb,"unrelated writes do not reconstruct native UI");
        listener.onSharedPreferenceChanged(prefs,null);
        check(commute.binds==cb+1&&tools.binds==tb+1,"bulk remote change refreshes both consumers");
        cb=commute.binds;tb=tools.binds;Cfg.invalidate();
        check(commute.binds==cb+1&&tools.binds==tb+1,"bulk local restore refreshes both consumers");
        System.out.println("BaiduConfigRefreshTest: "+checks+" assertions passed");
    }
}
