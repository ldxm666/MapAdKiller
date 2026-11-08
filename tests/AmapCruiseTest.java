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
    public static final class Chain implements XposedInterface.Chain {
        Object[] args;int calls;boolean fail;
        Chain(Object... args){this.args=args;}
        public Executable getExecutable(){try{return AmapCruiseTest.class.getMethod("nativeSetter",boolean.class);}catch(Exception e){throw new RuntimeException(e);}}
        public Object getThisObject(){return null;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){calls++;if(fail)throw new IllegalStateException("native-fixture");return null;}
        public Object proceed(Object[] a){args=a;return proceed();}
        public Object proceedWith(Object o){return proceed();}
        public Object proceedWith(Object o,Object[] a){return proceed(a);}
    }
    public static void main(String[] args)throws Exception {
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
        System.out.println("PASS "+checks+" cruise replay assertions");
    }
}
