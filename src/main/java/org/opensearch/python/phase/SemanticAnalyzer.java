/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python.phase;

import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.opensearch.python.antlr.Python3Lexer;
import org.opensearch.python.antlr.Python3Parser;
import org.opensearch.python.antlr.Python3ParserBaseListener;
import org.opensearch.script.ScriptException;

public class SemanticAnalyzer {
    private final String code;
    private Python3Lexer lexer;
    private CommonTokenStream tokens;
    private Python3Parser parser;

    public SemanticAnalyzer(String code) {
        this.code = code;
        lexer = new Python3Lexer(CharStreams.fromString(code));
        tokens = new CommonTokenStream(lexer);
        parser = new Python3Parser(tokens);
    }

    public void checkSemantic() {
        try {
            // Parse the input as a statement (stmt is the rule for statements in Python)
            ParseTree tree = parser.file_input();

            // Walk the parse tree to perform semantic checks
            Python3SemanticCheckParser semanticCheckParser = new Python3SemanticCheckParser();
            new org.antlr.v4.runtime.tree.ParseTreeWalker().walk(semanticCheckParser, tree);
        } catch (Exception e) {
            throw new ScriptException("compile error", e, List.of(), code, "python");
        }
    }

    public static class Python3SemanticCheckParser extends Python3ParserBaseListener {
        @Override
        public void enterWhile_stmt(Python3Parser.While_stmtContext ctx) {
            // Check where while has an escape
            if (ctx.test().getText().equals("True")) {
                if (ctx.children.stream()
                        .noneMatch(Python3SemanticCheckParser::containsEscapeRecursive)) {
                    throw new IllegalArgumentException("no paths escape from while loop");
                }
            } else if (ctx.test().getText().equals("False")) {
                throw new IllegalArgumentException("extraneous while loop");
            }
        }

        private static boolean containsEscapeRecursive(ParseTree node) {
            if (node instanceof Python3Parser.Break_stmtContext) {
                return true;
            }
            if (node instanceof Python3Parser.Return_stmtContext) {
                return true;
            }
            if (node instanceof Python3Parser.Raise_stmtContext) {
                return true;
            }
            // Recurse
            for (int i = 0; i < node.getChildCount(); i++) {
                if (containsEscapeRecursive(node.getChild(i))) {
                    return true;
                }
            }
            return false;
        }
    }
}
