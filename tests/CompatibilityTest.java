package io.github.ldxm666.mapclean;

/** A changed app can keep working features while incompatible signatures are skipped. */
public final class CompatibilityTest {
    private static int assertions;
    static final class Old {
        public String id;
        public String load(String context, Object state) { return "view"; }
    }
    static final class Updated {
        public int id;
        public String load(String context, Object state, boolean preload) { return "view"; }
        public void unrelated() {}
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(Compatibility.method(Old.class, "load", String.class, String.class, Object.class) != null, "old two-argument capability found");
        check(Compatibility.method(Updated.class, "load", String.class, String.class, Object.class) == null, "changed signature skipped");
        check(Compatibility.method(Updated.class, "load", String.class, String.class, Object.class, boolean.class) != null, "new supported overload found independently");
        check(Compatibility.method(Updated.class, "unrelated", void.class) != null, "unrelated stable feature remains available");
        check(Compatibility.method(Old.class, "load", void.class, String.class, Object.class) == null, "wrong return type rejected");
        check(Compatibility.method(null, "load", null) == null, "missing class safely skipped");
        check(Compatibility.field(Old.class, "id", String.class), "expected field type accepted");
        check(!Compatibility.field(Updated.class, "id", String.class), "changed DTO field rejected");
        check(!Compatibility.field(Old.class, "jadxAlias", String.class), "decompiler aliases are not assumed to be runtime fields");
        System.out.println("PASS " + assertions + " capability assertions");
    }
}
