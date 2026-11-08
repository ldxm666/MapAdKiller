package io.github.ldxm666.mapadkiller;

import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sanitized ResourceAutoRequest fixtures: remove only the taxi home marketing skin. */
public final class AmapTaxiSkinTest {
    private static int checks;
    private static final String PATH = AmapTaxiData.CONTENT_INFO;
    private static final AmapData.Settings ALL = new AmapData.Settings() {
        public boolean enabled(String key) { return true; }
        public boolean selected(String key) { return true; }
    };
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static JSONObject params() throws Exception {
        return new JSONObject().put("pageId", "homePage").put("typeId", "Passenger")
            .put("gdServiceId", 1).put("resources", "skin,securityTips,complianceLinks")
            .put("ossNode", "Amap").put("amapOrderId", "").put("curAmapOrderId", "").put("travelId", "");
    }
    private static String form(JSONObject data) throws Exception {
        StringBuilder out = new StringBuilder();
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(key, "UTF-8")).append('=')
                .append(URLEncoder.encode(data.get(key).toString(), "UTF-8"));
        }
        return out.toString();
    }
    private static boolean scope(Object body) throws Exception {
        return AmapTaxiData.isHomeRequest(new JSONObject().put("url", "$aos.m5zb$" + PATH).put("body", body));
    }
    private static JSONObject item() throws Exception {
        return new JSONObject().put("uiSchemaId", "fixture-skin").put("biz", new JSONObject())
            .put("protocol", new JSONObject().put("skin_list", new JSONArray().put(new JSONObject()
                .put("template", "fixture-banner").put("title", "fixture-marketing")
                .put("action_url", "fixture:promotion").put("weight", 1))))
            .put("children", new JSONArray());
    }
    private static JSONObject payload() throws Exception {
        JSONObject resources = new JSONObject().put("skin", new JSONObject().put("handleType", 0)
            .put("dataList", new JSONArray().put(item())).put("uiSchemaMap", new JSONObject().put("fixture-skin", "fixture-schema")));
        for (String area : new String[]{"securityTips", "complianceLinks", "toolBar", "toolMapBar", "popup", "beforeOrderPopup"}) {
            // Matching skin_list data outside the marketing skin must remain intact.
            resources.put(area, new JSONObject().put("dataList", new JSONArray().put(item())));
        }
        return new JSONObject().put("code", 1).put("timestamp", 20261108).put("data", new JSONObject()
            .put("lubanData", resources).put("matrixData", new JSONObject().put("skin", item()))
            .put("address", "fixture-address").put("price", 123).put("order", new JSONObject().put("id", "fixture-order")));
    }
    private static JSONArray entries(JSONObject root) throws Exception {
        return root.getJSONObject("data").getJSONObject("lubanData").getJSONObject("skin").getJSONArray("dataList");
    }
    public static void main(String[] args) throws Exception {
        check(AmapTaxiData.handles(PATH), "shared endpoint recognized for scoped filtering");
        check(scope(params()), "JSONObject passenger taxi home request");
        check(scope(params().toString()), "JSON string passenger taxi home request");
        check(scope(form(params())), "actual form-encoded request");
        check(scope(params().put("pageId", "tabHomePage")), "tab taxi home request");
        check(scope(params().put("position", "9001")), "native home position");
        check(AmapTaxiData.isHomeRequest(new JSONObject().put("url", "https://m5.amap.com" + PATH + "?" + form(params()))), "query parameters supported");
        for (String page : new String[]{"", "103", "104", "108", "carListPage", "tabCarListPage", "personalPage"}) {
            check(!scope(params().put("pageId", page)), "nonhome page excluded: " + page);
        }
        check(!scope(params().put("typeId", "Driver")), "driver resources excluded");
        for (int service : new int[]{2, 6, 8, 10, 19}) {
            check(!scope(params().put("gdServiceId", service)), "other service excluded: " + service);
        }
        for (String key : new String[]{"amapOrderId", "orderId", "order_id", "curAmapOrderId", "travelId"}) {
            check(!scope(params().put(key, "fixture-order")), "order/travel context excluded: " + key);
        }
        check(!scope(params().put("position", "9103")), "conflicting order position excluded");
        check(!scope(params().put("ossNode", "AmapHitchDriver")), "other node excluded");
        check(!scope(params().put("resources", "securityTips,complianceLinks")), "safety-only request excluded");
        check(!scope(new JSONObject().put("position", "9001")), "home-like position alone cannot select shared endpoint");
        check(!AmapTaxiData.isHomeRequest(new JSONObject().put("url", "https://m5.amap.com" + PATH + "?pageId=103")
            .put("body", params())), "body cannot overwrite URL page scope");
        JSONObject source = payload();
        String raw = source.toString();
        String filtered = AmapTaxiData.response(PATH, raw, ALL);
        JSONObject result = new JSONObject(filtered);
        check(entries(result).length() == 0, "top banner removed even when other promotions are allowed");
        check(result.getInt("timestamp") == source.getInt("timestamp"), "metadata retained");
        JSONObject originalData = source.getJSONObject("data"), data = result.getJSONObject("data");
        for (String key : new String[]{"matrixData", "address", "price", "order"}) {
            check(originalData.get(key).toString().equals(data.get(key).toString()), "core data retained: " + key);
        }
        JSONObject before = originalData.getJSONObject("lubanData"), after = data.getJSONObject("lubanData");
        for (String area : new String[]{"securityTips", "complianceLinks", "toolBar", "toolMapBar", "popup", "beforeOrderPopup"}) {
            check(before.get(area).toString().equals(after.get(area).toString()), "native resource retained: " + area);
        }
        check(before.getJSONObject("skin").get("uiSchemaMap").toString().equals(after.getJSONObject("skin").get("uiSchemaMap").toString()), "skin schema retained");
        check(filtered.equals(AmapTaxiData.response(PATH, filtered, ALL)), "idempotent removal");
        JSONObject future = new JSONObject().put("protocol", new JSONObject().put("future", "fixture"));
        JSONObject mixed = payload(); entries(mixed).put(future).put(JSONObject.NULL);
        JSONArray kept = entries(new JSONObject(AmapTaxiData.response(PATH, mixed.toString(), ALL)));
        check(kept.length() == 2 && future.toString().equals(kept.getJSONObject(0).toString()) && kept.isNull(1), "unknown skin entries preserved");
        JSONObject unknown = payload(); unknown.getJSONObject("data").getJSONObject("lubanData").getJSONObject("skin")
            .put("dataList", new JSONArray().put(future));
        check(unknown.toString().equals(AmapTaxiData.response(PATH, unknown.toString(), ALL)), "unknown skin schema untouched");
        for (String invalid : new String[]{"not json", "[]", "{}", "{\"data\":{\"lubanData\":{}}}",
                payload().put("code", 0).toString(), "{\"code\":1,\"data\":{\"lubanData\":{\"skin\":{\"dataList\":{}}}}}"}) {
            check(invalid.equals(AmapTaxiData.response(PATH, invalid, ALL)), "failed or malformed payload untouched");
        }
        check(raw.equals(AmapTaxiData.response(PATH + "/other", raw, ALL)), "no endpoint prefix filtering");
        String report = AmapTaxiData.summary(PATH, filtered);
        check(report.contains("topBanners=0") && report.contains("securityItems=1") && report.contains("complianceItems=1"), "counts verify targeted removal");
        check(!report.contains("fixture") && !report.contains("action_url"), "diagnostics contain no payload values");
        System.out.println("PASS " + checks + " taxi home skin assertions");
    }
}
