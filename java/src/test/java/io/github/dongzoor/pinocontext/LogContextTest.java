package io.github.dongzoor.pinocontext;

import static io.github.dongzoor.pinocontext.LogContext.withLogContext;
import static io.github.dongzoor.pinocontext.LogContext.wrap;
import static io.github.dongzoor.pinocontext.LogContext.wrapCallable;
import static io.github.dongzoor.pinocontext.LogContext.wrapSupplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LogContextTest {
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private Map<String, String> originalMdc;
    private Logger logger;
    private AppenderBase<ILoggingEvent> appender;

    @BeforeEach
    void capture(TestInfo test) {
        originalMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        logger = (Logger) LoggerFactory.getLogger(getClass().getName() + "." + test.getTestMethod().orElseThrow().getName());
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restore() {
        logger.detachAppender(appender);
        appender.stop();
        if (originalMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(originalMdc);
        }
    }

    @Test
    void nestedScopesSnapshotInputsAndRestoreExternalMdcAfterFailure() {
        MDC.put("trace_id", "external-trace");
        Map<String, String> context = new HashMap<>(Map.of("request_id", "parent"));
        IllegalStateException failure = new IllegalStateException("job failed");
        Object result = new Object();

        assertSame(result, withLogContext(context, () -> {
            context.put("request_id", "changed-after-entry");
            logger.info("parent-before");
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                withLogContext(Map.of("request_id", "child", "job_id", "job-1"), (Runnable) () -> {
                    logger.info("child");
                    MDC.put("temporary", "discard-me");
                    throw failure;
                })));
            logger.info("parent-after");
            return result;
        }));
        logger.info("outside");

        assertContext("parent-before", Map.of("trace_id", "external-trace", "request_id", "parent"));
        assertContext("child", Map.of("trace_id", "external-trace", "request_id", "child", "job_id", "job-1"));
        assertContext("parent-after", Map.of("trace_id", "external-trace", "request_id", "parent"));
        assertContext("outside", Map.of("trace_id", "external-trace"));
        assertEquals(Map.of("request_id", "changed-after-entry"), context);

        MDC.clear();
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
            withLogContext(Map.of("request_id", "failed"), (Supplier<Object>) () -> { throw failure; })));
        logger.info("empty-after-failure");
        assertContext("empty-after-failure", Map.of());
    }

    @Test
    void overlappingRequestsKeepIndependentCapturedContexts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<String> first = request("a", barrier);
            Callable<String> second = request("b", barrier);
            Future<String> a = pool.submit(first);
            Future<String> b = pool.submit(second);
            assertEquals("a", a.get(5, TimeUnit.SECONDS));
            assertEquals("b", b.get(5, TimeUnit.SECONDS));
            logger.info("submitter-outside");
            assertContext("a-start", Map.of("request_id", "a"));
            assertContext("b-start", Map.of("request_id", "b"));
            assertContext("a-nested", Map.of("request_id", "a", "job_id", "a-job"));
            assertContext("b-nested", Map.of("request_id", "b", "job_id", "b-job"));
            assertContext("a-end", Map.of("request_id", "a"));
            assertContext("b-end", Map.of("request_id", "b"));
            assertContext("submitter-outside", Map.of());
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<String> request(String id, CyclicBarrier barrier) {
        return withLogContext(Map.of("request_id", id), () -> wrapCallable(() -> {
            logger.info(id + "-start");
            barrier.await(5, TimeUnit.SECONDS);
            withLogContext(Map.of("job_id", id + "-job"), () -> {
                logger.info(id + "-nested");
            });
            barrier.await(5, TimeUnit.SECONDS);
            logger.info(id + "-end");
            return MDC.get("request_id");
        }));
    }

    @Test
    void reusedWorkerRestoresItsContextAfterFailedAndEmptyCapturedTasks() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        IOException checkedFailure = new IOException("checked failure");
        IllegalArgumentException failure = new IllegalArgumentException("failed task");
        try {
            pool.submit(() -> MDC.put("worker", "original")).get(5, TimeUnit.SECONDS);
            Runnable captured = withLogContext(Map.of("request_id", "captured"), () -> wrap(() -> {
                logger.info("captured");
                MDC.put("temporary", "discard-me");
                throw failure;
            }));
            MDC.put("request_id", "changed-after-capture");
            ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> pool.submit(captured).get(5, TimeUnit.SECONDS));
            assertSame(failure, thrown.getCause());
            assertContext("captured", Map.of("request_id", "captured"));
            assertEquals("changed-after-capture", MDC.get("request_id"));

            // Reusing the same wrapper must not retain mutations from its first run.
            assertThrows(ExecutionException.class, () -> pool.submit(captured).get(5, TimeUnit.SECONDS));
            assertEquals(Map.of("request_id", "captured"), events.get(1).getMDCPropertyMap());
            MDC.clear();
            Callable<Void> empty = wrapCallable(() -> {
                logger.info("empty-capture");
                throw checkedFailure;
            });
            ExecutionException checked = assertThrows(ExecutionException.class,
                () -> pool.submit(empty).get(5, TimeUnit.SECONDS));
            assertSame(checkedFailure, checked.getCause());
            pool.submit(wrap(() -> logger.info("empty-runnable"))).get(5, TimeUnit.SECONDS);
            pool.submit(() -> logger.info("worker-restored")).get(5, TimeUnit.SECONDS);
            assertContext("empty-capture", Map.of());
            assertContext("empty-runnable", Map.of());
            assertContext("worker-restored", Map.of("worker", "original"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void futureSuppliersCaptureBeforeScopeExitAndRestoreAfterSuccessAndFailure() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        RuntimeException failure = new RuntimeException("future failed");
        try {
            Supplier<String> captured = withLogContext(Map.of("request_id", "future"), () -> wrapSupplier(() -> {
                logger.info("future");
                return MDC.get("request_id");
            }));
            assertEquals("future", CompletableFuture.supplyAsync(captured, pool).get(5, TimeUnit.SECONDS));
            Supplier<String> failing = withLogContext(Map.of("request_id", "failed-future"), () -> wrapSupplier(() -> {
                logger.info("failed-future");
                throw failure;
            }));
            CompletableFuture<String> future = CompletableFuture.supplyAsync(failing, pool);
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertSame(failure, thrown.getCause());
            assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
            assertNull(pool.submit((Callable<String>) () -> MDC.get("request_id")).get(5, TimeUnit.SECONDS));
            pool.submit(() -> logger.info("after-future")).get(5, TimeUnit.SECONDS);
            assertContext("future", Map.of("request_id", "future"));
            assertContext("failed-future", Map.of("request_id", "failed-future"));
            assertContext("after-future", Map.of());
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertContext(String message, Map<String, String> expected) {
        ILoggingEvent event = events.stream().filter(e -> message.equals(e.getFormattedMessage())).findFirst().orElseThrow();
        assertEquals(expected, event.getMDCPropertyMap(), message);
    }
}
