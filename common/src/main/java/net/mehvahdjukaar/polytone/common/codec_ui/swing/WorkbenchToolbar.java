package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor.Side;
import net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackReloader;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The workbench header band: brand mark, the current-pack breadcrumb chip, the primary
 * "New Content" action, the game-sync reload pair, and the compact/theme toggles.
 *
 * <p>Owns everything about the reload buttons — the availability {@link javax.swing.Timer}, the
 * enablement polling, and the trigger action — since those belong together. Pack context and the
 * New-Content enablement are pushed in by the shell via {@link #setPack} / {@link #setNewContentEnabled}
 * as the workspace changes; reload feedback goes back out through the {@link StatusSink}.</p>
 */
final class WorkbenchToolbar {

    /** Where reload progress/outcome messages are surfaced (the shell's status bar). */
    interface StatusSink {
        void info(String message);
        void success(String message);
        void error(String message);
    }

    private final JToolBar bar;
    private final JLabel packLabel = StyledLabels.muted("No pack opened");
    private final JLabel packKindLabel = StyledLabels.accentSmall("");
    private final JButton newContentButton = Buttons.primary("New Content", WorkbenchIcons.filePlus());
    private final JButton reloadResourcesButton = new JButton("Reload Resources");
    private final JButton reloadDataButton = new JButton("Reload Data");
    private final javax.swing.Timer reloadAvailabilityTimer;
    private final StatusSink status;

    WorkbenchToolbar(Runnable onOpenPack, Runnable onNewContent, StatusSink status) {
        this.status = status;

        // Slight elevation + bottom hairline turns the toolbar into a proper header band.
        // Styling in updateUI() so a live theme switch recomputes the surface + divider.
        bar = new JToolBar() {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.surface(0.03f));
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, EditorOps.dividerColor()),
                        BorderFactory.createEmptyBorder(UiScale.small(), UiScale.med(), UiScale.small(), UiScale.med())));
            }
        };
        bar.setFloatable(false);

        // Accent brand mark, left-aligned like an app title bar.
        JLabel brand = StyledLabels.of("Polytone", l -> {
            l.setFont(UiScale.labelFont(Font.BOLD, 2f));
            l.setForeground(EditorOps.accentColor());
        });
        brand.setBorder(BorderFactory.createEmptyBorder(0, UiScale.small(), 0, UiScale.large()));
        bar.add(brand);

        // Context first, like a title bar: brand · current pack chip. Actions follow after
        // the hairline — the old order (actions, THEN the chip, then glue) left the chip
        // floating mid-bar between button groups.
        bar.add(buildPackChip(onOpenPack));

        bar.add(Box.createHorizontalStrut(UiScale.med()));
        bar.add(separator());
        bar.add(Box.createHorizontalStrut(UiScale.med()));

        newContentButton.setToolTipText(
                "Add content to the pack — the file lands in its correct folder automatically");
        newContentButton.addActionListener(e -> onNewContent.run());
        // Nest in a plain panel so FlatLaf does NOT flatten it to a toolbar button (which would
        // drop the accent fill); this keeps the native default-button look, matching dialogs.
        JPanel newContentWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        newContentWrap.setOpaque(false);
        newContentWrap.add(newContentButton);
        bar.add(newContentWrap);

        bar.add(Box.createHorizontalGlue());

        // Game-sync pair: green-tinted icons — "this talks to the running game".
        // Borderless like the mockup: lighter header, the tint does the signaling.
        reloadResourcesButton.putClientProperty("JButton.buttonType", "toolBarButton");
        reloadDataButton.putClientProperty("JButton.buttonType", "toolBarButton");
        reloadResourcesButton.setIcon(WorkbenchIcons.refreshTinted());
        reloadResourcesButton.setToolTipText("Reload the game's resource packs (F3+T) so saved files take effect");
        reloadResourcesButton.addActionListener(e -> triggerReload(Side.CLIENT_RESOURCES, reloadResourcesButton));
        reloadDataButton.setIcon(WorkbenchIcons.databaseTinted());
        reloadDataButton.setToolTipText("Reload the integrated server's datapacks (/reload)");
        reloadDataButton.addActionListener(e -> triggerReload(Side.SERVER_DATA, reloadDataButton));
        bar.add(reloadResourcesButton);
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(reloadDataButton);
        // Open-pack lives in the Files panel header, next to Re-scan — the two pack-folder actions
        // belong together (see PackTreePanel).

        // Zoom lives in the status bar (bottom-right, the conventional spot) — keeping the
        // header to context + actions only.
        bar.add(Box.createHorizontalStrut(UiScale.med()));
        bar.add(separator());
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(buildCompactToggle());
        bar.add(Box.createHorizontalStrut(UiScale.small()));
        bar.add(buildThemeToggle());

        // Game state (world joined, server started...) changes reload availability over time.
        reloadAvailabilityTimer = new javax.swing.Timer(1500, e -> updateReloadButtons());
        reloadAvailabilityTimer.start();
        updateReloadButtons();
    }

    /** The header band to seat at the top of the workbench frame. */
    JComponent component() {
        return bar;
    }

    /** Update the breadcrumb chip; {@code name == null} shows the no-pack placeholder. */
    void setPack(@Nullable String name, @Nullable String kind, @Nullable String tooltip) {
        if (name == null) {
            packLabel.setText("No pack opened");
            packKindLabel.setText("");
            packLabel.setToolTipText(null);
        } else {
            packLabel.setText(name);
            packKindLabel.setText(kind);
            packLabel.setToolTipText(tooltip);
        }
    }

    void setNewContentEnabled(boolean enabled) {
        newContentButton.setEnabled(enabled);
    }

    /** Stop the availability poll — called when the window closes. */
    void dispose() {
        reloadAvailabilityTimer.stop();
    }

    // -------------------- Reload --------------------

    private void updateReloadButtons() {
        PackReloader reloader = PackReloader.get();
        boolean resources = reloader.available(Side.CLIENT_RESOURCES);
        boolean data = reloader.available(Side.SERVER_DATA);
        reloadResourcesButton.setEnabled(resources);
        reloadDataButton.setEnabled(data);
        if (!resources) reloadResourcesButton.setToolTipText("No running game to reload");
        if (!data) reloadDataButton.setToolTipText("No integrated server running");
    }

    private void triggerReload(Side side, JButton button) {
        button.setEnabled(false);
        status.info(side == Side.CLIENT_RESOURCES ? "Reloading resource packs…" : "Reloading datapacks…");
        PackReloader.get().reload(side, error -> SwingUtilities.invokeLater(() -> {
            updateReloadButtons();
            if (error == null) status.success("Reload complete — new files are now referenceable");
            else status.error("Reload failed: " + error);
        }));
    }

    // -------------------- Sub-components --------------------

    /**
     * Rounded breadcrumb chip showing the opened pack (folder glyph + name — kind); clicking
     * it opens another pack. A contained pill, so the current-location readout is visually
     * separate from the action buttons around it.
     */
    private JComponent buildPackChip(Runnable onOpenPack) {
        JPanel chip = new JPanel() {
            @Override public void updateUI() {
                super.updateUI();
                setOpaque(true);
                setBackground(EditorOps.surface(0.06f));
                setBorder(new com.formdev.flatlaf.ui.FlatLineBorder(
                        new java.awt.Insets(4, 10, 4, 10), EditorOps.dividerColor(), 1f, 999));
            }
            @Override public Dimension getMaximumSize() {
                return getPreferredSize(); // hug the label — never stretch into a bar
            }
        };
        chip.setLayout(new BoxLayout(chip, BoxLayout.X_AXIS));
        JLabel icon = new JLabel(WorkbenchIcons.folder());
        icon.setForeground(EditorOps.mutedColor());
        chip.add(icon);
        chip.add(Box.createHorizontalStrut(UiScale.small()));
        chip.add(packLabel);
        chip.add(Box.createHorizontalStrut(UiScale.med()));
        chip.add(packKindLabel);
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chip.setToolTipText("Click to open a different pack");
        chip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onOpenPack.run(); }
        });
        return chip;
    }

    /** Toggle for the narrow-screen compact layout (field name stacked above its value). */
    private JToggleButton buildCompactToggle() {
        JToggleButton toggle = Buttons.toolbarToggle("☰", // ☰ rows/compact glyph
                "Compact layout — stack each field's name above its value (saves width)");
        toggle.setSelected(SwingSchemaEditor.isCompactMode());
        toggle.addActionListener(e -> SwingSchemaEditor.toggleCompact());
        return toggle;
    }

    /** Sun/moon button flipping the whole workbench between the light and dark FlatLaf themes. */
    private JButton buildThemeToggle() {
        JButton toggle = Buttons.asToolbar(new JButton());
        Runnable sync = () -> {
            boolean dark = SwingSchemaEditor.isDarkTheme();
            // Show the destination: a sun while dark (click → light), a moon while light.
            toggle.setIcon(dark ? WorkbenchIcons.sun() : WorkbenchIcons.moon());
            toggle.setToolTipText(dark ? "Switch to light theme" : "Switch to dark theme");
        };
        sync.run();
        toggle.addActionListener(e -> {
            SwingSchemaEditor.toggleTheme();
            sync.run();
        });
        return toggle;
    }

    /** Vertical hairline dividing toolbar / status-bar groups. Shared with the status bar. */
    static JComponent separator() {
        return new JSeparator(SwingConstants.VERTICAL) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(UiScale.px(1), UiScale.px(24));
            }
        };
    }
}
