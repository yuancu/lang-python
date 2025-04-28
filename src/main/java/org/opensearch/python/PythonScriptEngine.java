/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.security.AccessControlContext;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.script.*;

public class PythonScriptEngine implements ScriptEngine {
    public static final String NAME = "python";
    private static final Logger logger = LogManager.getLogger();
    // Supported contexts (score, field, template, etc.) and their factories
    private static Map<ScriptContext<?>, Function<String, ScriptFactory>> contexts;
    // Permissions context used during compilation.
    private static final AccessControlContext COMPILATION_CONTEXT;

    static {
        PythonScriptEngine.contexts =
                Map.of(
                        FieldScript.CONTEXT, PythonFieldScript::newFieldScriptFactory,
                        ScoreScript.CONTEXT, PythonScoreScript::newScoreScriptFactory,
                        TemplateScript.CONTEXT, PythonTemplateScript::newTemplateScriptFactory);

        // Set up the allowed permissions.
        final Permissions none = new Permissions();
        none.setReadOnly();
        COMPILATION_CONTEXT =
                new AccessControlContext(new ProtectionDomain[] {new ProtectionDomain(null, none)});
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
        ScriptFactory factory = contexts.get(context).apply(code);
        return context.factoryClazz.cast(factory);
    }

    @Override
    public Set<ScriptContext<?>> getSupportedContexts() {
        return contexts.keySet();
    }
}
