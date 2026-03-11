/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.io.ProcessHandler;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;
import org.opensearch.common.util.concurrent.FutureUtils;
import org.opensearch.python.phase.SemanticAnalyzer;
import org.opensearch.script.ScriptException;
import org.opensearch.threadpool.ThreadPool;

/**
 * Manages a pool of pre-warmed GraalPy contexts for executing Python scripts.
 *
 * <p>Contexts are created once during initialization with {@code IsolateNativeModules=true}, giving
 * each context its own isolated copy of native libraries (numpy's .so files). A shared {@link
 * Engine} provides JIT code caching across all contexts. Between uses, each context's Python global
 * state is reset to prevent information leakage while preserving the module cache (sys.modules) for
 * fast reimport.
 *
 * <p>The custom {@link ProcessHandler} uses OpenSearch's {@code AccessController} to run patchelf
 * with elevated privileges, bypassing the seccomp-BPF system call filter that normally blocks
 * subprocess creation. Since contexts are reused, patchelf only runs during initialization.
 */
public class ExecutionUtils {
    @Getter @Setter private static int TIMEOUT_IN_SECONDS = 20;
    private static final Logger logger = LogManager.getLogger();
    private static final String MODULE_META_SIMPLE_NAME = "module";

    /**
     * Whether the current OS supports IsolateNativeModules (requires ELF binary patching).
     * macOS uses Mach-O format which GraalPy cannot yet modify for native module isolation.
     */
    private static final boolean ISOLATE_NATIVE_MODULES_SUPPORTED =
            !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /**
     * Default number of pre-warmed Python contexts to keep in the pool. On Linux,
     * IsolateNativeModules=true allows multiple contexts to load native libraries independently.
     * On macOS, only the first context can load native modules, so we use a pool of 1.
     * Configurable via the {@code script.python.context_pool_size} node setting.
     */
    static final int DEFAULT_POOL_SIZE = ISOLATE_NATIVE_MODULES_SUPPORTED ? 2 : 1;

    /** Actual pool size, set during {@link #warmup(int)}. */
    private static int poolSize;

    /** Python global names that belong to the default module scope and must not be cleared. */
    private static final Set<String> SYSTEM_GLOBALS =
            Set.of("__builtins__", "__name__", "__doc__", "__package__", "__loader__", "__spec__");

    // Reference:
    // https://github.com/graalvm/graal-languages-demos/blob/main/graalpy/graalpy-javase-guide/README.md
    static VirtualFileSystem vfs =
            VirtualFileSystem.newBuilder()
                    .allowHostIO(VirtualFileSystem.HostIO.READ)
                    // The value is set in build.gradle
                    .resourceDirectory("GRAALPY-VFS/org.opensearch/lang-python")
                    .build();
    static Path resourcesDir;

    /**
     * Shared GraalVM engine for JIT code caching across all pooled contexts. Compiled Truffle ASTs
     * are shared, reducing warmup time for subsequent contexts.
     */
    private static final Engine sharedEngine;

    /** Pool of pre-warmed, reusable Python contexts with numpy already loaded. */
    private static LinkedBlockingQueue<Context> contextPool;

    /**
     * Dedicated thread pool for Python script execution. Using a dedicated pool instead of the
     * GENERIC thread pool avoids thread pool starvation: calling threads (often GENERIC pool
     * threads) block on Future.get(), while Python work runs on these separate threads.
     */
    private static ExecutorService pythonExecutor;

    /**
     * Custom ProcessHandler that creates subprocesses with elevated privileges. The default Truffle
     * ProcessHandler's subprocess calls are blocked by OpenSearch's seccomp-BPF system call filter.
     * This handler uses OpenSearch's AccessController to execute process creation with the plugin's
     * security permissions.
     *
     * <p>Subprocesses are only created during context warmup when GraalPy runs patchelf to isolate
     * native libraries. After warmup, no further subprocess creation occurs.
     */
    private static final ProcessHandler PROCESS_HANDLER =
            command -> {
                try {
                    return org.opensearch.secure_sm.AccessController.doPrivilegedChecked(
                            () -> {
                                List<String> cmd = command.getCommand();
                                ProcessBuilder pb = new ProcessBuilder(cmd);

                                Map<String, String> env = command.getEnvironment();
                                if (env != null) {
                                    pb.environment().putAll(env);
                                }

                                pb.redirectErrorStream(command.isRedirectErrorStream());
                                return pb.start();
                            });
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Failed to start process", e);
                }
            };

    static {
        sharedEngine =
                Engine.newBuilder()
                        .allowExperimentalOptions(true)
                        .option("engine.ShowInternalStackFrames", "true")
                        .option("engine.PrintInternalStackTrace", "true")
                        .build();
        // Extract VFS resources (Python packages, native extensions) to the cluster's temp
        // directory. OpenSearch sets java.io.tmpdir to a cluster-specific location that's writable
        // within the security manager constraints. Native libraries must be extracted to a real
        // filesystem path to be loaded by Python's C extension loader.
        // Reference: https://www.graalvm.org/python/docs/#virtual-filesystem
        resourcesDir = Path.of(System.getProperty("java.io.tmpdir"), "graalpy-resources");
        logger.info("Extracting GraalPy resources to: {}", resourcesDir.toAbsolutePath());
        try {
            GraalPyResources.extractVirtualFileSystemResources(vfs, resourcesDir);
            // The VFS extraction strips execute permissions from binaries.
            // patchelf needs execute permission because GraalPy runs it as a subprocess
            // to modify SONAME in duplicated native libraries when IsolateNativeModules=true.
            setExecutablePermissions(resourcesDir.resolve("venv/bin"));
        } catch (Exception e) {
            logger.error("CAN'T EXTRACT RESOURCES TO TARGET", e);
        }
    }

    /**
     * Initializes the context pool by creating and pre-warming contexts. Each context imports numpy
     * during warmup, which triggers GraalPy's native module isolation (patchelf via the privileged
     * {@link #PROCESS_HANDLER}) once per context. After warmup, contexts are reused for requests
     * without further subprocess creation.
     */
    public static void warmup(int configuredPoolSize) {
        if (!ISOLATE_NATIVE_MODULES_SUPPORTED && configuredPoolSize > 1) {
            logger.warn(
                    "IsolateNativeModules not supported on this OS; capping pool size from {} to 1",
                    configuredPoolSize);
            configuredPoolSize = 1;
        }
        poolSize = configuredPoolSize;
        contextPool = new LinkedBlockingQueue<>(poolSize);

        AtomicInteger threadCount = new AtomicInteger();
        pythonExecutor =
                Executors.newFixedThreadPool(
                        poolSize,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r, "python-executor-" + threadCount.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        });

        logger.info("Initializing context pool with {} contexts", poolSize);
        for (int i = 0; i < poolSize; i++) {
            try {
                long start = System.currentTimeMillis();
                Context context = createContext();
                // GraalPy's set_default_verify_paths() reads SSL_CERT_FILE and tries
                // to load the PEM file via TruffleFile. If this fails (e.g. VFS can't
                // read the host path), it silently swallows the error and leaves the
                // SSL context with no CA certs — instead of falling through to Java's
                // default truststore (which has valid CAs). Unsetting SSL_CERT_FILE
                // forces GraalPy to use Java's truststore directly.
                context.eval(
                        "python",
                        "import os; os.environ.pop('SSL_CERT_FILE', None);"
                                + " os.environ.pop('SSL_CERT_DIR', None)");
                context.eval("python", "import numpy");
                resetContextState(context);
                contextPool.offer(context);
                long elapsed = System.currentTimeMillis() - start;
                logger.info("Context {}/{} warmed up in {}ms", i + 1, poolSize, elapsed);
            } catch (Exception e) {
                logger.error("Failed to create context {}/{}", i + 1, poolSize, e);
            }
        }
        logger.info("Context pool ready: {}/{} contexts available", contextPool.size(), poolSize);
    }

    /**
     * Closes all pooled contexts, the shared engine, and the Python executor. Called during plugin
     * shutdown.
     */
    public static void closeContextPool() {
        logger.info("Shutting down context pool");
        pythonExecutor.shutdownNow();
        Context ctx;
        while ((ctx = contextPool.poll()) != null) {
            try {
                ctx.close();
            } catch (Exception e) {
                logger.warn("Error closing pooled context", e);
            }
        }
        try {
            sharedEngine.close();
        } catch (Exception e) {
            logger.warn("Error closing shared engine", e);
        }
    }

    /**
     * Sets executable permissions on all files in the given directory. This is needed because
     * {@link GraalPyResources#extractVirtualFileSystemResources} does not preserve executable
     * permissions from the VFS resources.
     */
    private static void setExecutablePermissions(Path binDir) {
        if (!Files.isDirectory(binDir)) {
            return;
        }
        try (var entries = Files.list(binDir)) {
            entries.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try {
                                    Files.setPosixFilePermissions(
                                            file, EnumSet.allOf(PosixFilePermission.class));
                                } catch (IOException e) {
                                    logger.warn(
                                            "Failed to set execute permission on {}: {}",
                                            file,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            logger.warn("Failed to list files in {}: {}", binDir, e.getMessage());
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
        return GraalPyResources.contextBuilder(vfs)
                .engine(sharedEngine)
                .sandbox(SandboxPolicy.TRUSTED)
                .allowHostAccess(HostAccess.ALL)
                // The following options are necessary for importing 3-rd party
                // libraries that load native libraries (like numpy)
                .allowExperimentalOptions(true)
                .allowIO(IOAccess.ALL)
                .allowCreateThread(true)
                .allowNativeAccess(true)
                .allowCreateProcess(true)
                .processHandler(PROCESS_HANDLER)
                // Allow access to environment variables so subprocesses can locate
                // binaries like patchelf when isolating native modules
                .allowEnvironmentAccess(EnvironmentAccess.INHERIT)
                // Reference for Python context options:
                // https://www.graalvm.org/python/docs/#python-context-options
                .option(
                        "python.Executable",
                        String.format(
                                Locale.ROOT, "%s/venv/bin/graalpy", resourcesDir.toAbsolutePath()))
                // On Linux, each context gets its own isolated copies of native .so files.
                // GraalPy uses patchelf (via PROCESS_HANDLER) to give each copy a
                // unique SONAME so the OS dynamic linker loads them independently.
                // On macOS, Mach-O modification is not yet supported, so we disable isolation.
                .option(
                        "python.IsolateNativeModules",
                        String.valueOf(ISOLATE_NATIVE_MODULES_SUPPORTED))
                // Use native POSIX backend so Python's socket module works,
                // enabling urllib.request for HTTP calls from scripts.
                .option("python.PosixModuleBackend", "native")
                // Enable verbose warnings for debugging native extensions
                .option("python.WarnExperimentalFeatures", "true")
                .build();
    }

    /**
     * Resets a pooled context to a clean state between uses. Removes all user-defined global
     * variables while preserving Python system globals. The module cache (sys.modules) is NOT
     * cleared, so re-importing numpy in user scripts is instant.
     */
    private static void resetContextState(Context context) {
        try {
            Value bindings = context.getBindings("python");
            for (String key : new ArrayList<>(bindings.getMemberKeys())) {
                if (!SYSTEM_GLOBALS.contains(key)) {
                    try {
                        bindings.removeMember(key);
                    } catch (Exception e) {
                        logger.trace("Could not remove binding '{}': {}", key, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Context state reset failed: {}", e.getMessage());
        }
    }

    /** Returns a context to the pool after resetting its state. */
    private static void returnContext(Context context) {
        resetContextState(context);
        contextPool.offer(context);
    }

    /**
     * Recovers a context after a timeout or error and returns it to the pool.
     *
     * <p>When IsolateNativeModules is supported (Linux), the corrupted context is force-closed and
     * replaced with a fresh one. When not supported (macOS), the context cannot be destroyed and
     * recreated (native modules can only be loaded by the first context), so we interrupt it and
     * return the same context to the pool.
     */
    private static void replaceContext(Context context) {
        if (ISOLATE_NATIVE_MODULES_SUPPORTED) {
            try {
                context.close(true); // force close to interrupt any running code
            } catch (Exception e) {
                logger.warn("Error force-closing corrupted context: {}", e.getMessage());
            }
            try {
                Context replacement = createContext();
                replacement.eval(
                        "python",
                        "import os; os.environ.pop('SSL_CERT_FILE', None);"
                                + " os.environ.pop('SSL_CERT_DIR', None)");
                replacement.eval("python", "import numpy");
                resetContextState(replacement);
                contextPool.offer(replacement);
                logger.info("Replaced corrupted context. Pool size: {}", contextPool.size());
            } catch (Exception e) {
                logger.error(
                        "Failed to create replacement context. Pool capacity reduced to {}",
                        contextPool.size(),
                        e);
            }
        } else {
            // macOS: interrupt and reuse the same context
            try {
                context.interrupt(java.time.Duration.ofSeconds(10));
            } catch (Exception e) {
                logger.warn("Error interrupting context: {}", e.getMessage());
            }
            resetContextState(context);
            contextPool.offer(context);
        }
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

        // Borrow a pre-warmed context from the pool. Blocks until one is available or timeout.
        Context context;
        try {
            context = contextPool.poll(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw wrapWithScriptException(
                    e, "Interrupted while waiting for available Python context", code);
        }
        if (context == null) {
            throw wrapWithScriptException(
                    new TimeoutException("No Python context available"),
                    String.format(
                            Locale.ROOT,
                            "All %d Python contexts are busy, timed out after %d seconds",
                            poolSize,
                            TIMEOUT_IN_SECONDS),
                    code);
        }

        // Submit to the dedicated Python executor to avoid GENERIC thread pool starvation.
        boolean contextReturned = false;
        try {
            final Context pooledContext = context;
            final Future<Value> futureResult =
                    pythonExecutor.submit(
                            () -> executeWorker(pooledContext, code, params, doc, ctx, score));

            try {
                Value result = futureResult.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
                Object extracted = extractValueBeforeContextClose(result);
                returnContext(context);
                contextReturned = true;
                return extracted;

            } catch (TimeoutException e) {
                // Timeout: context may be stuck executing, must replace it
                FutureUtils.cancel(futureResult);
                throw wrapWithScriptException(
                        e,
                        String.format(
                                Locale.ROOT,
                                "Script execution timed out after %d seconds",
                                TIMEOUT_IN_SECONDS),
                        code);
            } catch (ExecutionException e) {
                // Script error (e.g., NameError, TypeError): context is still usable
                try {
                    returnContext(context);
                    contextReturned = true;
                } catch (Exception resetEx) {
                    logger.warn("Context return failed after script error", resetEx);
                }
                throw wrapWithScriptException(e, code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw wrapWithScriptException(e, code);
            }
        } catch (ScriptException e) {
            throw e;
        } catch (Exception e) {
            throw wrapWithScriptException(e, code);
        } finally {
            if (!contextReturned) {
                // Context may be corrupted (timeout, interrupt, unexpected error) — replace it
                replaceContext(context);
            }
        }
    }

    private static ScriptException wrapWithScriptException(Exception e, String code) {
        return wrapWithScriptException(e, "Script execution failed with error", code);
    }

    private static ScriptException wrapWithScriptException(
            Exception e, String errorFmt, String code) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        List<String> stacktrace =
                Arrays.stream(sw.toString().split("\\n")).map(String::trim).toList();
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
