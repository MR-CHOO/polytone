package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import net.mehvahdjukaar.polytone.common.codec_ui.Schema;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The dedicated editor for expression-language strings: a multi-line syntax-highlighted
 * code area with debounced live validation and clickable variable/function hint chips.
 * Round-trips as a JSON string, so it binds to any string-shaped expression codec.
 *
 * <p>Configure per expression dialect via {@link #define()}:
 * <pre>{@code
 * ExpressionWidget.define()
 *     .variables("TIME", "POS_X", ...)          // chips; click inserts at caret
 *     .functions("state_prop")                   // chips; click inserts "name()"
 *     .validator(text -> errorMessageOrNull)     // usually: parse through the real codec
 * }</pre>
 * The widget itself knows nothing about any expression engine — the validator carries
 * that knowledge, keeping this class reusable across polytone's expression systems.</p>
 */
public final class ExpressionWidget implements SwingWidget, CollapsibleWidget {

    /** Returns a human-readable error for the expression text, or null when it compiles. */
    @FunctionalInterface
    public interface Validator {
        @Nullable String validate(String expression);
    }

    /** Configurable factory; one per expression dialect, shared across widget instances. */
    public static final class Def implements SwingWidgetDef<String> {
        private final List<String> variables = new ArrayList<>();
        private final List<String> functions = new ArrayList<>();
        private @Nullable Validator validator;
        private int rows = 5;

        private Def() {}

        public Def variables(String... names) {
            variables.addAll(Arrays.asList(names));
            return this;
        }

        public Def functions(String... names) {
            functions.addAll(Arrays.asList(names));
            return this;
        }

        public Def validator(Validator validator) {
            this.validator = validator;
            return this;
        }

        public Def rows(int rows) {
            this.rows = rows;
            return this;
        }

        @Override
        public SwingWidget create(Schema.Custom<String> schema) {
            return new ExpressionWidget(this);
        }
    }

    public static Def define() {
        return new Def();
    }

    private final Def def;
    private final JPanel root = new JPanel();
    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final CollapsibleSection section;
    private final @Nullable JTextArea status;
    private final javax.swing.Timer validateTimer;
    private @Nullable String lastError;

    private ExpressionWidget(Def def) {
        this.def = def;

        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.setOpaque(false);

        // ---- Code area: expression dialects are C-like enough that the JS lexer gives
        // useful coloring (numbers, operators, strings, parens) without a custom TokenMaker.
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setHighlightCurrentLine(false);
        area.setRows(def.rows);
        try (var in = Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/" + (FlatLaf.isLafDark() ? "dark.xml" : "default.xml"))) {
            if (in != null) Theme.load(in).apply(area);
        } catch (Throwable ignored) {
            // Theme is cosmetic only.
        }
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(15)));

        RTextScrollPane scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(false);
        scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension pref = scroll.getPreferredSize();
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        scroll.setMinimumSize(new Dimension(UiScale.px(200), pref.height));
        root.add(scroll);

        // ---- Hint chips: single scrollable strip; click inserts at the caret.
        if (!def.variables.isEmpty() || !def.functions.isEmpty()) {
            root.add(Box.createVerticalStrut(UiScale.small()));
            root.add(buildChipStrip());
        }

        // ---- Debounced validation status.
        if (def.validator != null) {
            status = new JTextArea(" ");
            status.setEditable(false);
            status.setFocusable(false);
            status.setLineWrap(true);
            status.setWrapStyleWord(true);
            status.setOpaque(false);
            status.setFont(UiScale.deriveFont(UIManager.getFont("Label.font"), Font.PLAIN, -1f));
            status.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(Box.createVerticalStrut(UiScale.small()));
            root.add(status);

            validateTimer = new javax.swing.Timer(350, e -> runValidation());
            validateTimer.setRepeats(false);
            area.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { validateTimer.restart(); }
                @Override public void removeUpdate(DocumentEvent e) { validateTimer.restart(); }
                @Override public void changedUpdate(DocumentEvent e) { validateTimer.restart(); }
            });
        } else {
            status = null;
            validateTimer = null;
        }

        // Collapsed by default: the header summary (text + validity) carries the value, so
        // records with several expression fields stay one line each until actually edited.
        section = new CollapsibleSection("expression", root, true);
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateSummary(); }
            @Override public void removeUpdate(DocumentEvent e) { updateSummary(); }
            @Override public void changedUpdate(DocumentEvent e) { updateSummary(); }
        });
        // Initial pass runs AFTER section exists — both paths write the header summary.
        if (def.validator != null) runValidation();
        else updateSummary();
    }

    @Override
    public void setCollapsed(boolean collapsed) {
        section.setCollapsed(collapsed);
    }

    private void updateSummary() {
        String text = area.getText().trim();
        String shown = text.isEmpty() ? "(empty)" : text.replaceAll("\\s+", " ");
        if (shown.length() > 60) shown = shown.substring(0, 57) + "…";
        boolean error = lastError != null && !text.isEmpty();
        section.setSummary(error ? "✗ " + shown : shown, error);
    }

    private JComponent buildChipStrip() {
        Box strip = Box.createHorizontalBox();
        JLabel tag = new JLabel(def.functions.isEmpty() ? "vars " : "vars/fns ");
        tag.setFont(UiScale.deriveFont(tag.getFont(), Font.BOLD, -2f));
        tag.setForeground(EditorOps.mutedColor());
        strip.add(tag);
        for (String name : def.variables) strip.add(chip(name, name));
        for (String name : def.functions) strip.add(chip(name + "()", name + "()"));
        strip.add(Box.createHorizontalGlue());

        JScrollPane chipScroll = new JScrollPane(strip,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        chipScroll.setBorder(BorderFactory.createEmptyBorder());
        chipScroll.setOpaque(false);
        chipScroll.getViewport().setOpaque(false);
        chipScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        int h = strip.getPreferredSize().height + UiScale.px(10); // room for the scrollbar
        chipScroll.setPreferredSize(new Dimension(UiScale.px(200), h));
        chipScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return chipScroll;
    }

    private JButton chip(String label, String insertion) {
        JButton chip = new JButton(label);
        chip.putClientProperty("JButton.buttonType", "toolBarButton");
        chip.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(12)));
        chip.setFocusable(false);
        chip.setToolTipText("Insert at cursor");
        chip.addActionListener(e -> {
            int caret = area.getCaretPosition();
            area.insert(insertion, caret);
            // For a function chip, park the caret between the parentheses.
            area.setCaretPosition(caret + insertion.length() - (insertion.endsWith("()") ? 1 : 0));
            area.requestFocusInWindow();
        });
        return chip;
    }

    private void runValidation() {
        if (def.validator == null || status == null) return;
        String error;
        try {
            error = def.validator.validate(area.getText());
        } catch (Throwable t) {
            error = String.valueOf(t);
        }
        status.setText(error == null ? "✓ compiles" : "✗ " + error);
        status.setForeground(error == null ? EditorOps.mutedColor() : EditorOps.errorColor());
        lastError = error;
        updateSummary();
    }

    // -------------------- SwingWidget --------------------

    @Override
    public JComponent component() {
        return section;
    }

    @Override
    public DataResult<JsonElement> currentJson() {
        return DataResult.success(new JsonPrimitive(area.getText()));
    }

    @Override
    public void setJson(@Nullable JsonElement value) {
        area.setText(value != null && value.isJsonPrimitive() ? value.getAsString() : "");
        area.setCaretPosition(0);
        area.discardAllEdits();
        if (validateTimer != null) validateTimer.restart();
    }
}
