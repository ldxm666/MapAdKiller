package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Reproduces disabled parent + enabled Home, native rebinds, and off/on recovery. */
public final class BaiduCommuteTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++; if (!value) throw new AssertionError(name);
    }
    private static void setStatic(String name, Object value) throws Exception {
        Field f=Cfg.class.getDeclaredField(name); f.setAccessible(true); f.set(null,value);
    }
    public static final class Binding {
        public final FrameLayout root, settingContainer, containerBesidesTitle;
        Binding(Context c) {
            root=new FrameLayout(c); settingContainer=new FrameLayout(c);
            containerBesidesTitle=new FrameLayout(c);
            root.addView(containerBesidesTitle,new FrameLayout.LayoutParams(400,80));
            root.addView(settingContainer,new FrameLayout.LayoutParams(72,80));
        }
        public View getRoot() { return root; }
    }
    public static final class NativeCommute {
        public final Binding mBinding;
        public final View homeInfo, companyInfo, verticalLine;
        public final TextView setupPrompt;
        public int settingContainerWidth=72, binds;
        public NativeCommute(Context c) {
            mBinding=new Binding(c); homeInfo=new FrameLayout(c);
            companyInfo=new FrameLayout(c); verticalLine=new View(c);
            setupPrompt=new TextView(c); setupPrompt.setText("去设置");
            ((FrameLayout)homeInfo).addView(setupPrompt);
            mBinding.containerBesidesTitle.addView(homeInfo);
            mBinding.containerBesidesTitle.addView(companyInfo);
            mBinding.containerBesidesTitle.addView(verticalLine);
        }
        public void initBg() { settingContainerWidth=96; mBinding.settingContainer.setVisibility(View.VISIBLE); }
        public void selfUpdate() { binds++; homeInfo.setVisibility(View.VISIBLE); companyInfo.setVisibility(View.VISIBLE); }
        public void onSlide(float fraction) {
            ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)mBinding.containerBesidesTitle.getLayoutParams();
            p.rightMargin=(int)(settingContainerWidth*fraction);
            mBinding.containerBesidesTitle.setLayoutParams(p);
        }
    }
    public static void main(String[] args) throws Exception {
        if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Class<?> cls=Class.forName("android.app.ActivityThread");
        Object thread=cls.getMethod("systemMain").invoke(null);
        Context c=(Context)cls.getMethod("getSystemContext").invoke(thread);
        Map<String,Object> data=new HashMap<>(); int[] commits={0};
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(proxy,method,values)->{
                String name=method.getName();
                if(name.equals("getAll"))return new HashMap<>(data);
                if(name.equals("contains"))return data.containsKey(values[0]);
                if(name.startsWith("get"))return data.containsKey(values[0])?data.get(values[0]):values[1];
                if(!name.equals("edit"))return null;
                Map<String,Object> pending=new HashMap<>();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class},(editor,edit,args2)->{
                        if(edit.getName().startsWith("put")){pending.put((String)args2[0],args2[1]);return editor;}
                        if(edit.getName().equals("commit")){data.putAll(pending);commits[0]++;return true;}
                        return editor;
                    });
            });
        setStatic("localCache",prefs); setStatic("embState",1);
        data.put(Spec.K_HC,false);data.put(Spec.K_HC_HOME,false);
        data.put(Spec.K_HC_COMPANY,false);data.put(Spec.K_HC_SETTING,false);Cfg.invalidate();
        NativeCommute nativeView=new NativeCommute(c);
        FrameLayout wrapper=new FrameLayout(c);wrapper.setId(Anchors.NUM_HC_WRAP);wrapper.addView(nativeView.mBinding.root);
        int[] homeClicks={0}; nativeView.homeInfo.setOnClickListener(v->homeClicks[0]++);
        BaiduCommute.apply(nativeView);
        check(wrapper.getVisibility()==View.GONE && nativeView.mBinding.root.getVisibility()==View.GONE,"disabled card has no visible wrapper");
        check(nativeView.settingContainerWidth==0,"hidden address editor reserves no width");
        int before=commits[0];check(App.write(Spec.K_HC_HOME,true),"Home writes through actual embedded settings path");
        check(commits[0]==before+1,"Home and parent are committed atomically");
        check(Boolean.TRUE.equals(data.get(Spec.K_HC)),"Home enable also enables its parent");
        check(Spec.effectiveVisible(Spec.K_HC_HOME,Spec.HOOK_READ),"effective Home visibility agrees with the switch");
        BaiduCommute.refresh();
        check(wrapper.getVisibility()==View.VISIBLE && nativeView.mBinding.root.getVisibility()==View.VISIBLE,"enabling restores both native wrappers");
        check(nativeView.homeInfo.getVisibility()==View.VISIBLE && nativeView.companyInfo.getVisibility()==View.GONE,"Home and Company use independent switches");
        check(nativeView.setupPrompt.getVisibility()==View.VISIBLE,"Home setup prompt survives a disabled address-management button");
        check(nativeView.binds>0,"refresh reuses native address binding");
        for(int i=0;i<8;i++) {
            check(App.write(Spec.K_HC_HOME,false),"Home disable commits");BaiduCommute.refresh();
            check(nativeView.homeInfo.getVisibility()==View.GONE && wrapper.getVisibility()==View.GONE,"last entry hidden collapses the native wrapper");
            check(App.write(Spec.K_HC_HOME,true),"Home re-enable commits");BaiduCommute.refresh();
            check(nativeView.homeInfo.getVisibility()==View.VISIBLE && wrapper.getVisibility()==View.VISIBLE,"Home restores without restarting");
            nativeView.homeInfo.performClick();
        }
        check(homeClicks[0]==8,"native Home click listener survives every toggle and bind");
        App.write(Spec.K_HC_SETTING,true);BaiduCommute.refresh();
        check(nativeView.settingContainerWidth==96,"re-enabled editor uses native theme width");
        check(nativeView.mBinding.settingContainer.getVisibility()==View.VISIBLE,"native address-management entry restored");
        App.write(Spec.K_HC,false);BaiduCommute.refresh();
        check(wrapper.getVisibility()==View.GONE,"parent can explicitly hide all children");
        check(Boolean.TRUE.equals(data.get(Spec.K_HC_HOME)),"disabling parent retains child choices");
        App.write(Spec.K_HC_COMPANY,true);BaiduCommute.refresh();
        check(wrapper.getVisibility()==View.VISIBLE && nativeView.verticalLine.getVisibility()==View.VISIBLE,"Company enable restores parent and native divider");
        System.out.println("BaiduCommuteTest: "+checks+" assertions passed");
    }
}
