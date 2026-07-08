# codec_ui — Polytone's editor UI over CodecUI

Polytone's Swing editor that takes a `SchemaCodec` (any codec turned into a `Schema` by the
CodecUI engine) and renders an editable GUI for it.

**The declarative core AND the inference engine now live in the standalone `codecui` library**
(jar-in-jar): `Schema`, `SchemaCodec` (`SchemaCodec.wrap(anyCodec)` runs the engine),
`SchemaCodecs` (primitives, combinators, and `registerCompanion`/`registerHandler`/
`registerDispatchKeys`), `SchemaRecord`/`SchemaRecordBuilder`, the resolver, and the
construction mixins. For how inference works (resolver tiers, mixins, the RCB arity trap,
known gaps) see **`codecui/ARCHITECTURE.md`**. This package is only the app-side UI.

## Package layout

```
codec_ui/            SchemaEditor (backend-agnostic editor handle).
codec_ui/workbench/  UI-framework-agnostic workbench MODEL — PackWorkspace (lenient pack
                     folder), CodecEntry (library entry + file association via containerDir),
                     PackReloader (game reload hook SPI), Workbench (session state).
                     Zero Swing/AWT imports — a future non-Swing backend reuses this as-is.
codec_ui/swing/      Swing backend. Depends on the codecui API + workbench.
                     SwingWorkbench (single-window shell), EditorPanel (per-codec tab),
                     widgets. Custom widgets bind via SwingWidgetDef.bind(codec).
```

Production editor assembly lives OUTSIDE this package: `net.mehvahdjukaar.polytone.editor`
(`PolytoneEditor` entry point, `PolytoneSchemas` widget/companion bootstrap, `GameReloadHooks`).

## Porting Polytone content to schema-carrying codecs

Reference ports to copy from: `content/colormap/Colormap.java`, `IColorGetter.java`,
`common/ColorUtils.java`.

**A codec WE OWN (preferred — declaration site, wire format unchanged):**

1. Record codec: replace `RecordCodecBuilder.create(i -> i.group(...).apply(i, X::new))` with
   `SchemaRecord.create(X.class, i -> i.group(...).apply(i, X::new))` — same shape;
   `i.field(name, codec, getter)`, `i.optional(name, codec, default, getter)`, and the
   `Optional<F>` flavor `i.optional(name, codec, c -> Optional<F>)`. Field type changes
   `Codec<X>` → `SchemaCodec<X>`; callers unaffected (`SchemaCodec extends Codec`).
2. Alternatives: `SchemaCodecs.withAlternative(alt("a", A), alt("b", B))` builds
   `Codec.withAlternative` + labeled picker in one go (`alt` = `SchemaCodecs.alt`). If the
   multi-format codec can't be rebuilt, keep it and wrap with
   `SchemaCodecs.labeled(existingCodec, alt("a", A), ...)` — labels only, wire untouched.
3. Simple override: `SchemaCodec.of(codec, schema)` (e.g. `ColorUtils.COLOR` → `Schema.Color`).

**Rules:**
- NEVER call `.schema()` or `SchemaCodecs.resolve(...)` in a static initializer — schemas must
  resolve at editor-open (wrap/DSL/`alt` are already lazy; `SchemaCodec.lazy` is the manual
  escape hatch).
- Swing widget bindings (`Schema.Custom` + `SwingWidgetDef`) NEVER go in content code — they're
  registered at editor bootstrap (`polytone.editor.PolytoneSchemas`) so production classes stay
  UI-free. See `swing/ExpressionWidget` — the syntax-highlighted expression editor with live
  compile-check and variable chips, configured per expression dialect in `PolytoneSchemas`.

**A codec we DON'T own (vanilla / other mods):** add an entry to codecui's
`internal/CuratedSchemas.register()` (or register from your own init) via the public API
(`registerCompanion` / `registerHandler` / `registerDispatchKeys`) — see `codecui/ARCHITECTURE.md`.

## Client vs server registries (`SchemaEditor.Side`)

Datapack registries (biomes, …) have two views — client-synced (what resource-pack files, i.e.
ALL polytone content, see) and the server's (what datapack files see). A file's pack type
decides its side; the editor binds the matching registry access for encode/validate ops
(fallback outside a world: `VanillaRegistries.createLookup()`). In the workbench,
`CodecEntry.side` drives the codec library's Client/Server filter and which base dir
(`assets`/`data`) New Content targets.

## The workbench (single-window UI)

`polytone.editor.PolytoneEditor.open()` (called by the platform classes) boots
`swing/SwingWorkbench` — ONE frame, everything in it:

- **Toolbar**: Open Pack… (lenient folder picker — ANY directory opens; `assets/`/`data/`/
  `pack.mcmeta` only refine the detected kind; starts at the running game's `resourcepacks`
  folder via the `workbench/GamePaths` provider hook, falling back to the last-used directory),
  plus Reload Resources / Reload Data buttons driven by the `PackReloader` hook
  (`polytone.editor.GameReloadHooks` binds them to `Minecraft.reloadResourcePacks()` and the
  integrated server's `reloadResources`; disabled when no game / no server). Reloading is what
  makes freshly saved files referenceable by registry pickers and dispatch enumeration.
- **Sidebar**: *Files* — lazy file tree (double-click routes: codec-associated JSON →
  `EditorPanel`, other text → `TextEditorPanel`, images → preview). *Codecs* — the
  searchable/side-filterable codec library from `PolytoneEditor`.
- **Editor tabs**: closable (Ctrl+W), dirty-dot titles, unsaved-changes confirm on close. Each
  `EditorPanel` is form (left) | live JSON preview (right): read-only RSyntaxTextArea + a
  status line that re-validates through the codec (registry-aware ops) whenever the JSON
  changes. Optional record fields are omitted from output while they equal their default.
  File-bound tabs save in place (Ctrl+S); unbound tabs ask once, then bind. A file that fails
  codec validation still opens — the error is shown, never a refusal.

File→codec association: `CodecEntry.containerDir` (e.g. `polytone/colormaps`) is matched against
the file's in-pack container. `SwingSchemaEditor.open(...)` (the `SchemaEditor` API) lands as a
tab in the same window.

**New Content flow** (toolbar, enabled when a pack is open): pick a concept (any entry with a
containerDir), pick/type a namespace, type a name — the dialog live-previews and computes the
ONE valid location itself (`PackWorkspace.fileFor` → `assets|data/<ns>/<container>/<name>.json`).
The tab opens bound but unwritten; file + folders are created on first save.

## Testing

Run `PolytoneEditor.open()` from the IDE with the mod classpath; the codecui construction
mixins must be active for the tags to exist (run via a client run config / dev launch). For
pure UI-structure work there is `polytone.editor.UiPreviewLauncher.main()` — a bare-JVM launch
with no game: reload buttons disabled, registry codecs validate with errors, RCB records
resolve opaque (no mixins). The Swing shell logs through `swing/UiLog` so a failed `Polytone`
class-init can't take the preview down. End-to-end check: open `resourcepacks/sunbathing/`
(in-repo dev pack) as the workspace, edit a colormap, save, hit Reload Resources in-game.
