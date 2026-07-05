package net.mehvahdjukaar.polytone.common.codec_ui;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.mehvahdjukaar.polytone.common.codec_ui.internal.SchemaResolver;
import net.mehvahdjukaar.polytone.common.codec_ui.internal.SchemaTags;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Facade of the codec_ui public API: combinators on {@link SchemaCodec} that preserve the
 * schema alongside the codec, plus every extension-point registration.
 *
 * <h2>Extension points (in resolution priority order)</h2>
 * <ol>
 *   <li>{@link #registerCompanion(Codec, Schema)} — hand-crafted schema for ONE specific
 *       codec instance. Always wins.</li>
 *   <li>{@link #registerHandler(SchemaHandler)} — structural handler for a whole CLASS of
 *       codecs (your own {@code Codec} implementations, third-party combinators). Runs
 *       before the built-in structural tiers.</li>
 *   <li>{@link EnumerableCodec} — implement directly on a custom codec whose value set is
 *       a closed, named collection (custom registries). Gives dropdowns and, when used as
 *       a dispatch key, full variant enumeration.</li>
 *   <li>{@link #registerDispatchKeys} — enumerate the keys of a {@code Codec.dispatch}
 *       whose key type you can't make {@link EnumerableCodec} (vanilla/3rd-party types).</li>
 *   <li>{@code SwingWidgetDef#bind} (in the {@code swing} backend package) — bind a
 *       domain-specific editor widget to a codec, bypassing schema-driven widget selection
 *       entirely. Backend-specific, hence not on this facade.</li>
 * </ol>
 */
public final class SchemaCodecs {

    private SchemaCodecs() {}

    /**
     * Manually register a schema for a codec we can't (or don't want to) auto-introspect.
     * Use this for codecs that wrap their internals via {@code Codec.of(enc, dec)} or similar
     * opaque paths — e.g. {@code DimensionType.DIRECT_CODEC} via {@code ExtraCodecs.catchDecoderException}.
     *
     * <p>After registration, any call to {@link SchemaResolver#resolve(Codec)} on this codec
     * — including when it appears as a field inside another codec — finds the hand-crafted
     * schema first (tier 0).</p>
     *
     * <p>Call once at mod init or class-load time; subsequent registrations of the same codec
     * overwrite.</p>
     */
    public static <A> void registerCompanion(Codec<A> codec, Schema<A> schema) {
        SchemaTags.tag(codec, schema);
    }

    /** Same as {@link #registerCompanion(Codec, Schema)} for a {@link MapCodec}. */
    public static <A> void registerCompanion(MapCodec<A> codec, Schema<A> schema) {
        SchemaTags.tag(codec, schema);
    }

    /**
     * Register a {@link SchemaHandler} that teaches the resolver how to introspect a whole
     * class of codecs. See {@link SchemaHandler} for contract, ordering and an example.
     */
    public static void registerHandler(SchemaHandler handler) {
        SchemaResolver.registerHandler(handler);
    }

    /**
     * Register the key set of a {@code Codec.dispatch(...)} family whose key type can't
     * implement {@link EnumerableCodec} (vanilla or third-party K). The resolver applies a
     * dispatch's decoder to each supplied key; keys the dispatch accepts become entries in
     * the variant picker, with fully resolved bodies.
     *
     * @param keyType the dispatch key class K (used to index hooks; one hook per K class)
     * @param keys    lazy supplier of all known keys — re-queried at each editor open, so
     *                late registrations are picked up
     * @param codecOf variant codec lookup, used only as a fallback when the dispatch's own
     *                decoder can't be read
     * @param nameOf  display/serialized name of a key (what the "type" field contains)
     */
    public static <K> void registerDispatchKeys(Class<K> keyType,
                                                java.util.function.Supplier<List<K>> keys,
                                                Function<K, MapCodec<?>> codecOf,
                                                Function<K, String> nameOf) {
        net.mehvahdjukaar.polytone.common.codec_ui.internal.DispatchRegistry.register(keyType, keys, codecOf, nameOf);
    }

    @SuppressWarnings("unchecked")
    private static <A, B> Schema<B> castSchema(Schema<A> schema) {
        return (Schema<B>) schema;
    }

    /** Convenience: a {@link SchemaCodec} that pairs an integer codec with a {@link Schema.Color} (RGB). */
    public static SchemaCodec<Integer> colorRgb(com.mojang.serialization.Codec<Integer> codec) {
        return SchemaCodec.of(codec, new Schema.Color(false));
    }

    /** Convenience: same as {@link #colorRgb} but for ARGB (with alpha channel). */
    public static SchemaCodec<Integer> colorArgb(com.mojang.serialization.Codec<Integer> codec) {
        return SchemaCodec.of(codec, new Schema.Color(true));
    }

    public static <A, B> SchemaCodec<B> xmap(SchemaCodec<A> inner, Function<A, B> to, Function<B, A> from) {
        Codec<B> codec = inner.xmap(to, from);
        return SchemaCodec.of(codec, castSchema(inner.schema()));
    }

    public static <A, B> SchemaCodec<B> xmapWithSchema(SchemaCodec<A> inner, Function<A, B> to, Function<B, A> from, Schema<B> schema) {
        Codec<B> codec = inner.xmap(to, from);
        return SchemaCodec.of(codec, schema);
    }

    public static <E> SchemaCodec<List<E>> list(SchemaCodec<E> elementCodec) {
        return list(elementCodec, 0, Integer.MAX_VALUE);
    }

    public static <E> SchemaCodec<List<E>> list(SchemaCodec<E> elementCodec, int minSize, int maxSize) {
        Codec<List<E>> codec;
        if (minSize == 0 && maxSize == Integer.MAX_VALUE) {
            codec = elementCodec.listOf();
        } else {
            codec = elementCodec.listOf(minSize, maxSize);
        }
        Schema<List<E>> schema = new Schema.ListOf<>(elementCodec.schema(), minSize, maxSize);
        return SchemaCodec.of(codec, schema);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <A> SchemaMapCodec<A> fieldOf(String name, SchemaCodec<A> codec) {
        MapCodec<A> mapCodec = codec.fieldOf(name);
        Schema.Field<Object, A> field = new Schema.Field<>(name, codec.schema(), false, null);
        List<Schema.Field<Object, ?>> fields = List.of(field);
        Schema<A> schema = (Schema<A>) (Schema) new Schema.Record<>(Object.class, fields);
        return SchemaMapCodec.of(mapCodec, schema);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <A> SchemaMapCodec<Optional<A>> optionalFieldOf(String name, SchemaCodec<A> codec) {
        MapCodec<Optional<A>> mapCodec = codec.optionalFieldOf(name);
        Schema.Field<Object, A> field = new Schema.Field<>(name, codec.schema(), true, null);
        List<Schema.Field<Object, ?>> fields = List.of(field);
        Schema<Optional<A>> schema = (Schema<Optional<A>>) (Schema) new Schema.Record<>(Object.class, fields);
        return SchemaMapCodec.of(mapCodec, schema);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <A> SchemaMapCodec<A> optionalFieldOf(String name, SchemaCodec<A> codec, A defaultValue) {
        MapCodec<A> mapCodec = codec.optionalFieldOf(name, defaultValue);
        Schema.Field<Object, A> field = new Schema.Field<>(name, codec.schema(), true, defaultValue);
        List<Schema.Field<Object, ?>> fields = List.of(field);
        Schema<A> schema = (Schema<A>) (Schema) new Schema.Record<>(Object.class, fields);
        return SchemaMapCodec.of(mapCodec, schema);
    }

    public static <L, R> SchemaCodec<Either<L, R>> either(SchemaCodec<L> left, SchemaCodec<R> right) {
        Codec<Either<L, R>> codec = Codec.either(left, right);
        Schema<Either<L, R>> schema = Schema.anyOf(
                Schema.option(left.schema()), Schema.option(right.schema()));
        return SchemaCodec.of(codec, schema);
    }

    public static <T> SchemaCodec<T> registryEntry(ResourceKey<? extends Registry<T>> registryKey, Codec<T> nameCodec) {
        Schema<T> schema = castSchema(new Schema.ResourceId(registryKey));
        return SchemaCodec.of(nameCodec, schema);
    }

    /**
     * Sum-type dispatch: serialize {@code A} as a tagged map where {@code typeField} selects a variant.
     * Each variant is a {@link SchemaMapCodec} keyed by the same string as produced by {@code typeFn}.
     */
    public static <A> SchemaCodec<A> dispatch(
            String typeField,
            Function<A, String> typeFn,
            Map<String, SchemaMapCodec<? extends A>> variants
    ) {
        Codec<A> codec = Codec.STRING.dispatch(
                typeField,
                typeFn,
                key -> variants.get(key).mapCodec()
        );
        Map<String, Schema<? extends A>> variantSchemas = new LinkedHashMap<>();
        variants.forEach((k, v) -> variantSchemas.put(k, v.schema()));
        Schema<A> schema = new Schema.OneOf<>(typeField, variantSchemas);
        return SchemaCodec.of(codec, schema);
    }
}
