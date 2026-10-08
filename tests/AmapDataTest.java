package io.github.ldxm666.mapadkiller;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs with Android's real org.json through app_process, without private user data. */
public final class AmapDataTest {
    private static int assertions;
    private static class Options implements AmapData.Settings {
        final Map<String, Boolean> values = new HashMap<String, Boolean>();
        public boolean enabled(String key) { Boolean v = values.get(key); return v == null || v; }
        Options off(String key) { values.put(key, false); return this; }
    }
    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
    private static JSONObject frame(String name, boolean done) throws Exception {
        return new JSONObject().put("is_done", done).put("has_more", true).put("session_id", "fixture")
            .put("component", new JSONObject().put("type", "container")
                .put("extra_info", new JSONObject().put("cardName", name)));
    }
    public static void main(String[] args) throws Exception {
        Options all = new Options();
        JSONArray tools = new JSONArray();
        for (String[] entry : AmapData.TOOLS)
            tools.put(new JSONObject().put("id", Integer.parseInt(entry[0])).put("schema", "action:" + entry[0]).put("name", "changed display text"));
        tools.put(new JSONObject().put("id", 9999).put("schema", "action:unknown"));
        String raw = new JSONObject().put("recommendTools", tools).put("recommendInfo", new JSONObject().put("version", 17)).toString();
        check(raw.equals(AmapData.storage("recommendTools", raw, all)), "all visible is byte preserving");
        for (String[] entry : AmapData.TOOLS) {
            Options hidden = new Options().off(Config.K_TOOL_PREFIX + entry[1]);
            String result = AmapData.storage("recommendTools", raw, hidden);
            JSONArray kept = new JSONObject(result).getJSONArray("recommendTools");
            check(kept.length() == tools.length() - 1, "one ID maps to one switch " + entry[0]);
            for (int i = 0; i < kept.length(); i++) {
                JSONObject tool = kept.getJSONObject(i);
                check(!entry[0].equals(tool.optString("id")), "hidden ID absent");
                check(tool.getString("schema").equals("9999".equals(tool.optString("id")) ? "action:unknown" : "action:" + tool.optString("id")), "actions stay paired");
            }
            check(result.equals(AmapData.storage("recommendTools", result, hidden)), "idempotent filtering");
            check(raw.equals(AmapData.storage("recommendTools", raw, all)), "restore uses untouched original");
        }
        check(raw.equals(AmapData.storage("unrelated", raw, new Options().off(Config.K_TOOLS_VISIBLE))), "unrelated storage untouched");
        JSONObject template = new JSONObject().put("king_switch", "1");
        for (int i = 0; i < 4; i++) {
            String id = AmapData.TOOLS[i][0];
            template.put("id" + i, id).put("schema" + i, "action:" + id)
                .put("imageToken" + i, "icon:" + id).put("itemDataForClick" + i, "click:" + id).put("display" + i, "flex");
        }
        JSONObject compact = AmapData.toolbox(template, new Options().off("tool_公交地铁").off("tool_租车"));
        check("106".equals(compact.getString("id1")), "native slots compact in order");
        check("action:106".equals(compact.getString("schema1")) && "click:106".equals(compact.getString("itemDataForClick1")), "native click payload follows ID");
        check("icon:106".equals(compact.getString("imageToken1")), "native icon follows ID");
        check("none".equals(compact.getString("display2")) && "".equals(compact.getString("schema2")), "tail slots have no action");
        check("103".equals(template.getString("id1")), "cached original not mutated");
        check("0".equals(AmapData.toolbox(template, new Options().off(Config.K_TOOLS_VISIBLE)).getString("king_switch")), "all hidden turns native panel off");
        Options noFeed = new Options().off(Config.K_FEED_BOARD).off(Config.K_FEED_CONTENT);
        check(AmapData.stream(frame("tipsRanking", false), noFeed) == null, "paged rank card dropped");
        check(AmapData.stream(frame("contentCenterFeed", false), noFeed) == null, "content loader dropped");
        check(AmapData.stream(frame("taxiQS", false), all) == null, "inverted ride switch respected");
        JSONObject navigation = frame("navContinuation", false);
        check(AmapData.stream(navigation, noFeed) == navigation, "navigation continuation preserved");
        JSONObject unknown = frame("futureCard", false);
        check(AmapData.stream(unknown, noFeed) == unknown, "unknown protocol passes through");
        JSONObject terminal = frame("tipsRanking", true);
        JSONObject finished = AmapData.stream(terminal, noFeed);
        check(finished.getBoolean("is_done") && !finished.getBoolean("has_more"), "terminal closes loading and pagination");
        check("fixture".equals(finished.getString("session_id")), "terminal metadata preserved");
        check(terminal.getBoolean("has_more"), "stream source not mutated");
        check(AmapData.stream(frame("hourlyWeather", false), new Options().off(Config.K_FEED_WEATHER)) == null, "weather ID filtered");
        check(AmapData.stream(frame("frequentLocations", false), new Options().off(Config.K_HOME_CHIPS)) == null, "location chip ID filtered");
        check("/ws/promote/main-page/assets".equals(AmapData.requestPath("$aos.m5$/ws/promote/main-page/assets?scene=1")), "query stripped exactly");
        check(!AmapData.handles("/ws/shield/search/poi/detail"), "POI endpoint excluded");
        String promo = "{\"code\":\"1\",\"data\":[{\"campaign\":7}],\"result\":true}";
        JSONObject stripped = new JSONObject(AmapData.response("/ws/promote/main-page/assets", promo, new Options().off(Config.K_FEED_FESTIVAL)));
        check(stripped.getJSONArray("data").length() == 0 && stripped.getBoolean("result"), "promotion payload emptied, status retained");
        check(promo.equals(AmapData.response("/ws/promote/main-page/assets", promo, all)), "promotion toggle restores response");
        JSONArray mine = new JSONArray().put(new JSONObject().put("dataType", "MineMemberRecommendTaskCardV2"))
            .put(new JSONObject().put("dataType", "MineNewFootprintCard"))
            .put(new JSONObject().put("dataType", "MineNewBEntranceCardV4").put("content", new JSONObject().put("entranceList_1", new JSONArray().put(7)).put("entranceList_2", new JSONArray().put(1))));
        String mineRaw = new JSONObject().put("code", "1").put("data", new JSONObject().put("nickname", "fixture").put("cardList", mine)).toString();
        JSONObject mineOut = new JSONObject(AmapData.response("/ws/shield/dsp/profile/index/nodefaasv3", mineRaw,
            new Options().off(Config.K_MY_TASK).off(Config.K_MY_ORDER_ROW)));
        JSONArray mineCards = mineOut.getJSONObject("data").getJSONArray("cardList");
        check(mineCards.length() == 2, "mine task only removed");
        check(mineCards.getJSONObject(1).getJSONObject("content").getJSONArray("entranceList_1").length() == 0, "order row switch isolated");
        check(mineCards.getJSONObject(1).getJSONObject("content").getJSONArray("entranceList_2").length() == 1, "service row retained");
        check(mine.getJSONObject(2).getJSONObject("content").getJSONArray("entranceList_1").length() == 1, "mine input unmodified");
        System.out.println("PASS " + assertions + " assertions on Android org.json");
    }
}
