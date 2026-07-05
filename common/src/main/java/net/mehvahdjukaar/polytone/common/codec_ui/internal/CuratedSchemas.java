package net.mehvahdjukaar.polytone.common.codec_ui.internal;

import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.common.codec_ui.Schema;
import net.mehvahdjukaar.polytone.common.codec_ui.SchemaCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * THE hand-maintained list of schema registrations for codecs that auto-inspection can't
 * (or shouldn't) handle. Deliberately separate from the inference machinery in
 * {@link SchemaResolver}: everything here goes through the exact same public API a
 * third-party mod would use ({@code SchemaCodecs.registerCompanion / registerHandler /
 * registerDispatchKeys}), so this class doubles as the reference example for external
 * curation of "weird" codecs.
 *
 * <p>Ground rules for adding entries:</p>
 * <ul>
 *   <li>First try to make inference handle the CLASS of codec (new tier-2 handler,
 *       {@code EnumerableCodec}, mixin tag). Curate here only when that's impossible
 *       (opaque lambdas, shape-changing xmaps we want a nicer surface for) or not worth it.</li>
 *   <li>Schemas describe the ON-DISK JSON shape, not the runtime type — widgets edit JSON.</li>
 *   <li>Comment WHY inference fails for each entry.</li>
 * </ul>
 *
 * <p>Bootstrapped lazily by the resolver on first resolve; safe because companions are
 * looked up fresh each resolve (never baked into cached schemas at construction time).</p>
 */
public final class CuratedSchemas {

    private static volatile boolean bootstrapped = false;

    private CuratedSchemas() {}

    public static void bootstrap() {
        if (bootstrapped) return;
        synchronized (CuratedSchemas.class) {
            if (bootstrapped) return;
            bootstrapped = true;
        }
        VanillaDispatches.bootstrap();
        try {
            register();
        } catch (Throwable t) {
            Polytone.LOGGER.warn("[codec_ui] curated schema registration failed", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register() {
        // Identifier.CODEC is STRING.comapFlatMap -> inference yields plain text; an id
        // widget (with the registry-less picker) is the nicer surface.
        SchemaCodecs.registerCompanion(Identifier.CODEC, new Schema.ResourceId(null));

        // UUIDUtil.CODEC is INT_STREAM.comapFlatMap -> opaque (INT_STREAM has no widget).
        // On disk it is a fixed quadruple of ints.
        SchemaCodecs.registerCompanion(UUIDUtil.CODEC, (Schema) new Schema.ListOf<>(
                new Schema.IntRange(Integer.MIN_VALUE, Integer.MAX_VALUE), 4, 4));

        // UUIDUtil.STRING_CODEC / LENIENT_CODEC decode via opaque lambdas; on disk they are
        // the canonical hyphenated string form.
        Schema uuidString = new Schema.Str(36, 36, java.util.regex.Pattern.compile(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        SchemaCodecs.registerCompanion(UUIDUtil.STRING_CODEC, uuidString);

        // BlockPos.CODEC is INT_STREAM.comapFlatMap — INT_STREAM is opaque. On disk: [x, y, z].
        Schema intAll = new Schema.IntRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
        Schema floatAll = new Schema.FloatRange(-Float.MAX_VALUE, Float.MAX_VALUE);
        SchemaCodecs.registerCompanion(BlockPos.CODEC, (Schema) new Schema.ListOf<>(intAll, 3, 3));

        // JOML vector codecs are FLOAT/INT.listOf().comapFlatMap with an arity check hidden
        // in the lambda; inference sees an unbounded list, curation restores the fixed size.
        SchemaCodecs.registerCompanion(ExtraCodecs.VECTOR2F, (Schema) new Schema.ListOf<>(floatAll, 2, 2));
        SchemaCodecs.registerCompanion(ExtraCodecs.VECTOR3F, (Schema) new Schema.ListOf<>(floatAll, 3, 3));
        SchemaCodecs.registerCompanion(ExtraCodecs.VECTOR4F, (Schema) new Schema.ListOf<>(floatAll, 4, 4));
        SchemaCodecs.registerCompanion(ExtraCodecs.VECTOR3I, (Schema) new Schema.ListOf<>(intAll, 3, 3));

        // Color codecs: inference at best yields AnyOf(integer, text); a color picker is the
        // point of this whole exercise. INT-primary variants emit packed ints, STRING_*
        // variants emit "#RRGGBB"/"#AARRGGBB" strings (hexString flag).
        SchemaCodecs.registerCompanion(ExtraCodecs.RGB_COLOR_CODEC, new Schema.Color(false, false));
        SchemaCodecs.registerCompanion(ExtraCodecs.ARGB_COLOR_CODEC, new Schema.Color(true, false));
        SchemaCodecs.registerCompanion(ExtraCodecs.STRING_RGB_COLOR, new Schema.Color(false, true));
        SchemaCodecs.registerCompanion(ExtraCodecs.STRING_ARGB_COLOR, new Schema.Color(true, true));
    }
}
