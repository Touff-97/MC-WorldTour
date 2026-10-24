package net.byhobby.worldtourmod.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class PathStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path root()           { return FabricLoader.getInstance().getConfigDir().resolve("worldtourmod"); }
    private static Path pathsDir()       { return root().resolve("paths"); }
    private static Path transitionsDir() { return root().resolve("transitions"); }

    public static void save(PathData p) throws IOException {
        Files.createDirectories(pathsDir());
        Files.writeString(pathsDir().resolve(p.id() + ".json"), GSON.toJson(p));
    }

    public static void save(TransitionData t) throws IOException {
        Files.createDirectories(transitionsDir());
        Files.writeString(transitionsDir().resolve(t.id + ".json"), GSON.toJson(t));
    }

    public static List<PathData> loadPaths() throws IOException {
        return loadAll(pathsDir(), PathData.class);
    }

    public static List<TransitionData> loadTransitions() throws IOException {
        return loadAll(transitionsDir(), TransitionData.class);
    }

    private static <T> List<T> loadAll(Path dir, Class<T> type) throws IOException {
        List<T> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    T value = GSON.fromJson(Files.readString(f), type);
                    if (value != null) out.add(value);
                } catch (Exception e) {
                    System.err.println("[worldtourmod] Skipping unreadable file " + f + ": " + e);
                }
            }
        }
        return out;
    }

    public static void delete(PathData p) throws IOException {
        Files.deleteIfExists(pathsDir().resolve(p.id() + ".json"));
    }

    public static void delete(TransitionData t) throws IOException {
        Files.deleteIfExists(transitionsDir().resolve(t.id + ".json"));
    }

    /**
     * Re-checks every transition against every path and updates links/active flags.
     * Returns the transitions that just became active.
     */
    public static List<TransitionData> relink() throws IOException {
        List<PathData> paths = loadPaths();
        List<TransitionData> newlyActive = new ArrayList<>();

        for (TransitionData t : loadTransitions()) {
            String fromPath = null, toPath = null;
            for (PathData p : paths) {
                if (p.points().isEmpty()) continue;
                if (fromPath == null && p.last().equals(t.from)) fromPath = p.id();
                if (toPath == null && p.first().equals(t.to)) toPath = p.id();
            }
            boolean active = fromPath != null && toPath != null;

            boolean changed = !Objects.equals(fromPath, t.fromPath)
                    || !Objects.equals(toPath, t.toPath)
                    || active != t.active;

            if (active && !t.active) newlyActive.add(t);

            if (changed) {
                t.fromPath = fromPath;
                t.toPath = toPath;
                t.active = active;
                save(t);
            }
        }
        return newlyActive;
    }

    /** Every block used by saved paths in the given dimension. */
    public static Set<BlockPos> occupied(String dim) throws IOException {
        Set<BlockPos> out = new HashSet<>();
        for (PathData p : loadPaths()) {
            if (p.points() == null || p.points().isEmpty() || !p.first().dim().equals(dim)) continue;
            out.addAll(blocksOf(p));
        }
        return out;
    }

    public static List<BlockPos> blocksOf(PathData p) {
        List<BlockPos> out = new ArrayList<>();
        if (p.blocks() != null) {
            for (int[] c : p.blocks()) out.add(new BlockPos(c[0], c[1], c[2]));
        } else { // file without a stored route: nodes only
            for (Pos pt : p.points()) out.add(new BlockPos(pt.x(), pt.y(), pt.z()));
        }
        return out;
    }
}