package io.github.ldxm666.bmapclean;

import android.content.SharedPreferences;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Tests independent mine switches, the verified render binding, and native event delivery. */
public final class BaiduMineCardsTest {
    private static int checks;
    private static final String[] KEYS={Spec.K_MINE_GRID,Spec.K_MINE_CAR,Spec.K_MINE_VOICE,
        Spec.K_MINE_CARNAV,Spec.K_MINE_SPORT,Spec.K_MINE_BUILD,Spec.K_MINE_OPS,Spec.K_MINE_AD};
    private static final String[][] CARDS={{"common"},{"car"},{"voice","oldVoice"},{"carLogo"},
        {"sport"},{"contribution","ugc"},{"goldCoin","dxmFinance","userOperation","shop"},{"gold","banner"}};
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void set(String name,Object value)throws Exception{
        Field f=Cfg.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    public static final class RuntimeContext {
        public String event,payload;
        public void o(String name,Object value){event=name;payload=(String)value;}
    }
    public static final class Runtime {
        public final RuntimeContext context=new RuntimeContext();
        public RuntimeContext getTalosRuntimeContext(){return context;}
    }
    public static void main(String[] args)throws Exception{
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        threadClass.getMethod("systemMain").invoke(null);
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(p,m,a)->{
                if(m.getName().equals("getAll"))return new HashMap<>(values);
                if(m.getName().equals("edit"))return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class},(editor,edit,pending)->{
                        if(edit.getName().startsWith("put")){values.put((String)pending[0],pending[1]);return editor;}
                        if(edit.getName().equals("commit"))return true;
                        return editor;
                    });
                if(m.getName().startsWith("get"))return values.containsKey(a[0])?values.get(a[0]):a[1];
                return null;
            });
        set("localCache",prefs);set("resolved",prefs);set("embState",1);set("backendState",Cfg.BACKEND_LOCAL);
        for(int mask=0;mask<256;mask++){
            values.put(Spec.K_MINE_APPLY,true);
            List<String> expected=new ArrayList<>();
            for(int bit=0;bit<8;bit++){
                boolean visible=(mask&(1<<bit))!=0;values.put(KEYS[bit],visible);
                if(!visible)expected.addAll(Arrays.asList(CARDS[bit]));
            }
            set("expires",0L);
            check(BaiduMineCards.hidden().equals(expected),"independent combination "+mask);
            values.put(Spec.K_MINE_APPLY,false);set("expires",0L);
            check(BaiduMineCards.hidden().isEmpty(),"master restores all "+mask);
        }
        String plain="var page={initData:function(){return{cardList:[].concat(base)}},"+
            "created:function(){this.nativeCreated=1},attached:function(){},detached:function(){this.nativeDetached=1},"+
            "processServerData:function(e){this.processCardList(e.data)},"+
            "processCardList:function(e){this.data.set(\"cardList\",[].concat(e))}};"+
            "var template='s-for=\"cardName in cardList\"';";
        String compiled=plain.replace("s-for=\"cardName in cardList\"",
            "for:{item:\"cardName\",value:{type:4,paths:[{type:1,value:\"cardList\"}]}}");
        List<String> hidden=Arrays.asList("car","voice");
        for(String source:Arrays.asList(plain,compiled)){
            String adapted=BaiduMineCards.script(source,hidden);
            check(!adapted.equals(source),"known template adapted");
            check(adapted.contains("this.data.set(\"cardList\",[].concat(e))"),"native model assignment preserved");
            check(adapted.contains("nativeCreated=1")&&adapted.contains("nativeDetached=1"),"native lifecycle preserved");
            check(adapted.contains("__mapcleanMineCards234"),"separate render array");
            check(!adapted.contains("Array.prototype")&&!adapted.contains("gk"),"no global mutations");
            check(BaiduMineCards.script(adapted,hidden).equals(adapted),"idempotent");
            check(!BaiduMineCards.script(source,Collections.emptyList()).equals(source),"all-on still subscribes");
            check(BaiduMineCards.script(source+source,hidden).equals(source+source),"ambiguous shape unchanged");
        }
        check(BaiduMineCards.script("unrelated",hidden).equals("unrelated"),"unknown script unchanged");
        for(String key:KEYS){
            values.put(Spec.K_MINE_APPLY,false);
            check(App.write(key,true),"child commits through actual embedded settings path");
            check(Boolean.TRUE.equals(values.get(Spec.K_MINE_APPLY)),"individual card enables master");
        }
        Runtime runtime=new Runtime();BaiduMineCards.track(runtime);
        Method publish=BaiduMineCards.Companion.getClass().getDeclaredMethod("publish");publish.setAccessible(true);
        values.put(Spec.K_MINE_APPLY,true);
        for(String key:KEYS)values.put(key,false);
        values.put(Spec.K_MINE_GRID,true);set("expires",0L);publish.invoke(BaiduMineCards.Companion);
        check(BaiduMineCards.EVENT.equals(runtime.context.event),"verified native emitter event");
        check(!runtime.context.payload.contains("common")&&runtime.context.payload.contains("carLogo"),"only grid on");
        values.put(Spec.K_MINE_APPLY,false);set("expires",0L);publish.invoke(BaiduMineCards.Companion);
        check(runtime.context.payload.equals("[]"),"master-off event restores");
        if(args.length==3){
            String source=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
            String adapted=BaiduMineCards.script(source,hidden);
            check(!adapted.equals(source),"actual device bundle adapted "+args[1]);
            Files.write(Paths.get(args[2]),adapted.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("PASS BaiduMineCardsTest "+checks+" assertions");
    }
}
