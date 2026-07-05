package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import com.formdev.flatlaf.FlatLaf;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Plain text editor tab for pack files with no codec association (shaders, lang files,
 * unmatched JSON, ...). Syntax highlighting by extension, Ctrl+S saves in place.
 */
final class TextEditorPanel extends JPanel implements WorkbenchTab {

    private static final Map<String, String> SYNTAX_BY_EXT = Map.ofEntries(
            Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("mcmeta", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("lang", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("toml", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("cfg", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("ini", SyntaxConstants.SYNTAX_STYLE_INI),
            Map.entry("vsh", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("fsh", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("csh", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("glsl", SyntaxConstants.SYNTAX_STYLE_C));

    private final Path file;
    private final String name;
    private final RSyntaxTextArea area;
    private final JLabel status = new JLabel(" ");
    private String savedText;
    private boolean dirty;
    private @Nullable Runnable stateListener;

    TextEditorPanel(Path file) throws IOException {
        super(new BorderLayout(0, UiScale.small()));
        this.file = file;
        this.name = String.valueOf(file.getFileName());
        this.savedText = Files.readString(file);

        setBorder(BorderFactory.createEmptyBorder(
                UiScale.med(), UiScale.med(), UiScale.med(), UiScale.med()));

        area = new RSyntaxTextArea(savedText);
        area.setSyntaxEditingStyle(syntaxFor(name));
        area.setHighlightCurrentLine(false);
        try (var in = Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/" + (FlatLaf.isLafDark() ? "dark.xml" : "default.xml"))) {
            if (in != null) Theme.load(in).apply(area);
        } catch (Throwable ignored) {
            // Theme is cosmetic only.
        }
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(15)));
        area.setCaretPosition(0);
        area.discardAllEdits();

        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onEdit(); }
            @Override public void removeUpdate(DocumentEvent e) { onEdit(); }
            @Override public void changedUpdate(DocumentEvent e) { onEdit(); }
        });

        RTextScrollPane scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(true);
        add(scroll, BorderLayout.CENTER);

        status.setForeground(EditorOps.mutedColor());
        add(status, BorderLayout.SOUTH);

        var im = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "save");
        getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { save(); }
        });
    }

    private static String syntaxFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SYNTAX_BY_EXT.getOrDefault(ext, SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    private void onEdit() {
        boolean nowDirty = !area.getText().equals(savedText);
        if (nowDirty != dirty) {
            dirty = nowDirty;
            if (stateListener != null) stateListener.run();
        }
    }

    // -------------------- WorkbenchTab --------------------

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public String title() {
        return (dirty ? "• " : "") + name;
    }

    @Override
    public Path file() {
        return file;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public boolean save() {
        try {
            String text = area.getText();
            Files.writeString(file, text);
            savedText = text;
            status.setText("Saved");
            onEdit();
            return true;
        } catch (IOException e) {
            status.setText("Write error: " + e.getMessage());
            status.setForeground(EditorOps.errorColor());
            return false;
        }
    }

    @Override
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    @Override
    public void dispose() {}
}
