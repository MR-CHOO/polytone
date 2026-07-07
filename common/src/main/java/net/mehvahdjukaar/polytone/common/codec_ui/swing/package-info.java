/**
 * Swing UI backend for codec_ui: renders a {@link net.mehvahdjukaar.codecui.Schema}
 * tree as editor widgets, and hosts the single-window
 * {@link net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWorkbench} shell (pack file
 * tree, codec library, closable editor tabs). Depends only on the public API and
 * {@code workbench} model packages — never on {@code internal}. Other backends (ImGui,
 * in-game screens, ...) would be siblings of this package rendering the same
 * {@code workbench} model and implementing the same widget pattern: one widget per
 * {@code Schema} variant plus a factory
 * ({@link net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWidgetFactory}).
 *
 * <p>Backend-specific custom widgets are bound to codecs via
 * {@link net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWidgetDef#bind}, which stores
 * the def in {@code Schema.Custom} — the core API never references Swing types.</p>
 */
package net.mehvahdjukaar.polytone.common.codec_ui.swing;
