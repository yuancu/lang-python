/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.time.Duration;
import java.util.Map;
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
import org.opensearch.threadpool.ThreadPool;

public class ExecutionUtils {
    private static final Logger logger = LogManager.getLogger();
    @Getter @Setter private static int TIMEOUT_IN_SECONDS = 10;

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

    public static Value executePython(
            ThreadPool threadPool,
            String code,
            Map<String, ?> params,
            Map<String, ?> doc,
            Double score) {
        try (final ExecutorService executor = threadPool.executor(ThreadPool.Names.GENERIC);
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
                                .build()) {
            final Future<Value> futureResult =
                    executor.submit(() -> executeWorker(context, code, params, doc, score));
            try {
                return futureResult.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                context.interrupt(Duration.ZERO);
                logger.debug("Script not evaluated within 10 seconds, interrupted.");
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to run python code", e);
            return null;
        }
    }

    public static String executePythonAsString(
            ThreadPool threadPool,
            String code,
            Map<String, ?> params,
            Map<String, ?> doc,
            Double score) {
        Value result = executePython(threadPool, code, params, doc, score);
        if (result == null) {
            logger.debug("Did not get any result from Python execution");
            return "";
        }
        if (result.isString()) {
            return result.asString();
        } else if (result.isNumber()) {
            return String.valueOf(result.asDouble());
        } else if (result.isBoolean()) {
            return String.valueOf(result.asBoolean());
        } else {
            logger.warn(
                    "Python execution only accepts string, number, or boolean as results for the"
                            + " time being, but got: {}",
                    result);
            return result.toString();
        }
    }
}
