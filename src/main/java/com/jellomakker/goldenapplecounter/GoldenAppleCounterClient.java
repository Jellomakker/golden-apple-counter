package com.jellomakker.goldenapplecounter;

import com.jellomakker.goldenapplecounter.config.GoldenAppleCounterConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GoldenAppleCounterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("goldenapplecounter");
    public static final String MOD_ID = "goldenapplecounter";

    public static final Identifier GOLDEN_APPLE_FONT = Identifier.of(MOD_ID, "golden_apple");
    public static final String GOLDEN_APPLE_ICON = "\uE200";

    private static final Map<UUID, Integer> COUNTS = new ConcurrentHashMap<>();

    /**
     * Per-player eating state tracking.
     * [0] = wasEating (0 or 1)
     * [1] = golden apple stack count in eating hand (baseline)
     * [2] = active hand ordinal (0=MAIN, 1=OFF)
     * [3] = grace ticks (ticks since eating stopped, for equipment sync delay)
     */
    private static final Map<UUID, int[]> EAT_STATE = new ConcurrentHashMap<>();

    /**
     * Track the golden apple stack count per player when they are NOT eating.
     * This lets us detect the first apple being consumed even if the stack
     * decrements before we see isUsingItem() go true.
     */
    private static final Map<UUID, int[]> IDLE_STACK = new ConcurrentHashMap<>();

    /** Entity network ID → UUID, populated each tick for the renderer mixin. */
    private static final Map<Integer, UUID> ID_TO_UUID = new ConcurrentHashMap<>();

    /** Ticks to wait after eating stops for the equipment update to arrive. */
    private static final int GRACE_TICKS = 6;

    private static KeyBinding resetKeybind;

    @Override
    public void onInitializeClient() {
        GoldenAppleCounterConfig.get();

        resetKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.goldenapplecounter.reset",
                GLFW.GLFW_KEY_UNKNOWN,
                "category.goldenapplecounter"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            COUNTS.clear();
            EAT_STATE.clear();
            IDLE_STACK.clear();
            ID_TO_UUID.clear();
        });

        LOGGER.info("[GoldenAppleCounter] Initialized (rendering via EntityRendererMixin)");
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null) return;

        // Check reset keybind
        if (resetKeybind != null) {
            while (resetKeybind.wasPressed()) {
                clearAll();
            }
        }

        GoldenAppleCounterConfig config = GoldenAppleCounterConfig.get();
        Set<UUID> active = new HashSet<>();

        for (PlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();
            active.add(uuid);
            ID_TO_UUID.put(player.getId(), uuid);

            if (!config.includeSelfDisplay && client.player != null
                    && uuid.equals(client.player.getUuid())) {
                continue;
            }

            // --- Golden apple tracking ---
            boolean eating = player.isUsingItem()
                    && isTrackedApple(player.getActiveItem().getItem(), config);

            int[] s = EAT_STATE.computeIfAbsent(uuid, k -> new int[]{0, 0, 0, 0});

            if (eating) {
                Hand hand = player.getActiveHand();
                int handCount = player.getStackInHand(hand).getCount();

                if (s[0] == 0) {
                    int[] idle = IDLE_STACK.get(uuid);
                    if (idle != null && idle[0] == hand.ordinal() && idle[1] > handCount) {
                        int missed = idle[1] - handCount;
                        for (int i = 0; i < missed; i++) increment(uuid);
                    }
                    s[0] = 1;
                    s[1] = handCount;
                    s[2] = hand.ordinal();
                    s[3] = 0;
                } else {
                    if (handCount < s[1]) {
                        increment(uuid);
                        s[1] = handCount;
                    }
                }
            } else {
                if (s[0] == 1) {
                    Hand hand = Hand.values()[s[2]];
                    Item handItem = player.getStackInHand(hand).getItem();
                    boolean stillHasApple = handItem == Items.GOLDEN_APPLE
                            || handItem == Items.ENCHANTED_GOLDEN_APPLE;
                    int handCount = stillHasApple ? player.getStackInHand(hand).getCount() : -1;

                    if (handCount >= 0 && handCount < s[1]) {
                        increment(uuid);
                        resetState(s);
                    } else if (!stillHasApple) {
                        if (s[1] == 1 || player.getStackInHand(hand).isEmpty()) {
                            increment(uuid);
                        }
                        resetState(s);
                    } else {
                        s[3]++;
                        if (s[3] > GRACE_TICKS) {
                            resetState(s);
                        }
                    }
                }

                updateIdleStack(uuid, player);
            }
        }

        COUNTS.keySet().retainAll(active);
        EAT_STATE.keySet().retainAll(active);
        IDLE_STACK.keySet().retainAll(active);
        ID_TO_UUID.values().retainAll(active);
    }

    private void updateIdleStack(UUID uuid, PlayerEntity player) {
        for (Hand hand : Hand.values()) {
            Item item = player.getStackInHand(hand).getItem();
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
                IDLE_STACK.put(uuid, new int[]{hand.ordinal(), player.getStackInHand(hand).getCount()});
                return;
            }
        }
        IDLE_STACK.remove(uuid);
    }

    private static void resetState(int[] s) {
        s[0] = 0;
        s[1] = 0;
        s[2] = 0;
        s[3] = 0;
    }

    private static boolean isTrackedApple(Item item, GoldenAppleCounterConfig config) {
        return (item == Items.GOLDEN_APPLE && config.countNormalGoldenApple)
                || (item == Items.ENCHANTED_GOLDEN_APPLE && config.countEnchantedGoldenApple);
    }

    public static void increment(UUID playerUuid) {
        COUNTS.merge(playerUuid, 1, Integer::sum);
    }

    public static int getCount(UUID playerUuid) {
        return COUNTS.getOrDefault(playerUuid, 0);
    }

    /** Look up UUID from an entity network id. */
    public static UUID getUuidFromEntityId(int entityId) {
        return ID_TO_UUID.get(entityId);
    }

    /** Build the counter text with a real golden apple icon from our custom font. */
    public static Text buildCounterText(int count) {
        Text appleIcon = Text.literal(GOLDEN_APPLE_ICON)
                .setStyle(Style.EMPTY.withFont(GOLDEN_APPLE_FONT));
        return Text.literal(String.valueOf(count)).append(Text.literal(" ")).append(appleIcon);
    }

    public static void clearAll() {
        COUNTS.clear();
        EAT_STATE.clear();
        IDLE_STACK.clear();
        ID_TO_UUID.clear();
    }
}
