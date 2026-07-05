/**
 * Swing UI backend for codec_ui: renders a {@link net.mehvahdjukaar.polytone.common.codec_ui.Schema}
 * tree as editor widgets. Depends only on the public API package — never on
 * {@code internal}. Other backends (ImGui, in-game screens, ...) would be siblings of this
 * package implementing the same pattern: one widget per {@code Schema} variant plus a
 * factory ({@link net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWidgetFactory}).
 *
 * <p>Backend-specific custom widgets are bound to codecs via
 * {@link net.mehvahdjukaar.polytone.common.codec_ui.swing.SwingWidgetDef#bind}, which stores
 * the def in {@code Schema.Custom} — the core API never references Swing types.</p>
 */
package net.mehvahdjukaar.polytone.common.codec_ui.swing;
