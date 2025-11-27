/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.script.*;
import org.opensearch.threadpool.ThreadPool;

public class PythonScriptEngine implements ScriptEngine {
    public static final String NAME = "python";
    private static final Logger logger = LogManager.getLogger();
    // Supported contexts (score, field, template, etc.) and their factories
    private static Map<ScriptContext<?>, BiFunction<String, ThreadPool, ScriptFactory>> contexts;
    private final ThreadPool threadPool;

    public PythonScriptEngine(ThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    static {
        PythonScriptEngine.contexts =
                Map.of(
                        FieldScript.CONTEXT, PythonFieldScript::newFieldScriptFactory,
                        ScoreScript.CONTEXT, PythonScoreScript::newScoreScriptFactory,
                        TemplateScript.CONTEXT, PythonTemplateScript::newTemplateScriptFactory,
                        IngestScript.CONTEXT, PythonIngestScript::newIngestScriptFactory,
                        SearchScript.CONTEXT, PythonSearchScript::newSearchScriptFactory);
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    public <FactoryType> FactoryType compile(
            String name,
            String code,
            ScriptContext<FactoryType> context,
            Map<String, String> params) {
        logger.debug("Got context {}", context.name);
        if (!contexts.containsKey(context)) {
            throw new IllegalArgumentException(
                    "Python engine does not know how to handle script context ["
                            + context.name
                            + "]");
        }
        ScriptFactory factory = contexts.get(context).apply(code, threadPool);
        return context.factoryClazz.cast(factory);
    }

    @Override
    public Set<ScriptContext<?>> getSupportedContexts() {
        return contexts.keySet();
    }
}
