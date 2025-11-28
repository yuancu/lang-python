/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

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
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.unit.TimeValue;
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
    private static final int WARMUP_DELAY_SECONDS = 5;
    private final SetOnce<PythonScriptEngine> pythonScriptEngine = new SetOnce<>();

    public PythonModulePlugin() {}

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
        // Asynchronously warm up Python engine to reduce cold start latency
        threadPool.schedule(
                () -> {
                    try {
                        logger.info("Starting Python engine warmup...");
                        long startTime = System.currentTimeMillis();
                        ExecutionUtils.executePython(threadPool, "1+1", null, null, null, null);
                        long duration = System.currentTimeMillis() - startTime;
                        logger.info("Python engine warmed up successfully in {}ms", duration);
                    } catch (Exception e) {
                        logger.warn("Python engine warmup failed", e);
                    }
                },
                TimeValue.timeValueSeconds(WARMUP_DELAY_SECONDS),
                ThreadPool.Names.GENERIC);

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
