/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
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
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;
import org.opensearch.common.util.concurrent.FutureUtils;
import org.opensearch.python.phase.SemanticAnalyzer;
import org.opensearch.script.ScriptException;
import org.opensearch.threadpool.ThreadPool;

public class ExecutionUtils {
    @Getter @Setter private static int TIMEOUT_IN_SECONDS = 20;
    private static final Logger logger = LogManager.getLogger();
    private static final String MODULE_META_SIMPLE_NAME = "module";
    // Reference:
    // https://github.com/graalvm/graal-languages-demos/blob/main/graalpy/graalpy-javase-guide/README.md
    static VirtualFileSystem vfs =
            VirtualFileSystem.newBuilder()
                    .allowHostIO(VirtualFileSystem.HostIO.READ)
                    // The value is set in build.gradle
                    .resourceDirectory("GRAALPY-VFS/org.opensearch/lang-python")
                    .build();
    static Path resourcesDir;

    static {
        // Extract VFS resources (Python packages, native extensions) to the cluster's temp
        // directory. OpenSearch sets java.io.tmpdir to a cluster-specific location that's writable
        // within the security manager constraints. Native libraries must be extracted to a real
        // filesystem path to be loaded by Python's C extension loader.
        // Reference: https://www.graalvm.org/python/docs/#virtual-filesystem
        resourcesDir = Path.of(System.getProperty("java.io.tmpdir"), "graalpy-resources");
        logger.info("Extracting GraalPy resources to: {}", resourcesDir.toAbsolutePath());
        try {
            GraalPyResources.extractVirtualFileSystemResources(vfs, resourcesDir);
        } catch (Exception e) {
            logger.error("CAN'T EXTRACT RESOURCES TO TARGET", e);
        }
    }

    private static Value executeWorker(
            Context context,
            String code,
            Map<String, ?> params,
            Map<String, ?> doc,
            Map<String, ?> ctx,
            Double score) {
        if (params != null) {
            context.getBindings("python").putMember("params", params);
        }
        if (doc != null) {
            context.getBindings("python").putMember("doc", doc);
        }
        if (ctx != null) {
            context.getBindings("python").putMember("ctx", ctx);
        }
        if (score != null) {
            context.getBindings("python").putMember("_score", score);
        }
        return context.eval("python", code);
    }

    private static Context createContext() {
        Context.Builder builder =
                GraalPyResources.contextBuilder(vfs)
                        .sandbox(SandboxPolicy.TRUSTED)
                        .allowHostAccess(HostAccess.ALL)
                        // The following options are necessary for importing 3-rd party
                        // libraries that load native libraries (like numpy)
                        .allowExperimentalOptions(true)
                        .allowIO(IOAccess.ALL)
                        .allowCreateThread(true)
                        .allowNativeAccess(true)
                        .allowCreateProcess(true)
                        // Reference for Python context options:
                        // https://www.graalvm.org/python/docs/#python-context-options
                        .option(
                                "python.Executable",
                                String.format(
                                        Locale.ROOT,
                                        "%s/venv/bin/graalpy",
                                        resourcesDir.toAbsolutePath()))
                        // Enable verbose warnings for debugging native extensions
                        .option("python.WarnExperimentalFeatures", "true")
                        // Show detailed stack traces for debugging
                        .option("engine.ShowInternalStackFrames", "true")
                        .option("engine.PrintInternalStackTrace", "true");

        // Native module isolation requires patchelf subprocess execution, which is
        // incompatible with Java Security Manager. Only enable when Security Manager is not active.
        // When disabled, native modules are shared across contexts (no isolation).
        if (System.getSecurityManager() == null) {
            // Enable native module isolation - creates isolated copies for each context
            // patchelf is provided via pip package (patchelf==0.17.2.4) in build.gradle
            builder.option("python.IsolateNativeModules", "true")
                    // Allow subprocesses to inherit environment variables (including PATH)
                    // This enables GraalPy to find and execute patchelf from venv
                    .allowEnvironmentAccess(EnvironmentAccess.INHERIT);
            logger.info("Enabled native module isolation (Security Manager not active)");
        } else {
            builder.option("python.IsolateNativeModules", "false");
            logger.info(
                    "Disabled native module isolation (Security Manager active - subprocess"
                            + " execution blocked)");
        }

        // The following two options help with debugging python execution & native extension
        // loading:
        // .option("log.python.capi.level", "FINE")
        // .option("log.python.level", "FINE")

        return builder.build();
    }

    public static Object executePython(
            ThreadPool threadPool,
            String code,
            Map<String, ?> params,
            Map<String, ?> doc,
            Map<String, ?> ctx,
            Double score) {
        SemanticAnalyzer analyzer = new SemanticAnalyzer(code + '\n');
        analyzer.checkSemantic();
        final ExecutorService executor = threadPool.executor(ThreadPool.Names.GENERIC);

        try (Context context = createContext()) {
            final Future<Value> futureResult =
                    executor.submit(() -> executeWorker(context, code, params, doc, ctx, score));

            try {
                Value result = futureResult.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

                // Extract the value before closing the context
                return extractValueBeforeContextClose(result);

            } catch (TimeoutException e) {
                // future.cancel is a forbidden API
                FutureUtils.cancel(futureResult);
                throw wrapWithScriptException(
                        e,
                        String.format(
                                Locale.ROOT,
                                "Script execution timed out after %d seconds",
                                TIMEOUT_IN_SECONDS),
                        code);
            } catch (ExecutionException | InterruptedException e) {
                throw wrapWithScriptException(e, code);
            }
        } catch (ScriptException e) {
            // Throw script exception as is
            throw e;
        } catch (Exception e) {
            throw wrapWithScriptException(e, code);
        }
    }

    private static ScriptException wrapWithScriptException(Exception e, String code) {
        return wrapWithScriptException(e, "Script execution failed with error", code);
    }

    private static ScriptException wrapWithScriptException(
            Exception e, String errorFmt, String code) {
        List<String> stacktrace;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        stacktrace = Arrays.stream(sw.toString().split("\\n")).map(String::trim).toList();
        return new ScriptException(
                String.format(Locale.ROOT, errorFmt, e.getMessage()),
                e,
                stacktrace,
                code,
                "python");
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
}
