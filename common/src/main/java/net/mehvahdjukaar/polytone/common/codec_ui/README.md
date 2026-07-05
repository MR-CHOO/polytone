# codec_ui — automatic GUI for arbitrary DFU Codecs

Goal: take **any** `Codec<A>` and produce an editable GUI for it, automatically. A companion
registry exists as the escape hatch for codecs that genuinely can't be introspected.

Targets DFU **9.0.19** (MC 1.21.11). Research notes: `research/codecs/findings.md` (written
against DFU 8 / 1.21.1 — tier analysis still applies, version facts don't).

## Package layout & API boundary

```
codec_ui/            PUBLIC API — Schema (ADT), SchemaCodec (entry point), SchemaCodecs
                     (facade + extension registration), SchemaRecord/SchemaRecordBuilder
                     (companion DSLs), SchemaEditor, SPIs (SchemaHandler, EnumerableCodec)
codec_ui/workbench/  UI-framework-agnostic workbench MODEL — PackWorkspace (lenient pack
                     folder), CodecEntry (library entry + file association via containerDir),
                     PackReloader (game reload hook SPI), Workbench (session state).
                     Zero Swing/AWT imports — a future non-Swing backend reuses this as-is.
codec_ui/internal/   machinery — SchemaResolver, mixin tag stores, dispatch enumeration.
                     NOT API. Only the construction mixins may reach in.
codec_ui/swing/      Swing backend. Depends on api + workbench, never on internal/.
                     SwingWorkbench (single-window shell), EditorPanel (per-codec tab),
                     widgets. Custom widgets bind via SwingWidgetDef.bind(codec).
codec_ui/example/    demo/test entries + tool bootstrap (codec library, game reload hooks).
mixins/codec_ui/     construction mixins — part of the internal layer, live outside only
                     because the mixin package is fixed by polytone-common.mixins.json.
```

Dependency direction: `example → swing → workbench → api ← internal ← mixins`. Each package
has a `package-info.java` restating its contract.

## Extending: making an unparseable codec editable

The design intent: **infer as much as possible; hand-curate the rest.** The in-repo curated
list is `internal/CuratedSchemas.java` — one bootstrap class whose entries use only the
public registration API below, each with a comment on WHY inference fails for it. It is the
first place to add vanilla/DFU codecs that resolve wrong or opaque, and it doubles as the
reference example for how external mods register their own weird codecs (they call the same
`SchemaCodecs` methods from their own init).

**For codecs YOU own, don't register anything** — declare codec + schema in one go:
`static final SchemaCodec<X> CODEC = SchemaRecord.create(X.class, i -> i.group(...).apply(i, X::new))`
(drop-in: `SchemaCodec extends Codec`). For alternative-style codecs, state each
alternative ONCE as `SchemaCodecs.alt(label, codec)`:
`SchemaCodecs.withAlternative(alt("reference", A), alt("inline", B))` builds codec + schema
together; `SchemaCodecs.labeled(existingCodec, alt(...), ...)` labels a multi-format codec
you can't rebuild. Schemas resolve lazily at editor-open (never call `.schema()` or
`SchemaCodecs.resolve` at class-init — `SchemaCodec.lazy(codec, supplier)` is the manual
escape hatch). See
`content/colormap/Colormap.java` for the reference port: its 3-layer nested alternatives
render as ONE picker (reference / inline colormap / color / expression / biome compound).
The registration mechanisms below are for codecs you DON'T own (vanilla, other mods) —
plus `Schema.Custom` widget bindings, which stay out of content code.

In priority order (first match wins at resolve time) — all registered via `SchemaCodecs`:

1. **Companion** — `SchemaCodecs.registerCompanion(codec, schema)`: hand-crafted schema for
   one specific codec instance. Always beats everything.
2. **Custom handler** — `SchemaCodecs.registerHandler((codec, resolver) -> ...)`: teach the
   resolver a whole *class* of codecs (your own `Codec` impls, third-party combinators).
   Return null to pass; use the provided `resolver` for inner codecs. Runs before the
   built-in structural tiers, so it also overrides tier-2/3 guesses.
3. **`EnumerableCodec`** — implement on a custom codec whose values are a closed named set
   (e.g. `MapRegistry`): gives an Enum dropdown, and full variant enumeration when the
   codec is a dispatch key.
4. **Dispatch keys** — `SchemaCodecs.registerDispatchKeys(keyType, keys, codecOf, nameOf)`
   for `Codec.dispatch` families whose key type you don't control.
5. **Custom widget** — `MyWidget.DEF.bind(codec)` (Swing backend): bypass schema-driven
   widget selection with a domain editor (see `example/ExampleExpressionWidget`).

## Porting cookbook (for future agents)

**Goal recap:** any codec should open as a real editing GUI. Inference handles most; what it
can't gets either a declaration-site schema (codecs we own) or a curated registration
(codecs we don't). Reference ports to copy from: `content/colormap/Colormap.java`,
`IColorGetter.java`, `common/ColorUtils.java`.

**Porting a codec WE OWN (preferred — declaration site, wire format unchanged):**

1. Record codec: replace `RecordCodecBuilder.create(i -> i.group(...).apply(i, X::new))`
   with `SchemaRecord.create(X.class, i -> i.group(...).apply(i, X::new))` — same shape;
   `i.field(name, codec, getter)`, `i.optional(name, codec, default, getter)`, and the
   `Optional<F>` flavor `i.optional(name, codec, c -> Optional<F>)`. Field type changes
   `Codec<X>` → `SchemaCodec<X>`; callers unaffected (`SchemaCodec extends Codec`).
2. Alternatives: `SchemaCodecs.withAlternative(alt("a", A), alt("b", B))` builds
   `Codec.withAlternative` + labeled picker in one go. If the multi-format codec can't be
   rebuilt (custom try-each classes, reference-or-direct), keep it and wrap with
   `SchemaCodecs.labeled(existingCodec, alt("a", A), ...)` — labels only, wire untouched.
3. Simple override: `SchemaCodec.of(codec, schema)` (e.g. `ColorUtils.COLOR` → `Schema.Color`).

**Rules:**
- NEVER call `.schema()` or `SchemaCodecs.resolve(...)` in a static initializer — schemas
  must resolve at editor-open (wrap/DSL/`alt` are already lazy; `SchemaCodec.lazy` is the
  manual escape hatch).
- Swing widget bindings (`Schema.Custom` + `SwingWidgetDef`) NEVER go in content code —
  they're registered at editor bootstrap (`example/PolytoneSchemas`) so production classes
  stay UI-free.
- MapCodecs registered into dispatch maps (e.g. `ItemPredicate.TYPES.register`) can't be
  ported yet: `SchemaMapCodec` does not extend `MapCodec` (known gap). Their dispatch still
  renders fully via `EnumerableCodec` enumeration.

**Porting a codec we DON'T own (vanilla / other mods):** add an entry to
`internal/CuratedSchemas.register()` using the public API (`registerCompanion` /
`registerHandler` / `registerDispatchKeys`), with a comment on WHY inference fails.
External mods do the same from their own init.

**Client vs server registries (`SchemaEditor.Side`):** datapack registries (biomes, ...)
have two views — client-synced (what resource-pack files, i.e. ALL polytone content, see)
and the server's (what datapack files see). A file's pack type decides its side; the editor
binds the matching registry access for encode/validate ops (fallback outside a world:
`VanillaRegistries.createLookup()`). The launcher's two top-level tabs (Client | Server)
are driven by `CodecRegistry.Entry.side`.

## Architecture

Three layers:

1. **`Schema<A>`** — sealed ADT describing the *edit surface* of a codec (Bool, IntRange,
   Str, ResourceId, Enum, Record, ListOf, MapOf, AnyOf, PairOf, OneOf, …). Escape
   hatches: `Opaque` (raw JSON editor live-validated by the codec) and `Custom` (bound
   widget). UI-backend-agnostic; the Swing backend is in `swing/`.

2. **`SchemaResolver`** — walks a codec graph and produces a `Schema`. Resolution order:
   - **Tier 0**: eager side-channel tag (`SchemaTags`) — manual companions and mixin tags.
   - **Tier 0a/0b/0d**: *lazy* tags (`FieldOfTags`, `RecordFieldTags`, `XmapTags`) written by
     the construction mixins. Lazy = they store the *inner codec*, not a resolved schema, so
     companions registered after MC bootstrap still win at resolve time. Never eagerly
     resolve inside a mixin.
   - **Tier 0.5**: user-registered `SchemaHandler`s (`SchemaCodecs.registerHandler`) —
     class-level handlers for codecs the built-in tiers can't or shouldn't guess.
   - **Tier 1**: identity match on primitive singletons (`Codec.INT`, `STRING`, …).
   - **Tier 2**: `instanceof` on concrete DFU/MC codec classes (+ VarHandles for private
     fields): ListCodec, EitherCodec, XorCodec, PairCodec, UnboundedMapCodec,
     SimpleMapCodec, CompoundListCodec, DispatchedMapCodec, RecursiveCodec,
     MapCodecCodec, OptionalFieldCodec, PairMapCodec, EitherMapCodec, RecursiveMapCodec,
     KeyDispatchCodec, and MC's registry-element codecs: `RegistryFileCodec`
     (id-or-inline → `AnyOf(reference, inline)`), `RegistryFixedCodec` (→ `ResourceId`),
     `HolderSetCodec` (tag-string / single / list). Codecs implementing the
     **`EnumerableCodec`** SPI (e.g. `MapRegistry`) resolve to an `Enum` dropdown of their
     registered names.
   - **Tier 3 (heuristic)**: reflective last resort for unknown hand-rolled codec classes —
     scans instance fields for inner `Codec`/`MapCodec`/`Codec[]` values: one inner →
     inherit (wrapper assumption); a (key, element/value) pair → `MapOf`; several →
     a flat `AnyOf` picker in declaration order ("try each" alternatives assumption).
     Covers reference-or-inline codecs, multi-format unions, etc. A wrong guess is
     overridden by a tier-0 companion.
   - **Fallback**: `Schema.Opaque`.

   `KeyDispatchCodec` variant enumeration order: (0) the dispatch's own key codec, when it
   implements `EnumerableCodec` or resolves to a `Schema.Enum` — each key is fed through the
   dispatch's decoder function, giving fully-resolved variant bodies; (1) `DispatchRegistry`
   hooks; (2) registry-backed key fallback — small registries (≤128 entries) also get real
   bodies via the decoder, large ones stay name-only with opaque bodies. If everything comes
   up empty the dispatch renders as raw JSON instead of a dead empty picker.

   Recursion: a per-resolve `IdentityHashMap` cache; a `Schema.Ref` placeholder is inserted
   before descending and bound to the finished schema on exit, so self-references (e.g.
   `Codec.recursive`, dispatch variants embedding the dispatch) become lazily-expanded
   sub-editors (`RefWidget`) instead of raw JSON.

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
  opaque by nature (tier 3 finds no codec-typed fields in the lambdas); needs a companion.
- Registry-backed dispatches over **large** registries (>128 entries, e.g. Block) keep
  opaque variant bodies; only the key dropdown is populated.
- Recursive self-references resolve to `Schema.Ref` (bound after the outer resolve
  completes) and render as lazily-expanded sub-editors — expand-on-click or on data load.
  Unexpanded required recursive fields surface as codec validation errors on save.
- Tier 3 is a guess: a hand-rolled codec whose two codec fields are *not* alternatives
  (and not a key/value pair) gets a wrong `AnyOf` surface. Override with a companion.
- `xmap`s that genuinely change shape (string ↔ parsed tree) show the on-disk (inner)
  shape — correct JSON, but no structured editor for the runtime form. Override with a
  companion/`SwingWidgetDef.bind` when a domain widget is wanted (see the expression widget).
- Plain-lambda enums via `Codec.stringResolver` are not enumerable → resolve as `Str`.
- `MapCodec.Dependent`, `assumeMapUnsafe`, `unit` — no handlers (rare; fall to Opaque).
- `DispatchedMapCodec` values are opaque (function-typed); keys resolve.
- `SchemaMapCodec` is a plain wrapper, NOT a `MapCodec` subclass — owned MapCodecs that get
  registered into dispatch registries can't be declared schema-carrying yet.

## The workbench (single-window UI)

`example/ExamplesLauncher.open()` boots `swing/SwingWorkbench` — ONE frame, everything in it:

- **Toolbar**: Open Pack… (lenient folder picker — ANY directory opens, since mods load packs
  from odd places; `assets/`/`data/`/`pack.mcmeta` only refine the detected kind), plus
  Reload Resources / Reload Data buttons driven by the `PackReloader` hook
  (`example/GameReloadHooks` binds them to `Minecraft.reloadResourcePacks()` and the
  integrated server's `reloadResources`; disabled when no game / no server). Reloading is
  what makes freshly saved files referenceable by registry pickers and dispatch enumeration.
- **Sidebar**: *Files* — lazy file tree of the opened pack (double-click routes a file:
  codec-associated JSON → `EditorPanel`, other text → syntax-highlighted `TextEditorPanel`,
  images → preview tab). *Codecs* — the searchable/side-filterable codec library from
  `example/CodecRegistry` (now `workbench/CodecEntry`s).
- **Editor tabs**: closable (Ctrl+W), dirty-dot titles, unsaved-changes confirm on close.
  Each `EditorPanel` is form (left) | live JSON preview (right): read-only RSyntaxTextArea +
  a wrapping status line that re-validates through the codec (registry-aware ops) whenever
  the produced JSON changes. Optional record fields are omitted from output while they equal
  their declared default. File-bound tabs save in place (Ctrl+S); unbound tabs ask once,
  then bind. Loading a file that fails codec validation still opens — the error is shown,
  never a refusal.

File→codec association: `CodecEntry.containerDir` (e.g. `polytone/colormaps`) is matched
against the file's in-pack container (`assets/<ns>/…` / `data/<ns>/…` prefix stripped,
lenient about extra levels). Add a containerDir to an entry and its files become
double-click-editable. `SwingSchemaEditor.open(...)` (the `SchemaEditor` API) now lands as
a tab in the same window — no separate frames anywhere.

## Testing

Run `ExamplesLauncher.open()` from the IDE with the mod classpath; mixins must be active
for the construction tags to exist (run via a client run config / dev launch, not plain main,
when testing mixin-dependent paths). The codec library exercises the resolver on
progressively nastier codecs, including migrated real Polytone codecs and vanilla ones
(`example/VanillaCodecs`). A convenient end-to-end check: open `resourcepacks/sunbathing/`
(in-repo dev pack) as the workspace, edit a colormap, save, hit Reload Resources in-game.
