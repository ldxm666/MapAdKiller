package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Replay the actual production window at controlled times; never inject device GPS or launch routes. */
public final class AmapCruiseTest {
    private static int checks;
    private static Object companion;
    private static Constructor<?> sample;
    private static Method advance,clear,fix;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static Field field(String name)throws Exception {Field f=AmapCruise.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void put(String name,Object value)throws Exception {field(name).set(null,value);}
    private static long number(String name)throws Exception{return ((Number)field(name).get(null)).longValue();}
    private static boolean tick(long now,float speed,String source)throws Exception {
        return (Boolean)advance.invoke(companion,now,sample.newInstance(speed,source,10f));
    }
    private static void reset()throws Exception {
        put("lastLat",30.0);put("lastLng",104.0);put("lastPosAt",1000L);
        put("totalDistM",0f);put("distFixSeq",0L);put("lastAcc",10f);
        clear.invoke(companion);
    }
    public static void nativeSetter(boolean value){}
    public static final class NativePage {
        boolean armed,fail;int calls,manual;
        public void setAutoEnterDriveRadar(boolean enabled){calls++;if(fail)throw new IllegalStateException("native-page-fixture");armed=enabled;}
        public void manualCruise(){manual++;}
    }
    public static final class NativeModule {
        final NativePage page;final XposedInterface.Hooker hook;int calls;
        NativeModule(NativePage page,XposedInterface.Hooker hook){this.page=page;this.hook=hook;}
        public void setAutoEnterDriveRadar(boolean enabled){
            calls++;
            try{hook.intercept(new NestedChain(page,enabled));}
            catch(RuntimeException|Error e){throw e;}
            catch(Throwable t){throw new AssertionError(t);}
        }
    }
    /** Replays the verified module -> page call, with the production hook at both layers. */
    public static final class NestedChain implements XposedInterface.Chain {
        final Object target;Object[] args;int calls;
        NestedChain(Object target,boolean requested){this.target=target;this.args=new Object[]{requested};}
        public Executable getExecutable(){try{return target.getClass().getMethod("setAutoEnterDriveRadar",boolean.class);}catch(Exception e){throw new RuntimeException(e);}}
        public Object getThisObject(){return target;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){calls++;if(target instanceof NativeModule)((NativeModule)target).setAutoEnterDriveRadar((Boolean)args[0]);else ((NativePage)target).setAutoEnterDriveRadar((Boolean)args[0]);return null;}
        public Object proceed(Object[] a){args=a;return proceed();}
        public Object proceedWith(Object o){return proceed();}
        public Object proceedWith(Object o,Object[] a){return proceed(a);}
    }
    private static boolean remembered(Object target)throws Exception {
        Field field=AmapCruiseOfficial.class.getDeclaredField("targets");field.setAccessible(true);
        Object state=((Map<?,?>)field.get(null)).get(target);
        check(state!=null,"native target remembered");
        Field requested=state.getClass().getDeclaredField("requested");requested.setAccessible(true);
        return (Boolean)requested.get(state);
    }
    private static void nestedRequests(Map<String,Object> values,XposedInterface.Hooker hook)throws Exception {
        values.put(Config.K_CRUISE_AUTO,false);values.put(Config.K_CRUISE_OFFICIAL,true);values.put(Config.K_CRUISE_FORCE_OFF,false);Config.invalidate();
        NativePage page=new NativePage();NativeModule module=new NativeModule(page,hook);
        NestedChain chain=new NestedChain(module,false);
        try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}
        check(chain.calls==1&&module.calls==1&&page.calls==1&&page.armed,"nested forced-on request executes each native layer once");
        check(!remembered(module)&&!remembered(page),"both layers remember original false rather than module-forced true");
        values.put(Config.K_CRUISE_OFFICIAL,false);Config.invalidate(Config.K_CRUISE_OFFICIAL);
        check(!page.armed,"turning official restore off disarms the nested native detector");
        values.put(Config.K_CRUISE_FORCE_OFF,true);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(!page.armed,"force-off keeps nested detector disarmed");
        values.put(Config.K_CRUISE_FORCE_OFF,false);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(!page.armed,"unblocking with both auto switches off cannot revive a forced request");
        check(!remembered(module)&&!remembered(page),"configuration replay cannot replace saved host requests");
        values.put(Config.K_CRUISE_FORCE_OFF,true);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        chain=new NestedChain(module,true);
        try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}
        check(!page.armed&&remembered(module)&&remembered(page),"both layers remember original true rather than module-forced false");
        values.put(Config.K_CRUISE_FORCE_OFF,false);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(page.armed,"genuine native true remains restorable after a blocked request");
        values.put(Config.K_CRUISE_FORCE_OFF,true);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(!page.armed,"force-off overrides genuine nested native true");
        page.manualCruise();check(page.manual==1,"nested automatic guard leaves manual entry callable");
        values.put(Config.K_CRUISE_FORCE_OFF,false);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(page.armed,"unblocking restores genuine native request");
        page.fail=true;chain=new NestedChain(module,false);int pageCalls=page.calls;boolean threw=false;
        try{hook.intercept(chain);}catch(IllegalStateException expected){threw=true;}catch(Throwable t){throw new AssertionError(t);}
        check(threw&&chain.calls==1&&page.calls==pageCalls+1,"nested native exception propagates without double execution");
        Field scope=AmapCruiseOfficial.class.getDeclaredField("originalRequest");scope.setAccessible(true);
        check(((ThreadLocal<?>)scope.get(null)).get()==null,"nested request scope cleared after native exception");
        page.fail=false;chain=new NestedChain(module,true);
        try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}
        check(page.armed&&remembered(page),"failed false request cannot contaminate the next native true request");
    }
    public static final class Chain implements XposedInterface.Chain {
        Object[] args;int calls;boolean fail;NativePage target;
        Chain(Object... args){this.args=args;}
        public Executable getExecutable(){try{return target==null?AmapCruiseTest.class.getMethod("nativeSetter",boolean.class):NativePage.class.getMethod("setAutoEnterDriveRadar",boolean.class);}catch(Exception e){throw new RuntimeException(e);}}
        public Object getThisObject(){return target;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){calls++;if(fail)throw new IllegalStateException("native-fixture");if(target!=null)target.setAutoEnterDriveRadar((Boolean)args[0]);return null;}
        public Object proceed(Object[] a){args=a;return proceed();}
        public Object proceedWith(Object o){return proceed();}
        public Object proceedWith(Object o,Object[] a){return proceed(a);}
    }
    public static void main(String[] args)throws Exception {
        if(android.os.Looper.getMainLooper()==null)android.os.Looper.prepareMainLooper();
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        Field cached=Config.class.getDeclaredField("cached");cached.setAccessible(true);cached.set(null,prefs);
        Field backend=Config.class.getDeclaredField("backend");backend.setAccessible(true);backend.set(null,Config.BACKEND_LOCAL);
        companion=AmapCruise.class.getField("Companion").get(null);
        Class<?> type=Class.forName(AmapCruise.class.getName()+"$Companion$Sample");
        sample=type.getDeclaredConstructor(float.class,String.class,float.class);sample.setAccessible(true);
        advance=companion.getClass().getDeclaredMethod("advanceWindow",long.class,type);advance.setAccessible(true);
        clear=companion.getClass().getDeclaredMethod("winClear");clear.setAccessible(true);
        fix=companion.getClass().getDeclaredMethod("addFixAt",double.class,double.class,float.class,boolean.class,long.class);fix.setAccessible(true);
        reset();check(!tick(1000,12,"amap"),"first speed frame opens window");
        check(!tick(9000,12,"amap"),"elapsed time alone cannot pass a stationary position");
        check(number("speedOkSince")==1000,"waiting for GPS preserves original hold and displacement baseline");
        put("lastLat",30.0002);check(tick(11000,12,"amap"),"late valid GPS point enters without another full eight seconds");
        check(number("speedOkSince")==0,"completed window consumed once");
        reset();tick(1000,18,"amap");tick(2000,0,"amap");tick(3000,18,"amap");
        check(number("speedOkSince")==1000,"one dropped speed frame tolerated");
        for(int t=4;t<=7;t++)tick(t*1000,0,"amap");
        check(number("speedOkSince")==0,"sustained stop clears window");
        reset();for(int t=1;t<=25;t++)check(!tick(t*1000,5,"amap"),"normal walking never meets default speed");
        reset();for(int t=1;t<=25;t++)check(!tick(t*1000,18,"amap"),"no displacement never enters despite high speed field");
        reset();tick(1000,12,"move");put("lastLat",30.00011);
        check(!tick(9000,12,"move"),"displacement-only speed has longer time requirement");
        check(!tick(13000,12,"move"),"displacement-only speed has stronger movement requirement");
        put("lastLat",30.0002);check(tick(14000,12,"move"),"verified displacement source still usable");
        for(float speed:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1,201}) {
            reset();for(int t=1;t<=12;t++)check(!tick(t*1000,speed,"amap"),"invalid speed rejected");
            check(number("speedOkSince")==0,"invalid speed cannot accumulate hold");
        }
        reset();put("lastLat",0.0);put("lastLng",0.0);put("lastPosAt",0L);
        fix.invoke(companion,30.0,104.0,10f,true,1000L);
        for(long t:new long[]{1500,2000,2500})fix.invoke(companion,30.0,104.0,10f,true,t);
        check(number("lastPosAt")==1000,"cached duplicate points cannot refresh the displacement clock");
        fix.invoke(companion,30.00005,104.0,10f,true,3000L);
        check(number("distFixSeq")==1,"next actual two-second movement counted once");
        check(number("lastPosAt")==3000,"actual fix advances clock");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,91,-91})fix.invoke(companion,bad,104.0,10f,true,4000L);
        check(number("lastPosAt")==3000,"invalid coordinates cannot poison movement baseline");
        Field hookField=AmapCruiseOfficial.class.getDeclaredField("FORCE_TRUE");hookField.setAccessible(true);
        XposedInterface.Hooker hook=(XposedInterface.Hooker)hookField.get(null);
        for(boolean enabled:new boolean[]{false,true,false,true}) {
            values.put(Config.K_CRUISE_OFFICIAL,enabled);Config.invalidate();
            Chain chain=new Chain(false);try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}
            check(chain.calls==1,"native method called exactly once");
            check(Boolean.valueOf(enabled).equals(chain.args[0]),"official switch applied live in both directions");
        }
        Chain chain=new Chain(true);try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}
        check(chain.calls==1&&Boolean.TRUE.equals(chain.args[0]),"native enabled request preserved");
        chain=new Chain(false);chain.fail=true;boolean threw=false;
        try{hook.intercept(chain);}catch(Throwable t){threw=true;}
        check(threw&&chain.calls==1,"native exception propagates without double proceed");
        chain=new Chain(false,false);try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}
        check(chain.calls==1&&Boolean.FALSE.equals(chain.args[0]),"unexpected overload left intact");
        check(!Config.defaultVisible(Config.K_CRUISE_FORCE_OFF),"force-off defaults disabled");
        for(boolean forceOff:new boolean[]{false,true})for(boolean official:new boolean[]{false,true})for(boolean auto:new boolean[]{false,true}){
            values.put(Config.K_CRUISE_FORCE_OFF,forceOff);values.put(Config.K_CRUISE_OFFICIAL,official);values.put(Config.K_CRUISE_AUTO,auto);Config.invalidate();
            check(Config.cruiseAuto()==(!forceOff&&auto),"force-off has highest module priority");
            check(Config.cruiseOfficial()==(!forceOff&&official),"force-off has highest native priority");
            for(boolean requested:new boolean[]{false,true}){
                Chain c=new Chain(requested);try{hook.intercept(c);}catch(Throwable t){throw new AssertionError(t);}
                check(c.calls==1&&Boolean.valueOf(!forceOff&&(requested||official)).equals(c.args[0]),"all native/force combinations");
            }
        }
        values.put(Config.K_CRUISE_FORCE_OFF,false);values.put(Config.K_CRUISE_AUTO,true);values.put(Config.K_CRUISE_OFFICIAL,true);Config.invalidate();
        put("handler",new android.os.Handler(android.os.Looper.getMainLooper()));
        Method enter=companion.getClass().getDeclaredMethod("enterCruise",float.class,String.class);enter.setAccessible(true);
        enter.invoke(companion,20f,"amap");
        Field pending=field("pendingEnters");List<Runnable> tasks=(List<Runnable>)pending.get(null);
        check(tasks.size()==5,"automatic verification tasks armed");
        List<Runnable> stale=new ArrayList<>(tasks);long seq=number("enterSeq");put("speedOkSince",1000L);
        values.put(Config.K_CRUISE_FORCE_OFF,true);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(tasks.isEmpty()&&number("enterSeq")!=seq&&number("speedOkSince")==0,"force-off cancels queued tasks and accumulated conditions immediately");
        for(Runnable task:stale)task.run();check(tasks.isEmpty(),"stale callbacks cannot rearm automatic retries");
        values.put(Config.K_CRUISE_FORCE_OFF,false);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(number("speedOkSince")==0,"unblocking requires fresh trigger accumulation");
        enter.invoke(companion,20f,"force");int manualTasks=tasks.size();
        values.put(Config.K_CRUISE_FORCE_OFF,true);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(tasks.size()==manualTasks&&manualTasks==5,"deliberate manual test is preserved");
        NativePage page=new NativePage();Chain nativeChain=new Chain(true);nativeChain.target=page;
        try{hook.intercept(nativeChain);}catch(Throwable t){throw new AssertionError(t);}
        check(!page.armed,"native detector cannot arm while blocked");
        values.put(Config.K_CRUISE_FORCE_OFF,false);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(page.armed,"native detector restored through original setter on unblocking");
        values.put(Config.K_CRUISE_FORCE_OFF,true);Config.invalidate(Config.K_CRUISE_FORCE_OFF);
        check(!page.armed,"already armed native detector disarmed immediately");
        page.manualCruise();check(page.manual==1,"manual cruise remains callable while blocked");
        nestedRequests(values,hook);
        System.out.println("PASS "+checks+" cruise replay assertions");
    }
}
