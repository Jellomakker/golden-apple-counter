package com.jellomakker.cobwebcounter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CobwebCounterConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("cobwebcounter.json");

    private static CobwebCounterConfig instance;

    public boolean enabled = true;
    public boolean showOnPlayerName = true;
    public boolean includeSelfDisplay = false;
    /** When false, the counter label won't show through blocks (no see-through background). */
    public boolean showBackground = true;

    public static CobwebCounterConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static CobwebCounterConfig load() {
        if (!Files.exists(CONFIG_PATH)) {
            CobwebCounterConfig created = new CobwebCounterConfig();
            created.save();
            return created;
        }

        try {
            String json = Files.readString(CONFIG_PATH);
            CobwebCounterConfig loaded = GSON.fromJson(json, CobwebCounterConfig.class);
            if (loaded == null) {
                loaded = new CobwebCounterConfig();
            }
            return loaded;
        } catch (IOException | JsonParseException ignored) {
            CobwebCounterConfig fallback = new CobwebCounterConfig();
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
