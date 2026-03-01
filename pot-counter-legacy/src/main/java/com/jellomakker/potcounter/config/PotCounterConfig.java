package com.jellomakker.potcounter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
    public boolean includeSelfDisplay = true;
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
            // Parse manually so missing fields keep their Java defaults (true),
            // instead of GSON silently setting them to false.
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            PotCounterConfig config = new PotCounterConfig();
            if (obj.has("enabled"))           config.enabled           = obj.get("enabled").getAsBoolean();
            if (obj.has("showOnPlayerName"))  config.showOnPlayerName  = obj.get("showOnPlayerName").getAsBoolean();
            if (obj.has("includeSelfDisplay"))config.includeSelfDisplay= obj.get("includeSelfDisplay").getAsBoolean();
            if (obj.has("showBackground"))    config.showBackground    = obj.get("showBackground").getAsBoolean();
            config.save(); // write back so any new fields appear in the file
            return config;
        } catch (IOException | JsonParseException | IllegalStateException ignored) {
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
