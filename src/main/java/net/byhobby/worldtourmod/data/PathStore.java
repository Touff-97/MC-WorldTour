package net.byhobby.worldtourmod.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
                out.add(GSON.fromJson(Files.readString(f), type));
            }
        }
        return out;
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
}