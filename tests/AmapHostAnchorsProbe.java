package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.view.View;
import dalvik.system.PathClassLoader;
import java.lang.reflect.*;
import org.json.JSONObject;

/** TEST-AMAP-PLAY-236: reflect the real unmodified host DEX on ART, without initialization. */
public final class AmapHostAnchorsProbe {
    private static int checks;
    private static void check(boolean value,String label) {
        checks++;if(!value)throw new AssertionError(label);System.out.println("OK "+label);
    }
    private static Class<?> type(ClassLoader cl,String name)throws Exception {return Class.forName(name,false,cl);}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("host base.apk path and cn/play required");
        boolean play=args[1].equals("play");
        ClassLoader cl=new PathClassLoader(args[0],AmapHostAnchorsProbe.class.getClassLoader());
        String requester=play?"ys5":"ah5",task=play?"wo5":"yc5",item=play?"fj6":"k66";
        check(AmapSplashKill.requesterShape(type(cl,requester)),"requester "+requester);
        check(AmapSplashKill.realtimeShape(type(cl,task)),"realtime "+task);
        if(play)check(!AmapSplashKill.requesterShape(type(cl,"ah5")),"unrelated ah5 rejected");
        Class<?> model=type(cl,item),listener=type(cl,"com.autonavi.minimap.listener.IEventListener");
        for(String name:new String[]{"ImageSplashView","VideoSplashView"}) {
            Method m=type(cl,"com.autonavi.minimap.view."+name).getDeclaredMethod("getSplashView",Context.class,model,listener);
            check(View.class.isAssignableFrom(m.getReturnType()),"renderer "+name+" / "+item);
        }
        Class<?> service=type(cl,"com.autonavi.minimap.impl.SplashScreenServiceImpl");
        check(service.getDeclaredMethod("fetchRealTime").getReturnType()==Void.TYPE,"realtime service");
        Method a=service.getDeclaredMethod("a");
        check(a.getReturnType()==Void.TYPE&&Modifier.isStatic(a.getModifiers()),"realtime static entry");
        check(AmapSplashRules.resolveGate(type(cl,play?"wm6":"y96"))!=null,"cached readiness result shape");
        Class<?> delegate=type(cl,"com.autonavi.bundle.amaphome.lite.quickservice.toolbox.IToolboxPanel$QsPanelStateDelegate");
        Method factory=type(cl,"com.autonavi.bundle.amaphome.lite.quickservice.toolbox.a").getDeclaredMethod("a",Context.class,String.class,Integer.TYPE,JSONObject.class,delegate);
        check(android.view.ViewGroup.class.isAssignableFrom(factory.getReturnType())&&Modifier.isStatic(factory.getModifiers()),"native toolbox factory");
        int loads=0;
        for(Method m:type(cl,"com.autonavi.bundle.amaphome.lite.NewQuickServiceView").getDeclaredMethods())
            if(m.getName().equals("loadAjxQs")&&View.class.isAssignableFrom(m.getReturnType()))loads++;
        check(loads==2,"both homepage canvas overloads");
        Class<?> qs=type(cl,"com.autonavi.bundle.amaphome.lite.NewQuickServiceView");
        check(qs.getDeclaredMethod("initToolBoxPanel",String.class,Integer.TYPE).getReturnType()==Void.TYPE,"native toolbox reattachment entry");
        check(View.class.isAssignableFrom(type(cl,"com.autonavi.bundle.amaphome.lite.quickservice.toolbox.ToolboxPanelV2")),"native compact toolbox is View-backed");
        for(String name:new String[]{"releaseAjxQs","removeQuickServiceListener"})check(qs.getDeclaredMethod(name).getReturnType()==Void.TYPE,"native canvas refresh "+name);
        // Validate LLM start(Callback):String through the original DEX. Reflecting
        // its full callback type graph in app_process exceeds the device linker budget.
        check(type(cl,"com.autonavi.core.network.inter.response.HttpResponse").getDeclaredMethod("getResponseBodyString").getReturnType()==String.class,"HTTP response body gate");
        check(type(cl,"com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage").getDeclaredMethod("getItemSync",String.class,String.class).getReturnType()==String.class,"cached card storage read");
        Class<?> module=type(cl,"com.autonavi.minimap.ajx3.modules.AbstractModule"),ajxContext=type(cl,"com.autonavi.minimap.ajx3.context.IAjxContext");
        check(module.getDeclaredMethod("getContext").getReturnType()==ajxContext,"storage page context");
        check(ajxContext.getDeclaredMethod("getJsPath").getReturnType()==String.class,"storage page identity");
        check(module.getDeclaredMethod("getNativeContext").getReturnType()==Context.class,"storage host catalogue context");
        Class<?> storage=type(cl,"com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage");
        check(storage.getDeclaredMethod("setItemInner",String.class,String.class,Object.class).getReturnType()==Boolean.TYPE,"home normalized cache write");
        check(storage.getDeclaredMethod("setItemSync",String.class,String.class,Object.class).getReturnType()==Boolean.TYPE,"cache synchronous write caller");
        check(storage.getDeclaredMethod("setItemASYNC",String.class,String.class,Object.class,type(cl,"com.autonavi.minimap.ajx3.core.JsFunctionCallback")).getReturnType()==Void.TYPE,"cache asynchronous write caller");
        Class<?> dom=type(cl,"com.autonavi.minimap.ajx3.dom.AjxDomTree");
        check(ajxContext.getDeclaredMethod("getDomTree").getReturnType()==dom,"home tool DOM access");
        check(dom.getDeclaredMethod("g",String.class,String.class).getReturnType()==View.class,"home tool business ID lookup");
        check(type(cl,"com.autonavi.minimap.ajx3.widget.view.Container").getMethod("setStyle",Integer.TYPE,Integer.TYPE,Object.class,Boolean.TYPE,Boolean.TYPE,Boolean.TYPE,Boolean.TYPE).getReturnType()==Void.TYPE,"native DOM style and layout synchronization");
        Class<?> domNode=type(cl,"com.autonavi.minimap.ajx3.widget.property.a").getDeclaredMethod("getNode").getReturnType();
        check(domNode.getDeclaredMethod("h",String.class).getReturnType()==Object.class,"tool node business attributes");
        check(java.util.List.class.isAssignableFrom(domNode.getDeclaredMethod("i").getReturnType()),"tool node children for exact More cell");
        check(domNode.isAssignableFrom(domNode.getDeclaredField("c").getType()),"tool node direct DOM parent");
        check(domNode.getDeclaredField("f").getType()==String.class&&domNode.getDeclaredField("u").getType()==View.class,"tool node tag and native view");
        check(type(cl,"com.autonavi.jni.ajx3.dom.KeyDefine").getDeclaredMethod("name2Type",String.class).getReturnType()==Integer.TYPE,"native display key lookup");
        Class<?> widget=type(cl,"com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityMapWidget");
        check(widget.getDeclaredMethod("setVisibility",Integer.TYPE).getReturnType()==Void.TYPE,"activity widget visibility");
        check(widget.getDeclaredMethod("setVisibility",Integer.TYPE,Boolean.TYPE).getReturnType()==Void.TYPE,"activity widget animated visibility");
        check(widget.getDeclaredMethod("setContentViewVisibility",Integer.TYPE).getReturnType()==Void.TYPE,"AJX activity widget visibility");
        Class<?> contract=type(cl,"com.autonavi.bundle.uitemplate.mapwidget.inter.IMapWidget");
        Class<?> property=type(cl,"com.autonavi.bundle.uitemplate.api.IWidgetProperty");
        check(contract.getDeclaredMethod("getWidgetProperty").getReturnType()==property,"template property contract");
        check(contract.getDeclaredMethod("getContentView").getReturnType()==View.class,"template native content view");
        check(property.getDeclaredMethod("getWidgetType").getReturnType()==String.class,"template business type");
        Class<?> container=type(cl,"com.autonavi.bundle.uitemplate.mapwidget.MapWidgetContainer");
        check(container.getDeclaredMethod("addWidget",contract,Integer.TYPE).getReturnType()==Integer.TYPE,"template initial attachment");
        check(container.getDeclaredMethod("setWidgetVisible",contract,Integer.TYPE).getReturnType()==Void.TYPE,"template native visibility");
        for(String owner:new String[]{"MapWidgetManagerImpl","MapWidgetManagerService"})
            check(type(cl,"com.autonavi.bundle.uitemplate.mapwidget.impl."+owner).getDeclaredMethod("setWidgetVisibleForType",String.class,Integer.TYPE).getReturnType()==Boolean.TYPE,"template visibility caller "+owner);
        check(type(cl,"com.autonavi.bundle.vui.llm.d").getDeclaredField("i").getType()==String.class,"current home stream scene field");
        check(type(cl,"com.amap.bundle.drive.radar.entrance.SpeedDetectManager$a").getDeclaredMethod("onLocationChanged",android.location.Location.class).getReturnType()==Void.TYPE,"automatic-only speed callback");
        for(String owner:new String[]{"com.amap.bundle.drive.ajx.module.ModuleRouteDriveResultImpl","com.amap.bundle.drive.result.driveresult.opt.page.AjxBaseResultPage"})
            check(type(cl,owner).getDeclaredMethod("setAutoEnterDriveRadar",Boolean.TYPE).getReturnType()==Void.TYPE,"official cruise "+owner);
        System.out.println("PASS "+args[1]+" host anchors: "+checks);
    }
}
