/**
 * codec_ui — Polytone's editor UI over the CodecUI library.
 *
 * <p>The declarative core AND the inference engine now live in the standalone
 * <b>codecui</b> library (jar-in-jar): {@link net.mehvahdjukaar.codecui.Schema},
 * {@link net.mehvahdjukaar.codecui.SchemaCodec} ({@code SchemaCodec.wrap(anyCodec)} runs the
 * engine), {@link net.mehvahdjukaar.codecui.SchemaCodecs} (primitives, combinators, and the
 * {@code registerCompanion}/{@code registerHandler}/{@code registerDispatchKeys} extension
 * points), the resolver, the construction mixins, and the {@link net.mehvahdjukaar.codecui.SchemaRecord}
 * DSL. This package is only the app-side editor UI:</p>
 * <ul>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor} — backend-agnostic
 *       editor handle.</li>
 *   <li>{@code swing} — the Swing UI backend (depends only on the codecui API).</li>
 *   <li>{@code workbench} — the standalone editor workbench built on that backend.</li>
 * </ul>
 *
 * <p>Polytone's own schema companions and Swing-widget bindings are registered at editor
 * bootstrap in {@code net.mehvahdjukaar.polytone.editor.PolytoneSchemas}.</p>
 */
package net.mehvahdjukaar.polytone.common.codec_ui;
