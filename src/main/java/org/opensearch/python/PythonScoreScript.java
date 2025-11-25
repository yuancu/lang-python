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
import org.apache.lucene.search.IndexSearcher;
import org.opensearch.script.ScoreScript;
import org.opensearch.search.lookup.SearchLookup;
import org.opensearch.threadpool.ThreadPool;

public class PythonScoreScript {
    private static final Logger logger = LogManager.getLogger();

    public static ScoreScript.Factory newScoreScriptFactory(String code, ThreadPool threadPool) {
        return new ScoreScript.Factory() {

            @Override
            public boolean isResultDeterministic() {
                return true;
            }

            @Override
            public ScoreScript.LeafFactory newFactory(
                    Map<String, Object> params, SearchLookup lookup, IndexSearcher indexSearcher) {
                return newScoreScript(code, params, lookup, indexSearcher, threadPool);
            }
        };
    }

    private static ScoreScript.LeafFactory newScoreScript(
            String code,
            Map<String, Object> params,
            SearchLookup lookup,
            IndexSearcher indexSearcher,
            ThreadPool threadPool) {
        return new PythonScoreScriptLeafFactory(code, params, lookup, indexSearcher, threadPool);
    }

    private record PythonScoreScriptLeafFactory(
            String code,
            Map<String, Object> params,
            SearchLookup lookup,
            IndexSearcher indexSearcher,
            ThreadPool threadPool)
            implements ScoreScript.LeafFactory {

        @Override
        public boolean needs_score() {
            return true;
        }

        @Override
        public ScoreScript newInstance(LeafReaderContext ctx) throws IOException {
            return new ScoreScript(params, lookup, indexSearcher, ctx) {
                @Override
                public double execute(ExplanationHolder explanation) {
                    if (explanation != null) {
                        explanation.set(
                                "Use user-provided Python expression to calculate the score of the"
                                        + " document");
                    }
                    return executePython(threadPool, code, getParams(), getDoc(), get_score());
                }
            };
        }

        private static double executePython(
                ThreadPool threadPool,
                String code,
                Map<String, ?> params,
                Map<String, ?> doc,
                double score) {
            Object evaluatedVal =
                    ExecutionUtils.executePython(threadPool, code, params, doc, null, score);
            if (evaluatedVal == null) {
                return 0;
            }
            return ((Number) evaluatedVal).doubleValue();
        }
    }
}
