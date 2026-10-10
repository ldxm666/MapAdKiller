package io.github.ldxm666.mapclean;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Exercises migration, separate groups, unset choices and a failed second-group commit. */
public final class ConfigBackupTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Memory implements ConfigBackup.Store {
        final Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        String failOnce;
        public Map<String, Object> read(String group) {
            if (!groups.containsKey(group)) groups.put(group, new LinkedHashMap<String, Object>());
            return new LinkedHashMap<>(groups.get(group));
        }
        public boolean write(String group, Map<String, Object> values) {
            read(group);
            for (Map.Entry<String, Object> value : values.entrySet()) {
                if (value.getValue() == null) groups.get(group).remove(value.getKey());
                else groups.get(group).put(value.getKey(), value.getValue());
            }
            if (group.equals(failOnce)) { failOnce = null; return false; }
            return true;
        }
        void put(String group, String key, Object value) { read(group); groups.get(group).put(key, value); }
    }
    public static void main(String[] args) throws Exception {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        schema.put(ConfigBackup.AMAP, new LinkedHashSet<>(Arrays.asList("debug_log", "ui_tools_allowlist", "tool_骑行", ConfigBackup.LEARNED)));
        schema.put(ConfigBackup.BAIDU, new LinkedHashSet<>(Arrays.asList("debug_log", "home_banner", "mine_apply")));
        schema.put(ConfigBackup.UI, new LinkedHashSet<>(Arrays.asList("hide_icon_v2")));
        Memory original = new Memory();
        original.put(ConfigBackup.AMAP, "debug_log", true);
        original.put(ConfigBackup.AMAP, "ui_tools_allowlist", true);
        original.put(ConfigBackup.AMAP, ConfigBackup.LEARNED, new LinkedHashSet<>(Arrays.asList("fixture.ad.sdk")));
        original.put(ConfigBackup.BAIDU, "debug_log", false);
        original.put(ConfigBackup.BAIDU, "home_banner", false);
        original.put(ConfigBackup.BAIDU, "mine_apply", true);
        original.put(ConfigBackup.BAIDU, "app_token", "private-fixture-token");
        original.put(ConfigBackup.UI, "hide_icon_v2", false);
        JSONObject exported = ConfigBackup.encode(original, schema, "fixture");
        check(exported.getInt("_ver") == 2, "versioned export");
        check(exported.getJSONObject("groups").getJSONObject(ConfigBackup.BAIDU).getBoolean("mine_apply"), "Baidu settings exported");
        check(exported.getJSONObject("groups").getJSONObject(ConfigBackup.AMAP).isNull("tool_骑行"), "unset allowlist tool preserved");
        check(!exported.toString().contains("private-fixture-token"), "diagnostic token excluded");
        Memory target = new Memory();
        target.put(ConfigBackup.AMAP, "tool_骑行", true);
        target.put(ConfigBackup.BAIDU, "app_token", "target-token");
        int count = ConfigBackup.apply(target, ConfigBackup.decode(new JSONObject(exported.toString()), schema));
        check(count == 8, "all groups committed");
        check(Boolean.TRUE.equals(target.read(ConfigBackup.AMAP).get("debug_log")), "AMap debug key restored");
        check(Boolean.FALSE.equals(target.read(ConfigBackup.BAIDU).get("debug_log")), "same-named Baidu debug key stays separate");
        check(Boolean.FALSE.equals(target.read(ConfigBackup.BAIDU).get("home_banner")), "false Baidu flag restored");
        check(Boolean.TRUE.equals(target.read(ConfigBackup.BAIDU).get("mine_apply")), "true Baidu flag restored");
        check(!target.read(ConfigBackup.AMAP).containsKey("tool_骑行"), "import clears tool previously set on target");
        check(target.read(ConfigBackup.AMAP).get(ConfigBackup.LEARNED) instanceof Set, "SDK string set round trips");
        check("target-token".equals(target.read(ConfigBackup.BAIDU).get("app_token")), "target diagnostic token retained");
        Map<String, Object> baiduBefore = target.read(ConfigBackup.BAIDU);
        JSONObject legacy = new JSONObject().put("_ver", 1).put("debug_log", false).put("unknown", true);
        ConfigBackup.apply(target, ConfigBackup.decode(legacy, schema));
        check(Boolean.FALSE.equals(target.read(ConfigBackup.AMAP).get("debug_log")), "legacy AMap restored");
        check(baiduBefore.equals(target.read(ConfigBackup.BAIDU)), "legacy backup leaves Baidu unchanged");
        boolean rejected = false;
        try { ConfigBackup.decode(new JSONObject().put("debug_log", "false"), schema); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "malformed boolean rejected instead of becoming default true");
        target.put(ConfigBackup.AMAP, "tool_骑行", false);
        target.put(ConfigBackup.BAIDU, "home_banner", true);
        Map<String, Object> amapBefore = target.read(ConfigBackup.AMAP);
        baiduBefore = target.read(ConfigBackup.BAIDU);
        target.failOnce = ConfigBackup.BAIDU;
        rejected = false;
        try { ConfigBackup.apply(target, ConfigBackup.decode(exported, schema)); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "failed group commit is reported");
        check(amapBefore.equals(target.read(ConfigBackup.AMAP)), "earlier AMap write rolled back");
        check(baiduBefore.equals(target.read(ConfigBackup.BAIDU)), "partially applied failed Baidu write rolled back");
        System.out.println("PASS " + assertions + " backup assertions");
    }
}
