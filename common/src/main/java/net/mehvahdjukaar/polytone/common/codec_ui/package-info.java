/**
 * codec_ui — automatic editing GUIs for arbitrary DFU {@code Codec}s.
 *
 * <p><b>This root package is the public API.</b> Everything a consumer needs lives here:</p>
 * <ul>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodec} — entry point:
 *       {@code SchemaCodec.wrap(anyCodec)} auto-introspects; {@code of(codec, schema)} pairs
 *       a hand-made schema.</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.Schema} — the sealed ADT describing
 *       a codec's edit surface; consumed by UI backends.</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs} — facade: combinators
 *       and ALL extension-point registration (see its class javadoc for the how-to-extend
 *       overview).</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaRecord} /
 *       {@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaRecordBuilder} — DSLs for
 *       declaring schema-carrying record codecs by hand (companion style).</li>
 *   <li>SPIs: {@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaHandler} (custom
 *       structural handlers) and {@link net.mehvahdjukaar.polytone.common.codec_ui.EnumerableCodec}
 *       (enumerable custom codecs / dispatch keys).</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor} — backend-agnostic
 *       editor handle.</li>
 * </ul>
 *
 * <p>Subpackages: {@code internal} (resolver machinery — do not reference from outside;
 * no compatibility guarantees), {@code swing} (the Swing UI backend; depends only on this
 * API), {@code example} (demo/test launcher and sample codecs; never referenced by
 * production code). The construction mixins under
 * {@code net.mehvahdjukaar.polytone.mixins.codec_ui} are part of the internal layer — they
 * only exist there because the mixin package is fixed by the mixin config.</p>
 */
package net.mehvahdjukaar.polytone.common.codec_ui;
