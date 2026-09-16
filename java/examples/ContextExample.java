import static io.github.dongzoor.pinocontext.LogContext.withLogContext;
import static io.github.dongzoor.pinocontext.LogContext.wrapSupplier;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runnable check of scoped MDC propagation. Besides logging, it verifies the
 * request context observed in the parent scope, a nested scope, a wrapped worker
 * task, and after each scope exits on both the original thread and the reused
 * worker thread. Any deviation throws {@link IllegalStateException}, so a
 * successful run (exit code 0) proves the behavior, not just that it launched.
 *
 * <p><b>Team registry consumption</b> (the artifact published to the private
 * GitLab Maven package registry). The canonical source stays on public GitHub and
 * the npm package distribution is unchanged; only the Java artifact is served
 * from the internal registry. Supply the two variables privately - never commit
 * them - then run from the repository root:
 *
 * <pre>
 * # Set MAVEN_REGISTRY_URL to the private project Maven endpoint.
 * # Load MAVEN_REGISTRY_TOKEN from your approved secret store (read_package_registry).
 *
 * mvn -s java/maven-settings.xml -f java/examples/maven/pom.xml compile exec:java
 * gradle -p java/examples/gradle run
 * </pre>
 *
 * Pin another published version with {@code -DpinoContextKitVersion=<version>}
 * (Maven) or {@code -PpinoContextKitVersion=<version>} (Gradle). Both consumers
 * compile this file directly and load ./logback.xml from the classpath.
 *
 * <p><b>Local installation</b> without any registry (JDK 11+, Maven), building the
 * library from this checkout and running the example as a single-file program:
 *
 * <pre>
 * mvn -f java/pom.xml install dependency:build-classpath -Dmdep.outputFile=target/example-classpath.txt
 * java -Dlogback.configurationFile=java/examples/logback.xml \
 *   -cp "java/target/pino-context-kit-0.1.0.jar:$(cat java/target/example-classpath.txt)" \
 *   java/examples/ContextExample.java
 * </pre>
 *
 * After install, local Maven consumers depend on io.github.dongzoor:pino-context-kit:0.1.0
 * and Gradle consumers add mavenLocal(). Consumers must supply their own SLF4J 2
 * provider; Logback is only a runtime dependency of the examples.
 */
public class ContextExample {
    private static final Logger logger = LoggerFactory.getLogger(ContextExample.class);
    private static final String REQUEST_ID = "request_id";
    private static final String JOB_ID = "job_id";
    private static final String[] CONTEXT_KEYS = {REQUEST_ID, JOB_ID};

    public static void main(String[] args) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<String> result = withLogContext(Map.of(REQUEST_ID, "request-a"), () -> {
                logger.info("request");
                expectContext("request scope", Map.of(REQUEST_ID, "request-a"));
                withLogContext(Map.of(JOB_ID, "job-1"), () -> {
                    logger.info("nested");
                    expectContext("nested scope", Map.of(REQUEST_ID, "request-a", JOB_ID, "job-1"));
                });
                expectContext("request scope after nested exit", Map.of(REQUEST_ID, "request-a"));
                return CompletableFuture.supplyAsync(wrapSupplier(() -> {
                    logger.info("worker");
                    expectContext("wrapped worker task", Map.of(REQUEST_ID, "request-a"));
                    return "completed";
                }), worker);
            });
            logger.info("outside: {}", result.join());
            expectContext("original thread after request exit", Map.of());
            CompletableFuture.runAsync(() -> {
                logger.info("worker-outside");
                expectContext("reused worker after wrapped task", Map.of());
            }, worker).join();
        } finally {
            worker.shutdown();
        }
    }

    /**
     * Compares only the request keys this example manages, so it stays agnostic
     * to how the MDC adapter represents an empty context.
     */
    private static void expectContext(String scope, Map<String, String> expected) {
        for (String key : CONTEXT_KEYS) {
            String actual = MDC.get(key);
            String wanted = expected.get(key);
            if (!Objects.equals(actual, wanted)) {
                throw new IllegalStateException(scope + " on " + Thread.currentThread().getName()
                        + ": MDC " + key + " was " + actual + ", expected " + wanted);
            }
        }
    }
}
