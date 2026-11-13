package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** TEST-CFG-AMAP-234: host remote events and bulk restore reach the cached native home controller. */
public final class AmapConfigRefreshTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void set(Class<?> cls,String name,Object value)throws Exception{Field f=cls.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    private static Object get(Class<?> cls,String name)throws Exception{Field f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    public static final class ToolboxPanelV2 extends FrameLayout { ToolboxPanelV2(Context c){super(c);} }
    public static ViewGroup nativeRows(Context c,String theme,int mode,JSONObject boot,Object delegate) {
        LinearLayout fresh=new LinearLayout(c);fresh.setOrientation(LinearLayout.VERTICAL);
        int n=Config.visible(Config.K_TOOLS_VISIBLE)?(Config.visible("tool_id_105")?2:1):0;
        for(int i=0;i<n;i++) fresh.addView(new View(c),new LinearLayout.LayoutParams(100,70));
        return fresh;
    }
    public static void main(String[] ignored)throws Exception{
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context c=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        set(Config.class,"cached",null);set(Config.class,"backend",-1);
        final boolean[] source={false};
        Config.Flag early=new Config.Flag(){protected boolean compute(){return source[0];}};
        check(!early.on(),"pending backend returns temporary fallback");
        source[0]=true;
        check(early.on(),"pending backend never freezes a default");
        set(Config.class,"cached",prefs);set(Config.class,"backend",Config.BACKEND_LOCAL);
        check(early.on(),"final backend resolves flag");
        source[0]=false;
        check(early.on(),"ready flags retain hot-path cache");
        Config.invalidate();check(!early.on(),"context/config invalidation refreshes final flags");
        Method feedSignature=AmapNativeHome.Companion.getClass().getDeclaredMethod("feedSignature");feedSignature.setAccessible(true);
        String[] mergedCards={Config.K_FEED_BOARD,Config.K_FEED_CONTENT,Config.K_FEED_FESTIVAL};
        check(!Config.defaultVisible(Config.K_FEED_OTHER),"merged recommendation switch defaults off");
        for(int bits=0;bits<8;bits++)for(boolean weather:new boolean[]{false,true}){
            values.clear();values.put(Config.K_FEED_WEATHER,weather);
            for(int i=0;i<mergedCards.length;i++)values.put(mergedCards[i],(bits&(1<<i))!=0);
            Config.invalidate();
            check(Config.readVisible(prefs,Config.K_FEED_OTHER)==(bits!=0),"old card choices migrate by OR in settings UI");
            for(String key:mergedCards)check(Config.visible(key)==(bits!=0),"legacy consumers share the merged state");
            check(Config.visible(Config.K_FEED_WEATHER)==weather,"weather remains independent during migration");
            for(boolean show:new boolean[]{false,true,false}){
                values.put(Config.K_FEED_OTHER,show);Config.invalidate(Config.K_FEED_OTHER);
                for(String key:mergedCards)check(Config.visible(key)==show&&Config.hotFlag(key).on()==show,"merged writes override stale legacy values and refresh hot flags");
                check(Config.readVisible(prefs,Config.K_FEED_OTHER)==show&&Config.visible(Config.K_FEED_WEATHER)==weather,"settings and hooks agree without changing weather");
            }
        }
        values.clear();values.put(Config.K_FEED_WEATHER,true);values.put(Config.K_FEED_OTHER,false);Config.invalidate();
        Object cardsOff=feedSignature.invoke(AmapNativeHome.Companion);
        values.put(Config.K_FEED_OTHER,true);Config.invalidate(Config.K_FEED_OTHER);
        check(!cardsOff.equals(feedSignature.invoke(AmapNativeHome.Companion)),"merged switch changes the existing canvas refresh signature");
        values.clear();Config.invalidate();
        values.put(Config.K_FEED_WEATHER,true);
        for(String key:new String[]{Config.K_TOOLS_VISIBLE,Config.K_TOOLS_ALLOWLIST,AmapToolCatalog.key("104"),AmapToolCatalog.key("302")}){
            values.put(key,false);Config.invalidate(key);Object off=feedSignature.invoke(AmapNativeHome.Companion);
            values.put(key,true);Config.invalidate(key);Object on=feedSignature.invoke(AmapNativeHome.Companion);
            check(!off.equals(on),"weather canvas invalidates when a tool selection changes: "+key);
            values.put(key,false);Config.invalidate(key);
            check(off.equals(feedSignature.invoke(AmapNativeHome.Companion)),"restoring a tool selection restores the canvas signature: "+key);
        }
        values.put(Config.K_TOOLS_ORDER,"[\"103\",\"102\"]");Config.invalidate(Config.K_TOOLS_ORDER);
        Object firstOrder=feedSignature.invoke(AmapNativeHome.Companion);
        values.put(Config.K_TOOLS_ORDER,"[\"102\",\"103\"]");Config.invalidate(Config.K_TOOLS_ORDER);
        check(!firstOrder.equals(feedSignature.invoke(AmapNativeHome.Companion)),"weather canvas invalidates for order-only changes");
        Object selectedOrder=feedSignature.invoke(AmapNativeHome.Companion);
        values.put("map_promo",true);Config.invalidate("map_promo");
        check(selectedOrder.equals(feedSignature.invoke(AmapNativeHome.Companion)),"unrelated map widget toggle does not reload home tools");
        values.clear();Config.invalidate();
        ToolboxPanelV2 panel=new ToolboxPanelV2(c);FrameLayout container=new FrameLayout(c);panel.addView(container);
        Class<?> state=Class.forName(AmapNativeHome.class.getName()+"$Companion$Built");
        Constructor<?> ctor=state.getDeclaredConstructor(ViewGroup.class,Context.class,String.class,int.class,JSONObject.class,Object.class);ctor.setAccessible(true);
        set(AmapNativeHome.class,"built",ctor.newInstance(container,c,"light",0,new JSONObject(),null));
        set(AmapNativeHome.class,"panelRef",new WeakReference<Object>(panel));
        SharedPreferences.OnSharedPreferenceChangeListener listener=(SharedPreferences.OnSharedPreferenceChangeListener)get(Config.class,"listener");
        Method signature=AmapNativeHome.Companion.getClass().getDeclaredMethod("signature");signature.setAccessible(true);
        for(String key:new String[]{Config.K_HOME_CHIPS,Config.K_TOOLS_VISIBLE,Config.K_TOOLS_ALLOWLIST,Config.K_TOOL_PREFIX+"test"}){
            for(boolean show:new boolean[]{false,true,false,true}){
                values.put(key,show);set(AmapNativeHome.class,"builtSignature","stale");
                listener.onSharedPreferenceChanged(prefs,key);
                check(!get(AmapNativeHome.class,"builtSignature").equals("stale"),"remote callback reaches native home for "+key);
                check(get(AmapNativeHome.class,"builtSignature").equals(signature.invoke(AmapNativeHome.Companion)),"host refresh uses new preference snapshot");
                set(AmapNativeHome.class,"builtSignature","stale");Config.invalidate(key);
                check(!get(AmapNativeHome.class,"builtSignature").equals("stale"),"local callback reaches same controller");
            }
        }
        values.put(Config.K_TOOLS_ORDER,"[\"103\",\"102\"]");
        set(AmapNativeHome.class,"builtSignature","stale");listener.onSharedPreferenceChanged(prefs,Config.K_TOOLS_ORDER);
        check(get(AmapNativeHome.class,"builtSignature").equals(signature.invoke(AmapNativeHome.Companion)),"string order refresh reaches actual home rebuild");
        set(AmapNativeHome.class,"builtSignature","stale");listener.onSharedPreferenceChanged(prefs,"unrelated");
        check(get(AmapNativeHome.class,"builtSignature").equals("stale"),"unrelated event leaves home untouched");
        listener.onSharedPreferenceChanged(prefs,null);check(!get(AmapNativeHome.class,"builtSignature").equals("stale"),"bulk remote reset refreshes home");
        set(AmapNativeHome.class,"builtSignature","stale");Config.invalidate();
        check(!get(AmapNativeHome.class,"builtSignature").equals("stale"),"bulk local restore refreshes home");
        check(panel.getChildAt(0)==container,"home container identity preserved");
        Method grid=AmapNativeHome.Companion.getClass().getDeclaredMethod("applyToolGridVisibility",ViewGroup.class,boolean.class);grid.setAccessible(true);
        FrameLayout gridRoot=new FrameLayout(c);
        for(int visibility:new int[]{View.VISIBLE,View.INVISIBLE,View.GONE}){
            View cell=new View(c);cell.setVisibility(visibility);gridRoot.addView(cell);
            for(int n=0;n<8;n++){
                grid.invoke(AmapNativeHome.Companion,gridRoot,false);grid.invoke(AmapNativeHome.Companion,gridRoot,false);
                check(cell.getVisibility()==View.GONE,"grid cell collapses repeatedly");
                grid.invoke(AmapNativeHome.Companion,gridRoot,true);
                check(cell.getVisibility()==visibility,"grid restores exact native visibility");
            }
            gridRoot.removeView(cell);
        }
        gridRoot.setVisibility(View.GONE);grid.invoke(AmapNativeHome.Companion,gridRoot,true);
        check(gridRoot.getVisibility()==View.GONE,"grid setting does not reveal a host-hidden parent");
        gridRoot.setPadding(7,29,11,31);grid.invoke(AmapNativeHome.Companion,gridRoot,false);
        check(gridRoot.getPaddingTop()==0&&gridRoot.getPaddingBottom()==0,"hidden grid removes owned vertical padding");
        grid.invoke(AmapNativeHome.Companion,gridRoot,false);grid.invoke(AmapNativeHome.Companion,gridRoot,true);
        check(gridRoot.getPaddingLeft()==7&&gridRoot.getPaddingTop()==29&&gridRoot.getPaddingRight()==11&&gridRoot.getPaddingBottom()==31,"padding restores exactly after repeated off");
        // V2 shares its container with later native cards. Only rows captured at the
        // factory boundary are ours to rebuild; appended content must survive.
        container.removeAllViews();View oldRow=new View(c);container.addView(oldRow);
        Object current=ctor.newInstance(container,c,"light",0,new JSONObject().put("nativeMetadata",17),null);
        set(AmapNativeHome.class,"built",current);
        View ai=new View(c);container.addView(ai);ai.setVisibility(View.INVISIBLE);
        set(AmapNativeHome.class,"factoryMethod",AmapConfigRefreshTest.class.getMethod("nativeRows",Context.class,String.class,int.class,JSONObject.class,Object.class));
        for(int repeat=0;repeat<4;repeat++) for(boolean tools:new boolean[]{false,true}) for(boolean two:new boolean[]{false,true}) {
            values.put(Config.K_TOOLS_VISIBLE,tools);values.put("tool_id_105",two);
            set(AmapNativeHome.class,"builtSignature","stale");Config.invalidate("tool_id_105");
            check(ai.getParent()==container&&ai.getVisibility()==View.INVISIBLE,"appended native card retained");
            check(container.getChildCount()==1+(tools?(two?2:1):0),"live row count follows selected tools");
            check(oldRow.getParent()==null,"obsolete native grid removed");
            check(get(AmapNativeHome.class,"built")==current,"live binding retains actual attached container");
        }
        set(AmapNativeHome.class,"factoryMethod",null);
        System.out.println("AmapConfigRefreshTest: "+checks+" assertions passed");
    }
}
