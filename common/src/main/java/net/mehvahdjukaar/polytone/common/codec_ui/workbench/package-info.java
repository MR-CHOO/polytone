/**
 * UI-framework-agnostic workbench model: the state and behavior of the pack-editing tool
 * WITHOUT any widget toolkit dependency. Swing (or any future backend) renders this model;
 * nothing in this package may import Swing/AWT classes.
 *
 * <ul>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.workbench.Workbench} — one editing
 *       session: the codec library plus the currently opened pack folder.</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackWorkspace} — a pack
 *       folder on disk. Deliberately lenient: ANY directory opens (mods load packs from odd
 *       places); {@code assets/}/{@code data/}/{@code pack.mcmeta} only refine the detected kind.</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.workbench.CodecEntry} — one editable
 *       content type. Its optional {@code containerDir} doubles as the file-association rule:
 *       a JSON file whose in-pack container matches opens with that entry's codec.</li>
 *   <li>{@link net.mehvahdjukaar.polytone.common.codec_ui.workbench.PackReloader} — SPI hook
 *       into the running game ("reload resources"/"reload data"). Installed by whoever embeds
 *       the workbench (see {@code example/GameReloadHooks}); defaults to an unavailable no-op
 *       so the tool works from a bare main.</li>
 * </ul>
 *
 * Depends on the codec_ui public API only (for {@code SchemaCodec} and {@code SchemaEditor.Side}).
 */
package net.mehvahdjukaar.polytone.common.codec_ui.workbench;
