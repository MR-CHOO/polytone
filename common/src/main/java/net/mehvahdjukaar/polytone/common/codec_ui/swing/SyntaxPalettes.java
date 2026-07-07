package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

import java.awt.Color;

/**
 * Custom RSyntax color schemes. The JSON palette here is deliberately a DIFFERENT family from the
 * expression (MVEL) palette in {@link ExpressionWidget} — that one is cool (blue identifiers, teal
 * properties, amber numbers, magenta keywords); this one is warm and structural (gold keys, green
 * strings, blue numbers, purple literals) so the two editor kinds read as distinct at a glance.
 *
 * <p>Applied AFTER the RSyntax light/dark theme loads, so it overrides just the JSON token colors.
 * RSyntax's JSON lexer tags object keys as {@link Token#VARIABLE} (separate from string values),
 * which is what lets keys carry their own color.</p>
 */
final class SyntaxPalettes {

    private SyntaxPalettes() {}

    private static volatile boolean builtinsRegistered;

    /**
     * Re-register the built-in RSyntax lexers we use with an RSyntax-owning classloader. RSyntax's
     * default {@link TokenMakerFactory} resolves bundled lexer classes reflectively, and in a modded
     * (isolated-classloader) runtime that resolution can fail — leaving files rendered as plain,
     * un-highlighted text. Binding each style to the classloader that actually holds those classes
     * (the same fix {@link MvelTokenMaker} uses) makes highlighting reliable everywhere. Idempotent.
     */
    static synchronized void ensureBuiltinTokenMakers() {
        if (builtinsRegistered) return;
        builtinsRegistered = true;
        AbstractTokenMakerFactory factory = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
        ClassLoader cl = TokenMakerFactory.class.getClassLoader(); // the jar that holds the modes.* lexers
        String modes = "org.fife.ui.rsyntaxtextarea.modes.";
        factory.putMapping(SyntaxConstants.SYNTAX_STYLE_JSON, modes + "JsonTokenMaker", cl);
        factory.putMapping(SyntaxConstants.SYNTAX_STYLE_MARKDOWN, modes + "MarkdownTokenMaker", cl);
        factory.putMapping(SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE, modes + "PropertiesFileTokenMaker", cl);
        factory.putMapping(SyntaxConstants.SYNTAX_STYLE_INI, modes + "IniTokenMaker", cl);
        factory.putMapping(SyntaxConstants.SYNTAX_STYLE_C, modes + "CTokenMaker", cl);
    }

    static void applyJson(RSyntaxTextArea area, boolean dark) {
        SyntaxScheme scheme = area.getSyntaxScheme();

        Color key     = dark ? new Color(0xE8A657) : new Color(0xB25000); // object keys — warm gold
        Color string  = dark ? new Color(0x9ECE6A) : new Color(0x4C7A28); // string values — green
        Color number  = dark ? new Color(0x7AA2F7) : new Color(0x1B5EDB); // numbers — blue
        Color literal = dark ? new Color(0xBB9AF7) : new Color(0x7A3EBF); // true / false / null — purple
        Color punct   = dark ? new Color(0x8A929E) : new Color(0x6A737D); // braces / brackets — muted

        set(scheme, Token.VARIABLE, key);                       // object keys
        set(scheme, Token.LITERAL_STRING_DOUBLE_QUOTE, string); // string values
        set(scheme, Token.LITERAL_NUMBER_DECIMAL_INT, number);
        set(scheme, Token.LITERAL_NUMBER_FLOAT, number);
        set(scheme, Token.LITERAL_NUMBER_HEXADECIMAL, number);
        set(scheme, Token.LITERAL_BOOLEAN, literal);            // true / false
        set(scheme, Token.RESERVED_WORD, literal);              // null
        set(scheme, Token.SEPARATOR, punct);

        area.repaint();
    }

    private static void set(SyntaxScheme scheme, int token, Color color) {
        var style = scheme.getStyle(token);
        if (style != null) style.foreground = color;
    }
}
