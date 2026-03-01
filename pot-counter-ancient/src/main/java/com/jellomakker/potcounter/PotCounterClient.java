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

    /** Maximum ticks to retry reading a potion’s item data before giving up. */
    private static final int MAX_RETRY_TICKS = 20;

    private static final Map<UUID, Integer> COUNTS = new ConcurrentHashMap<>();

    /** Potion entity IDs that have been definitively processed (counted or rejected). */
    private static final Set<Integer> PROCESSED_POTIONS = ConcurrentHashMap.newKeySet();

    /**
     * Potions awaiting identification. The item data may not be synced yet when
     * ClientWorld.addEntity() fires, so we retry each tick until the data is
     * available or we exceed MAX_RETRY_TICKS.
     */
    private static final Map<Integer, PendingPotion> PENDING = new ConcurrentHashMap<>();

    private static KeyBinding resetKeybind;

    private static final class PendingPotion {
        final PotionEntity entity;
        final Vec3d spawnPos;
        int ticksWaited;

        PendingPotion(PotionEntity entity) {
            this.entity = entity;
            this.spawnPos = entity.getPos();
            this.ticksWaited = 0;
        }
    }

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
            PROCESSED_POTIONS.clear();
            PENDING.clear();
        });

        LOGGER.info("[PotCounter] Initialized");
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null) return;

        if (resetKeybind != null) {
            while (resetKeybind.wasPressed()) {
                clearAll();
            }
        }

        PotCounterConfig config = PotCounterConfig.get();

        // --- Process pending potions (retry until data available) ---
        if (!PENDING.isEmpty()) {
            Iterator<Map.Entry<Integer, PendingPotion>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, PendingPotion> entry = it.next();
                PendingPotion pp = entry.getValue();
                pp.ticksWaited++;

                int result = checkPotion(pp.entity);
                if (result == 1) {
                    PROCESSED_POTIONS.add(entry.getKey());
                    it.remove();
                    attributePotionThrow(client, pp.entity, pp.spawnPos, config);
                } else if (result == -1 || pp.ticksWaited >= MAX_RETRY_TICKS) {
                    PROCESSED_POTIONS.add(entry.getKey());
                    it.remove();
                }
            }
        }

        // --- Also scan world for any PotionEntity we may have missed ---
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PotionEntity potionEntity)) continue;
            int eid = potionEntity.getId();
            if (PROCESSED_POTIONS.contains(eid) || PENDING.containsKey(eid)) continue;

            int result = checkPotion(potionEntity);
            if (result == 1) {
                PROCESSED_POTIONS.add(eid);
                attributePotionThrow(client, potionEntity, potionEntity.getPos(), config);
            } else if (result == 0) {
                PENDING.put(eid, new PendingPotion(potionEntity));
            } else {
                PROCESSED_POTIONS.add(eid);
            }
        }

        Set<UUID> active = new HashSet<>();
        for (PlayerEntity player : client.world.getPlayers()) {
            active.add(player.getUuid());
        }

        COUNTS.keySet().retainAll(active);
    }

    /**
     * Check whether a PotionEntity is an Instant Health II splash potion.
     * @return 1 = yes, -1 = no (definitively not), 0 = data not available yet
     */
    private static int checkPotion(PotionEntity potionEntity) {
        try {
            var stack = potionEntity.getStack();
            if (stack == null || stack.isEmpty()) return 0;

            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) return -1;

            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                        && effect.getAmplifier() >= 1) {
                    return 1;
                }
            }

            if (contents.potion().isPresent()) {
                var potionEntry = contents.potion().get();
                for (var effect : potionEntry.value().getEffects()) {
                    if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                            && effect.getAmplifier() >= 1) {
                        return 1;
                    }
                }
            }

            return -1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Called from ClientWorldMixin when a potion entity spawns in the client world.
     * We queue it for tick-based processing because the item data (potion contents)
     * may not have been synced yet at this point.
     */
    public static void onPotionSpawned(PotionEntity potionEntity) {
        int eid = potionEntity.getId();
        if (PROCESSED_POTIONS.contains(eid) || PENDING.containsKey(eid)) return;
        PENDING.put(eid, new PendingPotion(potionEntity));
    }

    private static void attributePotionThrow(MinecraftClient client, PotionEntity potionEntity,
                                              Vec3d potionPos, PotCounterConfig config) {
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

    public static Text buildCounterText(int count) {
        Text potIcon = Text.literal(POT_ICON)
                .setStyle(Style.EMPTY.withFont(POT_FONT));
        return Text.literal(String.valueOf(count)).append(Text.literal(" ")).append(potIcon);
    }

    public static void clearAll() {
        COUNTS.clear();
        PROCESSED_POTIONS.clear();
        PENDING.clear();
    }
}
