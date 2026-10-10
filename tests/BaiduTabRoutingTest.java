package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Android's actual LinearLayout measurement and touch dispatch, without a UI. */
public final class BaiduTabRoutingTest {
    private static int assertions;
    private static void check(boolean value,String name){
        assertions++;if(!value)throw new AssertionError(name);
    }
    // Unattached test views have no window Handler. Run their posted click task
    // immediately; Android still selects the child through its real hit test.
    private static final class Proxy extends View {
        Proxy(Context context){super(context);}
        @Override public boolean post(Runnable action){action.run();return true;}
    }
    private static void measure(LinearLayout row){
        row.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
        row.layout(0,0,1000,100);
    }
    private static void tap(LinearLayout row,float x){
        long time=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,50,0);
        MotionEvent up=MotionEvent.obtain(time,time+25,MotionEvent.ACTION_UP,x,50,0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(row.dispatchTouchEvent(down),"down handled by native proxy");check(row.dispatchTouchEvent(up),"up handled by native proxy");}
        finally{down.recycle();up.recycle();}
    }
    public static void main(String[] arguments) throws Exception {
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        else if(Looper.myLooper()==null)Looper.prepare();
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        Object thread=threadClass.getMethod("systemMain").invoke(null);
        Context context=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setAlpha(0);row.setMotionEventSplittingEnabled(false);
        View[] slots=new View[5];int[] clicked=new int[5];int[] staleTouches={0};
        View.OnClickListener[] listeners=new View.OnClickListener[5];
        for(int i=0;i<slots.length;i++){
            final int identity=i;listeners[i]=view -> clicked[identity]++;
            slots[i]=new Proxy(context);slots[i].setOnClickListener(listeners[i]);
            row.addView(slots[i],new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1));
        }
        row.setWeightSum(5);measure(row);
        tap(row,750);check(clicked[3]==1 && clicked[4]==0,"original five-column region reproduces taxi interception");
        slots[3].setOnTouchListener((view,event) -> {staleTouches[0]++;return false;});
        boolean[] two={true,false,false,false,true};
        BaiduTabRouting.applyProxyRow(row,slots,two,listeners);measure(row);
        check(row.getWeightSum()==2,"proxy total matches two visible tabs");
        check(row.getAlpha()==0 && row.getHeight()==100,"proxy transparency and height preserved");
        Rect bounds=new Rect();slots[0].getHitRect(bounds);
        check(bounds.equals(new Rect(0,0,500,100)),"Home owns actual left-half rectangle");
        slots[4].getHitRect(bounds);check(bounds.equals(new Rect(500,0,1000,100)),"Mine owns actual right-half rectangle");
        for(int i=1;i<4;i++){
            check(slots[i].getVisibility()==View.GONE,"hidden proxy has no layout region");
            check(!slots[i].isEnabled() && !slots[i].isClickable() && !slots[i].hasOnClickListeners(),"hidden proxy has no enabled click handler");
        }
        int mineBefore=clicked[4],homeBefore=clicked[0],taxiBefore=clicked[3];
        for(int pass=0;pass<20;pass++){
            for(float x:new float[]{510,600,750,900,990})tap(row,x);
            tap(row,250);
            // Mirrors SDK callback rebinding listeners after page/skin refresh.
            for(int i=0;i<slots.length;i++){slots[i].setVisibility(View.VISIBLE);slots[i].setOnClickListener(listeners[i]);slots[i].setEnabled(true);}
            row.setWeightSum(5);
            BaiduTabRouting.applyProxyRow(row,slots,two,listeners);measure(row);
        }
        check(clicked[4]-mineBefore==100,"all 100 right-half taps route to Mine after rebind");
        check(clicked[0]-homeBefore==20,"all Home taps retain native Home listener");
        check(clicked[3]==taxiBefore && staleTouches[0]==0,"hidden Taxi and old touch handler never receive taps");
        boolean[] three={true,true,false,false,true};
        BaiduTabRouting.applyProxyRow(row,slots,three,listeners);measure(row);
        check(row.getWeightSum()==3,"three native tab identities remain distinct");
        int nearbyBefore=clicked[1];mineBefore=clicked[4];tap(row,500);tap(row,850);
        check(clicked[1]==nearbyBefore+1 && clicked[4]==mineBefore+1,"Nearby and Mine retain their native listener identities");
        boolean[] all={true,true,true,true,true};
        BaiduTabRouting.applyProxyRow(row,slots,all,listeners);measure(row);
        taxiBefore=clicked[3];tap(row,750);check(clicked[3]==taxiBefore+1,"explicitly shown Taxi restores its own click handler");
        int[] voiceTouches={0};int thirdBefore=clicked[2];
        slots[2].setOnClickListener(null);
        slots[2].setOnTouchListener((view,event) -> {if(event.getActionMasked()==MotionEvent.ACTION_UP)voiceTouches[0]++;return true;});
        View.OnClickListener[] voiceListeners=listeners.clone();voiceListeners[2]=null;
        BaiduTabRouting.applyProxyRow(row,slots,all,voiceListeners);measure(row);tap(row,500);
        check(!slots[2].hasOnClickListeners() && voiceTouches[0]==1 && clicked[2]==thirdBefore,"visible voice slot retains SDK TouchListener without creating click");
        boolean[] none={false,false,false,false,false};
        BaiduTabRouting.applyProxyRow(row,slots,none,listeners);measure(row);
        check(row.getWeightSum()==0,"hidden complete bar has no proxy weights");
        for(View slot:slots)check(slot.getVisibility()==View.GONE && !slot.isEnabled(),"entire hidden bar has no enabled proxy");
        System.out.println("BaiduTabRoutingTest: "+assertions+" assertions passed");
    }
}
