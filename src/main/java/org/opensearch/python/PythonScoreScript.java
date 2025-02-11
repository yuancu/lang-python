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
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.script.ScoreScript;
import org.opensearch.script.ScriptException;
import org.opensearch.search.lookup.SearchLookup;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.SandboxPolicy;

import java.io.IOException;
import java.util.HashMap;
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

                    // TODO: Test throw exception
                    if (!PythonScriptUtility.isCodeAnExpression(code)) {
                        throw new ScriptException("Python score script must be an expression, but got " + code,
                            null, null, null, PythonScriptEngine.NAME);
                    }

                    Set<String> accessedDocFields = PythonScriptUtility.extractAccessedDocFields(code);
                    Map<String, Object> docParams = new HashMap<>();
                    accessedDocFields.add("pages");
                    for (String field : accessedDocFields) {
                        docParams.put(field, getDoc().get(field));
                    }

                    double evaluatedScore = runPythonCode(code, params, docParams, get_score());
                    return evaluatedScore;
                }
            };
        }

        private double runPythonCode(String code, Map<String, ?> params, Map<String, ?> doc, double score){
            try (Context context = Context.newBuilder("python")
                .sandbox(SandboxPolicy.TRUSTED)
                .allowHostAccess(HostAccess.ALL).build()) {

                logger.info("Executing python code: {}", code);
                logger.info("Params: {}", params.toString());
                logger.info("Doc: {}", doc.toString());
                logger.info("Score: {}", score);

                context.getBindings("python").putMember("params", params);
                context.getBindings("python").putMember("doc", doc);
                context.getBindings("python").putMember("_score", score);
                Value evaluatedVal = context.eval("python", code);
                return evaluatedVal.asDouble();
            } catch (Exception e){
                logger.error("Failed to run python code", e);
                return 0;
            }
        }
    }
}
