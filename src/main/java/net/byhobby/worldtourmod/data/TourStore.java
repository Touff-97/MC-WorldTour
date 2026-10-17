package net.byhobby.worldtourmod.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TourStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static TourBounds cached = null;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldtourmod").resolve("tour.json");
    }

    public static TourBounds get() {
        if (cached == null) {
            cached = TourBounds.EMPTY;
            if (Files.isRegularFile(file())) {
                try {
                    TourBounds t = GSON.fromJson(Files.readString(file()), TourBounds.class);
                    if (t != null) cached = t;
                } catch (Exception e) {
                    System.err.println("[worldtourmod] Skipping unreadable tour.json: " + e);
                }
            }
        }
        return cached;
    }

    public static void save(TourBounds t) throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), GSON.toJson(t));
        cached = t;
    }
}