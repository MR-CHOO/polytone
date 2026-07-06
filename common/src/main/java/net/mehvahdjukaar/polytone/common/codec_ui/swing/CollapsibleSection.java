package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Disclosure container for bulky inline editors (raw JSON, expressions): a one-line header
 * (chevron + title + live summary) that expands to the real editor on click. Collapsed is
 * the default so records full of these fields stay scannable — the summary line carries
 * the current value / validity so collapsing loses visibility, not information.
 *
 * <p>Reports a dynamic maximum size (preferred height) so BoxLayout parents track the
 * expand/collapse height change instead of pinning the built-time height.</p>
 */
final class CollapsibleSection extends JPanel {

    private final JLabel chevron = new JLabel();
    private final JLabel summary = new JLabel();
    // Rounded hairline container — the one grouping style shared with AnyOf and list items.
    private final JPanel contentHost = new JPanel(new java.awt.BorderLayout()) {
        @Override public void updateUI() {
            super.updateUI();
            setOpaque(false);
            setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                    new java.awt.Insets(8, 10, 8, 10), EditorOps.dividerColor(), 1f, 10));
        }
    };
    private JPanel indentHost;
    private boolean collapsed = true;
    private boolean summaryError;

    CollapsibleSection(String title, JComponent content, boolean collapsedByDefault) {
        this(title, null, content, collapsedByDefault);
    }

    /** {@code glyph} marks the embedded language (ƒ(x) for expressions, &lt;/&gt; for JSON). */
    CollapsibleSection(String title, @Nullable javax.swing.Icon glyph, JComponent content,
                       boolean collapsedByDefault) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(title); // default L&F font — follows zoom

        summary.setForeground(EditorOps.mutedColor());
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScale.px(13)));

        JPanel header = new JPanel() {
            @Override public Dimension getMaximumSize() {
                // Live so the header row grows with a zoomed font instead of being pinned to
                // its build-time height (which clipped the title / chevron after zooming in).
                // Reentrancy-safe against BoxLayout's non-reentrant size cache.
                Dimension d = UiScale.maxHeightHugging(this);
                return new Dimension(d.width, Math.max(d.height, UiScale.px(24)));
            }
        };
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText("Click to expand / collapse");
        header.add(chevron);
        header.add(Box.createHorizontalStrut(UiScale.small()));
        if (glyph != null) {
            header.add(new JLabel(glyph));
            header.add(Box.createHorizontalStrut(UiScale.small()));
        }
        header.add(titleLabel);
        header.add(Box.createHorizontalStrut(UiScale.med()));
        header.add(summary);
        header.add(Box.createHorizontalGlue());
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { setCollapsed(!collapsed); }
        });

        // Expanded content sits in the workbench's shared grouping container: rounded
        // hairline outline (same language as AnyOf groups and list items).
        contentHost.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentHost.add(content, java.awt.BorderLayout.CENTER);

        JPanel indent = new JPanel(new java.awt.BorderLayout());
        indent.setOpaque(false);
        indent.setAlignmentX(Component.LEFT_ALIGNMENT);
        indent.setBorder(BorderFactory.createEmptyBorder(UiScale.small(), UiScale.px(7), 0, 0));
        indent.add(contentHost, java.awt.BorderLayout.CENTER);

        add(header);
        add(indent);
        this.indentHost = indent;
        setCollapsed(collapsedByDefault);
    }

    void setCollapsed(boolean value) {
        collapsed = value;
        Icon icon = UIManager.getIcon(collapsed ? "Tree.collapsedIcon" : "Tree.expandedIcon");
        chevron.setIcon(icon);
        if (icon == null) chevron.setText(collapsed ? "▸" : "▾"); // L&F without tree icons
        if (indentHost != null) indentHost.setVisible(!collapsed);
        summary.setVisible(collapsed);
        revalidate();
        repaint();
    }

    boolean isCollapsed() {
        return collapsed;
    }

    /** One-line value/validity readout shown while collapsed; {@code error} paints it red. */
    void setSummary(@Nullable String text, boolean error) {
        summary.setText(text == null ? "" : text);
        if (error != summaryError) {
            summaryError = error;
            summary.setForeground(error ? EditorOps.errorColor() : EditorOps.mutedColor());
        }
    }

    /** Collapse/expand must re-flow BoxLayout parents — never pin the built-time height.
     *  Reentrancy-safe against BoxLayout's non-reentrant size cache. */
    @Override
    public Dimension getMaximumSize() {
        return UiScale.maxHeightHugging(this);
    }
}
