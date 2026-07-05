package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMap;

import javax.swing.text.Segment;

/**
 * Hand-rolled RSyntaxTextArea lexer for MVEL expressions — the generic JS lexer colors
 * {@code player.getRed() + g.x} as one flat identifier soup. This one distinguishes:
 * <ul>
 *   <li>{@link Token#IDENTIFIER} — bare variables ({@code player}, {@code g});</li>
 *   <li>{@link Token#VARIABLE} — property access after a dot ({@code .x});</li>
 *   <li>{@link Token#FUNCTION} — calls, i.e. any identifier followed by {@code (}
 *       ({@code getRed}, {@code sin});</li>
 *   <li>{@link Token#RESERVED_WORD} — MVEL keywords ({@code if}, {@code foreach},
 *       {@code contains}, {@code isdef}, ...);</li>
 *   <li>{@link Token#RESERVED_WORD_2} — {@code null}/{@code nil}/{@code empty}/{@code undefined};</li>
 *   <li>numbers (int / float / hex), booleans, strings ({@code "…"} and {@code '…'}),
 *       operators, separators, and {@code //} / {@code /* *}{@code /} comments
 *       (multiline state carried across lines).</li>
 * </ul>
 * Instantiated reflectively by {@link TokenMakerFactory} — must stay public with a
 * public no-arg constructor.
 */
public final class MvelTokenMaker extends AbstractTokenMaker {

    /** Our registered syntax style key — pass to {@code setSyntaxEditingStyle}. */
    public static final String SYNTAX_STYLE = "text/polytone-mvel";

    private static volatile boolean registered;

    /** Idempotently register this lexer with the default TokenMaker factory. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ((AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance())
                .putMapping(SYNTAX_STYLE, MvelTokenMaker.class.getName(),
                        MvelTokenMaker.class.getClassLoader());
    }

    private static final String SEPARATORS = "()[]{},;";

    @Override
    public TokenMap getWordsToHighlight() {
        TokenMap map = new TokenMap();
        for (String kw : new String[]{
                "if", "else", "foreach", "for", "while", "do", "return", "new", "var",
                "def", "function", "with", "assert", "import", "in", "contains",
                "instanceof", "strsim", "soundslike", "isdef", "this"}) {
            map.put(kw, Token.RESERVED_WORD);
        }
        map.put("true", Token.LITERAL_BOOLEAN);
        map.put("false", Token.LITERAL_BOOLEAN);
        for (String kw : new String[]{"null", "nil", "empty", "undefined"}) {
            map.put(kw, Token.RESERVED_WORD_2);
        }
        return map;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        char[] array = text.array;
        int offset = text.offset;
        int end = offset + text.count;
        int newStartOffset = startOffset - offset;

        int i = offset;

        // Continue an unterminated /* ... */ from the previous line.
        if (initialTokenType == Token.COMMENT_MULTILINE && i < end) {
            int close = indexOfCommentClose(array, i, end);
            if (close < 0) {
                addToken(text, i, end - 1, Token.COMMENT_MULTILINE, newStartOffset + i);
                return firstToken; // still inside — no null token = continuation
            }
            addToken(text, i, close + 1, Token.COMMENT_MULTILINE, newStartOffset + i);
            i = close + 2;
        }

        char prevSignificant = 0; // last non-whitespace char before the current token

        while (i < end) {
            char c = array[i];
            int start = i;

            if (Character.isWhitespace(c)) {
                while (i < end && Character.isWhitespace(array[i])) i++;
                addToken(text, start, i - 1, Token.WHITESPACE, newStartOffset + start);
                continue;
            }

            if (c == '/' && i + 1 < end && array[i + 1] == '/') {
                addToken(text, start, end - 1, Token.COMMENT_EOL, newStartOffset + start);
                i = end;
                continue;
            }

            if (c == '/' && i + 1 < end && array[i + 1] == '*') {
                int close = indexOfCommentClose(array, i + 2, end);
                if (close < 0) {
                    addToken(text, start, end - 1, Token.COMMENT_MULTILINE, newStartOffset + start);
                    return firstToken; // continuation
                }
                addToken(text, start, close + 1, Token.COMMENT_MULTILINE, newStartOffset + start);
                i = close + 2;
                continue;
            }

            if (c == '"' || c == '\'') {
                i++;
                while (i < end && array[i] != c) {
                    if (array[i] == '\\' && i + 1 < end) i++; // skip escaped char
                    i++;
                }
                if (i < end) i++; // consume the closing quote
                addToken(text, start, i - 1,
                        c == '"' ? Token.LITERAL_STRING_DOUBLE_QUOTE : Token.LITERAL_CHAR,
                        newStartOffset + start);
                prevSignificant = c;
                continue;
            }

            if (Character.isDigit(c)) {
                boolean hex = c == '0' && i + 1 < end && (array[i + 1] == 'x' || array[i + 1] == 'X');
                boolean isFloat = false;
                if (hex) {
                    i += 2;
                    while (i < end && isHexDigit(array[i])) i++;
                } else {
                    while (i < end && Character.isDigit(array[i])) i++;
                    if (i + 1 < end && array[i] == '.' && Character.isDigit(array[i + 1])) {
                        isFloat = true;
                        i++;
                        while (i < end && Character.isDigit(array[i])) i++;
                    }
                    if (i < end && (array[i] == 'e' || array[i] == 'E')) {
                        int save = i;
                        i++;
                        if (i < end && (array[i] == '+' || array[i] == '-')) i++;
                        if (i < end && Character.isDigit(array[i])) {
                            isFloat = true;
                            while (i < end && Character.isDigit(array[i])) i++;
                        } else {
                            i = save; // not an exponent after all
                        }
                    }
                }
                // Numeric suffixes (MVEL/Java: f, d, l, b, i...).
                while (i < end && Character.isLetter(array[i])) i++;
                addToken(text, start, i - 1,
                        hex ? Token.LITERAL_NUMBER_HEXADECIMAL
                                : isFloat ? Token.LITERAL_NUMBER_FLOAT : Token.LITERAL_NUMBER_DECIMAL_INT,
                        newStartOffset + start);
                prevSignificant = '0';
                continue;
            }

            if (Character.isJavaIdentifierStart(c)) {
                while (i < end && Character.isJavaIdentifierPart(array[i])) i++;
                boolean afterDot = prevSignificant == '.';
                boolean call = nextSignificantIs(array, i, end, '(');
                int type;
                if (call) {
                    type = Token.FUNCTION;
                } else if (afterDot) {
                    type = Token.VARIABLE; // property access: g.x, player.health
                } else {
                    int keyword = wordsToHighlight.get(array, start, i - 1);
                    type = keyword != -1 ? keyword : Token.IDENTIFIER;
                }
                addToken(text, start, i - 1, type, newStartOffset + start);
                prevSignificant = 'a';
                continue;
            }

            if (SEPARATORS.indexOf(c) >= 0) {
                i++;
                addToken(text, start, start, Token.SEPARATOR, newStartOffset + start);
                prevSignificant = c;
                continue;
            }

            // Everything else: operator characters, one at a time (., +, ?:, &&, ...).
            i++;
            addToken(text, start, start, Token.OPERATOR, newStartOffset + start);
            prevSignificant = c;
        }

        addNullToken();
        return firstToken;
    }

    private static boolean nextSignificantIs(char[] array, int from, int end, char expected) {
        for (int j = from; j < end; j++) {
            if (Character.isWhitespace(array[j])) continue;
            return array[j] == expected;
        }
        return false;
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int indexOfCommentClose(char[] array, int from, int end) {
        for (int j = from; j < end - 1; j++) {
            if (array[j] == '*' && array[j + 1] == '/') return j;
        }
        return -1;
    }
}
