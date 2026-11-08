package io.github.ldxm666.mapclean;

import android.content.Intent;
import io.github.libxposed.api.XposedInterface;
import io.github.ldxm666.mapadkiller.AmapSubprocess;
import io.github.ldxm666.bmapclean.BaiduPower;
import java.lang.reflect.*;
import java.util.*;

/** OEM card actions and native payloads must survive widget power filtering. */
public final class SystemCardCompatTest {
    private static int checks;
    private static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    private static final class Chain implements XposedInterface.Chain {
        final Intent intent;int calls;
        Chain(String action){intent=new Intent(action).putExtra("oem-card-fixture","unchanged");}
        public Executable getExecutable(){return null;}
        public Object getThisObject(){return null;}
        public Object getArg(int i){return i==1?intent:null;}
        public List<Object> getArgs(){return Arrays.asList(null,intent);}
        public Object proceed(){calls++;return "original";}
        public Object proceed(Object[] a){throw new AssertionError("arguments changed");}
        public Object proceedWith(Object t){throw new AssertionError("receiver changed");}
        public Object proceedWith(Object t,Object[] a){return proceed(a);}
    }
    public static void main(String[] ignored)throws Throwable{
        String mode=ignored.length==0?"both":ignored[0];
        List<XposedInterface.Hooker> hooks=new ArrayList<>();
        if(!mode.equals("baidu")){
            Field f=Class.forName("io.github.ldxm666.mapadkiller.AmapSubprocess").getDeclaredField("WIDGET_RECEIVE");f.setAccessible(true);
            hooks.add((XposedInterface.Hooker)f.get(null));
        }
        if(!mode.equals("amap"))hooks.add((XposedInterface.Hooker)Class.forName("io.github.ldxm666.bmapclean.BaiduPower").getMethod("widgetUpdate",String.class).invoke(null,"fixture"));
        String[] actions={null,"miui.appwidget.action.APPWIDGET_UPDATE","com.oplus.cardwidget.UPDATE","android.appwidget.action.APPWIDGET_UPDATE_OPTIONS","android.appwidget.action.APPWIDGET_ENABLED","android.appwidget.action.APPWIDGET_DELETED","vendor.APPWIDGET_UPDATE","android.appwidget.action.APPWIDGET_UPDATE"};
        for(XposedInterface.Hooker hook:hooks)for(String action:actions){
            Chain c=new Chain(action);Object result=hook.intercept(c);
            boolean block="android.appwidget.action.APPWIDGET_UPDATE".equals(action);
            check(c.calls==(block?0:1),"native dispatch count for "+action);
            check(block?result==null:"original".equals(result),"native return for "+action);
            check(Objects.equals(c.intent.getAction(),action)&&"unchanged".equals(c.intent.getStringExtra("oem-card-fixture")),"OEM action/payload preserved");
        }
        System.out.println("PASS SystemCardCompatTest checks="+checks);
    }
}
