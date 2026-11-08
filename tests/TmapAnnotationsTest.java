package io.github.ldxm666.tmapclean;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Failure recovery and engine replacement against production annotation logic. */
public final class TmapAnnotationsTest {
    private static int checks;
    private static final Map<String,Object> values=new HashMap<>();
    private static Method update,request,bind;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void visible(boolean v){values.put(Spec.K_MAP_ANNOTATIONS,v);Cfg.invalidate();}
    private static void update(Render r,boolean home)throws Exception{update.invoke(TmapAnnotations.INSTANCE,r,home);}
    public static final class Render {
        boolean labels=true,roads=true,fail;int calls;
        public void setContentVisible(int mask,boolean v){
            calls++;if(fail)throw new IllegalStateException("temporary-renderer");
            if((mask&8)!=0)labels=v;if((mask&1)!=0)roads=v;
        }
    }
    public static final class Node {
        public Object map,mMapEngine,mJNIWrapper,mTXMap;
        Render render;
        public Render getMapRenderController(){return render;}
    }
    public static final class Base {
        final Node scene=new Node();boolean home=true;
        public Node getSceneSwitch(){return scene;}
        public boolean isHomePage(){return home;}
        void engine(Render r){Node n=new Node();n.render=r;n.mTXMap=n;n.mJNIWrapper=n;n.mMapEngine=n;scene.map=n;}
    }
    public static final class Chain implements XposedInterface.Chain {
        final Render render;final Object[] args;int calls;RuntimeException error;
        Chain(Render r,int mask,boolean v){render=r;args=new Object[]{mask,v};}
        public Executable getExecutable(){return null;}
        public Object getThisObject(){return render;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){calls++;if(error!=null)throw error;render.setContentVisible((Integer)args[0],(Boolean)args[1]);return "native-result";}
        public Object proceed(Object[] a){throw new AssertionError("mask or arguments changed");}
        public Object proceedWith(Object t){throw new AssertionError("receiver changed");}
        public Object proceedWith(Object t,Object[] a){return proceed(a);}
    }
    public static void main(String[] ignored)throws Exception{
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        for(String key:new String[]{"localCache","resolved"}){Field f=Cfg.class.getDeclaredField(key);f.setAccessible(true);f.set(null,prefs);}
        Field embedded=Cfg.class.getDeclaredField("embState");embedded.setAccessible(true);embedded.set(null,1);
        update=TmapAnnotations.class.getDeclaredMethod("update",Object.class,boolean.class);update.setAccessible(true);
        request=TmapAnnotations.class.getDeclaredMethod("request",XposedInterface.Chain.class,Object.class,int.class,boolean.class);request.setAccessible(true);
        bind=TmapAnnotations.class.getDeclaredMethod("bind",Object.class);bind.setAccessible(true);
        visible(false);
        Render r=new Render();r.fail=true;update(r,true);
        check(r.labels,"failed hide retains visible native labels");
        r.fail=false;update(r,true);check(!r.labels,"failed hide retries successfully");
        r.fail=true;visible(true);update(r,true);check(!r.labels,"failed restore remains pending");
        r.fail=false;update(r,true);check(r.labels,"restore retries after renderer recovers");
        visible(false);update(r,true);
        Chain failed=new Chain(r,8,false);failed.error=new IllegalArgumentException("native-failure");
        try{request.invoke(TmapAnnotations.INSTANCE,failed,r,8,false);throw new AssertionError("exception swallowed");}
        catch(InvocationTargetException e){check(e.getCause()==failed.error,"native exception identity preserved");}
        check(failed.calls==1,"throwing original executes once");
        visible(true);update(r,true);check(r.labels,"failed native request cannot poison restore baseline");
        for(int mask:new int[]{1,2,7,8,9,24,65535})for(boolean v:new boolean[]{false,true}){
            visible(false);update(r,true);Chain c=new Chain(r,mask,v);
            Object result=request.invoke(TmapAnnotations.INSTANCE,c,r,mask,v);
            check(c.calls==1&&"native-result".equals(result),"original call and result preserved for "+mask);
            if((mask&1)!=0)check(r.roads==v,"road bit follows native request");
            if((mask&8)!=0){check(!r.labels,"successful native annotation request remains masked");visible(true);update(r,true);check(r.labels==v,"restores successful native intent");}
        }
        // An engine missing during initial lifecycle must remain eligible for refresh.
        visible(false);Base b=new Base();bind.invoke(TmapAnnotations.INSTANCE,b);
        Render first=new Render();b.engine(first);TmapAnnotations.refresh();
        check(!first.labels,"late engine is bound by refresh");
        Render second=new Render();b.engine(second);TmapAnnotations.refresh();
        check(first.labels&&!second.labels,"replacement engine receives mask and old engine is restored");
        b.home=false;TmapAnnotations.refresh();check(second.labels,"search/navigation scene restores labels");
        b.home=true;TmapAnnotations.refresh();check(!second.labels,"returning home reapplies mask");
        for(int i=0;i<30;i++){
            visible(true);TmapAnnotations.refresh();check(second.labels,"live enable "+i);
            visible(false);TmapAnnotations.refresh();check(!second.labels,"live disable "+i);
        }
        System.out.println("PASS TmapAnnotationsTest checks="+checks);
    }
}
