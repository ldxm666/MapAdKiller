package io.github.ldxm666.mapclean.tests;

import android.app.*;
import android.os.*;
import android.content.SharedPreferences;
import org.json.*;
import java.lang.reflect.*;

/** Test-only access through the module's own authenticated preferences service. */
public final class AmapPrefsProbe extends Instrumentation {
    String changes;boolean readTools;
    public void onCreate(Bundle args){super.onCreate(args);changes=args==null?null:args.getString("changes");readTools=args!=null&&Boolean.parseBoolean(args.getString("tools","false"));if(args!=null&&args.containsKey("changes64"))changes=new String(android.util.Base64.decode(args.getString("changes64"),android.util.Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Class<?> app=Class.forName("io.github.ldxm666.mapadkiller.App");
        Object service=null;
        for(int i=0;i<40&&service==null;i++){service=app.getMethod("svc").invoke(null);if(service==null)SystemClock.sleep(200);}
        if(service==null)throw new IllegalStateException("module service not connected");
        Method getter=service.getClass().getMethod("getRemotePreferences",String.class);getter.setAccessible(true);
        SharedPreferences prefs=(SharedPreferences)getter.invoke(service,"amap_enhancer_config");
        if(changes!=null){
            JSONObject update=new JSONObject(changes);SharedPreferences.Editor editor=prefs.edit();
            java.util.Iterator<String> it=update.keys();
            while(it.hasNext()){
                String key=it.next();
                if(!key.matches("feed_(weather|board|content|festival|other)|map_promo|debug_log|taxi_card|ride_card_off|tool_id_[0-9]+|tool_更多工具|ui_tools_(order|visible)"))throw new IllegalArgumentException("unsupported test key");
                if(update.isNull(key))editor.remove(key);
                else if(key.equals("ui_tools_order"))editor.putString(key,update.getString(key));
                else editor.putBoolean(key,update.getBoolean(key));
            }
            if(!editor.commit())throw new IllegalStateException("commit failed");
        }
        JSONObject snapshot=new JSONObject();
        for(String key:new String[]{"feed_weather","feed_board","feed_content","feed_festival","feed_other","taxi_card","ride_card_off","map_promo","debug_log"})
            snapshot.put(key,prefs.contains(key)?prefs.getBoolean(key,false):JSONObject.NULL);
        if(readTools){JSONObject tools=new JSONObject();for(java.util.Map.Entry<String,?> item:prefs.getAll().entrySet())
            if(item.getKey().startsWith("tool_")||item.getKey().startsWith("ui_tools_"))tools.put(item.getKey(),item.getValue());snapshot.put("tools",tools);}
        result.putString("stream","PREFS "+snapshot.toString()+"\n");
        finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
