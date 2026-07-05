package net.mehvahdjukaar.polytone.common.codec_ui;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.mehvahdjukaar.polytone.common.codec_ui.internal.SchemaResolver;

import java.util.function.Supplier;

/**
 * Pairing of a {@link Codec} with its {@link Schema}. Since this IS a {@code Codec}, an
 * existing {@code static final Codec<X> CODEC = ...} declaration can be upgraded in place
 * to {@code static final SchemaCodec<X> CODEC = SchemaRecord.create(...)} (or {@code of}/
 * {@code lazy}) with no change for any code that uses it.
 */
public sealed interface SchemaCodec<A> extends Codec<A> {

    Schema<A> schema();

    /**
     * Wrap any raw codec; the schema is derived LAZILY via {@link SchemaResolver} on each
     * {@link #schema()} call, so wrapping at class-init time is safe — companions, handlers
     * and registry content registered later are still reflected when the editor opens.
     */
    @SuppressWarnings("unchecked")
    static <A> SchemaCodec<A> wrap(Codec<A> codec) {
        if (codec instanceof SchemaCodec<?> sc) return (SchemaCodec<A>) sc;
        return new LazySchemaCodec<>(codec, () -> SchemaResolver.get().resolve(codec));
    }

    /** Wrap a raw codec with an explicit schema (caller-provided override). */
    static <A> SchemaCodec<A> of(Codec<A> codec, Schema<A> schema) {
        return new SimpleSchemaCodec<>(codec, schema);
    }

    /**
     * Wrap a raw codec with a lazily computed schema — the supplier runs fresh on each
     * {@link #schema()} call. Use when the schema references other codecs' schemas (e.g.
     * a labeled {@code Schema.anyOf} over alternatives) from a static initializer: resolution
     * is deferred to editor-open time instead of being frozen at class load.
     */
    static <A> SchemaCodec<A> lazy(Codec<A> codec, Supplier<Schema<A>> schema) {
        return new LazySchemaCodec<>(codec, schema);
    }

    record SimpleSchemaCodec<A>(Codec<A> codec, Schema<A> schema) implements SchemaCodec<A> {
        @Override
        public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
            return codec.decode(ops, input);
        }

        @Override
        public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
            return codec.encode(input, ops, prefix);
        }
    }

    record LazySchemaCodec<A>(Codec<A> codec, Supplier<Schema<A>> schemaSupplier) implements SchemaCodec<A> {
        @Override
        public Schema<A> schema() {
            return schemaSupplier.get();
        }

        @Override
        public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
            return codec.decode(ops, input);
        }

        @Override
        public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
            return codec.encode(input, ops, prefix);
        }
    }
}
