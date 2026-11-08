package io.github.ldxm666.tmapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.*;
import io.github.libxposed.api.XposedInterface;

/** Real Android geometry, reversible native controls and independent POI masks. */
public final class TmapPanelLayoutTest {
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static void cfg(Map<String,Object> values, String key, boolean value) throws Exception {
        values.put(key,value);Cfg.invalidate();
    }
    public static final class NativeCommute {
        public final View aH,aB;
        NativeCommute(Context c){aH=new TextView(c);aB=new TextView(c);}
    }
    public static final class NativeMap {
        final boolean[] value={true,false,true,true,true};
        final int[] calls=new int[5];
        public void setPOIRichEnable(boolean v){value[0]=v;calls[0]++;}
        public void setAppPoiRichEnabled(boolean v){value[1]=v;calls[1]++;}
        public void setBuildingPoiRichEnable(boolean v){value[2]=v;calls[2]++;}
        public void set3DPOIEnable(boolean v){value[3]=v;calls[3]++;}
        public void setLandmarkEnable(boolean v){value[4]=v;calls[4]++;}
    }
    public static final class NativeGrid extends FrameLayout {
        int layouts,ratioWrites,nativeFull=264; float ratio;
        NativeGrid(Context c){super(c);}
        @Override public void requestLayout(){layouts++;super.requestLayout();}
        public void c(float value){ratio=value;ratioWrites++;}
        public void a(float value) throws Exception {
            Field forced=TmapTools.class.getDeclaredField("gridForced");forced.setAccessible(true);
            if(forced.getBoolean(null)){
                Method pin=TmapTools.class.getDeclaredMethod("pinExpanded",View.class,boolean.class);pin.setAccessible(true);
                pin.invoke(TmapTools.INSTANCE,this,true);return;
            }
            getLayoutParams().height=nativeFull;requestLayout();c(value);
        }
    }
    public enum Feature { PoiRich, AppPoiRich, BuildingRich, MapAnnotationPoi3D, LandMark }
    public static final class NativeController {
        final boolean[] flags={true,false,true,true,true};
        boolean poi3d=true,landmark=true;
        public void setFeatureEnable(Feature f,boolean value){flags[f.ordinal()]=value;}
        public boolean getFeatureEnable(Feature f){return flags[f.ordinal()];}
        public void set3DPOIEnable(boolean value){poi3d=value;}
        public void setLandmarkEnable(boolean value){landmark=value;}
    }
    public static final class NativeChain implements XposedInterface.Chain {
        final Object self; Object[] args;int calls;
        NativeChain(Object target,Object... values){self=target;args=values;}
        public Executable getExecutable(){return null;}
        public Object getThisObject(){return self;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){calls++;return null;}
        public Object proceed(Object[] values){args=values;return proceed();}
        public Object proceedWith(Object target){return proceed();}
        public Object proceedWith(Object target,Object[] values){return proceed(values);}
    }
    public static void main(String[] args)throws Exception {
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context c=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class[]{SharedPreferences.class},(proxy,m,a)->{
                if(m.getName().equals("getAll"))return new HashMap<>(values);
                if(m.getName().equals("contains"))return values.containsKey(a[0]);
                if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);
                return null;
            });
        for(String key:new String[]{"localCache","resolved"}){
            Field f=Cfg.class.getDeclaredField(key);f.setAccessible(true);f.set(null,prefs);
        }
        Field emb=Cfg.class.getDeclaredField("embState");emb.setAccessible(true);emb.set(null,1);
        FrameLayout content=new FrameLayout(c);content.layout(0,0,500,1000);
        View background=new View(c);content.addView(background);background.layout(0,0,500,1000);
        FrameLayout row=new FrameLayout(c);content.addView(row);row.layout(0,50,500,250);
        row.setPadding(0,0,0,8);
        TextView label=new TextView(c);label.setText("设置家");row.addView(label);label.layout(0,10,200,70);
        check(TmapPanelContent.bottom(content,content)==128,"wrapper height is not content height");
        row.setTranslationY(9);content.scrollTo(0,5);
        check(TmapPanelContent.bottom(content,content)==132,"native translation and scroll counted once");
        row.setVisibility(View.GONE);
        check(TmapPanelContent.bottom(content,content)==-1,"hiding final row removes its space");
        row.setVisibility(View.VISIBLE);label.setVisibility(View.GONE);
        check(TmapPanelContent.bottom(content,content)==-1,"empty wrappers never reserve a blank page");
        check(TmapPanelContent.bottom(content,new FrameLayout(c))==-1,"unrelated coordinates fail open");
        for(float density:new float[]{1f,2.625f,3.5f})for(int count:new int[]{1,5,6,10,11,15}){
            int crop=count<=5?0:count<=10?30:40;
            check(TmapPanelContent.expandedGridHeight(300,count,density)==300+Math.round(crop*density),"native row crop follows current count "+count);
        }
        check(TmapPanelContent.expandedGridHeight(300,0,2.625f)==300,"empty grid has no extra height");
        int last=1000;
        for(int height:new int[]{60,160,400,620,820}){
            int limit=TmapPanelContent.topLimit(1000,height,100,12,800);
            check(limit<=last,"larger content gets more upward room");last=limit;
            check(limit==Math.min(800,Math.max(0,888-height)),"bounds contain content plus dock exactly");
        }
        check(TmapPanelContent.topLimit(1000,0,100,12,800)==Integer.MIN_VALUE,"unknown geometry leaves native range intact");
        check(TmapPanelFit.releaseTop(0,600,-100,700,800)==700,"upward release stops at real content");
        check(TmapPanelFit.releaseTop(800,780,100,700,800)==800,"downward release can collapse");
        check(TmapPanelFit.releaseTop(0,700,0,800,800)==800,"zero travel does not invent 96dp blank space");
        View scroll=new View(c);scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        TmapPanelFit.applyScrollEffect(scroll,true);
        check(scroll.getOverScrollMode()==View.OVER_SCROLL_NEVER,"compact native home does not stretch at its edge");
        TmapPanelFit.applyScrollEffect(scroll,true);TmapPanelFit.applyScrollEffect(scroll,false);
        check(scroll.getOverScrollMode()==View.OVER_SCROLL_ALWAYS,"visible feed restores original overscroll policy after repeated requests");
        TmapPanelFit.applyScrollEffect(scroll,true);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        TmapPanelFit.applyScrollEffect(scroll,false);
        check(scroll.getOverScrollMode()==View.OVER_SCROLL_IF_CONTENT_SCROLLS,"native policy changes are never overwritten on restore");
        check(TmapPanelContent.compactTop(2337,2040,242,21,1959,1090)==1247,"native compact endpoint wins over a stale full-height placeholder");
        check(TmapPanelContent.compactTop(2337,-1,242,21,1959,1090)==1247,"late Litho mounting cannot unlock blank full-screen expansion");
        check(TmapPanelContent.compactTop(2337,600,242,21,1959,1090)==1474,"fewer enabled tools reduce the drag range with their measured content");
        for(float progress:new float[]{0f,.001f,.5f,1f})
            check(TmapTools.compactProgress(progress,true,false)==0f,"compact header cannot shift above its native middle endpoint");
        check(TmapTools.compactProgress(.5f,true,true)==.5f,"visible feed retains native expansion motion");
        check(TmapTools.compactProgress(.5f,false,false)==.5f,"disabled expanded grid retains native motion");
        check(TmapTools.compactProgress(-.5f,true,false)==-.5f,"native collapse progress is preserved");
        Method owner=MainHook.class.getDeclaredMethod("canOwnUi",String.class,boolean.class);owner.setAccessible(true);
        check(Boolean.FALSE.equals(owner.invoke(MainHook.INSTANCE,"LSPosed",true)),"root copy cannot double-mask a patched host");
        check(Boolean.TRUE.equals(owner.invoke(MainHook.INSTANCE,"LSPosed",false)),"ordinary root host keeps native UI hooks");
        check(Boolean.TRUE.equals(owner.invoke(MainHook.INSTANCE,"LSPatch",true)),"embedded copy owns patched-host UI hooks");
        Method claim=MainHook.class.getDeclaredMethod("claimUi",Properties.class,String.class);claim.setAccessible(true);
        Properties processProperties=new Properties();
        check(Boolean.TRUE.equals(claim.invoke(MainHook.INSTANCE,processProperties,"LSPatch")),"first module copy claims the process");
        check(Boolean.FALSE.equals(claim.invoke(MainHook.INSTANCE,processProperties,"LSPosed")),"a second class loader cannot install another native mask");
        check(Boolean.FALSE.equals(claim.invoke(MainHook.INSTANCE,processProperties,"LSPatch")),"a repeated embedded copy cannot install twice either");

        // The original runtime failure: native Home + Company share a parent, whereas the
        // feed's full-height loading footer contains an image and only one action.
        Method section=TmapHomeFav.class.getDeclaredMethod("sectionOf",View.class);section.setAccessible(true);
        FrameLayout address=new FrameLayout(c),actions=new FrameLayout(c);address.addView(actions);
        for(int i=0;i<2;i++){
            FrameLayout action=new FrameLayout(c);action.setClickable(true);action.addView(new ImageView(c));actions.addView(action);
        }
        check("fav".equals(section.invoke(TmapHomeFav.INSTANCE,address)),"native paired address actions identify drawable-only Home section");
        FrameLayout footer=new FrameLayout(c),link=new FrameLayout(c);footer.addView(new ImageView(c));
        link.setClickable(true);link.addView(new ImageView(c));footer.addView(link);
        check(section.invoke(TmapHomeFav.INSTANCE,footer)==null,"image-only loading footer never becomes Home section");
        FrameLayout nativeContent=new FrameLayout(c);nativeContent.layout(0,0,1080,2337);
        nativeContent.addView(address);address.layout(0,834,1080,1247);
        nativeContent.addView(footer);footer.layout(0,1247,1080,2294);
        Field sections=TmapHomeFav.class.getDeclaredField("SECTIONS");sections.setAccessible(true);
        Map nativeSections=(Map)sections.get(TmapHomeFav.INSTANCE);nativeSections.clear();nativeSections.put(address,"fav");
        check(TmapHomeFav.visibleSectionBottom(nativeContent)==1247,"viewport-sized unclassified footer excluded from lock height");
        address.setTranslationY(8);nativeContent.scrollTo(0,5);
        check(TmapHomeFav.visibleSectionBottom(nativeContent)==1250,"native section translations counted exactly once");
        nativeSections.clear();

        Method isPanel=TmapTools.class.getDeclaredMethod("isPanel",View.class);isPanel.setAccessible(true);
        com.tencent.map.ama.newhome.widget.HomePageCardView panel=new com.tencent.map.ama.newhome.widget.HomePageCardView(c);
        check(Boolean.TRUE.equals(isPanel.invoke(TmapTools.INSTANCE,panel)),"native outer slide card is a panel");
        check(Boolean.FALSE.equals(isPanel.invoke(TmapTools.INSTANCE,new com.tencent.map.ama.newhome.widget.HomePageCardView.HomePageCardContainerView(c))),"similarly named inner content cannot receive panel commands");

        Field full=TmapTools.class.getDeclaredField("roomFullLp");full.setAccessible(true);full.setInt(null,631);
        Method pin=TmapTools.class.getDeclaredMethod("pinExpanded",View.class,boolean.class);pin.setAccessible(true);
        NativeGrid grid=new NativeGrid(c);grid.setLayoutParams(new FrameLayout.LayoutParams(996,264));
        pin.invoke(TmapTools.INSTANCE,grid,false);int layouts=grid.layouts;
        for(int i=0;i<120;i++)pin.invoke(TmapTools.INSTANCE,grid,true);
        check(grid.getLayoutParams().height==631&&grid.ratio==1f,"expanded native grid keeps height and alpha together");
        check(grid.layouts==layouts&&grid.ratioWrites==1,"120 drag frames cause no relayout or repeated alpha writes");
        Method expand=TmapTools.class.getDeclaredMethod("expandGridOnce",View.class);expand.setAccessible(true);
        for(int nextHeight:new int[]{264,631,264,631}){
            grid.nativeFull=nextHeight;expand.invoke(TmapTools.INSTANCE,grid);
            check(grid.getLayoutParams().height==nextHeight&&full.getInt(null)==nextHeight,
                "late native tool data replaces cached geometry without process restart "+nextHeight);
        }
        Field lastGrid=TmapTools.class.getDeclaredField("lastGrid");lastGrid.setAccessible(true);lastGrid.set(null,grid);
        grid.layout(0,0,996,264);
        check(!TmapTools.expandedGeometryReady(),"collapsed one-row layout cannot become the next upward-drag bound");
        grid.layout(0,0,996,631);
        check(TmapTools.expandedGeometryReady(),"expanded native layout is eligible for content fitting");
        grid.setVisibility(View.GONE);
        check(TmapTools.expandedGeometryReady(),"hidden grid still allows fitting search and address content");
        lastGrid.set(null,null);

        NativeCommute commute=new NativeCommute(c);int[] clicks={0};commute.aH.setOnClickListener(v->clicks[0]++);
        Method apply=TmapHomeFav.class.getDeclaredMethod("applyHomeFav",Object.class);apply.setAccessible(true);
        cfg(values,Spec.K_HOME_FEED,false);
        for(int i=0;i<6;i++){
            cfg(values,Spec.K_HOME_FAV,false);apply.invoke(TmapHomeFav.INSTANCE,commute);
            check(commute.aH.getVisibility()==View.GONE,"native Home hides");
            cfg(values,Spec.K_HOME_FAV,true);TmapHomeFav.refresh();
            check(commute.aH.getVisibility()==View.VISIBLE,"Home restores while recommendation feed stays off");
            commute.aH.performClick();
        }
        check(clicks[0]==6,"native Home click listener survives every switch");

        // Toolbar masking is reversible, home-scoped, and preserves native INVISIBLE state.
        NativeControls controls=new NativeControls(c);controls.locate.setVisibility(View.INVISIBLE);
        int[] locateClicks={0};controls.toolbar.setOnClickListener(v->locateClicks[0]++);
        Method applyControls=TmapMapIcons.class.getDeclaredMethod("applyBase",Object.class);applyControls.setAccessible(true);
        cfg(values,Spec.K_MAP_ICONS,false);applyControls.invoke(TmapMapIcons.INSTANCE,controls);
        check(controls.toolbar.getVisibility()==View.GONE,"home toolbar masks immediately");
        controls.zoom=new View(c);
        applyControls.invoke(TmapMapIcons.INSTANCE,controls);
        check(controls.zoom.getVisibility()==View.GONE,"late native control joins a nonempty cache without restarting");
        controls.home=false;applyControls.invoke(TmapMapIcons.INSTANCE,controls);
        check(controls.toolbar.getVisibility()==View.VISIBLE&&controls.locate.getVisibility()==View.INVISIBLE,"leaving home restores each native visibility");
        controls.home=true;applyControls.invoke(TmapMapIcons.INSTANCE,controls);
        cfg(values,Spec.K_MAP_ICONS,true);TmapMapIcons.refresh();controls.toolbar.performClick();
        check(controls.toolbar.getVisibility()==View.VISIBLE&&locateClicks[0]==1,"toolbar re-enable preserves native click callback");
        check(controls.zoom.getVisibility()==View.VISIBLE,"late control also restores its native visibility");

        NativeMap map=new NativeMap();
        Field maps=TmapPoi.class.getDeclaredField("maps");maps.setAccessible(true);
        Class<?> stateClass=Class.forName("io.github.ldxm666.tmapclean.TmapPoi$State");
        Constructor<?> ctor=stateClass.getDeclaredConstructor();ctor.setAccessible(true);Object state=ctor.newInstance();
        Field requested=stateClass.getDeclaredField("requested");requested.setAccessible(true);
        Boolean[] nativeValues=(Boolean[])requested.get(state);
        for(int i=0;i<5;i++)nativeValues[i]=map.value[i];
        ((Map)maps.get(TmapPoi.INSTANCE)).put(map,state);
        Method refreshMap=TmapPoi.class.getDeclaredMethod("apply",Object.class);refreshMap.setAccessible(true);
        for(int i=0;i<5;i++){
            String key=TmapPoi.keys[i];check(Spec.defaultVisible(key),"POI defaults preserve native behavior");
            cfg(values,key,false);refreshMap.invoke(TmapPoi.INSTANCE,map);
            check(!map.value[i],"individual POI mask applies "+i);
            cfg(values,key,true);refreshMap.invoke(TmapPoi.INSTANCE,map);
            check(map.value[i]==nativeValues[i],"restores requested native value instead of forcing true "+i);
        }
        check(map.calls[0]==2&&map.calls[1]==2&&map.calls[4]==2,"other POI switches never rewrite unrelated features");

        NativeController sdk=new NativeController();
        Field featureType=TmapPoi.class.getDeclaredField("featureClass");featureType.setAccessible(true);featureType.set(null,Feature.class);
        Field featureValues=TmapPoi.class.getDeclaredField("features");featureValues.setAccessible(true);
        Object[] enumValues=(Object[])featureValues.get(null);
        for(int i=0;i<3;i++)enumValues[i]=Feature.values()[i];
        Field sdkMaps=TmapPoi.class.getDeclaredField("controllers");sdkMaps.setAccessible(true);
        Object sdkState=ctor.newInstance();Boolean[] sdkRequests=(Boolean[])requested.get(sdkState);
        for(int i=0;i<5;i++)sdkRequests[i]=i==1?false:true;
        ((Map)sdkMaps.get(null)).put(sdk,sdkState);
        Method applySdk=TmapPoi.class.getDeclaredMethod("applyController",Object.class);applySdk.setAccessible(true);
        for(int i=0;i<5;i++){
            cfg(values,TmapPoi.keys[i],false);applySdk.invoke(TmapPoi.INSTANCE,sdk);
            check(i<3?!sdk.flags[i]:i==3?!sdk.poi3d:!sdk.landmark,"real SDK endpoint receives each independent mask "+i);
            cfg(values,TmapPoi.keys[i],true);applySdk.invoke(TmapPoi.INSTANCE,sdk);
            check(i<3?sdk.flags[i]==sdkRequests[i]:i==3?sdk.poi3d:sdk.landmark,"SDK restores original native request "+i);
        }
        check(sdk.flags[3],"3D POI never rewrites the unrelated MapAnnotationPoi3D flag");
        // A false argument forwarded by the facade must retain the original true native
        // request, otherwise re-enable would incorrectly restore false at the SDK layer.
        Field forwarding=TmapPoi.class.getDeclaredField("forwarding");forwarding.setAccessible(true);
        ThreadLocal forwarded=(ThreadLocal)forwarding.get(null);
        Constructor requestCtor=Class.forName("io.github.ldxm666.tmapclean.TmapPoi$Request").getDeclaredConstructor(int.class,boolean.class);requestCtor.setAccessible(true);
        Method requestSdk=TmapPoi.class.getDeclaredMethod("sdkRequest",XposedInterface.Chain.class,Object.class,int.class,boolean.class,int.class);requestSdk.setAccessible(true);
        cfg(values,Spec.K_POI_3D,false);forwarded.set(requestCtor.newInstance(3,true));
        NativeChain chain=new NativeChain(sdk,false);
        requestSdk.invoke(TmapPoi.INSTANCE,chain,sdk,3,false,0);forwarded.remove();
        check(Boolean.TRUE.equals(sdkRequests[3])&&chain.calls==1,"nested mask retains native intent and calls original exactly once");
        cfg(values,Spec.K_POI_3D,true);applySdk.invoke(TmapPoi.INSTANCE,sdk);
        check(sdk.poi3d,"re-enabling a nested SDK mask restores true correctly");

        NativeMap unknownMap=new NativeMap();
        for(String key:TmapPoi.keys)cfg(values,key,false);
        refreshMap.invoke(TmapPoi.INSTANCE,unknownMap);
        check(Arrays.stream(unknownMap.calls).sum()==0,"unobserved facade cannot poison SDK baseline with masked false");
        NativeController unknownSdk=new NativeController();
        applySdk.invoke(TmapPoi.INSTANCE,unknownSdk);
        check(!unknownSdk.flags[0]&&!unknownSdk.flags[2],"SDK getter snapshots original values before first mask");
        check(unknownSdk.poi3d&&unknownSdk.landmark,"3D features without getter/request are not irreversibly masked");
        for(String key:TmapPoi.keys)cfg(values,key,true);
        applySdk.invoke(TmapPoi.INSTANCE,unknownSdk);
        check(unknownSdk.flags[0]&&!unknownSdk.flags[1]&&unknownSdk.flags[2],"first SDK mask restores true AND native false correctly");

        // Ordinary base annotations are NOT the rich/3D feature switches. Only the
        // home renderer's bit 8 may change; roads/terrain and native search policy survive.
        Method annotationUpdate=TmapAnnotations.class.getDeclaredMethod("update",Object.class,boolean.class);
        annotationUpdate.setAccessible(true);
        Method annotationRequest=TmapAnnotations.class.getDeclaredMethod("request",XposedInterface.Chain.class,Object.class,int.class,boolean.class);
        annotationRequest.setAccessible(true);
        NativeRender render=new NativeRender();
        check(Spec.defaultVisible(Spec.K_MAP_ANNOTATIONS),"base labels default to native visibility");
        annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,true);
        check(render.calls==0,"default-on never forces a native label write");
        for(int i=0;i<20;i++){
            cfg(values,Spec.K_MAP_ANNOTATIONS,false);annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,true);
            check(!render.labels&&render.roads,"hide annotations without deleting the actual road layer");
            int before=render.calls;annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,true);
            check(render.calls==before,"steady home refresh does not repeat native writes");
            RenderChain all=new RenderChain(render,65535,true);
            annotationRequest.invoke(TmapAnnotations.INSTANCE,all,render,65535,true);
            check(all.calls==1&&!render.labels&&render.roads,"ALL preserves original call and masks ONLY annotations");
            annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,false);
            check(render.labels&&render.roads,"leaving home restores labels for search/navigation");
            annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,true);
            RenderChain off=new RenderChain(render,8,false);
            annotationRequest.invoke(TmapAnnotations.INSTANCE,off,render,8,false);
            cfg(values,Spec.K_MAP_ANNOTATIONS,true);annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,true);
            check(!render.labels,"native false request is never replaced with an invented true");
            RenderChain on=new RenderChain(render,8,true);
            annotationRequest.invoke(TmapAnnotations.INSTANCE,on,render,8,true);
            check(render.labels,"new native true request passes normally after re-enable");
        }
        cfg(values,Spec.K_MAP_ANNOTATIONS,false);annotationUpdate.invoke(TmapAnnotations.INSTANCE,render,false);
        check(render.labels,"disabled home setting cannot hide labels on a non-home renderer");
        System.out.println("PASS TmapPanelLayoutTest checks="+checks);
    }
    public static final class NativeRender {
        boolean labels=true,roads=true;int calls;
        public void setContentVisible(int type,boolean value){calls++;if((type&8)!=0)labels=value;if((type&1)!=0)roads=value;}
    }
    public static final class RenderChain implements XposedInterface.Chain {
        final NativeRender target;final Object[] args;int calls;
        RenderChain(NativeRender r,int type,boolean value){target=r;args=new Object[]{type,value};}
        public Executable getExecutable(){return null;}
        public Object getThisObject(){return target;}
        public List<Object> getArgs(){return Arrays.asList(args);}
        public Object getArg(int i){return args[i];}
        public Object proceed(){calls++;target.setContentVisible((Integer)args[0],(Boolean)args[1]);return null;}
        public Object proceed(Object[] a){throw new AssertionError("original mask must be preserved");}
        public Object proceedWith(Object target){return proceed();}
        public Object proceedWith(Object target,Object[] a){return proceed(a);}
    }
    public static final class NativeControls {
        final View toolbar,locate; boolean home=true;
        View zoom;
        NativeControls(Context c){toolbar=new View(c);locate=new View(c);}
        public View getToolBar(){return toolbar;}
        public View getLocateBtn(){return locate;}
        public View getZoomView(){return zoom;}
        public boolean isHomePage(){return home;}
    }
}
