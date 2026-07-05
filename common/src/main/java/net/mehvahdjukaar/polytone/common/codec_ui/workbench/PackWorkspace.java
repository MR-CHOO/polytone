package net.mehvahdjukaar.polytone.common.codec_ui.workbench;

import net.mehvahdjukaar.polytone.common.codec_ui.SchemaEditor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * An opened pack folder. Lenient by design: any readable directory is accepted, because mods
 * load packs from arbitrary locations (mod jars, config subfolders, world datapacks, dev
 * symlinks...). The presence of {@code assets/}, {@code data/} and {@code pack.mcmeta} only
 * refines the detected {@link Kind} — it never rejects the folder.
 *
 * <p>Stateless with respect to the filesystem: directory listings are re-read on every call,
 * so a UI "refresh" is just re-asking. No watcher threads at this layer.</p>
 */
public final class PackWorkspace {

    public enum Kind {
        RESOURCE_PACK("Resource pack"),
        DATA_PACK("Datapack"),
        MIXED("Resource + data pack"),
        UNKNOWN("Folder");

        private final String display;

        Kind(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    private final Path root;
    private final Kind kind;

    private PackWorkspace(Path root, Kind kind) {
        this.root = root;
        this.kind = kind;
    }

    public static PackWorkspace open(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Not a directory: " + root);
        boolean assets = Files.isDirectory(root.resolve("assets"));
        boolean data = Files.isDirectory(root.resolve("data"));
        Kind kind = assets && data ? Kind.MIXED
                : assets ? Kind.RESOURCE_PACK
                : data ? Kind.DATA_PACK
                : Kind.UNKNOWN;
        return new PackWorkspace(root, kind);
    }

    public Path root() {
        return root;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        Path fileName = root.getFileName();
        return fileName != null ? fileName.toString() : root.toString();
    }

    public boolean hasPackMcmeta() {
        return Files.isRegularFile(root.resolve("pack.mcmeta"));
    }

    /**
     * Directory listing for tree display: directories first, then files, case-insensitive
     * alphabetical; hidden (dot-prefixed) entries skipped. IO failures yield an empty list —
     * the tree shows an empty node rather than the whole UI erroring.
     */
    public List<Path> children(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        Path n = p.getFileName();
                        return n == null || !n.toString().startsWith(".");
                    })
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> String.valueOf(p.getFileName()), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Root-relative display path with forward slashes; falls back to the absolute path. */
    public String relativize(Path p) {
        try {
            return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    /**
     * Logical position of a file inside the pack. {@code containerDir} is the directory path
     * with the {@code assets/<ns>/} or {@code data/<ns>/} prefix stripped when present (e.g.
     * {@code "polytone/colormaps"}); for lenient roots without that structure it is simply the
     * root-relative parent directory. {@code side}/{@code namespace} are null when the file
     * does not sit under a recognized {@code assets}/{@code data} tree.
     */
    public record Location(@Nullable SchemaEditor.Side side, @Nullable String namespace, String containerDir) {}

    /** Null if the file is outside this workspace. */
    public @Nullable Location locate(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) return null;
        Path parent = root.relativize(abs).getParent();
        if (parent == null) return new Location(null, null, "");

        List<String> segments = new ArrayList<>();
        for (Path segment : parent) segments.add(segment.toString());

        if (segments.size() >= 2) {
            SchemaEditor.Side side = switch (segments.get(0)) {
                case "assets" -> SchemaEditor.Side.CLIENT_RESOURCES;
                case "data" -> SchemaEditor.Side.SERVER_DATA;
                default -> null;
            };
            if (side != null) {
                return new Location(side, segments.get(1),
                        String.join("/", segments.subList(2, segments.size())));
            }
        }
        return new Location(null, null, String.join("/", segments));
    }
}
