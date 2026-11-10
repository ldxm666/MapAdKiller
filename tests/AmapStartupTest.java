package io.github.ldxm666.mapadkiller;

import org.json.JSONObject;

/** TEST-AMAP-STARTUP-235 / TEST-AMAP-GRID-235: first session and native render contract. */
public final class AmapStartupTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++; if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] ignored) throws Exception {
        for (boolean captured : new boolean[]{false,true}) {
            for (long at : new long[]{0L,50L,200L}) {
                check(!Baseline.shouldCapture(false,0L,0L,captured,at),"first install/upgrade never implicitly bypasses hooks");
                check(Baseline.shouldCapture(true,0L,0L,captured,at),"explicit local capture retained");
                check(!Baseline.shouldCapture(false,100L,100L,captured,at),"handled capture never repeats");
                check(Baseline.shouldCapture(false,100L,0L,captured,at)==!(captured&&at>=100L),"pending remote request uses timestamp and completion");
            }
        }
        for (int count=0; count<=10; count++) {
            JSONObject source=new JSONObject().put("king_switch","0").put("metadata","preserved");
            for(int i=0;i<11;i++) {
                source.put("id"+i,i<count?"tool"+i:"").put("size"+i,"90px")
                    .put("name"+i,"name"+i).put("action"+i,"route:"+i);
            }
            String original=source.toString();
            JSONObject out=AmapData.renderToolbox(source);
            check(source.toString().equals(original),"host model remains unchanged");
            check(out.optString("metadata").equals("preserved"),"metadata preserved");
            check(out.optString("king_switch").equals("1"),"native validation does not substitute default tools");
            int slots=0;
            while(out.has("id"+slots)) {
                check(out.optString("size"+slots).matches("[1-9][0-9]*px"),"native positive-size validation");
                if(count>0) {
                    check(out.optString("id"+slots).equals("tool"+slots),"stable compact order");
                    check(out.optString("action"+slots).equals("route:"+slots),"routes remain paired");
                }
                slots++;
            }
            check(slots==Math.max(1,count),"no trailing placeholder row for "+count+" tools");
            if(count==0)check(out.optString("id0").isEmpty(),"zero tools use only an empty sentinel");
            check(AmapData.renderToolbox(out).toString().equals(out.toString()),"render filter idempotent");
        }
        System.out.println("AmapStartupTest: "+checks+" assertions passed");
    }
}
