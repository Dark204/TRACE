package trace2code.auto;

import java.lang.instrument.Instrumentation;

/** Java-agent entry point for the single-test Chart smoke. */
public final class TraceAgent {
    private TraceAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        String output = argument(agentArgs, "out");
        TraceRuntime.configure(output);
        instrumentation.addTransformer(new TraceTransformer(), false);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                TraceRuntime.flush();
            }
        }, "trace2code-auto-flush"));
    }

    private static String argument(String args, String name) {
        if (args == null) {
            throw new IllegalArgumentException("missing javaagent arguments");
        }
        String prefix = name + "=";
        for (String item : args.split(",")) {
            if (item.startsWith(prefix)) {
                return item.substring(prefix.length());
            }
        }
        throw new IllegalArgumentException("missing javaagent argument " + name);
    }
}
