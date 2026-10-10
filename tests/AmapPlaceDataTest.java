package io.github.ldxm666.mapadkiller;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the actual POI-detail protocol without map coordinates or account data. */
public final class AmapPlaceDataTest {
    private static final String FEED = "poiDetailWaterFeed";
    private static final String TITLE = "poiDetailWaterFeedTitle";
    private static final String QUICK = "poiDetailNoPoiSurroundQuickLink";
    private static int assertions;

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static JSONArray array(Object... values) {
        JSONArray result = new JSONArray();
        for (Object value : values) result.put(value);
        return result;
    }

    private static JSONObject fixture() throws Exception {
        JSONObject modules = new JSONObject()
            .put(TITLE, new JSONObject().put("card_id", "title_17")
                .put("data", new JSONObject().put("title", "Nearby recommendations")))
            .put(FEED, new JSONObject().put("card_id", "feed_17")
                .put("data", new JSONObject().put("total", 2).put("hasMore", 1)
                    .put("list", array(new JSONObject().put("image", "fixture:photo")))))
            .put(QUICK, new JSONObject().put("card_id", "quick_17")
                .put("data", new JSONObject().put("list", array("parking", "restroom"))))
            .put("poiDetailBaseInfo", new JSONObject().put("card_id", "base_17")
                .put("data", new JSONObject().put("name", "Fixture street")
                    .put("address", "Address containing " + FEED)
                    .put("route", new JSONObject().put("destination", "fixture:point")
                        .put("walking", true).put("driving", true))))
            .put("unrecognizedFutureModule", new JSONObject().put("card_id", "future_17")
                .put("data", new JSONObject().put("label", TITLE)));
        JSONObject opaque = new JSONObject().put("literal", FEED)
            .put("items", array(FEED, "feed_17"));
        JSONObject regions = new JSONObject()
            .put("listContent", array("base_17", TITLE, "feed_17", QUICK, "quick_17", "future_17"))
            .put("feedContent", array("title_17", FEED, "feed_17", TITLE, "base_17", "feed_17"))
            .put("other", array(FEED, "future_17", "feed_17"))
            .put("widget", array("base_17", "quick_17"))
            .put("futureRegion", array("title_17", "future_17", "base_17"))
            .put("opaqueValues", array(opaque, array(FEED), 7, JSONObject.NULL))
            .put("metadata", new JSONObject().put("literal", FEED));
        JSONObject data = new JSONObject().put("modules", modules).put("regions", regions)
            .put("meta", new JSONObject().put("poi_detail_water_feed_switch", "1")
                .put("next_page_index", 2).put("vo_meta_info", new JSONObject().put("poiInfo", new JSONObject())))
            .put("req_meta", new JSONObject().put("from", "fixture"))
            .put("template_id", "fixture-template");
        return new JSONObject().put("success", true).put("code", 200)
            .put("page", "poi").put("data", data)
            .put("geographic", new JSONObject().put("city", "Fixture city").put("street", "Fixture street"));
    }

    private static String hide(String raw) {
        return AmapPlaceData.response(AmapPlaceData.DETAIL_PATH, raw, false);
    }

    private static void unchanged(String raw, String description) {
        String result = hide(raw);
        check(raw == null ? result == null : raw.equals(result), description);
    }

    public static void main(String[] args) throws Exception {
        JSONObject input = fixture();
        String raw = input.toString();
        String output = hide(raw);
        check(!raw.equals(output), "confirmed recommendations are removed");
        check(input.toString().equals(raw), "source model is untouched for future visible loads");
        JSONObject filtered = new JSONObject(output);
        JSONObject originalData = input.getJSONObject("data");
        JSONObject data = filtered.getJSONObject("data");
        JSONObject modules = data.getJSONObject("modules");
        check(!modules.has(FEED) && !modules.has(TITLE), "waterfall title and photo cards both disappear");
        check(modules.length() == 3, "all three unrelated modules remain");
        for (String name : new String[] { QUICK, "poiDetailBaseInfo", "unrecognizedFutureModule" }) {
            check(modules.getJSONObject(name).toString().equals(originalData.getJSONObject("modules").getJSONObject(name).toString()),
                "unrelated module and actions unchanged: " + name);
        }
        JSONObject regions = data.getJSONObject("regions");
        check(regions.getJSONArray("listContent").toString().equals(array("base_17", QUICK, "quick_17", "future_17").toString()),
            "list compacts without losing quick tools or unknown modules");
        check(regions.getJSONArray("feedContent").toString().equals(array("base_17").toString()),
            "all duplicate module and card references are removed");
        check(regions.getJSONArray("other").toString().equals(array("future_17").toString()), "other direct references compact");
        check(regions.getJSONArray("futureRegion").toString().equals(array("future_17", "base_17").toString()), "new region names retain unrelated entries");
        for (String name : new String[] { "widget", "opaqueValues", "metadata" }) {
            check(regions.get(name).toString().equals(originalData.getJSONObject("regions").get(name).toString()),
                "non-reference values are never scanned: " + name);
        }
        check(data.getJSONObject("meta").toString().equals(originalData.getJSONObject("meta").toString()), "unverified pagination and metadata stay original");
        check(data.getJSONObject("req_meta").toString().equals(originalData.getJSONObject("req_meta").toString()), "request metadata preserved");
        check(filtered.getJSONObject("geographic").toString().equals(input.getJSONObject("geographic").toString()), "geographic information preserved");
        check(filtered.getBoolean("success") && filtered.getInt("code") == 200, "response status unchanged");
        check(output.equals(hide(output)), "a filtered response is idempotent");
        for (int i = 0; i < 40; i++) {
            check(output.equals(hide(raw)), "server refresh produces the same clean layout " + i);
            check(raw.equals(AmapPlaceData.response(AmapPlaceData.DETAIL_PATH, raw, true)), "switch restores untouched server data " + i);
        }
        check(raw.equals(AmapPlaceData.response("/ws/search/geo", raw, false)), "reverse geocoding response unaffected");
        check(raw.equals(AmapPlaceData.response("/ws/shield/search/poi/detailExtra", raw, false)), "similar endpoint is not treated as detail");
        check(raw.equals(AmapPlaceData.response(null, raw, false)), "missing endpoint is passed through");
        check(" not JSON ".equals(AmapPlaceData.response(AmapPlaceData.DETAIL_PATH, " not JSON ", true)), "visible switch does not parse or rewrite bytes");

        for (String invalid : new String[] { null, "", " ", "not JSON", "[]", "null", "7", "{}", "{\"data\":null}",
            "{\"data\":[]}", "{\"data\":{\"modules\":null,\"regions\":{}}}",
            "{\"data\":{\"modules\":[],\"regions\":{}}}" }) {
            unchanged(invalid, "unsupported payload is safe: " + invalid);
        }
        String spaced = " { \"data\" : { \"modules\" : { \"future\" : { \"card_id\" : \"fixture\" } }, \"regions\" : {} } } ";
        unchanged(spaced, "unknown schema preserves exact source bytes");
        for (Object wrong : new Object[] { JSONObject.NULL, "wrong", 42, true, new JSONArray() }) {
            JSONObject unsupported = fixture();
            unsupported.getJSONObject("data").getJSONObject("modules").put(FEED, wrong);
            unchanged(unsupported.toString(), "unexpected target type does not damage the page: " + wrong);
        }
        for (Object wrong : new Object[] { JSONObject.NULL, "wrong", new JSONArray(), 42 }) {
            JSONObject unsupported = fixture();
            unsupported.getJSONObject("data").put("regions", wrong);
            unchanged(unsupported.toString(), "unexpected region structure preserves the page");
        }

        JSONObject noCardId = fixture();
        noCardId.getJSONObject("data").getJSONObject("modules").getJSONObject(FEED).remove("card_id");
        JSONArray noCardRegion = array(FEED, "base_17", "feed_17");
        noCardId.getJSONObject("data").getJSONObject("regions").put("listContent", noCardRegion);
        JSONObject noCardResult = new JSONObject(hide(noCardId.toString())).getJSONObject("data");
        check(!noCardResult.getJSONObject("modules").has(FEED), "confirmed module can be removed without an alias");
        check(noCardResult.getJSONObject("regions").getJSONArray("listContent").toString().equals(array("base_17", "feed_17").toString()),
            "unconfirmed aliases are never guessed");

        JSONObject numericCard = fixture();
        numericCard.getJSONObject("data").getJSONObject("modules").getJSONObject(FEED).put("card_id", 9);
        numericCard.getJSONObject("data").getJSONObject("regions").put("listContent", array(FEED, "9", 9, "base_17"));
        JSONArray numericResult = new JSONObject(hide(numericCard.toString())).getJSONObject("data").getJSONObject("regions").getJSONArray("listContent");
        check(numericResult.toString().equals(array("9", 9, "base_17").toString()), "numeric aliases are not coerced into arbitrary strings");

        JSONObject collision = fixture();
        collision.getJSONObject("data").getJSONObject("modules").getJSONObject("unrecognizedFutureModule").put("card_id", "feed_17");
        JSONObject collisionResult = new JSONObject(hide(collision.toString())).getJSONObject("data");
        check(collisionResult.getJSONObject("modules").has("unrecognizedFutureModule"), "alias collision retains the unrelated module");
        check(collisionResult.getJSONObject("regions").getJSONArray("listContent").toString().equals(array("base_17", "feed_17", QUICK, "quick_17", "future_17").toString()),
            "alias owned by a retained module keeps its layout reference");
        JSONObject ambiguous = fixture();
        ambiguous.getJSONObject("data").getJSONObject("modules").getJSONObject("unrecognizedFutureModule").put("card_id", FEED);
        unchanged(ambiguous.toString(), "a retained alias equal to a removed module name fails open");
        for (String absent : new String[] { FEED, TITLE }) {
            JSONObject single = fixture();
            single.getJSONObject("data").getJSONObject("modules").remove(absent);
            JSONObject singleResult = new JSONObject(hide(single.toString())).getJSONObject("data");
            check(!singleResult.getJSONObject("modules").has(FEED) && !singleResult.getJSONObject("modules").has(TITLE),
                "partial recommendation schema is removed safely: " + absent);
            check(singleResult.getJSONObject("modules").getJSONObject(QUICK).toString().equals(
                single.getJSONObject("data").getJSONObject("modules").getJSONObject(QUICK).toString()), "quick tools remain with a partial schema");
        }
        char[] oversized = new char[4 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, ' ');
        unchanged(new String(oversized), "oversized responses bypass parsing and preserve bytes");
        System.out.println("PASS " + assertions + " AMap place recommendation assertions");
    }
}
