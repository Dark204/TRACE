package trace2code.auto;

import java.util.Enumeration;

import junit.framework.Test;
import junit.framework.TestFailure;
import junit.framework.TestResult;
import junit.framework.TestSuite;

/** P39 JUnit-3 launcher with complete failure traces in stderr. */
public final class SingleJUnit3Launcher {
    private SingleJUnit3Launcher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: class method");
        }
        String testId = args[0] + "::" + args[1];
        Class<?> testClass = Class.forName(args[0]);
        Test test = TestSuite.createTest(testClass, args[1]);
        TestResult result = new TestResult();
        long started = System.nanoTime();
        test.run(result);
        long elapsed = Math.max(0L, System.nanoTime() - started);
        System.out.println("test_id=" + testId);
        System.out.println("run_count=" + result.runCount());
        System.out.println("failure_count=" + result.failureCount());
        System.out.println("error_count=" + result.errorCount());
        System.out.println("success=" + result.wasSuccessful());
        System.out.println("wall_ns=" + elapsed);
        System.out.println("transformed_classes=" + TraceTransformer.transformedClassCount());
        System.out.println("enter_calls=" + TraceRuntime.enterCount());
        System.out.println("trace_events=" + TraceRuntime.eventCount());
        printFailures("failure", result.failures());
        printFailures("error", result.errors());
        try {
            TraceRuntime.flush();
        } finally {
            System.out.println("P39_QUEUE_METRICS=" + TraceRuntime.queueMetricsJson());
        }
        System.exit(result.wasSuccessful() ? 0 : 1);
    }

    private static void printFailures(String prefix, Enumeration<?> failures) {
        while (failures.hasMoreElements()) {
            TestFailure failure = (TestFailure) failures.nextElement();
            System.err.println(prefix + "=" + failure.failedTest() + "|"
                    + failure.exceptionMessage());
            System.err.println(failure.trace());
        }
    }
}
