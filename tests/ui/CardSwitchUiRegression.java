package io.github.ldxm666.mapclean.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only production UI check: no switch click, preference editor or host configuration write. */
public final class CardSwitchUiRegression extends Instrumentation {
    private static final String WEATHER="feed_weather", OTHER="feed_other";
    private static final String[] OLD_KEYS={"feed_board","feed_content","feed_festival"};
    private static final String[] OLD_TITLES={"扫街榜 / 周边精选榜单","攻略 / 内容流卡片","出行节 / 促销活动接口"};
    private Activity activity; private ClassLoader loader; private int checks;
    private String floating="not_packaged; shared_catalogue_checked";
    private interface Work {void run() throws Exception;}
    private void main(Work work)throws Exception{
        Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private void texts(View view,List<String> values){if(view instanceof TextView)values.add(((TextView)view).getText().toString());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)texts(((ViewGroup)view).getChildAt(i),values);}
    private int cardGroup(Object[] groups)throws Exception{
        int found=-1;
        for(int i=0;i<groups.length;i++){
            Object[] sections=(Object[])groups[i].getClass().getField("sections").get(groups[i]);
            if(sections==null)continue;
            for(Object raw:sections){Object[] section=(Object[])raw;if(!"内容卡片".equals(section[0]))continue;
                check(found==-1,"one production content-card section");found=i;
                Object[] items=(Object[])section[1];check(items.length==2,"production content-card catalogue contains exactly two switches");
                Map<String,String> labels=new LinkedHashMap<>();
                for(Object item:items)labels.put((String)item.getClass().getField("key").get(item),(String)item.getClass().getField("title").get(item));
                check("天气卡片".equals(labels.get(WEATHER)),"weather retains its independent production key");
                check("推荐与活动卡片".equals(labels.get(OTHER)),"merged production row uses feed_other");
                for(String old:OLD_KEYS)check(!labels.containsKey(old),"old card key is absent from the visible catalogue: "+old);
            }
        }
        check(found>=0,"production content-card section is available");return found;
    }
    private Map<String,Object> stored(SharedPreferences prefs){Map<String,Object> out=new LinkedHashMap<>();Map<String,?> all=prefs.getAll();for(String key:new String[]{WEATHER,OTHER,"feed_board","feed_content","feed_festival"})out.put(key,all.get(key));return out;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent().setClassName("io.github.ldxm666.mapadkiller","io.github.ldxm666.mapclean.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();loader=activity.getClassLoader();
        Class<?> app=loader.loadClass("io.github.ldxm666.mapadkiller.App");Object service=null;
        for(int i=0;i<40&&service==null;i++){service=app.getMethod("svc").invoke(null);if(service==null)SystemClock.sleep(200);}
        check(service!=null,"production preferences service connected for read-only comparison");
        Method getter=service.getClass().getMethod("getRemotePreferences",String.class);getter.setAccessible(true);
        SharedPreferences prefs=(SharedPreferences)getter.invoke(service,"amap_enhancer_config");Map<String,Object> before=stored(prefs);
        Class<?> config=loader.loadClass("io.github.ldxm666.mapadkiller.Config");
        Object[] catalogue=(Object[])loader.loadClass("io.github.ldxm666.mapclean.AmapPanel").getMethod("groups").invoke(null);
        cardGroup(catalogue);
        main(()->{
            call(activity,"showTab",new Class[]{int.class,boolean.class},1,false);
            @SuppressWarnings("unchecked") List<Integer> pageIds=(List<Integer>)field(activity,"appPageIds");
            int amap=pageIds.indexOf(1);check(amap>=0,"module includes AMap settings page");
            call(activity,"selectApp",new Class[]{int.class},amap);
            call(activity,"syncSwitches",new Class[]{});
            @SuppressWarnings("unchecked") Map<String,View> switches=(Map<String,View>)field(activity,"switches");
            check(switches.containsKey(WEATHER)&&switches.containsKey(OTHER),"module renderer creates both current switches");
            for(String old:OLD_KEYS)check(!switches.containsKey(old),"module renderer removes old switch: "+old);
            View card=(View)switches.get(OTHER).getParent().getParent();
            if(card.getVisibility()==View.GONE)((ViewGroup)card.getParent()).getChildAt(0).performClick();
        });
        SystemClock.sleep(500);waitForIdleSync();
        main(()->{
            @SuppressWarnings("unchecked") Map<String,View> switches=(Map<String,View>)field(activity,"switches");
            List<String> labels=new ArrayList<>();texts(activity.getWindow().getDecorView(),labels);
            for(String title:new String[]{"天气卡片","推荐与活动卡片"})check(labels.contains(title),"module renders current title: "+title);
            for(String old:OLD_TITLES)check(!labels.contains(old),"module no longer renders old title: "+old);
            for(String key:new String[]{WEATHER,OTHER}){
                View sw=switches.get(key);boolean expected=(Boolean)config.getMethod("readVisible",SharedPreferences.class,String.class).invoke(null,prefs,key);
                check((Boolean)sw.getClass().getMethod("isChecked").invoke(sw)==expected,"production row reads the effective stored key: "+key);
                check(sw.isShown()&&sw.getHeight()>0,"production card switch is expanded and measured: "+key);
            }
            View row=(View)switches.get(OTHER).getParent();row.requestRectangleOnScreen(new Rect(0,0,row.getWidth(),row.getHeight()),true);
        });
        // Full module deliberately prunes FloatingPanel. If the target includes it,
        // exercise its real renderer; otherwise report only the verified shared catalogue.
        try{
            Class<?> floatingType=loader.loadClass("io.github.ldxm666.mapclean.FloatingPanel");
            main(()->{
                Object panel=floatingType.getConstructor(Context.class,boolean.class).newInstance(activity,false);
                int group=cardGroup((Object[])field(panel,"groups"));call(panel,"select",new Class[]{int.class,boolean.class},group,false);
                @SuppressWarnings("unchecked") Map<String,View> switches=(Map<String,View>)field(panel,"switches");
                check(switches.containsKey(WEATHER)&&switches.containsKey(OTHER),"floating renderer uses the two current card switches");
                for(String old:OLD_KEYS)check(!switches.containsKey(old),"floating renderer omits old card switch: "+old);
            });floating="production_renderer_checked";
        }catch(ClassNotFoundException expected){}
        check(before.equals(stored(prefs)),"card preferences remain unchanged after UI inspection");
        result.putString("stream","OK checks="+checks+"; two production card switches; no settings written; floating="+floating+"\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable t){result.putString("stream","FAIL "+t+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
