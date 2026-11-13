package io.github.ldxm666.mapadkiller;
import java.util.*;
import org.json.*;
import io.github.ldxm666.mapclean.ConfigBackup;
import io.github.ldxm666.mapclean.ToolOrderCodec;
public class AmapToolOrderBackupTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static final class Store implements ConfigBackup.Store {
        final Map<String,Object> values=new LinkedHashMap<>();
        public Map<String,?> read(String group){return new LinkedHashMap<>(values);}
        public boolean write(String group,Map<String,?> updates){for(Map.Entry<String,?> e:updates.entrySet()){
            if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}return true;}
    }
    public static void main(String[] args)throws Exception {
        Map<String,Set<String>> schema=new LinkedHashMap<>();schema.put(ConfigBackup.AMAP,new LinkedHashSet<>(Arrays.asList(ToolOrderCodec.KEY,"tool_flag")));
        Store before=new Store();before.values.put(ToolOrderCodec.KEY,"[\"102\",\"105\",\"102\"]");before.values.put("tool_flag",true);
        JSONObject backup=ConfigBackup.encode(before,schema,"fixture");
        Store after=new Store();after.values.put("unrelated","preserved");
        ConfigBackup.apply(after,ConfigBackup.decode(backup,schema));
        check(after.values.get(ToolOrderCodec.KEY).equals("[\"102\",\"105\"]"),"typed order round trip");
        check(after.values.get("tool_flag").equals(true)&&after.values.get("unrelated").equals("preserved"),"backup preserves switches and unrelated settings");
        check(ConfigBackup.decode(new JSONObject().put("tool_flag",false),schema).get(ConfigBackup.AMAP).get("tool_flag").equals(false),"legacy boolean backup retained");
        boolean rejected=false;try{ConfigBackup.decode(new JSONObject().put(ToolOrderCodec.KEY,new JSONArray().put("102")),schema);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"only the whitelisted string representation is accepted");
        System.out.println("PASS "+checks+" tool-order backup assertions");
    }
}
