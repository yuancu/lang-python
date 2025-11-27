/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.util.Map;
import org.opensearch.script.ScriptFactory;
import org.opensearch.script.SearchScript;
import org.opensearch.threadpool.ThreadPool;

/**
 * Executes Python scripts within search pipeline request processors to transform search requests.
 */
public class PythonSearchScript {
    public static SearchScriptFactory newSearchScriptFactory(String code, ThreadPool threadPool) {
        return new SearchScriptFactory(code, threadPool);
    }

    public record SearchScriptFactory(String code, ThreadPool threadPool)
            implements SearchScript.Factory, ScriptFactory {

        @Override
        public SearchScript newInstance(Map<String, Object> params) {
            return new SearchScript(params) {
                @Override
                public void execute(Map<String, Object> ctx) {
                    executePython(threadPool, code, getParams(), ctx);
                }
            };
        }

        @Override
        public boolean isResultDeterministic() {
            return true;
        }

        private static void executePython(
                ThreadPool threadPool,
                String code,
                Map<String, ?> params,
                Map<String, Object> ctx) {
            ExecutionUtils.executePython(threadPool, code, params, null, ctx, null);
        }
    }
}
