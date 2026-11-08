package io.github.ldxm666.mapclean;

import java.lang.reflect.InvocationTargetException;

/** app_process normally logs uncaught exceptions only to logcat. Keep failures in the test report. */
public final class RegressionHarness {
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->fail(error));
        try {
            if(args.length!=1)throw new IllegalArgumentException("Expected exactly one regression class");
            Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            System.out.println("REGRESSION_OK "+args[0]);
        }catch(InvocationTargetException error){fail(error.getCause());}
        catch(Throwable error){fail(error);}
    }
    private static void fail(Throwable error){error.printStackTrace(System.err);System.exit(1);}
}
