package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Baidu 22.0.0: component binding hooks. No Activity/View/TextView hooks or polling. */
public final class BaiduHooks {
    private static final String DU = "com.baidu.baidumaps.duhelper.commute.";
    private static final Map<View,Integer> rowCounts = new WeakHashMap<>();
    private static final java.util.Set<String> toolIdsLogged = new java.util.HashSet<>();
    private BaiduHooks() {}
    public static void install(ClassLoader cl) throws Exception {
        android.content.pm.PackageInfo version = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_BMAP);
        H.log("baidu_adapter version=" + (version == null ? "unknown" : version.versionName) + " mode=capabilities");
        Class<?> splash = H.cls(cl, "com.baidu.baidumaps.splash.SplashAdManager");
        // Obfuscated splash gates retain their verified semantic meaning only on this build.
        if (version != null && version.versionCode == 1650) for (String name : new String[]{"F","z","w","y"}) {
            hook(splash, name, chain -> false);
        }
        BaiduAdHooks.install(cl);
        BaiduPoiLayer.install(cl);
        BaiduSearchAds.install(cl);
        // The fixed search bar remains reachable when the entire bottom bar is hidden.
        Class<?> searchEntry=H.cls(cl,"com.baidu.baidumaps.aihome.search.DefaultSearchUpUIComponent");
        hook(searchEntry,"getView",chain -> {
            Object result=chain.proceed();
            if(result instanceof View)io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry((View)result,MainHook.PKG_BMAP);
            return result;
        });
        hook(searchEntry,"onResume",chain -> {
            Object result=chain.proceed();
            if(io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_BMAP)) {
                Object binding=field(chain.getThisObject(),"binding");
                if(binding!=null)for(String slot:new String[]{"commonSearchBoxHome","searchBoxAnim"}) {
                    Object entry=field(binding,slot);
                    if(entry instanceof View)io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry((View)entry,MainHook.PKG_BMAP);
                }
            }
            return result;
        });
        Class<?> mainCard = H.cls(cl, DU + "DuMainCardUIComponent");
        hook(mainCard, "applyCardVisibility", chain -> Spec.effectiveVisible(Spec.K_AI_NOW, Spec.HOOK_READ)
            ? chain.proceed() : chain.proceed(new Object[]{false}), boolean.class);
        Class<?> panel = H.cls(cl, "com.baidu.mapframework.aihome.AIHomePanel");
        hook(panel, "getAiHomePanel", chain -> {
            Object result = chain.proceed();
            tabs(chain.getThisObject()); return result;
        }, Context.class, String.class);
        hook(panel, "updateOtherTab", chain -> {
            Object result = chain.proceed(); tabs(chain.getThisObject()); return result;
        });
        Class<?> home=H.cls(cl,"com.baidu.baidumaps.aihome.panel.HomeUIComponent");
        hook(home,"calcBasePeekHeight",chain -> emptyHome()?0:chain.proceed(),android.content.res.Resources.class,int.class);
        hook(home,"adjustPeekHeightByState",chain -> emptyHome()?0:chain.proceed(),
                H.cls(cl,"com.baidu.baidumaps.aihome.newhome.CollapsePanelState"),int.class,int.class,android.content.res.Resources.class);
        for(String name:new String[]{"addUfoView","onResume"})hook(home,name,chain -> {
            Object r=chain.proceed();tabsBinding(field(chain.getThisObject(),"ufoBinding"));
            BaiduTabRouting.refreshComponent(chain.getThisObject());
            if(!Cfg.visible(Spec.K_BAR))hide((View)field(chain.getThisObject(),"ufoContainer"));return r;
        });
        Class<?> tabs=H.cls(cl,"com.baidu.baidumaps.aihome.panel.presenter.HomeTabPresenter");
        for(String name:new String[]{"init","initListeners","initChangeableTab","refreshSkin","bindClickOnTabChanged","refreshUfoWidgetEvent","onResume"})hook(tabs,name,chain -> {
            Object r=chain.proceed();tabsBinding(field(field(chain.getThisObject(),"component"),"ufoBinding"));
            BaiduTabRouting.refreshPresenter(chain.getThisObject());return r;
        });
        // Only this presenter's native bottom-tab taxi route is gated. The
        // separate route/tool/search entry points keep their own navigation.
        hook(tabs,"jumpRentCarPage",chain -> Cfg.visible(Spec.K_TAB+Spec.TABS[3])?chain.proceed():null);
        Class<?> weather=H.cls(cl,"com.baidu.mapframework.common.mapview.action.WeatherAction");
        for(String name:new String[]{"updateView","onStateCreate","showWeather","deactivateForceGone"})hook(weather,name,chain -> {
            Object r=chain.proceed();
            if(!Spec.effectiveVisible(Spec.K_WX_MAP,Spec.HOOK_READ)){
                weather.getField("forceGone").setBoolean(chain.getThisObject(),true);
                hide((View)field(chain.getThisObject(),"weatherLimitedContainer"));
            }
            return r;
        });
        component(cl, "RoutePanelShortCutUIComponent", Spec.K_TOOLS);
        component(cl, "CAAHCUIComponent", Spec.K_HC);
        Class<?> feed=H.cls(cl,DU+"FeedUIComponent");
        hook(feed,"getView",chain -> {
            View v=(View)chain.proceed();
            if(!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
                &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
                &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ))hide(v);
            return v;
        });
        Class<?> route=H.cls(cl,"com.baidu.baidumaps.duhelper.aihome.RouteUIComponent");
        // Native registration is the authoritative path; suppress hidden components before construction.
        hook(H.cls(cl,"com.baidu.baidumaps.duhelper.aihome.RouteUIComponent$l"),"g",chain -> {
            String id=(String)chain.getArg(0);
            boolean hide="du_trip_entrance".equals(id)?!Cfg.visible(Spec.K_TOOLS)
                :"du_trip_address".equals(id)?!Cfg.visible(Spec.K_HC)
                :"du_trip_main_card".equals(id)?!Spec.effectiveVisible(Spec.K_AI_NOW, Spec.HOOK_READ)
                :"du_aide_feed".equals(id)?!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ):false;
            return hide?null:chain.proceed();
        },String.class);
        for(String name:new String[]{"onCreateView","onResume","updateToCollapsedUI","updateToExpandUI"})hook(route,name,chain -> {
            Object r=chain.proceed();Object b=field(chain.getThisObject(),"binding");
            if(b!=null){
                if(!Spec.effectiveVisible(Spec.K_AI_NOW, Spec.HOOK_READ))hide((View)field(b,"componentContainer1"));
                if(!Cfg.visible(Spec.K_TOOLS))hide((View)field(b,"componentContainer3"));
                if(!Cfg.visible(Spec.K_HC))hide((View)field(b,"componentContainer5"));
                if(!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ))hide((View)field(b,"componentContainer6"));
            }return r;
        });
        Class<?> address = H.cls(cl, DU + "CAAHCUIComponent");
        for (String name : new String[]{"initView","viewLocalShow","initBg","selfUpdate"}) {
            hook(address,name,chain -> { Object r=chain.proceed(); address(chain.getThisObject()); return r; });
        }
        Class<?> row = H.cls(cl, "com.baidu.baidumaps.duhelper.view.ShortCutRow");
        hook(row, "update", chain -> {
            List<?> source = (List<?>) chain.getArg(0);
            List<Object> selected = new ArrayList<>();
            for (Object model : source) {
                Object unit = field(model,"d"), label = field(field(unit,"b"),"a");
                String title = label instanceof String ? (String) label : "";
                if (toolVisible(title)) selected.add(model);
                String identity=field(model,"m")+":"+title;
                if(toolIdsLogged.add(identity))H.log("tool model "+identity);
            }
            Object[] args = chain.getArgs().toArray(); args[0] = selected;
            Object r = chain.proceed(args);
            View view = (View) chain.getThisObject();
            rowCounts.put(view, selected.size()); layoutRow(view, selected.size()); return r;
        },List.class,int[].class,String[].class,int.class);
        hook(row,"onSlide",chain -> {
            Integer count=rowCounts.get((View)chain.getThisObject());
            return count!=null && count<5 ? null : chain.proceed();
        },float.class);
        // Feed data and user-center card bindings are handled in their own Talos bundle only.
        BaiduTalos.install(cl);
    }
    private static boolean toolVisible(String title) {
        if (!Cfg.visible(Spec.K_TOOLS)) return false;
        if ("公交地铁".equals(title)) title="公共交通";
        for(String known:Spec.TOOLS) if(known.equals(title)) return Cfg.visible(Spec.K_TOOL+known);
        return true;
    }
    private static boolean emptyHome(){
        return !Cfg.visible(Spec.K_TOOLS)&&!Cfg.visible(Spec.K_HC)
            &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
            &&!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
            &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ)
            &&!Spec.effectiveVisible(Spec.K_AI_NOW,Spec.HOOK_READ);
    }
    private static void component(ClassLoader cl,String name,String key) {
        Class<?> c=H.cls(cl,DU+name);
        hook(c,"getView",chain -> {
            View view=(View)chain.proceed();
            if(view!=null && !Cfg.visible(key)) view.setVisibility(View.GONE);
            return view;
        });
    }
    private static void address(Object self) throws Exception {
        Object binding=field(self,"mBinding");
        if(binding==null) return;
        if(!Cfg.visible(Spec.K_HC)) { hide((View)invoke(binding,"getRoot")); return; }
        if(!Cfg.visible(Spec.K_HC_HOME)) hide((View)field(self,"homeInfo"));
        if(!Cfg.visible(Spec.K_HC_COMPANY)) hide((View)field(self,"companyInfo"));
        if(!Cfg.visible(Spec.K_HC_SETTING)) {
            hide((View)field(binding,"settingContainer"));
            Field width=self.getClass().getDeclaredField("settingContainerWidth");width.setAccessible(true);width.setInt(self,0);
            View content=(View)field(binding,"containerBesidesTitle");
            if(content!=null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)content.getLayoutParams();
                if(lp.rightMargin!=0){lp.rightMargin=0;content.setLayoutParams(lp);}
            }
        }
    }
    private static void tabs(Object panel) throws Exception {
        Object binding=invoke(panel,"getAihomeBinding");
        tabsBinding(binding);
    }
    private static void tabsBinding(Object binding) throws Exception {
        if(binding==null)return;
        LinearLayout bar=(LinearLayout)field(binding,"newBtns");
        if(bar==null)return;
        boolean master=Cfg.visible(Spec.K_BAR);
        // Keep one reachable tab when the bar is shown.
        String[] slots={"newRoute","newNearby","newThird","newFourth","newUser"};
        int n=0;
        for(int i=0;i<slots.length;i++) {
            View cell=(View)field(binding,slots[i]);
            boolean show=master && (i==0 || Cfg.visible(Spec.K_TAB+Spec.TABS[i]));
            if(cell==null)continue;
            if(i==0)io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry(cell,MainHook.PKG_BMAP);
            if(i==slots.length-1 && io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_BMAP)) {
                cell.setOnLongClickListener(view -> {
                    android.content.Context context=view.getContext();
                    for(int depth=0;depth<16 && context!=null;depth++) {
                        if(context instanceof android.app.Activity)
                            return io.github.ldxm666.mapclean.EmbeddedSettings.open((android.app.Activity)context);
                        if(!(context instanceof android.content.ContextWrapper))break;
                        android.content.Context base=((android.content.ContextWrapper)context).getBaseContext();
                        if(base==context)break;
                        context=base;
                    }
                    return false;
                });
            }
            if(!show)hide(cell);
            if(cell.getVisibility()==View.VISIBLE){
                n++; LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)cell.getLayoutParams();
                if(lp.width!=0||lp.weight!=1){lp.width=0;lp.weight=1;cell.setLayoutParams(lp);}
            }
        }
        if(bar.getWeightSum()!=n)bar.setWeightSum(n);
        if(!master){hide(bar);hide((View)invoke(binding,"getRoot"));}
        if(!master||!Cfg.visible(Spec.K_TAB+Spec.TABS[2]))hide((View)field(binding,"homeAiContainer"));
    }
    private static void layoutRow(View row,int count) throws Exception {
        Object cells=field(row,"frameLayouts");if(!(cells instanceof List))return;
        List<?> list=(List<?>)cells;
        int width=row.getWidth();if(width<=0) width=row.getResources().getDisplayMetrics().widthPixels;
        for(int i=0;i<list.size();i++){
            View cell=(View)list.get(i);
            if(i>=count){hide(cell);continue;}
            cell.setVisibility(View.VISIBLE);cell.setAlpha(1f);cell.setTranslationX(0);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)cell.getLayoutParams();
            int w=Math.max(1,width/Math.max(1,count));
            if(lp.width!=w||lp.leftMargin!=i*w){lp.width=w;lp.leftMargin=i*w;cell.setLayoutParams(lp);}
        }
        if(count==0)hide(row);else row.setVisibility(View.VISIBLE);
    }
    static void hide(View view){if(view!=null&&view.getVisibility()!=View.GONE)view.setVisibility(View.GONE);}
    static Object field(Object target,String name) throws Exception {
        if(target==null)return null;
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try {
            Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(target.getClass().getName()+"."+name);
    }
    static Object invoke(Object target,String name) throws Exception {
        if(target==null)return null;Method m=target.getClass().getMethod(name);return m.invoke(target);
    }
    static void hook(Class<?> c,String name,XposedInterface.Hooker hk,Class<?>...args){
        Method m=null;try{if(c!=null)m=c.getDeclaredMethod(name,args);}catch(Throwable ignored){}
        H.hook(m,"baidu_"+name+(c==null?"":c.getSimpleName()),hk);
    }
}
