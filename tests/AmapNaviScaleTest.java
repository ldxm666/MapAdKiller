package io.github.ldxm666.mapadkiller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import io.github.ldxm666.mapclean.AmapPanel;
import io.github.ldxm666.mapclean.PanelTypes;
import java.lang.reflect.*;
import java.util.*;

/** Landscape bounds, real Android transforms, identity restoration and persistent configuration. */
public final class AmapNaviScaleTest {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void near(float value, float wanted, String label) { check(Math.abs(value-wanted)<.0001f,label+": "+value); }
    public static final class Tree {
        public final View bar;
        public View cross,obstacle;
        Tree(View bar) { this.bar=bar; }
        public View g(String key, String value) {
            if("class".equals(key)&&"trip-navi-header-cross".equals(value))return cross;
            if("id".equals(key)&&"carNaviFooterTipsWrap".equals(value))return obstacle;
            return "id".equals(key)&&"idTripNaviHeaderContainer".equals(value)?bar:null;
        }
    }
    public static final class Shown extends View {
        int x,y;
        Shown(Context c,int x,int y){super(c);this.x=x;this.y=y;layout(0,0,450,70);}
        @Override public boolean isShown(){return true;}
        @Override public void getLocationInWindow(int[] point){point[0]=x;point[1]=y;}
    }
    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Context context=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        check(AmapNaviScale.clampPercent(Integer.MIN_VALUE)==100,"low corrupt preference bounded");
        check(AmapNaviScale.clampPercent(Integer.MAX_VALUE)==200,"high corrupt preference bounded");
        for(int percent=100;percent<=200;percent+=10) {
            near(AmapNaviScale.fitScale(percent,1920,1080,20,24,600,134),percent/100f,"requested landscape scale");
            near(AmapNaviScale.fitScale(percent,1080,1920,20,24,1040,134),1f,"portrait unchanged");
            near(AmapNaviScale.fitScale(percent,1080,1080,20,24,1040,134),1f,"square native layout unchanged");
        }
        for(int vw:new int[]{800,1280,1920,2400}) for(int vh:new int[]{480,600,720})
            for(int width:new int[]{250,500,750}) for(int height:new int[]{134,260}) {
                float factor=AmapNaviScale.fitScale(200,vw,vh,20,24,width,height);
                check(factor>=1f&&factor<=2f,"scale bounded");
                if(width<=vw-40&&height<=vh-24) {
                    check(width*factor<=vw-40+.001f,"right edge contained");
                    check(height*factor<=vh-24+.001f,"bottom edge contained");
                }
            }
        near(AmapNaviScale.fitScale(200,1200,600,20,24,0,134),1f,"unmeasured card unchanged");
        near(AmapNaviScale.fitScale(200,1200,600,-1,24,300,134),1f,"unknown coordinates unchanged");
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getAll"))return new HashMap<>(values);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().startsWith("get"))return values.getOrDefault(a[0],a[1]);
            return null;
        });
        Field cached=Config.class.getDeclaredField("cached");cached.setAccessible(true);cached.set(null,prefs);
        Field backend=Config.class.getDeclaredField("backend");backend.setAccessible(true);backend.set(null,Config.BACKEND_LOCAL);
        check(Config.naviInfoPercent()==100,"missing key uses native size");
        values.put(Config.K_NAVI_INFO_PERCENT,160);Config.invalidate();
        check(Config.naviInfoPercent()==160,"stored integer read back");
        values.put(Config.K_NAVI_INFO_PERCENT,-5);Config.invalidate();
        check(Config.naviInfoPercent()==100,"invalid low integer clamped");
        values.put(Config.K_NAVI_INFO_PERCENT,999);Config.invalidate();
        check(Config.naviInfoPercent()==200,"invalid high integer clamped");
        View card=new View(context);card.layout(0,0,450,140);
        Class<?> original=Class.forName(AmapNaviScale.class.getName()+"$Original");
        Constructor<?> ctor=original.getDeclaredConstructor(View.class);ctor.setAccessible(true);
        Method apply=original.getDeclaredMethod("apply",float.class),restore=original.getDeclaredMethod("restore");
        apply.setAccessible(true);restore.setAccessible(true);
        Object saved=ctor.newInstance(card);
        restore.invoke(saved);
        check(!card.isPivotSet(),"default size does not write native pivot");
        apply.invoke(saved,1.6f);near(card.getScaleX(),1.6f,"whole card horizontal scale");
        near(card.getScaleY(),1.6f,"icons and text uniformly scaled");
        near(card.getPivotX(),0,"left edge anchored");near(card.getPivotY(),0,"top edge anchored");
        apply.invoke(saved,2f);near(card.getScaleX(),2,"repeated changes never compound");
        card.layout(0,0,500,180);restore.invoke(saved);
        near(card.getScaleX(),1,"native scale restored");check(!card.isPivotSet(),"automatic pivot restored on orientation change");
        near(card.getPivotX(),250,"pivot follows resized card");near(card.getPivotY(),90,"vertical pivot follows resized card");
        card.setScaleX(.9f);card.setScaleY(.8f);card.setPivotX(7);card.setPivotY(9);
        saved=ctor.newInstance(card);apply.invoke(saved,1.5f);restore.invoke(saved);
        near(card.getScaleX(),.9f,"native nonunit X preserved");near(card.getScaleY(),.8f,"native nonunit Y preserved");
        near(card.getPivotX(),7,"explicit native pivot X preserved");near(card.getPivotY(),9,"explicit native pivot Y preserved");
        FrameLayout root=new FrameLayout(context);root.layout(0,0,1200,600);
        FrameLayout parent=new FrameLayout(context);root.addView(parent);parent.layout(0,0,500,200);
        View header=new View(context);parent.addView(header);header.layout(0,0,450,140);
        parent.setClipChildren(true);parent.setClipToPadding(false);root.setClipChildren(false);root.setClipToPadding(true);
        Class<?> host=Class.forName(AmapNaviScale.class.getName()+"$Host");
        Constructor<?> hc=host.getDeclaredConstructor(View.class,Object.class);hc.setAccessible(true);
        Tree tree=new Tree(header);Object instance=hc.newInstance(root,tree);
        Method update=host.getDeclaredMethod("apply");update.setAccessible(true);
        values.put(Config.K_NAVI_INFO_PERCENT,160);Config.invalidate();update.invoke(instance);
        near(header.getScaleX(),1.6f,"host locates only the exact DOM card");
        check(!parent.getClipChildren()&&!root.getClipToPadding(),"card can draw outside its original wrapper");
        values.put(Config.K_NAVI_INFO_PERCENT,100);Config.invalidate();update.invoke(instance);
        near(header.getScaleX(),1,"host restores size immediately");
        check(parent.getClipChildren()&&!parent.getClipToPadding()&&!root.getClipChildren()&&root.getClipToPadding(),"each ancestor clipping flag restored exactly");
        values.put(Config.K_NAVI_INFO_PERCENT,200);Config.invalidate();
        tree.obstacle=new Shown(context,0,240);update.invoke(instance);
        near(header.getScaleY(),240f/140f,"visible native footer limits enlargement before overlap");
        tree.cross=new Shown(context,0,140);update.invoke(instance);
        near(header.getScaleX(),1,"native road-junction graphics preserve their own geometry");
        tree.cross=null;tree.obstacle=null;update.invoke(instance);
        near(header.getScaleX(),2,"selected size resumes after junction image closes");
        int metrics=0;
        for(PanelTypes.Group group:AmapPanel.groups()) if(group.metrics!=null) for(PanelTypes.Metric metric:group.metrics)
            if(Config.K_NAVI_INFO_PERCENT.equals(metric.key)) {
                metrics++;
                check(metric.def==100,"visible setting defaults to native");
                check(Arrays.equals(metric.values,new int[]{100,110,120,130,140,150,160,170,180,190,200}),"slider exposes all supported sizes");
            }
        check(metrics==1,"one visible navigation size setting");
        System.out.println("PASS "+checks+" navigation scale assertions");
    }
}
