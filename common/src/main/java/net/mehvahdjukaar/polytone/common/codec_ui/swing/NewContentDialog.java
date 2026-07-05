package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackWorkspace;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Guided "add content" flow: pick WHAT (a creatable {@link CodecEntry}, i.e. one that
 * declares its pack container dir), WHERE (a namespace — existing ones offered, new ones
 * typed), and the NAME. The dialog computes the one valid file location itself
 * ({@code assets|data/<ns>/<container>/<name>.json}) — the user never chooses a folder,
 * so content can't be created in a place the game won't read.
 */
final class NewContentDialog extends JDialog {

    /** What the user asked to create; {@code file} does not exist yet. */
    record Result(CodecEntry entry, String namespace, String name, Path file) {}

    private final PackWorkspace workspace;
    private final JComboBox<CodecEntry> conceptBox;
    private final JComboBox<String> namespaceBox;
    private final JTextField nameField = new JTextField();
    private final JLabel pathPreview = new JLabel(" ");
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton createButton = new JButton("Create");

    private @Nullable Result result;

    /** Modal; returns null on cancel. Caller guarantees an open workspace. */
    static @Nullable Result show(JFrame owner, Workbench model, PackWorkspace workspace) {
        List<CodecEntry> creatable = model.entries().stream()
                .filter(e -> e.containerDir() != null).toList();
        if (creatable.isEmpty()) return null;
        NewContentDialog dialog = new NewContentDialog(owner, workspace, creatable);
        dialog.setVisible(true); // blocks until closed
        return dialog.result;
    }

    private NewContentDialog(JFrame owner, PackWorkspace workspace, List<CodecEntry> creatable) {
        super(owner, "New Content", true);
        this.workspace = workspace;

        conceptBox = new JComboBox<>(creatable.toArray(new CodecEntry[0]));
        conceptBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof CodecEntry entry) setText(entry.label());
                return this;
            }
        });

        // Existing namespaces from the pack; the combo is editable so a NEW namespace is
        // just typed in (its folders get created on first save).
        List<String> namespaces = workspace.namespaces();
        namespaceBox = new JComboBox<>(namespaces.toArray(new String[0]));
        namespaceBox.setEditable(true);
        if (namespaces.isEmpty()) namespaceBox.setSelectedItem("minecraft");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(
                UiScale.large(), UiScale.large(), UiScale.med(), UiScale.large()));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = UiScale.insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, gc, 0, "Concept:", conceptBox);
        addRow(form, gc, 1, "Namespace:", namespaceBox);
        addRow(form, gc, 2, "Name:", nameField);

        pathPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(13)));
        pathPreview.setForeground(EditorOps.mutedColor());
        gc.gridx = 0;
        gc.gridy = 3;
        gc.gridwidth = 2;
        form.add(pathPreview, gc);

        errorLabel.setForeground(EditorOps.errorColor());
        gc.gridy = 4;
        form.add(errorLabel, gc);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        createButton.addActionListener(e -> onCreate());
        Box buttons = Box.createHorizontalBox();
        buttons.setBorder(BorderFactory.createEmptyBorder(0, UiScale.large(), UiScale.large(), UiScale.large()));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(UiScale.med()));
        buttons.add(createButton);

        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(createButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Revalidate on every change.
        DocumentListener revalidate = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        };
        nameField.getDocument().addDocumentListener(revalidate);
        conceptBox.addActionListener(e -> refresh());
        namespaceBox.addActionListener(e -> refresh());
        if (namespaceBox.getEditor().getEditorComponent() instanceof JTextField nsField) {
            nsField.getDocument().addDocumentListener(revalidate);
        }
        refresh();

        pack();
        setMinimumSize(new Dimension(UiScale.px(560), getPreferredSize().height));
        setSize(Math.max(getWidth(), UiScale.px(560)), getHeight());
        setLocationRelativeTo(owner);
    }

    private static void addRow(JPanel form, GridBagConstraints gc, int row, String label, JComponent field) {
        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.gridy = row;
        gc.weightx = 0;
        form.add(new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        form.add(field, gc);
    }

    private @Nullable CodecEntry selectedEntry() {
        return (CodecEntry) conceptBox.getSelectedItem();
    }

    private String namespace() {
        Object item = namespaceBox.getEditor().getItem(); // editable combo: editor is the truth
        return item == null ? "" : item.toString().trim().toLowerCase(Locale.ROOT);
    }

    private String name() {
        String raw = nameField.getText().trim().toLowerCase(Locale.ROOT);
        return raw.endsWith(".json") ? raw.substring(0, raw.length() - ".json".length()) : raw;
    }

    /** Live validation: preview the computed path, explain the first problem, gate Create. */
    private void refresh() {
        CodecEntry entry = selectedEntry();
        String ns = namespace();
        String name = name();

        String error = null;
        Path file = null;
        if (entry == null || entry.containerDir() == null) {
            error = "Pick a content type";
        } else {
            String side = entry.side() == Side.SERVER_DATA ? "data" : "assets";
            pathPreview.setText(side + "/" + (ns.isEmpty() ? "<namespace>" : ns) + "/"
                    + entry.containerDir() + "/" + (name.isEmpty() ? "<name>" : name) + ".json");
            if (ns.isEmpty()) {
                error = "Pick or type a namespace";
            } else if (!PackWorkspace.isValidNamespace(ns)) {
                error = "Invalid namespace (allowed: a-z 0-9 _ . -)";
            } else if (name.isEmpty()) {
                error = "Type a name";
            } else if (!PackWorkspace.isValidResourcePath(name)) {
                error = "Invalid name (allowed: a-z 0-9 _ . - and / for subfolders)";
            } else {
                file = workspace.fileFor(entry.side(), ns, entry.containerDir(), name);
                if (Files.exists(file)) error = "That file already exists — open it from the Files tree instead";
            }
        }

        errorLabel.setText(error == null ? " " : error);
        createButton.setEnabled(error == null);
        if (error == null && entry != null && file != null) {
            result = null; // only set on Create
        }
    }

    private void onCreate() {
        CodecEntry entry = selectedEntry();
        String ns = namespace();
        String name = name();
        if (entry == null || entry.containerDir() == null) return;
        if (!PackWorkspace.isValidNamespace(ns) || !PackWorkspace.isValidResourcePath(name)) return;
        Path file = workspace.fileFor(entry.side(), ns, entry.containerDir(), name);
        if (Files.exists(file)) return;
        result = new Result(entry, ns, name, file);
        dispose();
    }
}
