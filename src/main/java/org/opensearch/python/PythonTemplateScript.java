/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python;

import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.script.ScriptFactory;
import org.opensearch.script.TemplateScript;
import org.opensearch.threadpool.ThreadPool;

public class PythonTemplateScript {
    private static final Logger logger = LogManager.getLogger();

    public static TemplateScriptFactory newTemplateScriptFactory(
            String code, ThreadPool threadPool) {
        return new TemplateScriptFactory(code, threadPool);
    }

    public static class TemplateScriptFactory implements TemplateScript.Factory, ScriptFactory {
        private final String code;
        private final ThreadPool threadPool;

        TemplateScriptFactory(String code, ThreadPool threadPool) {
            this.code = code;
            this.threadPool = threadPool;
        }

        @Override
        public TemplateScript newInstance(Map<String, Object> params) {
            return new TemplateScript(params) {
                @Override
                public String execute() {
                    logger.debug("Executing template script with code: {}", code);
                    return executePython(threadPool, code, params);
                }
            };
        }

        @Override
        public boolean isResultDeterministic() {
            return true;
        }

        private static String executePython(
                ThreadPool threadPool, String code, Map<String, ?> params) {
            Object result =
                    ExecutionUtils.executePython(
                            threadPool, code, params, null, null, null);
            if (result == null) {
                logger.warn("Did not get any result from Python execution");
                return "";
            }
            return result.toString();
        }
    }
}
