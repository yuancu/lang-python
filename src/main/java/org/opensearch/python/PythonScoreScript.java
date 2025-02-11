/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.python;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.opensearch.script.ScoreScript;
import org.opensearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.Map;
import java.util.Set;


// Reference memo (For developers only)
// - https://www.elastic.co/guide/en/elasticsearch/painless/current/painless-score-context.html

public class PythonScoreScript {
    private static final Logger logger = LogManager.getLogger();

    public static ScoreScript.Factory newScoreScriptFactory(String code){
        return new ScoreScript.Factory(){

            @Override
            public boolean isResultDeterministic() {
                return true;
            }

            @Override
            public ScoreScript.LeafFactory newFactory(Map<String, Object> params, SearchLookup lookup, IndexSearcher indexSearcher) {
                return newScoreScript(code, params, lookup, indexSearcher);
            }
        };
    }

    private static ScoreScript.LeafFactory newScoreScript(String code, Map<String, Object> params, SearchLookup lookup, IndexSearcher indexSearcher) {
        return  new PythonScoreScriptLeafFactory(code, params, lookup, indexSearcher);
    }

    private static class PythonScoreScriptLeafFactory implements ScoreScript.LeafFactory {
        private final String code;
        private final Map<String, Object> params;
        private final SearchLookup lookup;
        private final IndexSearcher indexSearcher;

        private PythonScoreScriptLeafFactory(String code, Map<String, Object> params, SearchLookup lookup, IndexSearcher indexSearcher) {
            this.code = code;
            this.params = params;
            this.lookup = lookup;
            this.indexSearcher = indexSearcher;
        }

        @Override
        public boolean needs_score() {
            return true;
        }

        @Override
        public ScoreScript newInstance(LeafReaderContext ctx) throws IOException {
            return new ScoreScript(params, lookup, indexSearcher, ctx) {
                @Override
                public double execute(ExplanationHolder explanation) {
                    if(explanation != null) {
                        explanation.set("Use user-provided Python expression to calculate the score of the document");
                    }
                    logger.info("_score: {}", get_score());
                    System.out.println("Pages: " + getDoc().get("pages"));
                    return 0;
                }
            };
        }
    }
}
