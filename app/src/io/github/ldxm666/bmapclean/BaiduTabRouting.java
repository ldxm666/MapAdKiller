package io.github.ldxm666.bmapclean;

import android.view.View;
import android.widget.LinearLayout;

/** Keep the SDK's transparent proxy hit regions in step with native tab cells. */
final class BaiduTabRouting {
    private static final String[] CELLS={"newRoute","newNearby","newThird","newFourth","newUser"};
    private static final String[] LISTENERS={"routeClickListener","nearbyClickListener","thirdClickListener","fourthClickListener","userClickListener"};
    private static final String[] PROXIES={"aihome_route_proxy","aihome_nearby_proxy","aihome_third_proxy","aihome_fourth_proxy","aihome_user_proxy"};
    private static boolean logged;
    static void refreshPresenter(Object presenter) throws Exception {
        refreshComponent(BaiduHooks.field(presenter,"component"));
    }
    static void refreshComponent(Object component) throws Exception {
        if(component==null)return;
        Object binding=BaiduHooks.field(component,"ufoBinding");
        Object presenter=BaiduHooks.field(component,"homeTabPresenter");
        if(binding==null || presenter==null)return;
        boolean master=Cfg.visible(Spec.K_BAR);
        boolean[] show=new boolean[CELLS.length];
        View.OnClickListener[] listeners=new View.OnClickListener[CELLS.length];
        for(int i=0;i<CELLS.length;i++){
            View cell=(View)BaiduHooks.field(binding,CELLS[i]);
            show[i]=master && (i==0 || Cfg.visible(Spec.K_TAB+Spec.TABS[i])) && cell!=null && cell.getVisibility()==View.VISIBLE;
            Object listener=BaiduHooks.field(presenter,LISTENERS[i]);
            // Third can be an SDK voice TouchListener with deliberately no
            // click callback. Keep bindThirdClickEvent's displayed semantics.
            if(i!=2 && listener instanceof View.OnClickListener)listeners[i]=(View.OnClickListener)listener;
            if(cell!=null){
                if(show[i]){cell.setEnabled(true);if(listeners[i]!=null)cell.setOnClickListener(listeners[i]);}
                else disable(cell);
            }
        }
        if(!show[2])disable((View)BaiduHooks.field(binding,"newHomeAiTouchView"));
        Object homeBinding=BaiduHooks.field(component,"binding");
        if(homeBinding==null)return;
        View rowView=(View)BaiduHooks.field(homeBinding,"preventEvent");
        if(!(rowView instanceof LinearLayout))return;
        LinearLayout row=(LinearLayout)rowView;
        View[] proxies=new View[PROXIES.length];
        for(int i=0;i<proxies.length;i++){
            int id=row.getResources().getIdentifier(PROXIES[i],"id",MainHook.PKG_BMAP);
            if(id!=0)proxies[i]=row.findViewById(id);
        }
        // This verified XML row is alpha=0 and fills the drawer's hit area.
        // Its padding, height, alpha and SDK visibility state stay intact.
        applyProxyRow(row,proxies,show,listeners);
        if(!master)row.setVisibility(View.GONE);
        if(!logged){logged=true;H.log("tab_routing native_proxy_slots=synchronized identity=listener");}
    }
    static void applyProxyRow(LinearLayout row,View[] proxies,boolean[] show,View.OnClickListener[] listeners){
        int count=0;
        for(int i=0;i<proxies.length;i++){
            View proxy=proxies[i];if(proxy==null)continue;
            if(!show[i]){disable(proxy);continue;}
            proxy.setVisibility(View.VISIBLE);proxy.setEnabled(true);
            if(listeners[i]!=null)proxy.setOnClickListener(listeners[i]);
            if(proxy.getLayoutParams() instanceof LinearLayout.LayoutParams){
                LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)proxy.getLayoutParams();
                if(params.width!=0 || params.weight!=1){params.width=0;params.weight=1;proxy.setLayoutParams(params);}
            }
            count++;
        }
        if(row.getWeightSum()!=count)row.setWeightSum(count);
    }
    private static void disable(View view){
        if(view==null)return;
        view.setOnTouchListener(null);view.setOnClickListener(null);view.setTouchDelegate(null);
        view.setClickable(false);view.setEnabled(false);view.setVisibility(View.GONE);
    }
    private BaiduTabRouting(){}
}
