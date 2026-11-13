package io.github.ldxm666.bmapclean;

/** Exercises native half/hide transitions, including callbacks and retained map insets. */
public class BaiduHomeReselectTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public static class Presenter {
        String state="COLLAPSED"; int half,hide,full,callbacks,inset=120;
        private String getDrawerState(){return state;}
        public void letTabHalf(){half++;state="COLLAPSED";callbacks++;inset=120;}
        public void letTabHide(){hide++;state="HIDDEN";callbacks++;inset=0;}
        private void letTabFull(){full++;state="EXPANDED";callbacks++;inset=900;}
    }
    public static void main(String[] args){
        for(boolean compact:new boolean[]{false,true})for(String current:new String[]{"ROUTE","USER","NEARBY"})for(String target:new String[]{"ROUTE","USER","NEARBY"})
            check(BaiduHomeReselect.shouldScope(current,target,compact)==(compact&&current.equals("ROUTE")&&target.equals("ROUTE")),"only compact reselected home changes");
        Presenter p=new Presenter();
        for(int round=0;round<10;round++){
            check(BaiduHomeReselect.toggleCompact(p),"native hide handled");
            check(p.state.equals("HIDDEN")&&p.inset==0,"full map has no white drawer");
            check(BaiduHomeReselect.toggleCompact(p),"native half handled");
            check(p.state.equals("COLLAPSED")&&p.inset==120,"compact content restored");
        }
        check(p.full==0&&p.half==10&&p.hide==10&&p.callbacks==20,"exactly one native callback per action");
        p.state="EXPANDED";check(BaiduHomeReselect.toggleCompact(p)&&p.state.equals("HIDDEN"),"stale expanded state recovers through native hide");
        p.state="SETTLING";int old=p.callbacks;check(!BaiduHomeReselect.toggleCompact(p)&&p.callbacks==old,"animation is not retargeted");
        check(!BaiduHomeReselect.toggleCompact(new Object()),"unknown host contract fails open");
        System.out.println("PASS BaiduHomeReselectTest checks="+checks);
    }
}
