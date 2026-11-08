package io.github.ldxm666.mapadkiller;

import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android org.json tests for the 17.02 native taxi resource protocol, with no user data. */
public final class AmapTaxiDataTest {
    private static int assertions;
    private static final String[] KEYS = {Config.K_TAXI_ACTIVITY, Config.K_TAXI_COUPON,
        Config.K_TAXI_AD, Config.K_TAXI_OPERATION, Config.K_TAXI_TASK,
        Config.K_TAXI_VIP, Config.K_TAXI_MILEAGE};
    private static final String[] TYPES = {"activity", "union", "coupon", "ad", "operation", "task",
        "newcomer", "vip", "vip_exchange"};
    private static final int[] KEY_INDEX = {0, 0, 1, 2, 3, 4, 4, 5, 6};
    private static class Options implements AmapData.Settings {
        final Map<String, Boolean> values = new HashMap<>();
        Options(int mask) {
            for (int i = 0; i < KEYS.length; i++) values.put(KEYS[i], (mask & (1 << i)) != 0);
        }
        public boolean enabled(String key) { return Boolean.TRUE.equals(values.get(key)); }
        public boolean selected(String key) { return enabled(key); }
    }
    private static void check(boolean result, String name) {
        assertions++;
        if (!result) throw new AssertionError(name);
    }
    private static boolean scope(String path, Object body) throws Exception {
        return AmapTaxiData.isHomeRequest(new JSONObject().put("url", "https://m5.amap.com" + path)
            .put("body", body));
    }
    /** Sanitized structure observed in the 17.02 merged home response, all values synthetic. */
    private static JSONObject union() throws Exception {
        return new JSONObject().put("banner_type", "union").put("new_style", 1)
            .put("able_forward", true).put("callback_url", "fixture:callback")
            .put("dataList", new JSONArray().put(new JSONObject().put("uiSchemaId", "fixture-union")
                .put("protocol", new JSONObject().put("show_info", new JSONArray()
                    .put(new JSONObject().put("banner_id", "fixture-promo-one").put("ad_flag", true)
                        .put("action_url", "fixture:activity-one").put("background", "fixture:image-one")
                        .put("union_callback", new JSONArray().put("fixture:expose-one")))
                    .put(new JSONObject().put("banner_id", "fixture-promo-two")
                        .put("action_url", "fixture:activity-two"))))))
            .put("uiSchemaMap", new JSONObject().put("fixture-union", new JSONObject().put("type", "fixture")));
    }
    private static JSONObject data() throws Exception {
        JSONArray banners = new JSONArray();
        for (String type : TYPES) banners.put("union".equals(type) ? union() :
            new JSONObject().put("banner_type", type)
                .put("show_info", new JSONArray().put(new JSONObject().put("banner_id", type + "-native")
                    .put("title", "server text can change").put("scheme", "fixture:" + type))));
        banners.put(new JSONObject().put("banner_type", "future_type").put("native", true));
        banners.put(new JSONObject().put("banner_type", "welfare"));
        banners.put(new JSONObject().put("title", "活动专区")); // text alone is never an identifier
        banners.put(JSONObject.NULL).put(73);
        return new JSONObject().put("banner", banners).put("config", new JSONObject().put("first", new JSONArray(Arrays.asList(TYPES))))
            .put("icon", new JSONArray().put(new JSONObject().put("type", "order")))
            .put("popup", new JSONObject().put("type", "safety"))
            .put("address", "fixture address").put("order", new JSONObject()
                .put("banner", new JSONArray().put(new JSONObject().put("banner_type", "ad")))
                .put("order_id", "fixture-order").put("price", 99));
    }
    public static void main(String[] args) throws Exception {
        String path = AmapTaxiData.RESOURCE;
        check(AmapTaxiData.handles(path), "current resource endpoint");
        check(AmapTaxiData.handles(AmapTaxiData.HOME), "current merged endpoint");
        check(AmapTaxiData.handles(AmapTaxiData.LEGACY), "legacy resource endpoint");
        check(!AmapTaxiData.handles("/ws/boss/order/car/create"), "order endpoint excluded");
        check(!AmapTaxiData.handles(path + "/other"), "no prefix match");
        for (String position : new String[]{"9001", "9002", "9020"}) {
            String form = "node=Amap&position=" + URLEncoder.encode("[" + position + "]", "UTF-8") + "&resourceType=banner%2Cconfig";
            check(scope(path, form), "form body home scope " + position);
            check(scope(path, new JSONObject().put("position", new JSONArray().put(Integer.parseInt(position)))), "object body " + position);
            check(scope(path, new JSONObject().put("position", position).toString()), "JSON string body " + position);
            check(AmapTaxiData.isHomeRequest(new JSONObject().put("url", "https://m5.amap.com" + path + "?" + form)), "query scope " + position);
        }
        check(scope(AmapTaxiData.HOME, "position=%5B9020%5D&dsp_svrctlin=banner%2Cother"), "merged request scope");
        check(scope(path, "position=%5B9001%2C9020%5D&node=Amap"), "pure home batch scope");
        for (String body : new String[]{"", "node=Amap", "position=[]", "position=null", "position=9004",
                "position=9014", "position=9103", "position=9202", "position=9001&node=AmapHitchDriver",
                "position=%5B9020%2C9103%5D", "position=9001&orderId=fixture", "position=9001&amapOrderId=fixture",
                "position=9001&order_id=fixture", "position=9001&position=9103", "position=%XX"}) {
            check(!scope(path, body), "nonhome or ambiguous request untouched: " + body);
        }
        check(!scope("/ws/boss/order/car/create", "position=9001"), "home-like parameter cannot select order API");
        check(!scope(path, new JSONObject().put("position", new JSONArray().put(JSONObject.NULL))), "malformed position rejected");
        for (String key : new String[]{"position", "node", "amapOrderId", "orderId", "order_id"}) {
            String query = "position".equals(key) ? "position=9103" : "position=9001&" + key + "=fixture-order";
            JSONObject body = new JSONObject().put(key, "position".equals(key) ? 9001 : "node".equals(key) ? "Amap" : "");
            check(!AmapTaxiData.isHomeRequest(new JSONObject().put("url", "https://m5.amap.com" + path + "?" + query)
                .put("body", body)), "scope cannot be overwritten across query and body: " + key);
        }
        for (String key : KEYS) check(!Config.defaultVisible(key), "new promotion controls default hidden: " + key);
        check(Config.defaultVisible(Config.K_MY_ORDER_ROW), "existing order setting retains default");
        JSONObject sourceData = data();
        String raw = new JSONObject().put("code", 1).put("timestamp", 20261108).put("data", sourceData).toString();
        String merged = new JSONObject().put("code", 1).put("data", new JSONObject()
            .put("banner", new JSONObject(raw)).put("other", new JSONObject().put("code", 1)
                .put("data", new JSONObject().put("icon", new JSONArray().put("native order entry"))))) .toString();
        Options all = new Options(127);
        check(raw.equals(AmapTaxiData.response(path, raw, all)), "all enabled preserves bytes");
        check(merged.equals(AmapTaxiData.response(AmapTaxiData.HOME, merged, all)), "merged all enabled preserves bytes");
        for (int mask = 0; mask < 128; mask++) {
            Options settings = new Options(mask);
            String filtered = AmapTaxiData.response(path, raw, settings);
            JSONObject out = new JSONObject(filtered);
            JSONObject result = out.getJSONObject("data");
            JSONArray kept = result.getJSONArray("banner");
            int expected = 5;
            for (int i = 0; i < TYPES.length; i++) {
                boolean wanted = (mask & (1 << KEY_INDEX[i])) != 0;
                boolean found = false;
                for (int j = 0; j < kept.length(); j++) {
                    JSONObject item = kept.optJSONObject(j);
                    if (item != null && TYPES[i].equals(item.optString("banner_type"))) {
                        found = true;
                        check(sourceData.getJSONArray("banner").getJSONObject(i).toString().equals(item.toString()),
                            "native actions, schema and card data stay paired: " + TYPES[i]);
                    }
                }
                check(found == wanted, "independent key " + TYPES[i] + " mask=" + mask);
                if (wanted) expected++;
            }
            check(kept.length() == expected, "only requested native types removed mask=" + mask);
            for (String key : new String[]{"config", "icon", "popup", "address", "order"}) {
                check(sourceData.get(key).toString().equals(result.get(key).toString()), "core field intact: " + key);
            }
            check(out.getInt("timestamp") == 20261108, "timestamp unchanged");
            check(filtered.equals(AmapTaxiData.response(path, filtered, settings)), "idempotent");
            JSONObject mergedOut = new JSONObject(AmapTaxiData.response(AmapTaxiData.HOME, merged, settings));
            check(kept.toString().equals(mergedOut.getJSONObject("data").getJSONObject("banner").getJSONObject("data").getJSONArray("banner").toString()), "merged path same result");
            check(new JSONObject(merged).getJSONObject("data").getJSONObject("other").toString().equals(mergedOut.getJSONObject("data").getJSONObject("other").toString()), "merged nonbanner service data intact");
        }
        for (String invalid : new String[]{"not json", "[]", "null", "{}", "{\"code\":0,\"data\":{\"banner\":[]}}",
                "{\"code\":1,\"data\":{\"banner\":{\"banner_type\":\"ad\"}}}"}) {
            check(invalid.equals(AmapTaxiData.response(path, invalid, new Options(0))), "failed or unknown schema unchanged");
        }
        check(raw.equals(AmapTaxiData.response("/ws/boss/order/car/create", raw, new Options(0))), "no filtering outside allowlist");
        check(AmapTaxiData.keyFor("coupon").equals(Config.K_TAXI_COUPON), "coupon is its own key");
        check(AmapTaxiData.keyFor("union").equals(Config.K_TAXI_ACTIVITY), "17.02 union activity uses the activity switch");
        check(AmapTaxiData.keyFor("future") == null, "unknown future resource preserved");
        check(!Arrays.asList(KEYS).contains(Config.K_TAXI_CARD), "home taxi card is independent");
        String summary = AmapTaxiData.summary(AmapTaxiData.HOME, merged);
        check(summary.contains("union:1") && summary.contains("unionCards=2"), "safe diagnostics recognize native union structure");
        check(summary.contains("unknown=5"), "unknown or malformed resources counted without logging values");
        check(!summary.contains("fixture") && !summary.contains("活动专区") && !summary.contains("action_url"), "diagnostics omit payload data");
        check(!AmapTaxiData.summary(path, AmapTaxiData.response(path, raw, new Options(0))).contains("union:"), "disabled union absent from diagnostic summary");
        for (String invalid : new String[]{"not json", "[]", "{}", "{\"code\":0,\"data\":{\"banner\":[]}}"}) {
            check("unavailable".equals(AmapTaxiData.summary(path, invalid)), "diagnostics tolerate invalid payload");
        }
        String nestedFailure = new JSONObject(merged).getJSONObject("data").put("banner", new JSONObject(raw).put("code", 0)).toString();
        String failedMerged = new JSONObject().put("code", 1).put("data", new JSONObject(nestedFailure)).toString();
        check(failedMerged.equals(AmapTaxiData.response(AmapTaxiData.HOME, failedMerged, new Options(0))), "failed merged banner service untouched");
        System.out.println("PASS " + assertions + " taxi protocol assertions");
    }
}
