/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.io.IOException;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.opensearch.script.FieldScript;
import org.opensearch.search.lookup.SearchLookup;
import org.opensearch.threadpool.ThreadPool;

public class PythonFieldScript {
    private static final Logger logger = LogManager.getLogger();

    public static FieldScriptFactory newFieldScriptFactory(String code, ThreadPool threadPool) {
        return new FieldScriptFactory(code, threadPool);
    }

    public static class FieldScriptFactory implements FieldScript.Factory {
        private final String code;
        private final ThreadPool threadPool;

        FieldScriptFactory(String code, ThreadPool threadPool) {
            this.code = code;
            this.threadPool = threadPool;
        }

        @Override
        public boolean isResultDeterministic() {
            return true;
        }

        @Override
        public FieldScript.LeafFactory newFactory(Map<String, Object> params, SearchLookup lookup) {
            return new FieldScriptLeafFactory(code, params, lookup, threadPool);
        }
    }

    private record FieldScriptLeafFactory(
            String code, Map<String, Object> params, SearchLookup lookup, ThreadPool threadPool)
            implements FieldScript.LeafFactory {

        @Override
        public FieldScript newInstance(LeafReaderContext ctx) throws IOException {
            return new FieldScript(params, lookup, ctx) {
                @Override
                public Object execute() {
                    logger.debug(
                            "Executing python field script code: {}\nParams: {}", code, params);
                    return executePython(threadPool, code, getParams(), getDoc());
                }
            };
        }

        private static Object executePython(
                ThreadPool threadPool, String code, Map<String, ?> params, Map<String, ?> doc) {
            Object result = ExecutionUtils.executePython(threadPool, code, params, doc, null, null);
            if (result == null) {
                logger.debug("Did not get any result from Python field script execution");
                return null;
            }
            return result;
        }
    }
}
