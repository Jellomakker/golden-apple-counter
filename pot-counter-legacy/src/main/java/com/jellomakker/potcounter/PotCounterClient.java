package com.jellomakker.potcounter;

import com.jellomakker.potcounter.config.PotCounterConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PotCounterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("potcounter");
    public static final String MOD_ID = "potcounter";

    public static final Identifier POT_FONT = Identifier.of(MOD_ID, "pot");
    public static final String POT_ICON = "\uE200";

    private static final Map<UUID, Integer> COUNTS = new ConcurrentHashMap<>();

    /** Entity network ID → UUID, populated each tick for the renderer mixin. */
    private static final Map<Integer, UUID> ID_TO_UUID = new ConcurrentHashMap<>();

    /**
     * Track potion entity IDs we have already counted so we don't
     * double-count a potion that exists for multiple ticks.
     */
    private static final Set<Integer> COUNTED_POTIONS = ConcurrentHashMap.newKeySet();

    private static KeyBinding resetKeybind;

    @Override
    public void onInitializeClient() {
        PotCounterConfig.get();

        resetKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.potcounter.reset",
                GLFW.GLFW_KEY_UNKNOWN,
                "category.potcounter"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            COUNTS.clear();
            ID_TO_UUID.clear();
            COUNTED_POTIONS.clear();
        });

        LOGGER.info("[PotCounter] Initialized (rendering via EntityRendererMixin)");
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null) return;

        // Check reset keybind
        if (resetKeybind != null) {
            while (resetKeybind.wasPressed()) {
                clearAll();
            }
        }

        PotCounterConfig config = PotCounterConfig.get();

        // Maintain entity-id → UUID map for renderer mixin
        Set<UUID> active = new HashSet<>();
        for (PlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();
            active.add(uuid);
            ID_TO_UUID.put(player.getId(), uuid);
        }

        COUNTS.keySet().retainAll(active);
        ID_TO_UUID.values().retainAll(active);
    }

    private static boolean isInstantHealthTwo(PotionEntity potionEntity) {
        try {
            var stack = potionEntity.getStack();
            if (stack == null || stack.isEmpty()) return false;

            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) return false;

            // Check if potion matches STRONG_HEALING (Instant Health II)
            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                        && effect.getAmplifier() >= 1) {
                    return true;
                }
            }

            // Also check the base potion entry
            if (contents.potion().isPresent()) {
                var potionEntry = contents.potion().get();
                for (var effect : potionEntry.value().getEffects()) {
                    if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                            && effect.getAmplifier() >= 1) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Cross-version safety
        }
        return false;
    }

    /**
     * Called from ClientWorldMixin when a potion entity spawns in the client world.
     */
    public static void onPotionSpawned(PotionEntity potionEntity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        PotCounterConfig config = PotCounterConfig.get();
        if (!config.enabled) return;

        int entityId = potionEntity.getId();
        if (!COUNTED_POTIONS.add(entityId)) return;

        if (!isInstantHealthTwo(potionEntity)) return;

        attributePotionThrow(client, potionEntity, config);
    }

    private static void attributePotionThrow(MinecraftClient client, PotionEntity potionEntity, PotCounterConfig config) {
        Vec3d potionPos = potionEntity.getPos();
        double closestDist = 20.0; // Larger range since potions travel
        PlayerEntity closest = null;

        for (PlayerEntity player : client.world.getPlayers()) {
            double dist = player.getPos().distanceTo(potionPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = player;
            }
        }

        if (closest != null) {
            UUID uuid = closest.getUuid();

            if (!config.includeSelfDisplay && client.player != null
                    && uuid.equals(client.player.getUuid())) {
                return;
            }

            COUNTS.merge(uuid, 1, Integer::sum);
        }
    }

    public static int getCount(UUID playerUuid) {
        return COUNTS.getOrDefault(playerUuid, 0);
    }

    /** Look up UUID from an entity network id. */
    public static UUID getUuidFromEntityId(int entityId) {
        return ID_TO_UUID.get(entityId);
    }

    /** Build the counter text with a potion icon from our custom font. */
    public static Text buildCounterText(int count) {
        Text potIcon = Text.literal(POT_ICON)
                .setStyle(Style.EMPTY.withFont(POT_FONT));
        return Text.literal(String.valueOf(count)).append(Text.literal(" ")).append(potIcon);
    }

    public static void clearAll() {
        COUNTS.clear();
        ID_TO_UUID.clear();
        COUNTED_POTIONS.clear();
    }
}
