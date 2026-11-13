package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Regression: failed remote mirrors must not revert a successful embedded/local write. */
public final class CfgStoreTest {
    private static int assertions;
    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }
    private static final class Memory implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int commits;
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public boolean contains(String k) { return values.containsKey(k); }
        public boolean getBoolean(String k, boolean d) { return contains(k) ? (Boolean)values.get(k) : d; }
        public String getString(String k, String d) { return contains(k) ? (String)values.get(k) : d; }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String k, Set<String> d) { return contains(k) ? (Set<String>)values.get(k) : d; }
        public int getInt(String k, int d) { return contains(k) ? (Integer)values.get(k) : d; }
        public long getLong(String k, long d) { return contains(k) ? (Long)values.get(k) : d; }
        public float getFloat(String k, float d) { return contains(k) ? (Float)values.get(k) : d; }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> changes = new HashMap<>();
                boolean clear;
                public Editor putBoolean(String k, boolean v) { changes.put(k, v); return this; }
                public Editor putInt(String k, int v) { changes.put(k, v); return this; }
                public Editor putLong(String k, long v) { changes.put(k, v); return this; }
                public Editor putFloat(String k, float v) { changes.put(k, v); return this; }
                public Editor putString(String k, String v) { changes.put(k, v); return this; }
                public Editor putStringSet(String k, Set<String> v) { changes.put(k, new HashSet<>(v)); return this; }
                public Editor remove(String k) { changes.put(k, null); return this; }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    if (clear) values.clear();
                    for (Map.Entry<String, Object> e : changes.entrySet()) {
                        if (e.getValue() == null) values.remove(e.getKey()); else values.put(e.getKey(), e.getValue());
                    }
                    commits++;
                    return true;
                }
                public void apply() { commit(); }
            };
        }
    }
    public static void main(String[] ignored) throws Exception {
        Map<String, Object> local = new HashMap<>(), remote = new HashMap<>();
        local.put(Config.K_TAXI_ACTIVITY, false);
        remote.put(Config.K_TAXI_ACTIVITY, true);
        local.put("speed", 35);
        remote.put("speed", 20);
        remote.put(Config.K_HOME_CHIPS, false);
        Map<String, Object> saved = new HashMap<>(local);
        Map<String, Object> embedded = CfgStore.mergeValues(true, local, remote);
        check(Boolean.FALSE.equals(embedded.get(Config.K_TAXI_ACTIVITY)), "stale remote cannot undo hidden local promotion");
        check(Integer.valueOf(35).equals(embedded.get("speed")), "local slider also authoritative");
        check(Boolean.FALSE.equals(embedded.get(Config.K_HOME_CHIPS)), "unmigrated old remote choices preserved");
        check(local.equals(saved), "merge does not mutate source preferences");
        local.put(Config.K_TAXI_ACTIVITY, true);
        remote.put(Config.K_TAXI_ACTIVITY, false);
        check(Boolean.TRUE.equals(CfgStore.mergeValues(true, local, remote).get(Config.K_TAXI_ACTIVITY)), "restore survives stale remote false");
        check(Boolean.FALSE.equals(CfgStore.mergeValues(false, local, remote).get(Config.K_TAXI_ACTIVITY)), "LSPosed remote remains authoritative");
        check(CfgStore.mergeValues(true, local, new HashMap<String, Object>()).equals(local), "remote outage preserves local data");
        check(CfgStore.mergeValues(true, new HashMap<String, Object>(), remote).equals(remote), "early context absence preserves existing remote data");

        Method seed = Config.class.getDeclaredMethod("seedLocal", SharedPreferences.class, SharedPreferences.class, boolean.class);
        seed.setAccessible(true);
        Memory old = new Memory(), fresh = new Memory();
        old.values.put(Config.K_HOME_CHIPS, false);
        old.values.put(Config.K_TAXI_VIP, true);
        old.values.put("text", "fixture");
        old.values.put("speed", 30);
        old.values.put("time", 100L);
        old.values.put("ratio", 0.5f);
        fresh.values.put(Config.K_TAXI_VIP, false);
        seed.invoke(Config.INSTANCE, old, fresh, true);
        check(Boolean.FALSE.equals(fresh.values.get(Config.K_TAXI_VIP)), "migration preserves already written local switch");
        check(Boolean.FALSE.equals(fresh.values.get(Config.K_HOME_CHIPS)), "old false choice imported");
        for (String key : new String[]{"text", "speed", "time", "ratio"})
            check(old.values.get(key).equals(fresh.values.get(key)), "migration preserves type: " + key);
        check(fresh.getBoolean("_local_migrated", false), "migration marked complete");
        int commits = fresh.commits;
        old.values.put(Config.K_HOME_CHIPS, true);
        seed.invoke(Config.INSTANCE, old, fresh, true);
        check(fresh.commits == commits && !fresh.getBoolean(Config.K_HOME_CHIPS, true), "migration runs once and never reimports stale remote");
        Memory empty = new Memory(), later = new Memory();
        seed.invoke(Config.INSTANCE, empty, later, true);
        check(!later.contains("_local_migrated"), "temporarily unavailable remote is not marked migrated");
        empty.values.put(Config.K_HOME_CHIPS, false);
        seed.invoke(Config.INSTANCE, empty, later, true);
        check(later.contains(Config.K_HOME_CHIPS), "migration retries after remote becomes readable");
        local.clear();remote.clear();
        local.put(Config.K_FEED_OTHER,false);remote.put(Config.K_FEED_OTHER,true);remote.put(Config.K_FEED_BOARD,true);
        check(!Config.readVisible(CfgStore.mergeValues(true,local,remote),Config.K_FEED_CONTENT),"embedded merged card switch keeps authoritative local off");
        check(Config.readVisible(CfgStore.mergeValues(false,local,remote),Config.K_FEED_FESTIVAL),"module merged card switch keeps authoritative remote on");
        local.put(Config.K_FEED_OTHER,true);remote.put(Config.K_FEED_OTHER,false);
        check(Config.readVisible(CfgStore.mergeValues(true,local,remote),Config.K_FEED_BOARD),"embedded merged card switch restores with local on");
        check(!Config.readVisible(CfgStore.mergeValues(false,local,remote),Config.K_FEED_CONTENT),"module explicit off beats a stale legacy card on");
        local.clear();remote.clear();remote.put(Config.K_FEED_FESTIVAL,true);
        check(Config.readVisible(CfgStore.mergeValues(true,local,remote),Config.K_FEED_OTHER),"old remote selection remains usable before local migration");
        Class<?> defaults=io.github.ldxm666.mapclean.DefaultConfig.Companion.getClass();
        Method writeDefaults=defaults.getDeclaredMethod("write",SharedPreferences.class,Map.class,io.github.ldxm666.mapclean.DefaultConfig.Result.class,String.class,int.class);
        writeDefaults.setAccessible(true);
        Map<String,Object> defaultCards=new HashMap<>();defaultCards.put(Config.K_FEED_OTHER,null);
        for(String key:io.github.ldxm666.mapclean.FeedCardConfig.LEGACY_KEYS)defaultCards.put(key,false);
        defaultCards.put(Config.K_FEED_WEATHER,false);
        for(int legacyBits=0;legacyBits<8;legacyBits++){
            Memory existing=new Memory();existing.values.put(Config.K_FEED_WEATHER,true);
            for(int i=0;i<io.github.ldxm666.mapclean.FeedCardConfig.LEGACY_KEYS.length;i++)
                if((legacyBits&(1<<i))!=0)existing.values.put(io.github.ldxm666.mapclean.FeedCardConfig.LEGACY_KEYS[i],true);
            for(int pass=0;pass<2;pass++){
                writeDefaults.invoke(io.github.ldxm666.mapclean.DefaultConfig.Companion,existing,defaultCards,new io.github.ldxm666.mapclean.DefaultConfig.Result(0,0),Config.PREF_GROUP,2);
                check(!existing.contains(Config.K_FEED_OTHER),"default seeding leaves the merged key unset for legacy migration");
                check(Config.readVisible(existing,Config.K_FEED_OTHER)==(legacyBits!=0),"default seeding preserves old selected card choices");
                check(existing.getBoolean(Config.K_FEED_WEATHER,false),"default seeding does not reset weather");
            }
            existing.values.put(Config.K_FEED_OTHER,false);
            writeDefaults.invoke(io.github.ldxm666.mapclean.DefaultConfig.Companion,existing,defaultCards,new io.github.ldxm666.mapclean.DefaultConfig.Result(0,0),Config.PREF_GROUP,2);
            check(!Config.readVisible(existing,Config.K_FEED_OTHER),"reapplying defaults never resurrects explicitly disabled merged cards");
        }
        System.out.println("PASS " + assertions + " preference authority/migration assertions");
    }
}
