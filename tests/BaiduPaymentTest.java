package io.github.ldxm666.bmapclean;

import android.content.SharedPreferences;
import io.github.ldxm666.mapclean.PaymentUiPolicy;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Payment diagnostics must be transparent even when the original SDK throws. */
public final class BaiduPaymentTest {
    private static int checks;
    private static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static void set(String n,Object v)throws Exception{Field f=Cfg.class.getDeclaredField(n);f.setAccessible(true);f.set(null,v);}
    public static final class Chain implements XposedInterface.Chain {
        Object value;RuntimeException error;int calls;
        Chain(Object v){value=v;}
        public Executable getExecutable(){return null;}
        public Object getThisObject(){return null;}
        public List<Object> getArgs(){return Collections.emptyList();}
        public Object getArg(int i){throw new AssertionError("must not inspect orders or SDK parameters");}
        public Object proceed(){calls++;if(error!=null)throw error;return value;}
        public Object proceed(Object[] a){throw new AssertionError("must not replace payment arguments");}
        public Object proceedWith(Object t){throw new AssertionError("must not replace payment target");}
        public Object proceedWith(Object t,Object[] a){throw new AssertionError("must not replace payment target/arguments");}
    }
    public static void main(String[] args)throws Exception{
        Map<String,Object> prefsValues=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(prefsValues);
            if(m.getName().equals("contains"))return prefsValues.containsKey(a[0]);
            if(m.getName().startsWith("get"))return prefsValues.getOrDefault(a[0],a[1]);return null;
        });
        set("localCache",prefs);set("embState",1);set("dbgAt",0L);
        Method trace=BaiduPayment.class.getDeclaredMethod("trace",XposedInterface.Chain.class,String.class,boolean.class,boolean.class);trace.setAccessible(true);
        Method safe=BaiduPayment.class.getDeclaredMethod("safeResult",String.class,Object.class);safe.setAccessible(true);
        Field scope=BaiduPayment.class.getDeclaredField("channel");scope.setAccessible(true);ThreadLocal channel=(ThreadLocal)scope.get(null);
        for(boolean debug:new boolean[]{false,true}){
            prefsValues.put(Cfg.K_DEBUG,debug);set("dbgAt",0L);
            for(boolean nested:new boolean[]{false,true}) for(boolean scoped:new boolean[]{false,true}) for(boolean limited:new boolean[]{false,true}){
                if(nested)channel.set(true);else channel.remove();
                for(Object result:new Object[]{null,false,true,new HashMap<>(),"PRIVATE_ORDER_DO_NOT_LOG"}){
                    Chain c=new Chain(result);Object got=trace.invoke(BaiduPayment.INSTANCE,c,"alipay_sdk",scoped,limited);
                    check(got==result&&c.calls==1,"original result/identity and exactly one invocation survive");
                    check(Objects.equals(channel.get(),nested?Boolean.TRUE:null),"nested scope restored");
                }
                Chain c=new Chain(null);c.error=new IllegalStateException("PRIVATE_ORDER_DO_NOT_LOG");
                try{trace.invoke(BaiduPayment.INSTANCE,c,"wechat_send",scoped,limited);throw new AssertionError("must throw");}
                catch(InvocationTargetException e){check(e.getCause()==c.error&&c.calls==1,"original exception identity survives; no retry");}
                check(Objects.equals(channel.get(),nested?Boolean.TRUE:null),"scope restored after exception");
            }
        }
        channel.remove();
        Map<String,String> result=new HashMap<>();result.put("resultStatus","9000");result.put("result","PRIVATE_ORDER_DO_NOT_LOG");result.put("memo","PRIVATE_ORDER_DO_NOT_LOG");
        check("9000".equals(safe.invoke(BaiduPayment.INSTANCE,"alipay_sdk",result)),"only numeric Alipay status is read");
        result.put("resultStatus","private");check(safe.invoke(BaiduPayment.INSTANCE,"alipay_sdk",result)==null,"non-status text rejected");
        check(safe.invoke(BaiduPayment.INSTANCE,"wechat_send","PRIVATE_ORDER_DO_NOT_LOG")==null,"raw strings never logged");
        for(String name:new String[]{"com.baidu.poly.widget.PolyActivity","com.baidu.poly.widget.PayWebActivity","com.baidu.BaiduMap.wxapi.WXPayEntryActivity","com.baidu.wallet.paysdk.ui.H5PayWebViewActivity","com.alipay.sdk.app.H5PayActivity","com.tencent.map.wxapi.WXEntryActivity"})check(PaymentUiPolicy.protectedActivity(name),"official payment/callback UI excluded from floating overlay");
        for(String name:new String[]{"com.baidu.baidumaps.MapsActivity","com.tencent.map.WelcomeActivity","com.baidu.polyglot.Main","example.pay.Main"})check(!PaymentUiPolicy.protectedActivity(name),"normal map/unrelated namespace remains eligible");
        check(!PaymentUiPolicy.protectedActivity(null),"null class is safe");
        System.out.println("PASS BaiduPaymentTest checks="+checks);
    }
}
