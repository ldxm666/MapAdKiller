package io.github.ldxm666.mapadkiller;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** POI-detail BFF module IDs verified from the installed application's response. */
public final class AmapPlaceData {
    public static final String DETAIL_PATH = "/ws/shield/search/poi/detail";
    private static final String[] FEED_MODULES = {"poiDetailWaterFeedTitle", "poiDetailWaterFeed"};
    private AmapPlaceData() {}

    public static String response(String path, String raw, boolean showRecommendations) {
        if (showRecommendations || !DETAIL_PATH.equals(path) || raw == null || raw.isEmpty()
                || raw.length() > 4 * 1024 * 1024) return raw;
        try {
            JSONObject out = new JSONObject(raw);
            JSONObject data = out.optJSONObject("data");
            JSONObject modules = data == null ? null : data.optJSONObject("modules");
            JSONObject regions = data == null ? null : data.optJSONObject("regions");
            if (modules == null || regions == null) return raw;
            Set<String> moduleNames = new HashSet<String>();
            Set<String> references = new HashSet<String>();
            for (String name : FEED_MODULES) {
                JSONObject module = modules.optJSONObject(name);
                if (modules.has(name) && module == null) return raw;
                if (module == null) continue;
                moduleNames.add(name);
                references.add(name);
                Object cardId = module.opt("card_id");
                if (cardId instanceof String && !((String) cardId).isEmpty()) references.add((String) cardId);
            }
            if (moduleNames.isEmpty()) return raw;
            // A shared card ID is ambiguous: retain its reference for the surviving module.
            Iterator<String> names = modules.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (moduleNames.contains(name)) continue;
                JSONObject module = modules.optJSONObject(name);
                Object cardId = module == null ? null : module.opt("card_id");
                if (cardId instanceof String) {
                    if (moduleNames.contains(cardId)) return raw;
                    references.remove(cardId);
                }
            }
            for (String name : moduleNames) modules.remove(name);
            if (regions != null) {
                Iterator<String> regionNames = regions.keys();
                while (regionNames.hasNext()) {
                    String name = regionNames.next();
                    JSONArray source = regions.optJSONArray(name);
                    if (source == null) continue;
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < source.length(); i++) {
                        Object item = source.opt(i);
                        if (!(item instanceof String) || !references.contains(item)) kept.put(item);
                    }
                    if (kept.length() != source.length()) regions.put(name, kept);
                }
            }
            return out.toString();
        } catch (Exception ignored) {
            return raw;
        }
    }
}
