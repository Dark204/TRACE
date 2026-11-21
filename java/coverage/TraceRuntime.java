package trace2code.auto;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Deflater;

/** Runtime used by the agent-injected calls. It deliberately has no benchmark dependency. */
public final class TraceRuntime {
    private static final String MASKED_BRANCH = "MASKED";
    private static final int SPILL_EVENT_LIMIT = 131072;
    private static final int DEFAULT_P39_QUEUE_CAPACITY = 32768;
    private static final int DEFAULT_P39_SERIALIZER_WORKERS = 3;
    private static final AtomicLong NEXT_SPOOL_TEMP_ID = new AtomicLong();
    private static final AtomicLong NEXT_ORDINAL = new AtomicLong();
    private static final AtomicLong ENTER_CALLS = new AtomicLong();
    private static final AtomicLong COMPLETED_EVENTS = new AtomicLong();
    private static final List<Event> EVENTS = Collections.synchronizedList(
            new ArrayList<Event>(SPILL_EVENT_LIMIT));
    private static final List<Path> SPILL_CHUNKS = new ArrayList<Path>();
    private static final Object ASYNC_LIFECYCLE_LOCK = new Object();
    private static volatile AsyncEventWriter asyncEventWriter;
    private static int activeAsyncSubmissions;
    private static boolean asyncClosing;
    private static long SPILLED_EVENTS;
    private static final ThreadLocal<Deque<Span>> STACKS =
            new ThreadLocal<Deque<Span>>() {
                @Override
                protected Deque<Span> initialValue() {
                    return new ArrayDeque<Span>();
                }
            };
    private static volatile Path output;
    private static volatile Path spillDirectory;
    private static volatile IOException spillFailure;
    private static volatile boolean flushed;

    private TraceRuntime() {
    }

    public static void configure(String outputPath) {
        if (outputPath == null || outputPath.length() == 0) {
            throw new IllegalArgumentException("automatic collector requires an output path");
        }
        output = Paths.get(outputPath);
        activeAsyncSubmissions = 0;
        asyncClosing = false;
        if (Boolean.parseBoolean(System.getProperty("trace2code.p39.asyncQueue", "false"))) {
            int capacity = boundedIntegerProperty("trace2code.p39.queueCapacity",
                    DEFAULT_P39_QUEUE_CAPACITY, 1, SPILL_EVENT_LIMIT);
            int workers = boundedIntegerProperty("trace2code.p39.serializerWorkers",
                    DEFAULT_P39_SERIALIZER_WORKERS, 1, 4);
            try {
                asyncEventWriter = new AsyncEventWriter(output, capacity, workers, 0L, -1L);
            } catch (IOException error) {
                throw new IllegalStateException("failed to initialize bounded P39 event writer", error);
            }
        }
    }

    private static int boundedIntegerProperty(String name, int fallback, int minimum, int maximum) {
        String raw = System.getProperty(name);
        if (raw == null || raw.length() == 0) return fallback;
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid integer property " + name + "=" + raw, error);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("property outside supported bounds: " + name + "=" + value);
        }
        return value;
    }

    static void configureForIntegrationProbe(String outputPath, int capacity, int workers,
                                             long writerDelayMillis, long failAfterCommits) {
        if (outputPath == null || outputPath.length() == 0) {
            throw new IllegalArgumentException("integration probe requires an output path");
        }
        if (asyncEventWriter != null || flushed || COMPLETED_EVENTS.get() != 0L) {
            throw new IllegalStateException("integration probe must use a fresh collector process");
        }
        if (capacity < 1 || workers < 1 || workers > 4) {
            throw new IllegalArgumentException("invalid integration probe queue dimensions");
        }
        output = Paths.get(outputPath);
        activeAsyncSubmissions = 0;
        asyncClosing = false;
        try {
            asyncEventWriter = new AsyncEventWriter(output, capacity, workers,
                    writerDelayMillis, failAfterCommits);
        } catch (IOException error) {
            throw new IllegalStateException("failed to initialize integration probe writer", error);
        }
    }

    static long currentOrdinalForIntegrationProbe() {
        Deque<Span> stack = STACKS.get();
        if (stack.isEmpty()) throw new IllegalStateException("no active collector span");
        return stack.peek().ordinal;
    }

    public static String queueMetricsJson() {
        AsyncEventWriter writer = asyncEventWriter;
        return writer == null ? "{\"schema\":\"TRACE_P39_ASYNC_QUEUE_METRICS_V1\",\"enabled\":false}"
                : writer.metricsJson();
    }

    public static void enter(String className, String methodName, String descriptor,
                             String file, int line, String returnType) {
        ENTER_CALLS.incrementAndGet();
        Deque<Span> stack = STACKS.get();
        String parentSymbol = stack.isEmpty() ? "" : stableSymbol(stack.peek());
        Span span = new Span(
                NEXT_ORDINAL.getAndIncrement(), className, methodName, descriptor,
                file, line, returnType, Thread.currentThread().getId(), stack.size(),
                parentSymbol, System.nanoTime());
        stack.push(span);
    }

    public static void exitReturn() {
        Deque<Span> stack = STACKS.get();
        if (stack.isEmpty()) {
            return;
        }
        Span span = stack.pop();
        record(Event.returned(span, elapsed(span)));
    }

    public static void exitException(Throwable error) {
        Deque<Span> stack = STACKS.get();
        if (stack.isEmpty()) {
            return;
        }
        Span span = stack.pop();
        record(Event.failed(span, elapsed(span), error));
    }

    public static long enterCount() {
        return ENTER_CALLS.get();
    }

    public static int eventCount() {
        return (int) COMPLETED_EVENTS.get();
    }

    public static void flush() {
        if (flushed) {
            return;
        }
        synchronized (TraceRuntime.class) {
            if (flushed) {
                return;
            }
            AsyncEventWriter writer = asyncEventWriter;
            if (writer != null) {
                long expectedEvents;
                boolean interruptedWhileDraining = false;
                synchronized (ASYNC_LIFECYCLE_LOCK) {
                    asyncClosing = true;
                    while (activeAsyncSubmissions > 0) {
                        try {
                            ASYNC_LIFECYCLE_LOCK.wait();
                        } catch (InterruptedException interrupted) {
                            interruptedWhileDraining = true;
                        }
                    }
                    expectedEvents = COMPLETED_EVENTS.get();
                }
                try {
                    writer.finish(expectedEvents, ENTER_CALLS.get());
                } catch (IOException error) {
                    spillFailure = error;
                    throw new IllegalStateException("failed to finish bounded automatic trace " + output, error);
                } finally {
                    synchronized (ASYNC_LIFECYCLE_LOCK) {
                        flushed = true;
                        ASYNC_LIFECYCLE_LOCK.notifyAll();
                    }
                    if (interruptedWhileDraining) Thread.currentThread().interrupt();
                }
                return;
            }
            // A defensive drain covers a process terminated by an exception after a
            // method's catch-all handler could not be reached. Normal Chart/JUnit
            // paths are expected to leave every stack empty.
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                // Thread stacks are intentionally not inspected across threads. The
                // launcher calls flush after the test and the shutdown hook is only a
                // last-resort close for already-finished event buffers.
            }
            writeEvents();
            flushed = true;
        }
    }

    private static long elapsed(Span span) {
        return Math.max(0L, System.nanoTime() - span.startNs);
    }

    private static void record(Event event) {
        AsyncEventWriter writer = asyncEventWriter;
        if (writer != null) {
            synchronized (ASYNC_LIFECYCLE_LOCK) {
                if (flushed || asyncClosing) return;
                COMPLETED_EVENTS.incrementAndGet();
                activeAsyncSubmissions++;
            }
            try {
                writer.submit(event);
            } finally {
                synchronized (ASYNC_LIFECYCLE_LOCK) {
                    activeAsyncSubmissions--;
                    ASYNC_LIFECYCLE_LOCK.notifyAll();
                }
            }
            return;
        }
        synchronized (EVENTS) {
            COMPLETED_EVENTS.incrementAndGet();
            if (flushed || spillFailure != null) {
                return;
            }
            EVENTS.add(event);
            if (EVENTS.size() >= SPILL_EVENT_LIMIT) {
                try {
                    spillChunkLocked();
                } catch (IOException error) {
                    // Instrumentation must not change the observed program's
                    // control flow.  Preserve the failure for the final flush
                    // receipt and stop retaining additional events.
                    spillFailure = error;
                    EVENTS.clear();
                }
            }
        }
    }

    /**
     * Bounded P39 handoff. Event and Span are immutable snapshots before submit;
     * serializer workers may finish out of order, while the single commit thread
     * writes the original span ordinal into Lang43's lossless indexed spool.
     */
    private static final class AsyncEventWriter {
        private final Path outputPath;
        private final String spoolBase;
        private final Path spoolReservationPath;
        private final TraceEventSpool spool;
        private final int capacity;
        private final int serializerCount;
        private final int newlineBytes;
        private final long writerDelayMillis;
        private final long failAfterCommits;
        private final ArrayBlockingQueue<WorkItem> workQueue;
        private final ArrayBlockingQueue<SerializedItem> commitQueue;
        private final Thread[] serializers;
        private final Thread committer;
        private final AtomicInteger liveSerializers;
        private final AtomicInteger peakWorkDepth = new AtomicInteger();
        private final AtomicInteger peakCommitDepth = new AtomicInteger();
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong serialized = new AtomicLong();
        private final AtomicLong committed = new AtomicLong();
        private final AtomicLong uncompressedBytes = new AtomicLong();
        private final AtomicLong producerBlockedOffers = new AtomicLong();
        private final AtomicLong serializerBlockedOffers = new AtomicLong();
        private final AtomicLong producerBlockedNanos = new AtomicLong();
        private final AtomicLong serializerBlockedNanos = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private volatile boolean accepting = true;

        private AsyncEventWriter(Path outputPath, int capacity, int serializerCount,
                                 long writerDelayMillis, long failAfterCommits) throws IOException {
            this.outputPath = outputPath.toAbsolutePath().normalize();
            this.capacity = capacity;
            this.serializerCount = serializerCount;
            this.writerDelayMillis = writerDelayMillis;
            this.failAfterCommits = failAfterCommits;
            this.newlineBytes = utf8Length(System.lineSeparator());
            Path parent = this.outputPath.getParent();
            if (parent == null) throw new IOException("automatic trace output has no parent: " + outputPath);
            Files.createDirectories(parent);
            if (Files.exists(this.outputPath) || Files.exists(captureSealPath(this.outputPath))) {
                throw new IOException("refuse to overwrite an existing trace or capture seal: " + this.outputPath);
            }
            SpoolReservation reservation = reserveSpoolBase(parent, this.outputPath.getFileName().toString());
            this.spoolBase = reservation.base.toString();
            this.spoolReservationPath = reservation.marker;
            TraceEventSpool initializedSpool;
            try {
                initializedSpool = new TraceEventSpool(spoolBase);
            } catch (IOException error) {
                if (!spoolArtifactsExist(spoolBase)) {
                    Files.deleteIfExists(spoolReservationPath);
                }
                throw error;
            }
            this.spool = initializedSpool;
            this.workQueue = new ArrayBlockingQueue<WorkItem>(capacity);
            this.commitQueue = new ArrayBlockingQueue<SerializedItem>(capacity);
            this.serializers = new Thread[serializerCount];
            this.liveSerializers = new AtomicInteger(serializerCount);
            for (int index = 0; index < serializerCount; index++) {
                final int workerIndex = index;
                serializers[index] = new Thread(new Runnable() {
                    @Override public void run() { serializeLoop(); }
                }, "trace2code-p39-serializer-" + workerIndex);
                serializers[index].setDaemon(true);
                serializers[index].start();
            }
            this.committer = new Thread(new Runnable() {
                @Override public void run() { commitLoop(); }
            }, "trace2code-p39-ordinal-committer");
            this.committer.setDaemon(true);
            this.committer.start();
        }

        private static final class SpoolReservation {
            private final Path base;
            private final Path marker;

            private SpoolReservation(Path base, Path marker) {
                this.base = base;
                this.marker = marker;
            }
        }

        private static SpoolReservation reserveSpoolBase(Path parent, String outputName) throws IOException {
            for (int retry = 0; retry < 65536; retry++) {
                long identifier = NEXT_SPOOL_TEMP_ID.getAndIncrement();
                if (identifier < 0L) {
                    throw new IOException("P39 spool temporary identifier exhausted");
                }
                String stem = outputName + ".p39-ordinal-" + Long.toString(identifier, 36);
                Path base = parent.resolve(stem);
                Path marker = parent.resolve(stem + ".reserve");
                try {
                    // Files.createFile is an atomic CREATE_NEW reservation. A
                    // different writer that chooses the same monotonic id must
                    // observe the existing marker and retry before opening spool files.
                    Files.createFile(marker);
                } catch (java.nio.file.FileAlreadyExistsException collision) {
                    continue;
                }
                if (spoolArtifactsExist(base.toString())) {
                    Files.deleteIfExists(marker);
                    continue;
                }
                return new SpoolReservation(base, marker);
            }
            throw new IOException("unable to reserve a collision-free P39 ordinal spool name");
        }

        private static boolean spoolArtifactsExist(String base) {
            Path data = Paths.get(base + ".spool.data");
            Path index = Paths.get(base + ".spool.index");
            Path blocks = Paths.get(base + ".spool.data.blocks");
            return Files.exists(data) || Files.exists(index) || Files.exists(blocks);
        }

        private void submit(Event event) {
            if (!accepting || failure.get() != null) return;
            WorkItem item = new WorkItem(event, false);
            long started = System.nanoTime();
            boolean waited = false;
            while (accepting && failure.get() == null) {
                if (liveSerializers.get() == 0) {
                    fail(new IOException("all P39 serializer workers exited"));
                    break;
                }
                try {
                    if (workQueue.offer(item, 100L, TimeUnit.MILLISECONDS)) {
                        submitted.incrementAndGet();
                        updatePeak(peakWorkDepth, workQueue.size());
                        if (waited) producerBlockedNanos.addAndGet(System.nanoTime() - started);
                        return;
                    }
                    waited = true;
                    producerBlockedOffers.incrementAndGet();
                    updatePeak(peakWorkDepth, capacity);
                } catch (InterruptedException interrupted) {
                    fail(new IOException("interrupted while applying P39 queue backpressure", interrupted));
                    return;
                }
            }
            if (waited) producerBlockedNanos.addAndGet(System.nanoTime() - started);
        }

        private void serializeLoop() {
            try {
                while (true) {
                    WorkItem item;
                    try {
                        item = workQueue.take();
                    } catch (InterruptedException interrupted) {
                        fail(new IOException("P39 serializer interrupted", interrupted));
                        continue;
                    }
                    if (item.poison) return;
                    if (failure.get() != null) continue;
                    try {
                        String json = item.event.toJson();
                        SerializedItem ready = new SerializedItem(item.event.span.ordinal, json, false);
                        long started = System.nanoTime();
                        boolean waited = false;
                        while (true) {
                            if (!committer.isAlive()) {
                                fail(new IOException("P39 ordinal committer exited before flush"));
                                break;
                            }
                            try {
                                if (commitQueue.offer(ready, 100L, TimeUnit.MILLISECONDS)) {
                                    serialized.incrementAndGet();
                                    updatePeak(peakCommitDepth, commitQueue.size());
                                    if (waited) serializerBlockedNanos.addAndGet(System.nanoTime() - started);
                                    break;
                                }
                                waited = true;
                                serializerBlockedOffers.incrementAndGet();
                                updatePeak(peakCommitDepth, capacity);
                            } catch (InterruptedException interrupted) {
                                fail(new IOException("interrupted while handing off a serialized P39 event", interrupted));
                                break;
                            }
                        }
                    } catch (Throwable error) {
                        fail(error);
                    }
                }
            } finally {
                liveSerializers.decrementAndGet();
            }
        }

        private void commitLoop() {
            while (true) {
                SerializedItem item;
                try {
                    item = commitQueue.take();
                } catch (InterruptedException interrupted) {
                    fail(new IOException("P39 ordinal committer interrupted", interrupted));
                    continue;
                }
                if (item.poison) return;
                if (failure.get() != null) continue;
                try {
                    if (writerDelayMillis > 0L) Thread.sleep(writerDelayMillis);
                    if (failAfterCommits >= 0L && committed.get() >= failAfterCommits) {
                        throw new IOException("synthetic P39 writer failure after " + failAfterCommits + " commits");
                    }
                    int jsonBytes = utf8Length(item.json);
                    spool.append(item.ordinal, item.json);
                    committed.incrementAndGet();
                    uncompressedBytes.addAndGet((long) jsonBytes + (long) newlineBytes);
                } catch (Throwable error) {
                    fail(error);
                    // Keep draining until the poison item so producers cannot deadlock
                    // behind a failed writer and failure reaches the final flush receipt.
                }
            }
        }

        private void finish(long expectedEvents, long enterCount) throws IOException {
            accepting = false;
            for (int index = 0; index < serializerCount; index++) {
                offerWorkerPoison();
            }
            for (Thread serializer : serializers) joinWithoutAbandoning(serializer);
            if (committer.isAlive()) offerCommitPoison();
            else commitQueue.clear();
            joinWithoutAbandoning(committer);

            Throwable cause = failure.get();
            if (cause != null) {
                throw pipelineFailure(expectedEvents, cause);
            }
            if (submitted.get() != expectedEvents || serialized.get() != expectedEvents
                    || committed.get() != expectedEvents) {
                throw pipelineFailure(expectedEvents, new IOException("P39 completion count mismatch: submitted="
                        + submitted.get() + ", serialized=" + serialized.get() + ", committed=" + committed.get()));
            }
            if (enterCount < expectedEvents) {
                throw new IOException("P39 completed-event count exceeds enter count");
            }

            Path stagedBase = Paths.get(spoolBase + ".final");
            Path stagedGzip = Paths.get(stagedBase.toString() + ".gz");
            try {
                spool.finish(stagedBase);
                if (!Files.isRegularFile(stagedGzip)) {
                    throw new IOException("Lang43 spool did not produce its final gzip: " + stagedGzip);
                }
                replaceOutput(stagedGzip, outputPath);
                String outputHash = sha256(outputPath);
                writeCaptureSeal(outputPath, outputHash, Files.size(outputPath),
                        uncompressedBytes.get(), expectedEvents);
                Files.deleteIfExists(spoolReservationPath);
            } catch (IOException error) {
                fail(error);
                throw error;
            }
        }

        private IOException pipelineFailure(long expectedEvents, Throwable cause) {
            return new IOException("bounded P39 writer failed; completed=" + expectedEvents
                    + ", accepted=" + submitted.get() + ", serialized=" + serialized.get()
                    + ", committed=" + committed.get() + ", workQueuePeak=" + peakWorkDepth.get()
                    + ", commitQueuePeak=" + peakCommitDepth.get(), cause);
        }

        private void offerWorkerPoison() {
            while (liveSerializers.get() > 0) {
                try {
                    if (workQueue.offer(WorkItem.POISON, 100L, TimeUnit.MILLISECONDS)) return;
                    updatePeak(peakWorkDepth, capacity);
                } catch (InterruptedException interrupted) {
                    fail(new IOException("interrupted while draining P39 event queue", interrupted));
                }
            }
            workQueue.clear();
        }

        private void offerCommitPoison() {
            while (committer.isAlive()) {
                try {
                    if (commitQueue.offer(SerializedItem.POISON, 100L, TimeUnit.MILLISECONDS)) return;
                    updatePeak(peakCommitDepth, capacity);
                } catch (InterruptedException interrupted) {
                    fail(new IOException("interrupted while draining P39 commit queue", interrupted));
                }
            }
            commitQueue.clear();
        }

        private void joinWithoutAbandoning(Thread thread) {
            while (thread.isAlive()) {
                try {
                    thread.join(100L);
                } catch (InterruptedException interrupted) {
                    fail(new IOException("interrupted while joining P39 writer thread", interrupted));
                }
            }
        }

        private void fail(Throwable error) {
            failure.compareAndSet(null, error);
        }

        private String metricsJson() {
            Throwable error = failure.get();
            return "{\"schema\":\"TRACE_P39_ASYNC_QUEUE_METRICS_V1\",\"enabled\":true"
                    + ",\"input_capacity\":" + capacity
                    + ",\"commit_capacity\":" + capacity
                    + ",\"serializer_workers\":" + serializerCount
                    + ",\"completed_events\":" + COMPLETED_EVENTS.get()
                    + ",\"accepted_events\":" + submitted.get()
                    + ",\"serialized_events\":" + serialized.get()
                    + ",\"committed_events\":" + committed.get()
                    + ",\"input_queue_peak_depth\":" + peakWorkDepth.get()
                    + ",\"commit_queue_peak_depth\":" + peakCommitDepth.get()
                    + ",\"producer_blocked_offer_count\":" + producerBlockedOffers.get()
                    + ",\"serializer_blocked_offer_count\":" + serializerBlockedOffers.get()
                    + ",\"producer_blocked_nanos\":" + producerBlockedNanos.get()
                    + ",\"serializer_blocked_nanos\":" + serializerBlockedNanos.get()
                    + ",\"uncompressed_jsonl_bytes\":" + uncompressedBytes.get()
                    + ",\"writer_failure\":" + jsonString(error == null ? "" : error.toString()) + "}";
        }

        private static void updatePeak(AtomicInteger peak, int value) {
            int current;
            while (value > (current = peak.get()) && !peak.compareAndSet(current, value)) { }
        }

        private static int utf8Length(String value) {
            int bytes = 0;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current <= 0x7f) {
                    bytes++;
                } else if (current <= 0x7ff) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    bytes += 4;
                    index++;
                } else if (Character.isSurrogate(current)) {
                    // String.getBytes(UTF-8) uses the one-byte '?' replacement for an unpaired surrogate.
                    bytes++;
                } else {
                    bytes += 3;
                }
            }
            return bytes;
        }

        private static final class WorkItem {
            static final WorkItem POISON = new WorkItem(null, true);
            final Event event;
            final boolean poison;
            WorkItem(Event event, boolean poison) { this.event = event; this.poison = poison; }
        }

        private static final class SerializedItem {
            static final SerializedItem POISON = new SerializedItem(-1L, "", true);
            final long ordinal;
            final String json;
            final boolean poison;
            SerializedItem(long ordinal, String json, boolean poison) {
                this.ordinal = ordinal;
                this.json = json;
                this.poison = poison;
            }
        }
    }

    private static final Comparator<Event> EVENT_ORDER = new Comparator<Event>() {
        @Override
        public int compare(Event left, Event right) {
            return Long.compare(left.span.ordinal, right.span.ordinal);
        }
    };

    /** Sort and spill one bounded event batch; the final trace is ordinal-ordered. */
    private static void spillChunkLocked() throws IOException {
        if (EVENTS.isEmpty()) {
            return;
        }
        Path path = output;
        if (path == null) {
            return;
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("automatic trace output has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        Path directory = spillDirectory;
        if (directory == null) {
            directory = Files.createTempDirectory(parent, ".trace-spill-");
            spillDirectory = directory;
        }
        Collections.sort(EVENTS, EVENT_ORDER);
        Path chunk = directory.resolve("chunk-" + SPILL_CHUNKS.size() + ".tsv.gz");
        Path temporary = chunk.resolveSibling(chunk.getFileName().toString() + ".tmp");
        long firstOrdinal = Long.MAX_VALUE;
        long lastOrdinal = Long.MIN_VALUE;
        String separator = System.lineSeparator();
        CountingOutputStream plainCounter;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                (plainCounter = new CountingOutputStream(new FastGzipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(temporary))))),
                StandardCharsets.UTF_8))) {
            for (Event event : EVENTS) {
                String json = event.toJson();
                writer.write(Long.toString(event.span.ordinal));
                writer.write('\t');
                writer.write(json);
                writer.write(separator);
                firstOrdinal = Math.min(firstOrdinal, event.span.ordinal);
                lastOrdinal = Math.max(lastOrdinal, event.span.ordinal);
            }
        }
        replaceOutput(temporary, chunk);
        SPILL_CHUNKS.add(chunk);
        int rows = EVENTS.size();
        appendChunkCheckpoint(chunk, rows, firstOrdinal, lastOrdinal, plainCounter.count);
        SPILLED_EVENTS += rows;
        EVENTS.clear();
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(OutputStream output) throws IOException {
            super(output);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendChunkCheckpoint(Path chunk, int rows, long firstOrdinal,
                                              long lastOrdinal, long uncompressedBytes)
            throws IOException {
        Path directory = spillDirectory;
        Path manifest = directory.resolve("chunks.jsonl");
        String record = "{\"chunk\":\"" + chunk.getFileName() + "\",\"rows\":" + rows
                + ",\"first_ordinal\":" + firstOrdinal + ",\"last_ordinal\":" + lastOrdinal
                + ",\"uncompressed_bytes\":" + uncompressedBytes + ",\"compressed_bytes\":"
                + Files.size(chunk) + ",\"sha256\":\"" + sha256(chunk) + "\"}\n";
        try (FileChannel channel = FileChannel.open(manifest, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(record);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    private static void writeReadyCheckpoint(Path path) throws IOException {
        Path directory = spillDirectory;
        Path manifest = directory.resolve("chunks.jsonl");
        if (SPILLED_EVENTS != COMPLETED_EVENTS.get()) {
            throw new IOException("durable spill row count differs from completed events: "
                    + SPILLED_EVENTS + " != " + COMPLETED_EVENTS.get());
        }
        String ready = "{\"schema\":\"TRACEOPT_R09_GZIP_SPILL_READY_V1\",\"status\":\"READY_TO_MERGE\","
                + "\"output_path\":\"" + jsonEscape(path.toAbsolutePath().normalize().toString())
                + "\",\"chunk_count\":" + SPILL_CHUNKS.size()
                + ",\"manifest_sha256\":\"" + sha256(manifest) + "\",\"event_count\":"
                + COMPLETED_EVENTS.get() + ",\"spilled_event_count\":" + SPILLED_EVENTS
                + ",\"enter_count\":" + ENTER_CALLS.get() + "}\n";
        Path temporary = directory.resolve("ready.json.tmp");
        Files.write(temporary, ready.getBytes(StandardCharsets.UTF_8));
        replaceOutput(temporary, directory.resolve("ready.json"));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static Path captureSealPath(Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".capture.json");
    }

    private static void writeCaptureSeal(Path path, String compressedSha256,
                                        long compressedBytes, long uncompressedBytes,
                                        long rows) throws IOException {
        String receipt = "{\"schema\":\"TRACE_R09_GZIP_CAPTURE_SEAL_V1\",\"complete\":true,"
                + "\"terminal_newline\":true,\"compressed_sha256\":\"" + compressedSha256
                + "\",\"compressed_bytes\":" + compressedBytes
                + ",\"uncompressed_jsonl_bytes\":" + uncompressedBytes
                + ",\"rows\":" + rows + "}\n";
        Path target = captureSealPath(path);
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temporary, receipt.getBytes(StandardCharsets.UTF_8));
        replaceOutput(temporary, target);
    }

    private static final class ChunkCursor {
        private final BufferedReader reader;
        private final int chunkIndex;
        private long ordinal;
        private long rowCount;
        private long previousOrdinal = Long.MIN_VALUE;
        private String json;

        private ChunkCursor(Path path, int index) throws IOException {
            reader = new BufferedReader(new java.io.InputStreamReader(
                    new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))),
                    StandardCharsets.UTF_8));
            chunkIndex = index;
        }

        private boolean advance() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                json = null;
                return false;
            }
            int separator = line.indexOf('\t');
            if (separator <= 0) {
                throw new IOException("Invalid automatic trace spill row");
            }
            ordinal = Long.parseLong(line.substring(0, separator));
            if (rowCount > 0 && ordinal <= previousOrdinal) {
                throw new IOException("non-increasing ordinal within automatic trace spill chunk");
            }
            previousOrdinal = ordinal;
            rowCount++;
            json = line.substring(separator + 1);
            return true;
        }
    }

    private static void replaceOutput(Path temporary, Path path) throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void mergeChunks(Path path) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName().toString()
                + ".tmp-" + System.nanoTime());
        PriorityQueue<ChunkCursor> queue = new PriorityQueue<ChunkCursor>(
                Math.max(1, SPILL_CHUNKS.size()), new Comparator<ChunkCursor>() {
                    @Override
                    public int compare(ChunkCursor left, ChunkCursor right) {
                        int byOrdinal = Long.compare(left.ordinal, right.ordinal);
                        return byOrdinal != 0 ? byOrdinal
                                : Integer.compare(left.chunkIndex, right.chunkIndex);
                    }
                });
        List<ChunkCursor> cursors = new ArrayList<ChunkCursor>(SPILL_CHUNKS.size());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        long rowCount = 0L;
        long previousOrdinal = Long.MIN_VALUE;
        String separator = System.lineSeparator();
        CountingOutputStream plainCounter;
        try {
            for (int index = 0; index < SPILL_CHUNKS.size(); index++) {
                ChunkCursor cursor = new ChunkCursor(SPILL_CHUNKS.get(index), index);
                cursors.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    (plainCounter = new CountingOutputStream(new FastGzipOutputStream(
                            new BufferedOutputStream(new DigestOutputStream(
                                    Files.newOutputStream(temporary), digest))))),
                    StandardCharsets.UTF_8))) {
                while (!queue.isEmpty()) {
                    ChunkCursor cursor = queue.remove();
                    if (rowCount > 0 && cursor.ordinal <= previousOrdinal) {
                        throw new IOException("duplicate or out-of-order ordinal across spill chunks");
                    }
                    previousOrdinal = cursor.ordinal;
                    writer.write(cursor.json);
                    writer.write(separator);
                    rowCount++;
                    if (cursor.advance()) {
                        queue.add(cursor);
                    }
                }
            }
            if (rowCount != COMPLETED_EVENTS.get()) {
                throw new IOException("spill merge rows differ from completed event count: "
                        + rowCount + " != " + COMPLETED_EVENTS.get());
            }
            replaceOutput(temporary, path);
            StringBuilder outputHash = new StringBuilder(64);
            for (byte value : digest.digest()) {
                outputHash.append(String.format("%02x", value & 0xff));
            }
            writeCaptureSeal(path, outputHash.toString(), Files.size(path), plainCounter.count, rowCount);
            Path directory = spillDirectory;
            Path parent = path.toAbsolutePath().getParent();
            if (directory != null && directory.toAbsolutePath().getParent().equals(parent)
                    && directory.getFileName().toString().startsWith(".trace-spill-")) {
                for (Path chunk : SPILL_CHUNKS) {
                    Files.deleteIfExists(chunk);
                }
                Files.deleteIfExists(directory.resolve("chunks.jsonl"));
                Files.deleteIfExists(directory.resolve("ready.json"));
                Files.deleteIfExists(directory);
                spillDirectory = null;
                SPILL_CHUNKS.clear();
            }
        } finally {
            for (ChunkCursor cursor : cursors) {
                try {
                    cursor.reader.close();
                } catch (IOException ignored) {
                    // Preserve the primary merge error.
                }
            }
        }
    }

    private static void writeEvents() {
        Path path = output;
        if (path == null) {
            return;
        }
        synchronized (EVENTS) {
            if (spillFailure != null) {
                throw new IllegalStateException("failed to spill automatic trace " + path, spillFailure);
            }
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (SPILL_CHUNKS.isEmpty()) {
                    Collections.sort(EVENTS, EVENT_ORDER);
                    Path temporary = path.resolveSibling(path.getFileName().toString()
                            + ".tmp-" + System.nanoTime());
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance("SHA-256");
                    } catch (NoSuchAlgorithmException impossible) {
                        throw new IOException("SHA-256 is unavailable", impossible);
                    }
                    String separator = System.lineSeparator();
                    CountingOutputStream plainCounter;
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            (plainCounter = new CountingOutputStream(new FastGzipOutputStream(
                                    new BufferedOutputStream(new DigestOutputStream(
                                            Files.newOutputStream(temporary), digest))))),
                            StandardCharsets.UTF_8))) {
                        for (Event event : EVENTS) {
                            String json = event.toJson();
                            writer.write(json);
                            writer.write(separator);
                        }
                    }
                    replaceOutput(temporary, path);
                    StringBuilder outputHash = new StringBuilder(64);
                    for (byte value : digest.digest()) {
                        outputHash.append(String.format("%02x", value & 0xff));
                    }
                    writeCaptureSeal(path, outputHash.toString(), Files.size(path),
                            plainCounter.count, EVENTS.size());
                    EVENTS.clear();
                } else {
                    spillChunkLocked();
                    writeReadyCheckpoint(path);
                    mergeChunks(path);
                }
            } catch (IOException error) {
                throw new IllegalStateException("failed to write automatic trace " + path, error);
            }
        }
    }

    private static final class Span {
        private final long ordinal;
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final String file;
        private final int line;
        private final String returnType;
        private final long threadId;
        private final int depth;
        private final String parentSymbol;
        private final long startNs;

        private Span(long ordinal, String className, String methodName, String descriptor,
                     String file, int line, String returnType, long threadId, int depth,
                     String parentSymbol, long startNs) {
            this.ordinal = ordinal;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.file = file;
            this.line = line;
            this.returnType = returnType;
            this.threadId = threadId;
            this.depth = depth;
            this.parentSymbol = parentSymbol;
            this.startNs = startNs;
        }
    }

    private static final class Event {
        private final Span span;
        private final long durationNs;
        private final String outcome;
        private final String exception;

        private Event(Span span, long durationNs, String outcome, String exception) {
            this.span = span;
            this.durationNs = durationNs;
            this.outcome = outcome;
            this.exception = exception;
        }

        private static Event returned(Span span, long durationNs) {
            return new Event(span, durationNs, "return", "");
        }

        private static Event failed(Span span, long durationNs, Throwable error) {
            String type = error == null ? "java.lang.Throwable" : error.getClass().getName();
            return new Event(span, durationNs, "exception", type);
        }

        private String toJson() {
            StringBuilder result = new StringBuilder();
            result.append("{\"ordinal\":").append(span.ordinal);
            result.append(",\"class\":").append(jsonString(span.className));
            result.append(",\"method\":").append(jsonString(span.methodName));
            result.append(",\"descriptor\":").append(jsonString(span.descriptor));
            result.append(",\"symbol\":").append(jsonString(stableSymbol(span)));
            result.append(",\"parent_symbol\":").append(jsonString(span.parentSymbol));
            result.append(",\"file\":").append(jsonString(span.file));
            result.append(",\"line\":").append(span.line);
            result.append(",\"thread_id\":").append(span.threadId);
            result.append(",\"depth\":").append(span.depth);
            result.append(",\"duration_ns\":").append(durationNs);
            result.append(",\"outcome\":").append(jsonString(outcome));
            result.append(",\"return_type\":").append(jsonString(span.returnType));
            result.append(",\"branch\":").append(jsonString(MASKED_BRANCH));
            result.append(",\"exception\":").append(jsonString(exception));
            result.append("}");
            return result.toString();
        }
    }

    private static String stableSymbol(Span span) {
        return span.className + "#" + span.methodName + span.descriptor
                + "@" + span.file + ":" + span.line;
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                    break;
            }
        }
        result.append('"');
        return result.toString();
    }
}
