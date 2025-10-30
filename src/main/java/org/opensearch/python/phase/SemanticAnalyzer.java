/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.python.phase;

import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
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

        // Remove default error listeners and add strict error handling
        parser.removeErrorListeners();
        lexer.removeErrorListeners();

        // Add custom error listener that throws exceptions immediately
        BaseErrorListener errorListener =
                new BaseErrorListener() {
                    @Override
                    public void syntaxError(
                            Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line,
                            int charPositionInLine,
                            String msg,
                            RecognitionException e) {
                        throw new IllegalArgumentException(
                                "Syntax error at line "
                                        + line
                                        + ":"
                                        + charPositionInLine
                                        + " - "
                                        + msg);
                    }
                };

        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);
    }

    public void checkSemantic() {
        try {
            // Parse the input as a statement (stmt is the rule for statements in Python)
            ParseTree tree = parser.file_input();

            // Walk the parse tree to perform semantic checks
            Python3SemanticCheckParser semanticCheckParser = new Python3SemanticCheckParser();
            new ParseTreeWalker().walk(semanticCheckParser, tree);
        } catch (IllegalArgumentException e) {
            // Re-throw semantic analysis errors as-is
            throw new ScriptException(e.getMessage(), e, List.of(), code, "python");
        } catch (Exception e) {
            throw new ScriptException(
                    "Compile error: " + e.getMessage(), e, List.of(), code, "python");
        }
    }

    public static class Python3SemanticCheckParser extends Python3ParserBaseListener {
        @Override
        public void enterWhile_stmt(Python3Parser.While_stmtContext ctx) {
            // Check where while has an escape
            if (ctx.test().getText().equals("True")) {
                if (ctx.children.stream()
                        .noneMatch(Python3SemanticCheckParser::containsEscapeRecursive)) {
                    throw new IllegalArgumentException(
                            "Infinite loop detected: while True loop has no exit condition");
                }
            } else if (ctx.test().getText().equals("False")) {
                throw new IllegalArgumentException(
                        "Unreachable code: while False loop will never execute");
            }
        }

        private static boolean containsEscapeRecursive(ParseTree node) {
            return containsEscapeRecursive(node, 0);
        }

        private static boolean containsEscapeRecursive(ParseTree node, int nestedLoopDepth) {
            // Direct escape statements
            if (node instanceof Python3Parser.Return_stmtContext) {
                return true; // return always escapes all loops
            }
            if (node instanceof Python3Parser.Raise_stmtContext) {
                return true; // raise always escapes all loops
            }
            if (node instanceof Python3Parser.Break_stmtContext) {
                return nestedLoopDepth == 0; // break only escapes if we're at the target loop level
            }

            // Track nested loops
            int newDepth = nestedLoopDepth;
            if (node instanceof Python3Parser.While_stmtContext
                    || node instanceof Python3Parser.For_stmtContext) {
                newDepth++; // Entering a nested loop
            }

            // Recurse with updated depth
            for (int i = 0; i < node.getChildCount(); i++) {
                if (containsEscapeRecursive(node.getChild(i), newDepth)) {
                    return true;
                }
            }
            return false;
        }
    }
}
