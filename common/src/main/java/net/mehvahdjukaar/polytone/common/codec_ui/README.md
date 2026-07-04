# codec_ui — automatic GUI for arbitrary DFU Codecs

Goal: take **any** `Codec<A>` and produce an editable GUI for it, automatically. A companion
registry exists as the escape hatch for codecs that genuinely can't be introspected.

Targets DFU **9.0.19** (MC 1.21.11). Research notes: `research/codecs/findings.md` (written
against DFU 8 / 1.21.1 — tier analysis still applies, version facts don't).

## Architecture

Three layers:

1. **`Schema<A>`** — sealed ADT describing the *edit surface* of a codec (Bool, IntRange,
   Str, ResourceId, Enum, Record, ListOf, MapOf, EitherOf, PairOf, OneOf, …). Escape
   hatches: `Opaque` (raw JSON editor live-validated by the codec) and `Custom` (bound
   widget). UI-backend-agnostic; the Swing backend is in `swing/`.

2. **`SchemaResolver`** — walks a codec graph and produces a `Schema`. Resolution order:
   - **Tier 0**: eager side-channel tag (`SchemaTags`) — manual companions and mixin tags.
   - **Tier 0a/0b/0d**: *lazy* tags (`FieldOfTags`, `RecordFieldTags`, `XmapTags`) written by
     the construction mixins. Lazy = they store the *inner codec*, not a resolved schema, so
     companions registered after MC bootstrap still win at resolve time. Never eagerly
     resolve inside a mixin.
   - **Tier 1**: identity match on primitive singletons (`Codec.INT`, `STRING`, …).
   - **Tier 2**: `instanceof` on concrete DFU codec classes (+ VarHandles for private
     fields): ListCodec, EitherCodec, XorCodec, PairCodec, UnboundedMapCodec,
     SimpleMapCodec, CompoundListCodec, DispatchedMapCodec, RecursiveCodec,
     MapCodecCodec, OptionalFieldCodec, PairMapCodec, EitherMapCodec, RecursiveMapCodec,
     KeyDispatchCodec (variant enumeration via `DispatchRegistry` hooks or registry-backed
     key codecs).
   - **Fallback**: `Schema.Opaque`.

   Recursion: a per-resolve `IdentityHashMap` cache; an `Opaque` placeholder is inserted
   before descending, so self-references (e.g. `Codec.recursive`) terminate and render as
   raw-JSON sub-editors.

3. **Construction mixins** (`mixins/codec_ui/`) — because `xmap`/`RecordCodecBuilder`
   capture their inner codecs in lambdas, the only general way to see them is to record
   side-channel tags (weak identity maps) as the codecs are *constructed*:
   - `CodecXmapMixin` — all shape-preserving `Codec` combinators (`xmap`, `flatXmap`,
     `comapFlatMap`, `flatComapMap`, `validate`, `mapResult`, `orElse*`, `stable`, …) tag
     wrapper→inner; `fieldOf`/`optionalFieldOf`/`lenientOptionalFieldOf` tag single-field
     records; static `intRange`/`floatRange`/`doubleRange`/`string(min,max)` tag exact bounds.
   - `MapCodecXmapMixin` — same for `MapCodec.xmap/flatXmap/validate`.
   - `RecordCodecBuilderMixin` + `RecordCodecBuilderInstanceMixin` — capture per-field
     (name, codec) tags on RCB construction and transfer them to the built `MapCodec`.
   - `RegistryByNameCodecMixin` — `Registry.byNameCodec()` → `Schema.ResourceId(registryKey)`.
   - `StringRepresentableCodecMixin` — every `StringRepresentable` codec (all MC enum
     codecs) → `Schema.Enum` dropdown.

## The RecordCodecBuilder arity trap (fixed 2026-07)

`Instance` only overrides `ap2/ap3/ap4` (plus `map`/`lift1`). Other arities use the
`Applicative` interface **defaults**:

- `apN` for N≥5 decomposes into `ap2/ap3/ap4` chains, but each default *starts* with
  `this.map(curryK, func)` — a new builder. Without tag propagation through `Instance.map`,
  every record with **more than 4 fields** silently lost the fields captured before the map
  (a 9-field record showed only fields 5–9).
- 1-field records go `Products.P1.apply → Applicative.ap → lift1(func).apply(t1)`,
  bypassing apN entirely — they were fully opaque.

Fix: `RecordCodecBuilderInstanceMixin` propagates tags through `map` (copy) and `lift1`
(wrap the returned function, concat func+arg tags). With those two, all arities 1–16 work.

## Known remaining gaps

- `RecordCodecBuilder.Instance.dependent(...)` and hand-rolled `Codec.of(enc, dec)` —
  opaque by nature; needs a companion.
- `KeyDispatchCodec` variant **bodies** from registry-backed dispatches stay `Opaque`
  (resolving 1000+ per-entry codecs is impractical); only the key dropdown is populated.
  Non-registry dispatches need a `DispatchRegistry` hook (see `VanillaDispatches`).
- `xmap`s that genuinely change shape (string ↔ parsed tree) show the on-disk (inner)
  shape — correct JSON, but no structured editor for the runtime form. Override with a
  companion/`withWidget` when a domain widget is wanted (see the expression widget).
- Plain-lambda enums via `Codec.stringResolver` are not enumerable → resolve as `Str`.
- `MapCodec.Dependent`, `assumeMapUnsafe`, `unit` — no handlers (rare; fall to Opaque).
- `DispatchedMapCodec` values are opaque (function-typed); keys resolve.

## Testing

`example/ExamplesLauncher` is a standalone Swing app (FlatLaf) exercising the resolver on
progressively nastier codecs, including migrated real Polytone codecs and vanilla ones
(`example/VanillaCodecs`). Run it from the IDE with the mod classpath; mixins must be active
for the construction tags to exist (run via a client run config / dev launch, not plain main,
when testing mixin-dependent paths).
