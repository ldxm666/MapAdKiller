package io.github.ldxm666.mapadkiller;

import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Argument-rewriting hookers for 高德's tunable setters.
 *
 * <h3>Why a class of its own instead of adding to {@link H}</h3>
 * {@code H} is shared with the 百度 and 腾讯 halves through one APK; anything added there is
 * compiled into every variant and has to stay valid for three hosts. These three helpers are
 * only ever used by the 高德 tuning hooks, so they live next to them.
 *
 * <h3>The API-102 trap this exists to avoid</h3>
 * {@code Chain} has both {@code proceed(Object[] newArgs)} and {@code proceedWith(Object
 * thisObject)}. Passing an {@code Object[]} to the latter is silently accepted at compile time
 * (arrays are Objects) and then fails at runtime with
 * {@code IllegalArgumentException: thisObject must be an instance of …} — inside the host
 * process, i.e. a crash on every call. AmapTune hit exactly that and it is recorded in its
 * test report; every rewrite here goes through {@code proceed(Object[])}.
 *
 * <p>Values are boxed to the <em>declared</em> parameter type, read back from the executable,
 * so an overload that declares {@code long} or {@code float} still works instead of throwing
 * {@code IllegalArgumentException} from reflection boxing.
 */
final class AmapHookArgs {

    private AmapHookArgs() {}

    /** Supplies the value to force into an argument slot; {@code null} result means "leave it". */
    interface Value {
        Object get();
    }

    /**
     * Force argument {@code index} to {@code value} on every call.
     *
     * <p>Zero-allocation fast path: when the caller already passes the value we would force,
     * {@code chain.proceed()} runs and no array is built. These setters can be called in a
     * render loop, so the steady state must not allocate.
     */
    static XposedInterface.Hooker arg(final int index, final Value value) {
        return new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object wanted = value.get();
                if (wanted == null) return chain.proceed();
                List<Object> args = chain.getArgs();
                if (index < 0 || index >= args.size()) return chain.proceed();
                if (wanted.equals(args.get(index))) return chain.proceed();
                Object[] copy = args.toArray(new Object[args.size()]);
                copy[index] = box(chain, index, wanted);
                return chain.proceed(copy);
            }
        };
    }

    /**
     * Force two argument slots: the fps value at {@code fpsIndex} and the "enabled" flag at
     * {@code flagIndex}.
     *
     * <p>{@code setRenderFpsWithTimer(int, int, boolean)} is the reason: handing the engine the
     * right fps while it is passed {@code enabled=false} does nothing at all, so both slots have
     * to move together or the hook is decoration.
     */
    static XposedInterface.Hooker argPlusFlag(final int fpsIndex, final Value fps,
                                              final int flagIndex) {
        return new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object wanted = fps.get();
                if (wanted == null) return chain.proceed();
                List<Object> args = chain.getArgs();
                if (fpsIndex >= args.size() || flagIndex >= args.size()) return chain.proceed();
                boolean same = wanted.equals(args.get(fpsIndex))
                        && Boolean.TRUE.equals(args.get(flagIndex));
                if (same) return chain.proceed();
                Object[] copy = args.toArray(new Object[args.size()]);
                copy[fpsIndex] = box(chain, fpsIndex, wanted);
                copy[flagIndex] = Boolean.TRUE;
                return chain.proceed(copy);
            }
        };
    }

    /** Box to the executable's declared parameter type, not to the target's natural type. */
    private static Object box(XposedInterface.Chain chain, int index, Object value) {
        try {
            Class<?>[] types = chain.getExecutable().getParameterTypes();
            if (index >= types.length) return value;
            Class<?> want = types[index];
            if (want == boolean.class) return Boolean.valueOf(Boolean.TRUE.equals(value)
                    || (value instanceof Number && ((Number) value).intValue() != 0));
            if (want == int.class) {
                return Integer.valueOf(value instanceof Number
                        ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
            }
            if (want == long.class) {
                return Long.valueOf(value instanceof Number
                        ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value)));
            }
            if (want == float.class) {
                return Float.valueOf(value instanceof Number
                        ? ((Number) value).floatValue() : Float.parseFloat(String.valueOf(value)));
            }
            return value;
        } catch (Throwable ignored) {
            return value;
        }
    }
}
