package com.jellomakker.potcounter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PotCounterConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("potcounter.json");

    private static PotCounterConfig instance;

    public boolean enabled = true;
    public boolean showOnPlayerName = true;
    public boolean includeSelfDisplay = false;
    public boolean showBackground = true;

    public static PotCounterConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static PotCounterConfig load() {
        if (!Files.exists(CONFIG_PATH)) {
            PotCounterConfig created = new PotCounterConfig();
            created.save();
            return created;
        }

        try {
            String json = Files.readString(CONFIG_PATH);
            PotCounterConfig loaded = GSON.fromJson(json, PotCounterConfig.class);
            if (loaded == null) {
                loaded = new PotCounterConfig();
            }
            return loaded;
        } catch (IOException | JsonParseException ignored) {
            PotCounterConfig fallback = new PotCounterConfig();
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
