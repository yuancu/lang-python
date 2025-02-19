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
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;

import java.util.Map;

public class ExecutionUtils {
    private static final Logger logger = LogManager.getLogger();

    public static Value executePython(String code, Map<String, ?> params, Map<String, ?> doc, Double score){
        try (Context context = Context.newBuilder("python")
            .sandbox(SandboxPolicy.TRUSTED)
            .allowHostClassLookup(s -> true)
            .allowHostAccess(HostAccess.ALL).build()) {

            if (params != null) {
                logger.debug("Params: {}", params.toString());
                context.getBindings("python").putMember("params", params);
            }
            if (doc != null) {
                logger.debug("Doc: {}", doc.toString());
                context.getBindings("python").putMember("doc", doc);
            }
            if (score != null) {
                logger.debug("Score: {}", score);
                context.getBindings("python").putMember("_score", score);
            }
            return context.eval("python", code);
        } catch (Exception e){
            logger.error("Failed to run python code", e);
            return null;
        }
    }
}
