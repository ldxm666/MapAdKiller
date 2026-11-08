package io.github.ldxm666.tmapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** TEST-TMAP-STATE-234: native inputs, geometry and host-owned state survive toggles. */
public final class TmapStateRecoveryTest {
    private static int checks;
    private static final Map<String,Object> values=new HashMap<>();
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void flag(String key,boolean value){values.put(key,value);Cfg.invalidate();}
    private static Method method(String name,Class<?>... types)throws Exception{
        Method m=TmapSeekChips.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    public static final class Page { public String pageName; Page(String s){pageName=s;} }
    public static final class Info { public Page page; Info(String s){page=new Page(s);} }
    public static final class Tab { String page;Tab(String s){page=s;} public String getPage(){return page;} }
    public static final class Base {
        public int seekChipRowHeight=72,lastSeekChipRowHeight=72,calls;
        public boolean home=true,fail;
        public FrameLayout parent;
        Base(Context c){parent=new FrameLayout(c);}
        public boolean isHomePage(){return home;}
        public FrameLayout getSeekParentView(){return parent;}
        public void setSeekChipRowHeight(int h){calls++;if(fail)throw new IllegalStateException("native retry");seekChipRowHeight=h;if(h>0)lastSeekChipRowHeight=h;}
    }
    public static final class Chain implements XposedInterface.Chain {
        final Base base;final Object[] args;int passed=-1,calls;
        Chain(Base b,int h){base=b;args=new Object[]{h};}
        public Executable getExecutable(){return null;}
        public Object getThisObject(){return base;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){return proceed(args);}
        public Object proceed(Object[] a){calls++;passed=(Integer)a[0];base.setSeekChipRowHeight(passed);return "native-result";}
        public Object proceedWith(Object t){throw new AssertionError("receiver changed");}
        public Object proceedWith(Object t,Object[] a){throw new AssertionError("receiver changed");}
    }
    public static void main(String[] ignored)throws Exception{
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context c=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        for(String name:new String[]{"localCache","resolved"}){Field f=Cfg.class.getDeclaredField(name);f.setAccessible(true);f.set(null,prefs);}
        Field emb=Cfg.class.getDeclaredField("embState");emb.setAccessible(true);emb.set(null,1);
        Method pages=TmapTabBar.class.getDeclaredMethod("selectedPages",List.class);pages.setAccessible(true);
        Method views=TmapTabBar.class.getDeclaredMethod("selectedViews",List.class);views.setAccessible(true);
        String[] names={"homePage","explorePage","planPage","mePage","futurePage"};
        List<Info> nativePages=new ArrayList<>();List<Tab> nativeViews=new ArrayList<>();
        for(String name:names){nativePages.add(new Info(name));nativeViews.add(new Tab(name));}
        List<Info> pageInput=Collections.unmodifiableList(nativePages);
        List<Tab> viewInput=Collections.unmodifiableList(nativeViews);
        for(int mask=0;mask<16;mask++){
            for(int i=0;i<4;i++)values.put(Spec.K_TAB+names[i],(mask&(1<<i))!=0);
            Cfg.invalidate();
            List<?> p=(List<?>)pages.invoke(TmapTabBar.INSTANCE,pageInput),v=(List<?>)views.invoke(TmapTabBar.INSTANCE,viewInput);
            check(nativePages.size()==5&&nativeViews.size()==5,"native lists preserved "+mask);
            check(p.size()==Integer.bitCount(mask)+1&&v.size()==p.size(),"matching native model/view count "+mask);
            int index=0;
            for(int i=0;i<5;i++)if(i==4||(mask&(1<<i))!=0){
                check(p.get(index)==nativePages.get(i)&&v.get(index)==nativeViews.get(i),"order, identity, unknown future tab "+mask+":"+i);index++;
            }
            check(p!=pageInput&&v!=viewInput,"only private copies filtered");
        }
        for(String name:names)flag(Spec.K_TAB+name,true);
        check(((List<?>)pages.invoke(TmapTabBar.INSTANCE,pageInput)).size()==5,"native rebind restores every cached tab");
        Method bar=TmapTabBar.class.getDeclaredMethod("setBarVisibility",View.class);bar.setAccessible(true);
        for(int nativeVisibility:new int[]{View.VISIBLE,View.INVISIBLE,View.GONE}){
            View row=new View(c);row.setVisibility(nativeVisibility);
            for(int n=0;n<5;n++){
                flag(Spec.K_BAR,false);bar.invoke(TmapTabBar.INSTANCE,row);check(row.getVisibility()==View.GONE,"bar hidden");
                flag(Spec.K_BAR,true);bar.invoke(TmapTabBar.INSTANCE,row);check(row.getVisibility()==nativeVisibility,"exact host visibility restored");
            }
        }
        View changed=new View(c);flag(Spec.K_BAR,false);bar.invoke(TmapTabBar.INSTANCE,changed);changed.setVisibility(View.INVISIBLE);
        flag(Spec.K_BAR,true);bar.invoke(TmapTabBar.INSTANCE,changed);check(changed.getVisibility()==View.INVISIBLE,"host change takes precedence");
        Method apply=method("apply",Object.class),request=method("request",XposedInterface.Chain.class);
        Base base=new Base(c);
        for(int visibility:new int[]{View.VISIBLE,View.INVISIBLE,View.GONE}){
            base.parent.setVisibility(visibility);
            for(int n=0;n<5;n++){
                flag(Spec.K_SEEK_CHIPS,false);apply.invoke(TmapSeekChips.INSTANCE,base);
                check(base.seekChipRowHeight==0&&base.lastSeekChipRowHeight==0&&base.parent.getVisibility()==View.GONE,"shortcut geometry collapses");
                flag(Spec.K_SEEK_CHIPS,true);apply.invoke(TmapSeekChips.INSTANCE,base);
                check(base.seekChipRowHeight==72&&base.lastSeekChipRowHeight==72,"shortcut geometry returns");
                check(base.parent.getVisibility()==visibility,"shortcut host visibility retained");
            }
        }
        flag(Spec.K_SEEK_CHIPS,false);base.parent.setVisibility(View.VISIBLE);
        Chain chain=new Chain(base,104);check("native-result".equals(request.invoke(TmapSeekChips.INSTANCE,chain)),"native return retained");
        check(chain.calls==1&&chain.passed==0&&base.seekChipRowHeight==0,"hidden native request receives zero once");
        flag(Spec.K_SEEK_CHIPS,true);apply.invoke(TmapSeekChips.INSTANCE,base);
        check(base.seekChipRowHeight==104&&base.lastSeekChipRowHeight==104,"latest requested height restored");
        flag(Spec.K_SEEK_CHIPS,false);apply.invoke(TmapSeekChips.INSTANCE,base);base.fail=true;
        flag(Spec.K_SEEK_CHIPS,true);apply.invoke(TmapSeekChips.INSTANCE,base);check(base.seekChipRowHeight==0,"failed setter remains pending");
        base.fail=false;apply.invoke(TmapSeekChips.INSTANCE,base);check(base.seekChipRowHeight==104,"failed restoration retries");
        flag(Spec.K_SEEK_CHIPS,false);apply.invoke(TmapSeekChips.INSTANCE,base);base.home=false;
        apply.invoke(TmapSeekChips.INSTANCE,base);check(base.seekChipRowHeight==104&&base.parent.getVisibility()==View.VISIBLE,"leaving home restores native row");
        chain=new Chain(base,88);request.invoke(TmapSeekChips.INSTANCE,chain);check(chain.passed==88,"non-home native height passes through");
        base.home=true;apply.invoke(TmapSeekChips.INSTANCE,base);base.parent=null;
        flag(Spec.K_SEEK_CHIPS,true);apply.invoke(TmapSeekChips.INSTANCE,base);check(base.seekChipRowHeight==88,"missing parent does not block geometry restoration");
        Method section=TmapHomeFav.class.getDeclaredMethod("setSection",View.class,boolean.class,String.class);section.setAccessible(true);
        Method hide=TmapHomeFav.class.getDeclaredMethod("hideOnce",View.class,String.class);hide.setAccessible(true);
        Method restore=TmapHomeFav.class.getDeclaredMethod("restoreSections");restore.setAccessible(true);
        for(int visibility:new int[]{View.VISIBLE,View.INVISIBLE,View.GONE}){
            View card=new View(c);card.setVisibility(visibility);
            for(int n=0;n<6;n++){
                section.invoke(TmapHomeFav.INSTANCE,card,false,"fav");section.invoke(TmapHomeFav.INSTANCE,card,false,"fav");
                check(card.getVisibility()==View.GONE,"favorite/feeds native section hides repeatedly");
                section.invoke(TmapHomeFav.INSTANCE,card,true,"fav");
                check(card.getVisibility()==visibility,"favorite/feeds exact native section visibility returns");
                hide.invoke(TmapHomeFav.INSTANCE,card,"fallback_whole");restore.invoke(TmapHomeFav.INSTANCE);
                check(card.getVisibility()==visibility,"whole-card fallback restores exact visibility");
            }
        }
        View card=new View(c);section.invoke(TmapHomeFav.INSTANCE,card,false,"feed");card.setVisibility(View.INVISIBLE);
        section.invoke(TmapHomeFav.INSTANCE,card,true,"feed");check(card.getVisibility()==View.INVISIBLE,"native card visibility change wins over old snapshot");
        System.out.println("TmapStateRecoveryTest: "+checks+" assertions passed");
    }
}
