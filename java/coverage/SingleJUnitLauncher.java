package trace2code.auto;

import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;

/** P39 JUnit-4 launcher with complete failure traces in stderr. */
public final class SingleJUnitLauncher {
    private SingleJUnitLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: class method");
        }
        String testId = args[0] + "::" + args[1];
        long started = System.nanoTime();
        Result result = new JUnitCore().run(Request.method(Class.forName(args[0]), args[1]));
        long elapsed = Math.max(0L, System.nanoTime() - started);
        System.out.println("test_id=" + testId);
        System.out.println("run_count=" + result.getRunCount());
        System.out.println("failure_count=" + result.getFailureCount());
        System.out.println("ignore_count=" + result.getIgnoreCount());
        System.out.println("success=" + result.wasSuccessful());
        System.out.println("wall_ns=" + elapsed);
        System.out.println("transformed_classes=" + TraceTransformer.transformedClassCount());
        System.out.println("enter_calls=" + TraceRuntime.enterCount());
        System.out.println("trace_events=" + TraceRuntime.eventCount());
        if (!result.wasSuccessful()) {
            for (org.junit.runner.notification.Failure failure : result.getFailures()) {
                System.err.println("failure=" + failure.getTestHeader() + "|"
                        + failure.getMessage());
                System.err.println(failure.getTrace());
            }
        }
        try {
            TraceRuntime.flush();
        } finally {
            System.out.println("P39_QUEUE_METRICS=" + TraceRuntime.queueMetricsJson());
        }
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
}
