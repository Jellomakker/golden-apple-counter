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

    private static final Set<Integer> PROCESSED_POTIONS = ConcurrentHashMap.newKeySet();

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
            PROCESSED_POTIONS.clear();
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

        Set<UUID> active = new HashSet<>();
        for (PlayerEntity player : client.world.getPlayers()) {
            active.add(player.getUuid());
        }
        COUNTS.keySet().retainAll(active);
    }

    /**
     * Called from ThrownItemEntityMixin when setItem() fires on a PotionEntity.
     * setItem() is invoked by the data tracker when the server’s tracker update
     * packet is processed – this is the exact moment item data becomes available.
     */
    public static void onPotionItemSet(PotionEntity potionEntity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        int eid = potionEntity.getId();

        var stack = potionEntity.getStack();
        if (stack == null || stack.isEmpty()) return;

        if (!PROCESSED_POTIONS.add(eid)) return;

        PotCounterConfig config = PotCounterConfig.get();
        if (!config.enabled) return;

        if (!isInstantHealthTwo(potionEntity)) return;

        attributePotionThrow(client, potionEntity, config);
    }

    private static boolean isInstantHealthTwo(PotionEntity potionEntity) {
        try {
            PotionContentsComponent contents =
                    potionEntity.getStack().get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) return false;

            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                        && effect.getAmplifier() >= 1) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void attributePotionThrow(MinecraftClient client, PotionEntity potionEntity,
                                              PotCounterConfig config) {
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

        if (closest == null) return;

        UUID uuid = closest.getUuid();
        if (!config.includeSelfDisplay && client.player != null
                && uuid.equals(client.player.getUuid())) {
            return;
        }
        COUNTS.merge(uuid, 1, Integer::sum);
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
    }
}
