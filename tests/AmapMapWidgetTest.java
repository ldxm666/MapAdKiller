package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** TEST-AMAP-MAP-PROMO-236: all native visibility entry points stay independent of cards. */
public final class AmapMapWidgetTest {
    static int checks;
    static final Map<String,Object> values=new HashMap<>();
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void set(String name,Object value)throws Exception{Field f=Config.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    static void flag(boolean on)throws Exception{values.put(Config.K_MAP_PROMO,on);set("expiresAt",0L);}
    static void clear()throws Exception{Field f=AmapMapWidget.class.getDeclaredField("widgets");f.setAccessible(true);((Map<?,?>)f.get(null)).clear();}
    public static final class Native {
        int visibility=0,calls;boolean animate;
        public void setVisibility(int value){calls++;visibility=value;}
        public void setVisibility(int value,boolean animated){calls++;visibility=value;animate=animated;}
        public void setContentViewVisibility(int value){calls++;visibility=value;}
    }
    static final class Chain implements XposedInterface.Chain {
        final Object self;final Method method;final Object[] args;int calls;Object[] passed;boolean fail;
        XposedInterface.Hooker nested;Native nestedSelf;
        Chain(Object self,Method method,Object... args){this.self=self;this.method=method;this.args=args;}
        public Executable getExecutable(){return method;}public Object getThisObject(){return self;}
        public List<Object> getArgs(){return Arrays.asList(args);}public Object getArg(int i){return args[i];}
        public Object proceed(){return proceed(args);}
        public Object proceed(Object[] changed){
            calls++;passed=changed.clone();if(fail)throw new IllegalStateException("native");
            if(nested!=null){
                try{return nested.intercept(new Chain(nestedSelf==null?self:nestedSelf,methods[0],changed[0]));}
                catch(Throwable t){throw new AssertionError(t);}
            }
            try{return method.invoke(self,changed);}catch(Exception e){throw new AssertionError(e);}
        }
        public Object proceedWith(Object self){throw new AssertionError("receiver changed");}
        public Object proceedWith(Object self,Object[] args){throw new AssertionError("receiver changed");}
    }
    static Method[] methods;
    public static final class Property {
        final String type;Property(String type){this.type=type;}
        public String getWidgetType(){return type;}
    }
    public static final class Template {
        final Property property;final View view;
        Template(Context c,String type,int visibility){property=new Property(type);view=new View(c);view.setVisibility(visibility);}
        public Property getWidgetProperty(){return property;}
        public View getContentView(){return view;}
    }
    public static final class Container {
        int calls;
        public int addWidget(Template widget,int position){calls++;return position;}
        public void setWidgetVisible(Template widget,int visibility){calls++;if(widget!=null)widget.view.setVisibility(visibility);}
    }
    static void run(XposedInterface.Hooker hook,Chain chain){try{hook.intercept(chain);}catch(Throwable t){throw new AssertionError(t);}}
    public static void main(String[] args)throws Exception{
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);return null;
        });
        set("cached",prefs);set("backend",Config.BACKEND_LOCAL);
        methods=new Method[]{Native.class.getMethod("setVisibility",int.class),Native.class.getMethod("setVisibility",int.class,boolean.class),Native.class.getMethod("setContentViewVisibility",int.class)};
        check(!Config.defaultVisible(Config.K_MAP_PROMO),"map promotion defaults hidden");
        String[] cards={Config.K_FEED_WEATHER,Config.K_FEED_BOARD,Config.K_FEED_CONTENT,Config.K_FEED_FESTIVAL,Config.K_TAXI_CARD};
        for(int bits=0;bits<32;bits++)for(boolean allowed:new boolean[]{false,true}){
            for(int i=0;i<cards.length;i++)values.put(cards[i],(bits&(1<<i))!=0);
            flag(allowed);
            for(Method method:methods)for(int requested:new int[]{0,4,8}){
                clear();Native widget=new Native();Object[] nativeArgs=method.getParameterTypes().length==2?new Object[]{requested,true}:new Object[]{requested};
                Chain c=new Chain(widget,method,nativeArgs);run(AmapMapWidget.visibilityHook(method),c);
                check(c.calls==1&&widget.calls==1,"native setter called once");
                check(widget.visibility==(allowed?requested:8),"only map promotion setting gates all native entries bits="+bits);
                check(((Integer)c.args[0])==requested,"original hook arguments preserved");
                if(nativeArgs.length==2)check(widget.animate&&Boolean.TRUE.equals(c.passed[1]),"native animation argument preserved");
            }
            Chain start=new Chain(new Native(),methods[0],0);
            Object result;try{result=AmapMapWidget.startHook().intercept(start);}catch(Throwable t){throw new AssertionError(t);}
            check(allowed?start.calls==1:start.calls==0&&Boolean.FALSE.equals(result),"presenter is independent of every content card switch");
        }
        clear();Native widget=new Native();flag(false);
        Chain outer=new Chain(widget,methods[1],0,true);outer.nested=AmapMapWidget.visibilityHook(methods[0]);
        run(AmapMapWidget.visibilityHook(methods[1]),outer);check(widget.visibility==8,"delegating setter remains hidden");
        int before=widget.calls;AmapMapWidget.refreshWidgets();check(widget.calls==before,"unchanged setting does not replay");
        flag(true);AmapMapWidget.refreshWidgets();check(widget.visibility==0&&widget.animate&&widget.calls==before+1,"live restore uses original outer request, not nested forced GONE");
        flag(false);AmapMapWidget.refreshWidgets();check(widget.visibility==8,"live disable hides existing widget");
        run(AmapMapWidget.visibilityHook(methods[2]),new Chain(widget,methods[2],4));
        flag(true);AmapMapWidget.refreshWidgets();check(widget.visibility==4,"host-hidden widget never revived as visible");
        clear();Native second=new Native();flag(false);
        Chain cross=new Chain(widget,methods[1],0,false);cross.nested=AmapMapWidget.visibilityHook(methods[0]);cross.nestedSelf=second;
        run(AmapMapWidget.visibilityHook(methods[1]),cross);flag(true);AmapMapWidget.refreshWidgets();
        check(widget.visibility==0&&second.visibility==8,"nested unrelated receiver tracks its actual native request");
        clear();flag(false);Chain broken=new Chain(widget,methods[0],0);broken.fail=true;boolean threw=false;
        try{AmapMapWidget.visibilityHook(methods[0]).intercept(broken);}catch(Throwable t){threw=true;}
        check(threw&&broken.calls==1,"native failure is propagated without retry");
        Field replay=AmapMapWidget.class.getDeclaredField("replay");replay.setAccessible(true);
        check(((ThreadLocal<?>)replay.get(null)).get()==null,"receiver guard cleared after exception");
        clear();
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context context=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Method property=Template.class.getMethod("getWidgetProperty"),type=Property.class.getMethod("getWidgetType"),view=Template.class.getMethod("getContentView");
        Method add=Container.class.getMethod("addWidget",Template.class,int.class),show=Container.class.getMethod("setWidgetVisible",Template.class,int.class);
        XposedInterface.Hooker addHook=AmapMapWidget.templateAddHook(property,type,view),showHook=AmapMapWidget.templateVisibilityHook(property,type,view);
        for(int bits=0;bits<32;bits++)for(boolean allowed:new boolean[]{false,true})
            for(String business:new String[]{"template_rank","template_mainmapRightTop","template_mainmapRightBottom","unknown"})
                for(int requested:new int[]{0,4,8}){
                    clear();for(int i=0;i<cards.length;i++)values.put(cards[i],(bits&(1<<i))!=0);flag(allowed);
                    Template template=new Template(context,business,requested);Container container=new Container();
                    Chain attach=new Chain(container,add,template,7);Object returned;
                    try{returned=addHook.intercept(attach);}catch(Throwable t){throw new AssertionError(t);}
                    boolean rank=business.equals("template_rank");
                    check(Integer.valueOf(7).equals(returned)&&attach.calls==1&&container.calls==1,"attachment preserves native result and side effects");
                    check(template.view.getVisibility()==(rank&&!allowed?8:requested),"initial attachment gates only exact rank business type");
                    run(addHook,new Chain(container,add,template,7));
                    flag(true);AmapMapWidget.refreshWidgets();
                    check(template.view.getVisibility()==requested,"re-attachment does not cache forced GONE as native state");
                    flag(allowed);AmapMapWidget.refreshWidgets();
                    Chain visibility=new Chain(container,show,template,requested);run(showHook,visibility);
                    check(visibility.calls==1&&container.calls==3,"native container visibility runs once");
                    check(((Integer)visibility.args[1])==requested&&visibility.args[0]==template,"native arguments and widget preserved");
                    check(template.view.getVisibility()==(rank&&!allowed?8:requested),"future native visibility is gated only for rank");
                    flag(!allowed);AmapMapWidget.refreshWidgets();
                    check(template.view.getVisibility()==(rank&&allowed?8:requested),"live switch affects rank only, preserving native hidden state");
                    flag(true);AmapMapWidget.refreshWidgets();
                    check(template.view.getVisibility()==requested,"live restore respects VISIBLE INVISIBLE and GONE");
                }
        clear();flag(false);Template rank=new Template(context,"template_rank",0);Container container=new Container();
        run(addHook,new Chain(container,add,rank,0));
        run(showHook,new Chain(container,show,rank,4));flag(true);AmapMapWidget.refreshWidgets();
        check(rank.view.getVisibility()==4,"native hide while disabled survives enable");
        run(showHook,new Chain(container,show,rank,0));flag(false);AmapMapWidget.refreshWidgets();
        check(rank.view.getVisibility()==8,"already displayed rank hides immediately");
        run(showHook,new Chain(container,show,(Object)null,0));
        check(container.calls==4,"null widget uses original native path");
        clear();System.out.println("PASS AmapMapWidgetTest checks="+checks);
    }
}
