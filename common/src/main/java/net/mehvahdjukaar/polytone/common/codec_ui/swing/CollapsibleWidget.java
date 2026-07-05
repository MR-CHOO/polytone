package net.mehvahdjukaar.polytone.common.codec_ui.swing;

/**
 * Widgets that render inside a {@link CollapsibleSection} (raw JSON, expressions). They
 * default to collapsed so forms stay scannable; the shell expands the one case where
 * collapsing is pointless — when such a widget IS the whole editor page.
 */
interface CollapsibleWidget {

    void setCollapsed(boolean collapsed);
}
