/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.util.Map;
import org.opensearch.script.IngestScript;
import org.opensearch.script.ScriptFactory;
import org.opensearch.threadpool.ThreadPool;

/**
 * Executes Python scripts within ingest pipeline processors to transform documents during ingestion.
 */
public class PythonIngestScript {
    public static IngestScriptFactory newIngestScriptFactory(String code, ThreadPool threadPool) {
        return new IngestScriptFactory(code, threadPool);
    }

    public record IngestScriptFactory(String code, ThreadPool threadPool)
            implements IngestScript.Factory, ScriptFactory {

        @Override
        public IngestScript newInstance(Map<String, Object> params) {
            return new IngestScript(params) {
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
