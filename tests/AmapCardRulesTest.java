package io.github.ldxm666.mapadkiller;

import java.util.*;
import org.json.*;

public class AmapCardRulesTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static class Options implements AmapData.Settings {
        final Map<String,Boolean> values=new HashMap<>();
        public boolean enabled(String key){return values.getOrDefault(key,true);}
        public boolean selected(String key){return enabled(key);}
    }
    public static void main(String[] args)throws Exception {
        String[] keys={Config.K_FEED_WEATHER,Config.K_FEED_BOARD,Config.K_FEED_CONTENT,Config.K_FEED_FESTIVAL,Config.K_TAXI_CARD};
        String[] names={"hourly_weather","tipsRanking","contentCenterFeed","festivalActivityCard","ShareTripTaxiCard"};
        for(int bits=0;bits<32;bits++){
            Options s=new Options(); JSONArray cards=new JSONArray();
            for(int i=0;i<keys.length;i++){
                s.values.put(keys[i],(bits&(1<<i))!=0);
                JSONObject c=new JSONObject().put("data_type",names[i]);cards.put(c);
                JSONObject frame=new JSONObject().put("component",new JSONObject().put("type","container").put("name","genericContainer")
                    .put("id","card-"+i).put("data",new JSONObject().put("fixture","render-payload"))
                    .put("extraInfo",new JSONObject().put("biz_name",names[i]))).put("is_done",false).put("seq",i+1);
                JSONObject filtered=AmapData.stream(frame,s);
                check(filtered!=null&&filtered.getInt("seq")==i+1,"stream envelope retained bits="+bits+" i="+i);
                check(filtered.getJSONObject("component").has("data")==s.enabled(keys[i]),"stream payload independent bits="+bits+" i="+i);
                check(filtered.getJSONObject("component").getString("id").equals("card-"+i),"component identity retained");
                JSONObject terminal=new JSONObject(frame.toString()).put("isDone",true).put("session_id","session").put("hasMore",true);
                JSONObject end=AmapData.stream(terminal,s);
                check(end!=null&&end.getBoolean("isDone")&&end.getString("session_id").equals("session"),"completion retained");
                check(end.getBoolean("hasMore"),"server pagination retained for every switch combination");
                check(terminal.getJSONObject("component").has("extraInfo"),"original stream not mutated");
            }
            cards.put(new JSONObject().put("card_name","frequentLocations"));
            JSONObject raw=new JSONObject().put("code",1).put("data",new JSONObject().put("cardList",cards)
                .put("mapBizList",new JSONArray().put(new JSONObject().put("id","map-poi"))));
            String out=AmapCardRules.response(raw.toString(),s);
            JSONArray kept=new JSONObject(out).getJSONObject("data").getJSONArray("cardList");
            check(kept.length()==Integer.bitCount(bits)+1,"HTTP independent combination "+bits);
            check(new JSONObject(out).getJSONObject("data").getJSONArray("mapBizList").toString().equals(raw.getJSONObject("data").getJSONArray("mapBizList").toString()),"map data preserved");
            check(raw.getJSONObject("data").getJSONArray("cardList").length()==6,"response source intact");
            check(AmapCardRules.cached(raw.toString(),s).equals(out),"cached result uses same classifier");
            JSONObject recruit=new JSONObject().put("cardName","rideshareRecruit").put("data",new JSONObject().put("fixture","recruit-render-data"));
            JSONObject recruitFrame=new JSONObject().put("seq",12).put("component",new JSONObject(recruit.toString()));
            check(AmapData.stream(recruitFrame,s).getJSONObject("component").has("data")==s.enabled(Config.K_FEED_FESTIVAL),"rideshare recruitment follows promotion switch alone bits="+bits);
            String recruitList=new JSONObject().put("data",new JSONObject().put("cardList",new JSONArray().put(recruit)).put("mapBizList",new JSONArray())).toString();
            String recruitResponse=AmapCardRules.response(recruitList,s);
            check(new JSONObject(recruitResponse).getJSONObject("data").getJSONArray("cardList").length()==(s.enabled(Config.K_FEED_FESTIVAL)?1:0),"recruitment HTTP classified");
            check(AmapCardRules.cached(recruitList,s).equals(recruitResponse),"recruitment cache classified consistently");
            s.values.put(Config.K_HOME_CHIPS,false);
            check(new JSONObject(AmapCardRules.response(raw.toString(),s)).getJSONObject("data").getJSONArray("cardList").length()==Integer.bitCount(bits),"home/company independent");
            for(String key:keys)s.values.put(key,true);s.values.put(Config.K_HOME_CHIPS,true);
            check(AmapCardRules.response(raw.toString(),s).equals(raw.toString()),"reenable restores original cached cards");
        }
        Options off=new Options();for(String key:keys)off.values.put(key,false);
        JSONObject control=new JSONObject().put("component",new JSONObject().put("cardName","navigationContinuation")).put("token",42);
        check(AmapData.stream(control,off)==control,"unclassified business/control frame preserved");
        check(AmapCardRules.key(new JSONObject().put("dataType","CombineUI").put("dataKey","Combine|CommuteShareTripTaxi|123")).equals(Config.K_TAXI_CARD),"taxi wrapper recognized");
        check(AmapCardRules.key(new JSONObject().put("description","weather activity coupon").put("cardName","NavigationCard"))==null,"arbitrary content never used as identity");
        String other="{\"data\":{\"cardList\":[{\"dataType\":\"hourlyWeather\"}]}}";
        check(AmapCardRules.cached(other,off).equals(other),"non-home storage preserved");
        sequenceRegression();
        scopeRegression();
        System.out.println("PASS AmapCardRulesTest checks="+checks);
    }
    public static class Scene {public String i;}
    static void scopeRegression()throws Exception{
        Scene session=new Scene();java.lang.reflect.Field scene=Scene.class.getField("i");
        java.lang.reflect.Field cache=AmapProtocolHooks.class.getDeclaredField("sessions");cache.setAccessible(true);
        Map<Object,Boolean> sessions=(Map<Object,Boolean>)cache.get(null);
        sessions.put(session,false);session.i="home_qs";
        check(AmapProtocolHooks.isHomeStream(session,scene),"home reload recognized even if tiny setter was inlined");
        sessions.put(session,true);session.i="navigation";
        check(!AmapProtocolHooks.isHomeStream(session,scene),"current non-home scene overrides stale home record");
        session.i=null;check(AmapProtocolHooks.isHomeStream(session,scene),"missing scene value falls back to setter record");
        check(!AmapProtocolHooks.isHomeStream(new Scene(),null),"unknown session never filtered globally");
        sessions.remove(session);
    }
    /** An earlier hidden taxi packet must not strand the following enabled weather. */
    static void sequenceRegression()throws Exception{
        Options weather=new Options();
        weather.values.put(Config.K_FEED_BOARD,false);weather.values.put(Config.K_FEED_CONTENT,false);
        weather.values.put(Config.K_FEED_FESTIVAL,false);weather.values.put(Config.K_TAXI_CARD,false);
        String[] names={"", "", "", "navContinuation", "frequentLocations", "taxiQS", "hourlyWeather", "taxiQSMarket", ""};
        Map<Integer,JSONObject> waiting=new TreeMap<>();int next=1;boolean rendered=false;
        for(int i=0;i<names.length;i++){
            JSONObject component=new JSONObject().put("id","component-"+i).put("type",i==8?"markdown":"container")
                .put("is_done",i!=8).put("extra_info",new JSONObject().put("cardName",names[i]).put("card_pos",i))
                .put("data",new JSONObject().put("type","pmt").put("id","pmt").put("fixture","weather-render-data"));
            JSONObject frame=new JSONObject().put("seq",i+1).put("session_id","fixture-session").put("task_id","fixture-task")
                .put("is_done",i==8).put("has_more",true).put("next_page_num",2).put("component",component);
            String original=frame.toString();JSONObject out=AmapData.stream(frame,weather);
            check(out!=null,"no sequence hole at "+(i+1));
            check(out.getInt("seq")==i+1&&out.getString("task_id").equals("fixture-task"),"transport identity unchanged");
            check(out.getBoolean("is_done")== (i==8)&&out.getBoolean("has_more")&&out.getInt("next_page_num")==2,"transport flags unchanged");
            check(out.getJSONObject("component").getString("id").equals("component-"+i),"component id unchanged");
            check(out.getJSONObject("component").getJSONObject("extra_info").getInt("card_pos")==i,"component metadata unchanged");
            if(i==5||i==7){
                JSONObject empty=out.getJSONObject("component");
                check(!empty.has("data")&&empty.getString("type").equals("markdown")&&empty.getString("text").isEmpty()&&empty.getBoolean("is_done"),"hidden taxi is empty completed component");
            }else check(out==frame,"visible/control packets are passed through byte-for-byte");
            check(original.equals(frame.toString()),"cached source not mutated");
            waiting.put(out.getInt("seq"),out);
            while(waiting.containsKey(next)){
                JSONObject delivered=waiting.remove(next++);
                if("hourlyWeather".equals(AmapData.cardName(delivered))&&delivered.getJSONObject("component").has("data"))rendered=true;
            }
        }
        check(rendered&&next==10&&waiting.isEmpty(),"weather renders through contiguous stream with hidden taxi before it");
    }
}
