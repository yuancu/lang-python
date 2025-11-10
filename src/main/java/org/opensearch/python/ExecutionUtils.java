/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;
import org.opensearch.common.util.concurrent.FutureUtils;
import org.opensearch.python.phase.SemanticAnalyzer;
import org.opensearch.script.ScriptException;
import org.opensearch.threadpool.ThreadPool;

public class ExecutionUtils {
    private static final Logger logger = LogManager.getLogger();
    private static final String MODULE_META_SIMPLE_NAME = "module";
    @Getter @Setter private static int TIMEOUT_IN_SECONDS = 20;

    private static Value executeWorker(
            Context context, String code, Map<String, ?> params, Map<String, ?> doc, Double score) {
        if (params != null) {
            logger.debug("Params: {}", params.toString());
            context.getBindings("python").putMember("params", params);
        }
        if (doc != null) {
            logger.debug("Doc: {}", doc.toString());
            context.getBindings("python").putMember("doc", doc);
        }
        if (score != null) {
            logger.debug("Score: {}", score);
            context.getBindings("python").putMember("_score", score);
        }
        return context.eval("python", code);
    }

    public static Object executePython(
            ThreadPool threadPool,
            String code,
            Map<String, ?> params,
            Map<String, ?> doc,
            Double score) {
        SemanticAnalyzer analyzer = new SemanticAnalyzer(code + '\n');
        analyzer.checkSemantic();
        final ExecutorService executor = threadPool.executor(ThreadPool.Names.GENERIC);
        // A working context without capabilities to import packages:
        // Context context = Context.newBuilder("python")
        //            .sandbox(SandboxPolicy.TRUSTED)
        //            .allowHostAccess(HostAccess.ALL).build()
        final Context context =
                GraalPyResources.contextBuilder()
                        .sandbox(SandboxPolicy.TRUSTED)
                        .allowHostAccess(HostAccess.ALL)
                        // The following 2 options are necessary for importing 3-rd party
                        // libraries
                        // that load native libraries
                        .allowExperimentalOptions(true)
                        .option("python.IsolateNativeModules", "true")
                        .build();

        try {
            final Future<Value> futureResult =
                    executor.submit(() -> executeWorker(context, code, params, doc, score));

            try {
                Value result = futureResult.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

                // Extract the value before closing the context
                return extractValueBeforeContextClose(result);

            } catch (TimeoutException e) {
                // future.cancel is a forbidden API
                FutureUtils.cancel(futureResult);

                // Force close context immediately for import-related hangs
                executor.submit(
                        () -> {
                            try {
                                context.interrupt(Duration.ofSeconds(1));
                            } catch (Exception ex) {
                                logger.debug("Failed to interrupt context: {}", ex.getMessage());
                            }
                            try {
                                context.close(true);
                            } catch (Exception ex) {
                                logger.debug("Failed to force close context: {}", ex.getMessage());
                            }
                        });

                throw new ScriptException(
                        String.format(
                                Locale.ROOT,
                                "Script execution timed out after %d seconds",
                                TIMEOUT_IN_SECONDS),
                        e,
                        List.of(),
                        code,
                        "python");
            } catch (ExecutionException | InterruptedException e) {
                throw new ScriptException(
                        String.format(
                                Locale.ROOT,
                                "Script execution failed with error: %s",
                                e.getMessage()),
                        e,
                        List.of(),
                        code,
                        "python");
            }
        } catch (Exception e) {
            throw new ScriptException(
                    String.format(
                            Locale.ROOT, "Script execution failed with error: %s", e.getMessage()),
                    e,
                    List.of(),
                    code,
                    "python");
        } finally {
            // Ensure context is always closed after execution completes
            try {
                context.close(true);
            } catch (Exception e) {
                logger.debug("Context already closed or error closing: {}", e.getMessage());
            }
        }
    }

    private static Object extractValueBeforeContextClose(Value result) {
        if (result == null || result.isNull()) {
            return null;
        }

        try {
            // If it's a module object, treat it as None/empty
            if (result.getMetaObject() != null
                    && MODULE_META_SIMPLE_NAME.equals(result.getMetaObject().getMetaSimpleName())) {
                return null;
            }

            if (result.isString()) {
                return result.asString();
            }
            if (result.isNumber()) {
                return result.asDouble();
            }
            if (result.isBoolean()) {
                return result.asBoolean();
            }
            return result.toString();
        } catch (Exception e) {
            logger.warn("Error extracting value from result: {}", e.getMessage());
            return result.toString();
        }
    }

    public static String executePythonAsString(
            ThreadPool threadPool,
            String code,
            Map<String, ?> params,
            Map<String, ?> doc,
            Double score) {
        Object result = executePython(threadPool, code, params, doc, score);
        if (result == null) {
            logger.debug("Did not get any result from Python execution");
            return "";
        }
        if (result instanceof String) {
            return (String) result;
        } else if (result instanceof Double) {
            return String.valueOf(result);
        } else if (result instanceof Boolean) {
            return String.valueOf(result);
        } else {
            logger.warn(
                    "Python execution only accepts string, number, or boolean as results for the"
                            + " time being, but got: {}",
                    result);
            return result.toString();
        }
    }
}
