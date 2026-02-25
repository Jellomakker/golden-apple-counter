package com.jellomakker.goldenapplecounter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GoldenAppleCounterConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("goldenapplecounter.json");

    private static GoldenAppleCounterConfig instance;

    public boolean enabled = true;
    public boolean countNormalGoldenApple = true;
    public boolean countEnchantedGoldenApple = true;
    public boolean showOnPlayerName = true;
    public boolean includeSelfDisplay = false;
    /** When false, the counter label won't show through blocks (no see-through background). */
    public boolean showBackground = true;

    public static GoldenAppleCounterConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static GoldenAppleCounterConfig load() {
        if (!Files.exists(CONFIG_PATH)) {
            GoldenAppleCounterConfig created = new GoldenAppleCounterConfig();
            created.save();
            return created;
        }

        try {
            String json = Files.readString(CONFIG_PATH);
            GoldenAppleCounterConfig loaded = GSON.fromJson(json, GoldenAppleCounterConfig.class);
            if (loaded == null) {
                loaded = new GoldenAppleCounterConfig();
            }
            return loaded;
        } catch (IOException | JsonParseException ignored) {
            GoldenAppleCounterConfig fallback = new GoldenAppleCounterConfig();
            fallback.save();
            return fallback;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
