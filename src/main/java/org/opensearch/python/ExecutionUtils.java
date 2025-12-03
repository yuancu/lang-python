/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    static Path patchelfBinDir;

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
            // Download and setup patchelf for native module isolation
            patchelfBinDir = setupPatchelf(resourcesDir);
        } catch (Exception e) {
            logger.error("CAN'T EXTRACT RESOURCES TO TARGET", e);
        }
    }

    /**
     * Downloads and sets up patchelf binary for the current OS/architecture.
     * GraalPy needs patchelf when IsolateNativeModules=true to modify SONAME in duplicated native
     * libraries.
     *
     * @param baseDir The base directory where patchelf should be extracted
     * @return The directory containing the patchelf binary
     * @throws IOException If download or setup fails
     */
    private static Path setupPatchelf(Path baseDir) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        // Only Linux is supported for now (patchelf is a Linux tool)
        if (!os.contains("linux")) {
            logger.info("Skipping patchelf setup - not on Linux (OS: {})", os);
            return null;
        }

        // Normalize architecture names
        String normalizedArch;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            normalizedArch = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            normalizedArch = "aarch64";
        } else {
            logger.warn("Unsupported architecture for patchelf: {}", arch);
            return null;
        }

        // Create bin directory for patchelf
        Path binDir = baseDir.resolve("bin");
        Files.createDirectories(binDir);
        Path patchelfPath = binDir.resolve("patchelf");

        // Skip if patchelf already exists and is executable
        if (Files.exists(patchelfPath) && Files.isExecutable(patchelfPath)) {
            logger.info("Patchelf already exists at: {}", patchelfPath.toAbsolutePath());
            return binDir;
        }

        // Download patchelf from NixOS binary cache
        // These are statically-linked binaries that work on any Linux distribution
        String patchelfVersion = "0.15.0";
        String downloadUrl =
                String.format(
                        Locale.ROOT,
                        "https://github.com/NixOS/patchelf/releases/download/%s/patchelf-%s-%s",
                        patchelfVersion,
                        normalizedArch,
                        "linux");

        logger.info("Downloading patchelf from: {}", downloadUrl);

        try {
            URL url = new URL(downloadUrl);
            try (InputStream in = new BufferedInputStream(url.openStream())) {
                Files.copy(in, patchelfPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Set executable permissions (chmod +x)
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(patchelfPath, perms);

            logger.info(
                    "Successfully downloaded and set up patchelf at: {}",
                    patchelfPath.toAbsolutePath());
            return binDir;
        } catch (Exception e) {
            logger.error("Failed to download patchelf: {}", e.getMessage(), e);
            // Clean up partial download
            if (Files.exists(patchelfPath)) {
                Files.delete(patchelfPath);
            }
            throw new IOException("Failed to setup patchelf", e);
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
        // The following two options help with debugging python execution & native extension
        // loading:
        // .option("log.python.capi.level", "FINE")
        // .option("log.python.level", "FINE")

        // Configure native module isolation if patchelf is available
        if (patchelfBinDir != null) {
            logger.info(
                    "Enabling IsolateNativeModules with patchelf at: {}",
                    patchelfBinDir.toAbsolutePath());
            // Allow subprocesses to inherit environment variables (including PATH)
            // This enables GraalPy to find and execute patchelf
            builder.allowEnvironmentAccess(EnvironmentAccess.INHERIT);
            // Enable native module isolation - creates isolated copies for each context
            builder.option("python.IsolateNativeModules", "true");

            // Add patchelf bin directory to PATH environment variable
            String currentPath = System.getenv("PATH");
            String newPath =
                    patchelfBinDir.toAbsolutePath().toString()
                            + (currentPath != null ? ":" + currentPath : "");
            builder.environment("PATH", newPath);
        } else {
            logger.info(
                    "Using shared native modules (IsolateNativeModules=false) - patchelf not"
                            + " available");
            // Set to false to use shared native modules across contexts
            builder.option("python.IsolateNativeModules", "false");
        }

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
