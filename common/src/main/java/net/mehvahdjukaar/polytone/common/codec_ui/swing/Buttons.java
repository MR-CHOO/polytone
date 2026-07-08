package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JToggleButton;
import java.awt.event.ActionListener;

/**
 * Factory + configurator for the workbench's two button flavors, collapsing the repeated
 * {@code putClientProperty("JButton.buttonType", ...); setFocusable(false); ...} boilerplate.
 *
 * <ul>
 *   <li><b>toolbar</b> — flat, borderless, non-focusable (FlatLaf {@code toolBarButton}); the
 *       icon rows, zoom/theme/view toggles, copy buttons.</li>
 *   <li><b>primary</b> — accent-filled default button carrying the main action (New Content, Save).</li>
 * </ul>
 */
final class Buttons {

    /** Configure an existing button as a flat, non-focusable toolbar button. Returns it. */
    static <B extends AbstractButton> B asToolbar(B button) {
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        return button;
    }

    /** New flat toolbar button: icon + tooltip + action. */
    static JButton toolbar(Icon icon, String tooltip, ActionListener action) {
        JButton b = asToolbar(new JButton(icon));
        b.setToolTipText(tooltip);
        b.addActionListener(action);
        return b;
    }

    /** New flat toolbar toggle: text/glyph + tooltip (caller wires selection + action). */
    static JToggleButton toolbarToggle(String text, String tooltip) {
        JToggleButton b = asToolbar(new JToggleButton(text));
        b.setToolTipText(tooltip);
        return b;
    }

    /** New accent-filled primary (FlatLaf {@code default}) button. */
    static JButton primary(String text, Icon icon) {
        JButton b = icon != null ? new JButton(text, icon) : new JButton(text);
        b.putClientProperty("JButton.buttonType", "default");
        return b;
    }
}
