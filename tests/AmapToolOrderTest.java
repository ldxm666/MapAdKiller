package io.github.ldxm666.mapadkiller;

import io.github.ldxm666.mapclean.ToolOrderCodec;
import java.util.*;
import org.json.*;

/** TEST-AMAP-ORDER-236: production reconciliation, typed backup and source immutability. */
public final class AmapToolOrderTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Settings implements AmapData.OrderedSettings {
        final Set<String> keys=new HashSet<>();List<String> order=new ArrayList<>();
        boolean toolsVisible=true;
        public boolean enabled(String key){return key.equals(Config.K_TOOLS_VISIBLE)?toolsVisible:keys.contains(key);}
        public boolean selected(String key){return keys.contains(key);}
        public List<String> toolOrder(){return order;}
    }
    private static void invalid(String raw){boolean bad=false;try{ToolOrderCodec.decode(raw);}catch(IllegalArgumentException expected){bad=true;}check(bad,"invalid order rejected: "+raw);}
    private static android.content.SharedPreferences failingStore(Map<String,String> values,boolean[] fail){
        return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(AmapToolOrderTest.class.getClassLoader(),new Class[]{android.content.SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().equals("getString"))return values.getOrDefault(a[0],(String)a[1]);
            if(m.getName().equals("edit")){
                Map<String,String> staged=new HashMap<>();Set<String> removed=new HashSet<>();
                return java.lang.reflect.Proxy.newProxyInstance(AmapToolOrderTest.class.getClassLoader(),new Class[]{android.content.SharedPreferences.Editor.class},(e,em,ea)->{
                    if(em.getName().equals("putString")){staged.put((String)ea[0],(String)ea[1]);return e;}
                    if(em.getName().equals("remove")){removed.add((String)ea[0]);return e;}
                    if(em.getName().equals("commit")){values.keySet().removeAll(removed);values.putAll(staged);boolean ok=!fail[0];fail[0]=false;return ok;}
                    return e;
                });
            }
            return null;
        });
    }
    private static List<String> slotIds(JSONObject payload){
        List<String> ids=new ArrayList<>();
        for(int i=0;i<32;i++)if(!payload.optString("id"+i).isEmpty())ids.add(payload.optString("id"+i));
        return ids;
    }
    public static final class DomNode {
        public DomNode c;public String f="div";
        final Map<String,Object> attrs=new HashMap<>();final List<DomNode> nodes=new ArrayList<>();
        DomNode(String classes){attrs.put("class",classes);}
        DomNode add(DomNode child){child.c=this;nodes.add(child);return this;}
        public Object h(String key){return attrs.get(key);}
        public List<DomNode> i(){return nodes;}
    }
    private static DomNode moreItem(String classes){
        DomNode label=new DomNode("tl_item_label");label.f="label";label.attrs.put("text","更多工具");
        return new DomNode(classes).add(new DomNode("tl_item_container").add(label));
    }
    private static void homeMoreDomShape()throws Exception{
        java.lang.reflect.Method match=AmapHomeTools.class.getDeclaredMethod("moreCells",Object.class);match.setAccessible(true);
        DomNode root=new DomNode("tool_box_container"),row=new DomNode("");root.add(row);
        DomNode misleading=moreItem("unrelated");row.add(misleading);
        check(((List<?>)match.invoke(AmapHomeTools.INSTANCE,root)).isEmpty(),"text outside a verified tool item is never hidden");
        DomNode more=moreItem("tool_box_item tool_box_item_small");row.add(more);
        List<?> results=(List<?>)match.invoke(AmapHomeTools.INSTANCE,root);Object result=results.get(0);
        check(results.size()==1&&result.getClass().getMethod("getFirst").invoke(result)==more
                &&result.getClass().getMethod("getSecond").invoke(result)==row,"native More item with multiple class tokens is resolved with its exact row");
        row.add(moreItem("tool_box_item_more"));
        check(((List<?>)match.invoke(AmapHomeTools.INSTANCE,root)).size()==1,"similar class names are not matched by substring");
        row.add(moreItem("tool_box_item"));
        check(((List<?>)match.invoke(AmapHomeTools.INSTANCE,root)).size()==2,"both native More layout copies are found within the same business container");
    }
    private static void homeMoreSetting()throws Exception{
        java.lang.reflect.Method read=AmapHomeTools.class.getDeclaredMethod("moreEnabled");read.setAccessible(true);
        java.lang.reflect.Field snapshot=Config.class.getDeclaredField("snapshot"),expires=Config.class.getDeclaredField("expiresAt");snapshot.setAccessible(true);expires.setAccessible(true);
        Object previous=snapshot.get(null);long previousExpiry=expires.getLong(null);
        try{
            for(boolean tools:new boolean[]{false,true})for(boolean allowlist:new boolean[]{false,true})for(Boolean saved:new Boolean[]{null,false,true}){
                Map<String,Object> values=new HashMap<>();values.put(Config.K_TOOLS_VISIBLE,tools);values.put(Config.K_TOOLS_ALLOWLIST,allowlist);
                if(saved!=null)values.put(AmapToolCatalog.key(AmapData.MORE_TOOLS),saved);
                snapshot.set(null,Collections.unmodifiableMap(values));expires.setLong(null,Long.MAX_VALUE);
                Config.hotFlag(Config.K_TOOLS_VISIBLE).resolve();Config.hotFlag(Config.K_TOOLS_ALLOWLIST).resolve();
                boolean expected=tools&&(allowlist?Boolean.TRUE.equals(saved):saved==null||saved);
                check((Boolean)read.invoke(AmapHomeTools.INSTANCE)==expected,"production More DOM gate uses master/allowlist/saved selection together");
                check((Boolean)read.invoke(AmapHomeTools.INSTANCE)==AmapData.toolVisible(AmapData.MORE_TOOLS,AmapData.CURRENT),"DOM and rendered-tool visibility agree");
            }
        }finally{
            snapshot.set(null,previous);expires.setLong(null,previousExpiry);
            Config.hotFlag(Config.K_TOOLS_VISIBLE).resolve();Config.hotFlag(Config.K_TOOLS_ALLOWLIST).resolve();
        }
    }
    private static void homeCanvasStorage()throws Exception{
        final String home="path://amap_bundle_quickservice/src/pages/QuickServiceRedesign.page.js";
        final String key="local_tool_list_for_xml_v2";
        check(AmapData.virtualHomeToolWrite(home,"toolbox",key),"home normalized render cache stays virtual");
        check(!AmapData.virtualHomeToolWrite(home,"other",key),"other storage namespace still persists");
        check(!AmapData.virtualHomeToolWrite(home,"toolbox","choiceTools"),"real choice edits still persist");
        check(!AmapData.virtualHomeToolWrite("path://amap_bundle_toolbox/src/pages/ToolboxHomepage.page.js","toolbox",key),"More Tools editor cache still persists");
        for(String path:new String[]{home,home+"?mode=home",home+"#home",home+"?mode=home#top"})
            check(AmapData.homeToolPage(path),"home canvas path accepts its query/fragment");
        for(String path:new String[]{null,"",home+".backup","prefix"+home,"path://amap_bundle_quickservice/src/pages/MoreTools.page.js"})
            check(!AmapData.homeToolPage(path),"other pages never receive a filled home list");
        for(boolean toolsEnabled:new boolean[]{false,true})for(boolean moreEnabled:new boolean[]{false,true}){
            check(AmapHomeTools.shouldHideMore(home,toolsEnabled,moreEnabled)==(toolsEnabled&&!moreEnabled),"More-only DOM suppression follows its independent switch");
            check(!AmapHomeTools.shouldHideMore("path://amap_bundle_toolbox/src/pages/ToolboxHomepage.page.js",toolsEnabled,moreEnabled),"More editor is never suppressed by the homepage switch");
            check(AmapHomeTools.shouldHideTools(home,toolsEnabled)==!toolsEnabled,"master switch retains priority over individual tools");
        }

        Settings s=new Settings();
        List<String> enabled=new ArrayList<>(Arrays.asList("102","103"));
        for(String[] entry:AmapToolCatalog.ENTRIES)
            if(enabled.size()<18&&!entry[0].equals(AmapData.MORE_TOOLS)&&!enabled.contains(entry[0]))enabled.add(entry[0]);
        for(String id:enabled)s.keys.add(AmapToolCatalog.key(id));
        s.keys.add(AmapToolCatalog.key(AmapData.MORE_TOOLS));
        JSONArray tools=new JSONArray();
        for(String id:enabled)tools.put(new JSONObject().put("id",id).put("name","host-"+id)
                .put("schema","amapuri://test/tool?id="+id+"&label=a+b")
                .put("imageToken","host-icon-"+id).put("size","90px")
                .put("hostExtra",new JSONObject().put("owner",id).put("version",7)));
        String catalog=new JSONObject().put("category",new JSONArray().put(new JSONObject().put("tools",tools))).toString();
        JSONObject original=new JSONObject().put("metadata","keep");
        for(int i=0;i<3;i++){
            String id=new String[]{"102","103",AmapData.MORE_TOOLS}[i];
            original.put("id"+i,id).put("name"+i,"native-"+id).put("schema"+i,"native-route:"+id)
                    .put("imageToken"+i,"native-icon:"+id).put("size"+i,"90px")
                    .put("itemDataForClick"+i,new JSONObject().put("owner",id));
        }
        final String raw=original.toString();
        Map<String,String> stored=new HashMap<>();stored.put("tool_list_data",catalog);stored.put(key,raw);
        final Map<String,String> before=new HashMap<>(stored);
        final android.content.SharedPreferences prefs=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                AmapToolOrderTest.class.getClassLoader(),new Class[]{android.content.SharedPreferences.class},(p,m,a)->{
                    if(m.getName().equals("getString"))return stored.getOrDefault(a[0],(String)a[1]);
                    if(m.getName().equals("contains"))return stored.containsKey(a[0]);
                    throw new AssertionError("home rendering must only read its source: "+m.getName());
                });
        android.content.Context context=new android.content.ContextWrapper(null){
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                if(!name.equals("toolbox"))throw new AssertionError("unexpected preferences: "+name);
                return prefs;
            }
        };
        JSONObject filled=new JSONObject(AmapData.homeToolStorage(home,key,raw,s,context));
        check(slotIds(filled).size()==10,"three cached cells expand to the two-row home capacity");
        check(filled.optString("id9").equals(AmapData.MORE_TOOLS),"default fill keeps More Tools at the last visible cell");
        check(filled.optString("metadata").equals("keep"),"home render retains native root metadata");

        String moved=enabled.get(17);
        s.order=Arrays.asList(moved,"103","102");
        String[] cardKeys={Config.K_FEED_WEATHER,Config.K_FEED_BOARD,Config.K_FEED_CONTENT,Config.K_FEED_FESTIVAL};
        for(int mask=0;mask<16;mask++){
            for(int i=0;i<cardKeys.length;i++){s.keys.remove(cardKeys[i]);if((mask&(1<<i))!=0)s.keys.add(cardKeys[i]);}
            JSONObject ordered=new JSONObject(AmapData.homeToolStorage(home+"?canvas=1",key,raw,s,context));
            List<String> visible=slotIds(ordered);
            check(visible.size()==10&&new HashSet<>(visible).size()==10,"all card combinations keep ten distinct selected tools");
            check(visible.get(0).equals(moved)&&visible.get(1).equals("103")&&visible.get(2).equals("102"),"saved order brings an overflow tool into view before truncation");
            check(visible.get(9).equals(AmapData.MORE_TOOLS)&&!ordered.has("id10"),"home never creates an overlapping third row");
            check(ordered.optString("schema0").equals("amapuri://test/tool?id="+moved+"&label=a+b")
                    &&ordered.optString("imageToken0").equals("host-icon-"+moved)
                    &&ordered.optString("name0").equals("host-"+moved),"catalog tool keeps its native name, icon and route");
            check(new JSONObject(android.net.Uri.decode(ordered.getString("itemDataForClick0"))).getString("schema")
                    .equals(ordered.getString("schema0")),"filled cell click route matches its displayed tool including literal plus");
            check(ordered.getJSONObject("itemDataForClick1").getString("owner").equals("103")
                    &&ordered.optString("imageToken1").equals("native-icon:103"),"existing cell retains full native action and icon after reorder");
        }
        for(String path:new String[]{null,"path://amap_bundle_quickservice/src/pages/MoreTools.page.js",home+".backup"}){
            JSONObject other=new JSONObject(AmapData.homeToolStorage(path,key,raw,s,context));
            check(new HashSet<>(slotIds(other)).equals(new HashSet<>(Arrays.asList("102","103",AmapData.MORE_TOOLS))),"non-home reads only filter the original three cells");
        }
        for(String arrayKey:new String[]{"recommendTools","choiceTools"}){
            JSONArray oldList=new JSONArray().put(tools.getJSONObject(0)).put(tools.getJSONObject(1));
            String cached=new JSONObject().put(arrayKey,oldList).put("revision",23).put("metadata",new JSONObject().put("native",true)).toString();
            JSONObject rendered=new JSONObject(AmapData.homeToolStorage(home,arrayKey,cached,s,context));
            JSONArray selected=rendered.getJSONArray(arrayKey);
            if(arrayKey.equals("choiceTools")){
                check(selected.length()==0,"homepage grid tools are not repeated in the More-folder preview");
                check(rendered.getInt("revision")==23&&rendered.getJSONObject("metadata").getBoolean("native"),"suppressing the homepage folder preview retains wrapper metadata");
                for(String page:new String[]{null,"path://amap_bundle_quickservice/src/pages/MoreTools.page.js"}){
                    JSONArray unchanged=new JSONObject(AmapData.homeToolStorage(page,arrayKey,cached,s,context)).getJSONArray(arrayKey);
                    check(unchanged.toString().equals(oldList.toString()),"More Tools and other pages retain the host choice list");
                }
                s.toolsVisible=false;
                check(new JSONObject(AmapData.homeToolStorage(home,arrayKey,cached,s,context)).getJSONArray(arrayKey).length()==0,"master off keeps the folder preview empty");
                s.toolsVisible=true;
                check(new JSONObject(cached).getJSONArray(arrayKey).length()==2,"homepage preview suppression never writes its source");
                continue;
            }
            check(selected.length()==9,"home array fills nine selected tools, reserving the native More Tools cell: "+arrayKey);
            check(selected.getJSONObject(0).getString("id").equals(moved)
                    &&selected.getJSONObject(1).getString("id").equals("103")
                    &&selected.getJSONObject(2).getString("id").equals("102"),"AJX arrays use the same order as the indexed toolbar");
            for(int i=0;i<selected.length();i++){
                JSONObject item=selected.getJSONObject(i);String id=item.getString("id");
                check(!id.equals(AmapData.MORE_TOOLS),"AJX array never duplicates its own More Tools cell");
                check(item.getString("schema").equals("amapuri://test/tool?id="+id+"&label=a+b")
                        &&item.getString("imageToken").equals("host-icon-"+id)
                        &&item.getJSONObject("hostExtra").getString("owner").equals(id)
                        &&item.getJSONObject("hostExtra").getInt("version")==7,"AJX preserves full host objects including icon, route and unknown fields");
            }
            check(rendered.getInt("revision")==23&&rendered.getJSONObject("metadata").getBoolean("native"),"recommendation wrapper metadata survives reconciliation");
            s.keys.remove(AmapToolCatalog.key(AmapData.MORE_TOOLS));
            JSONArray withoutMore=new JSONObject(AmapData.homeToolStorage(home,arrayKey,cached,s,context)).getJSONArray(arrayKey);
            check(withoutMore.length()==9,"AJX keeps its reserved native More slot when More is hidden: "+arrayKey);
            check(withoutMore.getJSONObject(0).getString("id").equals(moved)
                    &&withoutMore.getJSONObject(1).getString("id").equals("103"),"hiding More retains the chosen leading order");
            for(int i=0;i<withoutMore.length();i++)check(!withoutMore.getJSONObject(i).getString("id").equals(AmapData.MORE_TOOLS),"More preference off never puts More inside the recommendation array");
            s.keys.add(AmapToolCatalog.key(AmapData.MORE_TOOLS));
            for(String page:new String[]{null,"path://amap_bundle_quickservice/src/pages/MoreTools.page.js"}){
                JSONArray unchanged=new JSONObject(AmapData.homeToolStorage(page,arrayKey,cached,s,context)).getJSONArray(arrayKey);
                check(unchanged.toString().equals(oldList.toString()),"other pages keep their original recommendation selection");
            }
            s.toolsVisible=false;
            check(new JSONObject(AmapData.homeToolStorage(home,arrayKey,cached,s,context)).getJSONArray(arrayKey).length()==0,"master off empties AJX tool arrays");
            s.toolsVisible=true;
            check(new JSONObject(cached).getJSONArray(arrayKey).length()==2,"array render does not mutate cached recommendations");
        }
        check(AmapData.homeToolStorage(home,"unrelated",raw,s,context).equals(raw),"unrelated storage key is unchanged");
        check(AmapData.homeToolStorage(home,key,null,s,context)==null,"absent cache is safe");
        s.keys.remove(AmapToolCatalog.key(moved));
        check(!slotIds(new JSONObject(AmapData.homeToolStorage(home,key,raw,s,context))).contains(moved),"disabling a filled tool removes it on the next live read");
        s.keys.add(AmapToolCatalog.key(moved));
        check(new JSONObject(AmapData.homeToolStorage(home,key,raw,s,context)).optString("id0").equals(moved),"re-enabling restores its saved leading position");
        s.toolsVisible=false;
        JSONObject hidden=new JSONObject(AmapData.homeToolStorage(home,key,raw,s,context));
        check(slotIds(hidden).isEmpty(),"master tool switch hides every home tool");
        check(hidden.optString("king_switch").equals("0")&&!hidden.has("id0"),"empty AJX selection disables the toolbar without a native sentinel");
        check(stored.equals(before)&&original.toString().equals(raw),"rendering never writes the host selection or catalog");

        Settings fallback=new Settings();
        for(String id:new String[]{"102","103","104","105",AmapData.MORE_TOOLS})fallback.keys.add(AmapToolCatalog.key(id));
        fallback.order=Arrays.asList("105","104","102","103");
        check(slotIds(new JSONObject(AmapData.homeToolStorage(home,key,raw,fallback,null)))
                .equals(Arrays.asList("105","104","102","103",AmapData.MORE_TOOLS)),"missing Context still fills known built-in walking and cycling tools");
        stored.remove("tool_list_data");
        check(slotIds(new JSONObject(AmapData.homeToolStorage(home,key,raw,fallback,context)))
                .equals(Arrays.asList("105","104","102","103",AmapData.MORE_TOOLS)),"cold home without a downloaded catalog fills known selected tools");
        for(String arrayKey:new String[]{"recommendTools","choiceTools"}){
            String empty=new JSONObject().put(arrayKey,new JSONArray()).put("revision",23).toString();
            JSONArray selected=new JSONObject(AmapData.homeToolStorage(home,arrayKey,empty,fallback,context)).getJSONArray(arrayKey);
            if(arrayKey.equals("choiceTools")){
                check(selected.length()==0,"cold cache never copies grid selections into the folder preview");
                continue;
            }
            List<String> selectedIds=new ArrayList<>();
            for(int i=0;i<selected.length();i++){
                JSONObject item=selected.getJSONObject(i);selectedIds.add(item.getString("id"));
                check(!item.getString("schema").isEmpty(),"missing catalog fallback retains a real tool route");
            }
            check(selectedIds.equals(Arrays.asList("105","104","102","103")),"empty recommendation cache fills static tools in saved order");
        }
        stored.put("tool_list_data",catalog);
        check(stored.equals(before),"test restores its catalog fixture after cold-cache checks");
    }
    public static void main(String[] args)throws Exception {
        check(ToolOrderCodec.decode(null).isEmpty(),"old installs have no forced order");
        check(ToolOrderCodec.decode("[\"102\",\"103\",\"102\"]").equals(Arrays.asList("102","103")),"stable duplicate removal");
        for(String raw:new String[]{"{}","[102]","[null]","[\"tool_驾车\"]","[true]","broken"})invalid(raw);
        check(ToolOrderCodec.read("broken").isEmpty(),"runtime corrupted order safely uses default");
        check(AmapToolOrder.remember(Arrays.asList("102","103","105"),Arrays.asList("105","102")).equals(Arrays.asList("105","103","102")),"disabled position retained during visible reorder");
        check(AmapToolOrder.reconcile(Arrays.asList("105","103","102"),Arrays.asList("102","103","105","106")).equals(Arrays.asList("105","103","102","106")),"re-enabled position restored and new selection appended");
        List<String> ids=new ArrayList<>();Settings s=new Settings();
        for(String[] entry:AmapToolCatalog.ENTRIES)if(!entry[0].equals(AmapData.MORE_TOOLS)&&ids.size()<18){ids.add(entry[0]);s.keys.add(AmapToolCatalog.key(entry[0]));}
        s.keys.add(AmapToolCatalog.key(AmapData.MORE_TOOLS));
        JSONObject source=new JSONObject().put("metadata","keep");
        for(int i=0;i<10;i++)source.put("id"+i,ids.get(i)).put("name"+i,"native-"+ids.get(i)).put("schema"+i,"route:"+ids.get(i)).put("size"+i,"90px").put("imageToken"+i,"icon:"+ids.get(i)).put("itemDataForClick"+i,new JSONObject().put("owner",ids.get(i)));
        source.put("id10",AmapData.MORE_TOOLS).put("name10","更多工具").put("size10","90px");
        String original=source.toString();
        s.order=Arrays.asList(ids.get(8),ids.get(14),ids.get(0));
        JSONObject out=AmapData.toolbox(source,s,true,10);
        check(out.optString("id0").equals(ids.get(8)),"custom native tool first");
        check(out.optString("schema0").equals("route:"+ids.get(8)),"route moves with the tool");
        check(out.optString("imageToken0").equals("icon:"+ids.get(8)),"icon moves with the tool");
        check(out.getJSONObject("itemDataForClick0").getString("owner").equals(ids.get(8)),"click metadata moves with the tool");
        check(out.optString("id1").equals(ids.get(14)),"tool beyond capacity enters before truncation");
        check(out.optString("id9").equals(AmapData.MORE_TOOLS),"native end cell reserved");
        check(source.toString().equals(original),"host cache object never mutated");
        check(out.optString("metadata").equals("keep"),"host root metadata retained");
        for(int cap=1;cap<=10;cap++){
            JSONObject capped=AmapData.toolbox(source,s,true,cap);
            check(capped.optString("id"+(cap-1)).equals(AmapData.MORE_TOOLS),"more stays last for cap "+cap);
        }
        s.order=Collections.emptyList();
        check(AmapData.toolbox(source,s,false,10).optString("id0").equals(ids.get(0)),"reset restores host order");
        Map<String,String> values=new HashMap<>();boolean[] fail={true};android.content.SharedPreferences prefs=failingStore(values,fail);
        check(!ToolOrderCodec.persist(prefs,ToolOrderCodec.KEY,"[\"102\"]")&&!values.containsKey(ToolOrderCodec.KEY),"failed first save rolls back in-memory mutation to absent");
        values.put(ToolOrderCodec.KEY,"[\"105\"]");fail[0]=true;
        check(!ToolOrderCodec.persist(prefs,ToolOrderCodec.KEY,"[\"102\"]")&&values.get(ToolOrderCodec.KEY).equals("[\"105\"]"),"failed commit restores last successful saved order");
        check(ToolOrderCodec.persist(prefs,ToolOrderCodec.KEY,"[\"102\"]")&&values.get(ToolOrderCodec.KEY).equals("[\"102\"]"),"successful commit read back");
        homeCanvasStorage();
        homeMoreDomShape();
        homeMoreSetting();
        System.out.println("PASS "+checks+" tool ordering assertions");
    }
}
