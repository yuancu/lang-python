/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionRequest;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.SetOnce;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.python.action.PythonExecuteAction;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

/**
 *
 * Registers Python as a plugin.
 *
 * PythonModulePlugin is a plugin for OpenSearch that adds support for Python as a scripting language.
 * This class extends the {@link Plugin} class and implements the {@link ScriptPlugin} interface.
 *
 * <p>
 * This plugin allows users to write and execute scripts in Python within OpenSearch.
 * </p>
 *
 */
public class PythonModulePlugin extends Plugin implements ScriptPlugin, ActionPlugin {
    private static final Logger logger = LogManager.getLogger();
    private final SetOnce<PythonScriptEngine> pythonScriptEngine = new SetOnce<>();

    /**
     * Number of pre-warmed Python contexts in the pool. On Linux (IsolateNativeModules supported),
     * defaults to 64. On macOS, defaults to 1 since only one context can load native modules.
     * Requires a node restart to take effect.
     */
    public static final Setting<Integer> POOL_SIZE_SETTING =
            Setting.intSetting(
                    "script.python.context_pool_size",
                    ExecutionUtils.DEFAULT_POOL_SIZE,
                    1,
                    Setting.Property.NodeScope);

    public PythonModulePlugin() {}

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(POOL_SIZE_SETTING);
    }

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        pythonScriptEngine.set(new PythonScriptEngine(settings));
        return pythonScriptEngine.get();
    }

    @Override
    public Collection<Object> createComponents(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService,
            ScriptService scriptService,
            NamedXContentRegistry xContentRegistry,
            Environment environment,
            NodeEnvironment nodeEnvironment,
            NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<RepositoriesService> repositoriesServiceSupplier) {
        // Synchronously warm up the Python context pool and pre-load numpy in each context.
        // This blocks node startup while contexts are created and patchelf isolates native
        // libraries. After warmup, contexts are reused without further subprocess creation.
        try {
            int poolSize = POOL_SIZE_SETTING.get(environment.settings());
            logger.info("Starting Python engine warmup (pool size: {})...", poolSize);
            long startTime = System.currentTimeMillis();
            ExecutionUtils.warmup(poolSize);
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Python engine warmed up successfully in {}ms", duration);
        } catch (Exception | ExceptionInInitializerError e) {
            // ExceptionInInitializerError occurs when GraalPy runtime is unavailable
            // (e.g., running on a standard JDK without the polyglot module path).
            // The plugin still loads but Python execution will not work.
            logger.warn("Python engine warmup failed", e);
        }

        PythonScriptEngine engine = pythonScriptEngine.get();
        // Lazily assign its thread pool
        engine.setThreadPool(threadPool);
        // This is to bind python script engine in guice
        return Collections.singletonList(engine);
    }

    /**
     * Actions added by this plugin.
     */
    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> actions =
                new ArrayList<>();
        actions.add(
                new ActionHandler<>(
                        PythonExecuteAction.INSTANCE, PythonExecuteAction.TransportAction.class));
        return actions;
    }

    @Override
    public void close() throws IOException {
        try {
            ExecutionUtils.closeContextPool();
        } catch (NoClassDefFoundError e) {
            // ExecutionUtils failed to initialize (e.g., no GraalPy runtime) — nothing to clean up
        }
    }

    public List<RestHandler> getRestHandlers(
            Settings settings,
            RestController restController,
            ClusterSettings clusterSettings,
            IndexScopedSettings indexScopedSettings,
            SettingsFilter settingsFilter,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<DiscoveryNodes> nodesInCluster) {
        return List.of(new PythonExecuteAction.RestAction());
    }
}
