package net.mehvahdjukaar.polytone.common.codec_ui;

import net.mehvahdjukaar.polytone.common.codec_ui.internal.SchemaResolver;

import java.util.Map;

/**
 * Opt-in SPI for codecs that decode from a closed, enumerable set of named values —
 * custom registries, string→object dispatch maps, and similar. Implementing this on a
 * {@code Codec} lets the {@link SchemaResolver} do two things it can't do structurally:
 *
 * <ul>
 *   <li><b>Direct resolution:</b> the codec renders as a dropdown of the registered names
 *       instead of a raw string field.</li>
 *   <li><b>Dispatch enumeration:</b> when the codec is the key of a
 *       {@code Codec.dispatch(...)}, every value is fed through the dispatch's
 *       type→codec function, producing a fully-populated variant picker with real
 *       per-variant editors.</li>
 * </ul>
 *
 * <p>Called fresh on every resolve, so late registrations are picked up automatically.
 * Keys are the display/serialized names; values are the objects the codec decodes to
 * (i.e. the dispatch keys).</p>
 */
public interface EnumerableCodec {

    Map<String, ?> codecUiValues();
}
