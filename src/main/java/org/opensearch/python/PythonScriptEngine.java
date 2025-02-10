/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
* Modifications Copyright OpenSearch Contributors. See
* GitHub history for details.
*/

package org.opensearch.python;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.script.*;

import java.io.IOException;
import java.security.AccessControlContext;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;


@SuppressWarnings("removal")
public class PythonScriptEngine implements ScriptEngine {

    /**
     * Standard name of the Python language.
     */
    public static final String NAME = "python";

    // Create a logger
    private static final Logger logger = LogManager.getLogger();

    private static Map<ScriptContext<?>, Function<String, ScriptFactory>> contexts;

    static {

        Map<ScriptContext<?>, Function<String, ScriptFactory>> contexts = new HashMap<>();
        contexts.put(FieldScript.CONTEXT, PythonFieldScript::newFieldScriptFactory);
        contexts.put(ScoreScript.CONTEXT, PythonScoreScript::newScoreScriptFactory);
        PythonScriptEngine.contexts = Collections.unmodifiableMap(contexts);
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    public <FactoryType> FactoryType compile(String name, String code, ScriptContext<FactoryType> context, Map<String, String> params) {
        logger.info("Got context {}", context.name);
        if (!contexts.containsKey(context)) {
            throw new IllegalArgumentException("Python engine does not know how to handle script context [" + context.name + "]");
        }
        ScriptFactory factory = contexts.get(context).apply(code);
        return context.factoryClazz.cast(factory);
    }

    @Override
    public Set<ScriptContext<?>> getSupportedContexts() {
        return contexts.keySet();
    }

    /**
     * Permissions context used during compilation.
     */
    private static final AccessControlContext COMPILATION_CONTEXT;

    /*
     * Set up the allowed permissions.
     */
    static {
        final Permissions none = new Permissions();
        none.setReadOnly();
        COMPILATION_CONTEXT = new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, none) });
    }
}
