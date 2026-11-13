package io.github.ldxm666.mapadkiller;

import io.github.ldxm666.mapclean.AmapPanel;
import io.github.ldxm666.mapclean.PanelTypes;
import java.lang.reflect.Method;
import java.util.HashMap;

/** TEST-AMAP-SPLASH-235: defaults, cached readiness and callback release. */
public final class AmapSplashTest {
    private static int checks;
    private static void check(boolean ok,String message) {
        checks++; if(!ok)throw new AssertionError(message);
    }
    public static class Gate {
        public static class a { public int a; public String b,c; }
        public static Gate b(){return null;}
        public boolean a(){return false;}
        public boolean f(int scene){return false;}
        public a g(int scene,String schema){return null;}
        public a h(int scene,String schema){return null;}
    }
    public static class WrongGate {
        public static class a { public int a; public String b,c; }
        public static WrongGate b(){return null;}
        public boolean a(){return false;}
        public boolean f(int scene){return false;}
        public Object g(int scene,String schema){return null;}
        public a h(int scene,String schema){return null;}
    }
    private static final class Request {}
    public static class Requester {
        public void attemptRequestData(int scene) {}
        public void attemptRequestData(int scene,boolean force) {}
    }
    public static class WrongRequester {
        public void attemptRequestData(int scene) {}
        public boolean attemptRequestData(int scene,boolean force) {return false;}
    }
    public static class Realtime implements Runnable {
        public Realtime(String url,String body) {}
        public void run() {}
    }
    private static class Callback {
        int calls; Object received;
        public void onFailure(Request request,IllegalArgumentException error) {
            calls++; received=request;
        }
    }
    private static class Throwing extends Callback {
        @Override public void onFailure(Request request,IllegalArgumentException error) {
            calls++; throw new IllegalStateException("fixture");
        }
    }
    private static final class Primitive {
        int calls;
        public void onFailure(int code){calls++;}
    }
    private static final class Single {
        int calls; Throwable error;
        public void onFailure(Throwable error){calls++;this.error=error;}
    }
    public static void main(String[] ignored) throws Exception {
        String key=Config.K_ALIMAMA_SPLASH_OFF;
        check(AmapSplashKill.requesterShape(Requester.class),"both exact requester overloads accepted");
        check(!AmapSplashKill.requesterShape(WrongRequester.class),"reused obfuscated class rejected by return type");
        check(!AmapSplashKill.requesterShape(null),"absent requester stays native");
        check(AmapSplashKill.realtimeShape(Realtime.class),"verified URL/body Runnable shape accepted");
        check(!AmapSplashKill.realtimeShape(Requester.class),"unrelated Play class is not a realtime task");
        check(Config.defaultVisible(key),"missing ad-block preference defaults on");
        check(!Config.defaultVisible(Config.K_SPLASH_SKIP),"whole-page skip stays opt-in");
        HashMap<String,Object> values=new HashMap<>();
        check(Config.readVisible(values,key),"embedded/module missing key agrees");
        values.put(key,false);check(!Config.readVisible(values,key),"explicit user off preserved");
        values.put(key,true);check(Config.readVisible(values,key),"explicit on preserved");
        for(boolean embedded:new boolean[]{false,true})for(Boolean local:new Boolean[]{null,false,true})for(boolean fallback:new boolean[]{false,true})
            check(AmapSplashRules.enabled(embedded,local,fallback)==(embedded&&local!=null?local:fallback),"early embedded local wins; module keeps authoritative backend");
        boolean found=false;
        for(PanelTypes.Group group:AmapPanel.groups()) {
            if(group.sections==null)continue;
            for(Object[] section:group.sections)for(PanelTypes.Item item:(PanelTypes.Item[])section[1]) {
                if(key.equals(item.key)) {found=true;check(!item.strict,"UI uses same default as hook");}
            }
        }
        check(found,"ad switch present in both settings catalogues");
        Method gate=AmapSplashRules.resolveGate(Gate.class);
        check(gate!=null,"exact verified gate shape accepted");
        check(AmapSplashRules.resolveGate(null)==null,"missing SDK stays native");
        check(AmapSplashRules.resolveGate(WrongGate.class)==null,"reused obfuscated name rejected");
        check(!AmapSplashRules.denyAd(gate,null),"native null unchanged");
        Object foreign=new Object();
        check(!AmapSplashRules.denyAd(gate,foreign),"foreign result unchanged");
        Gate.a routing=new Gate.a();
        check(!AmapSplashRules.denyAd(gate,routing)&&routing.a==0,"schema routing and boot preload untouched");
        int previous=AmapSplashRules.beginPresentation();
        int nested=AmapSplashRules.beginPresentation();
        AmapSplashRules.endPresentation(nested);
        for(int code=-2;code<=4;code++) {
            Gate.a nativeResult=new Gate.a(); nativeResult.a=code;
            nativeResult.b="nativeReason"; nativeResult.c="nativeMetadata";
            boolean blocked=AmapSplashRules.denyAd(gate,nativeResult);
            check(nativeResult.c.equals("nativeMetadata"),"native metadata preserved");
            if(code==0) {
                check(blocked,"allowed cached ad blocked without requiring a constructor");
                check(nativeResult.a==1,"caller follows native NO_SPLASH completion branch");
                check("mapclean_ad_blocked".equals(nativeResult.b),"nonempty native reason");
                check(!AmapSplashRules.denyAd(gate,nativeResult),"native denial is idempotent");
            } else check(!blocked&&nativeResult.a==code&&nativeResult.b.equals("nativeReason"),"native denial/unknown state preserved");
        }
        final boolean[] threadNative={false};
        Thread other=new Thread(new Runnable(){public void run(){
            threadNative[0]=!AmapSplashRules.denyAd(gate,routing)&&routing.a==0;
        }});
        other.start();other.join();
        check(threadNative[0],"presentation scope never leaks to another thread");
        AmapSplashRules.endPresentation(previous);
        check(!AmapSplashRules.denyAd(gate,routing)&&routing.a==0,"scope removed after presentation");
        Method release=AmapAlimama.class.getDeclaredMethod("releaseCallback",Object[].class);
        release.setAccessible(true);
        Request request=new Request();Callback unrelated=new Callback(), callback=new Callback();
        check((Boolean)release.invoke(AmapAlimama.INSTANCE,(Object)new Object[]{request,unrelated,callback}),"known failure callback completes");
        check(callback.calls==1&&callback.received==request,"request and exactly one completion preserved");
        check(unrelated.calls==0,"falcon object is never treated as callback");
        check(!(Boolean)release.invoke(AmapAlimama.INSTANCE,(Object)new Object[]{request,unrelated,new Object()}),"missing callback allows network fallback");
        check(unrelated.calls==0,"failed callback lookup does not call other argument");
        Throwing throwing=new Throwing();
        check(!(Boolean)release.invoke(AmapAlimama.INSTANCE,(Object)new Object[]{request,unrelated,throwing}),"throwing callback allows fallback");
        check(throwing.calls==1&&unrelated.calls==0,"throwing callback never invoked again through superclass");
        Primitive primitive=new Primitive();
        check(!(Boolean)release.invoke(AmapAlimama.INSTANCE,(Object)new Object[]{request,null,primitive}),"primitive signature rejected");
        check(primitive.calls==0,"invalid callback never called");
        Single single=new Single();
        check((Boolean)release.invoke(AmapAlimama.INSTANCE,(Object)new Object[]{request,null,single}),"single exception callback supported");
        check(single.calls==1&&single.error!=null,"Throwable callback receives a real failure");
        check(!(Boolean)release.invoke(AmapAlimama.INSTANCE,(Object)new Object[]{}),"empty arguments fail open");
        System.out.println("AmapSplashTest: "+checks+" assertions passed");
    }
}
