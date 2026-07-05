/**
 * The PRODUCTION Polytone pack editor: polytone-specific assembly on top of the generic
 * {@code codec_ui} library. {@code PolytoneEditor} is the entry point (platform code calls
 * {@code PolytoneEditor.open()}); it owns the real content library (colormaps, lightmaps,
 * block/fluid/item/dimension modifiers with their pack container dirs), the schema/widget
 * registrations ({@code PolytoneSchemas}), and the running-game hooks
 * ({@code GameReloadHooks}: pack reloads + game folders). {@code UiPreviewLauncher} is the
 * bare-JVM main for UI iteration without a game.
 *
 * <p>Dependency direction: this package depends on codec_ui (api, workbench, swing) and on
 * polytone content codecs; codec_ui must never depend back on this package.</p>
 */
package net.mehvahdjukaar.polytone.editor;
