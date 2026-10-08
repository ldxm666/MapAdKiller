package io.github.ldxm666.mapadkiller;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Version 17 protocol adapter: fixed paths and stable identifiers, never display text. */
public final class AmapData {
    public interface Settings { boolean enabled(String key); }
    public static final Settings CURRENT = new Settings() {
        @Override public boolean enabled(String key) { return Config.visible(key); }
    };
    public static final String[][] TOOLS = {
        {"102", "驾车"}, {"103", "公交地铁"}, {"168", "租车"}, {"106", "打车"},
        {"317", "订酒店"}, {"327", "火车票"}, {"469", "顺风车"}, {"483", "高德扫街"},
        {"461", "高德出行节"}, {"362", "代驾"}, {"418", "秒送"}, {"151", "实时公交"},
        {"380", "旅游度假"}, {"116", "车主服务"}, {"113", "景点游玩"}, {"115", "离线地图"}, {"86", "更多工具"}
    };
    private static final String[] SLOT_FIELDS = {
        "id", "schema", "name", "size", "containerClass", "isLottie", "displayLottie",
        "displayImage", "imageUrlRender", "lottieUrlRender", "imageClass", "itemDataForClick",
        "display", "imageToken", "moreItem", "label", "tips", "redDot", "badge"
    };
    private AmapData() {}

    public static boolean toolVisible(String id, Settings s) {
        if (!s.enabled(Config.K_TOOLS_VISIBLE)) return false;
        for (String[] entry : TOOLS)
            if (entry[0].equals(id)) return s.enabled(Config.K_TOOL_PREFIX + entry[1]);
        return true;
    }

    public static String storage(String key, String raw, Settings s) throws Exception {
        if (raw == null || raw.length() == 0) return raw;
        if ("local_tool_list_for_xml_v2".equals(key)) return toolbox(new JSONObject(raw), s).toString();
        if (!"recommendTools".equals(key) && !"aosRecommendTools".equals(key)
                && !"choiceTools".equals(key)) return raw;
        JSONObject out = new JSONObject(raw);
        String listKey = "choiceTools".equals(key) ? "choiceTools" : "recommendTools";
        JSONArray source = out.optJSONArray(listKey);
        if (source == null) return raw;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject tool = source.optJSONObject(i);
            if (tool == null || toolVisible(tool.optString("id"), s)) kept.put(source.opt(i));
        }
        if (kept.length() == source.length()) return raw;
        out.put(listKey, kept);
        return out.toString();
    }

    /** Native first frame: move the complete slot, including its action and icon. */
    public static JSONObject toolbox(JSONObject source, Settings s) throws Exception {
        List<Integer> kept = new ArrayList<Integer>();
        int count = 0;
        for (; count < 10 && source.has("id" + count); count++)
            if (toolVisible(source.optString("id" + count), s)) kept.add(count);
        if (count == kept.size()) return source;
        JSONObject out = new JSONObject(source.toString());
        for (int i = 0; i < count; i++) {
            for (String field : SLOT_FIELDS) out.remove(field + i);
            if (i < kept.size()) {
                int from = kept.get(i);
                for (String field : SLOT_FIELDS) {
                    String key = field + from;
                    if (source.has(key)) out.put(field + i, source.opt(key));
                }
                out.put("display" + i, "flex");
            } else {
                out.put("display" + i, "none");
                out.put("id" + i, "");
                out.put("schema" + i, "");
                out.put("itemDataForClick" + i, "");
            }
        }
        if (kept.isEmpty()) out.put("king_switch", "0");
        return out;
    }

    /** Null drops only nonterminal cards; completion envelopes always reach the page. */
    public static JSONObject stream(JSONObject source, Settings s) throws Exception {
        JSONObject component = source.optJSONObject("component");
        if (component == null) return source;
        JSONObject extra = component.optJSONObject("extra_info");
        String name = extra == null ? "" : extra.optString("cardName");
        boolean hide = "tipsRanking".equals(name) && !s.enabled(Config.K_FEED_BOARD)
            || "hourlyWeather".equals(name) && !s.enabled(Config.K_FEED_WEATHER)
            || "frequentLocations".equals(name) && !s.enabled(Config.K_HOME_CHIPS)
            || ("taxiQS".equals(name) || "taxiQSMarket".equals(name)) && s.enabled(Config.K_RIDE_CARD_OFF)
            || "contentCenterFeed".equals(name) && !s.enabled(Config.K_FEED_CONTENT);
        boolean terminal = source.optBoolean("is_done");
        if (hide && !terminal) return null;
        boolean stopPaging = terminal && !s.enabled(Config.K_FEED_BOARD) && !s.enabled(Config.K_FEED_CONTENT);
        if (!hide && !stopPaging) return source;
        JSONObject out = new JSONObject(source.toString());
        if (hide) {
            JSONObject empty = new JSONObject();
            empty.put("type", "markdown"); empty.put("text", ""); empty.put("is_done", true);
            out.put("component", empty);
        }
        if (stopPaging) out.put("has_more", false);
        return out;
    }

    public static String requestPath(String url) {
        if (url == null) return "";
        int begin = url.indexOf("/ws/");
        if (begin < 0) return "";
        int end = url.indexOf('?', begin);
        return end < 0 ? url.substring(begin) : url.substring(begin, end);
    }
    public static boolean handles(String path) {
        return "/ws/promote/main-page/assets".equals(path)
            || "/ws/shield/dsp/profile/index/nodefaasv3".equals(path);
    }

    public static String response(String path, String raw, Settings s) throws Exception {
        if (raw == null || raw.length() == 0) return raw;
        JSONObject out = new JSONObject(raw);
        if ("/ws/promote/main-page/assets".equals(path)) {
            if (s.enabled(Config.K_FEED_FESTIVAL) || !out.has("data")) return raw;
            Object data = out.opt("data");
            if (data instanceof JSONArray) out.put("data", new JSONArray());
            else if (data instanceof JSONObject) out.put("data", new JSONObject());
            else return raw;
            return out.toString();
        }
        if (!"/ws/shield/dsp/profile/index/nodefaasv3".equals(path)) return raw;
        JSONObject data = out.optJSONObject("data");
        JSONArray cards = data == null ? null : data.optJSONArray("cardList");
        if (cards == null) return raw;
        JSONArray kept = new JSONArray();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) { kept.put(cards.opt(i)); continue; }
            String type = card.optString("dataType");
            if (("MineRowSlideCard".equals(type) || "UserCircleCardV3".equals(type))
                    && !s.enabled(Config.K_MY_FRIENDS)) continue;
            if ("MineMemberRecommendTaskCardV2".equals(type) && !s.enabled(Config.K_MY_TASK)) continue;
            if (("PopularActivitiesCardV4".equals(type) || "PopularActivitiesCardV5".equals(type))
                    && !s.enabled(Config.K_MY_PROMO_ROW)) continue;
            if ("ContentCenterFeedCard".equals(type) && !s.enabled(Config.K_MY_GUESS)) continue;
            if ("MineNewBEntranceCardV3".equals(type) || "MineNewBEntranceCardV4".equals(type)) {
                JSONObject content = card.optJSONObject("content");
                if (content != null) {
                    if (!s.enabled(Config.K_MY_ORDER_ROW)) content.put("entranceList_1", new JSONArray());
                    if (!s.enabled(Config.K_MY_SERVICE_ROW)) content.put("entranceList_2", new JSONArray());
                    JSONArray one = content.optJSONArray("entranceList_1");
                    JSONArray two = content.optJSONArray("entranceList_2");
                    if (one != null && two != null && one.length() == 0 && two.length() == 0) continue;
                }
            }
            kept.put(card);
        }
        data.put("cardList", kept);
        return out.toString();
    }
}
