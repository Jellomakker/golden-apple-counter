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

    /** Entity network ID -> UUID, populated each tick for the renderer mixin. */
    private static final Map<Integer, UUID> ID_TO_UUID = new ConcurrentHashMap<>();

    /** Entity IDs already processed this session (counted or rejected). */
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

        LOGGER.info("[PotCounter] Initialized (1.21.2-1.21.4)");
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null) return;

        if (resetKeybind != null) {
            while (resetKeybind.wasPressed()) {
                clearAll();
            }
        }

        PotCounterConfig config = PotCounterConfig.get();

        // Maintain entity-id -> UUID map for renderer mixin
        Set<UUID> active = new HashSet<>();
        for (PlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();
            active.add(uuid);
            ID_TO_UUID.put(player.getId(), uuid);
        }

        // Scan all entities for splash potions
        if (config.enabled) {
            for (Entity entity : client.world.getEntities()) {
                if (!(entity instanceof PotionEntity potionEntity)) continue;
                int entityId = potionEntity.getId();
                if (!COUNTED_POTIONS.add(entityId)) continue;
                if (!isInstantHealthTwo(potionEntity)) continue;
                attributePotionThrow(client, potionEntity, config);
            }
        }

        // Clean up COUNTED_POTIONS for entities that are gone
        COUNTED_POTIONS.removeIf(id -> {
            Entity e = client.world.getEntityById(id);
            return e == null || !e.isAlive();
        });

        COUNTS.keySet().retainAll(active);
        ID_TO_UUID.values().retainAll(active);
    }

    private boolean isInstantHealthTwo(PotionEntity potionEntity) {
        try {
            var stack = potionEntity.getStack();
            if (stack == null || stack.isEmpty()) return false;

            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) return false;

            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                        && effect.getAmplifier() >= 1) {
                    return true;
                }
            }

            if (contents.potion().isPresent()) {
                var potionEntry = contents.potion().get();
                for (var effect : potionEntry.value().getEffects()) {
                    if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                            && effect.getAmplifier() >= 1) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void attributePotionThrow(MinecraftClient client, PotionEntity potionEntity, PotCounterConfig config) {
        Vec3d potionPos = potionEntity.getPos();
        double closestDist = 20.0;
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
