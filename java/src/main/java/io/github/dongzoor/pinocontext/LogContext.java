package io.github.dongzoor.pinocontext;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Scoped context for native SLF4J loggers on Java 11 and later.
 *
 * <p>The application supplies an MDC-capable SLF4J 2 provider, such as Logback,
 * and configures its layout/encoder to include MDC (%X in a Logback pattern).
 * No logger or logging backend is installed by this library. Unlike the Node.js
 * API, context values are strings and JSON layout and per-event field collision
 * behavior belong to the logging backend, not this library.
 *
 * <p>Scopes run synchronously on the calling thread. For asynchronous work,
 * wrap each task or continuation <em>before</em> leaving the submitting scope.
 * Returning a future does not propagate context to its callbacks automatically.
 * Wrappers capture the complete MDC at creation, replace (not merge with) the
 * executing thread's MDC, and restore that thread's previous MDC on exit.
 * Direct MDC mutations inside a scope are visible until that scope exits.
 */
public final class LogContext {
    private LogContext() {}

    /**
     * Snapshots and merges context with the current MDC, with child keys winning.
     * Restores the complete previous MDC even when the action throws.
     *
     * @param context string fields to add or override
     * @param action synchronous action
     */
    public static void withLogContext(Map<String, String> context, Runnable action) {
        Objects.requireNonNull(action, "action");
        Map<String, String> previous = enter(context);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * Runs a value-producing action with the same scope semantics as the
     * Runnable overload. Returns its result and propagates its exception unchanged.
     *
     * @param context string fields to add or override
     * @param action synchronous computation
     * @param <T> result type
     * @return the action's result
     */
    public static <T> T withLogContext(Map<String, String> context, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Map<String, String> previous = enter(context);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * Captures MDC now for a Runnable submitted to an executor or a new thread.
     *
     * @param action task to run with the captured context
     * @return a reusable task that restores the executing thread's MDC on exit
     */
    public static Runnable wrap(Runnable action) {
        Objects.requireNonNull(action, "action");
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(captured);
            try {
                action.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * Captures MDC now for an ExecutorService task, preserving checked exceptions.
     *
     * @param action task to run with the captured context
     * @param <T> result type
     * @return a reusable task that restores the executing thread's MDC on exit
     */
    public static <T> Callable<T> wrapCallable(Callable<T> action) {
        Objects.requireNonNull(action, "action");
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(captured);
            try {
                return action.call();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * Captures MDC now for a CompletableFuture.supplyAsync computation.
     *
     * @param action computation to run with the captured context
     * @param <T> result type
     * @return a reusable supplier that restores the executing thread's MDC on exit
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(captured);
            try {
                return action.get();
            } finally {
                restore(previous);
            }
        };
    }

    private static Map<String, String> enter(Map<String, String> context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (previous == null || previous.isEmpty()) {
            // MDC.setContextMap copies the map; no intermediate snapshot is needed.
            MDC.setContextMap(context);
        } else {
            Map<String, String> merged = new HashMap<>(previous);
            merged.putAll(context);
            MDC.setContextMap(merged);
        }
        return previous;
    }

    private static void restore(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
