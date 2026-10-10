package io.github.ldxm666.mapclean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned user settings only. Preference groups keep identically named keys separate. */
public final class ConfigBackup {
    public static final String AMAP = "amap_enhancer_config", BAIDU = "bmapclean_config", UI = "mak_ui";
    public static final String LEARNED = "sdk_learned";
    public interface Store {
        Map<String, ?> read(String group);
        /** A null value removes the key, preserving an unset/default preference. */
        boolean write(String group, Map<String, Object> values);
    }
    private ConfigBackup() {}

    public static JSONObject encode(Store store, Map<String, Set<String>> schema, String app) throws Exception {
        JSONObject groups = new JSONObject();
        for (Map.Entry<String, Set<String>> group : schema.entrySet()) {
            Map<String, ?> stored = store.read(group.getKey());
            JSONObject values = new JSONObject();
            for (String key : group.getValue()) {
                Object value = stored.get(key);
                if (value instanceof Set && LEARNED.equals(key)) values.put(key, new JSONArray((Set<?>) value));
                else if (value == null || value instanceof Boolean) values.put(key, value == null ? JSONObject.NULL : value);
                else throw new IllegalArgumentException("配置类型错误：" + key);
            }
            groups.put(group.getKey(), values);
        }
        return new JSONObject().put("_app", app).put("_ver", 2).put("groups", groups);
    }

    public static Map<String, Map<String, Object>> decode(JSONObject backup, Map<String, Set<String>> schema) throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        if (backup.has("groups")) {
            if (backup.optInt("_ver", 0) != 2) throw new IllegalArgumentException("不支持的配置版本");
            JSONObject groups = backup.getJSONObject("groups");
            for (Map.Entry<String, Set<String>> group : schema.entrySet()) {
                if (!groups.has(group.getKey())) continue;
                Map<String, Object> values = decodeGroup(groups.getJSONObject(group.getKey()), group.getValue());
                if (!values.isEmpty()) result.put(group.getKey(), values);
            }
        } else {
            // Legacy flat exports contain AMap settings only; never change Baidu on import.
            Map<String, Object> values = decodeGroup(backup, schema.get(AMAP));
            if (!values.isEmpty()) result.put(AMAP, values);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("没有可识别的配置项");
        return result;
    }

    private static Map<String, Object> decodeGroup(JSONObject object, Set<String> allowed) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (allowed == null) return result;
        for (String key : allowed) {
            if (!object.has(key)) continue;
            Object value = object.get(key);
            if (value == JSONObject.NULL) value = null;
            else if (value instanceof JSONArray && LEARNED.equals(key)) {
                JSONArray list = (JSONArray) value;
                Set<String> roots = new LinkedHashSet<String>();
                for (int i = 0; i < list.length(); i++) {
                    Object item = list.get(i);
                    if (!(item instanceof String)) throw new IllegalArgumentException("SDK 清单类型错误");
                    roots.add((String) item);
                }
                value = roots;
            } else if (!(value instanceof Boolean)) throw new IllegalArgumentException("开关类型错误：" + key);
            result.put(key, value);
        }
        return result;
    }

    public static int apply(Store store, Map<String, Map<String, Object>> groups) {
        Map<String, Map<String, Object>> before = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> group : groups.entrySet()) {
            Map<String, ?> current = store.read(group.getKey());
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (String key : group.getValue().keySet()) values.put(key, current.get(key));
            before.put(group.getKey(), values);
        }
        List<String> attempted = new ArrayList<String>();
        int count = 0;
        try {
            for (Map.Entry<String, Map<String, Object>> group : groups.entrySet()) {
                attempted.add(group.getKey());
                if (!store.write(group.getKey(), group.getValue())) throw new IllegalStateException("配置写入失败");
                count += group.getValue().size();
            }
            return count;
        } catch (RuntimeException failure) {
            boolean restored = true;
            for (int i = attempted.size() - 1; i >= 0; i--) {
                String group = attempted.get(i);
                try { restored &= store.write(group, before.get(group)); }
                catch (RuntimeException ignored) { restored = false; }
            }
            throw new IllegalStateException(restored ? "恢复失败，已保留原配置" : "恢复失败，部分配置回退失败，请重新导入", failure);
        }
    }
}
