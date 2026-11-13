package io.github.ldxm666.mapclean.tests;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Render production themes, exercise real move/drag/save paths, then restore tool order. */
public final class OrderUiRegression extends Instrumentation {
    Activity activity; int checks;
    interface Work {void run() throws Exception;}
    void main(Work work)throws Exception{
        Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    void walk(View v,List<View> all){all.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)walk(((ViewGroup)v).getChildAt(i),all);}
    View text(View root,String label){List<View> all=new ArrayList<>();walk(root,all);for(View v:all)if(v instanceof TextView&&((TextView)v).getText().toString().equals(label))return v;return null;}
    void capture(Dialog dialog,String name)throws Exception{
        Bitmap[] bmp={null};int[] result={-1};CountDownLatch ready=new CountDownLatch(1);
        main(()->{View decor=dialog.getWindow().getDecorView();bmp[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
            PixelCopy.request(dialog.getWindow(),bmp[0],code->{result[0]=code;ready.countDown();},new Handler(Looper.getMainLooper()));});
        if(!ready.await(5,TimeUnit.SECONDS)||result[0]!=PixelCopy.SUCCESS)throw new AssertionError("dialog capture "+result[0]);
        File folder=new File(getTargetContext().getCacheDir(),"ui-qa");folder.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){bmp[0].compress(Bitmap.CompressFormat.PNG,100,out);}bmp[0].recycle();
    }
    Class<?> panel()throws Exception{return activity.getClassLoader().loadClass("io.github.ldxm666.mapclean.AmapPanel");}
    String savedOrder()throws Exception{return (String)((Map<?,?>)panel().getMethod("toolOrderValues").invoke(null)).get("ui_tools_order");}
    List<String> ids(String raw)throws Exception{List<String> ids=new ArrayList<>();org.json.JSONArray a=new org.json.JSONArray(raw==null?"[]":raw);for(int i=0;i<a.length();i++)ids.add(a.getString(i));return ids;}
    Dialog show()throws Exception{return (Dialog)activity.getClassLoader().loadClass("io.github.ldxm666.mapadkiller.AmapToolOrderEditor").getMethod("show",Context.class).invoke(null,activity);}
    List<View> moves(Dialog dialog,String prefix){List<View> all=new ArrayList<>(),result=new ArrayList<>();walk(dialog.getWindow().getDecorView(),all);for(View v:all)if(String.valueOf(v.getContentDescription()).startsWith(prefix))result.add(v);return result;}
    // Native startDragAndDrop transfers input to a system-owned drag window on Android 16.
    // UiAutomation can continue that gesture; targeted sendPointerSync rejects its UID.
    void pointer(long down,long when,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,when,action,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);boolean sent=getUiAutomation().injectInputEvent(event,true);event.recycle();if(!sent)throw new AssertionError("native drag input rejected");}
    void exerciseMoves()throws Exception{
        String original=savedOrder();Dialog[] dialog={null};
        try{
            main(()->dialog[0]=show());SystemClock.sleep(500);waitForIdleSync();
            List<String> names=new ArrayList<>();main(()->{for(View v:moves(dialog[0],"下移 "))names.add(v.getContentDescription().toString());});
            check(names.size()>=2,"at least two configured tools for interaction test");
            main(()->moves(dialog[0],"下移 ").get(0).performClick());waitForIdleSync();
            main(()->check(moves(dialog[0],"下移 ").get(0).getContentDescription().toString().equals(names.get(1)),"down button moves the complete production row"));
            List<String> moved=ids(savedOrder());check(moved.size()>=names.size(),"move persists stable IDs through real settings service");
            main(()->moves(dialog[0],"上移 ").get(1).performClick());waitForIdleSync();
            main(()->check(moves(dialog[0],"下移 ").get(0).getContentDescription().toString().equals(names.get(0)),"up button restores the row"));
            // HyperOS can leave an instrumentation-owned Activity behind the current task.
            // Bring this exact task forward and verify focus before injecting touch input.
            ParcelFileDescriptor foreground=getUiAutomation().executeShellCommand("am start --activity-single-top --activity-reorder-to-front -n io.github.ldxm666.mapadkiller/io.github.ldxm666.mapclean.MainActivity");
            try(FileInputStream input=new FileInputStream(foreground.getFileDescriptor())){byte[] bytes=new byte[1024];while(input.read(bytes)!=-1){}}finally{foreground.close();}
            boolean[] focus={false};for(int i=0;i<40&&!focus[0];i++){main(()->focus[0]=dialog[0].getWindow().getDecorView().hasWindowFocus());if(!focus[0])SystemClock.sleep(100);}
            check(focus[0],"production dialog owns foreground input before drag");
            SystemClock.sleep(400);waitForIdleSync();
            float[] xy=new float[4];main(()->{
                List<View> controls=moves(dialog[0],"下移 ");View first=(View)controls.get(0).getParent(),last=(View)controls.get(controls.size()-1).getParent();
                int[] a=new int[2],b=new int[2];first.getLocationOnScreen(a);last.getLocationOnScreen(b);
                xy[0]=a[0]+first.getWidth()*.35f;xy[1]=a[1]+first.getHeight()*.5f;xy[2]=b[0]+last.getWidth()*.35f;xy[3]=b[1]+last.getHeight()*.5f;
            });
            long down=SystemClock.uptimeMillis();pointer(down,down,0,xy[0],xy[1]);SystemClock.sleep(Math.max(850,ViewConfiguration.getLongPressTimeout()+250));
            capture(dialog[0],"order-drag-start");
            for(int i=1;i<=16;i++){float t=i/16f;pointer(down,SystemClock.uptimeMillis(),2,xy[0]+(xy[2]-xy[0])*t,xy[1]+(xy[3]-xy[1])*t);SystemClock.sleep(35);}
            pointer(down,SystemClock.uptimeMillis(),1,xy[2],xy[3]);SystemClock.sleep(700);waitForIdleSync();
            capture(dialog[0],"order-drag-end");
            main(()->check(moves(dialog[0],"下移 ").get(0).getContentDescription().toString().equals(names.get(1)),"long press drag changes row order"));
            List<String> dragged=ids(savedOrder());main(()->dialog[0].dismiss());SystemClock.sleep(350);
            main(()->dialog[0]=show());SystemClock.sleep(500);waitForIdleSync();
            check(ids(savedOrder()).equals(dragged),"saved drag order survives editor reopen");
            main(()->check(moves(dialog[0],"下移 ").get(0).getContentDescription().toString().equals(names.get(1)),"reopened editor renders saved order"));
        }finally{
            main(()->{if(dialog[0]!=null)dialog[0].dismiss();});
            check(Boolean.TRUE.equals(panel().getMethod("writeToolOrder",List.class).invoke(null,ids(original))),"original order restored");
            check(ids(savedOrder()).equals(ids(original)),"restored order read back");
        }
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle output=new Bundle();
        try{
            Intent launch=new Intent().setClassName("io.github.ldxm666.mapadkiller","io.github.ldxm666.mapclean.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch);waitForIdleSync();
            for(boolean dark:new boolean[]{false,true}){
                Dialog[] dialog={null};
                main(()->{
                    Configuration cfg=new Configuration(activity.getResources().getConfiguration());
                    cfg.uiMode=(cfg.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
                    ContextThemeWrapper themed=new ContextThemeWrapper(activity,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
                    themed.applyOverrideConfiguration(cfg);
                    dialog[0]=(Dialog)activity.getClassLoader().loadClass("io.github.ldxm666.mapadkiller.AmapToolOrderEditor").getMethod("show",Context.class).invoke(null,themed);
                });
                SystemClock.sleep(500);waitForIdleSync();
                main(()->{
                    View decor=dialog[0].getWindow().getDecorView();
                    TextView title=(TextView)text(decor,"首页工具排序");
                    check(title!=null,"production title present");
                    Class<?> design=activity.getClassLoader().loadClass("io.github.ldxm666.mapclean.HyperDesign");
                    Object palette=design.getMethod("of",boolean.class).invoke(null,dark);
                    check(title.getCurrentTextColor()==design.getField("onSurface").getInt(palette),"title follows shared day/night palette");
                    check(decor.getWidth()<=activity.getResources().getDisplayMetrics().widthPixels,"dialog fits screen width");
                    check(decor.getHeight()<activity.getResources().getDisplayMetrics().heightPixels*.88f,"dialog fits screen height");
                    check(text(decor,"完成")!=null&&text(decor,"恢复默认")!=null,"shared actions present");
                    List<View> all=new ArrayList<>();walk(decor,all);List<View> up=new ArrayList<>(),down=new ArrayList<>();
                    for(View v:all){String label=String.valueOf(v.getContentDescription());if(label.startsWith("上移 "))up.add(v);if(label.startsWith("下移 "))down.add(v);}
                    check(up.size()==down.size(),"matching accessible move controls");
                    if(!up.isEmpty()){
                        check(!up.get(0).isEnabled()&&!down.get(down.size()-1).isEnabled(),"unavailable boundary moves disabled");
                        check(up.get(0).getHeight()>=44*activity.getResources().getDisplayMetrics().density,"move target at least 44dp");
                        if(up.size()<=5)check(decor.getHeight()<activity.getResources().getDisplayMetrics().heightPixels*.75f,"short lists have no fixed empty viewport");
                    }
                });
                capture(dialog[0],dark?"order-dark":"order-light");
                main(()->text(dialog[0].getWindow().getDecorView(),"完成").performClick());
                SystemClock.sleep(500);waitForIdleSync();
                main(()->check(!dialog[0].isShowing(),"done dismisses through shared dialog path"));
            }
            exerciseMoves();
            output.putString("stream","OK checks="+checks+"; production order light/dark, buttons, drag, save/reopen; original order restored\n");finish(Activity.RESULT_OK,output);
        }catch(Throwable error){output.putString("stream","FAIL "+error+"\n");finish(Activity.RESULT_CANCELED,output);}
    }
}
